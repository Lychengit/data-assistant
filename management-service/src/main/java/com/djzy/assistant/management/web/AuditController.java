package com.djzy.assistant.management.web;

import com.djzy.assistant.management.audit.AuditWindow;
import com.djzy.assistant.management.service.AuditService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M6 审计 / 回放 / 监控入口（§18.4.6 / §20.4）。
 *
 * <p>**不走 data-gateway**：审计记录不挂在业务对象树上，套用户范围过滤会查不全（§20.4）。
 * 所有查询**必须带时间范围**（{@code from}/{@code to}，左闭右开、UTC、ISO-8601），
 * 且 admin 的每次查询本身都会被记一条 {@code audit_read_audit}。
 */
@RestController
@RequestMapping(path = "/v1/admin/audit", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** 数据访问审计（I6 权威源，§20.4）。 */
    @GetMapping("/data-access")
    public ResponseEntity<List<Map<String, Object>>> dataAccess(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "requestId", required = false) String requestId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "httpPath", required = false) String httpPath,
            @RequestParam(value = "outcome", required = false) String outcome,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        AuditWindow window = AuditWindow.parse(from, to, limit);
        return ResponseEntity.ok(auditService.dataAccess(authorization, window, requestId, userId, httpPath, outcome));
    }

    /** 权限判定审计（网关单点判定的记录）。 */
    @GetMapping("/permission")
    public ResponseEntity<List<Map<String, Object>>> permission(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "traceId", required = false) String traceId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "decision", required = false) String decision,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        AuditWindow window = AuditWindow.parse(from, to, limit);
        return ResponseEntity.ok(auditService.permissionAudits(authorization, window, traceId, userId, decision));
    }

    /** 配置变更审计（§20.7：M2/M3/M4/M5 的每次改动）。 */
    @GetMapping("/config")
    public ResponseEntity<List<Map<String, Object>>> config(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "target", required = false) String target,
            @RequestParam(value = "who", required = false) String who,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        AuditWindow window = AuditWindow.parse(from, to, limit);
        return ResponseEntity.ok(auditService.configAudits(authorization, window, target, who));
    }

    /** 「谁在何时查了审计」（§20.4 审计读本身也留痕）。 */
    @GetMapping("/reads")
    public ResponseEntity<List<Map<String, Object>>> reads(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "who", required = false) String who,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        AuditWindow window = AuditWindow.parse(from, to, limit);
        return ResponseEntity.ok(auditService.auditReads(authorization, window, who));
    }

    /** 按请求编号 / 链路 id 回看整条链路（含「agent 记了、接口服务没记」的不一致告警，§20.4）。 */
    @GetMapping("/trace")
    public ResponseEntity<Map<String, Object>> trace(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "requestId", required = false) String requestId,
            @RequestParam(value = "traceId", required = false) String traceId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        AuditWindow window = AuditWindow.parse(from, to, limit);
        return ResponseEntity.ok(auditService.trace(authorization, window, requestId, traceId));
    }

    /** 监控总览：拦截数 / 跑偏信号 / 延迟分位 / 成本（§18.4.6 M6、§10.4）。 */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok(auditService.overview(authorization, AuditWindow.parse(from, to, limit)));
    }
}
