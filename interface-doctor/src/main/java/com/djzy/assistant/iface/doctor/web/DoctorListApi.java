package com.djzy.assistant.iface.doctor.web;

import com.djzy.assistant.common.api.ApiEnvelope;
import com.djzy.assistant.iface.doctor.config.DoctorInterfaceProperties;
import com.djzy.assistant.iface.doctor.core.DoctorQuery;
import com.djzy.assistant.iface.doctor.repo.DoctorQueryExecutor;
import com.djzy.assistant.iface.doctor.service.DoctorQueryResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口：医生名单（{@code POST /doctor/list}）。
 *
 * <p>存在理由不是"查得全"，而是**让模型先拿到真实的科室取值再过滤**：科室字段里存的是中文科室名
 * （心内科 / 呼吸科 …），模型很容易按直觉翻成拼音或英文去过滤 {@code /doctor/performance}，
 * 那样结果是空的且看不出错在哪。接口的 {@code scenario} 说明了这件事，模型据此先调这里。
 *
 * <p>约定同 {@link DoctorPerformanceApi}：路径与 DTO 字段都由启动自检与 {@code sys_api} 对账。
 */
@RestController
public class DoctorListApi {

    public static final String PATH = "/doctor/list";

    private final DoctorQueryExecutor executor;
    private final int maxRows;

    public DoctorListApi(DoctorQueryExecutor executor, DoctorInterfaceProperties properties) {
        this.executor = executor;
        this.maxRows = properties.getMaxRows();
    }

    @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public DoctorQueryResult list(@Valid @RequestBody ApiEnvelope<Args> body) {
        Args args = body.args();

        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT doctor_id, doctor_name, dept_code FROM doctor_metric");
        List<Object> params = new java.util.ArrayList<>();
        if (args.deptCode() != null && !args.deptCode().isBlank()) {
            sql.append(" WHERE dept_code = ?");
            params.add(args.deptCode());
        }
        sql.append(" ORDER BY doctor_id ASC LIMIT ?");
        params.add(maxRows + 1);

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("service", "interface-doctor");
        provenance.put("httpPath", PATH);
        provenance.put("requestId", body.caller().requestId());

        return DoctorQueryResult.of(PATH, executor.fetch(new DoctorQuery(sql.toString(), params)), maxRows, provenance);
    }

    /** 入参：字段名必须与 {@code sys_api.param_schema.properties} 一致（启动自检会对账）。 */
    public record Args(@JsonProperty("dept_code") String deptCode) {}
}