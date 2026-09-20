package com.djzy.assistant.agentweb.web;

import com.djzy.assistant.common.error.UnifiedErrors;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 接入层统一错误处理（§11.3）：对外只给统一措辞 + 稳定错误码，内部细节只进日志。
 *
 * <p>401 / 403 / 404 一律不区分原因：越权提问不得因为错误信息不同而被探测出「数据是否存在」。
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        if (e.status().is5xxServerError()) {
            log.error("接入层错误：{}", e.code(), e);
        } else {
            log.info("接入层拒绝：status={} code={}", e.status().value(), e.code());
        }
        return body(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        log.info("请求参数不合法：{}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", UnifiedErrors.INVALID_REQUEST);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Throwable e) {
        log.error("未预期异常", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", UnifiedErrors.SERVICE_UNAVAILABLE);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", code);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
