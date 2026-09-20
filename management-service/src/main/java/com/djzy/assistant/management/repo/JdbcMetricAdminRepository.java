package com.djzy.assistant.management.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link MetricAdminRepository} 的 PG 实现。
 *
 * <p>{@code aliases} / {@code derived_of} 是 {@code jsonb}，PG 需要显式 cast；
 * 异构库（测试用 H2）把 {@code jsonbCast} 设为 {@code false} 即可复用同一套 SQL。
 */
public final class JdbcMetricAdminRepository implements MetricAdminRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    private static final String COLUMNS = """
            metric_key, domain, name, aliases, definition, formula, time_basis,
            unit, scale, rounding, derived_of, timezone, basis, enabled
            """;

    private static final String LIST = "SELECT " + COLUMNS + " FROM metric_dictionary ORDER BY metric_key";

    private static final String FIND = "SELECT " + COLUMNS + " FROM metric_dictionary WHERE metric_key = ?";

    private final JdbcTemplate jdbc;
    private final String updateSql;
    private final String insertSql;
    private final String enableSql;
    private final String deleteSql;

    public JdbcMetricAdminRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource), true);
    }

    public JdbcMetricAdminRepository(JdbcTemplate jdbc, boolean jsonbCast) {
        this.jdbc = jdbc;
        String cast = jsonbCast ? "::jsonb" : "";
        this.updateSql = """
                UPDATE metric_dictionary
                   SET domain = ?, name = ?, aliases = ?%s, definition = ?, formula = ?, time_basis = ?,
                       unit = ?, scale = ?, rounding = ?, derived_of = ?%s, timezone = ?, basis = ?,
                       enabled = ?, updated_at = now()
                 WHERE metric_key = ?
                """.formatted(cast, cast);
        this.insertSql = """
                INSERT INTO metric_dictionary
                    (metric_key, domain, name, aliases, definition, formula, time_basis,
                     unit, scale, rounding, derived_of, timezone, basis, enabled)
                VALUES (?, ?, ?, ?%s, ?, ?, ?, ?, ?, ?, ?%s, ?, ?, ?)
                """.formatted(cast, cast);
        this.enableSql = "UPDATE metric_dictionary SET enabled = ?, updated_at = now() WHERE metric_key = ?";
        this.deleteSql = "DELETE FROM metric_dictionary WHERE metric_key = ?";
    }

    @Override
    public List<MetricDefinitionView> list() {
        return jdbc.query(LIST, (rs, rowNum) -> toView(rs));
    }

    @Override
    public Optional<MetricDefinitionView> find(String metricKey) {
        if (metricKey == null || metricKey.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(FIND, (rs, rowNum) -> toView(rs), metricKey).stream().findFirst();
    }

    @Override
    public void upsert(MetricDefinitionView metric) {
        int updated = jdbc.update(
                updateSql,
                metric.domain(),
                metric.name(),
                json(metric.aliases()),
                metric.definition(),
                metric.formula(),
                metric.timeBasis(),
                metric.unit(),
                metric.scale(),
                metric.rounding(),
                json(metric.derivedOf()),
                metric.timezone(),
                metric.basis(),
                metric.enabled(),
                metric.metricKey());
        if (updated == 0) {
            jdbc.update(
                    insertSql,
                    metric.metricKey(),
                    metric.domain(),
                    metric.name(),
                    json(metric.aliases()),
                    metric.definition(),
                    metric.formula(),
                    metric.timeBasis(),
                    metric.unit(),
                    metric.scale(),
                    metric.rounding(),
                    json(metric.derivedOf()),
                    metric.timezone(),
                    metric.basis(),
                    metric.enabled());
        }
    }

    @Override
    public boolean setEnabled(String metricKey, boolean enabled) {
        return jdbc.update(enableSql, enabled, metricKey) > 0;
    }

    @Override
    public boolean delete(String metricKey) {
        return jdbc.update(deleteSql, metricKey) > 0;
    }

    @Override
    public List<Map<String, Object>> listUnitConversions() {
        return jdbc.query("SELECT from_unit, to_unit, factor FROM unit_convert ORDER BY from_unit, to_unit", (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fromUnit", rs.getString("from_unit"));
            row.put("toUnit", rs.getString("to_unit"));
            row.put("factor", rs.getBigDecimal("factor"));
            return row;
        });
    }

    @Override
    public void upsertUnitConversion(String fromUnit, String toUnit, BigDecimal factor) {
        // 先 UPDATE 再 INSERT：不依赖各库的 upsert 方言（PG 的 ON CONFLICT / H2 的 MERGE 写法不同）
        int updated = jdbc.update(
                "UPDATE unit_convert SET factor = ? WHERE from_unit = ? AND to_unit = ?", factor, fromUnit, toUnit);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO unit_convert (from_unit, to_unit, factor) VALUES (?, ?, ?)",
                    fromUnit,
                    toUnit,
                    factor);
        }
    }

    private static MetricDefinitionView toView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MetricDefinitionView(
                rs.getString("metric_key"),
                rs.getString("domain"),
                rs.getString("name"),
                readList(rs.getString("aliases")),
                rs.getString("definition"),
                rs.getString("formula"),
                rs.getString("time_basis"),
                rs.getString("unit"),
                rs.getBigDecimal("scale"),
                rs.getInt("rounding"),
                readMap(rs.getString("derived_of")),
                rs.getString("timezone"),
                rs.getString("basis"),
                rs.getBoolean("enabled"));
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? null : value);
        } catch (Exception e) {
            throw new IllegalStateException("口径字典序列化失败", e);
        }
    }
}
