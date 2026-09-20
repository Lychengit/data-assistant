package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.permission.AuthorizationService;
import com.djzy.assistant.common.permission.PermissionDecision;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 用真实 SQL 验证 §4.6 / §19.1 的并集口径与 fail-closed（接口身份 = {@code sys_api.id}）。 */
class JdbcPermissionRepositoryTest {

    private static final long PERF = 100L;
    private static final long LIST = 101L;
    private static final long EXPORT = 102L;

    private AuthorizationService authorization;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PersistenceTestSupport.dataSource();
        JdbcTemplate jdbc = PersistenceTestSupport.template(dataSource);
        seed(jdbc);
        authorization = new AuthorizationService(new JdbcPermissionRepository(jdbc));
    }

    private static void seed(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO sys_user (id, username, status) VALUES (1,'alice','active'),(2,'bob','active'),(3,'carol','disabled')");
        jdbc.update("INSERT INTO sys_role (id, role_code) VALUES (10,'dept_a'),(11,'dept_b'),(12,'finance')");
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (1,10),(1,11),(2,12),(3,12)");
        jdbc.update("INSERT INTO sys_api (id, name, service, http_method, http_path, kind, resource, param_schema, enabled) VALUES "
                + "(100,'医生绩效','interface-doctor','POST','/doctor/performance','read','doctor','{\"month\":\"string\"}',TRUE),"
                + "(101,'医生清单','interface-doctor','POST','/doctor/list','read','doctor','{}',TRUE),"
                + "(102,'导出报表','interface-doctor','POST','/report/export','write','report','{}',TRUE),"
                + "(103,'停用接口','interface-doctor','POST','/doctor/disabled','read','doctor','{}',FALSE)");
        jdbc.update("INSERT INTO sys_skill (id, skill_code, owner_type, owner_id) VALUES "
                + "(20,'perf_report','platform',NULL),(21,'my_skill','personal',1)");
        jdbc.update("INSERT INTO role_skill (role_id, skill_id, can_view) VALUES (10,20,TRUE)");
        jdbc.update("INSERT INTO skill_api (skill_id, api_id, approved) VALUES (20,100,TRUE),(20,102,FALSE)");
        // role_api 现在是纯关联表：授权只说"能不能调"，不说"能看多大范围"（§19.1）
        jdbc.update("INSERT INTO role_api (role_id, api_id) VALUES (10,100),(11,100),(10,101),(12,101),(12,103)");
    }

    @Test
    void multiRoleUnionForAuthorization() {
        assertEquals(List.of("dept_a", "dept_b"), authorization.rolesOf("alice"));
        assertEquals(Set.of(PERF, LIST), authorization.apiSet("alice"));
    }

    @Test
    void skillDerivedApisRequireApproval() {
        assertEquals(Set.of(PERF), authorization.runtimeApis("perf_report"));
    }

    @Test
    void disabledApiDropsOutOfUnion() {
        // 只有 finance 被授权 103，但那条接口已停用：停用即失效，不必先撤销授权（§20.3）
        assertFalse(authorization.apiSet("bob").contains(103L));
    }

    @Test
    void personalSkillsAreVisibleToOwner() {
        assertEquals(Set.of("perf_report", "my_skill"), authorization.viewableSkills("alice"));
        assertTrue(authorization.canTrigger("alice", "my_skill"));
        assertFalse(authorization.canTrigger("bob", "my_skill"));
    }

    @Test
    void noGrantAtAllIsDeniedInsteadOfSilentlyEmpty() {
        assertEquals(PermissionDecision.DENY, authorization.decideApiCall("bob", PERF, null).decision());
        assertFalse(authorization.apiSet("bob").contains(PERF));
    }

    @Test
    void gatewayDecisionDeniesWhenApiNotInUnion() {
        var decision = authorization.decideApiCall("bob", PERF, null);

        assertEquals(PermissionDecision.DENY, decision.decision());
        assertEquals("API_NOT_IN_USER_UNION", decision.reason());
    }

    @Test
    void skillScopedCallsCheckTriggerAndWhitelist() {
        assertTrue(authorization.decideApiCall("alice", PERF, "perf_report").allowed());

        assertEquals(
                "SKILL_NOT_TRIGGERABLE",
                authorization.decideApiCall("bob", PERF, "perf_report").reason());
        // 102 在 skill_api 里 approved=FALSE：审核没过的接口不进技能白名单
        assertEquals(
                "API_NOT_IN_SKILL_WHITELIST",
                authorization.decideApiCall("alice", EXPORT, "perf_report").reason());
    }

    @Test
    void unknownUserHasNothing() {
        assertTrue(authorization.rolesOf("nobody").isEmpty());
        assertTrue(authorization.apiSet("nobody").isEmpty());
    }
}