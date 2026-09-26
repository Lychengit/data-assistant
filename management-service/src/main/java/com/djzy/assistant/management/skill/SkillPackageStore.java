package com.djzy.assistant.management.skill;

import java.util.Optional;

/**
 * 技能包存储（§19.2）：**按内容哈希命名、不做同名覆盖**。
 *
 * <p>内容相同就是同一个对象（重复上传不产生新对象），内容不同就是不同对象（新包永远覆盖不到正在执行的包），
 * 执行记录里的包哈希因此天然可复现。
 *
 * <p>它只回答「按 key 取一段字节」，具体落在文件系统还是对象存储由 {@link ObjectStorageSkillPackageStore}
 * 背后的 {@code ObjectStorage} 实现决定——本机开发用文件系统，多副本部署换成 S3 兼容的对象存储。
 */
public interface SkillPackageStore {

    /** @return 存储键；同一个 sha256 重复调用返回同一个键，且不重写内容 */
    String store(String sha256, byte[] packageBytes);

    Optional<byte[]> load(String storageKey);
}