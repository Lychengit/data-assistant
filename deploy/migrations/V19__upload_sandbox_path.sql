-- V19：给导出上传接口的入参加 sandbox_path（§18.5.3 宿主代理读沙箱产物）。
--
-- 为什么加：老路子要求模型把沙箱产物的 base64 抄进上传参数——8.5KB 的不透明长串。
-- 2026-09-27 真机实测它抄不动：模型自己打印了 base64 的截断预览，再凭预览加头尾碎片"拼"出
-- 一份 224 字节的坏 xlsx（真文件 6379 字节），上传成功、链接有效、文件打不开。
--
-- 新路子：模型只给沙箱内的**路径**，宿主代理把字节读出来填进 content_base64，并在发往接口服务前
-- 把 sandbox_path 摘掉。所以这个字段接口服务永远读不到值，它进 param_schema 只为一件事——
-- 让"注册表 properties 与入参 DTO 字段逐一对账"的启动自检继续成立（§18.4.5 I0）。
--
-- 注意 required 不变：content_base64 仍必填，只是由**平台**填，不是由模型填。

UPDATE sys_api
   SET param_schema = '{"type":"object","required":["file_name","content_base64"],"properties":{
        "file_name":{"type":"string","description":"文件名（含扩展名）。只允许 xlsx/xls/csv/pdf/docx/doc/txt/json/png/jpg/jpeg/zip；路径分隔符会被剥掉"},
        "sandbox_path":{"type":"string","description":"可选。沙箱内产物路径（如 /workspace/out/perf.xlsx）。给了它就不必给 content_base64：宿主代理会把文件读出来并填好"},
        "content_base64":{"type":"string","description":"文件内容的 base64。单文件上限 20MB；内容按 SHA-256 寻址，重复上传不会产生第二个对象"},
        "content_type":{"type":"string","description":"可选，MIME 类型，仅作记录"},
        "session_id":{"type":"string","description":"可选，归属会话；技能导出时由平台带上"},
        "artifact_name":{"type":"string","description":"可选，工件的展示名"}
       },"additionalProperties":false}'::jsonb,
       updated_at = now()
 WHERE service = 'interface-doctor' AND http_path = '/doctor/export/upload';

INSERT INTO config_audit (who, target, field, before, after, request_id)
VALUES ('migration:V19', 'api', 'doctor_export_upload_param_schema',
        '{"content_base64":"模型填"}'::jsonb,
        '{"sandbox_path":"平台读沙箱后填 content_base64"}'::jsonb,
        'migration-V19');