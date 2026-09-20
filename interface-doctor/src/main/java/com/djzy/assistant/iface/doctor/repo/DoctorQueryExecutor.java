package com.djzy.assistant.iface.doctor.repo;

import com.djzy.assistant.iface.doctor.core.DoctorQuery;
import java.util.List;
import java.util.Map;

/** 参数化查询执行端口（接口服务唯一的取数出口）。 */
@FunctionalInterface
public interface DoctorQueryExecutor {

    List<Map<String, Object>> fetch(DoctorQuery query);
}
