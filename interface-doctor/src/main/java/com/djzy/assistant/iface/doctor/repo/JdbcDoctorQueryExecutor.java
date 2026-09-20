package com.djzy.assistant.iface.doctor.repo;

import com.djzy.assistant.iface.doctor.core.DoctorQuery;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC 实现：只执行 {@link DoctorQuery} 这种「SQL + 绑定值」形态，
 * 业务代码没有任何字符串拼接 SQL 的机会（§4.3 L2 防注入）。
 */
public final class JdbcDoctorQueryExecutor implements DoctorQueryExecutor {

    private final JdbcTemplate jdbc;

    public JdbcDoctorQueryExecutor(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcDoctorQueryExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Map<String, Object>> fetch(DoctorQuery query) {
        return jdbc.queryForList(query.sql(), query.arguments());
    }
}
