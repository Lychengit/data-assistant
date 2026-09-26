package com.djzy.assistant.common.storage.s3;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储配置（前缀 {@code object-storage}）。
 *
 * <p>**为什么用 provider 开关而不是直接写死实现**：本机开发只要文件系统（不起中间件），
 * 多副本 / 生产才需要真正的对象存储。同一个开关切两种实现，代码不用改。
 */
@ConfigurationProperties(prefix = "object-storage")
public class ObjectStorageProperties {

    /** 用哪个实现：{@code local}（本机文件系统，单副本）或 {@code s3}（S3 兼容，多副本 / 生产）。 */
    private String provider = "local";

    /** provider=local 时的根目录。 */
    private String rootDir = "./data/object-storage";

    /**
     * 统一前缀（相当于「桶里的一个目录」）。
     *
     * <p>用途：多个环境（本机 / 测试 / 生产）共用一个桶时靠它隔离；留空表示直接放在桶根下。
     * 写法随意（`doctor/` 与 `doctor` 等价），实现会补齐结尾的斜杠。
     */
    private String keyPrefix = "";

    private S3 s3 = new S3();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider == null ? "local" : provider;
    }

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public S3 getS3() {
        return s3;
    }

    public void setS3(S3 s3) {
        this.s3 = s3;
    }

    /** S3 兼容存储的连接参数：MinIO / 阿里云 OSS / 腾讯云 COS 都是这一套，只有地址和凭据不同。 */
    public static class S3 {

        /** 服务地址，例：{@code http://127.0.0.1:9000}、{@code https://oss-cn-hangzhou.aliyuncs.com}。 */
        private String endpoint = "";

        /** 区域；多数自建 / MinIO 环境随便填一个即可，但必填（SDK 要求）。 */
        private String region = "us-east-1";

        private String accessKey = "";

        private String secretKey = "";

        private String bucket = "doctor-assistant";

        /**
         * 是否用 path-style 访问（{@code http://host/bucket/key}）。
         *
         * <p>默认 true：MinIO、自建网关、以及不少私有云都只支持这种写法。
         * 公有云如果只认 virtual-hosted style，把它改成 false。
         */
        private boolean pathStyle = true;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public boolean isPathStyle() {
            return pathStyle;
        }

        public void setPathStyle(boolean pathStyle) {
            this.pathStyle = pathStyle;
        }
    }
}