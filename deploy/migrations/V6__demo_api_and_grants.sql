-- 骨架期演示数据：接口注册 + 角色授权（§4.7 / §18.4.6 M2/M4 / §19.1）。
--
-- 目的：让「授权 → 接口服务按登录人拼 WHERE → 不同角色看到不同数据」这条链**开箱可跑**。
--
-- **历史写法提示**：下面的 column_whitelist 与 role_api.scope（心内科 / 呼吸科）都是当时的字段，
-- V13 已把这两列删掉——返回哪些列由接口自己的 SQL 写死，范围由接口服务基于登录人推导（§19.1 / ADR-37）。
-- 这里保留原样是「迁移历史不改」的惯例：已跑过的库不会重跑它，重放时也是写完随即被 V13 删掉。
-- 与 V3 的 doctor_metric 演示数据配套；接入真实数据前整体替换。

-- ============ 接口注册（M4 管理的对象）============
-- 列白名单与 interface-doctor 的 DoctorApiCatalog 保持一致（§4.8 节点 F）。
INSERT INTO sys_api (api_code, name, service, method, resource, param_schema, column_whitelist, enabled) VALUES
    ('doctor_performance', '医生绩效明细', 'interface-doctor', 'read', 'doctor',
     '{"month":{"type":"string"},"metric_key":{"type":"string"},"doctor_id":{"type":"string"}}'::jsonb,
     '["doctor_id","doctor_name","dept_code","stat_month","metric_key","metric_value"]'::jsonb, TRUE),
    ('doctor_list', '医生列表', 'interface-doctor', 'read', 'doctor',
     '{"dept_code":{"type":"string"}}'::jsonb,
     '["doctor_id","doctor_name","dept_code"]'::jsonb, TRUE)
ON CONFLICT (api_code) DO NOTHING;

-- ============ 角色（M2 管理的对象）============
INSERT INTO sys_role (role_code, role_name, remark) VALUES
    ('director', '科室主任', '演示多角色并集（§19.1）')
ON CONFLICT (role_code) DO NOTHING;

-- ============ 演示授权 ============
-- boss 只看心内科、director 只看呼吸科、admin 全量；alice 同时持有 boss 与 director。
INSERT INTO role_api (role_id, api_id, scope)
SELECT r.id, a.id, v.scope::jsonb
  FROM (VALUES
        ('boss',     'doctor_performance', '{"depts":["心内科"]}'),
        ('boss',     'doctor_list',        '{"depts":["心内科"]}'),
        ('director', 'doctor_performance', '{"depts":["呼吸科"]}'),
        ('director', 'doctor_list',        '{"depts":["呼吸科"]}'),
        ('admin',    'doctor_performance', '{"all":true}'),
        ('admin',    'doctor_list',        '{"all":true}')
       ) AS v(role_code, api_code, scope)
  JOIN sys_role r ON r.role_code = v.role_code
  JOIN sys_api  a ON a.api_code  = v.api_code
ON CONFLICT (role_id, api_id) DO NOTHING;

-- alice：boss + director 两个角色（当年演示的是「范围取并集」；现在并集只影响接口可用性，范围看接口自己）
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
  FROM sys_user u JOIN sys_role r ON r.role_code = 'director'
 WHERE u.username = 'alice'
ON CONFLICT DO NOTHING;
