# 医生数据智能助理（骨架期）

按 `../agent分析权加限管控系统/医生数据智能助理-系统设计规格说明书-harness-v1.0.md` 实现。
本仓库对应规格书 **§18.8 M1 骨架**：先把「权限单点判定 + 服务间验签 + 节点 F 数据范围过滤 + 合规审计」这条骨架打通，
业务接口契约与业务测试表按 ADR-15 后置。

## 模块地图

| 模块 | 对应规格 | 职责 |
|--|--|--|
| `common/platform` | §4.2 / §4.3 / §4.5 / §4.7 / §6 / §19 | 纯 Java 领域件：权限判定 `AuthorizationService`、**接口身份三元组 `ApiRoute`**、签名 `ServiceSigner`/`ServiceVerifier`、JWT（HS256，≤15min）、内部信封 `ApiEnvelope`/`ApiResult`、数字台账与算式复算、append-only 事件日志 |
| `common/persistence` | §0.3-4 / §19.5 / §19.6 / §20.4 | PG/Redis 端口实现：权限读取、`permission_audit`、`data_access_audit`（I6）、`sys_api` 注册表、用户状态、Redis nonce、一次性确认、**事件落库通道两套实现**（`PgOutboxEventBus` 与 `RedisStreamEventQueue`，同一个 `EventQueue` 端口）、事件事实表写入（`JdbcAgentEventFactWriter`）、**运行时挂起状态落 PG**（`PgRuntimeStatePort`） |
| `common/web` | §20.1.4 | 服务间验签过滤器 `SignatureVerificationFilter`（G1 / I1 唯一实现）、请求体缓存、验签失败审计 |
| `agent-service/core` | §4.8 / §9 / §10 | agent 流水线与工具闸门（配额、重复调用、节点 E 前置）、SSE 投影、（运行时由 SPI 注入） |
| `agent-service/runtime/*` | ADR-31 / ADR-32 | `runtime-noop`（测试替身）与 `runtime-agentscope`（AgentScope 2.0.3 适配） |
| `agent-service/web` | §18.4.1 / §12 / §19.4 / §19.5 / §19.9 | agent 对外 Web 层：会话与轮次 API、**一次进入场券**、`GET /v1/agent/chat/stream`（SSE）、**HITL 挂起/确认/续跑**、轮次限速、append-only 事件日志（唯一事实源）与运行态落 PG |
| `interface-doctor` | §18.4.5 / §4.8 / §4.7 | 医生域接口服务：**一个接口一个端点、各自写自己的 SQL**；I1 验签 → I2 只用网关下发的 `userId`（范围由接口自己推导）→ I3 参数与口径校验 → I4 行过滤/列投影 → I5 参数化执行 + 溯源 → I6 直写审计（`RegisteredApiAspect` 切面，接口代码零审计）。启动自检「代码里的路由 ↔ `sys_api` 注册表」，不一致拒绝启动 |
| `management-service` | §18.4.6 / §19.4 / §20.4 / §20.7 | 管理后台：M1 登录（JWT ≤15min + 一次性 refresh）、M2 角色/接口授权、M3 技能包、M4 接口注册、M5 口径字典、M6 审计/回放/监控。平台元数据与审计读走**独立只读出口**，不套范围过滤 |
| `management-service`（模型供应商） | ADR-14 / §20.1.6 / §20.7 | 模型供应商配置：`sys_llm_provider` 存**密文**（AES-GCM，KEK 由环境注入），页面只回显 `keyHint`；同一时刻至多一个生效（服务层切换 + 部分唯一索引兜底）；每次变更写 `config_audit` |
| `web/`（前端） | §14-11 / §18.4.6 | Vue 3 + Vite + TS 单页：登录页、对话页（步骤卡/文件卡/确认按钮/澄清选项）、管理页 M2–M6。**前端只做展示**，判定仍在网关 |
| `deploy/` | §13 / §16 / §20.8 | `migrations/`（Flyway V1–V13）、`docker-compose.yml`（PG/Redis/MinIO/OTel/Prometheus）、OTel / Prometheus 配置 |

## 硬约束（改代码前先读）
- **判定单点**：只有网关推导身份；接口服务验签后**直接使用**网关下发的 `userId`，不重解析身份、不查权限库、
  不回查网关（§19.7 / §20.1.6-6）。网关**不接受**调用方自带的 userId / 角色 / 范围。
- **接口身份是三元组**：`(service, http_method, http_path)`，模型侧工具名由路径派生（`/doctor/performance` →
  `iface_doctor_performance`）。三元组每一项都能从代码里读出来，所以「注册表与代码不一致」是**启动失败**，
  不是运行期静默 404（§4.7）。
- **多角色并集**：可调接口取各角色**并集**；一条授权都没有 → 403，绝不退化成「全部」（§19.1）。
- **范围不在权限库里**：`role_api` 只回答「能不能调」；能看多大范围由接口服务基于登录人自己推导，
  过滤写在各接口自己的 SQL 里、值一律走占位符（§4.3 L2 / §19.1）。**这条目前是 TODO**：范围过滤还没接，
  接口里已留好位置（见 `DoctorPerformanceApi` 的注释），所以现在各账号看到的数据相同。
- **服务间验签**：API Key + HMAC-SHA256（方法/路径/query/时间戳/nonce/请求体 SHA-256）+ ±300s + nonce 一次性；
  失败统一 401，原因只进审计与告警（§20.1）。
- **合规审计不参与取舍**：`permission_audit`（网关）与 `data_access_audit`（接口服务 I6）**同步直写 PG、一条不漏**（§0.3-3 / §20.4）。
- **审计读不走业务出口**：审计/平台元数据读走 management-service 的只读出口（§20.4），不进网关、不套用户范围过滤；
  **审计读本身也要留一条审计**（`audit_read_audit`），且只有 `admin` 能进。
- **配置变更必留痕**：M2/M3/M4/M5 的每次改动与业务变更加同一个事务写 `config_audit`，带 `before/after`，审计写失败则变更回滚（§20.7）。
- **骨架期信任边界**：进程内不写入/不改写任何权限相关状态；密钥只在环境变量 / secret 注入，缺失即**拒绝启动**（§20.1.5）。
- **技能包不可变**：技能包按内容哈希寻址，同内容重复上传不产生第二版；只有一个「最新版本」，**不做版本并存、不做回滚**（§19.2）。
- **事件日志是唯一事实源**：本地 append-only 日志先落盘，队列只当**落库通道**——at-least-once、按 `event_id` 幂等，
  攒批（50 条 / 100ms / 单批 ≤500）、确认后才推进位点、连续失败 >5 次进 `event_dead_letter` 并告警；
  重启时从日志追赶「投递未确认」的事件（§19.6 / ADR-28）。
