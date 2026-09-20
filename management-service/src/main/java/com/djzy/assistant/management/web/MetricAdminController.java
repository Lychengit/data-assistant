package com.djzy.assistant.management.web;

import com.djzy.assistant.common.identity.UserIdentity;
import com.djzy.assistant.management.repo.MetricDefinitionView;
import com.djzy.assistant.management.service.CurrentUserService;
import com.djzy.assistant.management.service.MetricAdminService;
import java.math.BigDecimal;
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

/** M5 口径字典入口（§6.1 / §18.4.6）：**只有 admin** 可改；改完立即生效（零缓存）。 */
@RestController
@RequestMapping(path = "/v1/admin/metric", produces = MediaType.APPLICATION_JSON_VALUE)
public class MetricAdminController {

    private final MetricAdminService adminService;
    private final CurrentUserService currentUserService;

    public MetricAdminController(MetricAdminService adminService, CurrentUserService currentUserService) {
        this.adminService = adminService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(adminService.list().stream().map(MetricAdminController::toBody).toList());
    }

    @GetMapping("/unit-convert")
    public ResponseEntity<List<Map<String, Object>>> unitConversions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(adminService.unitConversions());
    }

    @GetMapping("/{metricKey}")
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable("metricKey") String metricKey,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(toBody(adminService.get(metricKey)));
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> upsert(
            @RequestBody(required = false) MetricDefinitionRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        MetricDefinitionRequest safe = request == null ? new MetricDefinitionRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null) : request;
        MetricDefinitionView metric = new MetricDefinitionView(
                safe.metricKey(),
                safe.domain(),
                safe.name(),
                safe.aliases(),
                safe.definition(),
                safe.formula(),
                safe.timeBasis(),
                safe.unit(),
                safe.scale(),
                safe.rounding() == null ? 1 : safe.rounding(),
                safe.derivedOf(),
                safe.timezone(),
                safe.basis(),
                safe.enabled() == null || safe.enabled());
        return ResponseEntity.ok(toBody(adminService.upsert(metric, admin.userId(), requestIdOf(requestId))));
    }

    @PutMapping("/{metricKey}/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(
            @PathVariable("metricKey") String metricKey,
            @RequestParam("enabled") boolean enabled,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        return ResponseEntity.ok(toBody(adminService.setEnabled(metricKey, enabled, admin.userId(), requestIdOf(requestId))));
    }

    @DeleteMapping("/{metricKey}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable("metricKey") String metricKey,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        boolean deleted = adminService.delete(metricKey, admin.userId(), requestIdOf(requestId));
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    /** 单位换算登记（§6.1：所有换算集中一张表）。 */
    @PutMapping("/unit-convert")
    public ResponseEntity<Map<String, Object>> upsertUnitConversion(
            @RequestBody(required = false) UnitConversionRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        UserIdentity admin = currentUserService.requireAdmin(authorization);
        UnitConversionRequest safe = request == null ? new UnitConversionRequest(null, null, null) : request;
        adminService.upsertUnitConversion(safe.fromUnit(), safe.toUnit(), safe.factor(), admin.userId(), requestIdOf(requestId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromUnit", safe.fromUnit());
        body.put("toUnit", safe.toUnit());
        body.put("factor", safe.factor());
        return ResponseEntity.ok(body);
    }

    private static String requestIdOf(String header) {
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }

    private static Map<String, Object> toBody(MetricDefinitionView metric) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metricKey", metric.metricKey());
        body.put("domain", metric.domain());
        body.put("name", metric.name());
        body.put("aliases", metric.aliases());
        body.put("definition", metric.definition());
        body.put("formula", metric.formula());
        body.put("timeBasis", metric.timeBasis());
        body.put("unit", metric.unit());
        body.put("scale", metric.scale());
        body.put("rounding", metric.rounding());
        body.put("derivedOf", metric.derivedOf());
        body.put("timezone", metric.timezone());
        body.put("basis", metric.basis());
        body.put("enabled", metric.enabled());
        return body;
    }

    /** 口径登记请求体；{@code rounding} 缺省 1、{@code timezone} 缺省 Asia/Shanghai。 */
    public record MetricDefinitionRequest(
            String metricKey,
            String domain,
            String name,
            List<String> aliases,
            String definition,
            String formula,
            String timeBasis,
            String unit,
            BigDecimal scale,
            Integer rounding,
            Map<String, Object> derivedOf,
            String timezone,
            String basis,
            Boolean enabled) {}

    public record UnitConversionRequest(String fromUnit, String toUnit, BigDecimal factor) {}
}
