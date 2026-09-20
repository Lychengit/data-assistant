package com.djzy.assistant.common.pipeline;

/**
 * 工具执行管线五段（§9.5 / ADR-29）。
 *
 * <pre>
 * PRE     前置：放行 / 拒绝 / 发起询问（ASK）/ 改写本次调用参数        —— 不保证拦住
 * GUARD   守卫：单调收紧，只能拒绝或弃权                            —— 没有放行权力
 * EXECUTE 执行：超时、只读幂等重试、耗时与 token 打点                —— 不判权限、不改业务语义
 * POST    后置：接受 / 拦截 / 替换结果 / 追加提醒上下文               —— 不把失败改成成功
 * RESULT  结果：只读观察（台账、审计、SSE 投影）                     —— 不得修改任何内容
 * </pre>
 *
 * <p>GUARD 必须独立于 PRE：安全约束写在 GUARD 才能保证「无论前面怎么放行，这里能一票否决」。
 */
public enum ToolStage {
    PRE,
    GUARD,
    EXECUTE,
    POST,
    RESULT
}