- **挂起状态不跟进程走**：HITL 挂起时把运行时快照落 PG（§19.5），重启 / 换 pod 后从快照把会话接回来续跑；
  接不回来（没有快照）一律 409，**不允许凭空当作用户已确认**（§19.9 不可绕过）。挂起与澄清渲染在对话气泡里，**不做弹窗**（§12.1）。
- **一次性入场券**：令牌只走 `Authorization` 头，**永不进 URL / 日志**；SSE 的 query 里只允许放券（`ticket`）与游标（`afterSeq`），券核销即失效，重连必须换新券（§19.4）。

## 构建与测试

```powershell
# 全量构建 + 测试（推荐；首次或改过父 pom 后必须这样跑）
mvn -B install

# 单模块（父 pom 已安装时）
mvn -B test -pl interface-doctor
mvn -B test -pl gateway

# 改过根 pom 后，先装父 pom，否则单模块构建会用 .m2 里的旧父 pom
mvn -B install -N
```

测试规模（2026-09-19，`mvn -B -o clean install` 全绿）：`common-platform` 56、`common-persistence` 51、
`agent-service-core` 11、`agent-service-web` 72、`runtime-agentscope` 16、`data-gateway` 25、
`interface-doctor` 15、`management-service` 42、`runtime-tck` 1（合计 289）。

前端另跑 `web/` 自己的两关（见 `web/README.md`）：

```powershell
cd web
npm run typecheck    # vue-tsc
npm run build        # vite build
```

接口服务测试用 H2（PostgreSQL 兼容模式，`DATABASE_TO_LOWER=TRUE`）跑真实 HTTP + 真实验签，
其中 `interface-doctor/src/test/resources/schema-h2.sql` **故意没有权限表**——若有人把权限查询塞进接口服务，测试会立刻失败。

## 本地联调

```powershell
cd deploy
docker compose up -d postgres redis minio otel-collector prometheus   # 首次启动会执行 migrations/ 下的 V1–V9

# 管理后台（M1/M2/M3/M4/M5/M6）
$env:MANAGEMENT_JWT_SECRET = "<与网关 GATEWAY_JWT_SECRET 相同的登录令牌密钥>"
$env:MANAGEMENT_DB_URL = "jdbc:postgresql://localhost:5432/doctor_assistant"
$env:MANAGEMENT_DB_USER = "assistant"
$env:MANAGEMENT_DB_PASSWORD = "assistant"
# 审计读专用只读账号（生产必配；不配则退化为「主连接 + 只读会话」）
$env:MANAGEMENT_READONLY_DB_URL = "jdbc:postgresql://localhost:5432/doctor_assistant"
$env:MANAGEMENT_READONLY_DB_USER = "assistant_ro"
$env:MANAGEMENT_READONLY_DB_PASSWORD = "<只读账号口令>"
# M3 技能包落盘目录（内容哈希寻址；不配则用工作目录下的 data/skill-packages）
$env:MANAGEMENT_SKILL_PACKAGE_DIR = "<技能包存放目录>"
# 模型 API Key 的加密根密钥（Base64 的 32 字节，§20.1.6）：openssl rand -base64 32
# 不配也能启动，但管理端**无法保存**模型密钥（查看不受影响）——绝不退化成明文落库
$env:MANAGEMENT_LLM_KEK = "<KEK>"
mvn -B spring-boot:run -pl management-service

# agent-service（会话 / SSE / 一次性入场券）
$env:AGENT_JWT_SECRET = "<必须与 MANAGEMENT_JWT_SECRET 相同，否则登录令牌过不了入口>"
$env:AGENT_SERVICE_SECRET = "<agent-service 调网关时用的服务密钥；网关侧同名 keyId 必须配同一个>"
# 限流计数走 Redis（§2.3：计数一律 Redis，多副本同口径；默认 memory 只适合单副本）
$env:AGENT_RATE_LIMIT_STORE = "redis"
$env:AGENT_DB_URL = "jdbc:postgresql://localhost:5432/doctor_assistant"   # 只用于查 sys_user.status
$env:AGENT_DB_USER = "assistant"
$env:AGENT_DB_PASSWORD = "assistant"
$env:AGENT_TICKET_STORE = "redis"        # 多副本必须 redis；单实例内存券无法保证跨实例一次性
$env:AGENT_EVENT_LOG_DIR = "<append-only 事件日志目录>"
$env:AGENT_STATE_STORE = "pg"            # 挂起/快照落 PG（§19.5）；只有单副本骨架才改 file
$env:AGENT_STATE_DIR = "<agent 运行态目录>"   # 仅 state-store=file 时使用
$env:AGENT_GATEWAY_URL = "http://localhost:8080"   # 留空 = 骨架期不接数据面，模型调不到任何工具
# 事件落库通道（§19.6）：redis-stream（规格里的路径：XADD 投递 / XREADGROUP 消费 / XPENDING 退避）
# / pg-outbox（默认；只有 PG 也能跑）/ none（只写本地 append-only 日志）
$env:AGENT_EVENT_BUS = "pg-outbox"
$env:AGENT_EVENT_STREAM = "doctor:events"           # 仅 redis-stream 用；多环境共用 Redis 时必须各用各的流名
$env:AGENT_EVENT_PERSIST_INTERVAL_MS = "200"       # 消费者攒批间隔；「50 条 / 100ms / 单批 ≤500」由代码固定
# 必须与 MANAGEMENT_LLM_KEK 是同一把：密文是管理端写的，agent 侧只负责解
$env:AGENT_LLM_KEK = "<同上>"
mvn -B spring-boot:run -pl agent-service/web

# 接口服务（I1–I6）
$env:IFACE_DOCTOR_GATEWAY_SECRET = "<与网关 outbound secret 相同>"
mvn -B spring-boot:run -pl interface-doctor

# 数据网关（G1–G5）
$env:GATEWAY_JWT_SECRET = "<登录令牌签名密钥>"
$env:GATEWAY_OUTBOUND_SECRET = "<网关对外密钥；须等于接口服务的 allowed-callers.gateway>"
$env:GATEWAY_AGENT_SERVICE_SECRET = "<agent-service 调用网关的密钥>"
mvn -B spring-boot:run -pl gateway
```

调用示例（agent-service 视角，走网关）：

