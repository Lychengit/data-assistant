package com.djzy.assistant.common.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 文件系统实现的行为：内容寻址下「不覆盖」、按前缀列、删了也算成功，以及 key 越界要被挡下。
 *
 * <p>工作目录故意放在 {@code target/} 下、不用 JUnit 的 {@code @TempDir}：
 * Windows 上 {@code @TempDir} 的收尾删除会和别的进程抢句柄（报「另一个程序正在使用此文件」），
 * 结果是**用例本身全过、构建却因为删不掉目录而失败**。这条踩过，见台账 L-14。
 */
class LocalFileObjectStorageTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 每个用例一个干净的工作目录（放在 target 下，避免 Windows 上删不掉系统临时目录）。 */
    private static Path 新工作目录(String name) throws IOException {
        Path dir = Path.of("target", "storage-test", name);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void 写进去能读出来_同一个key不覆盖() throws IOException {
        ObjectStorage storage = new LocalFileObjectStorage(新工作目录("put-if-absent"));

        assertTrue(storage.putIfAbsent("skill-packages/sha256-a.zip", bytes("first")));
        // 内容寻址的语义：同一个 key 重复写 = 已经在了，不重写、不报错
        assertFalse(storage.putIfAbsent("skill-packages/sha256-a.zip", bytes("second")));

        assertTrue(storage.exists("skill-packages/sha256-a.zip"));
        assertArrayEquals(bytes("first"), storage.get("skill-packages/sha256-a.zip").orElseThrow());
    }

    @Test
    void 不存在的对象返回空而不是抛异常() throws IOException {
        ObjectStorage storage = new LocalFileObjectStorage(新工作目录("missing"));

        assertEquals(Optional.empty(), storage.get("nothing/here.bin"));
        assertFalse(storage.exists("nothing/here.bin"));
        // 删不存在的对象也算成功：调用方不必先查后删
        storage.delete("nothing/here.bin");
    }

    @Test
    void 按前缀列key且返回相对key() throws IOException {
        ObjectStorage storage = new LocalFileObjectStorage(新工作目录("list-by-prefix"));
        storage.putIfAbsent("skill-packages/sha256-b.zip", bytes("b"));
        storage.putIfAbsent("skill-packages/sha256-a.zip", bytes("a"));
        storage.putIfAbsent("workspaces/u1/turn.json", bytes("{}"));

        assertEquals(
                List.of("skill-packages/sha256-a.zip", "skill-packages/sha256-b.zip"),
                storage.list("skill-packages/"));
        assertEquals(3, storage.list("").size());
    }

    @Test
    void 删除后读不到() throws IOException {
        ObjectStorage storage = new LocalFileObjectStorage(新工作目录("delete"));
        storage.putIfAbsent("a/b.bin", bytes("x"));

        storage.delete("a/b.bin");

        assertFalse(storage.exists("a/b.bin"));
    }

    @Test
    void key不允许越出存储根目录() throws IOException {
        ObjectStorage storage = new LocalFileObjectStorage(新工作目录("traversal"));

        assertThrows(IllegalArgumentException.class, () -> storage.get("../outside.bin"));
    }

    @Test
    void 父目录会自动创建() throws IOException {
        Path nested = 新工作目录("auto-parent").resolve("a/b/c");
        ObjectStorage storage = new LocalFileObjectStorage(nested);

        assertTrue(Files.isDirectory(nested));
        assertTrue(storage.putIfAbsent("deep/key.bin", bytes("v")));
    }

    /**
     * 端口契约：给不出临时链接时返回 {@code empty}——不抛异常，也不编一个指向本机路径的假链接
     * （接口服务据此如实回 null，而不是给调用方一个打不开的「下载地址」）。
     */
    @Test
    void 本地文件系统给不出签名链接() throws IOException {
        ObjectStorage storage = new LocalFileObjectStorage(新工作目录("presign"));
        storage.putIfAbsent("a/b.bin", bytes("x"));

        assertEquals(Optional.empty(), storage.presignedGetUrl("a/b.bin", java.time.Duration.ofMinutes(5)));
        assertEquals(Optional.empty(), storage.presignedGetUrl("nothing/here.bin", null));
    }
}
