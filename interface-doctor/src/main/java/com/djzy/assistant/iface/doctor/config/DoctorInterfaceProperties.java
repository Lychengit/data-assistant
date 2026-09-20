package com.djzy.assistant.iface.doctor.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 接口服务配置（§20.1.2 / §20.1.5）。
 *
 * <p>allowed-callers 只应包含网关：接口服务只接受来自网关的连接（网络策略默认全拒 + 白名单放行）。
 */
@ConfigurationProperties(prefix = "interface-doctor")
public class DoctorInterfaceProperties {

    /** 允许调用本服务的服务：keyId → shared_secret。 */
    private Map<String, String> allowedCallers = new LinkedHashMap<>();

    /** nonce 存储：memory（单实例退化）或 redis。 */
    private String nonceStore = "memory";

    /**
     * 本服务所有业务接口的路径前缀（默认 {@code /doctor}）。
     *
     * <p>它同时是**验签过滤器的保护范围**和**启动自检的边界**：注册到注册表里的每个路径都必须落在
     * 这个前缀内，否则服务拒绝启动——防止"加了接口但忘了让验签覆盖它"，那种端点网关能调、别人也能调，
     * 而且没有任何测试会失败。
     */
    private String apiPrefix = "/doctor";

    /** 单次查询返回行数上限（超出即截断并标注，防止把上下文打爆）。 */
    private int maxRows = 5000;

    /** 启动时校验密钥齐全（§20.1.5）。 */
    private boolean requireSecrets = true;

    public Map<String, String> getAllowedCallers() {
        return allowedCallers;
    }

    public void setAllowedCallers(Map<String, String> allowedCallers) {
        this.allowedCallers = allowedCallers;
    }

    public String getNonceStore() {
        return nonceStore;
    }

    public void setNonceStore(String nonceStore) {
        this.nonceStore = nonceStore;
    }

    public String getApiPrefix() {
        return apiPrefix;
    }

    public void setApiPrefix(String apiPrefix) {
        this.apiPrefix = apiPrefix;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public boolean isRequireSecrets() {
        return requireSecrets;
    }

    public void setRequireSecrets(boolean requireSecrets) {
        this.requireSecrets = requireSecrets;
    }
}