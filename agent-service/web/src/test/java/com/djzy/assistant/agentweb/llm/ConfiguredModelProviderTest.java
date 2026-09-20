package com.djzy.assistant.agentweb.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.djzy.assistant.common.llm.AesGcmApiKeyCipher;
import com.djzy.assistant.common.llm.ApiKeyCipher;
import com.djzy.assistant.common.llm.LlmProviderConfig;
import com.djzy.assistant.common.persistence.JdbcLlmProviderStore;
import com.djzy.assistant.spi.AgentRunRequest;
import com.djzy.assistant.spi.RuntimeStatePort;
import com.djzy.assistant.spi.Snapshot;
import io.agentscope.core.model.Model;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;

/**
 * 库里的供应商配置 → 真正可用的模型对象（ADR-14 / §20.1.6）。
 *
 * <p>这是「管理端配完，运行时认不认」的那条缝。断言的分寸很关键：**不去碰网络**，
 * 但必须证明解析出来的确实是 OpenAI 兼容实现（DeepSeek 走的就是它）、模型名对得上、
 * 而且切换供应商**当轮生效**——不缓存是刻意的，不是碰巧。
 */
class ConfiguredModelProviderTest {

    private static final String KEK = "Bw4VHCMqMTg/Rk1UW2JpcHd+hYyTmqGor7a9xMvS2eA=";
    private static final String OTHER_KEK = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String KEY = "sk-test-0123456789abcdefghijklmn";

    /** 扩展包注册的 OpenAI 兼容实现：DeepSeek 也走它，所以这个类名就是「接上了」的证据。 */
    private static final String OPENAI_CHAT_MODEL = "io.agentscope.extensions.model.openai.OpenAIChatModel";

    private static final String DDL = """
            CREATE TABLE sys_llm_provider (
                provider_id    VARCHAR(64)   PRIMARY KEY,
                adapter        VARCHAR(32)   NOT NULL,
                base_url       VARCHAR(255)  NOT NULL,
                model          VARCHAR(128)  NOT NULL,
                api_key_cipher VARCHAR(1000) NOT NULL,
                key_hint       VARCHAR(64)   NOT NULL,
                enabled        BOOLEAN       NOT NULL DEFAULT FALSE,
                created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private final ApiKeyCipher cipher = AesGcmApiKeyCipher.fromBase64(KEK);

    private JdbcTemplate jdbc;
    private JdbcLlmProviderStore store;
    private ConfiguredModelProvider provider;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:llm-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(DDL);
        store = new JdbcLlmProviderStore(jdbc, cipher);
        provider = new ConfiguredModelProvider(store, () -> false);
    }

    @Test
    void resolvesTheConfiguredProviderIntoARealModel() {
        store.upsert(config("deepseek", "deepseek", "https://api.deepseek.com", "deepseek-flash", true));

        Model model = provider.modelFor(request());

        assertThat(model.getClass().getName()).isEqualTo(OPENAI_CHAT_MODEL);
        assertThat(model.getModelName()).isEqualTo("deepseek-flash");
    }

    @Test
    void keepsTheKeyEncryptedAtRestButDecryptsForTheClient() {
        store.upsert(config("deepseek", "deepseek", "https://api.deepseek.com", "deepseek-flash", true));

        String stored = jdbc.queryForObject(
                "SELECT api_key_cipher FROM sys_llm_provider WHERE provider_id = 'deepseek'", String.class);
        assertThat(stored).isNotEqualTo(KEY);
        assertThat(store.findEnabled().orElseThrow().apiKey()).isEqualTo(KEY);
    }

    @Test
    void failsWithAnActionableMessageWhenNothingIsEnabled() {
        assertThatThrownBy(() -> provider.modelFor(request()))
                .isInstanceOf(ConfiguredModelProvider.NoModelConfiguredException.class)
                .hasMessageContaining("模型供应商");

        // 配了但没启用，同样不算数——「存在」不等于「生效」
        store.upsert(config("deepseek", "deepseek", "https://api.deepseek.com", "deepseek-flash", false));
        assertThatThrownBy(() -> provider.modelFor(request()))
                .isInstanceOf(ConfiguredModelProvider.NoModelConfiguredException.class);
    }

    @Test
    void switchingProviderTakesEffectOnTheVeryNextTurn() {
        store.upsert(config("primary", "deepseek", "https://api.deepseek.com", "deepseek-flash", true));
        assertThat(provider.modelFor(request()).getModelName()).isEqualTo("deepseek-flash");

        // 启用另一个（服务层会把前一个关掉）；不缓存，所以下一轮立刻换模型
        store.upsert(config("reasoner", "deepseek", "https://api.deepseek.com", "deepseek-v4-pro", true));
        assertThat(provider.modelFor(request()).getModelName()).isEqualTo("deepseek-v4-pro");
    }

    @Test
    void unknownAdapterFailsInsteadOfSilentlyFallingBack() {
        jdbc.update(
                "INSERT INTO sys_llm_provider (provider_id, adapter, base_url, model, api_key_cipher, key_hint, enabled)"
                        + " VALUES (?, ?, ?, ?, ?, ?, TRUE)",
                "mystery",
                "mystery-vendor",
                "https://example.invalid",
                "m-1",
                cipher.encrypt(KEY),
                "****klmn");

        assertThatThrownBy(() -> provider.modelFor(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mystery-vendor");
    }

    @Test
    void aWrongKekFailsLoudlyRatherThanReturningGarbage() {
        store.upsert(config("deepseek", "deepseek", "https://api.deepseek.com", "deepseek-flash", true));
        JdbcLlmProviderStore mismatched = new JdbcLlmProviderStore(jdbc, AesGcmApiKeyCipher.fromBase64(OTHER_KEK));

        assertThatThrownBy(mismatched::findEnabled).hasMessageContaining("解密失败");
    }

    private static LlmProviderConfig config(
            String providerId, String adapter, String baseUrl, String model, boolean enabled) {
        return new LlmProviderConfig(providerId, adapter, baseUrl, model, KEY, null, enabled);
    }

    private static AgentRunRequest request() {
        return AgentRunRequest.builder()
                .userId("u-1")
                .sessionId("s-1")
                .requestId("r-1")
                .toolInvoker(ignored -> Flux.empty())
                .statePort(new NoopStatePort())
                .build();
    }

    private static final class NoopStatePort implements RuntimeStatePort {

        @Override
        public Optional<Snapshot> load(String userId, String sessionId, String key) {
            return Optional.empty();
        }

        @Override
        public void save(String userId, String sessionId, String key, Snapshot snapshot) {}

        @Override
        public void delete(String userId, String sessionId, String key) {}
    }
}
