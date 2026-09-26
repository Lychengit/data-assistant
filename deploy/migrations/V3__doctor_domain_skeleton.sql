-- 医生域**骨架期演示表**：用于打通「接口服务按登录人拼 WHERE → 自己的 SQL 定返回列」这条链路（§4.8 节点 F）。
-- （早前这里写的是「网关下发范围 → … → 列白名单投影」，那套口径已废：范围不下发、也没有列白名单，见 V13 / ADR-37。）
--
-- 注意：ADR-15 明确「骨架期只搭流水线框架，业务接口契约与业务测试表后置」。
-- 本文件只是让骨架**可运行、可演示**；接入真实医生数据前必须整体替换或删除。
-- 演示用部门编码直接取部门名（真实域应使用 sys_dept 的部门编码）。

CREATE TABLE doctor_metric (
    doctor_id    VARCHAR(64)  NOT NULL,
    doctor_name  VARCHAR(64),
    dept_code    VARCHAR(64)  NOT NULL,
    stat_month   VARCHAR(7)   NOT NULL,               -- YYYY-MM
    metric_key   VARCHAR(64)  NOT NULL,
    metric_value NUMERIC(18, 2),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_doctor_metric_dept_month ON doctor_metric (dept_code, stat_month);
CREATE INDEX ix_doctor_metric_doctor ON doctor_metric (doctor_id, stat_month);

INSERT INTO doctor_metric (doctor_id, doctor_name, dept_code, stat_month, metric_key, metric_value) VALUES
    ('1', '张医生', '心内科', '2026-08', 'outpatient_visits', 120),
    ('2', '李医生', '心内科', '2026-08', 'outpatient_visits',  90),
    ('7', '王医生', '呼吸科', '2026-08', 'outpatient_visits',  75),
    ('8', '赵医生', '呼吸科', '2026-08', 'drug_ratio',        0.31),
    ('9', '钱医生', '财务科', '2026-08', 'outpatient_visits',  10)
ON CONFLICT DO NOTHING;

-- 口径字典（§6.1 / §6.3）：指标必须命中字典才能被接口返回。
INSERT INTO metric_dictionary (metric_key, name, unit, rounding, basis) VALUES
    ('outpatient_visits', '门诊人次', '人次', 0, '按挂号口径统计'),
    ('drug_ratio',        '药占比',   '%',   2, '药品收入 / 医疗收入')
ON CONFLICT (metric_key) DO NOTHING;
