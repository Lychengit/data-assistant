package com.djzy.assistant.common.permission;

import java.util.List;

/**
 * 权限数据读取端口（零缓存直查 PG，§0.3-4 / §4.1）。
 *
 * <p>判定逻辑一律在 {@link AuthorizationService} 唯一实现，实现类只负责取数。
 *
 * <p>为什么接口用 {@code apiIds} 而不是接口编码：授权比对的键是 {@code sys_api.id}。
 * 用编码比对意味着"改个路径就得把整张 {@code role_api} 一起改"，而路径恰恰是最常变的东西；
 * 用 id 比对后，路径改了、工具名跟着变，已发的授权不受影响。
 */
public interface PermissionRepository {

    List<String> roleIdsOf(String userId);

    /** {@code role_skill} 命中的技能（可见即可执行，§4.7）。 */
    List<String> skillCodesOfRoles(List<String> roleIds);

    /** 本人 personal 技能（owner=本人）。 */
    List<String> personalSkillCodesOf(String userId);

    /** {@code skill_api(approved=true)}：技能声明的、审核通过的接口（注册行 id）。 */
    List<Long> approvedApiIdsOfSkill(String skillCode);

    /** {@code role_api} 直配接口（注册行 id）——"这个角色手里有哪些钥匙"。 */
    List<Long> directApiIdsOfRoles(List<String> roleIds);
}