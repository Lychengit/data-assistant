package com.djzy.assistant.gateway.config;

import com.djzy.assistant.common.identity.JwtTokenService;
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
import com.djzy.assistant.gateway.core.ResponseTruncator;
import com.djzy.assistant.gateway.core.ServiceRouter;
import com.djzy.assistant.gateway.core.UserRateLimiter;
import com.djzy.assistant.common.web.SecurityAuditLog;
import com.djzy.assistant.common.web.SignatureVerificationFilter;
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
     * §20.1.4-3：默认内存实现只适用于单实例；多副本必须显式选 gateway.nonce-store=redis，
     * 否则防重放不完整——这里宁可直接拒绝启动，也不静默降级。
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

    @Bean
    public AuthorizationService authorizationService(PermissionRepository permissionRepository) {
        return new AuthorizationService(permissionRepository);
    }

    @Bean
    public UserRateLimiter userRateLimiter(GatewayProperties properties) {
        return new UserRateLimiter(properties.getPerUserQps());
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
