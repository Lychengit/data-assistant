-- V18：导出上传接口（§18.4.5 W1–W3 / §18.6.2 步骤 5）与它的角色授权。
--
-- 为什么需要这个接口：技能（含用户脚本）**在沙箱里没有凭据**（§18.4.4）——它只能把生成好的文件
-- 写到挂载目录，由宿主侧代它调这个接口把文件写进对象存储。所以「导出 → 上传 → 拿链接」这条链路上，
-- 必须有平台侧的写接口承接，而不是让脚本自己连对象存储（那等于把凭据发进容器）。
--
-- 它是 **kind=write**（副作用等级，不是权限）：
--   ① 网关 G3 会要求一次性确认凭据 confirmId（§19.9 技能启动前一次确认）；
--   ② 接口服务侧再做一次"确认凭据存在性"的纵深防御（RegisteredApiAspect）；
--   ③ 审计里这次调用会是一条"写"的记录。
-- 要禁止某角色上传，就不给它这条 role_api——而不是靠 kind。

INSERT INTO sys_api (name, service, kind, resource, http_method, http_path, param_schema, result_schema, scenario, enabled)
VALUES (
    '导出文件上传',
    'interface-doctor',
    'write',
    'object-storage',
    'POST',
    '/doctor/export/upload',
    -- properties 必须与 DoctorExportUploadApi.Args 的字段逐一对上：启动自检拿它和 DTO 对账，
    -- 不一致就不让接口服务启动（说明书与实际入参边界不能是两份清单）。
    '{"type":"object","required":["file_name","content_base64"],"properties":{
        "file_name":{"type":"string","description":"文件名（含扩展名）。只允许 xlsx/xls/csv/pdf/docx/doc/txt/json/png/jpg/jpeg/zip；路径分隔符会被剥掉"},
        "content_base64":{"type":"string","description":"文件内容的 base64。单文件上限 20MB；内容按 SHA-256 寻址，重复上传不会产生第二个对象"},
        "content_type":{"type":"string","description":"可选，MIME 类型，仅作记录"},
        "session_id":{"type":"string","description":"可选，归属会话；技能导出时由平台带上"},
        "artifact_name":{"type":"string","description":"可选，工件的展示名"}
     },"additionalProperties":false}'::jsonb,
    -- 导出字段评审（M3）的上界就取自绑定接口 result_schema 的字段名并集——导出件里能出现哪些链接/编号
    -- 由接口自己写死，不在台账里另配一份白名单（§18.4.6 M3 说明）。
    '{"type":"object","properties":{
        "artifact_id":{"type":"string"},
        "file_name":{"type":"string"},
        "size_bytes":{"type":"integer"},
        "content_sha256":{"type":"string"},
        "storage_key":{"type":"string"},
        "download_url":{"type":"string"},
        "download_url_expires_at":{"type":"string"},
        "object_expires_at":{"type":"string"}
     }}'::jsonb,
    '把一份已生成的导出文件写进对象存储，登记工件，并返回**短期有效**的下载链接（默认 30 分钟，对象保留 7 天）。写操作，需要确认凭据。',
    TRUE
)
ON CONFLICT DO NOTHING;

-- 授权给 admin（角色直配接口，§19.1 多角色并集）。
-- 注意这里**没有**范围列：能不能调由 role_api 回答，"能看多大范围"由接口服务基于登录人自己推导。
INSERT INTO role_api (role_id, api_id)
SELECT r.id, a.id
  FROM sys_role r
  JOIN sys_api a ON a.service = 'interface-doctor' AND a.http_path = '/doctor/export/upload'
 WHERE r.role_code = 'admin'
ON CONFLICT (role_id, api_id) DO NOTHING;

-- 顺带把这条写接口登记进配置审计的口径里（谁改过接口注册表要能查）。
INSERT INTO config_audit (who, target, field, before, after, request_id)
VALUES ('migration:V18', 'api', 'doctor_export_upload',
        NULL,
        '{"service":"interface-doctor","httpPath":"/doctor/export/upload","kind":"write","grantedRole":"admin"}'::jsonb,
        'migration-V18');