package com.djzy.assistant.common.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** H2（PostgreSQL 兼容模式）测试基座：同一份列名，验证 SQL 与判定口径。 */
final class PersistenceTestSupport {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private PersistenceTestSupport() {}

    static DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:persist-" + COUNTER.incrementAndGet() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        try (Connection connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-h2.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException("初始化测试 schema 失败", e);
        }
        return ds;
    }

    static JdbcTemplate template(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
