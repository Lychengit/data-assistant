package com.djzy.assistant.management.repo;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 口径字典条目（{@code metric_dictionary}，§6.1 / §18.4.6 M5）。
 *
 * <p>这是**模型的数值边界**：指标名/别名/公式/时间口径都在这里配置，不在代码里写死；
 * 接口服务只接受字典内的 {@code metricKey}（§6.1 约束）。
 *
 * @param metricKey 指标键（唯一）
 * @param domain 所属域
 * @param name 指标名
 * @param aliases 别名（用户口语 → 指标键）
 * @param definition 定义（口径一句话，注入 System Prompt 的精简版来源）
 * @param formula 计算公式文本（如「利润 = 收入 - 成本」）
 * @param timeBasis 时间口径（{@code pay_time} / {@code natural_month} …）
 * @param unit 基准单位
 * @param scale 可读单位换算系数（1 万 = 10000）
 * @param rounding 展示精度（小数位）——复算容差的唯一来源（§6.3）
 * @param derivedOf 派生声明（源指标 / 算子 / 基期）
 * @param timezone 该指标的时间口径时区（默认 Asia/Shanghai）
 * @param basis 口径说明（溯源标注用，§6.5）
 * @param enabled 是否启用（停用后接口服务按「未命中字典」拒绝，§4.3 L2）
 */
public record MetricDefinitionView(
        String metricKey,
        String domain,
        String name,
        List<String> aliases,
        String definition,
        String formula,
        String timeBasis,
        String unit,
        BigDecimal scale,
        int rounding,
        Map<String, Object> derivedOf,
        String timezone,
        String basis,
        boolean enabled) {

    /** 审计用的规范化快照（字段顺序固定，便于 before/after 逐字节比对）。 */
    public Map<String, Object> toAuditMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("metricKey", metricKey);
        map.put("domain", domain);
        map.put("name", name);
        map.put("aliases", aliases);
        map.put("definition", definition);
        map.put("formula", formula);
        map.put("timeBasis", timeBasis);
        map.put("unit", unit);
        map.put("scale", scale);
        map.put("rounding", rounding);
        map.put("derivedOf", derivedOf);
        map.put("timezone", timezone);
        map.put("basis", basis);
        map.put("enabled", enabled);
        return map;
    }
}
