package com.djzy.assistant.management.web;

import com.djzy.assistant.common.identity.UserIdentity;
import com.djzy.assistant.management.repo.RoleSkillGrantView;
import com.djzy.assistant.management.service.CurrentUserService;
import com.djzy.assistant.management.service.RoleSkillAdminService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色/技能授权入口（§4.7 / §18.4.6）：**只有 admin 可进**，每次改动写 {@code config_audit}。
 *
 * <p>这就是「怎么控制和管住哪些角色能用哪些技能」的落地：管理端在这里配一行
 * {@code role_skill(role_id, skill_id, can_view)}，网关下一轮算能力集时就会带上它。
 *
 * <p>与 {@code /v1/admin/role-api} 的分工：
 * <ul>
 *   <li>{@code role_skill} 决定「这个角色能用哪些**技能**」——模型这一轮看得到哪些技能包，
 *       **并且顺带拿到该技能绑定并评审通过的接口**（{@code skill_api}，§4.6 并集）；</li>
 *   <li>{@code role_api} 只管**没绑进技能**的接口——典型是写接口（ADR-19 ② 写操作默认不绑技能），
 *       所以它通常只是补充，不是"技能还得再授一遍"。</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/role-skill")
public class RoleSkillAdminController {

    private final RoleSkillAdminService adminService;
    private final CurrentUserService currentUserService;

    public RoleSkillAdminController(RoleSkillAdminService adminService, CurrentUserService currentUserService) {
        this.adminService = adminService;
        this.currentUserService = currentUserService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam("roleCode") String roleCode,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(
                adminService.list(roleCode).stream().map(RoleSkillAdminController::toBody).toList());
    }

    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> grant(
            @RequestBody(required = false) RoleSkillGrantRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        if (request == null || request.skillCode() == null || request.skillCode().isBlank()) {
            throw new IllegalArgumentException("缺少技能编码");
        }
        RoleSkillGrantView view = adminService.grant(
                request.roleCode(),
                request.skillCode(),
                request.canView() == null || request.canView(),
                admin.userId(),
                requestIdOf(requestId));
        return ResponseEntity.ok(toBody(view));
    }

    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> revoke(
            @RequestParam("roleCode") String roleCode,
            @RequestParam("skillCode") String skillCode,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        boolean revoked = adminService.revoke(roleCode, skillCode, admin.userId(), requestIdOf(requestId));
        return ResponseEntity.ok(Map.of("revoked", revoked));
    }

    private static String requestIdOf(String header) {
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }

    /** 对外视图：技能编码 + 展示名都给全，管理端不必再拿 id 去猜是哪个技能。 */
    private static Map<String, Object> toBody(RoleSkillGrantView view) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roleCode", view.roleCode());
        body.put("skillId", view.skillId());
        body.put("skillCode", view.skillCode());
        body.put("skillName", view.skillName());
        body.put("canView", view.canView());
        return body;
    }

    /** 授权请求体：{@code canView} 缺省为 {@code true}（授权就是"给用"）。 */
    public record RoleSkillGrantRequest(String roleCode, String skillCode, Boolean canView) {}
}