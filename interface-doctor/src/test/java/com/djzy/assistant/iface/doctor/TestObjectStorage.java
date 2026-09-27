package com.djzy.assistant.iface.doctor;

import com.djzy.assistant.common.storage.ObjectStorage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * 用例里的对象存储替身：**内存实现 + 一个假的预签名链接**。
 *
 * <p>为什么不直接用 {@code LocalFileObjectStorage}：它没有签名 URL 的能力，返回的下载链接恒为空——
 * 那样"上传接口有没有把链接交给调用方"这条根本测不到。这里只把 presign 这一件事变成可观察的，
 * 其余（键寻址、不覆盖、读回）都委托给真实的文件系统实现，免得替身自己写出一套与生产不同的语义。
 */
public final class TestObjectStorage implements ObjectStorage {

    private final ObjectStorage delegate;

    public TestObjectStorage(Path root) {
        this.delegate = new com.djzy.assistant.common.storage.LocalFileObjectStorage(root);
    }

    @Override
    public boolean putIfAbsent(String key, byte[] content) {
        return delegate.putIfAbsent(key, content);
    }

    @Override
    public Optional<byte[]> get(String key) {
        return delegate.get(key);
    }

    @Override
    public boolean exists(String key) {
        return delegate.exists(key);
    }

    @Override
    public java.util.List<String> list(String prefix) {
        return delegate.list(prefix);
    }

    @Override
    public void delete(String key) {
        delegate.delete(key);
    }

    @Override
    public Optional<String> presignedGetUrl(String key, Duration ttl) {
        long seconds = ttl == null ? 900 : Math.max(1, ttl.toSeconds());
        return Optional.of("http://127.0.0.1:9000/doctor-assistant/" + key + "?X-Amz-Expires=" + seconds
                + "&X-Amz-Signature=test-signature");
    }
}