```text
POST http://localhost:8080/v1/gateway/api-call
Authorization: Bearer <用户 JWT>
X-Api-Key / X-Timestamp / X-Nonce / X-Signature   # ServiceSigner 生成
{ "service": "interface-doctor", "httpMethod": "POST", "httpPath": "/doctor/performance", "requestId": "...", "args": { "month": "2026-08", "metric_key": "outpatient_visits" } }
```

agent-service 对话接口（令牌只走 `Authorization` 头；SSE 的 query 里只放券与游标）：

```text
POST   /v1/agent/sessions                          # 建会话，返回 sessionId
POST   /v1/agent/sessions/{id}/turns               # 发一轮，返回 { streamUrl, ticket }；ticket 只能用一次
POST   /v1/agent/sessions/{id}/tickets             # 换券重连：不发新轮次，只给一张新券（刷新页面用）
POST   /v1/agent/sessions/{id}/confirm             # 人工确认（HITL）回执，另给一张新券
POST   /v1/agent/sessions/{id}/archive             # 归档 / 取消归档（body {"archived":true|false}）——逻辑标记，不是删除
GET    /v1/agent/chat/stream?ticket=..&afterSeq=.. # SSE；认 Last-Event-ID，显式 afterSeq 优先
```

> §12 里写的 `GET /api/agent/chat/stream?sessionId=xxx` 已被 §19.4 取代：连接前先用**一次性入场券**换券，
> 令牌不进 URL/日志，会话号也不再出现在 query 里（券与 seq 不是凭证，可以进）。路径按本仓库统一口径落在 `/v1/`。

管理后台演示账号（`V4` 种子，口令哈希为 PBKDF2）：`admin/admin123`（admin 角色）、
`alice/alice123`（boss + director 两个角色，用于验证**多角色取并集**）、`gone`（disabled，验证停用立即失效）。

### 演示接口：有，两个；怎么测

| 接口（身份三元组） | 工具名（模型看到的） | 入参 | 作用 |
| -- | -- | -- | -- |
| `interface-doctor POST /doctor/performance` | `iface_doctor_performance` | `month`（必填，`YYYY-MM`）、`metric_key`（必填）、`doctor_id`、`dept_code` | 医生绩效明细（读 `doctor_metric`） |
| `interface-doctor POST /doctor/list` | `iface_doctor_list` | `dept_code`（可选） | 医生列表 |

- **种子**：接口与授权在 `deploy/migrations/V6__demo_api_and_grants.sql`，入参契约在 `V11__api_param_schema_contracts.sql`。
- **入参契约必须能被模型看见**：`GET /v1/permission/capabilities` 会把 `sys_api.param_schema` 连同身份三元组一并下发，
  agent-service 直接拿它当工具 schema（§4.8 节点 A）。只下发一个接口名时模型只能猜参数名（实测猜成
  `period` / `department`），每次调用都会被接口服务 I3 拒掉——表现为「接口看得见、一个都调不通」。
- **同一句提问、不同角色看到的接口不同**（§19.1 并集的验收口径，数据都在 2026-08）：
  授权按角色给（`role_api`），**能调哪些接口**取并集；**能看多大范围不在授权里**——
  范围过滤是接口服务里的 `TODO`（将来按登录人推导），所以现在同一个接口下、所有账号看到的数据是一样的：

  | 账号 | 角色 | 可调接口 | 问「上个月各科室门诊量」应看到 |
  | -- | -- | -- | -- |
  | `admin` | admin | 两个接口 | 全量（范围过滤未实现，别把「看到财务科」当 bug） |
  | `alice` | boss + director | 两个接口（并集） | 与 admin 相同——**范围过滤还没接** |
  | `gone` | 已停用 | — | 登录即被拒；停用后正在用的令牌也立刻失效 |

**「模型看到的接口说明书」在哪看**：原件是 `sys_api`（管理端 `/admin/api`，含「适用场景」与「返回字段」两栏）；
网关每次下发的 `GET /v1/permission/capabilities` 就是**它此刻拿到的那一份**（`scenario` / `resultSchema` /
入参 `example` 都在里面）。⚠️ 网关对 `/v1/*` 全挂了服务间验签过滤器，**裸 curl 会 401**，
必须带 `ServiceSigner` 生成的 `X-Api-Key/X-Timestamp/X-Nonce/X-Signature`（agent-service 就是这么调的）。
**四条测法，从粗到细**（排查「模型答得不对」时，先用第 3 条把数据面和模型面切开）：

1. **页面**：起好 `web/` 后登录 → 对话页直接问「上个月心内科各医生的门诊量排名」。
   工具卡会显示模型实际传的参数与结果字节数（§12.2 的 `tool` 事件形状）。
2. **一轮真实 SSE（不经过浏览器）**：`demo/ask.ps1`（登录 → 建会话 → 发轮 → 收 SSE 并打印事件）。

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File demo/ask.ps1 -Username alice -Password alice123 -Question "上个月各科室门诊量"
   ```
3. **只测数据面（不经过模型）**：`demo/call.ps1` —— 拿用户 JWT + `ServiceSigner` 签名直接打网关，走的就是 agent-service 那条路。

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File demo/call.ps1                      # 默认打 doctor_performance
   powershell -NoProfile -ExecutionPolicy Bypass -File demo/call.ps1 -DeptCode 心内科
   powershell -NoProfile -ExecutionPolicy Bypass -File demo/call.ps1 -HttpPath /doctor/list -DeptCode 呼吸科
   ```

   数据面通、页面不通 → 问题在工具 schema / 系统提示词；数据面也不通 → 看网关与接口服务日志
   （`interface-doctor` 的「不支持的参数 / 缺少必填参数」会直接告诉你是哪一项对不上）。
