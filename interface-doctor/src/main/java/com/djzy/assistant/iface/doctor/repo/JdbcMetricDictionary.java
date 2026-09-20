package com.djzy.assistant.iface.doctor.repo;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link MetricDictionary} 的 PG 实现（{@code metric_dictionary}，§6.1）。 */
public final class JdbcMetricDictionary implements MetricDictionary {

    private static final String FIND = """
            SELECT metric_key, name, unit, rounding, basis
              FROM metric_dictionary
             WHERE metric_key = ? AND enabled = TRUE
            """;

    private final JdbcTemplate jdbc;

    public JdbcMetricDictionary(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcMetricDictionary(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MetricInfo> find(String metricKey) {
        if (metricKey == null || metricKey.isBlank()) {
            return Optional.empty();
        }
        List<MetricInfo> rows = jdbc.query(FIND, (rs, rowNum) -> new MetricInfo(
                rs.getString("metric_key"),
                rs.getString("name"),
                rs.getString("unit"),
                rs.getInt("rounding"),
                rs.getString("basis")), metricKey);
        return rows.stream().findFirst();
    }
}
