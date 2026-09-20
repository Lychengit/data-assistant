package com.djzy.assistant.management.audit;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * 只读连接包装（§18.4.6 平台元数据 / 审计出口）。
 *
 * <p>审计查询走**独立的只读出口**：不经 data-gateway、不套用户范围过滤。生产环境应配置
 * 专用只读账号（{@code management-service.read-only.*}）；未配置时退化为「主连接 + 逐连接
 * {@code setReadOnly(true)}」，让误写尽量早地失败，而不是悄悄写进去。
 *
 * <p>{@code setReadOnly} 是尽力而为：个别驱动/库不支持或忽略它，因此**代码层面也不写**
 * （本包只做 SELECT），两层加起来才是「只读出口」。
 */
public final class ReadOnlyDataSource extends DelegatingDataSource {

    public ReadOnlyDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return readOnly(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return readOnly(super.getConnection(username, password));
    }

    private static Connection readOnly(Connection connection) {
        try {
            connection.setReadOnly(true);
        } catch (SQLException e) {
            // 驱动不支持就让上层 SQL 兜底：审计出口的语句本身全是 SELECT
        }
        return connection;
    }
}
