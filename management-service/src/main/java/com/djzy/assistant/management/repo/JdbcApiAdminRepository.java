package com.djzy.assistant.management.repo;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiKind;
import com.djzy.assistant.common.api.ApiRoute;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link ApiAdminRepository} 的 PG 实现（{@code sys_api}）。
 *
 * <p>定位键是三元组 {@code (service, upper(http_method), http_path)}——方法名归一后再比，
 * 库里写 {@code post}、管理端填 {@code POST} 不该变成两行（那样网关按 {@code POST} 查会查不到，静默 404）。
 *
 * <p>{@code param_schema} / {@code result_schema} 是 {@code jsonb}，PG 需要显式 cast；
 * 异构库（测试用 H2）把 {@code jsonbCast} 设为 {@code false} 即可复用同一套 SQL。
 */
public final class JdbcApiAdminRepository implements ApiAdminRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String COLUMNS = """
            id, name, service, http_method, http_path, kind, resource,
            param_schema, enabled, scenario, result_schema
            """;

    private static final String LIST = "SELECT " + COLUMNS + " FROM sys_api ORDER BY service, http_path";

    private static final String FIND_BY_ID = "SELECT " + COLUMNS + " FROM sys_api WHERE id = ?";

    private static final String FIND_BY_ROUTE =
            "SELECT " + COLUMNS + " FROM sys_api WHERE service = ? AND upper(http_method) = ? AND http_path = ?";

    private final JdbcTemplate jdbc;
    private final String updateSql;
    private final String insertSql;
    private final String enableSql;
    private final String deleteSql;

    public JdbcApiAdminRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource), true);
    }

    public JdbcApiAdminRepository(JdbcTemplate jdbc, boolean jsonbCast) {
        this.jdbc = jdbc;
        String cast = jsonbCast ? "::jsonb" : "";
        this.updateSql = """
                UPDATE sys_api
                   SET name = ?, kind = ?, resource = ?,
                       param_schema = ?%s, scenario = ?, result_schema = ?%s, enabled = ?, updated_at = now()
                 WHERE service = ? AND upper(http_method) = ? AND http_path = ?
                """.formatted(cast, cast);
        this.insertSql = """
                INSERT INTO sys_api (name, service, http_method, http_path, kind, resource,
                                     param_schema, enabled, scenario, result_schema)
                VALUES (?, ?, ?, ?, ?, ?, ?%s, ?, ?, ?%s)
                """.formatted(cast, cast);
        this.enableSql = "UPDATE sys_api SET enabled = ?, updated_at = now() WHERE id = ?";
        this.deleteSql = "DELETE FROM sys_api WHERE id = ?";
    }

    @Override
    public List<ApiDescriptor> list() {
        return jdbc.query(LIST, (rs, rowNum) -> toDescriptor(rs));
    }

    @Override
    public Optional<ApiDescriptor> findById(long id) {
        return jdbc.query(FIND_BY_ID, (rs, rowNum) -> toDescriptor(rs), id).stream().findFirst();
    }

    @Override
    public Optional<ApiDescriptor> findByRoute(ApiRoute route) {
        if (route == null) {
            return Optional.empty();
        }
        return jdbc.query(
                        FIND_BY_ROUTE,
                        (rs, rowNum) -> toDescriptor(rs),
                        route.service(),
                        route.httpMethod(),
                        route.httpPath())
                .stream()
                .findFirst();
    }

    @Override
    public void upsert(ApiDescriptor descriptor) {
        String kind = descriptor.kind() == ApiKind.WRITE ? "write" : "read";
        int updated = jdbc.update(
                updateSql,
                descriptor.name(),
                kind,
                descriptor.resource(),
                json(descriptor.paramSchema()),
                descriptor.scenario(),
                json(descriptor.resultSchema()),
                descriptor.enabled(),
                descriptor.service(),
                descriptor.httpMethod(),
                descriptor.httpPath());
        if (updated == 0) {
            jdbc.update(
                    insertSql,
                    descriptor.name(),
                    descriptor.service(),
                    descriptor.httpMethod(),
                    descriptor.httpPath(),
                    kind,
                    descriptor.resource(),
                    json(descriptor.paramSchema()),
                    descriptor.enabled(),
                    descriptor.scenario(),
                    json(descriptor.resultSchema()));
        }
    }

    @Override
    public boolean setEnabled(long id, boolean enabled) {
        return jdbc.update(enableSql, enabled, id) > 0;
    }

    @Override
    public boolean delete(long id) {
        return jdbc.update(deleteSql, id) > 0;
    }

    private static ApiDescriptor toDescriptor(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiDescriptor(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("service"),
                rs.getString("http_method"),
                rs.getString("http_path"),
                "write".equalsIgnoreCase(rs.getString("kind")) ? ApiKind.WRITE : ApiKind.READ,
                rs.getString("resource"),
                readMap(rs.getString("param_schema")),
                rs.getBoolean("enabled"),
                rs.getString("scenario"),
                readMap(rs.getString("result_schema")));
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

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? null : value);
        } catch (Exception e) {
            throw new IllegalStateException("接口注册元数据序列化失败", e);
        }
    }
}