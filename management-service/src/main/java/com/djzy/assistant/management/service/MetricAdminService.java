package com.djzy.assistant.management.service;

import com.djzy.assistant.common.config.ConfigAuditEntry;
import com.djzy.assistant.common.config.ConfigAuditWriter;
import com.djzy.assistant.common.ledger.ExpressionParser;
import com.djzy.assistant.management.repo.MetricAdminRepository;
import com.djzy.assistant.management.repo.MetricDefinitionView;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M5 口径字典（§6.1 / §18.4.6 / §20.7）。
 *
 * <p>数字复算（§6.3）的三块输入都在这里配置：**精度 `rounding`**（容差的唯一来源）、
 * **单位换算**（校验前先归一）、**派生声明 `derived_of`**（算子必须命中白名单）。
 * 因此这里的校验不是「输入友好」，而是 **fail-closed**：宁可 400，也不让一个算不出来的口径进库。
 */
@Service
public class MetricAdminService {

    private static final String TARGET = "dictionary";

    private static final Pattern METRIC_KEY = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final Pattern TIME_BASIS = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    private static final Pattern UNIT = Pattern.compile("^[A-Za-z%\\u4e00-\\u9fa5]{1,16}$");

    /** 派生声明允许出现的键：多一个都不行（未认识的声明结构一律拒绝，§6.1 实现标注）。 */
    private static final Set<String> DERIVED_KEYS = Set.of("sources", "operator", "basis", "window");

    private static final int MAX_ROUNDING = 6;
    private static final int MAX_ALIASES = 8;

    private final MetricAdminRepository repository;
    private final ConfigAuditWriter configAuditWriter;

    public MetricAdminService(MetricAdminRepository repository, ConfigAuditWriter configAuditWriter) {
        this.repository = repository;
        this.configAuditWriter = configAuditWriter;
    }

    public List<MetricDefinitionView> list() {
        return repository.list();
    }

    public MetricDefinitionView get(String metricKey) {
        return repository.find(requireText(metricKey, "指标键"))
                .orElseThrow(() -> new IllegalArgumentException("指标未登记：" + metricKey));
    }

    public List<Map<String, Object>> unitConversions() {
        return repository.listUnitConversions();
    }

    /**
     * 登记 / 更新一个指标口径。
     *
     * @throws IllegalArgumentException 指标键非法、精度越界、时区不存在、派生算子不在白名单
     */
    @Transactional
    public MetricDefinitionView upsert(MetricDefinitionView metric, String who, String requestId) {
        MetricDefinitionView valid = validate(metric);
        MetricDefinitionView before = repository.find(valid.metricKey()).orElse(null);
        repository.upsert(valid);
        MetricDefinitionView after = repository.find(valid.metricKey()).orElseThrow();
        audit(who, field(valid.metricKey()), before == null ? null : before.toAuditMap(), after.toAuditMap(), requestId);
        return after;
    }

    /** 启用 / 停用：停用后接口服务按「未命中字典」拒绝（§4.3 L2），模型也不会再看到它。 */
    @Transactional
    public MetricDefinitionView setEnabled(String metricKey, boolean enabled, String who, String requestId) {
        String key = requireText(metricKey, "指标键");
        MetricDefinitionView before = repository.find(key)
                .orElseThrow(() -> new IllegalArgumentException("指标未登记：" + key));
        if (before.enabled() == enabled) {
            return before;
        }
        if (!repository.setEnabled(key, enabled)) {
            throw new IllegalArgumentException("指标未登记：" + key);
        }
        MetricDefinitionView after = repository.find(key).orElseThrow();
        audit(who, field(key), before.toAuditMap(), after.toAuditMap(), requestId);
        return after;
    }

    @Transactional
    public boolean delete(String metricKey, String who, String requestId) {
        String key = requireText(metricKey, "指标键");
        MetricDefinitionView before = repository.find(key).orElse(null);
        if (before == null) {
            return false;
        }
        if (!repository.delete(key)) {
            throw new IllegalArgumentException("指标未登记：" + key);
        }
        audit(who, field(key), before.toAuditMap(), null, requestId);
        return true;
    }

