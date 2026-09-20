package com.djzy.assistant.management.repo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 口径字典读写（{@code metric_dictionary} + {@code unit_convert}，§6.1 / §18.4.6 M5）。 */
public interface MetricAdminRepository {

    List<MetricDefinitionView> list();

    Optional<MetricDefinitionView> find(String metricKey);

    void upsert(MetricDefinitionView metric);

    boolean setEnabled(String metricKey, boolean enabled);

    boolean delete(String metricKey);

    /** 单位换算集中表（§6.1）：校验前先把单位归一。 */
    List<Map<String, Object>> listUnitConversions();

    /** 登记 / 覆盖一条换算关系（{@code factor > 0}）。 */
    void upsertUnitConversion(String fromUnit, String toUnit, BigDecimal factor);
}
