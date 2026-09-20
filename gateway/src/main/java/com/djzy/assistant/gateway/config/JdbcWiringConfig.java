package com.djzy.assistant.gateway.config;

import com.djzy.assistant.common.api.ApiRegistry;
import com.djzy.assistant.common.confirm.ConfirmStore;
import com.djzy.assistant.common.identity.UserStatusPort;
import com.djzy.assistant.common.permission.PermissionAuditWriter;
import com.djzy.assistant.common.permission.PermissionRepository;
import com.djzy.assistant.common.persistence.JdbcApiRegistry;
import com.djzy.assistant.common.persistence.JdbcConfirmStore;
import com.djzy.assistant.common.persistence.JdbcPermissionAuditWriter;
import com.djzy.assistant.common.persistence.JdbcPermissionRepository;
import com.djzy.assistant.common.persistence.JdbcUserStatusPort;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 骨架期直连 PG 的端口实现（§0.3-4 零缓存）。
 *
 * <p>测试可用 gateway.persistence=none 关掉这一组，改用内存替身。
 */
@Configuration
@ConditionalOnProperty(name = "gateway.persistence", havingValue = "jdbc", matchIfMissing = true)
public class JdbcWiringConfig {

    @Bean
    public PermissionRepository permissionRepository(DataSource dataSource) {
        return new JdbcPermissionRepository(dataSource);
    }

    @Bean
    public ApiRegistry apiRegistry(DataSource dataSource) {
        return new JdbcApiRegistry(dataSource);
    }

    @Bean
    public UserStatusPort userStatusPort(DataSource dataSource) {
        return new JdbcUserStatusPort(dataSource);
    }

    @Bean
    public PermissionAuditWriter permissionAuditWriter(DataSource dataSource) {
        return new JdbcPermissionAuditWriter(dataSource);
    }

    @Bean
    public ConfirmStore confirmStore(DataSource dataSource) {
        return new JdbcConfirmStore(dataSource);
    }
}
