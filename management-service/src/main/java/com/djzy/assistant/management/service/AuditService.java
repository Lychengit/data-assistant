package com.djzy.assistant.management.service;

import com.djzy.assistant.common.audit.AuditReadEntry;
import com.djzy.assistant.common.audit.AuditReadWriter;
import com.djzy.assistant.common.identity.UserIdentity;
import com.djzy.assistant.management.audit.AuditQueryRepository;
import com.djzy.assistant.management.audit.AuditWindow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * M6 审计 / 回放 / 监控（§18.4.6 / §20.4）。
 *
 * <p>三条硬口径：
 * <ul>
 *   <li>**只有 admin 能进**：判定走 {@link CurrentUserService}（角色判定唯一实现在 common），
 *       非 admin 的尝试也留一条 DENY 记录（否则无法回答「谁试图翻看审计」）；
 *   <li>**审计读本身也要记一条审计**：写在 {@code audit_read_audit}，用可写连接；
 *       审计内容用只读连接查出——读写两条通道分开；
 *   <li>**查询必须带时间范围**：{@link AuditWindow} 强制 from/to（§20.5）。
 * </ul>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditQueryRepository repository;
    private final AuditReadWriter auditReadWriter;
    private final CurrentUserService currentUserService;

    public AuditService(
            AuditQueryRepository repository,
            AuditReadWriter auditReadWriter,
            CurrentUserService currentUserService) {
        this.repository = repository;
        this.auditReadWriter = auditReadWriter;
        this.currentUserService = currentUserService;
    }

    public List<Map<String, Object>> dataAccess(
            String authorization, AuditWindow window, String requestId, String userId, String httpPath, String outcome) {
        Map<String, Object> params = params(window, "requestId", requestId, "userId", userId, "httpPath", httpPath, "outcome", outcome);
        return auditedRead(
                authorization,
                "data-access",
                params,
                () -> repository.dataAccess(window, requestId, userId, httpPath, outcome),
                List::size);
    }

    public List<Map<String, Object>> permissionAudits(
            String authorization, AuditWindow window, String traceId, String userId, String decision) {
        Map<String, Object> params = params(window, "traceId", traceId, "userId", userId, "decision", decision);
        return auditedRead(
                authorization,
                "permission",
                params,
                () -> repository.permissionAudits(window, traceId, userId, decision),
                List::size);
    }

    public List<Map<String, Object>> configAudits(
            String authorization, AuditWindow window, String target, String who) {
        Map<String, Object> params = params(window, "target", target, "who", who);
        return auditedRead(
                authorization, "config", params, () -> repository.configAudits(window, target, who), List::size);
    }

    public List<Map<String, Object>> auditReads(String authorization, AuditWindow window, String who) {
        Map<String, Object> params = params(window, "who", who);
        return auditedRead(authorization, "reads", params, () -> repository.auditReads(window, who), List::size);
    }

    /**
     * 按请求编号 / 链路 id 回看整条链路（§20.4 / §8.3）。
     *
     * @throws IllegalArgumentException requestId 与 traceId 都没给
     */
    public Map<String, Object> trace(String authorization, AuditWindow window, String requestId, String traceId) {
        if ((requestId == null || requestId.isBlank()) && (traceId == null || traceId.isBlank())) {
            throw new IllegalArgumentException("必须给出 requestId 或 traceId（§20.4 按编号回放）");
        }
        Map<String, Object> params = params(window, "requestId", requestId, "traceId", traceId);
        return auditedRead(
                authorization,
                "trace",
                params,
                () -> repository.trace(requestId, traceId, window),
                chain -> ((List<?>) chain.get("dataAccess")).size()
                        + ((List<?>) chain.get("toolCalls")).size()
                        + ((List<?>) chain.get("turns")).size());
    }

    /** 跑偏信号 / 拦截数 / 延迟分位 / 成本（§18.4.6 M6、§10.4）。 */
    public Map<String, Object> overview(String authorization, AuditWindow window) {
        Map<String, Object> params = params(window);
        return auditedRead(
                authorization,
                "overview",
                params,
                () -> repository.overview(window),
                body -> 1);
    }

    /**
     * 「先记谁要查审计，再查审计」。
     *
     * <p>注意顺序与失败处理：
     * <ul>
     *   <li>**没有有效身份时不写记录**（否则任何人都能往审计表灌数据）；
     *   <li>有身份但非 admin → 记 DENY 再拒；
     *   <li>查询抛错 → 记 ERROR 后原样抛出（不吞异常）；
     *   <li>记录写不进去 → 直接失败（审计读不留痕比拒绝更危险，§20.4）。
     * </ul>
     */
    private <T> T auditedRead(
            String authorization, String action, Map<String, Object> params, Supplier<T> query, ToIntFunction<T> rowCount) {
        UserIdentity user = currentUserService.requireUser(authorization);
        if (!currentUserService.isAdmin(user.userId())) {
            record(user.userId(), action, params, AuditReadEntry.Outcome.DENY, 0, "非 admin 访问审计入口");
            throw new ForbiddenException();
        }
        T result;
        try {
            result = query.get();
        } catch (RuntimeException e) {
            recordQuietly(user.userId(), action, params, AuditReadEntry.Outcome.ERROR, 0, e.getMessage());
            throw e;
        }
        record(user.userId(), action, params, AuditReadEntry.Outcome.ALLOW, rowCount.applyAsInt(result), null);
        return result;
    }

    private void recordQuietly(
            String who, String action, Map<String, Object> params, AuditReadEntry.Outcome outcome, int rows, String reason) {
        try {
            record(who, action, params, outcome, rows, reason);
        } catch (RuntimeException e) {
            log.error("审计读（{}）失败后写入审计记录也失败：{}", action, e.getMessage(), e);
        }
    }

    private void record(
            String who, String action, Map<String, Object> params, AuditReadEntry.Outcome outcome, int rows, String reason) {
        auditReadWriter.write(new AuditReadEntry(who, action, params, outcome, rows, reason, Instant.now()));
    }

    /** 查询条件快照：时间范围必填，条件按固定顺序写入（便于逐字节比对与管理端展示）。 */
    private static Map<String, Object> params(AuditWindow window, Object... pairs) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", window.from().toString());
        params.put("to", window.to().toString());
        params.put("limit", window.limit());
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = String.valueOf(pairs[i]);
            Object value = pairs[i + 1];
            if (value != null && !(value instanceof String s && s.isBlank())) {
                params.put(key, value);
            }
        }
        return params;
    }
}
