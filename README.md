# 医生数据智能助理（骨架期）

按 `../agent分析权加限管控系统/医生数据智能助理-系统设计规格说明书-harness-v1.0.md` 实现。
本仓库对应规格书 **§18.8 M1 骨架**：先把「权限单点判定 + 服务间验签 + 节点 F 数据范围过滤 + 合规审计」这条骨架打通，
业务接口契约与业务测试表按 ADR-15 后置。

## 模块地图

| 模块 | 对应规格 | 职责 |
|--|--|--|
| `common/platform` | §4.2 / §4.3 / §4.5 / §4.7 / §6 / §19 | 纯 Java 领域件：权限判定 `AuthorizationService`、**接口身份三元组 `ApiRoute`**、签名 `ServiceSigner`/`ServiceVerifier`、JWT（HS256，≤15min）、内部信封 `ApiEnvelope`/`ApiResult`、数字台账与算式复算、append-only 事件日志 |
| `common/persistence` | §0.3-4 / §19.5 / §19.6 / §20.4 | PG/Redis 端口实现：权限读取、`permission_audit`、`data_access_audit`（I6）、`sys_api` 注册表、用户状态、Redis nonce、一次性确认、**事件落库通道两套实现**（`PgOutboxEventBus` 与 `RedisStreamEventQueue`，同一个 `EventQueue` 端口）、事件事实表写入（`JdbcAgentEventFactWriter`） |
| `common/web` | §20.1.4 | 服务间验签过滤器 `SignatureVerificationFilter`（G1 / I1 唯一实现）、请求体缓存、验签失败审计 |
| `agent-service/core` | §4.8 / §9 / §10 | agent 流水线与工具闸门（配额、重复调用、节点 E 前置）、SSE 投影、（运行时由 SPI 注入） |
| `agent-service/runtime/*` | ADR-31 / ADR-32 | `runtime-noop`（测试替身）与 `runtime-agentscope`（AgentScope 2.0.3 适配） |
| `agent-service/web` | §18.4.1 / §12 / §19.4 / §19.5 / §19.9 | agent 对外 Web 层：会话与轮次 API、**一次进入场券**、`GET /v1/agent/chat/stream`（SSE）、**HITL 挂起/确认/续跑**、轮次限速、append-only 事件日志（审计与事实表来源）与运行态落 PG（**会话状态权威**） |
| `interface-doctor` | §18.4.5 / §4.8 / §4.7 | 医生域接口服务：**一个接口一个端点、各自写自己的 SQL**；I1 验签 → I2 只用网关下发的 `userId`（范围由接口自己推导）→ I3 参数与口径校验 → I4 行过滤/列投影 → I5 参数化执行 + 溯源 → I6 直写审计（`RegisteredApiAspect` 切面，接口代码零审计）。启动自检「代码里的路由 ↔ `sys_api` 注册表」，不一致拒绝启动 |
| `management-service` | §18.4.6 / §19.4 / §20.4 / §20.7 | 管理后台：M1 登录（JWT ≤15min + 一次性 refresh）、M2 角色/接口授权、M3 技能包、M4 接口注册、M5 口径字典、M6 审计/回放/监控。平台元数据与审计读走**独立只读出口**，不套范围过滤 |
| `management-service`（模型供应商） | ADR-14 / §20.1.6 / §20.7 | 模型供应商配置：`sys_llm_provider` 存**密文**（AES-GCM，KEK 由环境注入），页面只回显 `keyHint`；同一时刻至多一个生效（服务层切换 + 部分唯一索引兜底）；每次变更写 `config_audit` |
| `web/`（前端） | §14-11 / §18.4.6 | Vue 3 + Vite + TS 单页：登录页、对话页（步骤卡/文件卡/确认按钮/澄清选项）、管理页 M2–M6。**前端只做展示**，判定仍在网关 |
| `deploy/` | §13 / §16 / §20.8 | `migrations/`（Flyway V1–V17）、**`local-windows/`（本机原生安装：MinIO + 建库脚本，见其 README）**、`docker-compose.yml`（CI / 生产参考）、OTel / Prometheus 配置 |

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
- **两条链各有各的权威**：**会话正文**（用户问了什么、agent 答了什么）权威是框架的 `AgentState`（落 PG，见 §19.5）；
  **事件日志**是**审计与事实表**这条链的来源——本地 append-only 日志先落盘，队列只当**落库通道**——at-least-once、按 `event_id` 幂等，
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

最近一次全量复核（2026-09-26，H-13 落地后 `mvn -B clean install`）：17 个模块 BUILD SUCCESS、**449 例 0 失败**（另有 2 例真 PG 用例默认跳过；Docker 可用时还会真起容器跑 3 例沙箱用例）——
`agent-service-web` 140、`common-platform` 73、`common-persistence` 47、`runtime-agentscope` 53、`management-service` 42、
`data-gateway` 30、`agent-service-state` 22、`interface-doctor` 15、`agent-service-core` 11、`common-object-storage` 8、`common-web` 7、`runtime-tck` 1。

前端另跑 `web/` 自己的两关（见 `web/README.md`）：

```powershell
cd web
npm run typecheck    # vue-tsc
npm run build        # vite build
```

