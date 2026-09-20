-- V11：接口入参契约补齐（§4.8 节点 A / §19.8）
--
-- 为什么动这几行：capabilities 只下发 api_code 时，agent-service 建出来的工具 schema 是空的，
-- 模型只能猜参数名（实测猜成 period / department），每次调用都被接口服务 I3 以「不支持的参数」拒掉——
-- 现象就是「接口看得见、却一个都调不通」。现在 capabilities 会把 param_schema 原样下发给
-- agent-service 当工具 schema，所以这一列从「接口服务自用」升级为「同时给模型看的契约」。
--
-- 为什么从扁平表换成完整 JSON Schema：模型最需要的是 required 与 description
-- （哪些必填、每个参数是什么意思）。扁平表（{"month":{"type":"string"}}）带不了这两条。
--
-- 写入方：management-service M4 接口注册页（仍可编辑）；读取方：data-gateway（capabilities）
-- → agent-service（工具 schema）。骨架期零缓存，改完即刻生效，无需重启（§0.3-4 / §19.8）。

UPDATE sys_api
   SET param_schema = '{
         "type": "object",
         "properties": {
           "month":      {"type": "string", "pattern": "^[0-9]{4}-[0-9]{2}$", "description": "统计月份，格式 YYYY-MM，例如 2026-08"},
           "metric_key": {"type": "string", "description": "指标键，必须命中指标字典：outpatient_visits=门诊人次、drug_ratio=药占比"},
           "doctor_id":  {"type": "string", "description": "医生 id（可选；不传返回范围内全部医生）"},
           "dept_code":  {"type": "string", "description": "科室编码（可选；不传按授权范围返回全部科室）"}
         },
         "required": ["month", "metric_key"],
         "additionalProperties": false
       }'::jsonb
 WHERE api_code = 'doctor_performance';

UPDATE sys_api
   SET param_schema = '{
         "type": "object",
         "properties": {
           "dept_code": {"type": "string", "description": "科室编码（可选；不传按授权范围返回全部科室）"}
         },
         "additionalProperties": false
       }'::jsonb
 WHERE api_code = 'doctor_list';
