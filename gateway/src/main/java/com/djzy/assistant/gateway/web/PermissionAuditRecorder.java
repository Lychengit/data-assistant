package com.djzy.assistant.gateway.web;

import com.djzy.assistant.common.permission.PermissionDecision;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次接口调用的「权限结论」暂存盒。
 *
 * <p>控制器只负责**下结论**（放行 / 拒绝 + 原因），**怎么写审计不归它管**：
 * 方法返回后由 {@link PermissionAuditAspect} 统一把盒子里的结论落成审计。
 * 于是控制器里再也看不见 {@code auditWriter}、{@code PermissionAuditEntry}、角色查询这些东西，
 * 只剩业务主流程。
 *
 * <p>一个请求一个盒子，挂在**请求属性**上（不是 ThreadLocal）：请求属性随请求生灭，
 * 不用惦记清理，也不会把上一个请求的结论带给下一个请求。
 *
 * <p>为什么装的是「一串结论」而不是「一个结论」：放行之后下游可能失败
 * （{@code DOWNSTREAM_UNAVAILABLE}），这两件事都要留痕，所以按发生顺序全部记下来。
 */
public final class PermissionAuditRecorder {

    /** 存放本盒子的请求属性名。 */
    public static final String REQUEST_ATTRIBUTE = "gateway.permissionAudit";

    private final List<Decision> decisions = new ArrayList<>();

    private PermissionAuditRecorder() {}

    /** 取本次请求的盒子；没有就建一个。参数解析器与切面共用它，保证拿到的是同一个。 */
    public static PermissionAuditRecorder of(HttpServletRequest request) {
        PermissionAuditRecorder existing = find(request);
        if (existing != null) {
            return existing;
        }
        PermissionAuditRecorder created = new PermissionAuditRecorder();
        request.setAttribute(REQUEST_ATTRIBUTE, created);
        return created;
    }

    /** 取本次请求的盒子；没建过就是 {@code null}（切面用它判断"这次调用根本没记过账"）。 */
    public static PermissionAuditRecorder find(HttpServletRequest request) {
        return request.getAttribute(REQUEST_ATTRIBUTE) instanceof PermissionAuditRecorder recorder ? recorder : null;
    }

    /** 记一条「放行」。 */
    public void allow() {
        decisions.add(new Decision(PermissionDecision.ALLOW, null));
    }

    /** 记一条「拒绝」。原因只进审计，不进对外响应（对外一律统一措辞，防探测）。 */
    public void deny(String reason) {
        decisions.add(new Decision(PermissionDecision.DENY, reason));
    }

    public List<Decision> decisions() {
        return List.copyOf(decisions);
    }

    /** 一条待落库的权限结论。 */
    public record Decision(PermissionDecision decision, String reason) {}
}
