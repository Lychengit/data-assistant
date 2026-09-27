package com.djzy.assistant.management.web;

import com.djzy.assistant.management.repo.RoleView;
import com.djzy.assistant.management.service.CurrentUserService;
import com.djzy.assistant.management.service.RoleAdminService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色清单（只读）：**只有 admin 可进**，管理端用它渲染角色下拉框。
 *
 * <p>它存在的理由很具体：{@code /v1/admin/role-api} 与 {@code /v1/admin/role-skill} 都以 {@code roleCode} 为主语，
 * 而此前没有任何端点能列出有哪些角色——配置只能靠背编码，配错了表现为"查不到授权"，而不是报错。
 */
@RestController
@RequestMapping(path = "/v1/admin/role", produces = MediaType.APPLICATION_JSON_VALUE)
public class RoleAdminController {

    private final RoleAdminService adminService;
    private final CurrentUserService currentUserService;

    public RoleAdminController(RoleAdminService adminService, CurrentUserService currentUserService) {
        this.adminService = adminService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(adminService.list().stream().map(RoleAdminController::toBody).toList());
    }

    /** 展示名与备注都给全：界面要能让人确认"点的是不是这个角色"，而不是对着编码猜。 */
    private static Map<String, Object> toBody(RoleView role) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roleCode", role.roleCode());
        body.put("roleName", role.roleName());
        body.put("remark", role.remark());
        return body;
    }
}
