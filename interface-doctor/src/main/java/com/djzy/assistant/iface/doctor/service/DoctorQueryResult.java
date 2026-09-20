package com.djzy.assistant.iface.doctor.service;

import com.djzy.assistant.common.api.ApiResult;
import java.util.List;
import java.util.Map;

/**
 * 接口服务返回（§18.4.5 I5 执行并标注来源）。
 *
 * <p>实现 {@link ApiResult} 是为了让审计切面能自动取到行数与截断标记——新增接口因此不用写审计代码。
 *
 * <p>为什么没有"列白名单"字段：返回哪些列由各自的 SQL 写死（列白名单要想在运行时兜底，就得先在配置里
 * 维护一份与 SQL 平行的清单，而那份清单没人能保证和 SQL 同步）。代价是没有运行时兜底，
 * 因此**接口的返回字段一律在 SQL 里显式列出，禁止 {@code SELECT *}**。
 *
 * @param httpPath 产出这份结果的接口路径（便于从响应体直接看出数据来自哪个端点）
 * @param rows 行数据
 * @param truncated 是否因行数上限被截断
 * @param provenance 溯源标注：哪个指标、哪个时间窗、来自哪个接口（§6.5）
 */
public record DoctorQueryResult(
        String httpPath, List<Map<String, Object>> rows, boolean truncated, Map<String, Object> provenance)
        implements ApiResult {

    public DoctorQueryResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
    }

    /**
     * 多查一行（{@code maxRows + 1}）以便识别"被截断"，而不是悄悄少给数据。
     *
     * <p>放在这里而不是各接口里，是因为"截断"这条语义一旦某个接口忘了做，
     * 表现是"数据看起来是全的，其实少了几百行"——这种错没人能从结果上看出来。
     */
    public static DoctorQueryResult of(
            String httpPath, List<Map<String, Object>> fetched, int maxRows, Map<String, Object> provenance) {
        int limit = Math.max(1, maxRows);
        boolean truncated = fetched.size() > limit;
        List<Map<String, Object>> rows = truncated ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        return new DoctorQueryResult(httpPath, rows, truncated, provenance);
    }
}