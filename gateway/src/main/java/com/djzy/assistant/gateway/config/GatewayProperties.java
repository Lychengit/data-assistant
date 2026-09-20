package com.djzy.assistant.gateway.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关配置（§18.4.2 G1–G4 / §20.1.2 / §20.3）。
 *
 * <p>密钥一律来自部署期注入（环境变量 / docker-compose secret），**不写进代码、不进 git、不进日志**。
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** 本网关持有的密钥表：登录令牌签名密钥 + 网关对外签名密钥（keyId → shared_secret）。 */
    private Map<String, String> secrets = new LinkedHashMap<>();

    /** 允许调用网关的服务：调用方 keyId → shared_secret（§20.1.2 按调用方逐一分配）。 */
    private Map<String, String> allowedCallers = new LinkedHashMap<>();

    /** 登录令牌签名密钥的 keyId。 */
    private String tokenKeyId = "login-token";

    /** 登录令牌有效期（≤15 分钟，§19.4）。 */
    private Duration tokenTtl = Duration.ofMinutes(15);

    /** 网关作为调用方对外签名时使用的 keyId。 */
    private String outboundKeyId = "gateway";

    /** 接口服务地址模板：{@code {service}} 由 {@code sys_api.service} 填入（固定配置发现，§20.3）。 */
    private String serviceBaseUrlTemplate = "http://{service}:8080";


    /** 读超时（§18.4.2 G4：读 5 秒）。 */
    private Duration readTimeout = Duration.ofSeconds(5);

    /** 写超时（§18.4.2 G4：写 30 秒）。 */
    private Duration writeTimeout = Duration.ofSeconds(30);

    /** 读失败重试次数（写操作绝不自动重试，§18.4.2 G2）。 */
    private int readRetries = 2;

    /** 响应体上限，超出即截断（§18.4.2 G4）。 */
    private int maxResponseBytes = 256 * 1024;

    /** 用户级限速：每秒请求数（§9.3）。 */
    private int perUserQps = 5;

    /** 熔断阈值：连续失败次数（§9.2）。 */
    private int circuitFailureThreshold = 5;

    /** 熔断打开时长。 */
    private Duration circuitOpenDuration = Duration.ofSeconds(30);

    /** nonce 存储：{@code memory}（单实例退化，§20.1.4 脚注）或 {@code redis}（多副本默认）。 */
    private String nonceStore = "memory";

    /** 启动时校验密钥齐全（§20.1.5 缺密钥拒绝启动）。 */
    private boolean requireSecrets = true;

    public Map<String, String> getSecrets() {
        return secrets;
    }

    public void setSecrets(Map<String, String> secrets) {
        this.secrets = secrets;
    }

    public Map<String, String> getAllowedCallers() {
        return allowedCallers;
    }

    public void setAllowedCallers(Map<String, String> allowedCallers) {
        this.allowedCallers = allowedCallers;
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

    public String getOutboundKeyId() {
        return outboundKeyId;
    }

    public void setOutboundKeyId(String outboundKeyId) {
        this.outboundKeyId = outboundKeyId;
    }

    public String getServiceBaseUrlTemplate() {
        return serviceBaseUrlTemplate;
    }

    public void setServiceBaseUrlTemplate(String serviceBaseUrlTemplate) {
        this.serviceBaseUrlTemplate = serviceBaseUrlTemplate;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getWriteTimeout() {
        return writeTimeout;
    }

    public void setWriteTimeout(Duration writeTimeout) {
        this.writeTimeout = writeTimeout;
    }

    public int getReadRetries() {
        return readRetries;
    }

    public void setReadRetries(int readRetries) {
        this.readRetries = readRetries;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public int getPerUserQps() {
        return perUserQps;
    }

    public void setPerUserQps(int perUserQps) {
        this.perUserQps = perUserQps;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public Duration getCircuitOpenDuration() {
        return circuitOpenDuration;
    }

    public void setCircuitOpenDuration(Duration circuitOpenDuration) {
        this.circuitOpenDuration = circuitOpenDuration;
    }

    public String getNonceStore() {
        return nonceStore;
    }

    public void setNonceStore(String nonceStore) {
        this.nonceStore = nonceStore;
    }

    public boolean isRequireSecrets() {
        return requireSecrets;
    }

    public void setRequireSecrets(boolean requireSecrets) {
        this.requireSecrets = requireSecrets;
    }
}