接口服务测试用 H2（PostgreSQL 兼容模式，`DATABASE_TO_LOWER=TRUE`）跑真实 HTTP + 真实验签，
其中 `interface-doctor/src/test/resources/schema-h2.sql` **故意没有权限表**——若有人把权限查询塞进接口服务，测试会立刻失败。

## 本地联调

### 本机原生（这台机器的实际跑法）

这台机器的 PostgreSQL 18 / Redis / MinIO 都是**原生进程**：PG 装在 `D:\PostgreSQL`，Redis 是本机服务，MinIO 用 `deploy/local-windows/` 的脚本装在 `D:\minio`。Docker Desktop 也已可用（2026-09-26 修好并验证：引擎 27.0.3、`hello-world` 通过；修法见 `deploy/local-windows/README.md`）。

```powershell
# 1) 对象存储：装一次（下载 + 校验 + 注册登录自启 + 建好桶 doctor-assistant）
cd deploy\local-windows
.\install-minio.ps1
.\minio-status.ps1                       # 之后看状态就跑它

# 2) 建库：本机原生 PG 没有容器那套 initdb 入口，用这个脚本按序 apply deploy\migrations
.\apply-migrations.ps1 -User postgres -Password '<本机 PG 口令>'

# 3) 一键把整套跑起来（仓库根目录）：后端 4 个 + 前端，起来后自动开浏览器
#    停止用 .\stop-local.bat；日志在 deploy\local-windows\.local\logs\（细节见 deploy\local-windows\README.md）
.\start-local.bat
```

MinIO 控制台：<http://127.0.0.1:9001>（默认 `djzy / djzy-minio`，仅开发机）。
⚠️ MinIO 社区版已被官方归档，本机装的是 GitHub Releases 上最后一个开源版本，**只用于开发**；
生产换仍在维护的对象存储即可（走 `ObjectStorage` 端口）。详见 `deploy/local-windows/README.md`。

### 多副本（≥2 实例）：会话不粘实例，A / B / C 怎么成立

实例内存里只留「正在跑的这一轮的回放位」（有界、可淘汰），**其余全部读共享存储**——所以请求落到哪台都行：

| 用户看到的场景 | 靠什么成立 |
| :-- | :-- |
| **A** 断线重连（含被丢到别的实例） | 换券后若本实例手里没有这一轮，就**追共享总线**把持有它的实例正在产出的内容**实时**接着往下发（H-01）；总线读不到（单实例 `memory`、老数据、总线故障）才退回「等它跑完再整段补」（明示降级，见 TASKS.md 的 L-02）。等待有窗口上限，超时如实收尾，**绝不空挂一条不说话的连接** |
| **B** 换台实例继续聊（发起新一轮） | 会话存不存在 / 聊过什么 = PG 里的框架 `AgentState`；券与轮次坑位 = Redis；本实例句柄丢了现建一个空壳即可 |
| **C** 实例挂了会话还在 | 同一份共享状态；正在跑的那一轮用「开始标记 + 坑位无人占」判定为中断，历史与实时流说同一句「本轮没跑完，请重发」 |
| 同一会话并发提问 | 跨副本轮次坑位（Redis `SET NX PX`，释放时比对持有者）+ 状态库 CAS 兜底（真撞上也不互相覆盖）；抢不到当场回「上一轮还在处理中」 |

两个实例共用同一套 PG / Redis / MinIO 的**一键冒烟**（会真的起两个进程、走 A / B / C 检查项）：

`@powershell
cd deploy\local-windows
.\two-instance-smoke.ps1 -JwtSecret '<与两实例 AGENT_JWT_SECRET 相同>' -DbPassword '<本机 PG 口令>'
`@

自动化版是 `AgentWebMultiInstanceTest`（两个 `ChatService` 共享存储、闸门与总线，`mvn -B test` 会跑），
它覆盖：换实例读同一份历史、跨实例换券、跨实例**实时**接流、硬挂后的中断说明。
跨实例实时这条靠的是平台实现的框架 `MessageBus`（`RedisMessageBus` 默认 / `InMemoryMessageBus`，`agent-service.live-bus` 一个开关）：
跑这一轮的实例每下发一条事件就往总线记一条，接流端按游标追着读，于是回答**边跑边到**（不是等跑完）——总线只是加速用的临时数据，权威永远在 PG 的 `AgentState` 里。
停止也复用这条总线（H-09）：`POST .../stop` 先写共享的信号键（兜底，保证最迟下一次事件也会停），再往总线的停止频道推一条；持有那一轮的实例收到就**当场取消**，不用等它下一次产出。
取消打的是框架 `interrupt(userId, sessionId)`——**必须带会话身份**：无参那个（框架已标 `@Deprecated`）打的是默认槽位，表现是「界面停了、模型照跑」（真跑一个慢模型实测出来的，见 TASKS.md 的 DR-48），所以这条有专门的回归用例守着。
设计结论见规格书 **ADR-35**（§0.3-10/13 / §8.4 / §19.4 / §19.5 / §19.6）；「框架已有的用框架」这条实现口径见 **ADR-36**；**跨副本实时续看**见 **ADR-38**；**数据范围归属**见 **ADR-37**（网关只判定接口可用性并下发身份、不算也不下发范围，范围由接口服务基于登录人自行推导）。

