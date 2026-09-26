package com.djzy.assistant.management.config;

import com.djzy.assistant.common.audit.AuditReadWriter;
import com.djzy.assistant.common.config.ConfigAuditWriter;
import com.djzy.assistant.management.audit.AuditQueryRepository;
import com.djzy.assistant.management.audit.JdbcAuditQueryRepository;
import com.djzy.assistant.management.audit.ReadOnlyDataSource;
import com.djzy.assistant.management.repo.ApiAdminRepository;
import com.djzy.assistant.management.repo.JdbcApiAdminRepository;
import com.djzy.assistant.management.repo.JdbcMetricAdminRepository;
import com.djzy.assistant.management.repo.MetricAdminRepository;
import com.djzy.assistant.common.persistence.JdbcAuditReadWriter;
import com.djzy.assistant.common.identity.JwtTokenService;
import com.djzy.assistant.common.identity.UserAccountPort;
import com.djzy.assistant.common.identity.UserStatusPort;
import com.djzy.assistant.common.identity.RefreshTokenStore;
import com.djzy.assistant.common.permission.AuthorizationService;
import com.djzy.assistant.common.permission.PermissionRepository;
import com.djzy.assistant.common.persistence.JdbcConfigAuditWriter;
import com.djzy.assistant.common.persistence.JdbcPermissionRepository;
import com.djzy.assistant.common.persistence.JdbcRefreshTokenStore;
import com.djzy.assistant.common.persistence.JdbcUserAccountPort;
import com.djzy.assistant.common.persistence.JdbcUserStatusPort;
import com.djzy.assistant.management.repo.JdbcRoleApiAdminRepository;
import com.djzy.assistant.management.repo.RoleApiAdminRepository;
import com.djzy.assistant.common.llm.ApiKeyCipher;
import com.djzy.assistant.common.persistence.JdbcLlmProviderStore;
import com.djzy.assistant.common.storage.s3.ObjectStorageConfig;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 管理后台装配：令牌签发/校验（M1）、授权判定（复用 common 唯一实现）、配置读写（M2）+ 配置审计（§20.7）。
 *
 * <p>平台元数据出口**直连 PG**（§18.4.6）：角色、接口、口径不挂在业务对象树上，没有 scope 可算，
 * 因此不经过网关、不套用户范围过滤；但**判定函数仍只有 common 一份**（不重写一套）。
 */
/** 对象存储装配（object-storage.provider 决定用本机文件系统还是 S3 兼容实现）随本配置一起生效。 */
@Configuration
@Import(ObjectStorageConfig.class)
public class ManagementConfig {

    private static final Logger log = LoggerFactory.getLogger(ManagementConfig.class);

    /** 令牌签发与校验同一实现（网关用同一密钥校验，§20.1.1）。 */
    @Bean
    public JwtTokenService tokenService(ManagementProperties properties) {
        return new JwtTokenService(
                StartupSecretCheck.resolverOf(properties), properties.getTokenKeyId(), properties.getTokenTtl());
    }

    @Bean
    public AuthorizationService authorizationService(PermissionRepository permissionRepository) {
        return new AuthorizationService(permissionRepository);
    }

    @Bean
    public PermissionRepository permissionRepository(DataSource dataSource) {
        return new JdbcPermissionRepository(dataSource);
    }

    @Bean
    public UserAccountPort userAccountPort(DataSource dataSource) {
        return new JdbcUserAccountPort(dataSource);
    }

    @Bean
    public UserStatusPort userStatusPort(DataSource dataSource) {
        return new JdbcUserStatusPort(dataSource);
    }

    @Bean
    public RefreshTokenStore refreshTokenStore(DataSource dataSource) {
        return new JdbcRefreshTokenStore(dataSource);
    }

    @Bean
    public RoleApiAdminRepository roleApiAdminRepository(DataSource dataSource) {
        return new JdbcRoleApiAdminRepository(dataSource);
    }

    @Bean
    public ConfigAuditWriter configAuditWriter(DataSource dataSource) {
        return new JdbcConfigAuditWriter(dataSource);
    }

    @Bean
    public ApiAdminRepository apiAdminRepository(DataSource dataSource) {
        return new JdbcApiAdminRepository(dataSource);
    }

    @Bean
    public MetricAdminRepository metricAdminRepository(DataSource dataSource) {
        return new JdbcMetricAdminRepository(dataSource);
    }

    /**
     * 模型 API Key 的加解密（§20.1.6）。
     *
     * <p>没配 KEK 时**不抛**，而是给一个「一用就报错」的实现：列表/查看看的是 {@code key_hint}，
     * 本来就不需要解密，凭什么叫人连界面都打不开？真正需要加密能力的写路径才失败，
     * 且失败信息直接告诉运维该配哪个变量、怎么生成。
     */
    @Bean
    public ApiKeyCipher apiKeyCipher(ManagementProperties properties) {
        String kek = properties.getLlmKek();
        if (kek == null || kek.isBlank()) {
            log.warn("未配置 management-service.llm-kek：模型 API Key 将无法保存（查看不受影响）。"
                    + "生成方式：openssl rand -base64 32");
        }
        return ApiKeyCipher.fromBase64OrUnconfigured(kek, "management-service.llm-kek");
    }

    /** 模型供应商配置存取（ADR-14）：管理端写、agent-service 读，共用同一份 SQL。 */
    @Bean
    public JdbcLlmProviderStore llmProviderStore(DataSource dataSource, ApiKeyCipher apiKeyCipher) {
        return new JdbcLlmProviderStore(dataSource, apiKeyCipher);
    }

    /** M3 技能包管理（§18.4.6）：版本台账 + 内容寻址的技能包存储（落在 ObjectStorage 端口上，本机文件系统 / 生产对象存储）。 */
    @Bean
    public com.djzy.assistant.management.repo.SkillPackageRepository skillPackageRepository(
            DataSource dataSource) {
        return new com.djzy.assistant.management.repo.JdbcSkillPackageRepository(dataSource);
    }

    @Bean
    public com.djzy.assistant.management.skill.SkillPackageStore skillPackageStore(
            com.djzy.assistant.common.storage.ObjectStorage objectStorage) {
        return new com.djzy.assistant.management.skill.ObjectStorageSkillPackageStore(objectStorage);
    }

    /** 审计读的记录写入走**可写**连接（§20.4）。 */
    @Bean
    public AuditReadWriter auditReadWriter(DataSource dataSource) {
        return new JdbcAuditReadWriter(dataSource);
    }

    /**
     * 审计查询走**只读出口**（§18.4.6 / §20.4）：配置了专用只读账号就用它，
     * 否则退化为「主连接 + 只读会话」。
     */
    @Bean
    public AuditQueryRepository auditQueryRepository(DataSource dataSource, ManagementProperties properties) {
        return new JdbcAuditQueryRepository(readOnlyDataSource(dataSource, properties.getReadOnly()));
    }

    private static DataSource readOnlyDataSource(DataSource primary, ManagementProperties.ReadOnly readOnly) {
        if (readOnly == null || !readOnly.isConfigured()) {
            return new ReadOnlyDataSource(primary);
        }
        com.zaxxer.hikari.HikariDataSource audit = new com.zaxxer.hikari.HikariDataSource();
        audit.setPoolName("audit-read-only");
        audit.setJdbcUrl(readOnly.getUrl());
        audit.setUsername(readOnly.getUsername());
        audit.setPassword(readOnly.getPassword());
        audit.setReadOnly(true);
        audit.setMaximumPoolSize(4);
        audit.setMinimumIdle(1);
        return new ReadOnlyDataSource(audit);
    }
}
