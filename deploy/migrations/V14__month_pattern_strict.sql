-- V14：把 month 的格式约束收紧到真实月份（01-12）。
--
-- 为什么：V11 写下的 `^[0-9]{4}-[0-9]{2}$` 连 `2026-13`、`2026-00` 都算合法。实测这两个值会**一路走到 SQL** 去
-- 比 `stat_month = '2026-13'`，命中不了任何行，于是接口回 200 + 空结果。对模型来说这跟「这个月真的没有数据」
-- 长得一模一样——它会照实说「2026-13 没有数据」，而正确行为是当场告诉调用方这个月份根本不存在。
-- 接口侧（DoctorPerformanceApi.Args.month 的 @Pattern）同步收紧；两处必须一致，否则模型看到的说明书
-- 与实际校验口径不同（§4.8 节点 A：说明书就是模型选参的依据）。
UPDATE sys_api
   SET param_schema = jsonb_set(
           jsonb_set(
               param_schema,
               '{properties,month,pattern}',
               '"^[0-9]{4}-(0[1-9]|1[0-2])$"'::jsonb,
               false),
           '{properties,month,description}',
           '"统计月份，格式 YYYY-MM，月份只能是 01-12（例 2026-08）。不支持区间、单日或季度；非法月份会被直接拒绝，不要拿它试参数" '::jsonb,
           false)
 WHERE service = 'interface-doctor'
   AND http_path = '/doctor/performance';