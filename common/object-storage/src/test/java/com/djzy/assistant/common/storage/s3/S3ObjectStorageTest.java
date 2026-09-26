package com.djzy.assistant.common.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.djzy.assistant.common.storage.ObjectStorage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 兼容实现的集成测试：**对着本机真实的 MinIO 跑**（`deploy/local-windows/README.md` 里有启动方式）。
 *
 * <p>**为什么允许跳过**：CI 或同事的机器上可能没起 MinIO，这种情况下 {@code assumeTrue} 会跳过整个类，
 * 而不是把「环境没准备好」当成代码错。但只要有 MinIO，它就会真跑——因为「S3 协议用得对不对」
 * 是没法用假实现验证的（分页、404 语义、path-style 全是协议行为）。
 *
 * <p>连接参数可用环境变量覆盖：{@code MINIO_ENDPOINT} / {@code MINIO_ACCESS_KEY} /
 * {@code MINIO_SECRET_KEY} / {@code MINIO_BUCKET}，默认对本机开发那套（djzy / djzy-minio）。
 */
class S3ObjectStorageTest {

    private static final String ENDPOINT = env("MINIO_ENDPOINT", "http://127.0.0.1:9000");
    private static final String ACCESS_KEY = env("MINIO_ACCESS_KEY", "djzy");
    private static final String SECRET_KEY = env("MINIO_SECRET_KEY", "djzy-minio");
    private static final String BUCKET = env("MINIO_BUCKET", "doctor-assistant");

    /** 每次测试自己占一个前缀，互相不打架，也不会污染别的数据。 */
    private static final String KEY_PREFIX = "test-" + UUID.randomUUID();

    private static S3Client client;
    private static ObjectStorage storage;

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @BeforeAll
    static void 连上本机MinIO并准备好桶() {
        client = S3Client.builder()
                .endpointOverride(URI.create(ENDPOINT))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .build();
        assumeTrue(minio可用了(client), "本机没有可用的 MinIO（" + ENDPOINT + "），跳过 S3 集成测试");
        storage = new S3ObjectStorage(client, BUCKET, KEY_PREFIX);
    }

    private static boolean minio可用了(S3Client client) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build());
            return true;
        } catch (NoSuchBucketException e) {
            // 桶不存在但服务在线：测试自己建（生产不会自动建桶，见 S3ObjectStorage 的说明）
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
                return true;
            } catch (S3Exception createFailure) {
                return false;
            }
        } catch (S3Exception e) {
            return false;
        }
    }

    @AfterAll
    static void 清掉本次测试产生的对象() {
        if (client == null) {
            return;
        }
        try {
            var response = client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(BUCKET)
                    .prefix(KEY_PREFIX + "/")
                    .build());
            response.contents().forEach(object -> client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(BUCKET)
                    .key(object.key())
                    .build()));
        } finally {
            client.close();
        }
    }

    @Test
    void 写进去能读出来_同一个key不覆盖() {
        assertTrue(storage.putIfAbsent("skill-packages/sha256-a.zip", bytes("first")));
        assertFalse(storage.putIfAbsent("skill-packages/sha256-a.zip", bytes("second")));

        assertTrue(storage.exists("skill-packages/sha256-a.zip"));
        assertArrayEquals(bytes("first"), storage.get("skill-packages/sha256-a.zip").orElseThrow());
    }

    @Test
    void 不存在的对象返回空而不是抛异常() {
        assertEquals(Optional.empty(), storage.get("nothing/here.bin"));
        assertFalse(storage.exists("nothing/here.bin"));
        storage.delete("nothing/here.bin");
    }

    @Test
    void 按前缀列key时去掉统一前缀() {
        storage.putIfAbsent("list-check/b.bin", bytes("b"));
        storage.putIfAbsent("list-check/a.bin", bytes("a"));

        List<String> keys = storage.list("list-check/");

        assertEquals(List.of("list-check/a.bin", "list-check/b.bin"), keys);
    }

    @Test
    void 删除后读不到() {
        storage.putIfAbsent("delete-check/x.bin", bytes("x"));

        storage.delete("delete-check/x.bin");

        assertFalse(storage.exists("delete-check/x.bin"));
    }

    @Test
    void 配置不全时直接拒绝启动而不是等到第一次上传() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setProvider("s3");
        properties.getS3().setEndpoint("http://127.0.0.1:9000");
        // 故意不填 access-key：装配期就该报错

        assertThrows(IllegalStateException.class, () -> S3ObjectStorage.from(properties));
    }
}