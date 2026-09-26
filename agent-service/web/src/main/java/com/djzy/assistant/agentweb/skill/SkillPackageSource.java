package com.djzy.assistant.agentweb.skill;

import com.djzy.assistant.common.skill.PackageContent;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 技能包来源（H-06a / DR-41）：按技能编码回答「现在算数的是哪一版」，答上来才去取字节。
 *
 * <p><b>为什么拆成两步</b>：这两个问题的代价差了几个数量级。「哪一版算数」是一次很小的索引查询，
 * 每轮都可以问；「把包取回来解开」要读对象存储再解压，只在**版本真的变了**的时候才做。
 * 合成一个方法就必然每轮把包全取一遍——那是白烧带宽。
 *
 * <p>实现是「读管理端的表 + 读对象存储」，见 {@link RegistrySkillPackageSource}；
 * 接口留在这里是为了让下发逻辑（{@link WorkspaceSkillProvisioner}）可以脱离数据库单独测。
 */
public interface SkillPackageSource {

    /**
     * 批量查「这些技能当前算数的版本」。
     *
     * <p>没发布过 / 已停用的技能**不出现在返回里**——「清单里有它、但它没有可下发的内容」是正常情况，
     * 调用方按「这次不下发」处理，不该当成错误。
     */
    Map<String, Published> published(List<String> skillCodes);

    /** 取某个已发布版本的内容（已解开的文件）。只在版本变了时才调。 */
    Optional<PackageContent> load(Published published);

    /** 「当前算数的版本」的三件事：哪一版、内容哈希（排障用）、包在对象存储里的键。 */
    record Published(String skillCode, String version, String sha256, String storageKey) {}
}
