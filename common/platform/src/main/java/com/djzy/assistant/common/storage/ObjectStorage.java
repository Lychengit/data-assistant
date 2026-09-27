package com.djzy.assistant.common.storage;

import java.util.List;
import java.util.Optional;

/**
 * 对象存储端口（§19.2 M3 技能包，后续多实例共享文件也走它）：按 key 存/取一段字节。
 *
 * <p>**为什么要有这个端口**：本机开发用文件系统就够了，上了生产（多实例）必须换成对象存储，
 * 否则每个实例只看得见自己的磁盘。换的时候应该只动「装配」，不动业务代码——所以业务只认这个接口。
 *
 * <p>**为什么桶名 / 前缀不在 key 里**：那是「存储实例的属性」（同一套代码连不同环境，数据不能串在一起），
 * 由实现的配置决定；调用方只管业务意义上的 key。
 *
 * <p>**为什么写是「不覆盖」**：技能包按内容哈希命名，同一个 key 必然是同一份内容（§19.2）。
 * 不覆盖既省一次写，也顺带把「同名不同内容」这种不该发生的事挡在门外。
 */
public interface ObjectStorage {

    /** @return true = 这次真的写进去了；false = 键已存在，什么都没动 */
    boolean putIfAbsent(String key, byte[] content);

    /** 读；对象不存在返回空（「没有」是正常情况，不是异常）。 */
    Optional<byte[]> get(String key);

    boolean exists(String key);

    /** 列出前缀下的所有 key（字典序）；前缀传空串即列全部。 */
    List<String> list(String prefix);

    /** 删除；对象本来就不存在也算成功（幂等，调用方不必先查后删）。 */
    void delete(String key);

    /**
     * 短期下载链接（§18.4.5 W2「返回短期有效的下载链接」）。
     *
     * <p>默认**不支持**：本机文件系统实现没有「签名 URL」这个概念（对象就在本机目录里，
     * 给它编一个 HTTP 链接反而是假信息）。只有能签名的实现（S3 兼容）覆写它；
     * 调用方拿到空值时应如实说明「本次没有可直接下载的链接」，而不是回一个本地路径。
     *
     * @param key 业务 key
     * @param ttl 链接有效期；null / 非正数由实现取默认值
     * @return 可直接 GET 的临时链接；不支持时为空
     */
    default Optional<String> presignedGetUrl(String key, java.time.Duration ttl) {
        return Optional.empty();
    }

    /** 存储不可用 / 读写失败：调用方按「服务暂不可用」处理，不许吞掉。 */
    final class UnavailableException extends RuntimeException {

        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public UnavailableException(String message) {
            super(message);
        }
    }
}
