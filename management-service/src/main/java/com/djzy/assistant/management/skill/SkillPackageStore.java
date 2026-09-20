package com.djzy.assistant.management.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 技能包存储（§19.2）：**按内容哈希命名、不做同名覆盖**。
 *
 * <p>内容相同就是同一个对象（重复上传不产生新对象），内容不同就是不同对象（新包永远覆盖不到正在执行的包），
 * 执行记录里的包哈希因此天然可复现。骨架期落本地卷，接 MinIO 时换实现。
 */
public interface SkillPackageStore {

    /** @return 存储键；同一个 sha256 重复调用返回同一个键，且不重写内容 */
    String store(String sha256, byte[] packageBytes);

    Optional<byte[]> load(String storageKey);

    /** 本地卷实现：{@code <root>/sha256-<hash>.zip}。 */
    final class Local implements SkillPackageStore {

        private final Path root;

        public Local(Path root) {
            this.root = root;
            try {
                Files.createDirectories(root);
            } catch (IOException e) {
                throw new IllegalStateException("技能包存储目录不可用：" + root, e);
            }
        }

        @Override
        public String store(String sha256, byte[] packageBytes) {
            String key = "sha256-" + sha256 + ".zip";
            Path target = root.resolve(key);
            if (Files.exists(target)) {
                return key;
            }
            try {
                Path temp = root.resolve(key + "." + java.util.UUID.randomUUID() + ".tmp");
                Files.write(temp, packageBytes);
                Files.move(
                        temp,
                        target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return key;
            } catch (IOException e) {
                throw new IllegalStateException("技能包落盘失败：" + key, e);
            }
        }

        @Override
        public Optional<byte[]> load(String storageKey) {
            try {
                return Optional.of(Files.readAllBytes(root.resolve(storageKey)));
            } catch (IOException e) {
                return Optional.empty();
            }
        }
    }
}
