package com.djzy.assistant.iface.doctor.core;

import java.util.List;

/** 参数化查询（SQL + 绑定值）：接口服务只执行这种形态，永不拼字符串。 */
public record DoctorQuery(String sql, List<Object> params) {

    public DoctorQuery {
        params = params == null ? List.of() : List.copyOf(params);
    }

    public Object[] arguments() {
        return params.toArray();
    }
}
