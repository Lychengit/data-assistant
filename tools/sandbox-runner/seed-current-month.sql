-- 本机演示数据：给「当前月」补一批医生绩效，让导出技能有真数据可跑。
--
-- 为什么单独放一个文件、而不是塞进 deploy/migrations：
-- 这是**演示数据**，不是表结构。混进迁移会在别的环境里被当成真实业务数据执行一次，
-- 而它的口径（中文科室名、财务科也在名单里）只对本机演示有意义（V3 已经写过同样的提醒）。
--
-- 用法：psql -h 127.0.0.1 -p 5432 -U postgres -d doctor_assistant -f seed-current-month.sql
--       月份按运行时改（下面写死 2026-09，对应当前月份；换月请改 stat_month）。

-- 本月门诊人次：心内科 / 呼吸科 / 财务科各若干
INSERT INTO doctor_metric (doctor_id, doctor_name, dept_code, stat_month, metric_key, metric_value) VALUES
    ('1',  '张医生', '心内科', '2026-09', 'outpatient_visits', 132),
    ('2',  '李医生', '心内科', '2026-09', 'outpatient_visits',  98),
    ('7',  '王医生', '呼吸科', '2026-09', 'outpatient_visits',  81),
    ('9',  '钱医生', '财务科', '2026-09', 'outpatient_visits',  12),
    -- 孙医生本月只有药占比、没有门诊人次：用来演示「名单里有他、但这个指标本月无数据」，
    -- 报表里应如实留空，而不是补 0（0 和「没有数据」在绩效口径里是两件事）。
    ('10', '孙医生', '心内科', '2026-09', 'drug_ratio',         0.44),
    ('8',  '赵医生', '呼吸科', '2026-09', 'drug_ratio',         0.29)
ON CONFLICT DO NOTHING;

-- 口径字典兜底（V3 已经写过，这里再 ON CONFLICT 一次，方便单独在空库上验这个演示）
INSERT INTO metric_dictionary (metric_key, name, unit, rounding, basis) VALUES
    ('outpatient_visits', '门诊人次', '人次', 0, '按挂号口径统计'),
    ('drug_ratio',        '药占比',   '%',   2, '药品收入 / 医疗收入')
ON CONFLICT (metric_key) DO NOTHING;