package com.djzy.assistant.common.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** §19.1 / §4.9：授权取并集；无贡献 → 403（fail-closed）。数据范围**不参与**判定。 */
class AuthorizationServiceTest {

    @Test
    void apiSetIsUnionOfSkillApisAndDirectApis() {
        FakeRepo repo = new FakeRepo();
        repo.roles("u1", "role_a", "role_b");
        repo.skillsOfRoles(List.of("role_a", "role_b"), "skill_x");
        repo.approvedApis("skill_x", 11L, 12L);
        repo.directApis(List.of("role_a", "role_b"), 13L);

        assertEquals(Set.of(11L, 12L, 13L), new AuthorizationService(repo).apiSet("u1"));
    }

    @Test
    void skillGrantedApiIsAllowedWithoutAnyDirectRoleApiRow() {
        // 数据范围不再由平台配置（§19.1）：技能命中的接口就是"有权限"，范围由接口服务基于登录人推导。
        // 这条断言就是那个口径的守卫——如果哪天有人把范围判定加回平台，这里会红。
        FakeRepo repo = new FakeRepo();
        repo.roles("u1", "role_a");
        repo.skillsOfRoles(List.of("role_a"), "skill_x");
        repo.approvedApis("skill_x", 21L);

        AuthorizationService service = new AuthorizationService(repo);
        assertTrue(service.apiSet("u1").contains(21L));
        assertTrue(service.decideApiCall("u1", 21L, null).allowed());
    }

    @Test
    void apiNotInUnionIsDenied() {
        FakeRepo repo = new FakeRepo();
        repo.roles("u1", "role_a");
        repo.directApis(List.of("role_a"), 31L);

        ApiCallDecision decision = new AuthorizationService(repo).decideApiCall("u1", 99L, null);
        assertFalse(decision.allowed());
        assertEquals("API_NOT_IN_USER_UNION", decision.reason());
    }

    @Test
    void skillCallRequiresTriggerAndSkillApiWhitelist() {
        FakeRepo repo = new FakeRepo();
        repo.roles("u1", "role_a");
        repo.skillsOfRoles(List.of("role_a"), "skill_x");
        repo.approvedApis("skill_x", 41L);

        AuthorizationService service = new AuthorizationService(repo);
        assertTrue(service.decideApiCall("u1", 41L, "skill_x").allowed());
        assertEquals("SKILL_NOT_TRIGGERABLE", service.decideApiCall("u1", 41L, "skill_other").reason());
        assertEquals("API_NOT_IN_SKILL_WHITELIST", service.decideApiCall("u1", 42L, "skill_x").reason());
    }

    @Test
    void skillDeclaresApiOnlyForApprovedVerbs() {
        FakeRepo repo = new FakeRepo();
        repo.approvedApis("skill_x", 51L);

        AuthorizationService service = new AuthorizationService(repo);
        assertTrue(service.skillDeclaresApi("skill_x", 51L));
        assertFalse(service.skillDeclaresApi("skill_x", 52L));
        assertFalse(service.skillDeclaresApi(null, 51L));
    }

    static final class FakeRepo implements PermissionRepository {
        private final Map<String, List<String>> roles = new HashMap<>();
        private final Map<String, List<String>> skillsOfRoles = new HashMap<>();
        private final Map<String, List<Long>> approvedApis = new HashMap<>();
        private final Map<String, List<Long>> directApis = new HashMap<>();

        void roles(String userId, String... roleIds) {
            roles.put(userId, List.of(roleIds));
        }

        void skillsOfRoles(List<String> roleIds, String... skillCodes) {
            skillsOfRoles.put(String.join(",", roleIds), List.of(skillCodes));
        }

        void approvedApis(String skillCode, Long... apiIds) {
            approvedApis.put(skillCode, List.of(apiIds));
        }

        void directApis(List<String> roleIds, Long... apiIds) {
            directApis.put(String.join(",", roleIds), List.of(apiIds));
        }

        @Override
        public List<String> roleIdsOf(String userId) {
            return roles.getOrDefault(userId, List.of());
        }

        @Override
        public List<String> skillCodesOfRoles(List<String> roleIds) {
            return skillsOfRoles.getOrDefault(String.join(",", roleIds), List.of());
        }

        @Override
        public List<String> personalSkillCodesOf(String userId) {
            return List.of();
        }

        @Override
        public List<Long> approvedApiIdsOfSkill(String skillCode) {
            return approvedApis.getOrDefault(skillCode, List.of());
        }

        @Override
        public List<Long> directApiIdsOfRoles(List<String> roleIds) {
            return directApis.getOrDefault(String.join(",", roleIds), List.of());
        }
    }
}