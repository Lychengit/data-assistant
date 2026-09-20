package com.djzy.assistant.management.service;

import com.djzy.assistant.common.config.ConfigAuditEntry;
import com.djzy.assistant.common.config.ConfigAuditWriter;
import com.djzy.assistant.common.llm.LlmAdapter;
import com.djzy.assistant.common.llm.LlmProviderConfig;
import com.djzy.assistant.common.persistence.JdbcLlmProviderStore;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型供应商配置（ADR-14 / §20.1.6 / §20.7）。
 *
 * <p>校验是 **fail-closed** 的：适配器不认识就 400，端点不是 http(s) 就 400，
 * 模型名带 {@code :} 就 400（那会把运行时的 {@code adapter:model} 引用串拼坏）。
 * 这些都是「写进来时能便宜地发现、运行时才发现就很贵」的错。
 *
 * <p>**审计与变更同事务**（§20.7）：一次 upsert / 启停 / 删除各写一条 {@code config_audit}，
 * 审计写失败则变更回滚。审计快照永远不含明文 Key——它记的是「换了哪把 Key」（{@code keyHint}），
 * 不是 Key 本身（§20.1.6 硬约束第 7 条：密钥不得进入日志 / Trace）。
 */
@Service
public class LlmProviderAdminService {

    private static final String TARGET = "llm_provider";
    private static final Pattern PROVIDER_ID = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");
    private static final int MAX_URL = 255;
    private static final int MAX_MODEL = 128;

    private final JdbcLlmProviderStore store;
    private final ConfigAuditWriter configAuditWriter;

    public LlmProviderAdminService(JdbcLlmProviderStore store, ConfigAuditWriter configAuditWriter) {
        this.store = store;
        this.configAuditWriter = configAuditWriter;
    }

    public List<LlmProviderConfig> list() {
        return store.list();
    }

    public LlmProviderConfig get(String providerId) {
        return store.find(requireText(providerId, "供应商标识"))
                .orElseThrow(() -> new IllegalArgumentException("供应商未配置：" + providerId));
    }

    /**
     * 新增 / 更新。
     *
     * <p>{@code apiKey == null} 表示**密钥不变**（只改端点、模型或开关）；
     * 新增时必须带 Key，否则拒绝。
     */
    @Transactional
    public LlmProviderConfig upsert(LlmProviderConfig config, String who, String requestId) {
        LlmProviderConfig valid = validate(config);
        LlmProviderConfig before = store.find(valid.providerId()).orElse(null);
        store.upsert(valid);
        LlmProviderConfig after = store.find(valid.providerId()).orElseThrow();
        audit(who, field(valid.providerId()), before == null ? null : before.toAuditMap(), after.toAuditMap(), requestId);
        return after;
    }

    /**
     * 启用 / 停用。
     *
     * <p>启用即「切换模型」：同事务把其它行关掉（数据库侧还有部分唯一索引兜底）。
     * 停用全部之后系统处于「没有模型可用」——那是**合法状态**，
     * 此时对话会明确报错，而不是偷偷继续用上一个。
     */
    @Transactional
    public LlmProviderConfig setEnabled(String providerId, boolean enabled, String who, String requestId) {
        String id = requireText(providerId, "供应商标识");
        LlmProviderConfig before = store.find(id)
                .orElseThrow(() -> new IllegalArgumentException("供应商未配置：" + id));
        if (before.enabled() == enabled) {
            return before;
        }
        if (!store.setEnabled(id, enabled)) {
            throw new IllegalArgumentException("供应商未配置：" + id);
        }
        LlmProviderConfig after = store.find(id).orElseThrow();
        audit(who, field(id), before.toAuditMap(), after.toAuditMap(), requestId);
        return after;
    }

    @Transactional
    public boolean delete(String providerId, String who, String requestId) {
        String id = requireText(providerId, "供应商标识");
        LlmProviderConfig before = store.find(id).orElse(null);
        if (before == null) {
            return false;
        }
        if (!store.delete(id)) {
            throw new IllegalArgumentException("供应商未配置：" + id);
        }
        audit(who, field(id), before.toAuditMap(), null, requestId);
        return true;
    }

    /** 生效中的供应商（去敏视图，不含明文）；没有就是空。 */
    public Optional<LlmProviderConfig> active() {
        return store.findEnabled().map(config -> new LlmProviderConfig(
                config.providerId(),
                config.adapter(),
                config.baseUrl(),
                config.model(),
                null,
                config.keyHint(),
                config.enabled()));
    }

    private static LlmProviderConfig validate(LlmProviderConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String providerId = requireText(config.providerId(), "供应商标识");
        if (!PROVIDER_ID.matcher(providerId).matches()) {
            throw new IllegalArgumentException("供应商标识只能是小写字母/数字/下划线/连字符，且以字母开头：" + providerId);
        }
        String adapterId = requireText(config.adapter(), "适配器").toLowerCase(java.util.Locale.ROOT);
        if (LlmAdapter.byId(adapterId).isEmpty()) {
            throw new IllegalArgumentException("不支持的适配器：" + adapterId + "（可选 " + java.util.Arrays.stream(LlmAdapter.values())
                    .map(LlmAdapter::id)
                    .toList() + "）");
        }
        String baseUrl = requireText(config.baseUrl(), "接口地址");
        if (baseUrl.length() > MAX_URL) {
            throw new IllegalArgumentException("接口地址过长（上限 " + MAX_URL + "）");
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("接口地址必须以 http:// 或 https:// 开头：" + baseUrl);
        }
        String model = requireText(config.model(), "模型名");
        if (model.length() > MAX_MODEL) {
            throw new IllegalArgumentException("模型名过长（上限 " + MAX_MODEL + "）");
        }
        if (model.contains(":")) {
            // 运行时按「适配器:模型」拼接引用串，模型名里再带冒号会把前缀解析歪
            throw new IllegalArgumentException("模型名不能包含冒号：" + model);
        }
        String apiKey = config.apiKey();
        if (apiKey != null) {
            String trimmed = apiKey.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("API Key 不能是空白");
            }
            if (!trimmed.equals(apiKey)) {
                // 粘贴时常带首尾空白/换行；这里顺手归一，而不是存下一个「看起来对但发出去 401」的 Key
                apiKey = trimmed;
            }
            if (apiKey.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("API Key 中不能包含空白字符");
            }
        }
        return new LlmProviderConfig(providerId, adapterId, baseUrl, model, apiKey, config.keyHint(), config.enabled());
    }

    private void audit(
            String who,
            String field,
            java.util.Map<String, Object> before,
            java.util.Map<String, Object> after,
            String requestId) {
        configAuditWriter.write(new ConfigAuditEntry(who, TARGET, field, before, after, requestId, java.time.Instant.now()));
    }

    private static String field(String providerId) {
        return "llm_provider:" + providerId;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }
}
