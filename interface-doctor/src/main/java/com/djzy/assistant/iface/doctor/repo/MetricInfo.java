package com.djzy.assistant.iface.doctor.repo;

/**
 * 口径字典条目（§6.1 metric_dictionary）：指标定义、单位、展示精度与口径说明。
 *
 * @param metricKey 指标键
 * @param name 指标名
 * @param unit 单位（元 / 万元 / % …）
 * @param rounding 展示精度（小数位）
 * @param basis 口径说明（溯源标注用，§6.5）
 */
public record MetricInfo(String metricKey, String name, String unit, int rounding, String basis) {}
