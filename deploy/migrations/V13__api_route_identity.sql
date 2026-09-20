-- 接口身份从「api_code 字符串」改成「(service, http_method, http_path) 三元组」。
--
-- 为什么：api_code 是"第二份清单"——代码里没有任何东西约束它，路由也不用它，只能靠人记住
-- "注册表里写的编码"和"@PostMapping 里实现的路径"保持一致。对不上时，网关按注册表把请求
-- 打到不存在的路径上，接口服务静默 404，而没人知道是哪一边写错了。三元组每一项都能从代码
-- 本身读出来（spring.application.name + @PostMapping），于是"对不上"变成**启动失败**
-- （接口服务启动自检），而不是运行期静默调错接口。
--
-- 同一版迁移顺手砍掉两样东西：
--   ① column_whitelist：返回哪些列由接口自己的 SQL 与返回类型写死（比运行时白名单更硬，
--      多返回一列在编译期就不可能）。技能包的「导出字段评审」上界改用 sys_api.result_schema
--      的字段名并集（V12 已经在补这一列）。
--   ② role_api.scope：授权只回答"能不能调"，能看多大范围由接口服务基于登录人自己推导（§19.1）。
--      把范围挂在授权行上，等于让权限库去猜业务表的结构，接口一复杂就必然要改权限库。

-- ============ sys_api：三元组身份 ============
-- 旧列 method 装的是副作用等级（read/write），名字撞上 HTTP 方法会引起误读，先改名再补新列。
ALTER TABLE sys_api RENAME COLUMN method TO kind;
ALTER TABLE sys_api ADD COLUMN http_method VARCHAR(8);
ALTER TABLE sys_api ADD COLUMN http_path   VARCHAR(255);

-- 两个演示接口：把旧编码翻译成真实路由（编码与路径的对应关系只在这里存在一次）
UPDATE sys_api SET http_method = 'POST', http_path = '/doctor/performance' WHERE api_code = 'doctor_performance';
UPDATE sys_api SET http_method = 'POST', http_path = '/doctor/list'        WHERE api_code = 'doctor_list';

-- 翻译不出路由的行会让下面两条 NOT NULL 失败——这是故意的：宁可迁移失败，也不要留下
-- "有注册行却没有路由"的注册表，那种行只会让网关静默 404。
ALTER TABLE sys_api ALTER COLUMN http_method SET NOT NULL;
ALTER TABLE sys_api ALTER COLUMN http_path   SET NOT NULL;

ALTER TABLE sys_api DROP CONSTRAINT sys_api_api_code_key;
ALTER TABLE sys_api DROP COLUMN api_code;
ALTER TABLE sys_api DROP COLUMN column_whitelist;

-- 三元组唯一：同「方法 + 路径」出现在两个服务上，派生出的模型工具名会撞车（模型只看得到其中一个）
CREATE UNIQUE INDEX uk_sys_api_route ON sys_api (service, upper(http_method), http_path);

-- 路径形态与 ApiRoute 里的正则同源，是**安全边界**而不是格式美观：http_path 会由网关拼进
-- 下游 URL，放过 .. / // / ? 等于把路径穿越与 SSRF 的口子留给"能改注册表的人"。
ALTER TABLE sys_api ADD CONSTRAINT ck_sys_api_path
    CHECK (http_path ~ '^/[A-Za-z0-9/_-]*$' AND http_path NOT LIKE '%//%' AND http_path NOT LIKE '%..%');

COMMENT ON COLUMN sys_api.service     IS '服务名（与 spring.application.name 一致，网关按它发现目标，§20.3）';
COMMENT ON COLUMN sys_api.http_method IS 'HTTP 方法（大写，与 @PostMapping 的方法一致）';
COMMENT ON COLUMN sys_api.http_path   IS '接口服务上真实的接收路径；三元组即接口身份，模型工具名由路径派生';
COMMENT ON COLUMN sys_api.kind        IS '副作用等级 read/write（不是权限，§4.6）';

-- ============ role_api：退化成纯关联表 ============
ALTER TABLE role_api DROP COLUMN scope;

-- ============ data_access_audit：记三元组 ============
ALTER TABLE data_access_audit ADD COLUMN service     VARCHAR(64);
ALTER TABLE data_access_audit ADD COLUMN http_method VARCHAR(8);
ALTER TABLE data_access_audit ADD COLUMN http_path   VARCHAR(255);

-- 旧列的归宿：api_code 既不是路径也不是服务名，留着只会变成一个"永远是 NULL 的死列"，
-- 后面的人查它、拿它做统计，得到的都是空。开发/验证库里的历史行本来就对不上新口径，直接删。
ALTER TABLE data_access_audit DROP COLUMN api_code;

COMMENT ON COLUMN data_access_audit.service  IS '接口所属服务（三元组之一，§20.4 回放用）';
COMMENT ON COLUMN data_access_audit.http_path IS '接口路径（三元组之一，§20.4 不一致检测按路径比对）';