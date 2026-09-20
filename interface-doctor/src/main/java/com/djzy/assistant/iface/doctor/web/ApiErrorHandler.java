package com.djzy.assistant.iface.doctor.web;

import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.iface.doctor.service.WriteConfirmationRequiredException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一错误出口（§4.5 / §11.3）：对外只有统一措辞，内部细节只进日志与审计。
 *
 * <p>两类失败的措辞刻意不同：**信封不可用**（解析不出 caller）按 403 fail-closed——
 * 拿不准是谁在调，就不能当成普通的"参数写错了"；**参数不合规**按 400，因为身份是确定的，
 * 只是这次调用有问题。
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    /** I2：写操作缺少确认凭据 → 403（W1，§19.9）。 */
    @ExceptionHandler(WriteConfirmationRequiredException.class)
    public ResponseEntity<Map<String, String>> confirmRequired(WriteConfirmationRequiredException e) {
        log.warn("写操作缺少确认凭据：path={}", e.httpPath());
        return forbidden();
    }

    /** I2：信封不可解析（缺 caller / 结构不对）→ 403 fail-closed，绝不退化成"匿名可访问"。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableEnvelope(HttpMessageNotReadableException e) {
        log.warn("网关下发信封不可解析（fail-closed）：{}", e.getMostSpecificCause().getMessage());
        return forbidden();
    }

    /** I3：DTO 约束不满足（必填缺失 / 格式不对 / 出现未声明的字段）→ 400 统一措辞。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalidArguments(MethodArgumentNotValidException e) {
        log.warn("参数校验失败：{}", e.getBindingResult().getFieldErrors());
        return badRequest();
    }

    /** I3：业务校验失败（指标不在口径字典里、参数组合不合法…）→ 400 统一措辞。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        log.warn("请求不合法：{}", e.getMessage());
        return badRequest();
    }

    /** I0：路径本身没注册 → 404。启动自检已经保证"注册的都有实现"，走到这里说明网关发错了目标。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NoResourceFoundException e) {
        log.warn("接口服务上没有这个路径：{}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", UnifiedErrors.NOT_FOUND_OR_FORBIDDEN));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internal(Exception e) {
        log.error("接口服务内部错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", UnifiedErrors.SERVICE_UNAVAILABLE));
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", UnifiedErrors.FORBIDDEN));
    }

    private static ResponseEntity<Map<String, String>> badRequest() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", UnifiedErrors.INVALID_REQUEST));
    }
}