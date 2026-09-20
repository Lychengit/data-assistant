package com.djzy.assistant.management.web;

import com.djzy.assistant.common.identity.UserIdentity;
import com.djzy.assistant.common.llm.LlmAdapter;
import com.djzy.assistant.common.llm.LlmProviderConfig;
import com.djzy.assistant.management.service.CurrentUserService;
import com.djzy.assistant.management.service.LlmProviderAdminService;
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
 * 模型供应商配置入口（ADR-14 / §20.1.6）：**只有 admin** 可进；改完立即生效（agent 侧零缓存直查）。
 *
 * <p>响应用 {@link LlmProviderConfig#toView()}，**任何一条路径都不回文明文 Key**：
 * 列表、详情、新增、更新、启停，全部只给 {@code keyHint}。
 * 界面因此永远拿不到「能拿去调模型」的字符串，它也就不可能漏进前端日志或浏览器存储。
 */
@RestController
@RequestMapping(path = "/v1/admin/llm-provider", produces = MediaType.APPLICATION_JSON_VALUE)
public class LlmProviderAdminController {

    private final LlmProviderAdminService adminService;
    private final CurrentUserService currentUserService;

    public LlmProviderAdminController(LlmProviderAdminService adminService, CurrentUserService currentUserService) {
        this.adminService = adminService;
        this.currentUserService = currentUserService;
    }

    /** 可选适配器目录：界面用它填下拉框与默认端点（含 DeepSeek 的默认值）。 */
    @GetMapping("/adapters")
    public ResponseEntity<List<Map<String, Object>>> adapters(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(java.util.Arrays.stream(LlmAdapter.values())
                .map(adapter -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("id", adapter.id());
                    body.put("defaultBaseUrl", adapter.defaultBaseUrl());
                    body.put("defaultModel", adapter.defaultModel());
                    // 现役模型名照抄即用：供应商改名的代价太大（deepseek-chat 就是这么失效的）
                    body.put("knownModels", adapter.knownModels());
                    return body;
                })
                .toList());
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(adminService.list().stream()
                .map(LlmProviderConfig::toView)
                .toList());
    }

    @GetMapping("/{providerId}")
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable("providerId") String providerId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(adminService.get(providerId).toView());
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> upsert(
            @RequestBody(required = false) LlmProviderRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        LlmProviderRequest safe = request == null
                ? new LlmProviderRequest(null, null, null, null, null, null)
                : request;
        LlmProviderConfig config = new LlmProviderConfig(
                safe.providerId(),
                safe.adapter(),
                safe.baseUrl(),
                safe.model(),
                safe.apiKey(),
                null,
                safe.enabled() != null && safe.enabled());
        return ResponseEntity.ok(
                adminService.upsert(config, admin.userId(), requestIdOf(requestId)).toView());
    }

    @PutMapping("/{providerId}/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(
            @PathVariable("providerId") String providerId,
            @RequestParam("enabled") boolean enabled,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(
                adminService.setEnabled(providerId, enabled, admin.userId(), requestIdOf(requestId)).toView());
    }

    @DeleteMapping("/{providerId}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable("providerId") String providerId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        boolean deleted = adminService.delete(providerId, admin.userId(), requestIdOf(requestId));
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    private static String requestIdOf(String header) {
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }

    /**
     * 写入请求体。
     *
     * <p>{@code apiKey} 是**唯一**的明文入口：留空表示不变（更新时），
     * 或直接拒绝（新增时）——见 {@code LlmProviderAdminService.upsert}。
     */
    public record LlmProviderRequest(
            String providerId,
            String adapter,
            String baseUrl,
            String model,
            String apiKey,
            Boolean enabled) {}
}
