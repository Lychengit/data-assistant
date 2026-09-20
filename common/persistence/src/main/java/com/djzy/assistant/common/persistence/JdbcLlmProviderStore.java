package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.llm.ApiKeyCipher;
import com.djzy.assistant.common.llm.LlmProviderConfig;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code sys_llm_provider} 的 JDBC 存取（ADR-14 / §20.1.6 / §20.7）。
 *
 * <p>读写两侧都走这一个类：管理端写、agent-service 读，SQL 只有一份，
 * 免得「写入时加密、读取时忘了解密」这类错位在两侧各写一遍。
 *
 * <p>两条**刻意的取舍**：
 * <ul>
 *   <li>{@link #list()} / {@link #find} 解不出明文——界面与审计只需要 {@code keyHint}，
 *       那就干脆不给它们明文的能力，而不是给完再靠调用方自觉遮蔽。
 *   <li>{@link #findEnabled} 是唯一的明文出口，只服务于「构造模型客户端」这一件事。
 * </ul>
 *
 * <p>{@code upsert} 的 {@code apiKey == null} 表示**密钥不动**（只改端点/模型/开关），
 * 避免「改个 baseUrl 还得把 Key 重贴一遍」——那只会催生把 Key 写在便签上的习惯。
 */
public final class JdbcLlmProviderStore {

    private static final String COLUMNS = "provider_id, adapter, base_url, model, api_key_cipher, key_hint, enabled";

    private static final String LIST = "SELECT " + COLUMNS + " FROM sys_llm_provider ORDER BY provider_id";

    private static final String FIND = "SELECT " + COLUMNS + " FROM sys_llm_provider WHERE provider_id = ?";

    /** 生效中的那一行；靠 {@code ux_sys_llm_provider_enabled} 保证至多一行。 */
    private static final String FIND_ENABLED =
            "SELECT " + COLUMNS + " FROM sys_llm_provider WHERE enabled = true";

    private static final String DISABLE_OTHERS = """
            UPDATE sys_llm_provider SET enabled = false, updated_at = now()
             WHERE enabled = true AND provider_id <> ?
            """;

    private static final String UPDATE_KEEP_KEY = """
            UPDATE sys_llm_provider
               SET adapter = ?, base_url = ?, model = ?, enabled = ?, updated_at = now()
             WHERE provider_id = ?
            """;

    private static final String UPDATE_WITH_KEY = """
            UPDATE sys_llm_provider
               SET adapter = ?, base_url = ?, model = ?, api_key_cipher = ?, key_hint = ?,
                   enabled = ?, updated_at = now()
             WHERE provider_id = ?
            """;

    private static final String INSERT = """
            INSERT INTO sys_llm_provider
                (provider_id, adapter, base_url, model, api_key_cipher, key_hint, enabled)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SET_ENABLED =
            "UPDATE sys_llm_provider SET enabled = ?, updated_at = now() WHERE provider_id = ?";

    private static final String DELETE = "DELETE FROM sys_llm_provider WHERE provider_id = ?";

    private final JdbcTemplate jdbc;
    private final ApiKeyCipher cipher;

    public JdbcLlmProviderStore(DataSource dataSource, ApiKeyCipher cipher) {
        this(new JdbcTemplate(dataSource), cipher);
    }

    public JdbcLlmProviderStore(JdbcTemplate jdbc, ApiKeyCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** 全量列表，**不含明文**（管理端界面用）。 */
    public List<LlmProviderConfig> list() {
        return jdbc.query(LIST, (rs, rowNum) -> masked(rs));
    }

    /** 单条，**不含明文**。 */
    public Optional<LlmProviderConfig> find(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(FIND, (rs, rowNum) -> masked(rs), providerId).stream().findFirst();
    }

    /**
     * 生效中的那一行，**含明文 Key**——唯一出口，只给「构造模型客户端」用。
     *
     * <p>没有生效配置就返回空：调用方必须显式处理「没有模型可用」，
     * **不允许**在这里偷偷回退到「随便挑一行的 Key」，那等于把「没配」变成「配错了」。
     */
    public Optional<LlmProviderConfig> findEnabled() {
        return jdbc.query(FIND_ENABLED, (rs, rowNum) -> decrypted(rs)).stream().findFirst();
    }

    /**
     * 新增或更新。
     *
     * <p>{@code config.apiKey() == null} = 密钥保持不变；新增时若为 null 则直接拒绝
     * （没有 Key 的供应商行没有意义，早点 400 好过晚点在运行时 401）。
     */
    public void upsert(LlmProviderConfig config) {
        if (config.enabled()) {
            disableOthers(config.providerId());
        }
        String cipherText = config.apiKey() == null ? null : cipher.encrypt(config.apiKey());
        String hint = config.apiKey() == null ? null : ApiKeyCipher.hint(config.apiKey());
        int updated = cipherText == null
                ? jdbc.update(
                        UPDATE_KEEP_KEY,
                        config.adapter(),
                        config.baseUrl(),
                        config.model(),
                        config.enabled(),
                        config.providerId())
                : jdbc.update(
                        UPDATE_WITH_KEY,
                        config.adapter(),
                        config.baseUrl(),
                        config.model(),
                        cipherText,
                        hint,
                        config.enabled(),
                        config.providerId());
        if (updated > 0) {
            return;
        }
        if (cipherText == null) {
            throw new IllegalArgumentException("新增供应商必须提供 API Key：" + config.providerId());
        }
        jdbc.update(
                INSERT,
                config.providerId(),
                config.adapter(),
                config.baseUrl(),
                config.model(),
                cipherText,
                hint,
                config.enabled());
    }

    /** 启用 / 停用；启用时同事务把其它行关掉（§「同一时刻至多一个生效」）。 */
    public boolean setEnabled(String providerId, boolean enabled) {
        if (enabled) {
            disableOthers(providerId);
        }
        return jdbc.update(SET_ENABLED, enabled, providerId) > 0;
    }

    public boolean delete(String providerId) {
        return jdbc.update(DELETE, providerId) > 0;
    }

    private void disableOthers(String providerId) {
        jdbc.update(DISABLE_OTHERS, providerId);
    }

    /** 从行读出「去敏」配置：明文列在 SQL 里读出来了，但**刻意不落到对象上**。 */
    private static LlmProviderConfig masked(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LlmProviderConfig(
                rs.getString("provider_id"),
                rs.getString("adapter"),
                rs.getString("base_url"),
                rs.getString("model"),
                null,
                rs.getString("key_hint"),
                rs.getBoolean("enabled"));
    }

    private LlmProviderConfig decrypted(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LlmProviderConfig(
                rs.getString("provider_id"),
                rs.getString("adapter"),
                rs.getString("base_url"),
                rs.getString("model"),
                cipher.decrypt(rs.getString("api_key_cipher")),
                rs.getString("key_hint"),
                rs.getBoolean("enabled"));
    }
}
