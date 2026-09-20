package com.djzy.assistant.iface.doctor.config;

import com.djzy.assistant.common.audit.DataAccessAuditWriter;
import com.djzy.assistant.common.persistence.JdbcDataAccessAuditWriter;
import com.djzy.assistant.common.persistence.RedisNonceStore;
import com.djzy.assistant.common.security.InMemoryNonceStore;
import com.djzy.assistant.common.security.NonceStore;
import com.djzy.assistant.common.security.ServiceVerifier;
import com.djzy.assistant.common.security.StaticSecretResolver;
import com.djzy.assistant.common.web.LoggingSecurityAuditLog;
import com.djzy.assistant.common.web.SecurityAuditLog;
import com.djzy.assistant.common.web.SignatureVerificationFilter;
import com.djzy.assistant.iface.doctor.repo.DoctorQueryExecutor;
import com.djzy.assistant.iface.doctor.repo.JdbcDoctorQueryExecutor;
import com.djzy.assistant.iface.doctor.repo.JdbcMetricDictionary;
import com.djzy.assistant.iface.doctor.repo.MetricDictionary;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 接口服务装配：I1 验签（网关唯一调用方）、I3–I5 参数校验 + 参数化执行、I6 直写审计（§18.4.5 / §20.1）。
 *
 * <p>这里**没有**权限库、没有网关回调、没有身份解析器：接口服务只使用网关验签后下发的 userId（§20.1.6-6）。
 *
 * <p>也没有"接口目录"bean：每个接口自己声明路径与入参 DTO，启动时由
 * {@code RegisteredRouteCatalog} 与 {@code sys_api} 对账。以前那份本地目录是第二份清单，
 * 与注册表之间没有任何东西保证一致。
 */
@Configuration
public class InterfaceDoctorConfig {

    /**
     * §20.1.4-3：默认内存实现只适用于单实例；多副本必须显式选 interface-doctor.nonce-store=redis，
     * 否则防重放不完整——宁可直接拒绝启动，也不静默降级。
     */
    @Bean
    public NonceStore nonceStore(DoctorInterfaceProperties properties, ObjectProvider<StringRedisTemplate> redisProvider) {
        if ("redis".equalsIgnoreCase(properties.getNonceStore())) {
            StringRedisTemplate template = redisProvider.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException(
                        "interface-doctor.nonce-store=redis 但容器中没有 StringRedisTemplate（§20.1.4）");
            }
            return new RedisNonceStore(template);
        }
        return new InMemoryNonceStore();
    }

    @Bean
    public ServiceVerifier serviceVerifier(DoctorInterfaceProperties properties, NonceStore nonceStore) {
        return new ServiceVerifier(StaticSecretResolver.of(properties.getAllowedCallers()), nonceStore);
    }

    @Bean
    public SecurityAuditLog securityAuditLog() {
        return new LoggingSecurityAuditLog("interface-doctor");
    }

    /**
     * I1：业务接口前缀下的全部请求先验签；失败统一 401，原因只进审计与告警（§20.1.4）。
     *
     * <p>范围取自 {@code interface-doctor.api-prefix}，而启动自检会拒绝任何落在该前缀之外的注册接口——
     * 所以"加了接口但忘了改这里的 urlPatterns"不会变成一个静默裸奔的端点，而是启动失败。
     */
    @Bean
    public FilterRegistrationBean<SignatureVerificationFilter> inboundSignatureFilter(
            ServiceVerifier serviceVerifier, SecurityAuditLog securityAuditLog, DoctorInterfaceProperties properties) {
        String prefix = properties.getApiPrefix();
        FilterRegistrationBean<SignatureVerificationFilter> registration = new FilterRegistrationBean<>(
                new SignatureVerificationFilter(serviceVerifier, securityAuditLog, "interface-doctor", prefix + "/"));
        registration.addUrlPatterns(prefix + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("inboundSignatureFilter");
        return registration;
    }

    @Bean
    public MetricDictionary metricDictionary(DataSource dataSource) {
        return new JdbcMetricDictionary(dataSource);
    }

    @Bean
    public DoctorQueryExecutor doctorQueryExecutor(DataSource dataSource) {
        return new JdbcDoctorQueryExecutor(dataSource);
    }

    @Bean
    public DataAccessAuditWriter dataAccessAuditWriter(DataSource dataSource) {
        return new JdbcDataAccessAuditWriter(dataSource);
    }
}