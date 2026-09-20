package com.djzy.assistant.management.web;

import com.djzy.assistant.common.identity.UserIdentity;
import com.djzy.assistant.management.repo.RoleApiGrantView;
import com.djzy.assistant.management.service.CurrentUserService;
import com.djzy.assistant.management.service.RoleApiAdminService;
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
 * M2 角色/接口授权入口（§18.4.6）：**只有 admin 可进**，每次改动写 {@code config_audit}。
 *
 * <p>接口用 {@code apiId} 指代（{@code sys_api.id}），**不传数据范围**——范围由接口服务基于登录人推导（§19.1）。
 */
@RestController
@RequestMapping("/v1/admin/role-api")
public class RoleApiAdminController {

    private final RoleApiAdminService adminService;
    private final CurrentUserService currentUserService;

    public RoleApiAdminController(RoleApiAdminService adminService, CurrentUserService currentUserService) {
        this.adminService = adminService;
        this.currentUserService = currentUserService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam("roleCode") String roleCode,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(adminService.list(roleCode).stream().map(RoleApiAdminController::toBody).toList());
    }

    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> grant(
            @RequestBody(required = false) RoleApiGrantRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        if (request == null || request.apiId() == null) {
            throw new IllegalArgumentException("缺少接口 id");
        }
        RoleApiGrantView view =
                adminService.grant(request.roleCode(), request.apiId(), admin.userId(), requestIdOf(requestId));
        return ResponseEntity.ok(toBody(view));
    }

    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> revoke(
            @RequestParam("roleCode") String roleCode,
            @RequestParam("apiId") long apiId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        boolean deleted = adminService.revoke(roleCode, apiId, admin.userId(), requestIdOf(requestId));
        return ResponseEntity.ok(Map.of("revoked", deleted));
    }

    private static String requestIdOf(String header) {
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }

    /** 对外视图：接口身份给全（方法 + 路径），行号给 {@code apiId}——管理端不必再拿名字去猜。 */
    private static Map<String, Object> toBody(RoleApiGrantView view) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roleCode", view.roleCode());
        body.put("apiId", view.apiId());
        body.put("httpMethod", view.httpMethod());
        body.put("httpPath", view.httpPath());
        body.put("route", view.display());
        return body;
    }

    /** 授权请求体：**没有 scope 字段**——数据范围不由授权承载（§19.1）。 */
    public record RoleApiGrantRequest(String roleCode, Long apiId) {}
}