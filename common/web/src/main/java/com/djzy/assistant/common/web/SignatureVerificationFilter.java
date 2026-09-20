package com.djzy.assistant.common.web;

import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.common.security.SecurityFailure;
import com.djzy.assistant.common.security.ServiceVerifier;
import com.djzy.assistant.common.security.SignatureHeaders;
import com.djzy.assistant.common.security.VerificationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 服务间验签过滤器（G1 / I1，§20.1.4）：API Key + HMAC-SHA256 + 时间窗 ±300s + nonce 一次性。
 *
 * <p>失败一律统一 401 措辞（不区分 key 不存在 / 签名不对 / 重放，防探测），
 * 原因只进审计与告警；不得降级放行。
 */
public final class SignatureVerificationFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ServiceVerifier verifier;
    private final SecurityAuditLog auditLog;
    private final String serviceName;
    private final String pathPrefix;

    public SignatureVerificationFilter(
            ServiceVerifier verifier, SecurityAuditLog auditLog, String serviceName, String pathPrefix) {
        this.verifier = verifier;
        this.auditLog = auditLog;
        this.serviceName = serviceName;
        this.pathPrefix = pathPrefix;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(pathPrefix);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CachedBodyRequest cached = new CachedBodyRequest(request);
        VerificationResult result = verifier.verify(
                collectHeaders(cached),
                cached.getMethod(),
                cached.getRequestURI(),
                queryParams(cached),
                cached.bodyAsString(),
                Instant.now().getEpochSecond());
        if (!result.verified()) {
            recordFailure(cached, result);
            writeUnauthorized(response);
            return;
        }
        cached.setAttribute(RequestAttributes.CALLER_KEY_ID, result.keyId());
        chain.doFilter(cached, response);
    }

    private void recordFailure(CachedBodyRequest request, VerificationResult result) {
        try {
            auditLog.record(new VerificationFailure(
                    result.keyId(),
                    result.failureIfPresent().orElse(SecurityFailure.SIGNATURE_MISMATCH),
                    request.getRequestURI(),
                    request.getRemoteAddr(),
                    request.getHeader(SignatureHeaders.TIMESTAMP),
                    serviceName,
                    Instant.now()));
        } catch (RuntimeException e) {
            logger.error("验签失败审计写入异常", e);
        }
    }

    private static Map<String, String> collectHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    private static Map<String, List<String>> queryParams(HttpServletRequest request) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> query.put(name, List.of(values)));
        return query;
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(Map.of("error", UnifiedErrors.UNAUTHORIZED));
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
