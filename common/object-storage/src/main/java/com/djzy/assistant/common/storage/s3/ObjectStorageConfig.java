package com.djzy.assistant.common.storage.s3;

import com.djzy.assistant.common.storage.LocalFileObjectStorage;
import com.djzy.assistant.common.storage.ObjectStorage;
import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储装配：按 {@code object-storage.provider} 选一个实现。
 *
 * <p>用哪个实现是**部署期决定**的（本机 local，多副本 s3），业务代码只认 {@link ObjectStorage} 端口。
 * 换云对象存储时，往往是直接复用这里的 S3 实现（OSS / COS 都有 S3 兼容入口）；
 * 真要换协议，只需在这个类里加一个分支 + 一个新的实现类。
 */
@Configuration
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class ObjectStorageConfig {

    /**
     * 注意 {@code destroyMethod} 用的是 Spring 默认的「自动推断」：
     * S3 实现带 {@code close()}（要释放 SDK 的连接池与线程），文件系统实现没有这个方法也不会报错。
     */
    @Bean
    public ObjectStorage objectStorage(ObjectStorageProperties properties) {
        String provider = properties.getProvider() == null ? "" : properties.getProvider().trim().toLowerCase();
        return switch (provider) {
            case "s3" -> S3ObjectStorage.from(properties);
            case "local", "" -> new LocalFileObjectStorage(Path.of(properties.getRootDir()));
            default -> throw new IllegalStateException(
                    "不认识的 object-storage.provider=" + properties.getProvider() + "（可选：local / s3）");
        };
    }
}