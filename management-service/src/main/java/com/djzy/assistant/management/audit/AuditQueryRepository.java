package com.djzy.assistant.management.audit;

import java.util.List;
import java.util.Map;

/**
 * 审计查询（只读出口，§18.4.6 / §20.4）。
 *
 * <p>实现**必须**跑在只读连接上，且**不套用户范围过滤**：审计记录不挂在业务对象树上，
 * 套范围过滤既没有意义、又会查不全。所有查询一律带时间范围（{@link AuditWindow}）。
 */
public interface AuditQueryRepository {

    /** 数据访问审计明细（I6 权威源），支持按请求编号 / 用户 / 接口路径 / 结果过滤。 */
    List<Map<String, Object>> dataAccess(
            AuditWindow window, String requestId, String userId, String httpPath, String outcome);

    /** 权限判定审计（网关单点判定的记录），支持按链路 / 用户 / 结论过滤。 */
    List<Map<String, Object>> permissionAudits(AuditWindow window, String traceId, String userId, String decision);

    /** 配置变更审计（§20.7：M2/M3/M4/M5 的每次改动）。 */
    List<Map<String, Object>> configAudits(AuditWindow window, String target, String who);

    /** 「谁在何时查了审计」（§20.4）。 */
    List<Map<String, Object>> auditReads(AuditWindow window, String who);

    /**
     * 按请求编号 / 链路 id 回看整条链路（§20.4 / §8.3）：
     * 会话轮次、步骤、模型调用、工具调用、权限判定、数据访问。
     *
     * @return 链路段：{@code turn / steps / llmCalls / toolCalls / permissionAudits / dataAccess}
     */
    Map<String, Object> trace(String requestId, String traceId, AuditWindow window);

    /** 跑偏信号 / 拦截数 / 延迟分位 / 成本（§18.4.6 M6、§10.4）。 */
    Map<String, Object> overview(AuditWindow window);
}
