package com.djzy.assistant.gateway.config;

import com.djzy.assistant.common.identity.JwtTokenService;
import com.djzy.assistant.common.identity.UserStatusPort;
import com.djzy.assistant.common.identity.UserTokenVerifier;
import com.djzy.assistant.common.permission.AuthorizationService;
import com.djzy.assistant.common.permission.PermissionAuditWriter;
import com.djzy.assistant.common.permission.PermissionRepository;
import com.djzy.assistant.common.persistence.RedisNonceStore;
import com.djzy.assistant.common.security.InMemoryNonceStore;
import com.djzy.assistant.common.security.NonceStore;
import com.djzy.assistant.common.security.ServiceCredential;
import com.djzy.assistant.common.security.ServiceVerifier;
import com.djzy.assistant.gateway.core.CircuitBreakerRegistry;
import com.djzy.assistant.gateway.core.DownstreamClient;
import com.djzy.assistant.gateway.core.HttpDownstreamClient;
import com.djzy.assistant.gateway.core.InMemoryUserRateLimiter;
import com.djzy.assistant.gateway.core.RedisUserRateLimiter;
import com.djzy.assistant.gateway.core.ResponseTruncator;
import com.djzy.assistant.gateway.core.ServiceRouter;
import com.djzy.assistant.gateway.core.UserRateLimiter;
import com.djzy.assistant.common.web.SecurityAuditLog;
import com.djzy.assistant.common.web.SignatureVerificationFilter;
import com.djzy.assistant.common.web.auth.JwtUserContextResolver;
import com.djzy.assistant.common.web.auth.UserContextResolver;
import com.djzy.assistant.common.web.auth.UserContextWebConfigurer;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 网关装配：G1 验签、G3 判定、G2 转发、G4 保护限额（§18.4.2）。 */
@Configuration
public class GatewayConfig {

    /**
     * §20.1.4-3：**默认就是 Redis**——多副本下只有共享存储才能识别出「这个 nonce 已经用过」。
     * 显式选 {@code gateway.nonce-store=memory} 才退回单实例内存（本机开发退路，多副本不完整）。
     * 选了 redis 却没有 StringRedisTemplate → 直接拒绝启动，不静默降级。
     */
    @Bean
    public NonceStore nonceStore(GatewayProperties properties, ObjectProvider<StringRedisTemplate> redisProvider) {
        if ("redis".equalsIgnoreCase(properties.getNonceStore())) {
            StringRedisTemplate template = redisProvider.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException("gateway.nonce-store=redis 但容器中没有 StringRedisTemplate（§20.1.4）");
            }
            return new RedisNonceStore(template);
        }
        return new InMemoryNonceStore();
    }

    @Bean
    public ServiceVerifier serviceVerifier(GatewayProperties properties, NonceStore nonceStore) {
        return StartupSecretCheck.verifierOf(properties, nonceStore);
    }

    @Bean
    public UserTokenVerifier userTokenVerifier(GatewayProperties properties) {
        return new JwtTokenService(
                StartupSecretCheck.resolverOf(properties), properties.getTokenKeyId(), properties.getTokenTtl());
    }

    /**
     * 网关的身份解析口子：「验登录令牌 + 查账号状态」。
     *
     * <p>它把原来散在各个控制器里的那段样板代码收到一处；控制器只声明 {@code @CurrentUser} 参数。
     */
    @Bean
    public UserContextResolver gatewayUserContextResolver(
            UserTokenVerifier tokenVerifier, UserStatusPort userStatusPort) {
        return new JwtUserContextResolver(tokenVerifier, userStatusPort);
    }

    /**
     * 装上身份拦截器 + {@code @CurrentUser} 参数解析器；网关只服务 {@code /v1/**}，所以只管这一段。
     *
     * <p>与 {@link SignatureVerificationFilter} 的分工：验签回答「哪个服务在调我」，
     * 这里回答「哪个用户在调我」，两者不重叠。
     */
    @Bean
    public UserContextWebConfigurer gatewayUserContextWebConfigurer(UserContextResolver resolver) {
        return new UserContextWebConfigurer(resolver, List.of("/v1/**"));
    }

    @Bean
    public AuthorizationService authorizationService(PermissionRepository permissionRepository) {
        return new AuthorizationService(permissionRepository);
    }

    /**
     * §2.3 / §9.3：限速计数一律放 Redis，多副本才共享同一口径；{@code memory} 只留给单副本。
     * 选了 redis 却没有 StringRedisTemplate → 直接拒绝启动，不静默退回本地计数（那会把限额悄悄放大成
     * 「副本数 × qps」，正是 §2.3 要根治的隐性单点）。
     */
    @Bean
    public UserRateLimiter userRateLimiter(
            GatewayProperties properties, ObjectProvider<StringRedisTemplate> redisProvider) {
        if ("redis".equalsIgnoreCase(properties.getRateLimitStore())) {
            StringRedisTemplate template = redisProvider.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException("gateway.rate-limit-store=redis 但容器中没有 StringRedisTemplate（§2.3）");
            }
            return new RedisUserRateLimiter(template, properties.getPerUserQps());
        }
        return new InMemoryUserRateLimiter(properties.getPerUserQps());
    }

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(GatewayProperties properties) {
        return new CircuitBreakerRegistry(properties.getCircuitFailureThreshold(), properties.getCircuitOpenDuration());
    }

    @Bean
    public ResponseTruncator responseTruncator(GatewayProperties properties) {
        return new ResponseTruncator(properties.getMaxResponseBytes());
    }

    @Bean
    public ServiceRouter serviceRouter(GatewayProperties properties) {
        return new ServiceRouter(properties);
    }

    @Bean
    public ServiceCredential outboundCredential(GatewayProperties properties) {
        return new ServiceCredential(
                properties.getOutboundKeyId(),
                properties.getSecrets().getOrDefault(properties.getOutboundKeyId(), ""));
    }

    @Bean
    public DownstreamClient downstreamClient(
            ServiceRouter serviceRouter, ServiceCredential outboundCredential, GatewayProperties properties) {
        return new HttpDownstreamClient(serviceRouter, outboundCredential, properties);
    }

    @Bean
    public FilterRegistrationBean<SignatureVerificationFilter> inboundSignatureFilter(
            ServiceVerifier serviceVerifier, SecurityAuditLog securityAuditLog) {
        FilterRegistrationBean<SignatureVerificationFilter> registration = new FilterRegistrationBean<>(
                new SignatureVerificationFilter(serviceVerifier, securityAuditLog, "data-gateway", "/v1/"));
        registration.addUrlPatterns("/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("inboundSignatureFilter");
        return registration;
    }

    /** G1 验签失败的审计：直写 PG permission_audit（§0.3-3 / §20.1.4-7）。 */
    @Bean
    public SecurityAuditLog gatewaySecurityAuditLog(PermissionAuditWriter auditWriter) {
        return failure -> {
            java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
            snapshot.put("peer", failure.peer());
            snapshot.put("keyId", failure.keyId());
            snapshot.put("timestamp", failure.timestampHeader());
            snapshot.put("path", failure.path());
            snapshot.put("reason", failure.reasonIfPresent().map(Enum::name).orElse("UNKNOWN"));
            auditWriter.write(new com.djzy.assistant.common.permission.PermissionAuditEntry(
                    "signature-verify",
                    null,
                    java.util.List.of(),
                    "signature-verify",
                    com.djzy.assistant.common.permission.PermissionDecision.DENY,
                    String.valueOf(snapshot.get("reason")),
                    snapshot,
                    failure.occurredAt()));
        };
    }
}
