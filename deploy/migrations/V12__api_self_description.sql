-- 接口自描述（§4.8 节点 A / §19.8）：让模型知道「什么时候用」「参数什么格式」「能拿到什么字段」。
--
-- 为什么要有这一版：V11 只把入参补成了 JSON Schema。接口一多，模型仍然会选错接口、用错字段——
-- 它缺三样东西：
--   ① 这个接口适用于什么场景、什么时候**不该**用它（选接口靠这条）；
--   ② 每个入参的格式与示例（V11 只有 pattern，模型容易把「心内科」写成 cardiology）；
--   ③ 返回有哪些字段、各自什么含义（此前完全没有，模型只能等结果回来才知道）。
-- ①② 进工具描述与入参 schema，③ 只能进工具描述（工具 schema 描述的是入参），所以单独一列。

ALTER TABLE sys_api ADD COLUMN IF NOT EXISTS scenario TEXT;
ALTER TABLE sys_api ADD COLUMN IF NOT EXISTS result_schema JSONB;

COMMENT ON COLUMN sys_api.scenario IS '适用场景（进模型工具描述）：什么时候用、什么时候不该用';
COMMENT ON COLUMN sys_api.result_schema IS '返回字段契约（进模型工具描述）：字段名 → {type, description}';

-- ============ 医生绩效明细 ============
UPDATE sys_api
   SET param_schema = '{
         "type": "object",
         "required": ["month", "metric_key"],
         "additionalProperties": false,
         "properties": {
           "month": {"type": "string", "pattern": "^[0-9]{4}-[0-9]{2}$", "example": "2026-08",
                     "description": "统计月份，格式 YYYY-MM，只支持整月（例 2026-08）。不支持区间、单日或季度"},
           "metric_key": {"type": "string", "example": "outpatient_visits",
                     "description": "指标键，必须命中指标字典：outpatient_visits=门诊人次、drug_ratio=药占比。不接受中文名，也不能自造键"},
           "dept_code": {"type": "string", "example": "心内科",
                     "description": "科室名称（中文，如 心内科）。不传=按授权范围返回全部科室；不要把它翻译成英文或拼音，真实取值可用 doctor_list 查看"},
           "doctor_id": {"type": "string", "example": "1",
                     "description": "医生编号。不传=返回范围内全部医生；建议先用 doctor_list 拿到编号再过滤"}
         }
       }'::jsonb,
       scenario = '按【月份 + 指标】取医生绩效明细，一行 = 一个医生 × 一个指标。适用：用户问某月的门诊量、药占比、医生排名、明细，或问科室汇总（拿明细自行汇总）。不适用：问「有哪些医生」「科室编码」用 doctor_list；问指标的定义/口径/单位用口径字典，不要调这个接口。缺月份时不要拿本月数据代替用户要的月份。',
       result_schema = '{
         "doctor_id": {"type": "string", "description": "医生编号，同一医生跨月不变"},
         "doctor_name": {"type": "string", "description": "医生姓名"},
         "dept_code": {"type": "string", "description": "科室名称（中文，如 心内科）"},
         "stat_month": {"type": "string", "description": "统计月份 YYYY-MM，与入参 month 一致"},
         "metric_key": {"type": "string", "description": "指标键，与入参 metric_key 一致"},
         "metric_value": {"type": "number", "description": "指标值：门诊人次单位是「人次」；药占比是 0~1 的小数（0.31 即 31%）"}
       }'::jsonb
 WHERE api_code = 'doctor_performance';

-- ============ 医生列表 ============
UPDATE sys_api
   SET param_schema = '{
         "type": "object",
         "additionalProperties": false,
         "properties": {
           "dept_code": {"type": "string", "example": "心内科",
                     "description": "科室名称（中文，如 心内科）。不传=返回授权范围内全部医生"}
         }
       }'::jsonb,
       scenario = '取授权范围内的医生名单（医生编号 + 姓名 + 科室名称）。适用：用户问「有哪些医生」「某科室有哪些医生」「医生编号是什么」，或需要先用真实取值来过滤别的接口。不适用：要任何指标数值（门诊量、药占比等）都用 doctor_performance，这个接口不含指标数据。',
       result_schema = '{
         "doctor_id": {"type": "string", "description": "医生编号，可作为 doctor_performance 的 doctor_id 入参"},
         "doctor_name": {"type": "string", "description": "医生姓名"},
         "dept_code": {"type": "string", "description": "科室名称（中文，如 心内科）"}
       }'::jsonb
 WHERE api_code = 'doctor_list';