package com.djzy.assistant.common.api;

import java.util.List;
import java.util.Map;

/**
 * 接口服务返回值的统一契约（§18.4.5 I5 执行并标注来源）。
 *
 * <p>存在的理由是**让审计能自动记账**：I6 那条"访问是否合规以接口服务为权威源"的记录里要有
 * 返回行数与是否截断，而这两项只有业务代码知道。约定所有接口返回这个契约，切面就能在出口处
 * 取到它们——新增接口因此一行审计代码都不用写（少写一行不报错，但审计会静默缺失，
 * 所以"记不出来"比"写起来烦"严重得多）。
 *
 * <p>为什么返回字段用 {@code rows} 的 {@code Map} 而不是各接口自定义结构：模型看到的就是表格，
 * 每行的字段由接口自己的 SQL 决定（返回字段写死在代码里，比运行时列白名单更硬）。
 */
public interface ApiResult {

    List<Map<String, Object>> rows();

    boolean truncated();

    /** 溯源标注：哪个指标、哪个时间窗、来自哪个接口（§6.5）。 */
    Map<String, Object> provenance();

    default int rowCount() {
        List<Map<String, Object>> rows = rows();
        return rows == null ? 0 : rows.size();
    }
}