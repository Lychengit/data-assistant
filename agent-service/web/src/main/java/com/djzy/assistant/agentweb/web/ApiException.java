package com.djzy.assistant.agentweb.web;

import com.djzy.assistant.common.error.UnifiedErrors;
import org.springframework.http.HttpStatus;

/** 接入层统一错误（§11.3 错误统一）：对外只给统一措辞，细节只进日志与审计。 */
public final class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", UnifiedErrors.UNAUTHORIZED);
    }

    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", UnifiedErrors.FORBIDDEN);
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", UnifiedErrors.NOT_FOUND_OR_FORBIDDEN);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(
                HttpStatus.BAD_REQUEST, "BAD_REQUEST", message == null ? UnifiedErrors.INVALID_REQUEST : message);
    }

    public static ApiException rateLimited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", UnifiedErrors.RATE_LIMITED);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", UnifiedErrors.SERVICE_UNAVAILABLE);
    }
}