### 容器方式（CI / 生产参考）

```powershell
cd deploy
docker compose up -d postgres redis minio otel-collector prometheus   # 首次启动会执行 migrations/ 下的 V1–V17

# 管理后台（M1/M2/M3/M4/M5/M6）
$env:MANAGEMENT_JWT_SECRET = "<与网关 GATEWAY_JWT_SECRET 相同的登录令牌密钥>"
$env:MANAGEMENT_DB_URL = "jdbc:postgresql://localhost:5432/doctor_assistant"
$env:MANAGEMENT_DB_USER = "assistant"
$env:MANAGEMENT_DB_PASSWORD = "assistant"
# 审计读专用只读账号（生产必配；不配则退化为「主连接 + 只读会话」）
$env:MANAGEMENT_READONLY_DB_URL = "jdbc:postgresql://localhost:5432/doctor_assistant"
$env:MANAGEMENT_READONLY_DB_USER = "assistant_ro"
$env:MANAGEMENT_READONLY_DB_PASSWORD = "<只读账号口令>"
# M3 技能包等共享文件的存放位置（内容哈希寻址）。
# 单副本本机开发用 local（默认，落 ./data/object-storage）；多副本部署必须用 s3（MinIO / 云 OSS / COS）。
$env:OBJECT_STORAGE_PROVIDER = "s3"
$env:OBJECT_STORAGE_S3_ENDPOINT = "http://127.0.0.1:9000"
$env:OBJECT_STORAGE_S3_ACCESS_KEY = "<access key>"
$env:OBJECT_STORAGE_S3_SECRET_KEY = "<secret key>"
# 可选：桶名（默认 doctor-assistant）、区域（默认 us-east-1）、桶内统一前缀（默认空）
$env:OBJECT_STORAGE_S3_BUCKET = "doctor-assistant"
# 模型 API Key 的加密根密钥（Base64 的 32 字节，§20.1.6）：openssl rand -base64 32
# 不配也能启动，但管理端**无法保存**模型密钥（查看不受影响）——绝不退化成明文落库
$env:MANAGEMENT_LLM_KEK = "<KEK>"
mvn -B spring-boot:run -pl management-service

# agent-service（会话 / SSE / 一次性入场券）
$env:AGENT_JWT_SECRET = "<必须与 MANAGEMENT_JWT_SECRET 相同，否则登录令牌过不了入口>"
$env:AGENT_SERVICE_SECRET = "<agent-service 调网关时用的服务密钥；网关侧同名 keyId 必须配同一个>"
# 限流计数走 Redis（§2.3：计数一律 Redis，多副本同口径；代码与 yml 的默认值本来就是 redis，只有单副本本机开发才应显式改成 memory）
$env:AGENT_RATE_LIMIT_STORE = "redis"
$env:AGENT_DB_URL = "jdbc:postgresql://localhost:5432/doctor_assistant"   # 只用于查 sys_user.status
$env:AGENT_DB_USER = "assistant"
$env:AGENT_DB_PASSWORD = "assistant"
$env:AGENT_TICKET_STORE = "redis"        # 多副本必须 redis；单实例内存券无法保证跨实例一次性
$env:AGENT_EVENT_LOG_DIR = "<append-only 事件日志目录>"
# 日志文件不会只增不减（§19.6 / ADR-28）：到 `AGENT_EVENT_LOG_MAX_BYTES` 就换一个文件接着写，
# 「已确认投递且超过 `AGENT_EVENT_LOG_RETENTION`」的记录才裁掉；维护由每个实例自己的调度器按
# `AGENT_EVENT_LOG_MAINTENANCE_INTERVAL_MS` 跑（它只碰自己的文件，不需要分布式锁）。
# 注意裁剪的前提是「已确认投递」——还没送进队列的记录，多旧都不会删。
$env:AGENT_EVENT_LOG_MAX_BYTES = "268435456"          # 单文件上限，默认 256 MB
$env:AGENT_EVENT_LOG_RETENTION = "7d"                 # 已确认记录的保留窗口，默认 7 天
$env:AGENT_EVENT_LOG_MAINTENANCE_INTERVAL_MS = "60000"  # 维护间隔，默认 1 分钟
# 会话状态（含 HITL 挂起快照）**只有落 PG 一种走法**（§19.5，见 `agent-service/state`）；
# 没有 `state-store=file` 这种开关——状态落本地会让会话被钉死在一台机器上，多副本下续聊会静默失效。
$env:AGENT_WORKSPACE_DIR = "<agent 工作目录>"   # 只放运行痕迹；不放会话状态
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
- §19.5 会话状态落 PG：**用框架扩展自带的 `AgentStateStore` 实现**（`agent-service/state` 的 `PlatformAgentStateStore` 装饰 `agentscope-extensions-jdbc` 的 `JdbcAgentStateStore`；表 `agentscope_sessions`，框架按**槽位号** `<userId>:<sessionId>` + `state_key` 寻址，`version` 做乐观并发）——平台只补了框架漏掉的一处「按 key 删除」，其余一律委托（ADR-36 ①）；HITL 挂起快照以 `state_key = platform_turn` 共存于同一张表（另有 `platform_session` / `platform_turn_live`）；一次落地同时解决 HITL 恢复 / 重启恢复 / 换 pod 恢复 / 断线续传
- §19.9 / §12.1 HITL 闭环：需要确认的操作按中立事件上报 → 投影成**对话气泡内的确认卡**（不是弹窗）→ 挂起时把运行时快照落 PG → `POST /sessions/{id}/confirm` 消费确认并**续跑同一运行时会话**；确认只能用一次（重复确认 409，不绕过、不重放）
- H-04 工作区共享（2026-09-26）：会话状态之外的第二块「不能跟进程走」的东西——agent 的工作区（技能 `SKILL.md` 与配套脚本、被写下的文件）。
  `agent-service.workspace-store=jdbc` 时接到共享 PG 表 `agentscope_store`（框架 `RemoteFilesystemSpec` + 框架自带的 `JdbcStore`，平台不写一行存储逻辑），
  隔离粒度是**用户**（`IsolationScope.USER`）；默认 `none` 与改造前逐字一致，真正用上它的是 H-06a（技能下发）。
- H-06a 技能内容下发与发现（2026-09-26）：网关给出「这个人现在能看见哪些技能」，agent 拿这份清单去**管理端的表**问
  「每个技能算数的是哪一版」（该技能下最新的一条 `published`，不是最新那一条——上传了没发布的不算），再从**对象存储**取包、解包写进
  **这个用户自己的工作区** `skills/<编码>/`。之后「有哪些技能、正文是什么」全交给**框架自带的 `WorkspaceSkillRepository`**：
  平台只写文件，不拼提示词、不维护第二份清单。开关 `agent-service.workspace-skills`（`none` 默认 / `workspace`）；每轮都同步一次
  （授权随时会被收走），但**版本没变时只花一次工作区读 + 一次批量 SQL**，不进对象存储；撤销会把技能文件清干净，
  下发失败只记日志、不影响这一轮对话（DR-41，落地说明见 `doc/refactor/TASKS.md` §6）。
- H-05 沙箱装配（2026-09-26，**默认关着**）：把「命令在哪儿跑」变成一个开关（`agent-service.sandbox.enabled`）。开着时容器当 agent 的
  主文件系统（`DockerFilesystemSpec`），`skills/` 这类**必须跨实例看到同一份**的前缀仍走共享库（路由表复用框架的 `RemoteFilesystemSpec`，
  键位与 H-04 远端模式逐字相同），壳工具随之放行；关着时与改造前**逐字一致**（没有容器、没有壳工具）。打开后启动期自检探 `docker version`，
  拿不到就**拒绝启动**——不退回本机执行（「以为隔离了、其实没隔离」比启动失败危险得多）。容器里**不放**技能正文（技能正文走路由读共享库；投影进容器的是技能脚本，H-06b），
  详见 DR-44 与下端「沙箱（H-05）」。
  隔离粒度是**用户**（一个用户一个容器槽位），这条假设有用例钉着：`SandboxIsolationTest` 6 例（不需要 Docker）证明
  「平台造出来的沙箱上下文确实带 `USER`」「不同用户不会捡到对方的沙箱、同一个用户换会话仍回到自己那台」——把隔离粒度改成 `AGENT`，它们会立刻变红；
  H-11 又加 1 例，用真中间件驱动，钉住「先拿锁 → 建容器 → 整轮跑完松手」（手写 release 会录成「只拿不还」，见 DR-51b）。
- H-08 AG-UI 调研结论（2026-09-26，**只验证不替换**）：`agentscope-agui-spring-boot-starter` 是**前端事件协议**（把框架 typed event 翻成
  `RUN_STARTED` / `TEXT_MESSAGE_*` / `TOOL_CALL_*` / `STATE_*` / `REASONING_*` 推给 AG-UI 客户端），所以不替换自研 SSE 层：§12.2 的
  `intent` / `skill_start` / `skill_step` / `sandbox_job` / `artifact` / `clarify` 与 `final` 的溯源标注 / figures 在它那里**没有语义位**（只能塞 `CUSTOM`）；
  它也不管权威与持久化 / 跨副本续看 / 轮次互斥 / 入场券 / 审计（入口只有 `POST /agui/run` 与 `/agui/run/{agentId}`）；
  它的 SSE **既没有 `event:` 名也没有 `id:`**（拿不到 `Last-Event-ID`），而且默认 `interruptOnDisconnect=true`（一断线就把这一轮打断）。
  结论与逐条对照表见 `doc/refactor/TASKS.md` §6「H-08 调研说明」与 DR-50。
- H-11 沙箱跨实例执行锁（2026-09-26，**已完成；跟着沙箱开关默认打开**）：沙箱开着时，**同一个用户同时只放一轮**进容器——拿不到锁的那一轮等一会儿，等不到就按超时失败。
  动手前先核实掉一条旧结论：台账原来写「接执行锁会**替换**框架自带的进程内实现」，实际上 2.0.3 里**框架默认就不锁**（`SandboxExecutionGuard` 的默认值是 `noop`），所以这是**纯新增**。
  底座直接用框架扩展里的 `JdbcSandboxExecutionGuard`，锁的后端**跟着数据库方言走**（生产 PG = `pg_try_advisory_lock`，锁挂在连接上、进程崩了自动松；测试 H2 = 一张锁表），
  平台只负责把它挂到 `DockerFilesystemSpec.executionGuard(...)` 上。不想要就关（单实例试跑）`agent-service.sandbox.distributed-lock=false`，等锁上限 `agent-service.sandbox.lock-timeout`（默认 `5m`）。
  两条代价（记在 L-22）：后到的那一轮要等、等不到就失败；PG 这条路整轮占一条连接，所以同时在跑脚本的用户数不能超过连接池。详见 DR-51 / DR-51b 与 `doc/refactor/TASKS.md` §6「H-11 落地说明」。
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
- §19.4 多副本会话管理（ADR-35）：**会话不粘实例**——状态与历史只有一份权威（框架 `AgentState`，落 PG），
  实例内存只留「这一轮的回放位」；同一会话跨副本同时只允许一轮（Redis 轮次坑位 + CAS 兜底）；
  跨实例重连**降级为「等本轮结束再整段补」**（有窗口上限、超时如实收尾）；实例硬挂时那一轮在历史与实时流里都有
  「没跑完，请重发」的说明；共享单例 agent（键 = 工具面 + 模型 + 迭代上限，每轮注入提示词与回调、每轮结束清 slot 缓存）
- §19.4 stop：`POST /v1/agent/sessions/{id}/stop`（停**单轮**、已产出保留、幂等；请求落到非持有实例也能生效——
  停止信号走共享存储；持有那一轮的实例上还会**实时**收到一条总线推送（H-09），所以它是当场取消，不用等下一个流事件。推送跟着 `agent-service.live-bus` 走，没有额外开关。
  取消同时会**真的打断模型循环**（框架的 `interrupt` 按会话身份打；喊错槽位会变成「界面停了、模型照跑」，见 DR-48 与 `TurnInterruptTest`））
- §19.4 历史会话：`GET /v1/agent/sessions`（只回自己的，按最后活动倒序）+ `GET /v1/agent/sessions/{id}/turns`。
  列表**走的是一条轻路径**：标题（首条提问截断）/ 提问条数 / 最后提问时刻都在**提问那一刻**写进会话档案
  （`platform_session`），所以列表一次对话正文都不读；**历史回放照旧读框架的 `AgentState`**——档案只是列表的索引，
  正文才是真相（升级前的老会话没有档案，列表照旧显示它，只是标题为「未命名会话」、条数为 0）；
  别人的 sessionId 一律 404（§11.3）。**历史直接来自 `AgentState`**（框架状态文档的投影，`SessionTranscript`），
  不再另存一份会话台账——所以「换实例接着看历史」天然成立。投影规则：每轮第一个 `TextBlock` 是用户原话，
  思考块出 `think` 步骤、助手文本出 `token`、工具调用出 `tool` 卡，每轮末尾补 `done`。
  投影是**有损**的（见 TASKS.md L-09）：逐字回放与心跳类过程事件不在历史里，需要时查审计日志。
- §19.4 归档（类比 Codex）：`POST /v1/agent/sessions/{id}/archive`。**归档是逻辑标记，不是删除**——
  规格把「归档与召回」列为后置能力、骨架期记录长期保留（§8.3 / §20.5），会话与该轮回答原样都在，取消归档就能接着聊。
  归档位存在 `platform_session`（框架状态里的 key，见 T1-05）：**只存算不出来的东西**（建档时刻 + 归档标记），
  标题 / 提问条数 / 最后活跃时间全部从 `AgentState` 现算，避免同一件事两个真相（DR-28）；
  列表接口回全部会话 + `archived` 标记，由前端分成「在用 / 已归档」两段。
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
   —— 指标那一格**目前是空壳**（`EventQueueMetrics.noop()` / `LogFirstEventMetrics`），用户 2026-09-26 明确先不做，台账记在 **H-14**
3. RocketMQ 版队列：`EventQueue` 端口已就位（`pg-outbox` / `redis-stream` 两套实现已落地），
   换 RocketMQ 只要重写 `poll / ack / nack / deadLetter / stats`，消费者与业务代码不动（§19.6 / ADR-10）
4. `management-service` M7，以及反馈接口——前端 `final` 卡片上的 👍/👎 后端**没有**对应接口，界面已注明只做本地记录
5. `deploy/docker-compose.yml` 的四个服务容器定义（镜像构建 + 密钥注入）
6. `web/` 的渠道抽象与思考流个人设置（§20.6）；当前是页面级开关，默认只显示步骤卡
7. `runtime-agentscope` 的**完整 TCK**（需要确定性模型桩或真实端点）。仓库默认配置仍是 `noop` + `tck-passed-runtimes=noop`——
   那是准入闸门（§18.11.6），不是「没做完」；本机为了让对话真的走模型，是用环境变量把 `agentscope` 显式加进准入选单的
   （见「接入大模型」一节），属于刻意的本地覆盖。模型侧接线已完成并有单测覆盖（解析 / 切换 / 解密 / 无配置报错 / 能力面基线）
8. Flyway 接入：迁移目前由部署脚本按序 apply（`deploy/migrations/V1–V17`）
9. 模型偶发英文过程旁白：DeepSeek 在**调工具前**会先说一句英文（如 I will query last month outpatient visits），
   这句走 `TEXT_BLOCK`，既进步骤卡、也随 `token` 流进答案气泡。提示词已写明「全程中文、不写过程旁白」，
   实测仍未完全压住（纯对话轮没有该现象，只在调工具的轮次出现）。更稳的做法在投影层而不是提示词：
   把「首次工具调用之前的正文」归到步骤卡、不进答案气泡（§12.2 的 `step` 语义）
10. **数据可见范围过滤**（§19.1 的落地部分）：`role_api.scope` 已经删了，范围过滤目前是接口服务里的
   `TODO`（`DoctorPerformanceApi` 里有位置注释）——**现在各账号看到的数据相同**，别把「alice 也看到财务科」
   当成 bug。要做的是：接口按 `body.caller().userId()` 查出可看范围，把它变成自己 SQL 里的 `where` 条件，
   并把实际用到的条件写进 `data_access_audit`（那一栏现在为空，不要理解成"没有范围限制"）

## 数据库迁移：已在本地 PostgreSQL 实测

`deploy/migrations/` 下 **V1–V17** 已在本地 PostgreSQL 18 空库上全量跑通（旧文档里「有 Docker 后再验证」这条已完成）：

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

工作区共享（V17 建的 `agentscope_store`）也在真库上验过，而且**建表用的就是迁移脚本的原文**（不是用例里另抄一份 DDL）：
两台「实例」（两个存储对象、连同一个库）任意写任意读都看得见；同一个文件的抢写只有一个赢（旧版本号写不进），另一次被拒绝且不落库。

会话状态（V16 建的 `agentscope_sessions`，实现来自框架扩展）同样在真库上验过：按「槽位号 + 状态键」寻址不串门、同一段重复保存是覆盖而不是追加、
载荷（数字 / 文本 / 布尔）原样往返、换实例能读到同一份状态（「不跟进程走」）、删除只删这一段；
带 `version` 的 CAS 写入在并发下不会互相覆盖（旧行版本对不上就拒绝写，而不是「最后写的赢」）。

多副本这件事最后是在**真进程 + 真库**上收口的（T1-15，2026-09-26）：本机建库 `doctor_assistant`（这台机器的 PG 只有 `postgres` 一个角色，
所以迁移与冒烟脚本用 `-User postgres`），16 个迁移从 V1 到 V17 按序全部应用；随后 `deploy/local-windows/two-instance-smoke.ps1`
起两台真实例（8081 / 8082，noop 运行时）把 10 项全跑绿：A 建会话 → B 看到 A 建的会话 → B 跨实例换券 → B 读历史 →
拿 B 的券到 A 接流（535 / 594 ms 正常收尾，不空挂）→ A 起一轮真的跑 → 这一轮跑完 → **事件进了共享总线** → A 停止（幂等）；H-04 又加了一项「两台实例都装配成共享工作区」（看启动日志，因为工作区平面现在关着、没有接口能观察到它），**10 项 PASS / 0 项失败**。
这次真跑还抓出两个「只有真跑才会暴露」的问题，都已修掉：迁移脚本原来按文件名字符串排序（`V10` 会排在 `V2` 前面，从零建库必然失败）、
冒烟脚本原来只杀 Maven 进程（`spring-boot:run` fork 出来的应用 JVM 成了孤儿，既占端口又挂着输出管道，调用方永远等不到脚本结束）。

跨副本**实时**续看（H-01，2026-09-26）另有三层证据：`RedisMessageBusTest` 3 例对着**本机真 Redis** 跑（
游标不重不漏 / 超过日志上限裁掉老条目 / 跨实例订阅能收到推送）；`CrossInstanceTurnRelayTest` 4 例，
新增的那条钉的是「别台正在产出的内容会实时补上来，而且跑完时不重复整段补」；`AgentWebMultiInstanceTest` 5 例。
全量 17 模块 BUILD SUCCESS、**413 例 0 失败**（截至 H-06b；H-05 是 405 例、H-06a 是 394 例、H-04 是 364 例、H-01 是 351 例；H-09 之后为 **421 例**，随后 L-21 验证那批用例把全量推到 **426 例**，H-11 落地后为 **431 例**，H-13 落地后为 **449 例 0 失败**）。
四张 `SpringBootTest` 显式把总线钉回 `memory`，单测结果不被本机 Redis 的有无牵着走。

真进程那一层也补上了：`two-instance-smoke.ps1` 现在会**真的跑一轮**，然后直接到共享 Redis 上找这一轮的总线条目——
为什么不看接口：走接口时「从总线实时读到」与「等跑完整段补」给用户的画面是一样的，只有看键才能证明**发布**这条路真的走了。
实测两台实例启动日志都打出「跨副本实时总线：Redis」；日志键是 Redis list、TTL 7193 s ≈ 2 小时（与 `Duration.ofHours(2)` 一致）；
另有 `agent-service:bus:seq` 这个**不带 TTL** 的条目号计数器——它必须比条目活得久，否则重启后新条目的号会倒回去，游标就乱了。

停止也走了这条总线（H-09，2026-09-26）：`POST .../stop` 现在**先写信号键、再推一条**——信号键（带 TTL）保证「最迟下一次事件也会停」，
推送负责「快」。证据这一层，`ChatServiceTest` 里有两条刻意**不推任何流事件**：只要推送生效，`cancelReasons` 里就会出现 `user_stop`；
`TurnStopChannelTest` 4 例中有 1 例对着**真 Redis** 跑（A 实例推、B 实例收），另 3 例钉住「字段不全被忽略」「订阅方抛异常不带崩发布方」。
真进程那一层，冒烟脚本复跑仍是 **10 项全 PASS**（停止项验的是幂等，默认档行为没有变化）。

工作区共享（H-04，2026-09-26）同样有真机证据，分两层：**真库**这一层，`PlatformWorkspaceStorePgTest` 直接跑 `V17` 迁移脚本建表，
再让两个存储对象连同一个库互相读写（跨实例看得见、抢写只有一个赢）；**真进程**这一层，冒烟脚本把两台实例的 `AGENT_WORKSPACE_STORE` 设成 `jdbc`，
启动日志里各打出「工作区存储：共享库（表 agentscope_store）」，表不存在时实例会直接启动失败——这一条同时钉住了「迁移跑过」。
为什么不看接口：工作区平面现在还关着（技能与子代理都没开），没有任何接口会去读写它，能观察到的只有装配日志。
接线是否真的生效，则由 `SharedWorkspaceTest` 在**真 `HarnessAgent`** 上验：甲实例写进工作区的文件，乙实例读得到（同一个人换会话仍可见、换个人看不见）。

技能下发（H-06a，2026-09-26）的证据分两层。**用例这一层**：`WorkspaceSkillProvisionerTest` 9 例（下发 / 不重写 / 撤销删干净 / 版本升级覆盖写…）、
`RegistrySkillPackageSourceTest` 8 例（「最新一条 `published`」的各种边界）、`SharedWorkspaceTest` 与 `ChatServiceTest` 各 2 例（接线与开关）、
以及 `SkillProvisioningIntegrationTest` 2 例——它把三样都换成**生产那一套**（真表 + 部署时装的对象存储 Bean + 共享工作区表），
下发一个已发布的技能之后，**另一个存储对象 / 文件系统**读得到同样的文件；顺带钉住「打开 `workspace-skills=workspace` 应用照样起得来」。
**真进程这一层还没做**：`two-instance-smoke.ps1` 跑的是 `noop` 运行时（本机没有可用的模型服务），技能下发挂在 AgentScope 运行时的每一轮开始处，
所以它不会被那个脚本碰到。要补这一层，需要一台能跑模型的环境 + 先在管理端真发布一个技能包。
技能**投影进容器**（H-06b，2026-09-26）的证据也分两层。**用例这一层**（不需要 Docker）：`SandboxSkillStagingTest` 3 例
（按用户抄到本机、共享库删了本地也删、身份里的特殊字符不互相撞也跳不出暂存根、没技能时不造空目录）＋`SandboxWiringTest` 11 例
（开关关着时行为逐字不变；开着时每轮落盘并把「用这个目录当投影源」的 `SandboxContext` 交给框架；投影目录名与共享前缀一致；
H-11 的执行锁确实挂在**两层**装配对象上、沙箱关着时即使传了锁也被归一化成不带锁）。
**真容器这一层**：`SandboxDockerEndToEndTest` 2 例真起 `ubuntu:22.04`，钉住「技能投影进容器后 `sh /workspace/skills/demo/scripts/run.sh` 真的打出预期输出」，
以及「**每轮必须先清沙箱状态**」——容器是一轮一个，而框架按内容哈希记「投影做过一次」，不清的话第二轮容器里就没有脚本文件了（见 TASKS.md L-20）。
打开方式与限制见上面「沙箱（H-05）」。
模型供应商配置（V10 的 `sys_llm_provider`）也在真库上验过：写入的是密文、明文在库外解出、
页面与审计只出现 `keyHint`、启用新的会自动关掉旧的（部分唯一索引兜底）、非法适配器在写入期就被 400 挡下。

## 事件落库通道（§19.6 / ADR-28）：两套实现，一个端口

在这条链上，队列只是**把事件搬到 PG 事实表的通道**；事实表的源头始终是本地 append-only 日志（`AGENT_EVENT_LOG_DIR`，
必须挂真实卷）。**注意**：这条链负责的是审计与事实表，不是会话正文——用户与 agent 说了什么由框架的 `AgentState` 权威保存（见 §19.5）。换队列实现不动消费者、不动业务代码，只换 `EventQueue` 的装配：

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

### 沙箱（H-05）：命令在哪儿跑，是一个开关（默认关）

`HarnessAgent` 自带 `shell_execute`，而它**默认**的文件系统是「本机 + 壳」——命令直接跑在应用服务器上。
平台的做法不是「关掉就完事」，而是把这个能力接到**容器**上，再用一个开关决定要不要开（默认 `false`）：

| 变量 | 默认 | 含义 |
| --- | --- | --- |
| `AGENT_SANDBOX_ENABLED` | `false` | `true` = 容器当 agent 的主文件系统（命令在容器里跑）。默认档与改造前逐字一致：没有容器、没有壳工具 |
| `AGENT_SANDBOX_IMAGE` | `ubuntu:22.04` | 容器镜像；本地没有时 `docker run` 会自己拉 |
| `AGENT_SANDBOX_WORKSPACE_ROOT` | `/workspace` | 容器里的工作区根目录；**命令的工作目录就是它**（所以容器侧一律用工作区相对路径，见 L-18） |
| `AGENT_SANDBOX_MEMORY_BYTES` / `AGENT_SANDBOX_CPU_COUNT` | `0` | 单容器资源上限；**0 = 不限制** |
| `AGENT_SANDBOX_NETWORK` | 空 | 容器接入的 Docker 网络；空 = Docker 默认 |
| `AGENT_SANDBOX_SHARED_PREFIXES` | `skills/` | 仍然读写**共享库**（PG）的工作区前缀，逗号分隔。必须**目录边界写法、逐个枚举**：写成 `/` 一条都匹配不上（L-15） |
| `AGENT_SANDBOX_DISTRIBUTED_LOCK` | `true` | 跨实例执行锁（H-11）：**同一个用户同时只放一轮进容器**。锁的后端跟着数据库方言走（生产 PG = advisory lock、进程崩了自动松；测试 H2 = 一张锁表）。单实例试跑可以关掉 |
| `AGENT_SANDBOX_LOCK_TIMEOUT` | `5m` | 等锁上限；等不到就让这一轮失败并报错（不无限挂着）。只在 `AGENT_SANDBOX_DISTRIBUTED_LOCK=true` 时有用 |

打开后三件事同时发生：①容器当主（模型跑脚本碰不到宿主机）；②`shared-prefixes` 里那些路径照旧走共享库，多副本看到同一份；
③壳工具被放行——**这是唯一故意偏离 ADR-31 基线的地方**，所以它必须显式打开，而且白名单那一步会再挡一次：没开沙箱时框架就算把它塞进来也会被移掉。

还有一道**跟着沙箱开关一起走的**保护（H-11，上表最后两行）：**同一个用户同时只放一轮进容器**。因为沙箱槽位按用户分，
而「这个槽位上现在是哪个容器」就记在槽位里——两轮几乎同时开始时，两边都还看不到对方，于是各起一个容器、后写的状态把先写的覆盖掉，
**而且不报任何错**。所以沙箱一开锁默认就开：后到的那一轮等一会儿，等不到就按超时失败（不无限挂着）。

**打开前必须确认 Docker 可用**：启动期自检探一次 `docker version`，拿不到就**拒绝启动**。为什么不是「起不来就退回本机跑」——
那样最坏的情况是运维以为脚本被隔离了，实际它以 agent 服务的身份跑在宿主机上。关着的时候**连探测都不做**（不给不相关的部署加前置条件）。

两条边界要知道：**①容器里的技能是「投影」进去的**（H-06b）——技能正文留在共享库（`skills/` 走路由）、模型按需读，
技能脚本则由平台每轮抄到本机后交给框架投影进容器，所以脚本在容器里跑得动。投影的落点必须与模型看到的路径一致
（`<工作区根>/skills/<技能>`，工作区根默认 `/workspace`），否则模型会照着一个容器里不存在的路径去跑脚本。
想跑脚本的话，光开沙箱还不够：技能下发（`AGENT_WORKSPACE_SKILLS=workspace`）也要开着——没有技能就没有可投影的东西。
**②本机是 Windows 时另有一条环境限制**——`docker exec` 会吃掉双引号分组（L-19），框架「往容器里写文件」在 Windows 上会失败，
真实使用请在 Linux 上跑（本机只用来验证「命令确实在容器里执行」，真容器用例就是这么做的）。

### 运行时基线（ADR-31）：四条「不写就一定会悄悄坏掉」的规矩

`runtime-agentscope` 外壳里包的是 `HarnessAgent`，它**默认自带一整套能力**。这四条是实测踩出来的，
现在都有关闭动作 + 回归测试（`AgentscopeRuntimeBaselineTest`）：

1. **工具面 = 平台工具面**。`HarnessAgent` 会无条件注册 `web_search` / `web_fetch` / `wait_async_results`
   （builder 上没有任何开关能关掉），它们绕开平台的 `ToolInvoker`，于是也绕开 DENY 判定、确认闸口与审计；
   平台没配工具时它们还成了模型唯一能调用的东西。适配器改成**白名单**：平台没注册的一律 `removeTool`，
   框架将来偷偷加工具也进不来。
2. **记忆/压缩钩子必须关**。默认开启时每轮会**多调一次模型**（实测每轮 2 次调用：一次答问、一次偷偷
   「抽取记忆」）。这些调用不在平台成本上报里、也不在事件流里。
3. **工作目录必须钉在 `workspace-dir` 下**。默认按 cwd 建 `.agentscope/`，实测会把**会话原文与记忆账本写进应用
   启动目录**（仓库根）——既是数据外泄，也是仓库污染。
4. **模型侧的 HTTP 失败要翻译成「去哪儿改」**。401/403/404 是运维改一个字段就能好的事，不能和真故障
   共用一句「服务暂不可用，请稍后再试」；翻译只吐写死的常量文本，**上游回包一个字都不带出去**
   （上游内容是对方可控的，可能回显请求头）。

同理，「没配模型供应商」也是可自解释的失败：它走 `RuntimeMisconfiguredException`（`agent-spi`），
由接入层原样下发「请管理员在管理端『模型供应商』页配置并启用一个」，而不是收成 `RUNTIME_UNAVAILABLE`。
