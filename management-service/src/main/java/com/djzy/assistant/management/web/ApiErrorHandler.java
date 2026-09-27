package com.djzy.assistant.management.web;

import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.common.skill.PackageReader;
import com.djzy.assistant.management.service.ForbiddenException;
import com.djzy.assistant.management.service.UnauthorizedException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 管理后台统一错误出口（§4.5 / §11.3）：对外只有统一措辞，细节只进日志。
 *
 * <p>唯一的例外是「用户传上来的东西不对」（技能包不合法 / 漏了 file 字段）：这类要说出具体原因——
 * 用户唯一的出路是改完重传，含糊的措辞只会让他重试一个永远不会好的请求。
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", UnifiedErrors.UNAUTHORIZED));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(ForbiddenException e) {
        log.warn("管理入口权限不足（骨架期只有 admin 可进，§20.4）");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", UnifiedErrors.FORBIDDEN));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        log.warn("请求不合法：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", UnifiedErrors.INVALID_REQUEST));
    }

    /**
     * 参数缺失 / 类型不对（如 {@code ?enabled=abc}、漏传 {@code from}）→ 400。
     *
     * <p>这两类默认会被下面的兜底当成 500，把「调用方写错了」误报成「服务坏了」，也会污染错误率指标。
     */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> badParameter(Exception e) {
        log.warn("请求参数不合法：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", UnifiedErrors.INVALID_REQUEST));
    }

    /**
     * 技能包本身不合法（不是 zip / 找不到 manifest.json / 条目路径越界）→ 400，并把**具体原因**说给上传的人。
     *
     * <p>为什么不套统一措辞：这是「你传的文件不对」，用户唯一的出路是改包重传。回一句「服务暂不可用，
     * 请稍后再试」等于让人去重试一个永远不会好的请求（2026-09-27 实测：Windows「压缩文件夹」把
     * manifest.json 裹进一层目录，被下面的兜底报成 500）。
     */
    @ExceptionHandler(PackageReader.InvalidPackageException.class)
    public ResponseEntity<Map<String, String>> invalidPackage(PackageReader.InvalidPackageException e) {
        log.warn("技能包不合法（{}）：{}", e.code(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "技能包无法上传：" + e.getMessage(), "code", e.code()));
    }

    /** 表单里没有 file 字段（调用方写错了）→ 400，同样不该报成「服务坏了」。 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, String>> missingPart(MissingServletRequestPartException e) {
        log.warn("上传缺少文件字段：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", UnifiedErrors.INVALID_REQUEST));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internal(Exception e) {
        log.error("管理后台内部错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", UnifiedErrors.SERVICE_UNAVAILABLE));
    }
}