4. **看「agent 此刻拿到的接口说明书」**：`demo/caps.ps1` —— 同样带签名，打的是 `GET /v1/permission/capabilities`，
   打印接口清单与每个接口的适用场景 / 入参 / 返回字段。模型选错接口或参数时，先看这份是不是本身就有问题。

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File demo/caps.ps1                     # 默认 alice
   powershell -NoProfile -ExecutionPolicy Bypass -File demo/caps.ps1 -Raw                # 原始 JSON
   ```

   > `demo/call.ps1` / `demo/caps.ps1` / `demo/probe.ps1` 都必须带签名：网关对 `/v1/*` 全挂了服务间验签过滤器，**裸 curl 一律 401**（§20.1.3）。
   > 签名串 = `v1` / 方法大写 / 路径 / query（字典序）/ 时间戳 / nonce / `SHA-256(请求体)`；
   > `X-Signature` 的形状是 `v1:` + Base64(HMAC-SHA256)。默认 `keyId=agent-service`，
   > 共享密钥取本机启动脚本里的 `local-agent-service-secret`，部署时用 `-KeyId` / `-SharedSecret` 覆盖。
**一条命令跑完一份巡检**：上面四条是「从粗到细地定位一个问题」，`demo/probe.ps1` 是「一次拿齐所有问题」——
把散在各处的硬性约束（身份 / 网关验签 / 授权 fail-closed / 入参校验 / 会话与归档 / 管理端）收成 57 条断言，
失败也继续跑完，末尾给失败清单。它不碰授权与技能，随时可跑。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File demo/probe.ps1     # 期望 PASS 57 / 57
```

**§4.10 的端到端示例，对应测法**（括号里是本机实测结果）：

| 示例（§4.10） | 测什么 | 怎么测 | 实测 |
| -- | -- | -- | -- |
| 例 1 正常链路 | 授权接口跑通、requestId 全程可查 | `demo/ask.ps1` 按上表三个账号各跑一次 | 都能跑通；**各账号数据相同**（范围过滤是接口里的 TODO） |
| 例 3 绕过二·直连数据接口 | 网关 G3 单点判定 + 接口服务验签 | 带**有效 JWT**、不带签名分别打网关与 `http://127.0.0.1:8084` | 两处都 401（接口服务从网关之外调不动） |
| 例 4 多角色并集 | 授权取并集、不是交集 | alice（boss + director）问「上个月各科室门诊量」 | 两个接口都调得到；范围过滤待接 |
| 例 5 伪造 / 篡改内部请求 | 缺签名、篡改 body、重放 nonce 一律 401 | 同例 3（缺 `X-Api-Key` / `X-Signature`）；正例见 `%TEMP%\Probe.java` 里 `ServiceSigner` 的用法 | 无签名 → 401 |
| 例 2 绕过一·直连技能接口 | 未授权直连技能执行接口 → 403 | 骨架期无此接口（技能执行后置，§18.8 M3/M4） | 库里两个技能都是 `disabled` 且 `role_skill` 为空，**模型答「看不到技能」是正确的** |

> **`dept_code` 的真实取值是中文科室名**（`心内科` / `呼吸科` / `财务科`，见 `V3` 种子），不是英文编码。
> 实测模型把「心内科」自己猜成 `cardiology` 去过滤 → 接口服务照此拼 WHERE → 返回空 → 模型交出一份
> 「无数据」的结论（数据没错，是过滤条件被模型编造了）。现在系统提示词强制：过滤参数只在用户**明确给出**时才传；
> 要按科室过滤就先调 `doctor_list`（不传参）看 `dept_code` 的真实取值，再用它过滤。
管理后台接口（均为 admin-only）：

```text
POST   /v1/auth/login | /v1/auth/refresh | /v1/auth/logout        GET /v1/auth/me
GET    /v1/admin/role-api?roleCode=          PUT /v1/admin/role-api        DELETE /v1/admin/role-api
GET    /v1/admin/skill | /pending | /{skillCode} | /versions/{vid}/checks
POST   /v1/admin/skill/upload                POST /v1/admin/skill/versions/{vid}/review
GET    /v1/admin/api | /v1/admin/api/{id}  PUT /v1/admin/api            PUT /v1/admin/api/{id}/enabled   DELETE /v1/admin/api/{id}
GET    /v1/admin/metric | /{key} | /unit-convert   PUT /v1/admin/metric | /unit-convert
PUT    /v1/admin/metric/{key}/enabled        DELETE /v1/admin/metric/{key}
GET    /v1/admin/audit/data-access | permission | config | reads | trace | overview   # 必带 from/to（UTC、左闭右开、≤31 天）
```

> `deploy/docker-compose.yml` 目前只有基础设施容器；agent-service / data-gateway / interface-doctor / management-service
> 的容器定义（镜像构建 + 密钥注入）待补。

## 已实现的规格条目

- §19.1 权限口径：多角色并集（`AuthorizationService`），一条授权都没有 → 403 fail-closed；授权键是 `sys_api.id`，
  **范围不随授权配置**（`role_api` 只有 `(role_id, api_id)`，范围由接口服务基于登录人推导）
- §19.7 / §20.1 信任边界：网关单点判定并签名下发；接口服务验签但不判定；`ApiEnvelope` 含 caller（userId + requestId + traceId）+ args
- §20.1.3–20.1.4 签名规范与校验步骤（时间窗、nonce 一次性、常量时间比较、统一 401）
- §20.1.5 密钥缺失拒绝启动（网关与接口服务）
- §18.4.2 G1–G5（验签、判定、转发、读重试/写不重试、超时 5s/30s、响应截断、限速、熔断、审计）
- §18.4.5 I1–I6：接口服务六道工序 + W1 的写操作确认凭据存在性检查
- §4.8 节点 F：行过滤写在各接口自己的 SQL 里（值一律占位符）+ 行数上限截断标注；**列由返回类型写死**（不再有列白名单）
- §4.5 / §11.3 统一错误措辞（`UnifiedErrors`）
- §20.4 I6 数据访问审计（`data_access_audit`，直写 PG）
- §20.7 `config_audit` 表；§20.5 审计表主键带 `created_at`（为月分区留口子）
- §18.4.6 M1/M2/M4/M5/M6：登录 + 角色/接口授权 + 接口注册 + 口径字典 + 审计回放监控（每次改动/查询都留痕）
- §19.4 JWT ≤15 分钟 + 一次性 refresh token（哈希存库、轮换消费 + 撤销）；令牌不进 URL/日志
- §6.1 口径字典规格字段（别名/定义/公式/时间口径/`scale`/`timezone`）+ `unit_convert` 换算表；派生算子与算式复算**共用一份白名单**
- §20.4 审计读独立只读出口（`audit_read_audit` 记录谁在何时查了审计）+ requestId 回放与「agent 记了、接口服务没记」不一致告警
- §20.5 审计查询强制时间范围（左闭右开、单次 ≤31 天、行数上限）
- ADR-32 / ADR-31 可插拔运行时与框架隔离（ArchUnit 守护）
- §19.6 / ADR-28 append-only 事件日志与日志先行投递（`common/platform/eventlog`）
- §19.6 / ADR-28 事件总线落地：`EventQueue` 端口 + `PgOutboxEventBus`（`event_outbox` 表，事件本体进 `jsonb`、`event_id` 唯一，重复投递不产生第二行）
- §19.6 落库消费者 `EventPersistConsumer`：攒批（50 条 / 100ms / 单批 ≤500，一批一事务）→ 幂等写入事实表 `conversation_turn` / `agent_step` / `tool_call`（`ON CONFLICT DO NOTHING`）→ 确认推进位点；失败退避重试（数据库时钟判定），>5 次转 `event_dead_letter` 并计入水位
- §19.6 进程重启从日志追赶「投递未确认」的事件重新入队（`LogFirstEventPublisher.redeliverPending`），队列只做落库通道、不做唯一副本
- §19.5 运行时状态落 PG：`RuntimeStatePort` → `PgRuntimeStatePort`（`agent_state`，按 `user_id / session_id / state_key` 三元组寻址、覆盖写）；一次落地同时解决 HITL 恢复 / 重启恢复 / 换 pod 恢复 / 断线续传
- §19.9 / §12.1 HITL 闭环：需要确认的操作按中立事件上报 → 投影成**对话气泡内的确认卡**（不是弹窗）→ 挂起时把运行时快照落 PG → `POST /sessions/{id}/confirm` 消费确认并**续跑同一运行时会话**；确认只能用一次（重复确认 409，不绕过、不重放）
- §19.5 / §19.14 进程重启或换 pod 后，运行时句柄没了也能从 PG 快照把会话接回来续跑（`runtime.resume`）；快照缺失一律 409，**不凭空当作用户已确认**
- §6.3 / §18.5.5 数字台账与算式复算（`common/platform/ledger`）
- §18.4.6 M3 技能包管理：上传（内容哈希寻址，同内容不产生第二版）→ 自动检查（告警不阻断，阻断项才拦）→ 人工评审（只评最新版）→ 发布成不可变版本；绑定只读接口的技能自动可用，绑定写接口的自动 `rejected` 并记 `reviewer=system`；停用保留包但吊销绑定
- §18.4.1 / §19.4 agent-service Web 层：会话/轮次 API + **一次性入场券**（`ticket` 核销即失效，`AGENT_TICKET_STORE=redis` 支持跨实例）+ `GET /v1/agent/chat/stream`（SSE，认 `Last-Event-ID`，`afterSeq` 优先）+ HITL 确认回执 + 轮次限速（`turns-per-minute`）
- §12.2 事件表 → 前端投影：步骤卡 / 技能卡 / 工具卡 / 文件卡 / 确认按钮 / 澄清选项 / 流式文本 / 最终答案，**确认与澄清渲染在对话气泡内，不做弹窗**（§12.1）
- §9.3 / §2.3 用户轮次配额：`TurnLimiter` 端口 + 两套实现——`TurnRateLimiter`（进程内滑动窗口，只给单副本骨架用）
  与 `RedisTurnLimiter`（`INCR` + 首次 `PEXPIRE` 的 Lua 原子窗口计数，**多副本同口径**）。规格禁止本地内存计数，
  所以多副本装配走 `AGENT_RATE_LIMIT_STORE=redis`；计数存储不可用时 **fail-closed**（503 统一措辞），
  既不静默放行也不退回本地计数——那正是 §2.3 要根治的隐性单点
- §14-11 `web/` 前端：登录页（失败只显示统一措辞）、对话页（**左侧历史会话列表**，点一下切回旧会话接着聊）、管理页 M2–M6 +「模型供应商」；换券重连（券一次性，所以原生 `EventSource` 自动重连会 401，必须换新券）；
输入框 **Enter 发送、Shift+Enter 换行**，输入法组合态放行（中文选词那一下不会把半截拼音发出去）
- §19.4 历史会话：`GET /v1/agent/sessions`（只回自己的，按最后活动倒序，标题取首条提问）+ `GET /v1/agent/sessions/{id}/turns`（按轮次回放**原始事件**，前端用与实时流同一个归约器渲染，所以历史会话和刚答完的那一轮长得一样）；别人的 sessionId 一律 404（§11.3）。
  会话列表**没有单独的表**：标题 / 轮次数 / 时间都从回放位派生，另建目录表就等于给同一件事两份真相
- §19.4 归档（类比 Codex）：`POST /v1/agent/sessions/{id}/archive`。**归档是逻辑标记，不是删除**——
  规格把「归档与召回」列为后置能力、骨架期记录长期保留（§8.3 / §20.5），所以这里只往唯一事实源追加一条
  `SESSION_ARCHIVED` 事件：会话与它的每一轮回答都原样还在，历史照样回放，取消归档就能接着聊。
  归档位**不落任何目录表**，而是由 `SessionSummary` 从回放位折叠出来（最后一次归档 / 取消归档说了算，
  归档时刻就是写下那条记录的时刻），因此重启后仍在；列表接口回全部会话 + `archived` 标记，
  由前端分成「在用 / 已归档」两段（归档不改变归属，别人的会话照样 404）。
  `conversation_session.status` 目前没有任何写入路径，等它的投影接上时归档要顺带同步。
- §8.4 / §12.2 新增中立事件 `USER_MESSAGE` → SSE `user`：用户的提问是**平台侧**发出的（运行时不会把正文放进 `TURN_START`），
  没有它 `conversation_turn.user_input` 永远是空、历史会话没有标题，而且这段正文确实进了模型请求却无法从日志重建（ADR-28 ③）
- §19.6 / ADR-28 落库通道可插拔：`pg-outbox`（PG 队列表）与 `redis-stream`（`XADD` + `XREADGROUP` + `XPENDING` 退避 +
  死信仍写 `event_dead_letter`）两套实现同一个 `EventQueue` 端口，切换只改 `AGENT_EVENT_BUS`，消费者与业务代码不动；
  两边的 `attempts` 都表示「已经失败过几次」（Redis 用投递计数 − 1），退避窗口同一把尺子（2^n 秒、上限 30s）
- ADR-14 / §20.1.6 模型供应商配置：`sys_llm_provider` 只存 AES-GCM 密文（KEK 由环境注入，库与密钥分家）、页面与审计只回 `keyHint`、同一时刻至多一个生效；`ConfiguredModelProvider` 每轮直查该配置并解析成 OpenAI 兼容模型（引用串 `deepseek:deepseek-flash`），**没有生效配置就明确报错，不回退**

- §4.8 节点 A 工具入参契约：`GET /v1/permission/capabilities` 下发 `apis`，每一条是 `ApiCapability` =
  **身份三元组（service / httpMethod / httpPath）+ 名称 + 副作用等级 + `param_schema` + `scenario` + `result_schema`**。
  agent-service 用三元组建「工具名 → 路由」映射、用 `param_schema` 建工具 schema。**只下发一个接口名时模型只能猜参数名**
  （实测猜成 `period` / `department`），每次调用都被接口服务 I3 拒掉——现象是「接口看得见、一个都调不通」。
  两种历史录法都认（完整 JSON Schema / 扁平「参数名→类型声明」）；条目缺三元组则**不进工具面**
  （模型拿到一个没有目标地址的工具，只会得到一次注定失败的调用）
- §6.6 时间口径落地：`BusinessTime`（业务时区 `Asia/Shanghai`、左闭右开区间、季度起点）+ 每轮现拼的系统提示词（`SystemPromptComposer`），
  把「今天 / 本月 / 上个月 / 本季度 / 上季度 / 最近 7 天」**算好写死**交给模型。规格要求「相对时间由程序解析、模型不做日期计算」——
  实测让模型自己推，它会把「上个月」猜错一个月，再连查六次才收敛；服务器按 UTC 跑时早上 8 点前更会整整差一个月
- §12.2 / §8.3 工具事件补全：`tool` 事件带上模型实际传的 `args` 与结果 `resultSize`（框架按片下发参数，所以在
  `TOOL_CALL_END` / `TOOL_RESULT` 上一起带出）；事实表 `tool_call.args` / `result_size` 由此才有值
- §12.2 文本块事件带整块正文：`TEXT_BLOCK_END` 只给块 id，正文由运行时按块累积（此前塞的是 `replyId`，
  于是每轮末尾多出一张内容是 UUID 的步骤卡）
- 工具结果状态如实上报：平台侧 `ToolInvocationStatus` 直译成框架结果状态（`DENIED` / `ERROR` / `SUCCESS`），
  失败不再被标成「成功」——那会让工具卡与审计读起来自相矛盾
- 接口自描述（§4.8 节点 A / §19.8，`V12`）：`sys_api` 加两列——`scenario`（什么时候用、什么时候不该用）与
  `result_schema`（返回字段名 + 含义 + 类型）。前者进工具描述与入参 schema、后者只能进工具描述（OpenAI 兼容的
  工具定义里**没有 outputSchema 的位置**，而模型恰恰最需要它来避免编字段名）。工具描述因此长这样：
  「适用场景：… / 入参：month（必填，string，例 2026-08）：… / 返回字段：metric_value（number）：…」。
  入参逐条带 `example`：模型最容易错的不是「填哪个参数」而是**取值口径**（把「心内科」翻成 `cardiology`）。
  管理端 `/admin/api` 已能录/改这两列；没录的接口描述自动退化成简版（不留空标题）
- 工具入参纪律（§4.8 入参契约 + 一条实测教训）：过滤参数只在用户**明确给出**时才传、编码一律不许猜。
  实测模型把「心内科」自己翻成 `cardiology` 去过滤 → 接口服务照此拼 WHERE → 返回空 → 答成「该口径无数据」；
  现在提示词要求「要按科室过滤就先调 `doctor_list` 取 `dept_code` 的真实取值」，实测一次命中
- 接口身份改成**三元组**（§4.7，`V13`）：`sys_api` 用 `(service, http_method, http_path)` 认接口，
  删掉 `api_code` / `column_whitelist`，`role_api` 删掉 `scope`。为什么：`api_code` 是"第二份清单"——
  代码里没有任何约束、路由也不用它，只能靠人记住"注册表写的"和"@PostMapping 实现的"一致，对不上时
  网关把请求打到不存在的路径、接口服务静默 404。三元组每一项都能从代码读出来，于是"对不上"变成
  **接口服务启动失败**（`RegisteredRouteCatalog` 自检：路由↔注册表、路径前缀、DTO 字段↔`param_schema`、
  required 字段是否真有约束）。模型侧工具名由路径派生（`iface_doctor_performance`），不再单独维护
- 空结果不能兼职表示「参数是错的」（`V14`）：`month` 的 `@Pattern` 原先是 `\d{4}-\d{2}`，连 `2026-13`、
  `2026-00` 都算合法，于是这类值一路走到 SQL、命中不了任何行、回 200 + 空结果——对模型来说和「这个月确实
  没数据」长得一模一样。现在收紧到真实月份（`0[1-9]|1[0-2]`），`sys_api.param_schema` 同步改（说明书与实际
  校验必须同一口径，否则模型是照着错说明选参）
- 回答跟着终点一起走（`conversation_turn.final_answer` 的补写）：事实表由消费端**攒批**写入，一条 `TEXT`
  （答案）和它那一轮的 `TURN_END` 完全可能落进两个批次，而 `TEXT` 自己不写 `conversation_turn`——实测 128 轮
  里 25 轮 `final_answer` 是空的，答案就那么丢了。现在运行时把整轮正文挂到 `TURN_END` 上，落库不再取决于
  批次边界（与 `user_input` 同一套理由，见 `ChatService.withUserText`）
- 一个接口一个端点：`DoctorQueryService#handle` 那种「一个入口拼单表查询」的写法换成每个接口一个类
  （`DoctorPerformanceApi` / `DoctorListApi`），入参 DTO、校验、SQL 都在各自文件里。模板式的动态 SQL
  一旦要"够用"，就会长成谁也改不动的构造器，而且每个接口的差异都会变成模板里的开关
- body 用**泛型信封** `ApiEnvelope<T>`（caller + args）：接口方法直接收有类型的入参，不用每个接口手写
  `InternalCodec.parse(body)`；身份字段仍留在请求体里（签名只覆盖 body，放到 header 就出了签名范围）
- 审计改**切面**（`RegisteredApiAspect`）：接口代码零审计——谁也不会忘记写，也不用每个新接口复制一遍；
  网关仍只记 `permission_audit`（允许/拒绝），接口服务记 `data_access_audit`（数据访问），两边职责不重叠
- 技能包 manifest 的 `boundApis`（接口编码数组）→ `boundRoutes`（`"服务名 方法 路径"`，可用
  `ApiRoute.format()/parse()` 互转）；导出列的**上界**从「列白名单并集」改成「绑定接口 `result_schema`
  的字段名并集」——白名单是第三份需要手工同步的清单
- 管理端 M2/M4 跟着改：接口表按 `服务 / 方法 / 路径` 展示并实时预览派生工具名（编辑时三元组锁定，
  改路径等于新登记一条，否则旧的会留在注册表里两条并存）；角色授权从"选接口编码 + 写数据范围 JSON"
  改成"从注册表下拉选接口"（`apiId`），范围那一栏整块删掉

## 尚未实现（按规格顺序）

1. `skill-execution-service`（S0–S7，骨架期内嵌 agent-service，§19.11）+ `sandbox-runner`（`network("none")`）
2. agent-service 可观测性（指标/链路导出，`AGENT_EVENT_LOG_DIR` 目前只落 append-only 事件日志）与 agent-service 侧 Flyway 接入
3. RocketMQ 版队列：`EventQueue` 端口已就位（`pg-outbox` / `redis-stream` 两套实现已落地），
   换 RocketMQ 只要重写 `poll / ack / nack / deadLetter / stats`，消费者与业务代码不动（§19.6 / ADR-10）
4. `management-service` M7，以及反馈接口——前端 `final` 卡片上的 👍/👎 后端**没有**对应接口，界面已注明只做本地记录
5. `deploy/docker-compose.yml` 的四个服务容器定义（镜像构建 + 密钥注入）
6. `web/` 的渠道抽象与思考流个人设置（§20.6）；当前是页面级开关，默认只显示步骤卡
7. `runtime-agentscope` 的**完整 TCK**（需要确定性模型桩或真实端点）。仓库默认配置仍是 `noop` + `tck-passed-runtimes=noop`——
   那是准入闸门（§18.11.6），不是「没做完」；本机为了让对话真的走模型，是用环境变量把 `agentscope` 显式加进准入选单的
   （见「接入大模型」一节），属于刻意的本地覆盖。模型侧接线已完成并有单测覆盖（解析 / 切换 / 解密 / 无配置报错 / 能力面基线）
8. Flyway 接入：迁移目前由部署脚本按序 apply（`deploy/migrations/V1–V14`）
9. 模型偶发英文过程旁白：DeepSeek 在**调工具前**会先说一句英文（如 I will query last month outpatient visits），
   这句走 `TEXT_BLOCK`，既进步骤卡、也随 `token` 流进答案气泡。提示词已写明「全程中文、不写过程旁白」，
   实测仍未完全压住（纯对话轮没有该现象，只在调工具的轮次出现）。更稳的做法在投影层而不是提示词：
   把「首次工具调用之前的正文」归到步骤卡、不进答案气泡（§12.2 的 `step` 语义）
10. **数据可见范围过滤**（§19.1 的落地部分）：`role_api.scope` 已经删了，范围过滤目前是接口服务里的
   `TODO`（`DoctorPerformanceApi` 里有位置注释）——**现在各账号看到的数据相同**，别把「alice 也看到财务科」
   当成 bug。要做的是：接口按 `body.caller().userId()` 查出可看范围，把它变成自己 SQL 里的 `where` 条件，
   并把实际用到的条件写进 `data_access_audit`（那一栏现在为空，不要理解成"没有范围限制"）

## 数据库迁移：已在本地 PostgreSQL 实测

`deploy/migrations/` 下 **V1–V14** 已在本地 PostgreSQL 18 空库上全量跑通（旧文档里「有 Docker 后再验证」这条已完成）：

```powershell
# 建库（口令按本地实际改）
#   createdb doctor_assistant_verify
$env:MANAGEMENT_DB_URL = "jdbc:postgresql://127.0.0.1:5432/doctor_assistant_verify"
$env:MANAGEMENT_DB_USER = "postgres"
$env:MANAGEMENT_DB_PASSWORD = "<口令>"
# 迁移目前由部署脚本按序 apply（Flyway 尚未接入，见文末「尚未实现」）；随后可用 M3 上传/评审/发布跑通端到端
```

实测覆盖：迁移全部成功、`skill_api.approved_by` 为 `VARCHAR(64)`（平台身份是 `sys_user.username`，§4.9）、
M3 上传 → 重复上传同内容只有一行 → 评审发布 → `config_audit` 有 before/after → 停用后绑定被吊销。

事件落库通道（V8 的 `event_outbox` / `event_dead_letter`）另在同一个库上单独验过：
重复 publish 只落一行、`jsonb` 事件本体可原样解回、`ack` 后不再被拉取、`nack` 按**数据库时钟**退避并记 `attempts / last_error`、
>5 次转死信后队列里不再有它、事实表按 `event_id` 重放三次仍只有一行、一轮对话结束后队列被搬空（pending 归零）。
`?::jsonb` 绑定与 `BIGINT = varchar` 这类**只有真 PG 才会暴露**的问题就是在这一步抓出来的（H2 会静默放过）。

运行时挂起状态（V9 的 `agent_state`）同样在真库上验过：三元组寻址不串门、同一段重复保存是覆盖而不是追加、
`jsonb` 载荷（数字 / 文本 / 布尔）原样往返、换实例能读到同一份状态（「不跟进程走」）、删除只删这一段。

模型供应商配置（V10 的 `sys_llm_provider`）也在真库上验过：写入的是密文、明文在库外解出、
页面与审计只出现 `keyHint`、启用新的会自动关掉旧的（部分唯一索引兜底）、非法适配器在写入期就被 400 挡下。

## 事件落库通道（§19.6 / ADR-28）：两套实现，一个端口

队列只是**把事件搬到 PG 事实表的通道**；唯一事实源始终是本地 append-only 日志（`AGENT_EVENT_LOG_DIR`，
必须挂真实卷）。换队列实现不动消费者、不动业务代码，只换 `EventQueue` 的装配：

| `AGENT_EVENT_BUS` | 实现 | 什么时候用 |
| --- | --- | --- |
| `redis-stream` | `RedisStreamEventQueue`（规格 §19.6 的路径） | 生产 / 多副本；Redis 要 AOF + `noeviction`，Stream 按 `MAXLEN ~ 100 万` 滚动清理 |
| `pg-outbox` | `PgOutboxEventBus`（默认） | 「只有 PG 也得跑」；不需要额外中间件 |
| `none` | `LogOnlyEventPublisher` | 只落本地日志，不搬事实表 |

两套实现的语义**逐条对齐**（换实现时最容易悄悄走样的五处）：

- **位点**：PG 是 `event_outbox.id`（BIGINT），Redis 是消息 id（`1789807259512-0`）——都当不透明字符串用；
- **`attempts` = 已经失败过几次**：PG 读 `attempts` 列，Redis 读投递计数 − 1（计数由 Redis 自己维护）；
- **退避**：两边都是 2^n 秒、上限 30s，判据都放在**存储侧**（PG 用 `next_attempt_at <= now()` 的库时钟，
  Redis 用待确认条目的空闲时长），应用侧时钟不一致不会造成静默积压；
- **死信**：都写 PG 的 `event_dead_letter`，都**先写死信再出队**——中间崩溃只会留下重复死信（可见），不丢事件；
- **至少一次**：落库幂等由事实表的 `event_id` 唯一键吸收；Redis 流没有唯一键，重启追赶重放会在流里留下第二条位点，
  这是明示的 at-least-once，不是缺陷。

### 本地怎么验

```powershell
# 让 agent-service 走 Redis Stream（消费组会自动建）
$env:AGENT_EVENT_BUS = "redis-stream"; $env:AGENT_EVENT_STREAM = "doctor:events"

# 跑一轮对话（见「本地联调」；配好模型供应商才会产生事件）
# 然后看通道：
& "D:\Redis\8.10.2\redis-cli.exe" xlen doctor:events          # 入队条数
& "D:\Redis\8.10.2\redis-cli.exe" xinfo groups doctor:events  # pending 应回到 0（攒批落库后 XACK）
& "D:\Redis\8.10.2\redis-cli.exe" xrevrange doctor:events + - COUNT 1
```

会话数据落在事实表：`conversation_turn`（TURN_END）/ `agent_step`（THOUGHT、TOOL_*、ERROR）/ `tool_call`（TOOL_RESULT）。
本机实测（Redis 8.10.2）：一轮对话入队 39 条、`entries-read 39`、`pending 0`、`lag 0`，
`conversation_turn` 同步出现该轮记录。

### 事实表的两条不变量（实测踩到过，别再踩）

- **一轮一行**：`conversation_turn` 的粒度是「一轮」（`user_input` / `final_answer` / 台账都在同一行），
  所以中立事件流里 `TURN_END` 一轮只能来一个。AgentScope 会同时发 `AGENT_RESULT` 与 `AGENT_END`，
  两者都是「这一轮结束」——翻译器把它们都映射成 `TURN_END`（漏掉任一个都会让某些路径收不了尾），
  但**只放行第一个**（`AgentscopeEventTranslator#translate`）。否则一轮会落两行。
- **`user_input` 不依赖攒批边界**：消费端是攒批落库的（50 条 / 100ms），而一轮回答要跑好几秒，
  `USER_MESSAGE` 与它那一轮的 `TURN_END` 完全可能落进两个批次。所以平台在发终点事件前，
  把这一轮的提问钉在 `TURN_END.payload.userText` 上（`ChatService#withUserText`）——
  事实表能不能重建「问了什么」，不该取决于批次怎么切。
## 接入大模型（ADR-14：OpenAI 兼容协议，默认 DeepSeek）

配置在**管理端页面**维护、存库、Key 加密：`/admin/llm`（只有 admin 可见）。

### 怎么配

1. 生成 KEK，给两个服务配上**同一把**（`openssl rand -base64 32`）：
   - management-service：`MANAGEMENT_LLM_KEK`
   - agent-service：`AGENT_LLM_KEK`
2. 打开「模型供应商」页，填：标识 `deepseek`、适配器 `deepseek`、模型 `deepseek-flash`、
   接口地址 `https://api.deepseek.com`，把 API Key 粘进 Key 框，勾「启用」，保存。
3. 列表里该行应显示 `生效中`，Key 只显示 `sk-****1234`。
4. 回到对话页点「新会话」再问——**旧会话不会换模型**：会话与会话记录都绑着 `runtimeId`（§19.14），
   不一致就拒绝恢复，不会「假装恢复成功」。

接口地址的两种写法都行：`https://api.deepseek.com`（适配器预填值）或 `https://api.deepseek.com/v1`。
若保存后报 `404 模型服务返回「不存在」`，就先换成带 `/v1` 的那个——这类错误现在会直接告诉你「去哪个页面核对哪个字段」。

### 为什么 Key 存在数据库里还算安全

- 库里只有 **AES-256-GCM 密文**；解密用的 KEK 来自运行环境，不进代码 / git / 镜像 / 日志 / Trace。
  单独拿到一份库 dump 解不出 Key。
- 任何接口都**不回传明文**（只回 `keyHint`），前端因此拿不到「能拿去调模型」的字符串。
- 每次变更写 `config_audit`，审计快照里同样只有 `keyHint`（§20.1.6 硬约束第 7 条）。

### 让对话真的走 DeepSeek

默认运行时是 `noop`（不调模型：答案由输入文本按关键词决定）。要切到真模型，改两处环境变量：

```powershell
$env:AGENT_RUNTIME_ID = "agentscope"
$env:AGENT_TCK_PASSED = "noop,agentscope"
```

`agentscope` 必须先列进 `tck-passed-runtimes` 才会被 `RuntimeRegistry` 选中（§18.11.6）——这是准入闸门，
不是配置项。切换后每轮按**当前启用中的供应商**解析模型（引用串形如 `deepseek:deepseek-flash`），
**每轮直查、零缓存**，所以换模型 / 改端点不必重启。

### 运行时基线（ADR-31）：四条「不写就一定会悄悄坏掉」的规矩

`runtime-agentscope` 外壳里包的是 `HarnessAgent`，它**默认自带一整套能力**。这四条是实测踩出来的，
现在都有关闭动作 + 回归测试（`AgentscopeRuntimeBaselineTest`）：

1. **工具面 = 平台工具面**。`HarnessAgent` 会无条件注册 `web_search` / `web_fetch` / `wait_async_results`
   （builder 上没有任何开关能关掉），它们绕开平台的 `ToolInvoker`，于是也绕开 DENY 判定、确认闸口与审计；
   平台没配工具时它们还成了模型唯一能调用的东西。适配器改成**白名单**：平台没注册的一律 `removeTool`，
   框架将来偷偷加工具也进不来。
2. **记忆/压缩钩子必须关**。默认开启时每轮会**多调一次模型**（实测每轮 2 次调用：一次答问、一次偷偷
   「抽取记忆」）。这些调用不在平台成本上报里、也不在事件流里。
3. **工作目录必须钉在 `state-dir` 下**。默认按 cwd 建 `.agentscope/`，实测会把**会话原文与记忆账本写进应用
   启动目录**（仓库根）——既是数据外泄，也是仓库污染。
4. **模型侧的 HTTP 失败要翻译成「去哪儿改」**。401/403/404 是运维改一个字段就能好的事，不能和真故障
   共用一句「服务暂不可用，请稍后再试」；翻译只吐写死的常量文本，**上游回包一个字都不带出去**
   （上游内容是对方可控的，可能回显请求头）。

同理，「没配模型供应商」也是可自解释的失败：它走 `RuntimeMisconfiguredException`（`agent-spi`），
由接入层原样下发「请管理员在管理端『模型供应商』页配置并启用一个」，而不是收成 `RUNTIME_UNAVAILABLE`。
