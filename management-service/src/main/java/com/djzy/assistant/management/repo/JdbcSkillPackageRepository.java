package com.djzy.assistant.management.repo;

import com.djzy.assistant.common.api.ApiRoute;
import com.djzy.assistant.management.skill.SkillCheckResult;
import com.djzy.assistant.management.skill.SkillPackageInspector;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link SkillPackageRepository} 的 PG 实现（{@code jsonb} 需要 cast；H2 侧传 {@code jsonbCast=false}）。
 *
 * <p>版本行**只增不改**（除状态字段）：manifest 与内容哈希一旦落库就是历史事实（§19.2 不可变版本）。
 */
public final class JdbcSkillPackageRepository implements SkillPackageRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String COLUMNS = """
            SELECT id, skill_code, version, content_sha256, storage_key, manifest, manifest_sha256,
                   kind, status, submitted_by, published_by, published_at, created_at
              FROM sys_skill_version
            """;

    private final JdbcTemplate jdbc;
    private final String insertVersionSql;
    private final String insertCheckSql;
    private final String insertReviewSql;
    private final String upsertSkillSql;
    private final String countSkillSql;
    private final String insertSkillSql;
    private final String updateSkillSql;

    public JdbcSkillPackageRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource), true);
    }

    public JdbcSkillPackageRepository(JdbcTemplate jdbc) {
        this(jdbc, true);
    }

    public JdbcSkillPackageRepository(JdbcTemplate jdbc, boolean jsonbCast) {
        this.jdbc = jdbc;
        String json = jsonbCast ? "::jsonb" : "";
        this.insertVersionSql = """
                INSERT INTO sys_skill_version
                    (skill_code, version, content_sha256, storage_key, manifest, manifest_sha256,
                     kind, status, submitted_by, created_at)
                VALUES (?, ?, ?, ?, ?%s, ?, ?, 'uploaded', ?, ?)
                """.formatted(json);
        this.insertCheckSql = """
                INSERT INTO skill_check (skill_version_id, check_code, severity, passed, detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        this.insertReviewSql = """
                INSERT INTO skill_review (skill_version_id, decision, reviewer, reason, exported_columns, created_at)
                VALUES (?, ?, ?, ?, ?%s, ?)
                """.formatted(json);
        this.countSkillSql = "SELECT COUNT(*) FROM sys_skill WHERE skill_code = ?";
        this.insertSkillSql = """
                INSERT INTO sys_skill (skill_code, name, description, owner_type, read_only, param_schema, status, created_at, updated_at)
                VALUES (?, ?, ?, 'platform', TRUE, ?%s, 'active', ?, ?)
                """.formatted(json);
        this.updateSkillSql = """
                UPDATE sys_skill SET name = ?, description = ?, param_schema = ?%s, status = 'active', updated_at = ?
                 WHERE skill_code = ?
                """.formatted(json);
        this.upsertSkillSql = countSkillSql;
    }

    @Override
    public Optional<SkillVersionView> findByContentHash(String contentSha256) {
        return first(COLUMNS + " WHERE content_sha256 = ?", contentSha256);
    }

    @Override
    public Optional<SkillVersionView> findVersion(long versionId) {
        return first(COLUMNS + " WHERE id = ?", versionId);
    }

    @Override
    public Optional<SkillVersionView> findLatest(String skillCode) {
        return first(COLUMNS + " WHERE skill_code = ? ORDER BY created_at DESC, id DESC LIMIT 1", skillCode);
    }

    @Override
    public List<SkillVersionView> listVersions(String skillCode) {
        return jdbc.query(COLUMNS + " WHERE skill_code = ? ORDER BY created_at DESC, id DESC", this::view, skillCode);
    }

    @Override
    public List<SkillVersionView> listPending() {
        return jdbc.query(
                COLUMNS
                        + " WHERE id IN (SELECT MAX(id) FROM sys_skill_version GROUP BY skill_code)"
                        + "   AND status IN ('checked','uploaded') ORDER BY created_at DESC",
                this::view);
    }

    @Override
    public List<SkillSummary> listSkills() {
        return jdbc.query(
                """
                SELECT s.skill_code, s.name, s.description, s.status, s.updated_at,
                       v.version AS latest_version, v.status AS latest_status
                  FROM sys_skill s
                  LEFT JOIN sys_skill_version v
                    ON v.id = (SELECT MAX(id) FROM sys_skill_version WHERE skill_code = s.skill_code)
                 ORDER BY s.skill_code
                """,
                (rs, rowNum) -> new SkillSummary(
                        rs.getString("skill_code"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("status"),
                        rs.getString("latest_version"),
                        rs.getString("latest_status"),
                        instant(rs.getTimestamp("updated_at"))));
    }

    @Override
    public Optional<SkillSummary> findSkill(String skillCode) {
        return listSkills().stream().filter(summary -> summary.skillCode().equals(skillCode)).findFirst();
    }

    @Override
    public long insertVersion(VersionDraft draft) {
        jdbc.update(
                insertVersionSql,
                draft.skillCode(),
                draft.version(),
                draft.contentSha256(),
                draft.storageKey(),
                draft.manifestJson(),
                draft.manifestSha256(),
                draft.kind(),
                draft.submittedBy(),
                Timestamp.from(draft.createdAt()));
        Long id = jdbc.queryForObject(
                "SELECT id FROM sys_skill_version WHERE content_sha256 = ?", Long.class, draft.contentSha256());
        return id == null ? 0L : id;
    }

    @Override
    public void updateStatus(long versionId, String status, String publishedBy, Instant publishedAt) {
        jdbc.update(
                "UPDATE sys_skill_version SET status = ?, published_by = ?, published_at = ? WHERE id = ?",
                status,
                publishedBy,
                publishedAt == null ? null : Timestamp.from(publishedAt),
                versionId);
    }

    @Override
    public void insertChecks(long versionId, List<SkillCheckResult> checks, Instant createdAt) {
        for (SkillCheckResult check : checks) {
            jdbc.update(
                    insertCheckSql,
                    versionId,
                    check.code(),
                    check.severity(),
                    check.passed(),
                    check.detail(),
                    Timestamp.from(createdAt));
        }
    }

    @Override
    public List<SkillCheckResult> listChecks(long versionId) {
        return jdbc.query(
                "SELECT check_code, severity, passed, detail FROM skill_check WHERE skill_version_id = ? ORDER BY id",
                (rs, rowNum) -> new SkillCheckResult(
                        rs.getString("check_code"), rs.getString("severity"), rs.getBoolean("passed"), rs.getString("detail")),
                versionId);
    }

    @Override
    public void insertReview(
            long versionId, String decision, String reviewer, String reason, List<String> exports, Instant createdAt) {
        jdbc.update(
                insertReviewSql, versionId, decision, reviewer, reason, toJson(exports == null ? List.of() : exports), Timestamp.from(createdAt));
    }

    @Override
    public Map<String, SkillPackageInspector.ApiMetadata> apiMetadata(List<String> routes) {
        Map<String, SkillPackageInspector.ApiMetadata> result = new LinkedHashMap<>();
        for (String declared : routes) {
            ApiRoute route;
            try {
                route = ApiRoute.parse(declared);
            } catch (IllegalArgumentException e) {
                // 写法不合法的项交给自动检查报错，这里跳过——不能让「包写错了」变成「上传直接 500」
                continue;
            }
            List<SkillPackageInspector.ApiMetadata> rows = jdbc.query(
                    """
                    SELECT kind, enabled, result_schema
                      FROM sys_api
                     WHERE service = ? AND upper(http_method) = ? AND http_path = ?
                    """,
                    (rs, rowNum) -> new SkillPackageInspector.ApiMetadata(
                            route.format(), rs.getString("kind"), rs.getBoolean("enabled"), parseResultColumns(rs.getString("result_schema"))),
                    route.service(),
                    route.httpMethod(),
                    route.httpPath());
            if (!rows.isEmpty()) {
                result.put(route.format(), rows.get(0));
            }
        }
        return result;
    }

    public void publishSkill(
            String skillCode,
            String name,
            String description,
            String paramSchemaJson,
            List<String> routes,
            String publishedBy,
            Instant publishedAt) {
        Long existing = jdbc.queryForObject(countSkillSql, Long.class, skillCode);
        Timestamp now = Timestamp.from(publishedAt);
        if (existing != null && existing > 0) {
            jdbc.update(updateSkillSql, name, description, paramSchemaJson, now, skillCode);
        } else {
            jdbc.update(insertSkillSql, skillCode, name, description, paramSchemaJson, now, now);
        }
        Long skillId = jdbc.queryForObject("SELECT id FROM sys_skill WHERE skill_code = ?", Long.class, skillCode);
        // 审核对象是最新包，因此这里先撤回该技能所有接口绑定，再按本包重新授予（不留上一次的残留绑定）。
        jdbc.update("DELETE FROM skill_api WHERE skill_id = ?", skillId);
        for (String declared : routes) {
            ApiRoute route = ApiRoute.parse(declared);
            Long apiId = jdbc.queryForObject(
                    "SELECT id FROM sys_api WHERE service = ? AND upper(http_method) = ? AND http_path = ?",
                    Long.class,
                    route.service(),
                    route.httpMethod(),
                    route.httpPath());
            jdbc.update(
                    "INSERT INTO skill_api (skill_id, api_id, approved, approved_by, approved_at) VALUES (?, ?, TRUE, ?, ?)",
                    skillId,
                    apiId,
                    publishedBy,
                    now);
        }
    }

    @Override
    public boolean setSkillEnabled(String skillCode, boolean enabled) {
        int updated = jdbc.update(
                "UPDATE sys_skill SET status = ?, updated_at = ? WHERE skill_code = ?",
                enabled ? "active" : "disabled",
                Timestamp.from(Instant.now()),
                skillCode);
        if (updated > 0 && !enabled) {
            jdbc.update(
                    "UPDATE skill_api SET approved = FALSE WHERE skill_id = (SELECT id FROM sys_skill WHERE skill_code = ?)",
                    skillCode);
        }
        return updated > 0;
    }

    private Optional<SkillVersionView> first(String sql, Object... args) {
        return jdbc.query(sql, this::view, args).stream().findFirst();
    }

    private SkillVersionView view(ResultSet rs, int rowNum) throws SQLException {
        return new SkillVersionView(
                rs.getLong("id"),
                rs.getString("skill_code"),
                rs.getString("version"),
                rs.getString("content_sha256"),
                rs.getString("storage_key"),
                rs.getString("manifest"),
                rs.getString("manifest_sha256"),
                rs.getString("kind"),
                rs.getString("status"),
                rs.getString("submitted_by"),
                rs.getString("published_by"),
                instant(rs.getTimestamp("published_at")),
                instant(rs.getTimestamp("created_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** 返回字段契约的字段名集合。导出列的允许上界就是它——列白名单已删，返回哪些列由接口 SQL 写死。 */
    private static List<String> parseResultColumns(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> schema = MAPPER.readValue(json, MAP_TYPE);
            return List.copyOf(schema.keySet());
        } catch (Exception e) {
            return List.of();
        }
    }
}