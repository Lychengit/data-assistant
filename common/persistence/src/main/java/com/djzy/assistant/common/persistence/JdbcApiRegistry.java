package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiKind;
import com.djzy.assistant.common.api.ApiRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link ApiRegistry} 的 PG 实现（{@code sys_api}，§4.7 / §20.3）。 */
public final class JdbcApiRegistry implements ApiRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    private static final String COLUMNS = """
            id, name, service, http_method, http_path, kind, resource,
            param_schema, enabled, scenario, result_schema
            """;

    private static final String FIND_BY_ROUTE =
            "SELECT " + COLUMNS + " FROM sys_api WHERE service = ? AND upper(http_method) = ? AND http_path = ?";

    private final JdbcTemplate jdbc;

    public JdbcApiRegistry(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    public JdbcApiRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ApiDescriptor> findByRoute(String service, String httpMethod, String httpPath) {
        if (isBlank(service) || isBlank(httpMethod) || isBlank(httpPath)) {
            return Optional.empty();
        }
        // 方法名归一后再比：库里存 post / 请求里是 POST 不该被当成两个接口（那样会静默 404）
        String normalized = httpMethod.trim().toUpperCase(Locale.ROOT);
        return jdbc.query(FIND_BY_ROUTE, (rs, rowNum) -> toDescriptor(rs), service.trim(), normalized, httpPath.trim())
                .stream()
                .findFirst();
    }

    @Override
    public List<ApiDescriptor> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT " + COLUMNS + " FROM sys_api WHERE id IN ("
                + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ") ORDER BY id";
        return jdbc.query(sql, (rs, rowNum) -> toDescriptor(rs), new ArrayList<>(ids).toArray());
    }

    private static ApiDescriptor toDescriptor(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiDescriptor(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("service"),
                rs.getString("http_method"),
                rs.getString("http_path"),
                toKind(rs.getString("kind")),
                rs.getString("resource"),
                readMap(rs.getString("param_schema")),
                rs.getBoolean("enabled"),
                rs.getString("scenario"),
                readMap(rs.getString("result_schema")));
    }

    private static ApiKind toKind(String kind) {
        return kind != null && kind.equalsIgnoreCase("write") ? ApiKind.WRITE : ApiKind.READ;
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

    static List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}