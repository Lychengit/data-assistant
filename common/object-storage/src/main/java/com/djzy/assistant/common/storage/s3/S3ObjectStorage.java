package com.djzy.assistant.common.storage.s3;

import com.djzy.assistant.common.storage.ObjectStorage;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3 兼容实现（MinIO / 阿里云 OSS / 腾讯云 COS 都是这一套协议）。
 *
 * <p>这个类的存在理由就是「多副本」：Agent 多实例部署时，每个实例都要看见同一份文件，
 * 而本地磁盘做不到——所以把文件放进共享的对象存储里。
 *
 * <p>几个刻意的选择：
 * <ul>
 *   <li><b>不自动建桶</b>：桶是运维资产（谁建、放哪、什么生命周期策略），由部署时建好；
 *       应用发现桶不存在会明确报错，而不是偷偷建一个可能放错地方的桶。</li>
 *   <li><b>putIfAbsent 先查后写</b>：key 是内容哈希，并发写同一个 key 时写的字节完全一样，
 *       所以不需要分布式锁；最坏情况是同一个内容被写了两遍，值不变。</li>
 *   <li><b>列表分页到底</b>：S3 单次最多返回 1000 个，这里用分页器一次取全。</li>
 * </ul>
 */
public final class S3ObjectStorage implements ObjectStorage, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client client;
    private final String bucket;
    private final String keyPrefix;
    private final boolean ownsClient;
    private final boolean pathStyle;
    /**
     * 预签名器（只有需要「短期下载链接」时才建）。
     *
     * <p>懒建而不是构造时必建：绝大多数调用（技能包读写、工作区）根本用不到签名，为一个用不上的
     * 能力多开一套连接池不划算。用 volatile + 双检锁保证只建一次。
     */
    private volatile S3Presigner presigner;

    public S3ObjectStorage(S3Client client, String bucket, String keyPrefix) {
        this(client, bucket, keyPrefix, false, true);
    }

    S3ObjectStorage(S3Client client, String bucket, String keyPrefix, boolean ownsClient) {
        this(client, bucket, keyPrefix, ownsClient, true);
    }

    /**
     * @param pathStyle 与建 client 时用的是不是同一个值。预签名链接必须与主客户端同一套寻址方式：
     *     自建存储（MinIO）只认 path-style，签成 virtual-hosted 会得到一个解析不了的主机名
     */
    S3ObjectStorage(S3Client client, String bucket, String keyPrefix, boolean ownsClient, boolean pathStyle) {
        this.client = client;
        this.bucket = bucket;
        this.keyPrefix = normalizePrefix(keyPrefix);
        this.ownsClient = ownsClient;
        this.pathStyle = pathStyle;
    }

    /** 按配置建一个实现；配置不完整就在这里拒绝启动（别等到第一次上传才发现）。 */
    public static S3ObjectStorage from(ObjectStorageProperties properties) {
        ObjectStorageProperties.S3 s3 = properties.getS3();
        require(s3.getEndpoint(), "object-storage.s3.endpoint");
        require(s3.getBucket(), "object-storage.s3.bucket");
        require(s3.getAccessKey(), "object-storage.s3.access-key");
        require(s3.getSecretKey(), "object-storage.s3.secret-key");
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(s3.getEndpoint()))
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())))
                .forcePathStyle(s3.isPathStyle())
                .build();
        log.info("对象存储使用 S3 兼容实现：endpoint={} bucket={} keyPrefix={}",
                s3.getEndpoint(), s3.getBucket(), properties.getKeyPrefix());
        return new S3ObjectStorage(client, s3.getBucket(), properties.getKeyPrefix(), true, s3.isPathStyle());
    }

    @Override
    public boolean putIfAbsent(String key, byte[] content) {
        if (exists(key)) {
            return false;
        }
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(fullKey(key))
                .contentLength((long) content.length)
                .build();
        try {
            client.putObject(request, RequestBody.fromBytes(content));
            return true;
        } catch (S3Exception e) {
            throw new UnavailableException("对象写入失败：" + key, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(fullKey(key)).build();
        try (var response = client.getObject(request)) {
            return Optional.of(response.readAllBytes());
        } catch (S3Exception e) {
            if (isNotFound(e)) {
                return Optional.empty();
            }
            throw new UnavailableException("对象读取失败：" + key, e);
        } catch (java.io.IOException e) {
            throw new UnavailableException("对象读取失败：" + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        HeadObjectRequest request = HeadObjectRequest.builder().bucket(bucket).key(fullKey(key)).build();
        try {
            client.headObject(request);
            return true;
        } catch (S3Exception e) {
            if (isNotFound(e)) {
                return false;
            }
            throw new UnavailableException("对象查询失败：" + key, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        String fullPrefix = fullKey(prefix == null ? "" : prefix);
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(fullPrefix)
                .build();
        List<String> keys = new ArrayList<>();
        try {
            for (var page : client.listObjectsV2Paginator(request)) {
                for (S3Object object : page.contents()) {
                    keys.add(stripPrefix(object.key()));
                }
            }
        } catch (S3Exception e) {
            throw new UnavailableException("对象列表读取失败：prefix=" + prefix, e);
        }
        return List.copyOf(keys);
    }

    @Override
    public void delete(String key) {
        DeleteObjectRequest request =
                DeleteObjectRequest.builder().bucket(bucket).key(fullKey(key)).build();
        try {
            client.deleteObject(request);
        } catch (S3Exception e) {
            throw new UnavailableException("对象删除失败：" + key, e);
        }
    }

    /**
     * 预签名 GET 链接（§18.4.5 W2）：**短期**、只对这个 key、拿到链接的人不需要凭据就能下载。
     *
     * <p>为什么不自己拼一个 http://host/bucket/key：桶默认是私有的，那样拼出来的链接要么 403、
     * 要么要求把桶设成匿名可读——后者等于把导出文件对全网开放。而「链接只对拿到它的人有效、
     * 到点自动失效」正是导出这类数据的正确形态。
     *
     * <p>签名器从**同一个 client 的配置**派生（endpoint / region / 凭据 / path-style），
     * 所以不存在「主客户端与签名器配成两套」的漂移；MinIO 这类自建存储必须保持 path-style，
     * 否则签出来的链接指向 bucket.host 这种解析不了的主机名。
     */
    @Override
    public Optional<String> presignedGetUrl(String key, java.time.Duration ttl) {
        java.time.Duration effective =
                (ttl == null || ttl.isZero() || ttl.isNegative()) ? java.time.Duration.ofMinutes(15) : ttl;
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(effective)
                .getObjectRequest(builder -> builder.bucket(bucket).key(fullKey(key)))
                .build();
        try {
            return Optional.of(getPresigner().presignGetObject(request).url().toString());
        } catch (S3Exception e) {
            throw new UnavailableException("对象签名失败：" + key, e);
        }
    }

    private S3Presigner getPresigner() {
        S3Presigner local = presigner;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (presigner == null) {
                presigner = presignerOf(client);
            }
            return presigner;
        }
    }

    private S3Presigner presignerOf(S3Client client) {
        var configuration = client.serviceClientConfiguration();
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(configuration.region())
                .credentialsProvider(configuration.credentialsProvider());
        configuration.endpointOverride().ifPresent(builder::endpointOverride);
        builder.serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyle)
                .build());
        return builder.build();
    }

    /** 关闭 SDK 客户端（不关会留下 HTTP 连接池和后台线程）。只关自己建的那个。 */
    @Override
    public void close() {
        S3Presigner local = presigner;
        if (local != null) {
            local.close();
        }
        if (ownsClient) {
            client.close();
        }
    }

    /** S3 对「不存在」的两种表达：404（HeadObject）与 NoSuchKey（GetObject）。 */
    private static boolean isNotFound(S3Exception e) {
        return e.statusCode() == 404 || e instanceof software.amazon.awssdk.services.s3.model.NoSuchKeyException;
    }

    private String fullKey(String key) {
        return keyPrefix + key;
    }

    private String stripPrefix(String fullKey) {
        return fullKey.startsWith(keyPrefix) ? fullKey.substring(keyPrefix.length()) : fullKey;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String trimmed = prefix.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private static void require(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("object-storage.provider=s3 时必填：" + propertyName);
        }
    }
}