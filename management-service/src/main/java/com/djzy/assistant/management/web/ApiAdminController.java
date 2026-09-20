package com.djzy.assistant.management.web;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.identity.UserIdentity;
import com.djzy.assistant.management.service.ApiAdminService;
import com.djzy.assistant.management.service.CurrentUserService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M4 接口注册入口（§18.4.6）：**只有 admin** 可读写。
 *
 * <p>行的定位键是 {@code id}（路径变量），身份是 {@code (service, httpMethod, httpPath)}（请求体）。
 * 模型侧工具名由路径派生、**不单独存**：管理端看到的就是派生结果，不存在"配置的名字和实际工具名不一致"。
 */
@RestController
@RequestMapping(path = "/v1/admin/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class ApiAdminController {

    private final ApiAdminService adminService;
    private final CurrentUserService currentUserService;

    public ApiAdminController(ApiAdminService adminService, CurrentUserService currentUserService) {
        this.adminService = adminService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(adminService.list().stream().map(ApiAdminController::toBody).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable("id") long id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(toBody(adminService.get(id)));
    }

    /** 登记 / 更新接口（按三元组幂等：命中即更新、未命中即插入）。 */
    @PutMapping
    public ResponseEntity<Map<String, Object>> upsert(
            @RequestBody(required = false) ApiRegistrationRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        ApiRegistrationRequest safe = request == null
                ? new ApiRegistrationRequest(null, null, null, null, null, null, null, null, null, null)
                : request;
        ApiDescriptor descriptor = adminService.upsert(
                safe.service(),
                safe.httpMethod(),
                safe.httpPath(),
                safe.name(),
                safe.kind(),
                safe.resource(),
                safe.paramSchema(),
                safe.enabled(),
                safe.scenario(),
                safe.resultSchema(),
                admin.userId(),
                requestIdOf(requestId));
        return ResponseEntity.ok(toBody(descriptor));
    }

    /** 启用 / 停用（停用后网关解析不到目标服务，调用被拒，§20.3）。 */
    @PutMapping("/{id}/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(
            @PathVariable("id") long id,
            @RequestParam("enabled") boolean enabled,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(toBody(adminService.setEnabled(id, enabled, admin.userId(), requestIdOf(requestId))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable("id") long id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        boolean deleted = adminService.delete(id, admin.userId(), requestIdOf(requestId));
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    private static String requestIdOf(String header) {
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }

    private static Map<String, Object> toBody(ApiDescriptor descriptor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", descriptor.id());
        body.put("service", descriptor.service());
        body.put("httpMethod", descriptor.httpMethod());
        body.put("httpPath", descriptor.httpPath());
        // 工具名是**派生的**：管理端直接展示，避免"配置的名字"与"模型看到的工具名"各说各话
        body.put("toolName", descriptor.toolName());
        body.put("name", descriptor.name());
        body.put("kind", descriptor.kind().name().toLowerCase());
        body.put("resource", descriptor.resource());
        body.put("paramSchema", descriptor.paramSchema());
        body.put("enabled", descriptor.enabled());
        // 场景与返回字段契约：模型靠它们选对接口、写对字段名（§4.8 节点 A），管理端必须看得见、改得动
        body.put("scenario", descriptor.scenario());
        body.put("resultSchema", descriptor.resultSchema());
        return body;
    }

    /**
     * 登记请求体：身份是三元组，**没有 scope、没有列白名单**。
     *
     * <p>{@code api_code} 也没了——它是"第二份清单"：代码里读不出来，路由也用不上，只能靠人记住。
     */
    public record ApiRegistrationRequest(
            String service,
            String httpMethod,
            String httpPath,
            String name,
            String kind,
            String resource,
            Map<String, Object> paramSchema,
            Boolean enabled,
            String scenario,
            Map<String, Object> resultSchema) {

    }
}