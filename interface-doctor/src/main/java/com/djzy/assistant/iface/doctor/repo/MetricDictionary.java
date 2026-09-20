package com.djzy.assistant.iface.doctor.repo;

import java.util.Optional;

/** 口径字典读取端口（§4.3 L2：指标必须命中口径字典）。 */
@FunctionalInterface
public interface MetricDictionary {

    Optional<MetricInfo> find(String metricKey);
}
