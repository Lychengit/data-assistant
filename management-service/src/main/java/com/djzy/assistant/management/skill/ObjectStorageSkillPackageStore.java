package com.djzy.assistant.management.skill;

import com.djzy.assistant.common.storage.ObjectStorage;
import java.util.Objects;
import java.util.Optional;

/**
 * 把技能包放进对象存储（§19.2 + §20.8）。
 *
 * <p>**为什么不是文件系统**：管理端和 agent 侧可能各是多副本，本地磁盘只有自己看得见；
 * 技能包必须在所有实例眼里是同一份。所以统一走 {@link ObjectStorage} 端口——
 * 本机开发它落文件系统，多副本部署它落对象存储，**这个类完全不用改**。
 *
 * <p>键 = {@code sha256-<哈希>.zip}，与库里的 {@code storage_key} 一一对应（历史数据不用迁移）。
 */
public final class ObjectStorageSkillPackageStore implements SkillPackageStore {

    /** 技能包在存储里的路径前缀，和别的用途（比如将来的工作区文件）分开，便于按前缀做生命周期策略。 */
    static final String PREFIX = "skill-packages/";

    private final ObjectStorage storage;

    public ObjectStorageSkillPackageStore(ObjectStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public String store(String sha256, byte[] packageBytes) {
        String key = keyOf(sha256);
        // putIfAbsent：同一个哈希再传一次就是同一个对象，不重写（§19.2 内容寻址）
        storage.putIfAbsent(key, packageBytes);
        return key;
    }

    @Override
    public Optional<byte[]> load(String storageKey) {
        return storage.get(storageKey);
    }

    static String keyOf(String sha256) {
        return PREFIX + "sha256-" + sha256 + ".zip";
    }
}