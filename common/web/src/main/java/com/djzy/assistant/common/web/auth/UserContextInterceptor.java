package com.djzy.assistant.common.web.auth;

import com.djzy.assistant.common.error.UnifiedErrors;
import com.djzy.assistant.common.web.RequestAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * 统一身份拦截器：进控制器之前先验一次身份，验过就挂到当前线程上。
 *
 * <p>控制器从此不必再写「解析 Authorization 头 → 拿 userId」那段样板代码，
 * 只声明 {@code @CurrentUser} 参数即可；业务代码里只看得见 userId，看不见令牌。
 *
 * <p>清理时机（**必须**清，否则线程复用时用户会串味）：
 * <ul>
 *   <li>普通请求：{@code afterCompletion}；</li>
 *   <li>SSE 这类异步请求：{@code afterConcurrentHandlingStarted}——
 *       这时候请求线程马上要还回线程池，{@code afterCompletion} 还没轮到。</li>
 * </ul>
 */
public class UserContextInterceptor implements AsyncHandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(UserContextInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UserContextResolver resolver;

    public UserContextInterceptor(UserContextResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // 默认全都要身份（fail-closed）：只有明确打了 @AnonymousAccess 的接口才跳过
        if (handler instanceof HandlerMethod method && isAnonymous(method)) {
            return true;
        }
        Optional<UserContext> resolved = resolver.resolve(request);
        if (resolved.isEmpty()) {
            log.info("身份校验未通过，按 401 拒绝：method={} path={}", request.getMethod(), request.getRequestURI());
            writeUnauthorized(response);
            return false;
        }
        UserContext context = resolved.get();
        UserContextHolder.set(context);
        // 同一份上下文也放一份在请求属性上：过滤器 / 异步回调拿不到 ThreadLocal 时可以从这里取
        request.setAttribute(RequestAttributes.USER_CONTEXT, context);
        request.setAttribute(RequestAttributes.TRACE_ID, context.traceId());
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContextHolder.clear();
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserContextHolder.clear();
    }

    private static boolean isAnonymous(HandlerMethod method) {
        return method.hasMethodAnnotation(AnonymousAccess.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), AnonymousAccess.class);
    }

    /** 401 的对外措辞固定，不透露具体失败原因（§20.1.2）。 */
    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", UnifiedErrors.UNAUTHORIZED);
        body.put("code", "UNAUTHORIZED");
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.flushBuffer();
    }
}
