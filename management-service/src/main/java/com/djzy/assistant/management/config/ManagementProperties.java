package com.djzy.assistant.management.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 管理后台配置（§19.4 / §20.1.5）。 */
@ConfigurationProperties(prefix = "management-service")
public class ManagementProperties {

    /** 密钥表：keyId → shared_secret（令牌签名密钥等，部署期注入，缺失拒绝启动）。 */
    private Map<String, String> secrets = new LinkedHashMap<>();

    /** 登录令牌（JWT）签名密钥的 keyId。 */
    private String tokenKeyId = "login-token";

    /** 登录令牌有效期：**≤15 分钟**（§19.4，超过会被 JwtTokenService 直接拒绝）。 */
    private Duration tokenTtl = Duration.ofMinutes(15);

    /** 刷新令牌有效期：可轮换、一次性消费（§19.4）。 */
    private Duration refreshTtl = Duration.ofHours(8);

    /** 启动时校验密钥齐全（§20.1.5）。 */
    private boolean requireSecrets = true;

    /** 审计 / 平台元数据的只读出口（§18.4.6）；不配置时退化为「主连接 + 只读会话」。 */
    private ReadOnly readOnly = new ReadOnly();

    /**
     * 模型 API Key 的加密根密钥（Base64 编码的 32 字节，§20.1.6）。
     *
     * <p>留空**不阻塞启动**：平台在没有模型的情况下（noop 运行时）也能跑，
     * 把「有没有模型密钥」变成启动硬依赖是凭空造出来的耦合。但此时**无法保存**模型密钥，
     * 管理端会明确报错，而不是退化成明文落库。
     *
     * <p>生成：{@code openssl rand -base64 32}；必须与 agent-service 的 {@code AGENT_LLM_KEK} 是同一把，
     * 否则 agent 侧解不出密文。
     */
    private String llmKek = "";

    public Map<String, String> getSecrets() {
        return secrets;
    }

    public void setSecrets(Map<String, String> secrets) {
        this.secrets = secrets;
    }

    public String getTokenKeyId() {
        return tokenKeyId;
    }

    public void setTokenKeyId(String tokenKeyId) {
        this.tokenKeyId = tokenKeyId;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }

    public boolean isRequireSecrets() {
        return requireSecrets;
    }

    public void setRequireSecrets(boolean requireSecrets) {
        this.requireSecrets = requireSecrets;
    }

    public ReadOnly getReadOnly() {
        return readOnly;
    }

    public String getLlmKek() {
        return llmKek;
    }

    public void setLlmKek(String llmKek) {
        this.llmKek = llmKek;
    }

    public void setReadOnly(ReadOnly readOnly) {
        this.readOnly = readOnly;
    }

    /** 只读出口连接配置：生产必须指向「只有 SELECT 权限」的账号。 */
    public static class ReadOnly {

        private String url = "";
        private String username = "";
        private String password = "";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public boolean isConfigured() {
            return url != null && !url.isBlank();
        }
    }
}
