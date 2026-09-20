package com.djzy.assistant.iface.doctor.web;

import com.djzy.assistant.common.api.ApiEnvelope;
import com.djzy.assistant.iface.doctor.config.DoctorInterfaceProperties;
import com.djzy.assistant.iface.doctor.core.DoctorQuery;
import com.djzy.assistant.iface.doctor.repo.DoctorQueryExecutor;
import com.djzy.assistant.iface.doctor.repo.MetricDictionary;
import com.djzy.assistant.iface.doctor.repo.MetricInfo;
import com.djzy.assistant.iface.doctor.service.DoctorQueryResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口：医生绩效明细（{@code POST /doctor/performance}）。
 *
 * <p>一个接口一个文件：入参 DTO、校验规则、SQL 都在这一个类里。SQL 写在这里而不是由公共模板拼装，
 * 是因为真实业务的查询会长出连接、窗口函数、按维度分组的复杂形态——模板一旦要"够用"，
 * 就会长成一个谁也改不动的动态 SQL 构造器，而且每个接口的差异都会变成模板里的开关。
 *
 * <p>约定（由启动自检强制，改了会对不上就不让服务启动）：
 * <ul>
 *   <li>路径常量与 {@code @PostMapping} 一致，且已登记在 {@code sys_api}；
 *   <li>{@link Args} 的字段与 {@code sys_api.param_schema.properties} 完全一致。
 * </ul>
 */
@RestController
public class DoctorPerformanceApi {

    public static final String PATH = "/doctor/performance";

    private final MetricDictionary metricDictionary;
    private final DoctorQueryExecutor executor;
    private final int maxRows;

    public DoctorPerformanceApi(
            MetricDictionary metricDictionary, DoctorQueryExecutor executor, DoctorInterfaceProperties properties) {
        this.metricDictionary = metricDictionary;
        this.executor = executor;
        this.maxRows = properties.getMaxRows();
    }

    @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public DoctorQueryResult performance(@Valid @RequestBody ApiEnvelope<Args> body) {
        Args args = body.args();

        // I3：指标必须命中口径字典（§4.3 L2）——查不到就是不可溯源，直接拒绝而不是悄悄少标注
        MetricInfo metric = metricDictionary
                .find(args.metricKey())
                .orElseThrow(() -> new IllegalArgumentException("未知指标：" + args.metricKey()));

        // 每个 where 片段都是代码里的常量，值一律走占位符：接口服务没有任何拼字符串的机会（§4.3 L2）
        List<String> predicates = new ArrayList<>(List.of("stat_month = ?", "metric_key = ?"));
        List<Object> params = new ArrayList<>(List.of(args.month(), metric.metricKey()));
        if (notBlank(args.doctorId())) {
            predicates.add("doctor_id = ?");
            params.add(args.doctorId());
        }
        if (notBlank(args.deptCode())) {
            predicates.add("dept_code = ?");
            params.add(args.deptCode());
        }
        // TODO 数据可见范围：范围管理落地后，在这里按 body.caller().userId() 查出可看科室并追加条件；
        // 同时把实际用到的条件写进审计（现在审计里那一栏是空的，别当成"没有范围限制"）。
        String sql = "SELECT doctor_id, doctor_name, dept_code, stat_month, metric_key, metric_value"
                + " FROM doctor_metric WHERE " + String.join(" AND ", predicates)
                + " ORDER BY metric_value DESC, doctor_id ASC LIMIT ?";
        params.add(maxRows + 1);

        return DoctorQueryResult.of(
                PATH, executor.fetch(new DoctorQuery(sql, params)), maxRows, provenance(args, metric, body));
    }

    private static Map<String, Object> provenance(Args args, MetricInfo metric, ApiEnvelope<Args> body) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("service", "interface-doctor");
        provenance.put("httpPath", PATH);
        provenance.put("requestId", body.caller().requestId());
        provenance.put("month", args.month());
        provenance.put("metricKey", metric.metricKey());
        putIfPresent(provenance, "metricName", metric.name());
        putIfPresent(provenance, "unit", metric.unit());
        putIfPresent(provenance, "basis", metric.basis());
        return provenance;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 入参：字段名必须与 {@code sys_api.param_schema.properties} 一致（启动自检会对账）。 */
    public record Args(
            @NotBlank(message = "month 必填")
                    @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "month 格式应为 YYYY-MM，月份 01-12")
                    String month,
            @NotBlank(message = "metric_key 必填") @JsonProperty("metric_key") String metricKey,
            @JsonProperty("doctor_id") String doctorId,
            @JsonProperty("dept_code") String deptCode) {}
}