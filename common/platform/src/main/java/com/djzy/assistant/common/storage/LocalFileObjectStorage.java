package com.djzy.assistant.common.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 文件系统实现（本机开发 / 单副本）。
 *
 * <p>**多副本部署不能用它**：每个实例只看得见自己的磁盘，换实例就找不到文件了——
 * 多副本请用 S3 兼容实现（`object-storage.provider=s3`）。
 *
 * <p>目录布局就是 {@code <root>/<key>}，key 里的 {@code /} 自然变成子目录。
 */
public final class LocalFileObjectStorage implements ObjectStorage {

    private final Path root;

    public LocalFileObjectStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UnavailableException("对象存储目录不可用：" + this.root, e);
        }
    }

    @Override
    public boolean putIfAbsent(String key, byte[] content) {
        Path target = resolve(key);
        if (Files.exists(target)) {
            return false;
        }
        try {
            Files.createDirectories(target.getParent());
            // 先写临时文件再原子改名：读的人永远读不到「写了一半」的对象
            Path temp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException e) {
                // 并发下别人先写成功了：内容按哈希寻址必然相同，按「已存在」处理即可
                Files.deleteIfExists(temp);
                return false;
            }
            return true;
        } catch (IOException e) {
            throw new UnavailableException("对象写入失败：" + key, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (IOException e) {
            if (e instanceof java.nio.file.NoSuchFileException) {
                return Optional.empty();
            }
            throw new UnavailableException("对象读取失败：" + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public List<String> list(String prefix) {
        String normalized = prefix == null ? "" : prefix;
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(name -> name.startsWith(normalized))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("对象列表读取失败：" + root, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UnavailableException("对象删除失败：" + key, e);
        }
    }

    /** key 越出存储根目录就拒绝：别让一个 {@code ../} 把别处的文件读出来。 */
    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的对象 key（越出存储根目录）：" + key);
        }
        return target;
    }
}