    /** 登记 / 覆盖单位换算（§6.1：所有单位换算集中一张表，校验前先归一）。 */
    @Transactional
    public void upsertUnitConversion(
            String fromUnit, String toUnit, BigDecimal factor, String who, String requestId) {
        String from = requireText(fromUnit, "源单位");
        String to = requireText(toUnit, "目标单位");
        if (!UNIT.matcher(from).matches() || !UNIT.matcher(to).matches()) {
            throw new IllegalArgumentException("单位名不合法：" + from + " → " + to);
        }
        if (factor == null || factor.signum() <= 0) {
            throw new IllegalArgumentException("换算系数必须为正数：" + from + " → " + to);
        }
        repository.upsertUnitConversion(from, to, factor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fromUnit", from);
        after.put("toUnit", to);
        after.put("factor", factor);
        audit(who, "unit_convert:" + from + "->" + to, null, after, requestId);
    }

    private MetricDefinitionView validate(MetricDefinitionView metric) {
        if (metric == null) {
            throw new IllegalArgumentException("缺少指标定义");
        }
        String key = requireText(metric.metricKey(), "指标键");
        if (!METRIC_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("指标键不合法（小写字母开头，数字/下划线）：" + key);
        }
        String name = requireText(metric.name(), "指标名");
        if (name.length() > 128) {
            throw new IllegalArgumentException("指标名过长（≤128）：" + key);
        }
        if (metric.rounding() < 0 || metric.rounding() > MAX_ROUNDING) {
            throw new IllegalArgumentException("展示精度必须是 0–" + MAX_ROUNDING + " 的整数：" + key);
        }
        if (metric.scale() != null && metric.scale().signum() <= 0) {
            throw new IllegalArgumentException("单位换算系数必须为正数：" + key);
        }
        String unit = metric.unit() == null || metric.unit().isBlank() ? null : metric.unit().trim();
        if (unit != null && !UNIT.matcher(unit).matches()) {
            throw new IllegalArgumentException("单位名不合法：" + unit);
        }
        String timeBasis = metric.timeBasis() == null || metric.timeBasis().isBlank() ? null : metric.timeBasis().trim();
        if (timeBasis != null && !TIME_BASIS.matcher(timeBasis).matches()) {
            throw new IllegalArgumentException("时间口径不合法（如 natural_month / pay_time）：" + timeBasis);
        }
        String timezone = metric.timezone() == null || metric.timezone().isBlank()
                ? "Asia/Shanghai"
                : metric.timezone().trim();
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("时区不合法：" + timezone);
        }
        List<String> aliases = normalizeAliases(metric.aliases(), key);
        Map<String, Object> derivedOf = normalizeDerivedOf(metric.derivedOf(), key);
        return new MetricDefinitionView(
                key,
                blankToNull(metric.domain()),
                name,
                aliases,
                blankToNull(metric.definition()),
                blankToNull(metric.formula()),
                timeBasis,
                unit,
                metric.scale(),
                metric.rounding(),
                derivedOf,
                timezone,
                blankToNull(metric.basis()),
                metric.enabled());
    }

    private static List<String> normalizeAliases(List<String> aliases, String metricKey) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                throw new IllegalArgumentException("别名不能为空：" + metricKey);
            }
            String value = alias.trim();
            if (value.length() > 64) {
                throw new IllegalArgumentException("别名过长（≤64）：" + value);
            }
            if (!cleaned.contains(value)) {
                cleaned.add(value);
            }
        }
        if (cleaned.size() > MAX_ALIASES) {
            throw new IllegalArgumentException("别名过多（≤" + MAX_ALIASES + "）：" + metricKey);
        }
        return List.copyOf(cleaned);
    }

    /**
     * 派生声明校验：只认 {@code sources / operator / basis / window}，
     * 且 {@code operator} 必须命中算式复算的算子白名单（**同一份实现**，§6.3）。
     */
    private static Map<String, Object> normalizeDerivedOf(Map<String, Object> derivedOf, String metricKey) {
        if (derivedOf == null || derivedOf.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : derivedOf.entrySet()) {
            String field = entry.getKey();
            if (field == null || !DERIVED_KEYS.contains(field)) {
                throw new IllegalArgumentException("派生声明含未知字段：" + field + "（" + metricKey + "）");
            }
            Object value = entry.getValue();
            if ("operator".equals(field)) {
                if (!ExpressionParser.isAllowedOperator(value == null ? null : String.valueOf(value))) {
                    throw new IllegalArgumentException("派生算子不在白名单：" + value + "（可用："
                            + String.join(", ", ExpressionParser.ALLOWED_OPERATORS) + "）");
                }
                cleaned.put(field, String.valueOf(value).trim().toUpperCase(java.util.Locale.ROOT));
                continue;
            }
            if ("sources".equals(field)) {
                if (!(value instanceof List<?> list) || list.isEmpty()) {
                    throw new IllegalArgumentException("派生声明必须列出源指标：" + metricKey);
                }
                List<String> sources = new ArrayList<>();
                for (Object item : list) {
                    if (item == null || String.valueOf(item).isBlank()) {
                        throw new IllegalArgumentException("派生源指标不能为空：" + metricKey);
                    }
                    sources.add(String.valueOf(item).trim());
                }
                cleaned.put(field, List.copyOf(sources));
                continue;
            }
            if (value == null || (value instanceof String s && s.isBlank())) {
                throw new IllegalArgumentException("派生声明字段不能为空：" + field + "（" + metricKey + "）");
            }
            cleaned.put(field, value);
        }
        if (!cleaned.containsKey("operator")) {
            throw new IllegalArgumentException("派生声明必须给算子 operator：" + metricKey);
        }
        return Map.copyOf(cleaned);
    }

    private void audit(String who, String field, Map<String, Object> before, Map<String, Object> after, String requestId) {
        configAuditWriter.write(new ConfigAuditEntry(who, TARGET, field, before, after, requestId, Instant.now()));
    }

    private static String field(String metricKey) {
        return "metric_dictionary:" + metricKey;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少" + what);
        }
        return value.trim();
    }
}
