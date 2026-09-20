package com.djzy.assistant.management;

import com.djzy.assistant.common.audit.AuditReadWriter;
import com.djzy.assistant.common.config.ConfigAuditWriter;
import com.djzy.assistant.common.persistence.JdbcAuditReadWriter;
import com.djzy.assistant.common.persistence.JdbcConfigAuditWriter;
import com.djzy.assistant.management.repo.ApiAdminRepository;
import com.djzy.assistant.management.repo.JdbcApiAdminRepository;
import com.djzy.assistant.management.repo.JdbcMetricAdminRepository;
import com.djzy.assistant.management.repo.JdbcRoleApiAdminRepository;
import com.djzy.assistant.management.repo.JdbcSkillPackageRepository;
import com.djzy.assistant.management.repo.MetricAdminRepository;
import com.djzy.assistant.management.repo.RoleApiAdminRepository;
import com.djzy.assistant.management.repo.SkillPackageRepository;
import com.djzy.assistant.management.skill.SkillPackageStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试基座：演示账号 + H2 方言替身。
 *
 * <p>若干处 H2 适配（PG 的 {@code ?::jsonb} 在 H2 里会把值再包一层引号，见 {@code H2AuditConfig} 同类说明）：
 * 配置审计写入、审计读记录、{@code role_api} / {@code sys_api} / {@code metric_dictionary} 各换一份不带 cast 的 SQL。
 * 审计查询只读、不写 jsonb，用生产实现即可。
 */
public final class ManagementTestSupport {

    public static final String jwtSecret = "management-jwt-test-secret";
    /** 模型 API Key 的测试用加密根密钥（32 字节的 Base64）；生产由 MANAGEMENT_LLM_KEK 注入。 */
    public static final String LLM_KEK = "Bw4VHCMqMTg/Rk1UW2JpcHd+hYyTmqGor7a9xMvS2eA=";
    public static final String ADMIN = "admin";
    public static final String ALICE = "alice";
    public static final String ADMIN_PASSWORD = "admin123";
    public static final String ALICE_PASSWORD = "alice123";

    private ManagementTestSupport() {}

    @TestConfiguration
    public static class H2DialectConfig {

        private static final String H2_INSERT = """
                INSERT INTO config_audit (who, target, field, before, after, request_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        private static final String H2_AUDIT_READ_INSERT = """
                INSERT INTO audit_read_audit (who, action, params, outcome, row_count, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        @Bean
        @Primary
        public ConfigAuditWriter h2ConfigAuditWriter(DataSource dataSource) {
            return new JdbcConfigAuditWriter(new JdbcTemplate(dataSource), H2_INSERT);
        }

        @Bean
        @Primary
        public RoleApiAdminRepository h2RoleApiAdminRepository(DataSource dataSource) {
            return new JdbcRoleApiAdminRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @Primary
        public ApiAdminRepository h2ApiAdminRepository(DataSource dataSource) {
            return new JdbcApiAdminRepository(new JdbcTemplate(dataSource), false);
        }

        @Bean
        @Primary
        public MetricAdminRepository h2MetricAdminRepository(DataSource dataSource) {
            return new JdbcMetricAdminRepository(new JdbcTemplate(dataSource), false);
        }

        @Bean
        @Primary
        public AuditReadWriter h2AuditReadWriter(DataSource dataSource) {
            return new JdbcAuditReadWriter(new JdbcTemplate(dataSource), H2_AUDIT_READ_INSERT);
        }

        /** M3：H2 没有 jsonb，manifest / exported_columns 按纯文本列处理（{@code jsonbCast=false}）。 */
        @Bean
        @Primary
        public SkillPackageRepository h2SkillPackageRepository(DataSource dataSource) {
            return new JdbcSkillPackageRepository(new JdbcTemplate(dataSource), false);
        }

        /** M3：技能包落临时目录，测完即弃（内容寻址，同名即同对象）。 */
        @Bean
        @Primary
        public SkillPackageStore h2SkillPackageStore() {
            try {
                return new SkillPackageStore.Local(Files.createTempDirectory("skill-package-test"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
