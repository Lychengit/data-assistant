-- 口径字典补齐 §6.1 规格字段（ADR-24 / §6.1 / §6.3 / §18.4.6 M5）。
--
-- V1 里的 `metric_dictionary` 只有骨架字段（key/name/unit/rounding/derived_of/basis），
-- 规格要求还有 别名、定义、公式、时间口径、可读单位换算、时区——数字复算（§6.3）全部依赖它们，
-- 不能在代码里写死，必须可配置。

ALTER TABLE metric_dictionary
    ADD COLUMN domain       VARCHAR(64),                  -- 指标所属域（如 doctor_performance）
    ADD COLUMN aliases      JSONB,                        -- 别名（用户口语 → 指标键）
    ADD COLUMN definition   TEXT,                         -- 指标定义（口径一句话）
    ADD COLUMN formula      TEXT,                         -- 计算公式（如 利润 = 收入 - 成本）
    ADD COLUMN time_basis   VARCHAR(32),                  -- pay_time / deliver_time / natural_month
    ADD COLUMN scale        NUMERIC(18, 6),               -- 可读单位换算系数（1 万 = 10000）
    ADD COLUMN timezone     VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai';

COMMENT ON COLUMN metric_dictionary.rounding IS '展示精度（小数位）→ 复算容差 = 最后一位的半个单位（§6.3）';
COMMENT ON COLUMN metric_dictionary.derived_of IS '派生指标声明：由哪些源指标、哪个算子、什么基期算出；声明后由工具层确定性计算（§6.1）';

-- 单位换算集中一张表：`128 万` 与 `1280000` 必须能互相换算（§6.3 校验前先归一）。
CREATE TABLE unit_convert (
    from_unit VARCHAR(32)   NOT NULL,
    to_unit   VARCHAR(32)   NOT NULL,
    factor    NUMERIC(18, 6) NOT NULL,
    PRIMARY KEY (from_unit, to_unit),
    CONSTRAINT ck_unit_convert_factor_positive CHECK (factor > 0)
);

-- 基准单位自反换算显式留一行，避免「忘了配置」被当成异常。
-- `CNY` 与 `元` 视为同一基准单位（规格写 CNY、演示数据写「元」）：不补这一对，
-- `万元 → 元` 这条链就断了——`128 万` 归一到「元」时会因为找不到换算关系而算不出（§6.3）。
INSERT INTO unit_convert (from_unit, to_unit, factor) VALUES
    ('CNY', 'CNY', 1), ('元', '元', 1), ('人次', '人次', 1), ('%', '%', 1),
    ('CNY', '元', 1), ('元', 'CNY', 1),
    ('万元', 'CNY', 10000), ('万元', '元', 10000),
    ('万', 'CNY', 10000), ('万', '元', 10000)
ON CONFLICT (from_unit, to_unit) DO NOTHING;

-- 演示指标补齐规格字段（与 V3 种子对齐，只补不覆盖业务值）。
UPDATE metric_dictionary
   SET domain = 'doctor_performance',
       aliases = '["门诊量","门诊人数","接诊量"]'::jsonb,
       definition = '统计期内医生完成的门诊挂号人次',
       time_basis = 'natural_month',
       scale = 1
 WHERE metric_key = 'outpatient_visits' AND domain IS NULL;

UPDATE metric_dictionary
   SET domain = 'doctor_performance',
       aliases = '["药品占比"]'::jsonb,
       definition = '药品收入占医疗收入的比重',
       formula = '药占比 = 药品收入 / 医疗收入',
       time_basis = 'natural_month',
       scale = 1
 WHERE metric_key = 'drug_ratio' AND domain IS NULL;
