# 改造任务台账（data-assistant）

> **来源**：2026-09-26 设计会话（AgentScope Java v2 官方文档 + `agentscope-java` 源码 + 本仓库代码，三方核查）
> **唯一性**：所有改造任务以本文件为准。任何还没做的，必须能在本文件被直接看到。
> **状态口径**：`未开始` / `进行中` / `阻塞`（必须写原因）/ `待验收` / `已完成`
> **ID 规则**：`DR-*` 决策、`T0-*` P0 任务、`T1-*` P1 任务、`H-*` 暂缓或待定、`L-*` 已知限制
> **机器可读镜像**：`doc/refactor/tasks.yaml`（id 与本文件一一对应）
> **2026-09-26 修正**：框架 2.0.3 **确实自带** `SessionTurnGate` / `DistributedStore` 与现成的 JDBC / Redis / PG / OSS 扩展实现（早前台账里「框架没有 `SessionTurnGate`」的写法是错的）。因此 P1 里凡是「框架已有」的部分一律改成**接线 / 用框架实现**：见 T1-13（状态存储）、T1-14（轮次闸门）、DR-32、H-10。
> **2026-09-26 H-01 修正**：原判断「框架 2.0.3 自带 `MessageBus` 的 Redis 实现、H-01 只是接线」**经源码核查不成立**——框架只带 `WorkspaceMessageBus`（把消息写进工作区文件系统，服务 agent 内部的收件箱 / 异步工具 / 子代理 / 团队，平台按 DR-12 一律关闭）。H-01 的实际形态是**按框架 `MessageBus` 接口自己写实现 + 平台接入层收发**，已落地并验证，见 DR-34、§4 / §7 与 §6 的「H-01 落地说明」。
> **2026-09-26 H-04 完成**：工作区共享（原 H-03 / H-04）已落地——**用框架的 `RemoteFilesystemSpec` + 框架自带的 `JdbcStore`**（落 PG），
> 平台只补装配与「表在不在」的启动检查，**不自己写存储、不引入新的对象存储 SDK**。见 DR-39 / DR-40、§4 与 §6 的「H-04 落地说明」。
> **2026-09-26 H-06a 完成**：技能内容下发与发现已落地——**管理端认版本（`sys_skill_version`）+ 对象存储取字节 + 写进该用户的工作区 `skills/`**，
> 「有哪些技能」由网关给，**发现与按需读正文全用框架自带的 `WorkspaceSkillRepository`**。见 DR-41、§4 与 §6 的「H-06a 落地说明」；H-06b（脚本执行隔离）当时**默认不做**（DR-43：技能只下发内容、不跑脚本），**已于同日补齐**（见下一条）。
> **2026-09-26 H-06b 完成**：技能**投影进容器**已落地——沙箱打开后，每轮先把共享库里的 `skills/` 抄到本机（`SandboxSkillStaging`），再把「用这个目录当投影源」的 `SandboxContext` 放进本轮上下文，框架启动容器时打 tar 灌进去；
> 于是**路由让模型读得到技能、投影让容器跑得动技能脚本**（缺一个就是「模型照着一个不存在的路径去跑脚本」）。默认档仍不开沙箱（DR-43），所以默认行为不变。见 DR-45、L-20、§4 与 §6 的「H-06b 落地说明」。
> **2026-09-26 H-05 装配完成**：沙箱从「选型验证」变成「**可用但默认关着**」——`agent-service.sandbox.enabled=true` 时容器当主文件系统（命令在容器里跑）、`skills/` 前缀仍走共享库、壳工具放行；
> 默认 `false` 时与改造前**逐字一致**；打开后启动期自检要求本机 Docker 可用（拿不到 `docker version` 就**拒绝启动**，不退回本机执行）。见 DR-44、§4 与 §6 的「H-05 落地说明」。
> **2026-09-26 本机 Docker 修好**：`Docker Desktop` 起不来的根因**不是 Docker 装坏了**，而是引导项 `hypervisorlaunchtype` 被设成了 `Off`（两个 WSL2 功能本来就是 `Enabled`，BIOS 虚拟化也是开的）。已用 `deploy/local-windows/fix-wsl2-hypervisor.ps1` 改回 `auto`，**2026-09-26 重启后已验证**：`HypervisorPresent=True`、`wsl -d docker-desktop` 打出 `BOOT_OK`、引擎 `server 27.0.3`、`docker run --rm hello-world` 通过（另：`docker` CLI 原先不在 PATH 上，已加进用户级 PATH，新终端生效）。所以 H-05 / H-06b 都不再卡在环境上（两项均已于 2026-09-26 落地，默认关着；打开方式见 README「沙箱（H-05）」）。
>

---

## 0. 阶段划分

| 阶段 | 主题 | 完成的标志 |
| --- | --- | --- |
| **P0** | 横切与基础设施 | 多副本部署不串味：限流、凭证、审计、鉴权全部共享；stop 可用；MinIO 可用 |
| **P1** | 框架分布式会话管理（去重 + 状态共享） | 起 2 个实例验证 A / B / C 全部成立 |
| **P2+** | 暂缓项（见 §4） | 按需启动 |

**A / B / C 定义**

- **A** 断线重连（含打到别的实例）还能接着看这一轮
- **B** 在别的实例上继续聊（发起新一轮）
- **C** 实例挂了会话还在（真 HA）

**去重口径（贯穿全表）**：凡 AgentScope 本身已有、我们又在外层造了一遍的，删或合并；凡框架没有的（审计、成本、配额、防重放、业务事件投影、提示词、工具本体），保留且不断线。

---

## 1. 决策记录（DR）

| id | 决策 | 关键理由 |
| --- | --- | --- |
| DR-01 | 分期 P0 / P1 / P2+；全部任务记入本台账，未完成的必须可识别 | 用户要求 |
| DR-02 | `AgentState`（会话状态）权威落 **PG**；实现用**框架扩展** `agentscope-extensions-jdbc` 的 JDBC 版（T1-13 之后不再自研）；**不加 Redis 缓存层**，**不按 key 拆后端** | 它是单个 JSON 文档、每轮一读一写；缓存无收益且会破坏 CAS 语义 |
| DR-03 | 状态库实现**直接用框架扩展**（`JdbcAgentStateStore` + 按 DataSource 自动识别的方言：生产 PG、测试 H2），平台只补一处框架漏掉的「按 key 删除」。原先「框架 CAS 非原子、必须自研」的结论**经源码核查不成立**，自研实现已删除 | 框架的 CAS 是单条 `UPDATE ... WHERE version = ?`（看影响行数），插入是 `ON CONFLICT DO NOTHING`——是真 CAS；再自己维护一份属于重复造轮子（用户裁定：框架已有的那部分去掉） |
| DR-04 | 会话记录权威 = 框架 `AgentState`；**不再自建会话记录** | 去重 |
| DR-05 | 「Redis 缓存会话记录副本（TTL）+ 回源 DB + 回填」**删除**；「队列落库」**保留且不断线**（判定为非重复） | 用户裁定：不重复的保留 |
| DR-06 | 我们的 turn / HITL 快照**合并进** `AgentStateStore`（key = `platform_turn`）；删掉 `RuntimeStatePort` 三件套与旧 `agent_state` 表；`runtime_id` / `runtime_version` 校验保留 | 避免把 `(userId, sessionId)` 寻址、CAS、清理逻辑再实现一遍 |
| DR-07 | 每会话一个 `HarnessAgent` 实例 → **共享实例**（框架口径：一个 agent 服务所有用户） | 去重，且 `build()` 很重（技能扫描、tools.json、约 15 个中间件） |
| DR-08 | 跨副本 turn 互斥**实现框架的 `SessionTurnGate` 接口**（平台提供 Redis / 内存两套实现，T1-14）；**不用框架自带的 `LocalSessionTurnGate`**（它是阻塞式公平信号量，会把 HTTP / SSE 请求挂住好几分钟）；正确性底线仍是 CAS（`saveIfVersion`） | 框架 2.0.3 确实有 `SessionTurnGate`；但它的本地实现语义是「排队等」而不是「抢不到就说」，与平台「当场告诉用户上一轮还在处理中」的口径相反 |
| DR-09 | A 先降级（同实例实时；跨实例等本轮结束）；真正的跨副本实时列为独立任务 H-01 | 不与 P1 混期。**（H-01 已于 2026-09-26 完成，落地形态见 DR-34：平台实现框架 `MessageBus` 接口，**不是**「接线框架自带的 Redis 实现」）** |
| DR-10 | 前端历史从 `AgentStateStore` 投影（`listSessionIds` + `AgentState.context`，纯函数、不落库）；`conversation_*` 事实表照旧由事件管线写 | 去重的必然结果 |
| DR-11 | **不设** `maxContextTokens`：它是**工作区上下文注入的预算**（默认 8000），不是模型上下文上限。真正要处理的是**关闭 compaction 后上下文超窗会直接报错**（不是静默截断），需要产品侧可解释降级 | 核查纠正：原先「避免静默截断」的理解是错的 |
| DR-12 | 框架工作区平面**保持关闭**（`disableTranscript` / `disableWorkspaceContext` / memory 系列）；P2 加技能与沙箱时不得顺手打开 | 避免出现三套聊天记录，以及远端日志 O(N^2) 写放大 |
| DR-13 | stop 语义：停**单轮**；已产出保留并落库；状态 `canceled`；幂等；跨副本靠自建信号通道（框架 `interrupt` 不持久化、不跨副本） | 用户批准 |
| DR-14 | 鉴权：网关与 agent-service 统一「拦截器 + 线程上下文」，控制器保持干净；`UserContext` **显式传入** agent 执行链（不能只靠 ThreadLocal） | 用户批准 |
| DR-15 | 网关架构不变（保持 Spring MVC）；agent-service **信任网关签名的身份**，不再自己解 JWT | 用户批准 |
| DR-16 | MinIO **原生安装**（决策时本机 Docker 起不来；现已按原生方式装好并自启，不折腾；Docker 已于 2026-09-26 修好）；对象存储走 `ObjectStorage` 端口 + S3 兼容默认实现 | 用户批准 |
| DR-17 | 删除系统提示词里的「数据范围」表述；`interface-doctor` 的范围过滤 TODO 保持（该能力仍未落地）。**2026-09-26 全仓复核**：注释层面按旧口径描述现状的几处也一并清掉，见 §6「数据范围残留复核」 | 用户要求 |
| DR-18 | 文档与 ADR 同步修订；`§19.6`「本地 append-only 日志是唯一事实源」改为「审计用；**会话状态权威是 `AgentStateStore`**」 | 原提法与框架的权威定义冲突 |
| DR-19 | 记录粒度：token 级**默认不落 PG**（H-02 已于 2026-09-26 **裁定为「不做逐字回放」**，要的是计时与用量，见 DR-54 / H-15） | 削峰；模型可见的是消息与工具结果，不是每个 token |
| DR-20 | P2 工作区 `BaseStore` 后端**默认 PG / JDBC**（H-03 待定） | Redis 在 `noeviction` 下不能被长期文件撑满 |
| DR-21 | 每轮变动的工具清单改用**框架原生机制**：① 把全部工具**完整编组**（未编组的工具永远可见，所以编组必须完备）；② 每轮设置该会话的 `ToolContextState` 激活组并 `saveAgentState`；③ 需要硬撤销时叠加 per-slot **DENY 规则**（`replacePermissionContext`）。**禁止**在每轮路径上调用 `registerAgentTool` / `removeTool` | 核查结论：`Toolkit` 是 agent 级全局可变；`ToolRequestConfig` 只能「加 schema-only 外部工具」或「整体隐藏后端」，无法选出本工具的子集；`onReasoning` 过滤只是 schema 可见性、不构成执行闸门 |
| DR-41 | **技能内容下发（H-06）用「DB 认版本 + 对象存储取字节 + 工作区放文件」**：管理端仍是唯一写者（`sys_skill_version.status=published` 是"哪个版本算数"的唯一权威）；agent 侧只**只读**这一张表拿 `storage_key`，去对象存储取包，再解包写进**该用户的工作区**（H-04 的共享 store，`IsolationScope.USER` 天然按人隔离）。**不开新接口、不新增跨服务鉴权面、不把技能内容在 DB 里存第二份** | 任何实例都自给自足（无单点）；"哪一版算数"只有一处判断；技能按人隔离直接复用工作区命名空间。**一个技能只维护一个最新可用版本**：历史版本行只留档、不参与下发，不做版本并存、不做回滚 |
| DR-42 | **H-05 沙箱形态（已实测选定，2026-09-26）**：走「**沙箱当主 + PG 走路由 + 技能投影进容器**」——文件读写落 PG（多实例共享），命令执行落容器（隔离），容器里要用的技能由框架的投影机制打 tar 进去（内容哈希去重）。**两条硬约束**：①路由前缀必须**逐个枚举**，写成 `/` 一把梭什么都匹配不到（框架会把两侧开头斜杠都剥掉，空前缀只剩「精确等于 /」这一种匹配）；②框架默认投影源钉在「本机工作目录」，必须 `workspaceProjectionEnabled(false)` + 自带 `WorkspaceSpec` 才能换成我们自己的来源 | 用户选定混合版（原话：工作区仍然共享在 PG，只在要执行脚本的时候把技能投影进容器里跑）。**不是纸面推断**：两个用例实测过——假后端钉路由语义（`SandboxFilesystemRoutingTest` 6 例）、真容器钉投影与执行落点（`DockerSandboxSpikeTest` 起真 `ubuntu:22.04` 容器）。框架把这条路写成了正经用法：`RoutedSandboxFilesystem` 的类注释原文就是「保留 primary 沙箱后端上的 shell 执行」 |
| DR-43 | **技能脚本「执行」默认不开 Docker（2026-09-26 定档）**：H-06b 的默认档 = **技能只下发内容、不执行脚本**（技能正文 + 资源文件进工作区，模型按需读，本身就是技能包的全部价值）；Docker 沙箱（DR-42 的形态）降级为**可选升级档**，默认关着。**为什么不干脆选「本机跑」**（`LocalFilesystemSpec`）：①框架**没有**「不用 Docker 的沙箱」，本机模式是「在宿主机上真起进程 + 路径白名单」，限的是路径不是权限——脚本仍以 agent 服务自己的身份读环境变量 / 网络 / 文件，**不构成多租户隔离**；②本机模式**和共享工作区凑不到一起**：框架只在「沙箱当主」时才用 `RoutedSandboxFilesystem` 保 shell，本机当主 + 路由会被包成 `CompositeFilesystem`，shell 执行能力**直接消失**（源码 `HarnessAgent.Builder.build()` 明确分叉，`CompositeFilesystem` 类注释原文：Shell execution is intentionally not supported in this mode） | 用户口径是「能用框架的别自己造 + 与框架重复的去掉」：分布式会话、共享工作区已经用框架能力拿到；脚本执行是**唯一**还需要额外基础设施（容器）才换得到隔离收益的部分，收益与复杂度不成比例时先不做。不做执行面 = 少一层要运维的容器、也不用为它做生命周期管理。**取证过程见 §6「不用 Docker 的那条路」** |
| DR-44 | **H-05 沙箱的装配形态（2026-09-26 落地）**：`.filesystem(DockerFilesystemSpec)` 当主 + `.filesystemRoute("skills/", 共享库文件系统)`（路由表由 `SandboxSharedRoutes` 复用框架的 `RemoteFilesystemSpec` 生成，于是同一份技能在共享库里的**键位与 H-04 远端模式逐字相同**）；**放行框架的壳工具 `ShellExecuteTool`**（这是开关打开后唯一故意偏离 ADR-31 基线的地方，所以必须显式打开）；**关掉框架默认投影**（`workspaceProjectionEnabled(false)`：它扫的是"这台机器的工作目录"，多副本下来源不确定）；**显式写死 `IsolationScope.USER`**（框架的兜底值不会回填到 `SandboxContext` 上，隔离粒度不能靠兜底）；容器快照沿用框架默认的 noop | 用户口径是「混合版：工作区仍然共享在 PG，只在要执行脚本的时候把技能投影进容器里跑」。本轮落地的是**前半段**（共享 + 隔离的执行场所）；「把技能投影进容器、让脚本在容器里闭环执行」当时仍归 H-06b（DR-43 默认不做，**已于 2026-09-26 由 DR-45 补齐**）。**为什么那一期沙箱模式下也不开投影**：投影源是**本机目录**，多副本下每台机器内容不同，等于让"技能从哪来"变成不确定；技能正文的权威始终是共享库（H-06a） |
| DR-45 | **H-06b 的装配形态（2026-09-26 落地）**：每轮三步 —— **清沙箱状态**、**技能抄到本机**、**把本轮 `SandboxContext` 放进 `RuntimeContext`**，技能于是被框架投影进容器。①抄到哪：平台自己的 `workspace-dir/sandbox-skills/<用户>/`，每个用户一份，内容是共享库 `skills/` 的**镜像**（同步而非重建）；②怎么让框架用我们的来源：每轮现造 `SandboxContext`（`spec.toSandboxContext(stagingRoot)`），框架的 `ensureSessionDefaults` 会拿它覆盖 agent 上的默认值；③为什么先清沙箱状态：容器一轮一个，而框架把「投影做过一次」按内容哈希记在沙箱状态里，不清就会「第一轮跑得动、第二轮文件没了」（真容器实测，见 L-20） | 投影源必须是**宿主本地目录**（框架 `WorkspaceProjectionApplier` 用 `Files.readAllBytes` 读源、只收 `isRegularFile`，不认共享存储），所以先在本地摆好再交给框架打 tar 灌进容器——框架自己给中心化技能用的 `MarketplaceStager` 也是这个套路。为什么每轮都抄：技能可见性来自网关授权、随时会变 |
| DR-46 | **H-09 形态（2026-09-26 落地）**：stop 从「拉」改「推」，但只是在框架总线上**多开一个频道**（`TurnStopChannel`，频道名 `agent-service:turn-stop`）。发布顺序是**先写信号键、再推一条**：信号键（带 TTL）负责「**一定到得了**」，推送负责「**快**」——订阅方收到就当场取消本地那一轮，不用等下一个流事件。底座直接用框架 `MessageBus`（H-01 那条 `live-bus`），**没有自己写 Redis Pub/Sub**；`live-bus=memory` 时推送自然退回旧行为 | 用户口径是「能用框架的别自己造」：H-01 已经把接口、订阅容器、停机都做完了，停止只多一个频道。为什么不让信号键直接消失：广播是**尽力而为**，订阅方可能正好没订阅上（滚动重启、网络抖动），信号键是兜底；只靠信号键就是原来那个延迟问题。为什么推送要带 `turnId`：一个用户可能同时有别的会话/轮次在跑，只有（用户、会话、轮次）全对上才是「停我这一轮」，对不上**静默忽略**（广播下「不是我的」是常态，记日志只会变噪音） |
| DR-47 | **H-10 结论（2026-09-26 逐项核源码取证）**：框架 `DistributedStore` 的子项**除沙箱执行锁外一律不接** —— ①`agentStateStore` / ②`baseStore`：平台已有显式接线，框架只在「没设」时才注入；③`sandboxSnapshotSpec`：框架默认就是 noop；④`messageBus`：H-01 已覆盖；⑤`asyncToolRegistry` / ⑥`taskRepository`：框架根本没有消费点（平台也不用后台任务/子代理，ADR-31）；⑦`sessionTurnGate`：框架的接线目标是 `HarnessGateway`，而平台调用链走 `agent.streamEvents(...)`，那条路没被用到（平台自己的闸门 T1-14 已在接入层生效）；⑧`teamClient`：团队功能平台关闭 | 「不接」不是省事，而是每一项都能说清「接了会换掉什么、换来的能力平台用不用得上」。唯一例外是 `sandboxExecutionGuard`：框架**默认不锁**（`SandboxExecutionGuard.noop()`）—— 早前台账里写的「框架 Docker 客户端自带进程内的 `DockerExecutionGuard`」经 2026-09-26 逐类核实**并不存在**（harness 的 docker 包里只有 Sandbox / Client / State / Spec 那几件），所以接分布式锁是**纯新增**、不替换任何东西。当时把「收益小」当成暂缓理由，现在按「静默失败优先修」处理：已落地（H-11 / DR-51） |
| DR-48 | **停止必须带会话身份**：`agent.interrupt(userId, sessionId)`，不许用无参的 `agent.interrupt()` —— 框架已把无参那个标 `@Deprecated`，它打的是默认槽位 `(null, defaultSessionId)`，而平台的每一轮跑在自己的 `(userId, sessionId)` 槽位上，**喊错槽位 = 没停** | 实测踩到过：无参版本下停止返回 200、界面也停了（平台自己标记结束 + 放坑位 + 记停止事件），但模型把整轮 15 个词一个不少地产完——烧 token、工具照跑（违反 TCK-4）。根因是框架的打断标记挂在**会话状态**上（`AgentState.interruptControl()`），本轮循环读的是自己那一份。回退到无参版本，`TurnInterruptTest` 两条用例会红（已实测） |
| DR-49 | **停止之后不提供「从断点继续」（2026-09-26 用户裁定不做）**：停掉就是停掉，再发一句算新一轮——模型看得到之前那半句，所以是「接着聊」不是「接着写」 | 框架没有「恢复同一轮」的接口，只有 HITL 挂起/恢复（已用在工具确认上）；自己做得保留挂起快照 + 另加续跑入口，而语义上模型是「看到半句再重写后半段」，既不是逐字续传、也不保证跟「从没停过」一致，换不到清晰的产品价值 |
| DR-50 | **H-08 结论（2026-09-26 读源码取证；结论：不替换）**：AG-UI 是**前端事件协议**，不替换自研 SSE 层 —— §12.2 的业务事件里 `intent` / `skill_start` / `skill_step` / `sandbox_job` / `artifact` / `clarify`（缺哪些槽），以及 `final` 的溯源标注 / figures / expressions，它都**没有语义位**（只能塞 `CUSTOM`），换过去等于给自研事件套一层壳；它也不提供平台已有的权威面（`AgentStateStore` 状态权威、H-01 跨副本实时续看、T1-07 / T1-14 轮次互斥、一次性入场券、限流、审计、HITL 回执、`GET /sessions` / `/sessions/{id}/turns` / `/sessions/{id}/stop`）—— starter 只暴露 `POST /agui/run` 与 `POST /agui/run/{agentId}` 两个入口；它自带的 server-side memory 是**进程内** `ConcurrentHashMap`（`ThreadSessionManager` 按「用户 + 线程」分槽），多副本各存各的；它的 SSE **只发 `data: {...}`，既没有 `event:` 名也没有 `id:`**（MVC 的 `SseEmitter.event().data(...)` 与 WebFlux 的 `ServerSentEvent.builder().data(...)` 两处都没有 `.id(...)`），客户端拿不到 `Last-Event-ID`，而它默认 `interruptOnDisconnect=true`（一断线就把这一轮打断），与「断了换一台实例接着看」正相反 | 平台已经有更好的那套：权威 = 框架 `AgentStateStore`（DR-02 / DR-03）、跨副本续看 = H-01 的总线游标、轮次互斥 = T1-07 / T1-14。能力缺口本身就足够决定，不需要靠「依赖版本不合」来否决。两条不构成否决但记下的事：①starter 依赖声明 `spring-boot-starter-web` / `webflux` / `configuration-processor` 都是 **4.0.3**，项目主线 Boot **3.4.1**（项目现在只用 agentscope 的 core / harness / extensions，没碰过它的 spring-boot starter），根 pom 的 BOM 会把版本压回 3.4.1 —— 能不能在 Spring 6.2 上跑**没实测**，是一条未验证风险；②它的词汇表（`STEP_STARTED` / `TOOL_CALL_*` / `REASONING_*` / `TEXT_MESSAGE_CHUNK`）与 §12.2 基本能对上，将来真要接 AG-UI 生态客户端是**新增一个 `/agui` 通道**，不是替换 |
| DR-51 | **H-11 形态（2026-09-26 落地）**：沙箱执行锁接框架扩展的 `JdbcSandboxExecutionGuard`，**锁的后端跟着数据库方言走**（生产 PG = `pg_try_advisory_lock`，锁在连接上、进程崩了自动松；测试 H2 = 一张锁表），并且**跟着沙箱开关默认打开**（`agent-service.sandbox.distributed-lock`，默认 `true`；等锁上限 `lock-timeout` 默认 5 分钟）。挂锁的位置是 `DockerFilesystemSpec.executionGuard(...)`——框架 javadoc 里的用法；**不走** `DistributedStore` 那条聚合接线 | ①不接聚合是因为平台的状态库 / 工作区库都已显式接好（T1-13 / H-04），为一个锁换整条接线不划算，而框架本来就支持只挂锁；②用 JDBC 版是因为本模块已有 `DataSource`，Redis 版要引入 Jedis 客户端；③锁挂「用户」层，与沙箱槽位同粒度（`IsolationScope.USER`），否则要么挡不住、要么误伤别人；④默认开是因为它防的是**静默失败**（同用户并发两轮各起一个容器、后写覆盖先写，不报错），沙箱关着时锁被归一化成 `null`，默认档行为一字不变。代价如实记在 **L-22** |
| DR-52 | **H-13 形态（2026-09-26 落地）**：本地事件日志不再只增不减，按两个条件收拾 —— ①**裁剪**：只删**开头**那段「已确认投递 **且** 早于保留窗口（默认 **7 天**，agent-service.event-log-retention）」的记录，遇到第一条「不能删」的立刻停（只裁前缀，裁中间会让字节位点失去意义）；②**滚动**：活动文件超过单文件上限（默认 **256 MB**，agent-service.event-log-max-bytes）时，把「还没确认投递的尾巴」**复制**进新文件、旧文件带时间戳改名留档（agent-{instanceId}-{yyyyMMddHHmmss}.jsonl），位点归零；留档到点整份删。三个动作由**每个实例自己的**调度器按分钟跑完（maintenance = 落盘 → 裁剪 → 滚动 → 清理留档，agent-service.event-log-maintenance-interval-ms 默认 1 分钟）。**未确认投递的记录一条都不删、也不滚** | ①日志是审计的唯一事实源（ADR-28），但「唯一事实源」不等于「无限长」：一个实例连着跑，那个文件会一直长到把磁盘占满，撑爆之后连追溯都做不了；而原来的实现里裁剪这条逻辑**从来没被调用过**、判据又写错（一条也裁不掉）、单文件上限配了却从未使用；②三条规矩都绕着同一件事——**没送进队列的绝不能丢**：裁剪只裁前缀；滚动时未确认的尾巴复制进新文件再留档旧文件，于是那几条在磁盘上**短暂存在两份**（靠消费端按 event_id 幂等兜住，不是新 bug）；**一条都没确认时干脆不滚**（滚了只是把文件整个复制一遍，还会每分钟再造一份）；③每个实例只碰自己的文件，所以这一整套**不需要分布式锁** |
| DR-53 | **会话列表改读「会话档案」（H-13）**：列表要的三个数（**标题** = 用户问的第一句话截断、**提问条数**、**最后提问时刻**）在**提问的那一刻**写进 platform_session（PlatformSessionStore#recordQuestion，由 ChatService 在跑模型之前顺手写、失败只 warn 不影响这一轮）；SessionCatalog#list 只读档案，**一次对话正文都不读**。历史回放（/turns）照旧读框架的 AgentState | 原来打开一次列表要把每个会话的整段对话读出来，只为了算这三个数——一个用户几百个会话就是几百次大对象读，而这些数在提问那一刻平台手上就有全部事实（标题就是用户刚打的那句话）。口径必须写清「**档案是索引、正文是真相**」：档案允许落后（例如绕过平台直接往状态库塞过对话），落后可自愈（再问一句就跟上），而历史回放一个字都不来自档案——升级前的老会话没有档案，列表照旧显示它，只是标题为「未命名会话」、条数为 0 |
| DR-54 | **H-02 裁定（2026-09-26）：逐字回放不做** —— token 级增量只在流里走、不落库；真正该补的不是逐字内容，而是**计时 + 用量**，另开 **H-15**（首字延迟 / 总耗时 / token 用量 / 成本） | ①同类产品就这么做：Codex 自己的会话记录（本机 37 个 rollout 文件逐字段扫过）只有条目级 `message` / `reasoning` / `function_call` / `function_call_output` + 每条的 `started_at`/`completed_at` + 一份 `token_usage_record`（数字，不是 token 内容），**名字里带 delta 的字段一个都没有**，连推理都只存摘要 + 加密块（加密块给模型续跑用，不是给人看）；②框架也这么划线：AgentScope 自家 `agentscope-service` 把 `EVENT_START` / `EVENT_DELTA` 标成 **Stream-only (never persisted)**，被持久化的只有 `agent.message`/`agent.thinking`/`agent.tool_use`/`agent.tool_result`/span/会话状态，与我们事实表那几类一一对应，而框架的 `TranscriptMiddleware` 也是每轮结束追加 `AgentState.context` 的**消息**；③需求上不需要：审计要的是口径/表/过滤/结果，评估要的是最终答案，排障要的那点在本地 append-only 日志里就有（H-13 之后 7 天窗口内确实存着逐字事件）；④代价：1 万轮/天 × 800 delta ≈ **800 万行/天**（块级约 10 万行/天，差 80 倍），且逐字是高频小写入，正好把 H-13 的滚动与裁剪推回去 |
| DR-51b | **顺带修正 DR-47 的一句错话**：那里写「框架 Docker 客户端自带进程内的 `DockerExecutionGuard`」**不成立**—— 2.0.3 里没有任何 `DockerExecutionGuard`，`SandboxExecutionGuard` 的默认值就是 `noop`（不锁），harness 的 `sandbox/impl/docker/` 里只有 Sandbox / Client / State / Options / Spec。所以 H-11 是**纯新增**，之前那条「会替换掉框架自带保护」的风险不存在。另一条实测事实：**松锁是框架的 `SandboxLifecycleMiddleware` 做的**（顺序 `SandboxManager.release` → `lease.close()`），不在 `SandboxManager.release` 里 —— 写用例时自己手写 release 会得到「只拿不还」的假象（本轮真踩到过，见 §6「H-11 落地说明」） | 这两条都是「读代码 + 跑用例」得到的，不改行为、只改认知：把不存在的风险去掉，把真实的分工记下来 |
| DR-22 | 共享单例 agent 必须配套**有界内存**：每轮结束清理该 slot 的 `stateCache`（框架在有 store 时会每次调用重新加载状态，缓存可以安全清理），否则堆随会话数无界增长 | 核查结论：`stateCache` / `slotVersions` / `permissionEngineCache` 都是无 LRU、无上限的 `ConcurrentHashMap` |
| DR-23 | 熔断保持**实例内**计数，不做跨副本共享；其余内存态（限流、入场券、nonce、停止信号）一律共享 | 熔断是「保护下游」的本地动作：某台发现下游在抖就先停它自己的转发，各台独立开合不会串味，也不需要全局一致；做成共享反而每次转发多一次 Redis 往返，还得定义「谁负责复位计数」。限流与凭证恰恰相反——各数一份会被放大成「副本数 × 额度」，是正确性问题 |
| DR-24 | MinIO 社区版已归档（`dl.min.io` 一律 410 Gone）→ 本机装 GitHub Releases 上**最后一个开源版本**（`RELEASE.2025-09-07T16-13-09Z`，下载后按官方 SHA256 校验），明确只用于开发；生产换仍在维护的 S3 兼容对象存储 | 官方不再分发与维护社区版；对象存储已按端口抽象（T0-08），换实现不动业务代码 |
| DR-25 | 共享文件走 `ObjectStorage` 端口，实现由 `object-storage.provider`（`local` / `s3`）在**装配期**决定；业务只认端口。S3 实现**不自动建桶**、`putIfAbsent` 先查后写、列表分页到底 | 本机开发不该被中间件绑架（`local` 够用），多副本必须有共享存储（`s3`）；桶是运维资产，应用偷偷建桶会把数据放到没人预期的位置 |
| DR-26 | 对象存储里业务自己带路径前缀（如技能包 `skill-packages/`），配置里的 `key-prefix` 只用于**环境隔离**（多环境共用一桶时不串数据） | 两层前缀职责不同：一个是业务分类、一个是部署隔离，混在一起以后没法按前缀设生命周期策略 |
| DR-27 | 版本 CAS **交给框架扩展**（T1-13），不再由平台自研 SQL。平台唯一自己写的 SQL 是「按 key 删除」，而且直接复用框架方言里现成的 `sessionStateDeleteByKey` 语句，不手写 | 自研 CAS 已被证明是重复实现（框架那份同样正确）；手写 SQL 一旦带上方言特性，测试库与生产库就会变成两套语义 |
| DR-28 | 历史 = `AgentState` 的**投影**，不再另存一份会话台账；会话档案（`platform_session`）只存**算不出来**的字段：建档时刻与归档标记。标题 / 提问条数 / 最后活跃时间都从状态现算 | 「存两份就有两个真相」：投影能算出来的字段一旦落库，就会出现「状态改了、台账没改」的分歧；反过来，建档时刻在 `AgentState` 里没有，才必须单独存 |
| DR-29 | 框架写死的存储键 `agent_state` 是**显式耦合点**（`SessionCatalog.AGENT_STATE_KEY` 常量 + 注释写明）；任何 AgentScope 升级都必须先跑 `AgentStateContractTest` | 历史现在完全寄托在框架的状态文档结构上；框架若改键名或改文档结构，表现是「历史全空」这种静默故障，必须有护栏用例把它变成红灯 |
| DR-30 | 跨实例接流**降级为「等本轮结束再整段补」**（`CrossInstanceTurnRelay`）：轮询共享状态库，等这一轮落盘后一次性补齐；等待窗口 = SSE 超时 − 5 秒，每秒看一次 | 轮询读的是状态库投影，天然跨实例、不需要额外中间件、也不会引入「事件总线连没连上」这种新失败模式；实时推送属于 H-01（接框架 `MessageBus`），不与 P1 混期 |
| DR-31 | 接流票据 `EntryTicket.turnId` **允许空串**（= 不指定轮次）；换券时若本机没有这一轮，就从共享的开始标记 `platform_turn_live` 里取轮次号 | 一次性券是跨实例核销的：发券的实例与接流的实例可能不是同一台，票据里没带轮次号时不能当成「没有可续看的」 |
| DR-32 | **把框架 `MessageBus` 的 Redis 实现接成 `DistributedStore.messageBus()`**，用它支撑跨副本实时续看（原 H-01）。框架 2.0.3 自带 `DistributedStore` 一站式接口（agentStateStore / baseStore / sessionTurnGate / messageBus / asyncToolRegistry / 沙箱快照与执行锁），`HarnessAgent.Builder.distributedStore(...)` 会自动接线 | **（修正：见 DR-34 —— 这两句都不成立。框架只带 `WorkspaceMessageBus`，没有 Redis 实现；平台的接入层也没走 `HarnessAgent.Builder.distributedStore(...)`，因为 agent 内部那条总线随 DR-12 一起关着。H-01 的实际形态是「按框架接口自己写实现」）** |
| DR-33 | **数据范围归接口服务**：网关只判定接口可用性（`apiSet`）并下发身份（`userId + requestId`）+ 签名，**不算也不下发范围**；范围由**接口服务基于登录人自行推导**（推导不出来 → 403）；**列级没有白名单**，返回哪些列由接口自己的 SQL 与返回类型写死 | 用户拍板（2026-09-26）：范围是「业务表结构 + 登录人」的知识，只有接口服务具备；让权限库猜业务表结构，接口一复杂就得改权限库。规格书写进 ADR-37（T1-16） |
| DR-34 | 跨副本实时续看（原 H-01）**由平台实现框架 `MessageBus` 接口**：`RedisMessageBus`（默认）/ `InMemoryMessageBus` 两套实现，平台用 `LiveTurnChannel` 收口读写，`CrossInstanceTurnRelay` 先按游标**追日志**拿实时内容、追不到再退回「等落盘整段补」；**不接进** `HarnessAgent.Builder.distributedStore(...)`（agent 内部那条总线是关闭的） | ① 先纠正判断：框架只带 `WorkspaceMessageBus`（写工作区文件系统，服务 agent 内部），**没有 Redis 实现**，DR-32 的「接线活」不成立；② 用框架接口而不是自造一套：语义（会话事件流 + 游标）正好复用 `sessionPublishEvent / sessionReadEvents`，将来框架补上 Redis 实现可原样替换；③ **追日志不订阅**：「我已经错过了一段」是重连场景的常态，订阅在断线那一瞬就丢消息，追日志按游标自愈；④ 实时只是加速、不是权威：总线带 TTL，写失败只 warn，权威始终是 PG 的 `AgentState`；⑤ 「无人管了」的判定与整段补只在 `CrossInstanceTurnRelay` 一处决定，避免同一段下发两遍 |
| DR-39 | 工作区共享（原 H-03 / H-04）用**框架的 `JdbcStore` + PG**（`RemoteFilesystemSpec`），**不引入阿里云 OSS SDK**；隔离范围取 `USER`（一个用户一个命名空间，与 H-06 的「技能按人隔离」同一个粒度）；开关 `agent-service.workspace-store`（`none` 默认 / `jdbc`），改成 `jdbc` 不动代码；平台侧 `PlatformWorkspaceStore` **只是一处装配点**，不包装饰器、不改写框架 SQL | ① 平台的对象存储栈是 AWS SDK v2 S3，而框架的 `OssBaseStore` 只认阿里云 OSS —— 直接用要么硬拉一个新 SDK、要么把两套对象存储语义混在一起；② 框架的 `JdbcStore` 已经把 CAS、前缀搜索、错误翻译做全了，再包一层就是重复造轮子；③ 默认 `none`：工作区平面按 DR-12 还关着，开了只会多一次库往返而没人用，等 H-06 用上再切 |
| DR-40 | **工作区共享与会话状态共享必须同时成立**：框架在 `HarnessAgent.build()` 里检查到 `AgentStateStore` 是本进程实现（`JsonFileAgentStateStore` / `InMemoryAgentStateStore`）就**直接抛错**，拒绝装配远端文件系统。平台的对应口径是「两个都落 PG」：`PlatformAgentStateStore`（会话状态）+ `PlatformWorkspaceStore`（工作区文件） | 「文件是同一份、状态各是各的」这种半分布式组合在多副本下必然错乱（A 机器写的工作区，B 机器带着自己的旧状态去读），框架直接禁止比运行期出错好。平台侧不需要额外做什么——本来就用 JDBC 状态库，这一条只是把「为什么两者绑在一起」写下来 |

---

## 2. P0 任务（横切与基础设施）

| id | 任务 | 状态 | 依赖 | 验收 | 涉及文件 |
| --- | --- | --- | --- | --- | --- |
| T0-01 | 网关鉴权与身份上下文统一：拦截器 + `UserContext` + `@CurrentUser` 解析器 | 已完成 | — | 控制器里不再出现 `tokenVerifier.verifyAuthorizationHeader(...)`；ThreadLocal 有 try/finally 清理 | `gateway/src/main/java/com/djzy/assistant/gateway/web/GatewayDataController.java`、`.../web/CapabilitiesController.java`、`.../config/GatewayConfig.java` |
| T0-02 | agent-service 同样统一：删掉每个接口的 `authenticator.requireUser(authorization)` | 已完成 | T0-01 的公共上下文 | `AgentController` 与 `ChatStreamController` 里没有身份解析样板 | `agent-service/web/.../web/AgentController.java`、`.../web/ChatStreamController.java`、`.../auth/Authenticator.java` |
| T0-03 | 网关审计记账移出 `apiCall`，改为 AOP（注解 + `@Around`），每条决策路径都要记账 | 已完成 | T0-01 | `apiCall` 只剩业务主流程；DENY / ALLOW 各路径审计条数不变 | `gateway/.../web/GatewayDataController.java`、`common/persistence/.../JdbcPermissionAuditWriter.java` |
| T0-04 | `UserContext` 跨线程显式传递（agent 执行链、SSE 推送） | 已完成 | T0-01 | 跑在别的线程池上的 agent 调用与 SSE 推送能拿到正确身份；无 ThreadLocal 泄漏 | `agent-service/core/.../runtime/`、`agent-service/web/.../web/SseEmitterBridge.java` |
| T0-05 | 删除系统提示词里的「数据范围」表述，并复核提示词稳定性 | 已完成 | — | 提示词中不再出现数据范围；除日期外无易变内容。**2026-09-26 又做了一次全仓复核**（含注释与迁移脚本），见 §6「数据范围残留复核」 | `agent-service/web/.../prompt/SystemPromptComposer.java` |
| T0-06 | stop 接口：HTTP 入口 + Redis 停止信号通道 + 语义实现 | 已完成 | T0-04 | 停单轮；已产出保留；状态 `canceled`；重复调用幂等；打到非持有实例也能生效 | `agent-service/web/.../web/AgentController.java`、`.../service/ChatService.java`、`common/agent-spi/.../AgentRuntimePort.java`（`cancel` 已存在） |
| T0-07 | 本机原生安装 MinIO（自启托管、数据目录、启停脚本、文档） | 已完成 | — | 9000 / 9001 可访问；登录后自动启动；数据目录固定（`D:\minio\data`）且有说明；桶 `doctor-assistant` 已建好 | `deploy/local-windows/`（新增：`install-minio.ps1`、`minio-run.ps1`、`minio-start/stop/restart/status.ps1`、`_minio-common.ps1`、`apply-migrations.ps1`、`README.md`） |
| T0-08 | `ObjectStorage` 端口 + S3 兼容实现；替换 `SkillPackageStore.Local` | 已完成 | T0-07 | 技能包改走对象存储；换云对象存储只需新增实现 | `common/platform/.../common/storage/{ObjectStorage,LocalFileObjectStorage}.java`、`common/object-storage/.../s3/{ObjectStorageProperties,ObjectStorageConfig,S3ObjectStorage}.java`、`management-service/.../skill/{SkillPackageStore,ObjectStorageSkillPackageStore}.java` |
| T0-09 | 内存态全部 Redis 化并切换默认，保留可回退开关 | 已完成 | — | 多副本下限流、入场券、nonce、停止信号均共享；**熔断刻意保持实例内**（理由见 DR-23）；每个开关都能显式切回 `memory` 跑单机 | `gateway/.../core/{UserRateLimiter,InMemoryUserRateLimiter,RedisUserRateLimiter}.java`、`agent-service/web/.../auth/`、`.../service/{TurnRateLimiter,RedisTurnLimiter}.java`、`.../session/{InMemory,Redis}TurnStopSignalStore.java`、`interface-doctor/.../config/DoctorInterfaceProperties.java`、三处 `application.yml` |
| T0-10 | 部署文档纠偏：新增本机原生启动说明，`docker-compose.yml` 标注为 CI / 生产参考 | 已完成 | T0-07 | 文档与实际运行方式一致（本机原生 vs 容器两条路径分明） | `deploy/docker-compose.yml`、`deploy/local-windows/README.md`、`README.md` |

---

## 3. P1 任务（框架分布式会话管理：去重 + 状态共享）

| id | 任务 | 状态 | 依赖 | 验收 | 涉及文件 |
| --- | --- | --- | --- | --- | --- |
| T1-01 | 自研 PG 版 `AgentStateStore`（单值 + CAS + `TEXT` 列）→ **已被 T1-13 取代**：契约与寻址不变，实现换成框架扩展，自研实现与其 15 个用例一并退场 | 已完成 | — | 曾经达成：`getVersioned` / `saveIfVersion` 是真版本 CAS、版本在同一事务内回填、并发写不互相覆盖 | `agent-service/state/`（模块 artifactId `agent-state-store`，现存 `PlatformAgentStateStore` / `PlatformTurnStore` / `PlatformSessionStore` / `PlatformLiveTurnStore`） |
| T1-02 | 迁移脚本：会话状态表按框架 JDBC 扩展的口径建（`deploy/migrations/V16__agent_state_framework_store.sql`，表 `agentscope_sessions`） | 已完成 | T1-01 | 新库一次跑通（本机 `apply-migrations.ps1` 全绿）；项目未上线，旧表直接 `DROP`，不写数据搬迁；表由**迁移脚本**建，应用侧用 `autoCreateTable(false)` 关掉运行期建表 | `deploy/migrations/V16__agent_state_framework_store.sql` |
| T1-03 | 替换 `AgentServiceConfig` 里硬编码的 `JsonFileAgentStateStore`，并让 workspace 可注入（**与 T1-02 / T1-04 同批**） | 已完成 | T1-01、T1-02 | 换实例后模型能拿到完整上下文（B 成立）；落点只有 PG，没有「退回本地文件」的开关 | `agent-service/web/.../config/AgentServiceConfig.java`、`.../config/AgentServiceProperties.java`、`.../src/main/resources/application.yml` |
| T1-04 | 合并 `RuntimeStatePort` → `AgentStateStore`（key = `platform_turn`）；删除端口三件套与旧表 | 已完成 | T1-01 | HITL 挂起 / 恢复行为不变；`main` 代码里已搜不到 `RuntimeStatePort` | 已删：`common/agent-spi/.../RuntimeStatePort.java`、`common/persistence/.../PgRuntimeStatePort.java`（含测试）、`agent-service/web/.../state/FileRuntimeStatePort.java`；改动：`ChatService`、`AgentServiceConfig`、`AgentWebH2Config`、`AgentApiTest`、`ChatServiceTest`、`RuntimeTck` |
| T1-05 | 删除自建会话记录：journal 四件套 + history / summary；历史改为 `AgentState` 投影 | 已完成 | T1-03 | 历史列表与历史消息可用且与 `AgentState` 一致；磁盘上不再有 `sse-<instanceId>.jsonl`（证据：`SessionTranscriptTest` 8 例、`AgentWebHistoryTest` 4 例、`AgentStateContractTest` 2 例全绿，全量 17 模块 BUILD SUCCESS） | 已删：`SessionJournal`、`FileSessionJournal`、`InMemorySessionJournal`、`JournalRecord`、`SessionHistory`（含测试）；新增：`SessionCatalog`、`SessionTranscript`、`StreamRecord`、`agent-service/state/.../PlatformSessionState` + `PlatformSessionStore` |
| T1-06 | 共享单例 `HarnessAgent`（去掉每会话一个实例），按 DR-21 实现每轮工具清单，按 DR-22 清理 slot 缓存 | 已完成 | DR-21、DR-22 | 单实例服务多用户；不同用户工具清单仍正确；每轮结束清理 slot 缓存；不再产生 `GracefulShutdownManager.stateSavers` 泄漏 | 新增 `SharedAgentPool`（LRU 上限 32，满了 `close()`）、`CallAttributes`（本轮信息全走 `RuntimeContext`）、`PerCallSystemPrompt`（提示词按轮注入）；改 `AgentscopeRuntimeAdapter`（agent 键 = 模型 × 迭代上限 × 工具面，**刻意不含 userId / sessionId / 提示词**）、`PlatformToolAdapter`（工具对象不再持有发起者回调）、`AgentRuntimePort`（新增 `release`）；证据：`SharedAgentTest` 4 例，全量 17 模块 BUILD SUCCESS |
| T1-07 | 跨副本 turn 互斥：实现框架 `SessionTurnGate`（Redis / 内存两套），抢不到**当场拒绝**而不是排队；CAS 兜底 | 已完成 | T1-01 | 两实例同跑同一会话不会互相覆盖；抢不到的那一轮明确收尾（`TURN_IN_PROGRESS` + `done`）；**不是**框架 `LocalSessionTurnGate` 那种阻塞排队 | 改 `RedisSessionTurnGate`（`SET NX PX` 占坑 + Lua 比对删除；坑位里存随机令牌，不存「哪一轮」）、`InMemorySessionTurnGate`；新增 `TurnGateKeys`（键 = `userId:sessionId`）、`ChatService.abandonBecauseBusy`；排障用「谁在跑」从 `platform_turn_live` 读（`holderOf`）；证据：`InMemorySessionTurnGateTest` 6 例 + `RedisSessionTurnGateTest` 4 例（真 Redis） |
| T1-08 | 轮次中断标记：实例硬挂时前端可解释「本轮已中断，请重发」 | 已完成 | T1-04 | 硬挂后历史里该轮有明确中断标记，不再是「用户问过但 agent 不记得」的静默状态 | 新增 `PlatformLiveTurnState` + `PlatformLiveTurnStore`（键 `platform_turn_live`：开跑写、跑完删、**挂起时刻意保留**，续跑要用它拿回用户原话）；`SessionTranscript.Trailing`（NONE / RUNNING / INTERRUPTED）；证据：`AgentWebInterruptedTurnTest` 3 例 |
| T1-09 | A 降级实现：同实例重连实时；跨实例等本轮结束 | 已完成 | T1-05 | 两实例下重连行为符合 DR-09 / DR-30。**（修订：跨实例那半条已由 H-01 升级为「实时追共享总线」，本行描述的整段补降级保留为兜底；见 DR-34）** | 新增 `CrossInstanceTurnRelay`（本实例没有这一轮时接流：先追总线实时补，追不到再轮询等落盘 → 三种结局：整段补 / 报中断 / 报仍在处理，**每种都发 `done` 闭合**；轮询用 `Flux.range(...).concatMap(delay)`，不能用 `Flux.interval`）；`ChatService.attach` 分两条路、`resumeTicket` 无本机轮次时从 `platform_turn_live` 取；前端 `ChatView.openSession` 对 `running` 会话改走 `attach()`；证据：`CrossInstanceTurnRelayTest` 4 例 |
| T1-10 | 两实例端到端验证 A / B / C | 已完成 | T1-03、T1-05、T1-09 | 自动化部分：`AgentWebMultiInstanceTest` 5 例覆盖「换实例继续聊 / 断线接流 / 硬挂解释」；全量 `mvn -B install` 17 模块 BUILD SUCCESS（351 例）；人工冒烟脚本已就绪但**尚未实跑**（见 T1-15） | 新增 `AgentWebMultiInstanceTest`（实例 B 用同一套共享装配 + 独立注册表与实例名）、`deploy/local-windows/two-instance-smoke.ps1` |
| T1-11 | 文档与 ADR 修订（`§19.5` / `§19.6` / `§19.4` / `§8.4` + 新增 ADR-35 / ADR-36） | 已完成 | T1-01 起 | 文档口径与实际实现一致，无自相矛盾（表名、存储实现、组件选型都对得上） | 规格书「v3.4」行 / ADR-35 / 新增 ADR-36 / `§19.5` / L1388 / L1407 / L1495、`README.md` 的 §19.5 条目与「真库上验过」那一段 |
| T1-12 | 确认 `PlatformToolAdapter` 是否为框架 `ToolBase` 子类 | 已完成（结论：**不是**） | — | 结论已落进类注释：它是 legacy `AgentTool`，走 ReActAgent 的 legacy 直通分支，**框架的 DENY 规则对它无效**；所以工具可见性的唯一闸门就是「这一轮注册了哪些工具」（DR-21） | `agent-service/runtime/runtime-agentscope/.../PlatformToolAdapter.java` |
| T1-13 | 会话状态存储**改用框架扩展**（`agentscope-extensions-jdbc`），删掉自研 `JdbcAgentStateStore`；平台只补一处框架漏掉的「按 key 删除」 | 已完成 | T1-01 | 表名 `agentscope_sessions`，槽位号 `<userId>:<sessionId>`；自研实现与其测试、`schema-agent-state.sql` 全部删除；证据：`PlatformAgentStateStoreTest` 5 例（含钉住槽位号拼法的一条），全量 17 模块 BUILD SUCCESS | 新增 `PlatformAgentStateStore`（装饰框架实现：只补按 key 删除，其余一律委托）、`V16__agent_state_framework_store.sql`；删 `JdbcAgentStateStore.java`、`JdbcAgentStateStoreTest`、`schema-agent-state.sql`；改 `AgentServiceConfig`（`@DependsOnDatabaseInitialization`）、`agent-service/state/pom.xml` |
| T1-14 | 轮次闸门改用**框架接口** `io.agentscope.harness.agent.gateway.SessionTurnGate`（删掉平台自定义的同名接口） | 已完成 | T1-07 | 平台不再定义自己的闸门接口；语义仍是「抢不到当场拒绝」（`TurnBusyException`）；证据：`InMemorySessionTurnGateTest` 6 例、`RedisSessionTurnGateTest` 4 例、`CrossInstanceTurnRelayTest`、`AgentWebMultiInstanceTest`、`ChatServiceTest` 全绿 | 删 `agentweb/session/SessionTurnGate.java`；新增 `TurnGateKeys`；改 `ChatService`、`CrossInstanceTurnRelay`、`AgentServiceConfig`、`ChatSession`（新增 `holdTurnLease` / `takeTurnLease`） |
| T1-15 | 在本机**实跑**两实例冒烟脚本（人工确认 A / B / C 三个场景） | 已完成 | T1-10 | **首跑 6 项全 PASS / 0 失败**（A 建会话 → B 看到 A 建的会话 → B 跨实例换券 → B 读历史 → 拿 B 的券到 A 接流（555 ms 正常收尾、不空挂）→ A 停止幂等）；本机建库 `doctor_assistant`，15 个迁移 V1→V16 按序全部应用（本机 PG 只有 `postgres` 角色，脚本用 `-User postgres`）。命令：`deploy\local-windows\two-instance-smoke.ps1 -JwtSecret 'dev-smoke-secret' -DbUser postgres -DbPassword '<口令>' -SkipBuild`。**2026-09-26 复跑（H-01 落地之后）**：**9 项全 PASS / 0 失败**——新增两项「A 起一轮（真的跑）」与「事件进了共享总线（H-01 发布）」；第 6 项耗时 535 / 594 ms（两次复跑）；总线键实测是 Redis list、TTL 7193 s ≈ 2 h，与代码里 `Duration.ofHours(2)` 一致；两台实例的启动日志都打出「跨副本实时总线：Redis」。命令同上（脚本现在会显式把 `AGENT_LIVE_BUS` 钉成 `redis`）。 | `deploy/local-windows/two-instance-smoke.ps1`、`deploy/local-windows/apply-migrations.ps1` |
| T1-16 | **规格书的「数据范围 / 列白名单」口径与实现对齐**（通读性修订） | 已完成 | — | 口径按你的拍板定稿：**数据范围由接口服务基于登录人自行推导**（网关只判定接口可用性并下发身份、不算也不下发范围；列级没有白名单，由接口自己的 SQL 写死）。规格书全量对齐并新增 ADR-37；V13 迁移里两列的 DROP 就是实现侧的证据 | 规格书 §0.2 版本行 / §0.3-7/12 / §1 / §3.4 / §3.5 / §4.1 / §4.3 / §4.5–§4.10 / §6.6 / §7.1 / §11.2 / §16-1 / §17 / §18.1.2 / §18.1.3 / §18.4.2 / §18.4.5 / §18.4.6 / §18.5.1 / §18.8 / §18.11 / §19.1 / §19.3 / §19.7 / §19.13 / §20.1 / §20.12 |

---

## 4. 暂缓 / 待定（H）

| id | 事项 | 为什么暂缓 | 恢复条件 | 默认建议 |
| --- | --- | --- | --- | --- |
| H-01 | 跨副本**实时**续看本轮 —— **已完成（2026-09-26）**：平台实现框架 `MessageBus` 接口（`RedisMessageBus` 默认 / `InMemoryMessageBus`），`LiveTurnChannel` 收口读写，`CrossInstanceTurnRelay` 先追总线实时补、追不到再退回「等落盘整段补」 | 原判断「框架自带 Redis 总线、属接线活」不成立（见 DR-34）；本轮已补齐 | — | 验收证据：`RedisMessageBusTest` 3 例（真 Redis）、`CrossInstanceTurnRelayTest` 4 例、`AgentWebMultiInstanceTest` 5 例；全量 17 模块 BUILD SUCCESS、351 例 0 失败 |
| H-02 | **token 级增量是否落 PG** —— **已裁定（2026-09-26）：不做逐字回放**（形态与取证件 DR-54） | 原口径「削峰；对查询价值低」太粗，逐条核过后定为不做：逐字只该在流里走（Codex 与 AgentScope 自家平台都不落库）；审计/评估/复现都不需要逐字，排障在本地 append-only 日志里就有；真做一天几百万行，会把 H-13 的滚动与裁剪推回去 | 出现「必须按逐字粒度作为证据」的需求时（例如监管要求留存模型逐字输出）；届时最小实现：只落答案 delta、不落思考，单独一张表 + 独立保留窗口 | 不做。真正缺的那一格由 **H-15** 补：计时 + 用量 |
| H-03 | P2 工作区 `BaseStore` 后端选型 —— **已完成（2026-09-26）**：选 **PG / 框架自带的 `JdbcStore`**（方言复用会话状态那套：生产 PG、测试 H2 自动识别）。若将来要落到对象存储，再按 DR-39 的取舍另开一项，届时要解决的是「AWS S3 SDK 与框架 `OssBaseStore`（只认阿里云）二选一」 | 依赖 H-04，已随 H-04 一起定 | — | 见 DR-39 |
| H-04 | 工作区共享（`RemoteFilesystemSpec` + `BaseStore`）—— **已完成（2026-09-26）**：`agent-service.workspace-store=jdbc` 时把工作区接到共享 PG 表 `agentscope_store`，多实例任意写任意读；默认 `none`（与改造前行为完全一致，连 `filesystem(...)` 都不调用） | 依赖的分布式状态早已落地，本轮补齐 | — | 验收证据：`PlatformWorkspaceStoreTest` 6 例、`PlatformWorkspaceStorePgTest` 2 例（真 PG，默认跳过）、`SharedWorkspaceTest` 7 例（真 `HarnessAgent` 上「甲写乙读」+ 技能下发钩子接线 2 例）；全量 17 模块 BUILD SUCCESS、**394 例 0 失败**（2 例为需显式开启的真 PG 用例）；真进程证据见 §6「H-04 落地说明」 |
| H-05 | 沙箱与技能脚本执行隔离 —— **已完成（2026-09-26：选型 + 装配都落地，默认关着）**：`agent-service.sandbox.enabled=true` 时容器当主文件系统（命令在容器里跑）、`skills/` 前缀仍走共享库、壳工具放行；默认 `false` 时与改造前逐字一致 | 用户已选定混合版（DR-42），装配形态见 DR-44；选型验证与生产装配都已完成（见 §6「H-05 选型验证」「H-05 落地说明」） | — | 验收证据：`SandboxWiringTest` 10 例 + `SandboxFilesystemRoutingTest` 6 例（假后端，不需要 Docker，CI 常跑）、`SandboxSkillStagingTest` 3 例（技能落盘，不需要 Docker）、`SandboxDockerEndToEndTest` 2 例 + `DockerSandboxSpikeTest` 1 例（真起 `ubuntu:22.04` 容器）、`StartupSandboxCheckTest` 3 例（启动自检三条分支）；怎么打开见 README「沙箱（H-05）」 |
| H-06 | 技能内容下发（按用户隔离）——**两半都已完成（2026-09-26）**：<br>**H-06a 内容下发与发现**：用户可见的技能包 → 写进他的工作区 `skills/` → 框架自带的 `WorkspaceSkillRepository` 发现并注入模型（见 DR-41 与 §6「H-06a 落地说明」）；<br>**H-06b 技能脚本的执行隔离**：打开 `agent-service.sandbox.enabled` 后，每轮把共享库里的 `skills/` 抄到本机、再交给框架投影进容器，技能里的脚本在容器里闭环执行（见 DR-42 后半段 / DR-45 与 §6「H-06b 落地说明」）。**默认档不开沙箱**，所以默认行为仍是「只下发内容、不跑脚本」（DR-43） | 已于 2026-09-26 完成，无需恢复 | — | 验收证据：`SandboxSkillStagingTest` 3 例 + `SandboxWiringTest` 10 例（不碰 Docker，钉住「每轮落盘并交给框架投影」与「关着时什么都不做」）、`SandboxDockerEndToEndTest` 2 例（真起容器，钉住「技能投影进容器后脚本跑得动」和「清沙箱状态这件事非做不可」）；H-06a 证据见 §6「H-06a 落地说明」。全量 17 模块 BUILD SUCCESS、**413 例 0 失败**（H-09 之后全量已是 **419 例 0 失败**） |
| H-07 | 数据范围过滤落地（口径已定，等第一个真实业务接口）——**用户 2026-09-26 明确「先不做」（暂缓）** | 业务接口尚未接入真实数据；口径已由 T1-16 / ADR-37 定稿。用户已明确先不做（暂缓，不是裁定不做） | 用户说做再做；自然触发点是接第一个真实业务接口时 | 在接口服务的 I4 里按登录人**自行推导**可见范围后注入 WHERE（推导不出来 → 403），并把本次实际用到的过滤条件写进 `data_access_audit.scope_snapshot` |
| H-08 | AG-UI（`agentscope-agui-spring-boot-starter`）调研 —— **已完成（2026-09-26）：结论是不替换**，自研 SSE 层继续当 §12.2 的唯一契约源。逐类核过 starter 与 extensions 的源码：它只提供 `POST /agui/run` / `POST /agui/run/{agentId}` 两个入口，没有会话与历史接口，SSE 没有事件 id（拿不到断线续传），自带记忆是进程内 map，默认一断线就打断这一轮 | 已有源码取证结论，不是「不确定」：§12.2 的业务事件它装不下（只能塞 `CUSTOM`），而权威 / 续看 / 互斥 / 入场券 / 审计本来就在平台侧 | — | 见 DR-50；详细对照表见 §6「H-08 调研说明」 |
| H-09 | stop 信号通道由「拉」改「推」 —— **已完成（2026-09-26）**：复用框架 `MessageBus`（H-01 那条 `live-bus`）多开一个停止频道 `TurnStopChannel`，订阅方收到就**当场取消**本地那一轮，不必等下一个流事件 | 原判断「推送要自研一套 Redis Pub/Sub」不成立：总线已经在了，只多一个频道（DR-46） | — | 验收证据：`ChatServiceTest` 14→16 例（**刻意不推任何流事件**也能当场停、推送里不是本实例这一轮时不受影响）、`TurnStopChannelTest` 4 例（含 1 例**真 Redis：A 实例推、B 实例收**）；真进程冒烟 10 项全 PASS；另修掉一个真问题（DR-48：停止原来喊的是框架的无参 `interrupt`，打不到本会话槽位，**界面停了但模型照跑**）并补 `TurnInterruptTest` 2 例；全量 17 模块 BUILD SUCCESS、**421 例 0 失败** |
| H-10 | **框架 `DistributedStore` 剩余子项接入** —— **已完成（2026-09-26，取证结论）**：逐项核过框架源码后确定「除沙箱执行锁外一律不接」 —— ①`agentStateStore` / `baseStore` 平台已有显式接线（框架只在「没设」时才注入）；②`sandboxSnapshotSpec` 框架默认就是 noop；③`messageBus` 由 H-01 覆盖；④`asyncToolRegistry` 与 ⑤`taskRepository` 框架根本没用上（平台也不用后台任务，ADR-31）；⑥`sessionTurnGate` 的接线目标 `HarnessGateway` 不在平台调用链上（平台自己的闸门 T1-14 已在接入层生效）；⑦`teamClient` 平台关闭 | 每一项都能说清「接了会换掉什么、换来的能力平台用不用得上」，所以是**结论**而不是省略（DR-47） | — | 证据是源码核对（`DistributedStore` / `HarnessAgent` / `SandboxManager` / `HarnessGateway`），未改代码；唯一留的口子是执行锁，拆成 H-11 |
| H-11 | **沙箱跨实例执行锁** —— **已完成（2026-09-26）**：沙箱开着时默认上锁（`agent-service.sandbox.distributed-lock`，默认 `true`），锁挂在「按用户」这一层；锁的后端跟着数据库方言走（生产 PG = advisory lock，进程崩了自动松；测试 H2 = 一张锁表）。防的是「同一个用户并发两轮各起一个容器、后写的状态覆盖先写的」这类**静默**失败 | 原判断「接上会替换框架自带的进程内锁」经核实不成立（框架默认是 `noop`，那个类根本不存在），所以从「可选」升级为「该做就做」；代价见 L-22 | — | 验收证据：`PlatformSandboxExecutionGuardTest` 3 例（真锁：同一用户互斥且超时错误带锁名、不同用户不阻塞、抢不到是「等」而非「立刻放行」）、`SandboxIsolationTest` 6 例（新增 1 例用**真中间件**驱动，一条断言钉住「先拿锁 → 建容器 → 整轮跑完才松手」）、`SandboxWiringTest` 11 例（新增 1 例：锁确实挂在两层装配对象上、沙箱关着时被归一化成不带锁）；全量 17 模块 BUILD SUCCESS、**431 例 0 失败** |
| H-13 | **事件日志滚动与保留 + 会话列表改读档案** —— **已完成（2026-09-26）**：①日志文件到多大就滚动（`agent-service.event-log-max-bytes`，默认 256 MB）、「已确认投递且超过保留窗口（`agent-service.event-log-retention`，默认 7 天）」的记录才裁，每实例自己的调度器按分钟收拢（`event-log-maintenance-interval-ms`，默认 1 分钟）；②会话列表改读 `platform_session` 档案（标题 / 条数 / 最后提问时刻在提问那一刻写下），一次对话正文都不读 | 用户 2026-09-26 点名要补：日志不能只增不减；列表不能每个会话各读一遍整段对话 | — | 验收证据：新增 `AppendOnlyEventLogTest` 6 例、`EventLogConfigTest` 5 例、`PlatformSessionStoreTest` 6 例、`AgentWebHistoryTest` 改 1 例为 2 例；全量 17 模块 BUILD SUCCESS、**449 例 0 失败**（2 例真 PG 默认跳过）。形态见 DR-52 / DR-53，代价见 L-23 |
| H-14 | **观测指标目前是空壳**（`EventQueueMetrics.noop()` / `LogFirstEventMetrics`）：日志写了「写失败 / 未确认积压 / 条数」这些指标口子，但没有真实现，等于没有可观测性 | 用户明确先不做（2026-09-26）：先保证行为正确，接不接、接哪套（Micrometer / Prometheus）等真有看板需求时再定 | 要挂监控看板 / 要按指标告警时 | 接口已经留好，接的时候只需提供一个真实现并在装配处替换 `noop`，不用改日志代码 |
| H-15 | **模型调用的用量与耗时落库**（首字延迟 / 总耗时 / token 用量 / 成本）—— 一句话：不要逐字内容，只要性能与成本数字 | 用户 2026-09-26 先把它定为 H-02 的替代（逐字回放不做，但要补计时 + 用量），随后明确「**先不做**」——所以是暂缓、不是裁定不做。场景一直成立：框架每轮都把用量与耗时递到我们手上，我们现在整个丢掉，只是不急着补 | 用户说做再做 | 框架的 `ModelCallEndEvent` 带 `ChatUsage`（input / output / cached / reasoning token 数，还带 `time` 耗时），而平台现在没有任何一处映射 `MODEL_CALL`（全库检索为空）；做法是翻译器加一条映射 → 平台事件带 usage 与耗时 → 走现有管线落事实表（一轮几行），「首字延迟」用该轮第一条 `TEXT_DELTA` 与该轮开始时刻的时差算；不动会话正文与历史回放 |
| H-16 | **「停止生成」前端接线 —— 已完成（2026-09-26）**：发送键在生成时变成停止键；停止这件事在事件流里的唯一出口是 `SseProjector` 的 `REQUEST_STOP → done`，这条收尾事件带 `stopped=true`，前端归约器（`lib/turn.ts`）据此把这一轮定稿成「已停止」。接口响应只兜一种底：流已经断了、收不到那条事件时，用返回的 `stopped` 标记它（且必须「界面也还认为它在跑」才动手） | H-09 只做了服务端（接口 + 跨实例推送），前端原先只有「停止接收」（只断 SSE、模型照跑）；用户 2026-09-26 明确要的是服务端真停，并按 Codex 的样子把按钮放在发送键的位置 | — | 验收证据：`ChatServiceTest` 停止那条用例加断言「done 的 payload 带 `stopped=true`」（该类 16 例仍全绿）；`vue-tsc` + `vite build` 通过（前端没有单测框架，不为这一处新引入一套）；真进程 + 真模型浏览器实测见 §6「H-16 落地说明」 |

---

## 5. 已知限制（需写入文档，避免被当成 bug）

| id | 限制 | 兜底 |
| --- | --- | --- |
| L-01 | 实例被硬杀（OOM / SIGKILL）时本轮不进 `AgentState`（框架只在 `call()` 结束落盘，只有优雅停机才补存） | 已由 T1-08 覆盖：硬挂后历史里那一轮带中断标记，前端提示「请重发」 |
| L-02 | 跨实例「实时」续看**已实现**（H-01 / DR-34）；剩下的降级只有一个：总线读不到内容时（单实例 `memory`、老数据、总线故障或那一段已过期）退回「等本轮结束再整段补」——此时不是逐字出现，且等满窗口仍无结果就要用户再点一次「重新连接」 | `CrossInstanceTurnRelay` 的整段补兜底；权威数据始终在 `AgentState`，重新连接即可拿到 |
| L-03 | 工作区远端模式（P2）下 `write` 是 create-if-absent，文件已存在必须走 `edit` | **已按此落地**：`WorkspaceSkillProvisioner.writeText` 统一处理（读得到就走 `edit` 整段替换、读不到才 `write`）。H-06a 的用例先撞上了它——不处理的话技能索引永远停在第一版 |
| L-04 | 已 `disableCompaction`：上下文超窗会**直接报错**（`Context overflow: no compaction configured`），不会静默截断，也没有自动摘要恢复 | 产品侧可解释降级（提示开新会话 / 清空上下文），并把错误映射成用户可读文案 |
| L-05 | 调用若不带 `sessionId`，框架会把 `sessionId` 兜底成 agent 名字，导致这些调用**塌缩到同一个 slot**（会话串台） | 所有调用必须显式带 `sessionId`；加断言/测试守护 |
| L-06 | ~~每会话建一个 `HarnessAgent` 且从不 `close()`，会持续堆积在框架静态的 `GracefulShutdownManager.stateSavers` 里~~ | **已由 T1-06 消除**（agent 改共享实例，LRU 上限 32）；此行保留仅作历史记录 |
| L-07 | stop 的**兜底**路径仍是「下一个流事件到达时」：信号键是一份状态、不是一条消息。在线实例上现在是即时的（H-09 推送），只有「推送没送到」时才露出这个延迟 —— 目标实例正好没订阅上（滚动重启）、目标实例已死、或 `live-bus=memory` 的单实例部署 | 两条路并存：先写信号键（一定到得了）再推（快），见 DR-46；连兜底都不想等就只能靠共享总线（`live-bus=redis`） |
| L-08 | `object-storage.provider=local` 时技能包只在本机磁盘上：多副本会出现「A 实例上传的技能 B 实例读不到」 | 多副本必须切 `s3`（管理端与 agent 侧指向同一个桶） |
| L-09 | 投影是**有损**的：历史里没有逐字回放、没有过程事件（进度/心跳），工具结果只报大小不报正文 | 逐字回放**已裁定不做**（H-02 / DR-54：Codex 与 AgentScope 自家平台都不落逐字）；需要性能与成本数字看 H-15；过程事件仍可从 append-only 审计日志（事件管线）查 |
| L-10 | `ChatSession` 的 `seq` 是**实例内**的：换实例或实例重启后，客户端带来的游标会失效，服务端退回「这一轮从头补一遍」 | 前端按 `turnId` 去重即可；跨实例的**实时**通路已由 H-01 提供（走 `LiveTurnChannel`，用的是总线自己的条目号游标，与本条这个内存 `seq` 无关） |
| L-11 | 会话句柄有上限（`agent-service.session-cache-size`，默认 512，LRU 淘汰）：被淘汰丢的是**内存里的回放位**，不是历史 | 被淘汰的会话下次请求会从状态库重新读起来（历史完整） |
| L-12 | 跨实例等待窗口（SSE 超时 − 5 秒）内没等到结果：用户看到的是「这一轮还在别的实例上处理中，等待已超时」，需要再点一次「重新连接」 | **只有实时总线也读不到内容时**才会走到这里（H-01 / DR-34 之后这一条已不是主路径）；结果已经落在状态库，重新连接即可整段拿到（DR-30 / T1-09）；窗口大小由 `AgentServiceProperties.streamTimeout` 决定 |
| L-13 | 框架 `BaseStore.search(命名空间, 第一个整数, 第二个整数)` 的**实参含义在 JDBC 实现里是「limit 在前、offset 在后」**（对应 SQL 的 `LIMIT ? OFFSET ?`），且**第一个数为 0 时直接返回空列表**（不查库）；框架自己的 `InMemoryStore` 读法又不一致（`from = min(第二个数, size)`） | 平台侧按 JDBC 口径使用（列一个目录 = `search(dir, 100, 0)`），注释里写明；真要跨实现一致时以 `AbstractFilesystem`（`ls` / `glob`）为准，不要直接依赖 `search` 的参数顺序 |
| L-14 | 远端模式下框架的 `WorkspaceIndex` 仍是**本机文件索引**（工作目录下的 `.index/workspace.db`，一个 H2 文件库），不是共享的；它只影响「列目录」的本地缓存，不影响文件本体（本体在共享表里） | 多实例各自重建自己的索引（`rebuildFromDisk`），功能正确；副作用是 Windows 上用例收尾删不掉这个文件库——测试工作目录一律放 `target/` 下，不要用 JUnit 的 `@TempDir` |
| L-15 | H-05 混合形态要面对的框架语义（**实测，不是推断**）：①路由按「前缀 + 目录边界」匹配，命中后交给对面后端的是**剥掉前缀**的路径（`/skills/a.md` → `/a.md`；`ls` 会把前缀补回来，`read` / `write` / `edit` 不会），前缀写成 `/` 则**什么都匹配不到**；②「投影」是**打 tar 上传进容器 + 内容哈希去重**，来源是**本机目录**（默认 `AGENTS.md` / `skills` / `subagents` / `knowledge` / `.skills-cache`），且只在沙箱 `start()` 时应用一次 | 装配与技能下发的路径口径按这个来写；要换投影源时才需要 `workspaceProjectionEnabled(false)` + 自带 `WorkspaceSpec`（见 DR-42） |
| L-16 | 框架**没有「不用 Docker 的沙箱」**：2.0.3 里 `Sandbox` 接口只有 Docker 一个实现（`sandbox/impl/docker/*`）；扩展包里的 `JdbcSandboxExecutionGuard` / `RedisSandboxExecutionGuard` 是「防两台并发抢用同一隔离槽」的**锁**，不是执行后端。不用 Docker 也能跑命令的那条路是 `LocalFilesystemSpec`（产出 `OverlayFilesystem`，上层 `LocalFilesystemWithShell` 在**宿主机上真起进程**：Windows `cmd.exe /c`、其它 `sh -c`），它给的只有「路径白名单」（`LocalFsMode.ROOTED` + `PathPolicy`）+ 超时/输出上限 + 默认不继承父进程环境变量 | 所以「脚本执行」只有两支：**容器**（真隔离，能和共享工作区共存）或**本机**（无隔离，而且一开路由就丢 shell —— 见同表上方 `CompositeFilesystem` 那条）。要「无 Docker 又要有执行面」，等于接受「用户脚本以 agent 服务的身份跑在宿主机上」，必须明确告知使用方；当前默认档是**不执行脚本**（DR-43） |
| L-17 | 框架**默认**的文件系统 `LocalFilesystemWithShell` **也实现** `AbstractSandboxFilesystem`（因为它能在宿主机上跑命令），所以 `filesystem instanceof AbstractSandboxFilesystem` **不能**当"这台实例开没开沙箱"的判据 | 判「是不是沙箱模式」要看两处，且它们都是明面上的：文件系统是不是 `RoutedSandboxFilesystem`（开了沙箱 + 有共享库）、工具面里有没有 `execute`（壳工具）。用例按这两条写（`SandboxWiringTest` 第 1 例的注释里写了原因） |
| L-18 | 沙箱文件系统的**路径口径分两层**：①命令执行的工作目录 = 工作区根（框架 `docker exec -w <root>`），`write` / `uploadFiles` 也把**相对路径**解析到工作区根，而写 `/workspace` **之外**的绝对路径会被拒（"Upload path is outside the sandbox workspace"）；②`read` / `write` / `delete` 是把路径**原样**拼进 shell 命令（不像上传会做解析），全靠上面那条工作目录兜住 | 容器侧的路径一律用**工作区相对路径**（`scratch/tmp.txt`）。共享前缀（`skills/`）不受影响：它由路由交给共享库，路径口径还是老那套（带不带前导斜杠都行，`SandboxWiringTest` / `SandboxDockerEndToEndTest` 各钉了一条） |
| L-19 | **Windows 宿主**上 `docker exec <容器> sh -c <命令>` 会**吃掉双引号的分组**：`sh -c 'echo "a b"'` 到容器里变成 `echo "a` + 参数 `b"`（实测输出 `a`；`test -e "a b"` 退出码 1），而 `echo a\ b` 这种转义写法正常。PowerShell 直接调也一样，所以**不是 Java 的锅**，是宿主侧 `docker` CLI 的参数解析；Linux / WSL 宿主没这个毛病 | 影响的正是框架的 `BaseSandboxFilesystem.write`——它的检查命令里带 `mkdir -p "$(dirname <路径>)"`，所以在 Windows 上**往容器里写文件会失败**；其余命令只要不含「双引号包住带空格的内容」都照常。本机（Windows）跑真容器用例时避开这一点（`SandboxDockerEndToEndTest` 改成让容器自己建文件，注释里写了原因），真正要往容器里写文件的场景在 Linux 上验 |
| L-20 | 沙箱容器是**一轮一个**（框架 `SandboxManager#release` 会 `stop` + `rm --force`），而框架把「工作区投影做过一次」按**内容哈希**记在沙箱状态里；下一轮 `acquire` 会 `resume` 上一轮的 state 并把这个哈希带进来，哈希没变就**跳过投影**——于是变成「第一轮容器里跑得动技能脚本、第二轮开始脚本文件不在」（2026-09-26 真容器实测） | 平台每轮开始前清掉该 (agent, 用户) 的**沙箱状态**（`AgentscopeRuntimeAdapter#clearStaleSandboxState`），让框架按全新沙箱处理（起容器 + 应用工作区 + 重新投影）。真容器用例 `SandboxDockerEndToEndTest` 把这个假设钉住了（不清状态时第二轮容器里确实没有脚本、清掉后第三轮又有）；框架哪天改成「容器重建就重投影」，这一步清理就可以撤。清的是沙箱状态（框架的 `_sandbox_state` 槽位），**不是会话状态**——聊了什么在另一把键上，照样跨实例、跨轮次 |
| L-21 | **已验证（2026-09-26，含并发面）**：隔离「按用户分槽」由 `SandboxIsolationTest` 6 例钉住 —— 平台造出来的 `SandboxContext`（含 H-06b 那条投影路径）确实带 `USER`、不同用户落在不同槽位、同一个用户换会话仍是同一个槽位、甲存的沙箱状态乙读不到（把隔离粒度改成 `AGENT`，这几条会红，已实测）。**并发面在 H-11 之后也有覆盖**：没有锁时同一用户并发两轮会各起一个沙箱、后写覆盖先写（用例把这个行为当事实记着）；接上锁之后那个窗口被真实抢锁用例挡住（`PlatformSandboxExecutionGuardTest`），并由一条「用真中间件驱动」的用例证明框架确实在**建容器之前**拿锁、在**整轮结束之后**松手 | 剩下没验的只有「真容器 + 真并发」的端到端表现；当前默认档不开沙箱、没有暴露面，要对外开沙箱时补一条真容器并发用例即可 |
| L-22 | **执行锁的代价（H-11 之后，只在 `agent-service.sandbox.enabled=true` 时存在）**：①同一个用户的两轮并发不再是「各起一个容器」，而是**后到的等锁** —— 等不到（默认 5 分钟）那一轮直接失败并报错；②生产 PG 走 advisory lock，锁挂在一条**专用连接**上、整轮都占着，所以「同时在跑脚本的用户数」不能超过连接池大小，超了会先把池子占满、再让别的请求排队（包括普通对话） | 嫌等得久就把 `agent-service.sandbox.lock-timeout` 调小（调到几秒＝抢不到立刻报错，不挂着）；连接池按「同时跑脚本的用户数」配；单实例试跑或确认不需要互斥时 `agent-service.sandbox.distributed-lock=false` |
| L-23 | 事件日志的三条自愈动作都只作用在「**已确认投递**」的那部分上：消费端长期不通（队列 / Redis 挂了）时，未确认的记录会一直堆在活动文件里——**它不裁剪、也不滚动**，所以这段时间磁盘占用会一直涨，保留窗口管不住它 | 这是刻意的取舍：宁可占磁盘也不丢事件（ADR-28）。消费端恢复后第一次维护就会把积压送走、文件随即收拢；真遇到长时间不通，该处理的是消费端本身（日志文件只是它的上游，不是原因） |
| L-24 | **停止过的轮次在历史回放里看不到「已停止生成」这个标记**：历史是从框架的 `AgentState` 现算出来的（§19.4 / `SessionTranscript`），而框架状态里记的是「模型看到了什么」。中断时框架自己会往状态里追加一句 assistant 消息 `I noticed that you have interrupted me. What can I do for you?`（`ReActAgent#handleInterrupt`，2026-09-26 核 2.0.3 源码），所以那一轮重新打开时看到的是「答案 + 一句英文套话」，而不是界面上那个标记 | 实时那一眼有明确交代（气泡上「已停止生成」）；历史里那句英文是框架的中断收尾语，不是我们漏渲染。刻意不补第二份真相（用户口径：会话记录就用框架自己的能力）。真要补成中文标记，得让历史那条链去读平台审计事实表里的 `REQUEST_STOP`，属于「两套真相对齐」的活，先如实记在这里 |


---

## 6. 落地说明（2026-09-26，P0 已做部分）

P0 的 T0-01 … T0-10 全部已完成，实现时有下面这些**判断**需要留在台账里，避免以后被当成遗漏：

- **T0-02 / DR-15 的关系**：浏览器是**直接**访问 agent-service 的（`/v1/agent/*`），并不是绕经网关，
  所以 agent-service 必须自己认 JWT。这次做的是把「验 JWT + 查账号状态」这段样板收进
  `JwtUserContextResolver` + `UserContextInterceptor`，控制器里不再出现。
  DR-15 里「agent-service 不再自己解 JWT」只适用于**网关调用**它的那条路径，浏览器这条路径做不到。
- **T0-03 的记账时机**：审计从「控制器里写」改成「切面在方法返回后写」，**条数与内容完全不变**，
  只是「放行」这条的落库时刻从「转发之前」挪到了「转发之后」。
- **T0-03 保留的那一行校验**：`apiCall` 开头那 3 行入参契约检查（三元组 / `requestId`）**没有**搬进切面。
  理由：搬走之后控制器就不再自证「拿到的请求是完整的」，可读性反而下降；审计（真正与业务无关的那部分）
  已经搬走了。若仍希望搬走，是一处很小的后续改动。
- **T0-06 的停止**：**2026-09-26 起是两条路**——带 TTL 的信号键（兜底：最迟下一次推进事件时读到）+ 框架总线上的停止推送（H-09 / DR-46，持有那一轮的实例当场 `cancel`）；
  同实例上的停止本来就是即时的。剩下还会露出「等下一个事件」的只有推送没送到的情况，见 L-07。
- **T0-09 的默认值**：入场券 / 限流 / 停止信号（agent-service）、nonce（gateway、interface-doctor）、
  限速（gateway）默认全部改成共享存储（`redis`）；想跑单机就把对应开关显式写成 `memory`。
  两套实现并存、对外语义一致（`EntryTicketStore`、`TurnLimiter`、`NonceStore`、`UserRateLimiter` 各一对），
  所以「切回单机」是改配置而不是改代码。
- **T0-09 里的例外是熔断**：`CircuitBreakerRegistry` 仍按实例计数，这是刻意的，理由见 DR-23。
- **T0-09 的测试纪律**：要证明「跨副本同一口径」的用例跑在**真实 Redis** 上（新增
  `RedisUserRateLimiterTest`、`RedisNonceStoreTest`，加上既有的 `RedisEntryTicketStoreTest` /
  `RedisTurnLimiterTest`），本机没有 Redis 时 `assumeTrue` 跳过——**跳过不等于通过**。
  反过来，不依赖共享存储的集成测试（`AgentApiTest`、网关两个 `@SpringBootTest`、接口服务三个）
  显式把开关钉回 `memory`，免得单测结果被本机 Redis 的有无牵着走。
- **T0-07 的自启没有做成「Windows 服务」**：这台机器上跑脚本的账号**不是管理员**，装不了服务，
  所以用「登录时触发」的计划任务（普通用户就能建）。任务动作是 `minio-run.ps1`，它**在前台守着**
  `minio.exe`，于是「任务在跑」=「MinIO 在跑」；又因为进程由任务计划服务拉起、不继承控制台句柄，
  `install-minio.ps1 | Select-Object …` 这类写法不会再把管道挂住（早先的写法会挂，已改）。
  代价：任务里记的是**仓库脚本路径**，仓库挪位置后要重跑一次 `install-minio.ps1`。
- **T0-10 补了一个原生建库脚本**：容器里 `deploy/migrations` 由 PG 镜像的 `docker-entrypoint-initdb.d`
  自动执行，本机原生 PG 没有这个环节，所以加了 `deploy/local-windows/apply-migrations.ps1`
  （按文件名排序 apply，用自建的 `schema_migration_local` 记账表保证可重入）。
- **T0-08 的接缝选在「存储后端」而不是「技能包存储」**：`SkillPackageStore` 还是原来那个接口，
  只是换了一个实现（`ObjectStorageSkillPackageStore`），它把字节交给 `ObjectStorage` 端口；
  本机装配成文件系统、多副本装配成 S3 兼容对象存储。于是「换对象存储」这件事只在
  `object-storage.provider` 一个开关上，业务代码一行不动；而技能包自己的语义（内容寻址、
  同名不覆盖、键名 `sha256-<hash>.zip`）仍然由技能包这一层负责，没有被稀释到通用存储层里。
- **T0-08 的 S3 实现有三个刻意的取舍**：① **不自动建桶**——桶是运维资产，应用发现桶不存在要明确报错，
  而不是偷偷建一个可能放错地方的桶；② `putIfAbsent` **先查后写**——键是内容哈希，并发写同一个键时
  字节完全一样，所以不需要分布式锁，最坏情况是同一份内容写了两遍、值不变；
  ③ `list` **分页到底**——S3 单次最多返回 1000 个，用分页器一次取全，免得以后出现「列不全」的怪现象。
- **T0-08 的测试分两层**：文件系统实现用纯单测（临时目录）；S3 实现是**对着本机真实 MinIO 跑**的集成测试
  （`assumeTrue` 只在机器上没有 MinIO 时跳过，而**跳过不等于通过**）。理由：分页、404 语义、path-style
  全是协议行为，假实现验证不了。
- **T0-08 的单个限制**：`object-storage.provider=local` 时技能包只在本机磁盘上，**多副本部署必须切 `s3`**，
  否则会出现「在 A 实例上传的技能，B 实例读不到」。这条已写进 `application.yml` 的注释与 README。
- **T1-01 落在一个新模块 `agent-service/state`（artifactId `agent-state-store`）里**，没有塞进 `common-persistence`：
  把框架状态落到 PG 要同时依赖「AgentScope core」与「spring-jdbc」，放进 common 会让网关 / 管理端 / 接口服务
  全部被迫背上 AgentScope；放进 `agent-service/web` 又会让「状态怎么存」和「HTTP 怎么接」挤在一个模块里。
- **T1-01 的 CAS 口径见 DR-27**：事务内 `SELECT ... FOR UPDATE` + 版本比对 + 版本 +1，PG 与 H2 跑同一套 SQL。
  代价是每次写开一个短事务（命中一行、走一次主键），换来的是「测试库与生产库语义完全一致」。
- **T1-01 为什么必须覆写框架的默认方法**：`AgentStateStore` 把 `getVersioned` / `saveIfVersion` 做成了默认方法，
  而默认实现直接调 `save(...)` 并返回 `-1`——也就是**不看版本、覆盖写**。只实现 `save` / `get` 的话，
  代码看起来支持乐观并发，实际仍然是「最后写的赢」。这是整个 P1 里最容易漏掉的坑。
- **T1-01 顺手把「平台轮次快照」的落点想清楚了**（`PlatformTurnState` + `PlatformTurnStore`，键 `platform_turn`）：
  它只做一件事——把平台快照**翻译**成框架认识的状态文档，存取交给状态库。T1-04 要做的就是把 `RuntimeStatePort`
  三件套换成这条通道：寻址、CAS、会话清理全部复用，不再各写一套。
- **T1-02 / T1-03 / T1-04 必须同批上线**：`agent_state` 表一旦换成「(user_id, session_id, state_key, version, payload)」口径，
  还在用旧列（runtime_id / snapshot_created_at / jsonb）的 `PgRuntimeStatePort` 会立刻查不动。
  这一批的验收就是「两实例下 B / C 成立」，拆开做只会留下一个坏掉的中间态。

### T1-02 / T1-03 / T1-04 落地说明（2026-09-26）

- **项目没上线，所以直接换表口径，不写数据搬迁**：`V16__agent_state_framework_store.sql` 开头就是 `DROP TABLE IF EXISTS agent_state;`
  再按新口径建表（`user_id / session_id / state_key / version / payload`，主键三列）。
  上线之后再动这张表就必须反过来写真正的迁移脚本——这句话已经写在脚本注释里，免得以后有人照抄这一段。
- **为什么把「落本地文件」的开关直接删掉**（而不是像限流那样留一个 `memory` 能切回来）：
  状态落本地磁盘意味着「会话被钉死在这台机器上」，多副本下 B / C 会静默失效（用户看不出报错，只是发现 agent 忘了上文）。
  留这个开关等于留一个「看起来能用、其实是错的」的默认降级路。单机开发不需要它——本机也有 PG。
- **`AgentServiceProperties` 里 `state-dir` / `state-store` 换成了 `workspace-dir`**：
  会话状态（连同 HITL 快照）一律走 PG；本地目录只剩下运行时自己产生的痕迹（框架的工作目录）。
  名字从 `state-dir` 改成 `workspace-dir` 就是为了让「这不是状态存放地」一眼可见。`AGENT_STATE_DIR` → `AGENT_WORKSPACE_DIR`。
- **`ChatServiceTest` 用的是框架自带的 `InMemoryAgentStateStore`**：这个用例只关心「挂起 → 恢复」的流程，
  不需要真数据库；真库语义由 `JdbcAgentStateStoreTest`（H2 `MODE=PostgreSQL`）覆盖。测试里也因此不需要再写一个平台自己的内存状态端口。
- **`AgentWebH2Config` 里那个 H2 专用状态端口替身也删了**：新 store 用的是一套不依赖方言的 SQL，
  H2 与 PG 跑的是同一份语句，不再需要「测试库换一个实现」。
- **验收证据**：`mvn -B install -DskipTests` 通过；`mvn -B test` 17 个模块全部 BUILD SUCCESS（含 `agent-state-store` 15 用例、`AgentApiTest` 走真 H2 表断言 `platform_turn` 行）。

### T1-05 落地说明（2026-09-26）

- **删掉的是「我们自己记的那一份」**：`SessionJournal`（append-only 的 SSE 事件流水，落 `sse-<instanceId>.jsonl`）、
  `FileSessionJournal` / `InMemorySessionJournal` / `JournalRecord` / `SessionHistory` 全部删除（含各自的测试）。
  历史上限、跨实例拼接、文件损坏修复这些复杂度随之消失。
- **历史改由 `SessionTranscript` 从 `AgentState` 投影**：它是纯函数（状态进、事件出），不落任何库。
  投影规则是「可执行的约定」：每轮**第一个 `TextBlock` 就是用户原话**（所以 `AgentscopeRuntimeAdapter.contentFor` 刻意把
  用户原话与系统提醒拆成**两个块**，提醒单独成块，避免两者粘在一起让规则失效）；
  `ThinkingBlock` → `think` 步骤、助手 `TextBlock` → `token`、`ToolUse`/`ToolResult` → `tool` 卡、每轮末尾补 `done`。
- **`SessionSummary` 从「存档」改成「现算」**：标题取首条提问、提问数数 User 消息、最后活跃时间取最新消息时间戳。
  只有两个字段算不出来，才落 `platform_session`（`PlatformSessionState` + `PlatformSessionStore`）：**建档时刻**与**归档标记**。
  建档用 `saveIfVersion(..., 0L)`——只在「这一格还是空的」时写下，保证并发首次请求不会把建档时刻改来改去。
- **`ChatSession` 的内存回放位改用「按轮次过滤」**：`streamTurn(afterSeq, turnId)` 只回这一轮里 `seq > afterSeq` 的事件。
  为什么按轮次：游标是实例内的，跨实例/重启后客户端带来的 `afterSeq` 可能来自上一轮，
  「只按 seq 过滤」会出现「新一轮被上一轮的游标吞掉」；按轮次过滤最多是「这一轮从头补一遍」（见 L-10）。
- **新增 `AgentStateContractTest`（护栏用例）**：用假模型跑**真实** `HarnessAgent`，断言 `agent_state` 这个存储键里
  确实躺着 USER（且第一块文本 = 用户原话）与 ASSISTANT 消息。历史现在完全寄托在框架的文档结构上，
  框架升级一旦改键名或改结构，表现是「历史全空」这种**静默故障**；有了这条用例，它会变红灯（DR-29）。
- **验收证据**：`mvn -B install` 全量 17 个模块 BUILD SUCCESS、0 失败；
  `agent-service-web` 75 例、`runtime-agentscope` 18 例、`agent-state-store` 15 例全绿。

---

### H-01 落地说明：跨副本实时续看（2026-09-26，已完成）

- **先说被推翻的那条判断**：台账原先写着「框架 2.0.3 自带 `MessageBus` 的 Redis 实现，H-01 只是接线」（DR-32）。
  源码核查后不成立：框架的 `MessageBus` 只有**唯一**一个实现 `WorkspaceMessageBus`，它把消息写进「工作区文件系统」，
  服务的是 agent **内部**的收件箱 / 异步工具 / 子代理 / 团队——而平台出于 DR-12 一律关闭了这些，
  所以「发布端接上、接收端就有了」这条现成通路并不存在。H-01 的真实形态是**按框架接口自己写实现 + 平台接入层收发**。
- **为什么仍然照着框架的接口写**：框架 `MessageBus` 上那组 `sessionPublishEvent / sessionReadEvents`
  语义与这里要的东西完全对得上（「某个会话时间线上、条目号单调递增、可带游标追读的一串事件」）。
  实现框架接口而不是自造一套，最直接的好处是**将来框架补上 Redis 实现时可以原样替换**，
  平台代码只认 `MessageBus` 这个类型；另外也让「平台正在用的东西」一直留在框架的词汇表里。
- **为什么是「追日志」而不是「订阅推送」**：这个场景的用户状态是「我已经错过了一段」——断线重连本来就要补历史。
  订阅是易失的（断线那一瞬没人在听，消息就没了），而追日志是自愈的（游标停在哪就从哪继续），
  还能顺带回答「这段时间一共产生了什么」。条目号做成定宽字符串（`BusEntryId`，`%020d`），
  比较大小就是比字符串——**只有一套比较规则**，游标不会因为格式不同而错位。
- **为什么实时读到过内容就不再整段补**：实时与「等落盘」两条路都会产出轮次内容，两个都发用户就会看到同一段回答出现两遍。
  所以这里刻意**一个轮询循环、一个游标**：只有「实时一条都没读到」时才去读状态投影、整段补。
  这不是靠事后去重，而是结构上就不可能重复（见 `CrossInstanceTurnRelay` 的类注释）。
- **为什么实时只算「加速」不算「权威」**：总线上的条目带 TTL（默认 2 小时），是**临时数据**；
  写失败只记 warn、不打断这一轮（`LiveTurnChannel.publishTurnEvent`）。真正的权威永远是 PG 里的 `AgentState`：
  总线丢了，用户退回「等落盘整段补」，一分信息都不少。
  这也决定了中继路径（`watchFromOtherInstance`）**不往总线写**——它转发的不是本实例产出的内容，写进去会让同一段在日志里出现两份。
- **为什么单实例也要能跑**：总线做成 `live-bus: redis|memory` 一个开关，`InMemoryMessageBus` 让这套代码在没有 Redis 的环境里
  跑同一份逻辑；四张 `SpringBootTest` 显式钉回 `memory`，单测结果不被本机 Redis 的有无牵着走。
- **验收证据**：`RedisMessageBusTest` 3 例（真 Redis：游标不重不漏 / 超上限裁老条目 / 跨实例订阅推送）、
  `CrossInstanceTurnRelayTest` 4 例（新增「别的实例正在产出的内容会实时补上来、跑完时不重复整段补」）、
  `AgentWebMultiInstanceTest` 5 例；全量 `mvn -B clean install` 17 模块 BUILD SUCCESS、351 例 0 失败。
- **真进程这一层的证据**（`two-instance-smoke.ps1`，2026-09-26 复跑为 **9 项全 PASS**）：脚本现在会真的跑一轮，
  再直接到共享 Redis 上找这一轮的总线条目。**为什么不看接口**——走接口时「从总线实时读到」与「等跑完整段补」
  给用户的画面是一样的，只有直接看键才能证明「发布」这条路真的走了、而不是碰巧走了兜底。实测：
  两台实例启动日志都打出「跨副本实时总线：Redis」；日志键是 Redis list、TTL 7193 s ≈ 2 h（与 `Duration.ofHours(2)` 一致）；
  另有 `agent-service:bus:seq` 这个**不带 TTL** 的条目号计数器——它必须比条目活得久，否则重启后新条目的号会倒回去，游标就乱了。

---

### H-04 落地说明：工作区共享（2026-09-26，已完成）

这一条的原问题是「多实例部署时，每个实例都要看到同一个工作区（技能、脚本、被写下的文件）」。答案不是自己写一套文件同步，
而是**用框架已有的两块能力拼起来**：`HarnessAgent.Builder.filesystem(RemoteFilesystemSpec)`（工作区的远端落点）+ `JdbcStore`（落点用 PG）。

- **平台只做三件事**：① 迁移脚本 `V17__workspace_store.sql` 建表（DDL 照抄框架 `PostgresDialect#storeCreateTableDdls`，一个字符不改）；
  ② `PlatformWorkspaceStore` 把 `JdbcStore` 接上平台 DataSource，并在装配时确认表在（缺表**直接启动失败**，不等到第一次用到才炸）；
  ③ 装配开关 `agent-service.workspace-store`（`none` 默认 / `jdbc`），配成别的值直接启动失败。
- **没有自己写一行存储逻辑，也没有引入新 SDK**：框架的 `OssBaseStore` 只认阿里云 OSS，而平台的对象存储栈是 AWS SDK v2 S3——
  硬接要么多一个 SDK，要么把两套语义混在一起，所以这一期选 PG（见 DR-39）；将来要落到对象存储，另开一项。
- **框架自己有一道闸**（本轮实测踩到）：装 `RemoteFilesystemSpec` 时，如果会话状态还是本进程实现（`JsonFileAgentStateStore` / `InMemoryAgentStateStore`），
  `build()` **直接抛错**拒绝装配。所以「工作区共享」和「会话状态共享」必须同时成立（DR-40）——平台本来两个都落 PG，这条闸对我们是白送的护栏，已用一条用例钉住。
- **隔离粒度**：`IsolationScope.USER`（框架默认值），一个用户一个命名空间。这正好是 H-06「我的技能你看不见、你的技能我看不见」要的粒度；
  取 `SESSION` 会把同一个人的技能按会话复制很多份，取 `GLOBAL` 会让所有人串在一起。
- **默认关着**：工作区平面按 DR-12 仍未打开（技能、子代理、工作区上下文都关着），所以 `none` 时行为与改造前**逐字一致**（连 `filesystem(...)` 都不调用）。
  真正用上它是 H-06（技能下发）。
- **实测到的两处框架脾气**（已记入 §5）：`BaseStore.search` 的实参在 JDBC 实现里是「limit 在前、offset 在后」且第一个数为 0 就返回空（L-13）；
  远端模式下 `WorkspaceIndex` 仍会开一个**本机** H2 文件库 `.index/workspace.db`（L-14，只影响列目录的本地缓存）。
- **验收证据**：
  `PlatformWorkspaceStoreTest` 6 例（H2：跨实例读写、CAS 抢写只有一个赢、按目录列文件不串目录、删掉就读不到、缺表装配失败）、
  `PlatformWorkspaceStorePgTest` 2 例（**真 PG**：建表用的就是 `V17` 的原文，两台实例共用一份工作区、抢写只有一个赢；默认跳过，要显式开 `-Dtest.pg.url`）、
  `SharedWorkspaceTest` 7 例（**真 `HarnessAgent`** 上「甲实例写、乙实例读」，以及换会话仍可见、换用户不可见、关掉开关时不是远端文件系统、缺分布式状态库时被框架拒绝，
  以及 H-06a 补的两条接线用例：技能下发钩子拿得到这一轮的清单、没装下发时技能读取工具不在工具面上）；
  全量 `mvn -B clean install` 17 模块 BUILD SUCCESS、**394 例 0 失败**（另有 2 例真 PG 用例在显式开启时才跑）。
- **真进程这一层的证据**（`two-instance-smoke.ps1`，2026-09-26 复跑为 **10 项全 PASS**）：两台实例都以 `AGENT_WORKSPACE_STORE=jdbc` 启动，
  启动日志里各打出「工作区存储：共享库（表 agentscope_store）」，实例正常起来并跑完原有 9 项。**为什么不看接口**——工作区平面现在是关的，
  没有任何业务接口会去读写它；能观察到的只有装配日志，而表不存在时实例会直接启动失败（这一条同时钉住了「迁移跑过」）。

### H-06a 落地说明：技能内容下发与发现（2026-09-26，已完成）

**一句话**：网关告诉 agent「这个人现在能看见哪些技能」，agent 拿这份清单去**管理端的表**问「每个技能算数的是哪一版」，
再从**对象存储**把那一版取回来、解包写进**这个用户自己的工作区**；之后「有哪些技能、每个技能怎么用」全交给框架。

- **分工（DR-41）**：**管理端是唯一写者**——发布时把「哪个版本算数」写进 `sys_skill_version.status = 'published'`；
  agent 侧对这张表**只读**，拿 `storage_key` 去对象存储取字节。**不开新接口、不新增服务间鉴权、不在库里存第二份技能内容。**
- **为什么 agent 直接读管理端的表**：两个服务本来就共用同一个库、同一个对象存储；专门开一条接口等于给这份判断加一层中转，
  还得再造一套服务间鉴权。直接读表，每个副本都自给自足——管理端此刻挂着，也不影响已经在跑的技能。
- **「算数的是哪一版」的口径**：该技能下**最新的一条 `published`**，按主键倒序——**不是**按版本号字符串排序
  （`10.0.0` 排到 `9.0.0` 前面是字符串比较的经典坑）。所以「上传了但还没发布」的那一版不会顶掉线上那一版。
- **发现与使用是框架自带的**：文件写进工作区 `skills/<技能编码>/SKILL.md` 之后，框架自己的 `WorkspaceSkillRepository`
  会把它们列进系统提示词，并提供「按需把正文读出来」的工具（`load_skill_through_path`）。平台**只写文件**，不碰清单、不拼提示词。
  **装了下发才打开技能发现**；没装时连这个能力一起关掉（`disableDynamicSkills` / `disableDefaultWorkspaceSkills`），
  连那个技能读取工具都会被工具面白名单移掉——不给模型留一个「永远读不到东西、还绕开 `ToolInvoker`」的口子。
- **每轮都做，但每轮都很便宜**：可见性来自授权、随时可能被收走，所以每轮都同步一次。代价被拆成两步：
  「哪一版算数」是一次很小的索引查询（每轮都可以问），「取包 + 解包」只在**版本真的变了**时做。
  什么都没变时，一轮的代价 = 一次工作区读 + 一次批量 SQL，不进对象存储。
- **索引放在工作区里**（`skills/.platform-skills.json`）：它描述的是「这个用户的工作区里现在有什么」，与工作区同生共死；
  放数据库就得再管一张表、再管一处一致性。多副本任意一台写完，别的实例读到的就是同一份（工作区本身是共享的）。
- **撤销要删干净**：上一轮可见、这一轮不可见的技能，连同它写下的文件一起删（不删的话模型还看得见它）；
  **换版本**时，上一版有、这一版没有的文件（作者删掉了某个脚本）也要一并清掉。
- **下发失败不影响对话**：技能是增强不是主链路。取不到包、写不进工作区，一律记日志、按工作区里现有的那份继续跑。
- **本轮修掉的一个真问题（框架事实，已并进 L-03）**：框架的 `write` 是**「不覆盖」**语义——目标已存在时直接拒绝。
  而技能下发恰恰是「把上一版换成这一版」，于是第一轮之后每次升版本、每次改索引都会撞上它（**用例先撞上了它**：
  索引永远停在第一版，版本升不动）。现在统一走 `WorkspaceSkillProvisioner.writeText`：**读得到就 `edit` 整段替换、读不到才 `write`**。
- **已知边界**：① 这一层只负责把技能脚本**放进**工作区，**不执行**它（执行隔离是 H-06b，已于 2026-09-26 落地：沙箱打开后运行时会把这些技能投影进容器，脚本在容器里跑；默认档仍是只下发、不执行）；
  ② 单文件超过 `PackageReader` 的 256KB 上限时不进包内文件表，因此不会下发（现在没有这种技能；真有了要连着那个上限一起改）。
- **真进程回归**（H-06a 落地后复跑，2026-09-26）：`two-instance-smoke.ps1` **10 项全 PASS**——工作区共享装配 / B 看到 A 建的会话 /
  跨实例换券 / B 读历史 / 拿 B 的券到 A 接流且 661ms 收尾不空挂 / A 跑完一轮 / 这一轮进了共享总线 / 停止幂等。
  也就是说本轮改动（网关技能清单 → `AgentRunRequest` → 运行时钩子、装配里多接一个对象存储、`application.yml` 新项）**没有破坏启动与多副本行为**。
  **技能下发本身冒烟脚本碰不到**：它跑的是 `noop` 运行时（本机没有模型服务），而下发挂在 AgentScope 运行时的每一轮开始处；
  这一层由下面的 `SkillProvisioningIntegrationTest` 兜着。
- **验收证据**：`WorkspaceSkillProvisionerTest` 9 例（首次下发写文件与索引、版本没变不重写也不取包、撤销删干净、
  作者自带 `SKILL.md` 就用他的、取不到新包保留旧版、没发布过不下发、换版覆盖写并清旧文件、空清单什么都不做、来源出错不带崩这一轮）、
  `RegistrySkillPackageSourceTest` 8 例（取最新一条已发布、草稿回落、从没发布过、只回问到的、空清单不查库、取包解开、键不存在、存储里那份坏了）、
  `SharedWorkspaceTest` 2 例（下发钩子拿到清单、没装下发时不放行技能读取工具）、
  `ChatServiceTest` 2 例（开关关着不问网关、开着时清单进到运行时请求里）、
  `SkillProvisioningIntegrationTest` 2 例（**端到端**：真表 + 部署时那个对象存储 Bean + 共享工作区表——
  写入一个已发布的技能后，**另一个存储对象 / 文件系统**读得到它；顺带钉住「打开 `workspace-skills=workspace` 应用照样起得来」）；
  全量 `mvn -B clean install` 17 模块 BUILD SUCCESS、**394 例 0 失败**（2 例真 PG 用例默认跳过；Docker 可用时另跑 1 例真容器用例）。

### H-05 落地说明：沙箱装配（2026-09-26，已完成 · 默认关着）

**一句话**：把「命令在哪儿跑」变成一个开关。`agent-service.sandbox.enabled=true` 时，容器是 agent 的主文件系统——模型跑脚本、写临时文件都在容器里；
但**必须跨实例看到同一份**的那些路径（现在只有 `skills/`）照旧读写共享库。默认 `false`，此时与改造前**逐字一致**（没有容器、没有壳工具）。

- **装配形状（DR-44）**：`.filesystem(sandbox.toFilesystemSpec())`（`DockerFilesystemSpec`：镜像、工作区根、内存/CPU 上限、网络都由配置给，`IsolationScope.USER` 显式写死）
  + `.filesystemRoute("skills/", 共享库文件系统)`。**路由表不自己拼**：`SandboxSharedRoutes` 直接问框架的 `RemoteFilesystemSpec` 要每个前缀对应的文件系统，
  于是沙箱模式与远端模式在共享库里的**键位逐字相同**——切开关不会「技能突然全没了」。代价是它依赖「远端那个装配结果确实是 `CompositeFilesystem`」，
  所以框架哪天换了装配，这里会**在构造 agent 时当场抛错**，而不是悄悄退化成「整块工作区都在容器里」。
- **壳工具只在开着的时候放行**：框架的注册条件是「`!disableShellTool` **且** 文件系统是 `AbstractSandboxFilesystem`」，所以开关关着时它根本不存在；
  白名单那一步（`allowedBuiltinTool`）再加一道：**没开沙箱就算框架哪天把它塞进来也会被移掉**。这是开关打开后**唯一**故意偏离 ADR-31 基线的地方，因此必须显式打开。
- **关掉框架默认投影**（`workspaceProjectionEnabled(false)`）+ 自带一个只有根目录的 `WorkspaceSpec`：框架的默认做法是把**这台机器的工作目录**打 tar 塞进容器，
  多副本下每台机器内容不同，等于让「技能从哪来」变得不确定。容器里要放什么应该由平台明确给——这一期先**什么都不投影**（容器里只有临时文件），
  H-06b 之后改成**每轮按用户给投影源**（见下节「H-06b 落地说明」），路径口径不变。
- **技能正文的权威仍然是共享库**：模型靠框架的技能读取工具按需读，读的是共享库那份（路由）。**这一期容器里的脚本还看不到技能**——
  「把技能投影进容器、让脚本闭环执行」是 H-06b，已于 2026-09-26 补齐（见下节「H-06b 落地说明」），不是这一期漏掉的。
- **启动期自检**（`StartupSandboxCheck`）：开着沙箱时探一次 `docker version`，拿不到就**拒绝启动**。为什么不「退回本机跑」：
  那样最坏的情况是运维以为脚本在容器里被隔离了，实际它以 agent 服务的身份跑在宿主机上——**「以为隔离了、其实没隔离」比启动失败危险得多**。
  关着的时候**连探测都不做**（不给不相关的部署加任何前置条件），这一条有专门的用例守着。
- **文档位置**：怎么打开、有哪些开关，写在 README 的「沙箱（H-05）」；本机怎么试一次写在 `deploy/local-windows/README.md`。
- **验收证据**：
  `SandboxWiringTest` 7 例（不需要 Docker：默认档行为不变、开着的装配形状、工具面只多壳工具一个、设置翻译、键位一致、技能下发在沙箱模式下照旧落共享库）、
  `SandboxFilesystemRoutingTest` 6 例（假后端钉路由语义）、
  `SandboxDockerEndToEndTest` 1 例（**真容器**：命令真的在容器里跑、技能写进共享库并被「另一台实例」读到、容器里看不到共享库的技能、容器自己写的临时文件通过文件系统读得到）、
  `DockerSandboxSpikeTest` 1 例（真容器：投影与执行落点）、
  `StartupSandboxCheckTest` 3 例（**不真调 docker**：关着连探测都不做、开着探测不到就拒绝启动、探测得到就通过）。
  全量 `mvn -B clean install` 17 模块 BUILD SUCCESS、**405 例 0 失败**（其中 2 例真 PG 用例默认跳过；真容器用例在本机没 Docker 时跳过）。本轮新增 5 例：`SandboxWiringTest` 补 1（6→7）、`SandboxDockerEndToEndTest` 1、`StartupSandboxCheckTest` 3（H-06b 那轮全量复核已是 **413 例 0 失败**，见下节）；台账里 H-04 / H-06a 那两行的 394 是当轮抄下来的数，本轮按各模块 surefire 报告重新统计。
- **本机实测踩到、已记进 §5 的三条框架/环境事实**：①框架**默认**的文件系统 `LocalFilesystemWithShell` 也实现 `AbstractSandboxFilesystem`，
  不能拿它当「开没开沙箱」的判据（L-17）；②沙箱文件系统的路径口径分两层，容器侧一律用工作区相对路径（L-18）；
  ③**Windows 宿主**上 `docker exec` 会吃掉双引号分组，于是框架的「往容器里写文件」在 Windows 上会失败（L-19，Linux 正常）。

### H-06b 落地说明：技能投影进容器（2026-09-26，已完成 · 沙箱默认关着）

**一句话**：技能有两条链要同时成立——**路由让模型读得到**（`skills/` 走共享库，H-05 已有），**投影让容器跑得动**（把技能打 tar 灌进容器，本轮补上）。
只做前者的话，框架提示词里写给模型的 `/workspace/skills/<技能>` 在容器里根本不存在，模型会照着一个空路径去跑脚本。

- **投影源必须由平台给，而且必须是「宿主本地目录」**（DR-45）：框架的 `WorkspaceProjectionApplier` 用 `Files.readAllBytes` 读源、只收 `isRegularFile`，
  它不认共享存储。所以照框架给的用法走：**先在本地把内容摆好**，容器启动时框架再打 tar 灌进去。
  `SandboxSkillStaging` 就是「摆好」这一步——每台实例每轮从共享库把该用户的 `skills/` 抄到自己的 `workspace-dir/sandbox-skills/<用户>/skills/`。
  抄是**同步**而不是重建：内容一样就不写、共享库里已经删掉的本地也删掉（同一个用户的并发调用撞上门时不会看到「删了一半」的中间状态）。
- **每轮现造 `SandboxContext` 放进本轮上下文**：agent 是所有用户共用的，投影源却是**每个用户、每一轮**都不同（技能授权随时会被收走），
  所以只有「每次调用」的地方放得下它；框架的 `ensureSessionDefaults` 会拿上下文里这个 `SandboxContext` 覆盖 agent 上的默认值。
- **每轮先清沙箱状态**（`clearStaleSandboxState`）：容器一轮一个，而框架把「投影做过一次」按内容哈希记在沙箱状态里，
  不清就会「第一轮跑得动、第二轮文件没了」——真容器实测，见 §5 的 L-20。清的是**沙箱状态**，不是会话状态，聊天记录不受影响。
- **投影的落点必须与模型看到的路径一致**：框架提示词给模型的是 `<工作区根>/skills/<技能>`（工作区根默认 `/workspace`），
  所以投影源目录下必须有一份 `skills/`——`SandboxSkillStaging` 的返回值就正好是这个结构（用例钉住了「投影目录名与共享前缀都是 `skills/`」）。
- **技能正文不需要投影**：模型读技能走的是路由后的文件系统（读的永远是共享库那份，H-06a）；投影只为「容器里要有脚本文件可跑」。
- **打开方式**：光开沙箱还不够，技能下发（`agent-service.workspace-skills=workspace`）也得开着——没有技能就没有可投影的东西。
  怎么开写在 README「沙箱（H-05）」与 `deploy/local-windows/README.md`。
- **验收证据**：
  `SandboxSkillStagingTest` 3 例（**不碰 Docker**：按用户抄到本机、共享库删了本地也删、身份里的特殊字符不互相撞也跳不出暂存根、没技能时不造空目录）、
  `SandboxWiringTest` 10 例（**不碰 Docker**：开关关着时行为逐字不变、开着时每轮落盘并把「用这个目录当投影源」的 `SandboxContext` 交给框架、投影目录名与共享前缀一致、工具面只多壳工具一个）、
  `SandboxDockerEndToEndTest` 2 例（**真起 `ubuntu:22.04` 容器**：技能投影进容器后 `sh /workspace/skills/demo/scripts/run.sh` 真的打出预期输出；
  不清沙箱状态时第二轮容器里确实没有脚本、清掉状态后第三轮又有——这条把 L-20 那个假设钉死了）。
  本轮新增 6 例（`SandboxSkillStagingTest` 3、`SandboxWiringTest` +3、`SandboxDockerEndToEndTest` +1，另有 `WorkspaceSkillProvisionerTest` +1）；
  全量 `mvn -B clean install` 17 模块 BUILD SUCCESS、**413 例 0 失败**（H-09 之后全量已是 **419 例 0 失败**；2 例真 PG 默认跳过；真容器用例在本机没 Docker 时跳过）。
- **顺带调整的一句话**：技能下发时提示模型的那句「脚本能不能跑」现在**跟着沙箱开关走**（开着：「在执行环境（容器）的同一路径下可用，可以直接执行」；
  关着：原来的「当前运行时不执行脚本，需要执行时如实说明」）。技能内容本身逐字不变，`WorkspaceSkillProvisionerTest` 有专门一例钉住这一点。


---

### H-09 落地说明：stop 从「拉」改「推」（2026-09-26，已完成）

**一句话**：停止现在走**两条路** —— 带 TTL 的信号键负责「**一定到得了**」，框架总线上的一个推送口负责「**快**」。
订阅方收到推送就**当场取消**本地那一轮，不用再等「下一个流事件」把停止捎进来。

- **为什么是加一个推送口，而不是换掉信号键**：信号键是一份**状态**（谁在什么时候要停），任何实例在任何时刻读一次都能看到，天然是兜底；
  但它没有「通知」这个动作，所以只能等到某台实例下一次推进事件时顺手读到。推送恰好补上另一半：它不保证送到，但送到了就是**立刻**。
  两者是「一条路负责可靠、一条路负责快」，不是新旧替代关系（DR-46）。
- **底座直接用框架的 `MessageBus`（H-01 那条 `live-bus`）**：`TurnStopChannel` 只是在这条总线上多开一个频道 `agent-service:turn-stop`，
  没有第二套连接、第二个订阅容器、第二处停机逻辑。`live-bus=memory` 时推送就在进程内转，单实例照样跑同一份代码（这时它等价于原来的行为）。
  这也是用户口径「能用框架的别自己造」的直接落地：**没有自己写 Redis Pub/Sub**。
- **发布顺序是「先写信号键、再推一条」**：反过来的话，推送已经送出去了、信号键还没落地，万一写键失败，兜底就没了。
  而按现在这个顺序，推送失败（比如总线临时不可用）只会记一条告警，不影响这次停止请求——因为兜底的键已经写成功了。
- **收到推送要过「三问」才动手**（`ChatService#onPushedStop`）：①本实例手里有这个会话的活口吗；②它现在还 `running()` 吗；③推来的 `turnId` 就是本实例正在跑的那一轮吗。
  三问全中才取消。为什么必须问：这是**广播**，同一个用户的停止信号会送到每一台实例，而只有一台实例真正在跑那一轮——
  「不是我的」是**常态**而不是异常，所以对不上时静默忽略、不记日志（记了就是刷屏噪音）。
  问 `turnId` 还有一层意义：同一个用户可能在别的会话、别的轮次上跑着，只有三元组（用户、会话、轮次）全对上才是「停我这一轮」。
- **顺带修掉一个「看起来成功」的失败（DR-48）**：停止原来调的是框架的**无参** `interrupt()`，它打的是默认槽位
  `(null, defaultSessionId)`，而我们的每一轮跑在 `(userId, sessionId)` 槽位上——结果界面停了（平台自己标记结束 + 放坑位 + 记停止事件），
  **模型却把整轮答案一个词不少地产完**，工具也照跑。这是实测出来的：写一个每 100ms 吐一个词的慢模型，
  取消之后数一数还冒出几个词，无参版本 15 个全在、带身份版本 45ms 内就断。现在 `cancel` 一律带身份调用，
  并留了 `TurnInterruptTest` 两条用例（停止后不再产出、停一个会话不牵连共用同一个 agent 的别人）——
  把实现改回无参版本它们会红，所以这是护栏而不是装饰。
  顺带做了一次同类审计：打开 javac 的 `-Xlint:deprecation` 把 agent-service 四个模块全量编译一遍，**0 条废弃用法**——
  说明没有第二处「框架给了带身份的版本、我们却调了默认槽位」的调用。
- **推送线程上只做轻量的事**：收到推送的那条线程是总线自己的消费线程，所以只做「找到本地会话 → 打一个取消标记」，
  真正的收尾（通知等待方、清理挂起状态）仍然走原来那条路。
- **怎么打开**：没有开关。推送跟着 `agent-service.live-bus` 走 —— `redis`（多副本默认）就是跨实例推送，`memory` 就是单实例。
  停止接口本身没变（还是 `POST .../stop`），用户侧无感。
- **验收证据**：
  `ChatServiceTest` 14→16 例（关键是**刻意不推任何流事件**：只有推送真的生效了，`cancelReasons` 才会出现 `user_stop`；另一例钉住「推送说的不是本实例这一轮时不受影响」）、
  `TurnStopChannelTest` 4 例（内存总线上推→订阅者收到、字段不全被忽略、订阅者抛异常不带崩推送方、**真 Redis 上 A 实例推 B 实例收**）、
  `AgentWebMultiInstanceTest` 里 A/B 两台模拟实例共用同一个推送频道（装配不漏）。
  真进程：`two-instance-smoke.ps1` 复跑 **10 项全 PASS**（A/B/C 与工作区共享逐项不退化、停止仍幂等 200）；
  脚本跑的是默认档，它证明「加了推送没有弄坏别的」，推送这条链本身由上面那条真 Redis 用例钉住。
  另有 `TurnInterruptTest` 2 例（真跑一个慢模型，钉住「停止真的打断这一轮」与「不牵连别人的会话」，见上面那条 DR-48）。
  全量 `mvn -B clean install` 17 模块 BUILD SUCCESS、**421 例 0 失败**（2 例真 PG 默认跳过；真容器用例在本机没 Docker 时跳过；本轮新增 8 例）。随后 L-21 验证那批用例把全量推到 **426 例 0 失败**（见下一节）。

**H-10 一句话结论**（取证结论，见 DR-47）：框架 `DistributedStore` 的那些子项**除沙箱执行锁外一律不接** ——
平台已有的接线（状态库、工作区文件系统）比聚合接线更明确；框架自己没用上的（`asyncToolRegistry` / `taskRepository`）、
平台调用链压根不走的（`HarnessGateway` 那条闸门）、平台已关闭的（团队），接了只会多一层说不清的耦合。
唯一留口子的是执行锁（H-11），因为它会**替换**框架自带的进程内实现，是「值得做但要单独评估」的一类。

---

### H-16 落地说明：「停止生成」按钮（2026-09-26，已完成）

**用户口径**：「在生成时发送变为停止图标就行，就和 codex 一样」，而且要的是**服务端真停**，不是「我不看了」。

**两个「停止」不是一回事**（界面上一度让人困惑，现在两边分工写在按钮与代码注释里）：

| 动作 | 按下去发生什么 | 入口 |
| --- | --- | --- |
| **停止接收** | 只断开这条 SSE：模型照跑、答案照产，随时点「重新连接」接着看 | 流状态行旁边的按钮（`stopReceiving`） |
| **停止生成** | **服务端真的取消这一轮**：会话还在、已产出的内容保留，停完可以马上问下一轮 | 生成时**发送键那个位置**变成的方形停止键（`stopGenerating`） |

**关键设计：结论由服务端在事件里说清楚，不让前端猜。**
停止在事件流里只有一个出口 —— `SseProjector` 把 `REQUEST_STOP` 投影成 `done` 时带上 `stopped=true`，
前端 `lib/turn.ts` 的归约器看到这一格就把这一轮定稿成「已停止」。
为什么不能让两边各判一次：停止接口的 HTTP 响应和这条事件**谁先到是不确定的**（实测这次是事件先到 —— 按原来「只看接口响应」的写法，界面就什么都不显示）。
事件带标记之后，两种先后顺序都能推出同一个结论：事件先到 → 归约器定稿；响应先到 → 响应那句先标上，事件到了直接返回（归约器看到已经是「已停止」就不再翻）。
接口的响应只兜一种底：**流已经断了**（用户点过「停止接收」、或连接失败）收不到那条事件时，用它来标记这一轮。

**另一个「不去猜」的原因**：点停止和这一轮跑完可能撞在一起（服务端记一条 `REQUEST_STOP` 的同时，模型恰好也自己收尾了）。
所以兜底那条要求「界面这边也还认为它在跑」才动手 —— 否则会把一份**已经收好的答案**翻成「已停止」，那是骗人的。

**验收证据**
- 后端：`ChatServiceTest` 停止那条用例加了一条断言（`done` 的 payload 带 `stopped=true`），该类 16 例全绿。
- 前端：`vue-tsc` + `vite build` 通过（前端没有单测框架，不为这一处新引入一套）。
- 全量：`mvn -B install` 17 模块 BUILD SUCCESS、**449 例 0 失败**（5 例跳过：3 例真容器、2 例真 PG，与改造前同一批）。
- 真进程 + 真模型（DeepSeek）浏览器实测：发送后约 2 秒停止键出现、发送键让位；点停止后服务端**当场**取消（事件日志 89 条 `TEXT_DELTA` 之后一条 `REQUEST_STOP`，此后不再产出内容），气泡显示「已停止生成：这一轮被取消，上面已经产出的内容保留。」，发送键立刻回来（说明轮次坑位已放开，停完能马上接着问）。
- **历史回放的一处如实说明**：停止过的轮次重新打开时看不到「已停止生成」这个标记 —— 历史是框架状态的投影；框架在中断时自己会往状态里追加一句英文收尾语 `I noticed that you have interrupted me. What can I do for you?`，所以历史里看到的是「答案 + 那句英文」，已记成 **L-24**。

---

### H-08 调研说明：AG-UI 能不能用（2026-09-26 · 结论：不用；只做能力验证）

**一句话**：AG-UI 是**前端事件协议** —— 它管的是「事件怎么画到前端」，不管「状态谁说了算、断了怎么接上、多副本怎么协调」。
这两件事平台都已经做完（权威 = 框架 `AgentStateStore`，跨副本续看 = H-01），换过去是净损失，所以结论是**不替换**（DR-50）。

**它是什么**：`io.agentscope:agentscope-agui-spring-boot-starter:2.0.3`（内容在 `agentscope-extensions-agui:2.0.3`）——
把 AgentScope 的 typed event 翻成 AG-UI 的标准事件（`RUN_STARTED` / `TEXT_MESSAGE_*` / `TOOL_CALL_*` / `STATE_*` / `REASONING_*`），
再通过 SSE 推给**支持 AG-UI 的前端客户端**（CopilotKit 这类）。它自己不提供页面，仓库里也没有示例应用。

**为什么不用**（三条硬事实，都是读源码得到的，不是推测）：

- **业务语义装不下**：§12.2 那张表里的 `intent` / `skill_start` / `skill_step` / `sandbox_job` / `artifact` 与 `clarify`（缺哪些槽），
  以及 `final` 的溯源标注 / figures / expressions，在 AG-UI 里**没有对应的语义位**，只能塞进它的 `CUSTOM` 事件。
  等于「自研事件外面再套一层壳」：多一套协议、多一套依赖，换来的能力平台已经有了。
- **权威面它一概不管**：状态持久化权威、跨副本实时续看、跨副本轮次互斥、一次性入场券、限流、审计、HITL 工具确认的回执关联，
  以及 `GET /sessions`、`/sessions/{id}/turns`、`/sessions/{id}/stop` —— 一个都没有。
  starter 暴露的入口**只有两个 POST**（`/agui/run`、`/agui/run/{agentId}`）。
  它自带的「服务端记忆」（`serverSideMemory=true`）是**进程内** `ConcurrentHashMap`（`ThreadSessionManager` 按「用户 + 线程」分槽）——
  多副本各存各的，**恰好就是我们要解决的那个问题**，不能拿它当答案。
- **断线续传接不上**：它的 SSE 只发 `data: {...}`，**没有 `event:` 名、也没有 `id:` 字段**
  （MVC 那条是 `SseEmitter.event().data(json, APPLICATION_JSON)`，WebFlux 那条是 `ServerSentEvent.builder().data(...)`，两处都没有 `.id(...)`），
  所以客户端拿不到 `Last-Event-ID`，没法回来补断掉的那一段 —— 而 §12.2 明确要求断线续传（平台现在由 H-01 的总线游标满足）。
  更反向的是它默认 `interruptOnDisconnect=true`：订阅端一断开就把这一轮**打断**，和「断了换一台实例接着看」正好相反。

**两条不构成否决、但记下的事**：

- **依赖面**：starter 的 pom 把 `spring-boot-starter-web` / `webflux` / `configuration-processor` 都声明成 **4.0.3**，项目主线是 Boot **3.4.1**。
  项目现在只用 agentscope 的 `core` / `harness` / `extensions`，**从没用过它的 spring-boot starter**，所以 AG-UI 会是第一个 Spring Boot 集成依赖。
  根 pom 的 `spring-boot-dependencies` BOM 会把版本压回 3.4.1，能不能在 Spring 6.2 上正常跑**没有实测**（jar 只声明 Java 17）——
  这是一条**未验证的兼容风险**，但结论不依赖它：上面的能力缺口已经足够决定。
- **词汇表本身是对得上的**：`STEP_STARTED` / `TOOL_CALL_*` / `REASONING_*` / `TEXT_MESSAGE_CHUNK` 与 §12.2 的 `step` / `tool` / 思考流 / `token` 基本能一一对应；
  它的 HITL 语义也齐（`permission_confirm` / `input_required` / `tool_call` 三种原因，加下一次请求带回 `resume{interruptId, status}`）。
  所以将来前端真要接 AG-UI 生态客户端时，路是通的 —— 但那是**新增一个 `/agui` 通道**，不是替换现在这个（那时再单开一项评估）。

**§12.2 业务事件 vs AG-UI 事件**（逐条对照，将来真要接时可以直接用）：

| §12.2 事件 | AG-UI 能不能直接承载 | 说明 |
| --- | --- | --- |
| `token` | 能（`TEXT_MESSAGE_CONTENT` / `TEXT_MESSAGE_CHUNK`） | 一对一 |
| `tool` | 能（`TOOL_CALL_START` / `ARGS` / `END` / `RESULT`） | 一对一 |
| `done` | 能（`RUN_FINISHED`） | 一对一 |
| 思考流（§20.6） | 能（`REASONING_*`，要开 `enableReasoning`） | 一对一 |
| `step` | 部分（`STEP_STARTED` / `STEP_FINISHED`） | 有步骤概念，但 `seq` / `content` / `toolName` 要自己塞 |
| `error` | 部分（`RUN_ERROR`） | 有错误事件，但 `code` 与降级引导要自己塞 |
| `permission` / `confirm` / `clarify` | 部分（interrupt + `resume{interruptId, status}`） | 机制在，但 `action` / `summary` / `missingSlots` / `candidates` 要自己塞 |
| `session` | 装不下 | 没有「会话建立」事件；入口只是 POST body 里的 `threadId` / `runId` |
| `intent` | 装不下 | 没有语义位，只能走 `CUSTOM` |
| `skill_start` / `skill_step` | 装不下 | 同上（§12.2 用事件 `source` 区分父子 agent，AG-UI 没有这个口径） |
| `sandbox_job` | 装不下 | 同上 |
| `artifact` | 装不下 | 同上 |
| `final`（溯源标注 / figures / expressions） | 装不下 | 同上 |

**怎么打开**：不用打开 —— 本项**只验证不替换**，没有加任何依赖、没有改任何代码。真要接时按上面这张表评估「新增 `/agui` 通道」这件事。

---

### H-11 落地说明：沙箱跨实例执行锁（2026-09-26，已完成 · 跟着沙箱开关默认打开）

**一句话**：沙箱开着时，同一个用户**同时只放一轮**进容器；拿不到锁的那一轮等一会儿，等不到就报错。
底座直接用框架扩展里的现成实现（`JdbcSandboxExecutionGuard`），平台只负责把它挂上去。

**先说一个被核实掉的旧结论**：台账原来写「接执行锁会**替换**框架自带的进程内 `DockerExecutionGuard`」，
所以属于「值得做但要单独评估」。核实之后的真相是：**框架默认就是不锁** —— `SandboxExecutionGuard` 的默认值是 `noop`,
2.0.3 里根本没有任何 `DockerExecutionGuard`（harness 的 `sandbox/impl/docker/` 里只有 Sandbox / Client / State / Options / Spec）。
所以这件事是**纯新增**、不替换任何东西，风险那一栏直接消失。

**它防的是什么**：沙箱槽位按用户分（`IsolationScope.USER`），而「这个槽位上现在是哪个容器」记在槽位里。
同一个用户的两轮几乎同时开始时，两边都还看不到对方的记录 → 各起一个容器 → **后写的状态覆盖先写的**。
聊天记录不受影响（它在另一把键上），坏掉的是容器那点状态，而且**不报任何错**。这类静默失败优先修。

**怎么接的**（三个选择，都写在 DR-51 里）：

- **挂在 `DockerFilesystemSpec.executionGuard(...)` 上**，不走 `DistributedStore` 那条聚合接线：
  平台的状态库 / 工作区库都已显式接好（T1-13 / H-04），为一个锁换整条接线不划算；
  而框架本来就支持只挂锁（`HarnessAgent` 只在我们没给锁时才去问 `distributedStore`）。
- **用 JDBC 版而不是 Redis 版**：这一层本来就有 `DataSource`，Redis 版要 `redis.clients.jedis.UnifiedJedis`——
  为一个锁引 Jedis 客户端不划算。而且「按数据库挑实现」框架已经做了：**方言自己就是锁策略**，
  于是同一份平台代码在生产走 `pg_try_advisory_lock`、在测试走表锁。
- **锁挂在「用户」这一层**：与沙箱槽位同粒度。挂错粒度只有两种结局——挂粗了误伤别人（一个人跑脚本全院排队），
  挂细了挡不住（等于没挂）。

**顺带纠正一条关于「谁松锁」的实测事实**：松锁的不是 `SandboxManager.release`（它只做 stop + 快照），
而是框架的 `SandboxLifecycleMiddleware` —— 顺序是 `release(...)` 之后再 `lease.close()`。
本轮写用例时先自己手写了 `acquire` / `release`，结果录到的是「只拿不还」（`[enter]`），
换成真中间件驱动之后才看到完整的 `[enter → create → exit]`。所以那条用例现在是**用真中间件**跑的，
断言也只有一条：`[enter:USER:alice, create:c-1, exit:USER:alice]`。

**怎么打开 / 关掉**：跟着沙箱走。`agent-service.sandbox.enabled=true` 时锁默认就开；
不想要（例如单实例试跑）用 `agent-service.sandbox.distributed-lock=false`；
等锁上限 `agent-service.sandbox.lock-timeout`（默认 `5m`）。沙箱关着时锁被归一化成 `null`，不会白占连接。

**代价（已写进 L-22，别当成没做）**：①后到的那一轮要等，等不到就失败；
②PG 这条路整轮占一条数据库连接，所以「同时在跑脚本的用户数」不能超过连接池大小。

**验收证据**：

- `PlatformSandboxExecutionGuardTest` 3 例（在 `agent-state-store` 模块，用 H2 模拟**两台实例**去抢同一把锁）：
  同一用户互斥且超时错误里带锁名（排障时要知道是谁占着）、不同用户互不阻塞（挂粗了就变全局锁）、
  抢不到的人会**等**（不是立刻放行，松手后马上拿到）；
- `SandboxIsolationTest` 新增 1 例：用真中间件驱动，钉住「先拿锁 → 建容器 → 整轮跑完松手」；
- `SandboxWiringTest` 新增 1 例：锁确实挂在**两层**装配对象上（agent 级 + 每轮带投影那个），
  且沙箱关着时即使传了锁也会被归一化成不带锁；
- 全量 `mvn -B clean install` 17 模块 BUILD SUCCESS、**431 例 0 失败**（2 例真 PG 默认跳过；真容器 3 例真跑过）。


---

### L-21 验证说明：沙箱「一个用户一个槽位」到底有没有生效（2026-09-26 · 已验证）

**为什么要单独验**：平台的沙箱装配里，隔离粒度是**显式写死** `IsolationScope.USER` 的（DR-44：不敢靠框架兜底值，兜底值不会回填到 `SandboxContext` 上）。
但写死不等于生效 —— 框架真正用来算槽位的是 `SandboxIsolationKey.resolve(上下文里的 scope, 本轮上下文, agentId)`。
只要「我们造出来的上下文没带 USER」或「算键时读的不是它」，隔离就**静默失效**：两个用户的脚本会跑进同一个容器，而且不会报任何错。

**验的方式**（`SandboxIsolationTest` 6 例，**不需要 Docker**，CI 常跑）：不靠读代码下结论，而是把框架的 `SandboxManager` 真类接上
（只把「起容器」换成记账的假客户端）、状态库用真的 `SessionSandboxStateStore`，喂给它平台生产路径造出来的 `SandboxContext`：

- **上下文带 USER**：`toFilesystemSpec()` 与 `toProjectingFilesystemSpec()`（H-06b 那条投影路径）都要带 —— 缺了就没有多租户隔离；
- **不同用户不同槽位、同一个用户换会话仍是同一个槽位**：前者是多租户隔离的最小前提，后者是「一个用户一个容器」的定义；
- **状态按用户分开存放**：甲存的沙箱状态乙读不到（读到了就等于两个人共用一个容器）；
- **真跑一遍 acquire 的顺序**：alice 新建 → bob 也新建（不能 resume 到 alice 那台）→ alice 再进来 resume 回自己那台；
- **没有执行锁时会怎样**（顺手记下的事实）：同一个用户并发两轮会各起一个沙箱，两条状态写同一个槽位、**后写的覆盖先写的** ——
  这正是 H-11 要防的场景。当前默认档不开沙箱，所以还没有真实暴露面。

**这条护栏是真的**：把 `SandboxSettings` 里的隔离粒度临时改成 `AGENT`（也就是「所有人共用一个容器」），4 条用例立刻变红（已实测）；
第 5 条不依赖隔离粒度，所以照常绿 —— 这也说明它测的确实是隔离这件事本身。

**还剩什么没验**：真容器 + 真并发的表现（同一个用户两台实例同时跑脚本）。那属于 H-11 的评估范围，留到对外开沙箱时做。


---
## 7. 未完成速查（每次开工先看这里）

做完 P0（T0-01 … T0-10）、整个 P1（T1-01 … T1-16）、P2 的第一项（H-03 / H-04 工作区共享）、**H-06（技能内容下发与发现 + 技能投影进容器）与 H-05（沙箱装配，默认关着）**，加上 **H-09（停止改推送）、H-16（「停止生成」前端接线）、H-10（`DistributedStore` 取证结论）、H-08（AG-UI 调研，结论是不替换）与 H-11（沙箱跨实例执行锁）、H-13（事件日志滚动与保留 + 会话列表改读档案）** 之后，**还没做的只剩「暂缓 / 待定」项**（H 系列）与如实承认的**已知限制**（L 系列，见 §5）——不是遗漏：

| id | 事项 | 为什么还没做 | 需要谁 / 什么才能开始 |
| --- | --- | --- | --- |
| H-15 | 模型调用的用量与耗时落库（首字延迟 / 总耗时 / token 用量 / 成本） | 用户 2026-09-26 明确「先不做」（暂缓）；它仍是 H-02 的替代——不要逐字内容，只要性能与成本数字，所以「逐字落库」那件事不用再提 | 用户说做再做 |
| H-05 | 沙箱：**已完成（2026-09-26），但默认档不启用**（DR-43 / DR-44 / DR-45） | 选型验证 + 生产装配 + 技能投影进容器（H-06b）都已完成（见 §6）；它是「可选升级档」而不是当前主线——默认关着，打开 `agent-service.sandbox.enabled` 才生效（打开后要求本机 Docker 可用，否则拒绝启动） | 需要多租户脚本隔离时 |
| H-07 | 数据范围过滤落地（口径已定：ADR-37） | 用户 2026-09-26 明确「先不做」（暂缓）；另外业务接口也还没接真实数据，过滤条件得跟业务表一起写 | 用户说做再做 |
| H-14 | 观测指标是空壳（`EventQueueMetrics.noop()` / `LogFirstEventMetrics`） | 用户明确先不做（2026-09-26）：先保证行为正确，接哪套指标（Micrometer / Prometheus）等真有看板需求时再定 | 要挂监控看板 / 要按指标告警时 |

**已知限制**见 §5（L-01 … L-24）；这些是「如实承认」而不是待修 bug，除非对应 H 项被启动。

**已裁定「不做」的**（不用再提）：H-02「把逐字增量落库做逐字回放」——理由见 DR-54（Codex 与 AgentScope 自家平台都不落逐字；要的是计时与用量，已改成 H-15）；H-12「停止之后从断点继续」——理由见 DR-49（框架没有「恢复同一轮」的接口；自己做的语义是「看到半句重写后半段」，不如重新提问直白）。

**上面这张表只列「还没做的」**。已经做完、但你可能还想回头看的是：H-01（跨副本实时续看）、H-03 / H-04（工作区共享）、**H-06（技能内容下发与发现 + 技能投影进容器）**、**H-05（沙箱装配，默认关着）**、**H-09（停止改推送）**、**H-16（「停止生成」前端接线）**、**H-11（沙箱跨实例执行锁）**、**H-13（事件日志滚动与保留 + 会话列表改读档案）**，各自的落地说明在本节下面（H-09 与 H-10 的落地说明/取证结论都在 §6「H-09 落地说明」一节里，**H-08 的调研结论与逐条对照表在 §6「H-08 调研说明」**，DR-50）。

### H-05 选型验证：混合形态（2026-09-26 · 验证与装配都已完成）

**用户口径**：沙箱走**混合版**——「工作区仍然共享在 PG，只在要执行脚本的时候把技能投影进容器里跑」。

**怎么验的**：没靠读文档拍脑袋，也没先写生产代码。先把 `agentscope-harness` 的 **sources jar** 从 Maven Central 取下来逐类读过，再写两个用例把关键语义钉死：

| 用例 | 钉住什么 | 结果 |
| --- | --- | --- |
| `SandboxFilesystemRoutingTest`（6 例；假沙箱后端，不需要 Docker，CI 里常跑） | 跑命令永远落沙箱；命中前缀的读写落路由后端；没命中前缀的留在沙箱；前缀被剥掉之后对面看到什么路径；前缀写 `/` 行不行 | 6 例全过 |
| `DockerSandboxSpikeTest`（1 例；真起 `ubuntu:22.04` 容器；本机没 Docker 时跳过） | 投影源能不能被我们自己的 `WorkspaceSpec` 接管；命令是不是真在容器里跑（`/.dockerenv`）；投影进去的脚本容器里读不读得到 | 1 例全过，跑完不留容器 |
| `SandboxWiringTest`（当时 7 例；假后端，不需要 Docker；H-06b 之后是 10 例） | **装配形状**：默认不开沙箱时没有沙箱也没有壳工具、开了之后容器当主且 `skills/` 走共享库、壳工具只多它一个（`web_search` / `web_fetch` 照旧被移掉）、没共享库时如实的退化成纯容器文件系统、设置翻译（镜像/资源上限/显式 USER 隔离/关掉默认投影）、共享库键位与远端模式逐字一致、**技能下发的动作（写正文→传脚本→换版 edit→撤销 delete）在沙箱模式下照旧落共享库且一个字节都不进容器** | 当时 7 例全过 |

**结论三条**（都会落到代码注释里）：

1. **形状成立**：「沙箱当主 + PG 走路由」是框架的正经用法（`RoutedSandboxFilesystem` 的类注释原文就是「保留 primary 沙箱后端上的 shell 执行」），装配点是 `Builder.filesystem(SandboxFilesystemSpec)` + `Builder.filesystemRoute(前缀, 文件系统)`。
2. **前缀要逐个枚举**：写成 `/` 一把梭**什么都匹配不到**（框架内部会把两侧的开头斜杠都剥掉）。想共享给多实例的那些路径（技能、约定、知识库…）得一条条列出来。
3. **投影源要自己接管**：框架默认从「本机工作目录」扫 `AGENTS.md` / `skills` / `subagents` / `knowledge` / `.skills-cache` 打成 tar 塞进容器；我们的技能正文在 PG 里，所以要么先落到本机一个暂存目录，要么 `workspaceProjectionEnabled(false)` + 自带 `WorkspaceSpec`（已验证可行）。

**当时还没做的**（2026-09-26 当天已补齐，见 H-05 落地说明）：生产装配（配置开关 + `AgentServiceConfig` 接线 + 默认关闭的行为守护 + 技能下发路径按路由口径调整）= H-05 本体。H-06b（把技能投影进容器、让技能脚本在容器里闭环执行）也已于 2026-09-26 补齐，见下节「H-06b 落地说明」。

### 脚本执行：不用 Docker 的那条路，查过了（2026-09-26 · 结论：不成立）

问的是「AgentScope 自己有没有不需要 Docker 的执行方式，能不能不用之前那套」。**答案：框架有本机执行，但没有「无 Docker 的沙箱」；而且本机执行跟我们的共享工作区凑不到一起。** 下面三条是把 2.0.3 源码（sources jar）逐类读过得到的，不是推断：

| 问题 | 查到的答案 | 依据（都在框架源码里） |
| --- | --- | --- |
| 框架有几种"执行面"？ | 三个文件系统模式**互斥**，只能选一个：`RemoteFilesystemSpec`（共享存储，**没有** shell）、`LocalFilesystemSpec`（本机真起进程）、`SandboxFilesystemSpec`（Docker 容器） | `HarnessAgent.Builder.build()` 里 `specCount > 1` 直接抛 `IllegalStateException` |
| 有「不用 Docker 的沙箱」吗？ | 没有。`Sandbox` 接口在 2.0.3 里只有 Docker 一个实现；扩展包里的 `Jdbc/Redis/PostgresSandboxExecutionGuard` 是**互斥锁**（防两台抢同一个隔离槽），不是执行后端 | `agent/sandbox/impl/docker/*`、`AbstractBaseSandbox` |
| 本机模式能当隔离用吗？ | 不能。它在**宿主机上以 agent 服务自己的身份**跑进程（Windows `cmd.exe /c`、其它 `sh -c`），给的只有路径白名单（`LocalFsMode.ROOTED` + `PathPolicy`）、超时/输出上限、默认不继承父进程环境变量 | `LocalFilesystemWithShell.execute()`、`LocalFilesystemSpec` 类注释原文：适合"agent is trusted to run host shell commands"的单进程部署 |
| 「工作区共享在 PG + 脚本本机跑」行不行？ | **不行**。框架只在「沙箱当主」时才用 `RoutedSandboxFilesystem` 保留 shell；本机当主 + 路由会被包成 `CompositeFilesystem`，而它**故意不实现 execute**，连 `shell_execute` 工具都不会注册 | `build()` 第 2396–2430 行的分叉、`CompositeFilesystem` 类注释原文 "Shell execution is intentionally not supported in this mode"、工具注册处的 `instanceof AbstractSandboxFilesystem` 判定 |

**所以只剩两个选择**：要「多实例共享 + 真隔离」就上容器（DR-42，已实测），要「不要 Docker」就**别做执行面**（DR-43 的默认档：技能只下发内容）。中间态（本机跑 + 共享工作区）框架不给。

**顺带把「除了 Docker 还有什么」一次列全**（免得以后再翻一遍源码）：

- **能跑命令的地方只有三处**：①Docker 容器沙箱（框架**唯一自带**的沙箱实现，真隔离）；②本机模式 `LocalFilesystemSpec`（宿主机起进程，无隔离，见上表）；③**自己实现** `SandboxClient` + `SandboxFilesystemSpec` 子类——框架把这两样都做成了开放扩展点（`SandboxFilesystemSpec.createClient()` 就是 protected abstract），所以接 K8s Pod / E2B / 微虚拟机 / 远程沙箱服务都行，只是**要自己写客户端和生命周期**，框架不给现成的。框架里**没有**别的执行后端：shell 的唯一入口是 `ShellExecuteTool`，没有内置的 Python/JS 解释器工具。
- **看着像沙箱、其实不是**（配套件，都有非 Docker 实现）：`SandboxExecutionGuard`（跨实例互斥锁：jdbc / redis / postgresql / noop，防止两台抢同一个沙箱）、`SandboxSnapshotSpec`（快照：noop / local 本机目录 / remote + jdbc / redis / postgresql / oss 远端客户端，让容器状态活过重启）、`WorkspaceSpec`（往沙箱里投影文件）。
- **跟 Docker 完全无关、且框架都带现成实现的**：会话状态 `AgentStateStore`（jdbc / redis：jedis·lettuce·redisson / postgresql / oss）、工作区文件 `BaseStore`（`JdbcStore` / `RedisStore` / `PostgresBaseStore` / `OssBaseStore`）、按上述组合出来的 `DistributedStore` 聚合、以及技能源 `GitSkillRepository`（扩展包，对应"中心化技能仓库"那种角色，我们不用——技能清单由网关给、内容按 DR-41 从对象存储取）。

### T1-16 落地说明：数据范围为什么归接口服务（2026-09-26，已完成）

这处原本是**两份设计打起来了**（实现按「接口服务自己推导」、规格书还留着「网关算出 scope 下发」），现在按你的拍板一次性对齐：**接口可用性判定看网关，数据范围推导看接口服务**。

- **分工一句话**：网关只回答「这个人能不能调这个接口」（`apiSet`，多角色并集），并把 `userId + requestId` 下发 + 签名；**能看哪些数据由接口服务自己推导**（它才认识业务表的归属字段），推导不出来一律 403。
- **为什么范围不能由网关算**：范围是「业务表结构 + 登录人」的知识（哪个字段代表科室、哪个代表本人）。让权限库去猜业务表结构，接口一复杂就必然要改权限库——V13 迁移把 `role_api.scope` 与 `sys_api.column_whitelist` 两列 DROP 掉，就是承认这件事。
- **列级为什么连白名单都不要**：返回哪些列由接口自己的 SQL 与返回类型写死，比运行期白名单更硬（多返回一列在编译期就不可能）。脱敏（masked / hidden）留作后续在输出格式化层扩展，骨架期不建表。
- **代价（明示）**：每接一个新接口都要在接口里写过滤条件；网关不再兜底范围，F 底线完全落在接口服务内，因此「只接受网关连接 + 强制验签」这两条硬约束更不能松。
- **文档落点**：新增 ADR-37（并修订 ADR-23 ② / ADR-26 ① / ADR-22 / ADR-20 的「网关下发 scope」表述），正文 40 余处同步；规格书版本行升到 v3.5。

---


---

### 数据范围残留复核（2026-09-26，已完成）

T0-05 只删了「系统提示词」里那一句。这次按你的要求把**全仓**又扫了一遍（中文「数据范围 / 数据可见范围」+ 英文 `scope`），结论分三类：

- **真残留（已改）**：
  - `agent-service/web/.../AgentServiceApplication.java`：类注释还写着「工具可见性与数据范围都由网关单点判定」——与 ADR-37 正好相反，改成「工具可见性看网关；数据范围不在本节点、也不在网关算」。
  - `common/platform/.../security/CanonicalRequest.java`：注释写「网关下发的 `userId + scope + requestId` 在请求体里，篡改数据范围会验签失败」——请求体里**根本没有 scope**（`CallerInfo(userId, requestId, traceId, skillCode, confirmId)`）。改成「签名保护的是身份与入参没被改，**不是**范围没被改」，并点明范围压根不在请求体里。
  - `deploy/migrations/V1 / V2 / V3 / V6`：建表注释与迁移头注释还在说「网关下发范围 → 接口服务拼 WHERE → 列白名单投影」。**DDL 一个字没动**（迁移历史不改），只加指引：这两列已在 V13 删除，范围现在归接口服务。
- **刻意保留（不是残留）**：`AuthorizationService` / `ApiDescriptor` / `RoleApiGrantView` / `RoleApiAdminController` / `ApiView.vue` 等处的「**没有** scope」「**不由**授权承载」——它们是在解释「为什么这里没有这列」，删了反而看不出取舍。
- **无关命中**：英文 `scope`（`IsolationScope` / `ResourceScope` / 前端 `css scoped`），以及审计字段 `scope_snapshot`（它是**留给接口服务填实际过滤条件**的栏位，等 H-07 才动）。

**一句话**：提示词本体本来就是干净的（`SystemPromptComposerTest` 里 `doesNotContain("数据范围")` 一直守着）；这次清掉的是「注释里还按旧口径描述现在的代码」那几处。

### T1-15 真跑暴露并修掉的两个问题（2026-09-26）

这一条的价值不在「跑一次」，而在**只有真跑才会暴露**的两处：

- **迁移脚本原来按文件名字符串排序**：`V10` 会排在 `V2` 前面，于是 `V13` 先于 `V2` 执行，而 `V13` 引用了 `V2` 建的 `data_access_audit`——**从零建库必然失败**。
  以前没发现，是因为一直是「在已有库上增量补迁移」（前面的都记过账、被跳过）；这次建新库才露出来。现在按版本号排序（`V1, V2, …, V16`）。
- **冒烟脚本原来只杀 Maven 进程**：`spring-boot:run` 会把应用 fork 成独立 JVM，只杀 Maven 那个会留下**孤儿**——它既占着 8081 / 8082，
  又还挂着脚本的输出管道，表现为「脚本早跑完了、调用方却永远等不到输出」。现在用 `taskkill /PID <maven> /T /F` 连子进程一起停，并按端口兜底补杀一次。

---

### T1-06 / T1-07 / T1-08 落地说明（2026-09-26）

- **T1-06 的共享粒度是「工具面 × 模型 × 迭代上限」**：这三样在框架里是 agent 级字段（`Toolkit` 尤其），改不了也不该改。
  每轮会变的东西——系统提示词、平台回调、本轮 id / 截止时间——全部改进 `RuntimeContext`（`CallAttributes`），
  于是同一个 agent 实例可以安全地服务所有用户。**这三处必须成对**，少一个就退化成「一台机器只能服务一个人」：
  agent 共享、工具对象不持有发起者回调、提示词按轮注入。
- **T1-06 为什么给共享缓存设上限 32**：键的取值空间正常是个位数（几个角色各一份），上限只是兜底——
  管理端反复换模型 / 反复改授权会让旧键变冷，满了就淘汰并 `close()`，这样内存与框架的静态注册表都不会随历史变更无限长。
  这也是 L-06（`stateSavers` 泄漏）的解法。
- **T1-07 为什么不用框架自带的 `LocalSessionTurnGate`**：它是**阻塞式公平信号量**——抢不到就排队等，
  而这里的调用方是 HTTP / SSE 请求，等一轮的租期就等于把用户的连接挂住几分钟不响一声。
  平台要的语义是「抢不到就当场说清楚（上一轮还在处理中）」，所以只**实现框架的接口**、自己提供 Redis / 内存两套实现。
  **接口用框架的、实现用自己的**，是这一条的关键取舍。
- **T1-07 的坑位里存随机令牌，不存「哪一轮」**：闸门只回答「有没有人在跑」；
  「是谁在跑」由 `platform_turn_live` 回答（那里连用户原话都有）。同一个问题不存两份，否则又是两个真相。
  排障日志里的 `holderOf(...)` 就读那一处，读不到就写「未知」——辅助信息不值得让它失败影响收尾。
- **T1-08 的标记为什么放 PG 而不是带 TTL 的 Redis**：它的用途是「下次读历史时解释那一轮」，而读历史走的是 PG，
  历史正文也在 PG。放 Redis 会出现「Redis 淘汰了标记、正文还在」——又回到静默丢失。
  它每条轮次重写一次，但只是一行小 JSON，代价可以忽略。
- **T1-08 的挂起时刻意不删标记**：HITL 续跑那一轮没有新提问，用户看到的「你问的是…」要靠这份标记留下来；
  删了就变成「确认续跑也挂了的时候，历史里只剩一句没头没尾的追问」。
- **T1-08 的写标记失败只记 warn，不让这一轮起不来**：标记是事后解释用的，把用户挡在门外是更大的损失。

### T1-09 / T1-10 / T1-11 / T1-12 落地说明（2026-09-26）

- **T1-09 三种结局都必须有交代，绝不能空挂**：跑完了→整段补（含末尾 `done`）；已经没人管→报「这一轮没跑完，请重发」；
  等到窗口结束还在跑→如实说「还在处理中，稍后再连」并闭合这一轮。空挂的连接比一句明确的失败更难查。
- **T1-09 判断「跑完了」要数 `done`，不能数轮次总数**：历史里可能已经挂着这一轮（进行中、没有 `done`），
  拿总数当基线就永远等不到「多出来一轮」。这是最容易写错的一处，代码里专门留了注释。
- **T1-09 的等待窗口 = SSE 超时 − 5 秒**：不做成常量的理由——等待用的是同一条 SSE 连接，
  等得比 SSE 超时还久，用户看到的只会是一个「莫名其妙断了的连接」，而不是我们精心准备的那句提示。
- **T1-10 的自动化与人工冒烟是两层**：`AgentWebMultiInstanceTest` 用同一套共享装配模拟两台实例（A 走 MockMvc、B 直接调服务），
  覆盖的是**逻辑**；`two-instance-smoke.ps1` 起两台真进程，覆盖的是**装配与端口**。
  自动化那层已经全绿；真进程那层需要本机 PG 口令，我这边跑不了（记在 T1-15）。
- **T1-11 的修订范围**：规格书里凡是写「自研 `JdbcAgentStateStore`」「表 `agent_state`」「`RuntimeStatePort`」的地方全部改掉；
  新增 ADR-36 记录「多副本运行时组件选型」的取舍（框架 `DistributedStore` 聚合 + 框架 `SessionTurnGate` 接口 + 现成扩展实现）。
- **T1-12 的结论对后续很重要**：工具可见性的唯一闸门是「这一轮注册了哪些工具」。
  `PlatformToolAdapter` 是 legacy `AgentTool`，不走 `ToolBase` 的权限规则那条路，所以**不要**指望用框架的 DENY 规则去兜底。
  以后要加「工具级硬拒绝」，要么把适配器改成继承 `ToolBase`，要么继续用 DR-21 的激活组方案。

### T1-13 / T1-14 落地说明（2026-09-26，本轮修正）

- **先承认之前的判断错了**：早前台账写着「框架 2.0.3 没有 `SessionTurnGate`」「框架的 PG / JDBC 版 CAS 不是真 CAS」，
  这两条**经源码核查都不成立**。框架有 `SessionTurnGate`、有 `DistributedStore` 一站式接口，
  扩展模块（jdbc / postgresql / redis / oss / skill-git-repository）也都能从 Maven Central 直接拿到。
  所以本轮把「凡是框架已有」的部分统一改成**用框架**：状态存储（T1-13）、轮次闸门接口（T1-14）。
- **T1-13 只保留一处平台自己写的东西：按 key 删除**。框架接口上的 `delete(userId, sessionId, key)` 是个**空方法**
  （默认什么都不做，注释写着「支持就覆写」），而框架的 JDBC 实现只覆写了「删整个会话」。
  于是平台侧「清掉一段状态」的调用会**静默失效**：一轮跑完了，挂起快照 / 轮次开始标记还留在库里，
  下次读历史就会把早就结束的一轮当成「还在跑」。静默失效比报错难查得多，所以必须补。
  补的方式也不手写 SQL——直接用框架方言里现成的 `sessionStateDeleteByKey`。
- **T1-13 的表名与寻址要对齐，这是唯一的耦合点**：表名 = 框架前缀 `agentscope_` + `sessions` → `agentscope_sessions`；
  寻址用的 `session_id` 列装的是**槽位号** `<userId>:<sessionId>`（没有 userId 时用框架的占位名 `__anon__`）。
  这个拼法框架没对外暴露，所以平台照抄了一份，并用 `PlatformAgentStateStoreTest` 里有一条用例专门钉住它：
  拼错了那条用例会红，而不是等线上「删除静默失效」才发现。
- **T1-13 为什么要包一层而不是直接 new 框架的类**：不包的话，应用里就有两个 `AgentStateStore` bean 的候选来源、
  而且「按 key 删除静默失效」这个坑没有地方写说明。包一层只有一个目的：**补这一个洞，顺便把理由写在类注释里**。
- **T1-13 为什么要 `@DependsOnDatabaseInitialization`**：这个 store 一造出来就要确认表在，
  所以它必须排在「建表脚本跑完」之后（测试走 `schema-h2.sql`，生产走迁移脚本）。
- **T1-13 为什么关掉 `autoCreateTable`**：DDL 只有一处才不会有第二个真相；而且多实例同时启动时，
  运行期 `CREATE TABLE IF NOT EXISTS` 在 PG 上会撞竞态。关掉之后表不存在就直接启动失败——这是我们要的失败方式。
- **T1-14 的 Redis 实现用一条 Lua 做释放**：`acquire` 是单条 `SET key value NX PX ttl`（只有一台能成功），
  释放要先比对「坑里还是我那个令牌」再删。分两步写就会出现「我释放了别人的坑」：
  租期到点、下一轮接管，我这轮才慢悠悠地收尾——这时无脑 `DEL` 会把下一轮的坑删掉。
  注意这是「坑位原本存什么」的**架构级**选择，不是实现细节：存随机令牌天然支持这种比对，存「轮次号」就做不到。
- **验收证据**：`mvn -B clean install` 全量 17 模块 BUILD SUCCESS、**351 个用例**、0 失败、0 跳过；
  其中 `agent-service-web` 101 例（含 `RedisMessageBusTest` 3 例真 Redis）、`agent-service-state` 5 例、`runtime-agentscope` 22 例、`platform` 62 例。

### H-13 落地说明：事件日志滚动与保留 + 会话列表改读档案（2026-09-26，已完成）

**这一轮用户点名要补的两件事**：①日志文件不能只增不减（「多大就滚动」可配、「已确认投递且超过 7 天」可删，可配）；②会话列表不能每开一次就把每个会话的整段对话读一遍。

**一、日志怎么收拢（DR-52）**

- **裁剪**（只删开头）：逐行读文件、逐行判，条件是「**这条已经确认投递**（位点在它之后）**且早于保留窗口**」；遇到第一条不满足的立刻停。只裁前缀是硬约束——位点是「活动文件内的字节偏移」，裁中间会让它全部失去意义。裁完把位点与写入位置一起前移同样多（前移量 = **删掉的前缀长度**），并立刻落盘：位点和文件内容必须是同一个瞬间的状态。
- **滚动**（换一个文件接着写）：活动文件到上限时，把「还没确认投递的尾巴」复制进新文件、旧文件带时间戳改名留档，位点归零。于是活动文件里只剩「还没送出去的那点东西」，而位点始终只在一个文件里，`pendingRedelivery()` 这类按位点扫的能力一点不用改。
- **清理**（删留档）：留档文件名里带时间戳（`agent-{instanceId}-{yyyyMMddHHmmss}.jsonl`），到点整份删；名字读不出来才退回看 mtime。
- **谁来跑**：`EventLogMaintenanceScheduler` 按分钟调 `maintenance()`（落盘 → 裁剪 → 滚动 → 清理留档）。它只碰**本实例自己**的文件，所以**不需要分布式锁**。没有它的话，那个文件会一直长到把磁盘占满——而它正是审计的唯一事实源。
- **一条不能碰的红线**：**未确认投递的记录一条都不删、也不滚**。滚动时未确认的尾巴会同时留在旧留档里，于是磁盘上**短暂存在两份**——这不是 bug，是「宁可多占一点也不能丢」的取舍，重投由消费端按 `event_id` 幂等兜住。反过来说，**一条都没确认时干脆不滚**：滚了只是把文件整个复制一遍，还会每分钟再造一份。

**二、列表怎么变快（DR-53）**

列表要的三个数（标题 / 提问条数 / 最后提问时刻）改成**在提问那一刻**写进 `platform_session` 档案，列表只读档案，**一次对话正文都不读**；历史回放（`/turns`）照旧读框架的 `AgentState`。口径写死在注释里：**档案是索引，正文是真相**——档案允许落后（例如绕过平台直接往状态库塞过对话），落后可自愈（再问一句就跟上）；升级前建的老会话没有档案，列表照旧显示它，只是标题为「未命名会话」、条数为 0。

**三、顺手修掉的三个真问题**

1. **裁剪判据永远是假**：原逻辑 `event.timestampEpochMs() < cutoff && cursor < acked` 里的 `cursor` 是循环游标、`acked` 是「已确认总数」，两者量纲不同，条件恒不成立——一条也裁不掉。
2. **位点可以回退**：`ack()` 原来不看当前值，一个「先发、后确认」的投递会把位点写小。现在**只许前进**（小于等于当前位点直接忽略）：多投一轮无所谓（`event_id` 幂等），但位点写小会让裁剪算错位置。
3. **`maxFileBytes` 配了却从未使用**：整个类里没有任何地方读过它。

另外把「整文件重写成保留段」换成「写临时文件 + 改名替换」：万一在抄写中途断电，磁盘上要么是完整的旧文件、要么是完整的新文件，不会留下一个抄了一半的活动日志；真崩在中间会剩下的那个 `.tmp`，由下一次清理按年龄收走（够老才删，所以不会误删正在写的那个）。

**验收证据**：`AppendOnlyEventLogTest` 4→10 例（新增：未确认一条都不裁 / 已确认且超期才裁且裁完位点与剩余内容都对 / 超上限滚动后旧文件留档且新文件只有未确认尾巴 / 一条都没确认时即使超上限也不滚 / 留档到点删而没到点的留 / 崩溃残留的临时文件被顺带收走）、新增 `EventLogConfigTest` 5 例（默认值兜底、文件命名、留档名时间戳往返、认不出的名字返回 -1 交给调用方兜底）、新增 `PlatformSessionStoreTest` 6 例（第一句定标题、压平并截断、档案缺失先补、空提问不写标题、提问不碰归档标记）、`AgentWebHistoryTest` 把「标题与条数来自会话正文」改成两条（标题与条数来自提问那一刻的记录 / **列表不读对话正文**——专门往状态库塞一段对话而不走提问入口，列表仍显示「未命名会话 / 0 条」）。全量 **17 模块 BUILD SUCCESS、449 例 0 失败**（2 例真 PG 默认跳过）。

### H-02 裁定说明：为什么不做逐字回放（2026-09-26 · 已裁定不做，改成 H-15）

**结论**：不把 token 级增量落库、不做「事后逐字回放」。**要补的是计时与用量**，已另开 **H-15**。

**证据一 · 同类产品就是这么做的（Codex 自己的会话记录）**：本机 37 个 rollout 文件逐字段扫过，只有三类——
条目级消息（`message` / `reasoning` / `function_call` / `function_call_output`）、每条的计时（`started_at` / `completed_at`）、
一份用量（`token_usage_record`：input / cached / output / reasoning / total **数字**）。**名字里带 delta 的字段一个都没有**；
连推理也只存「摘要 + 加密块」（加密块是给模型续跑用的，不是给人看的）。

**证据二 · 框架也这么划线（AgentScope 自家平台）**：`agentscope-service` 的 `SessionEventTypes` 把 `EVENT_START` / `EVENT_DELTA`
明确归到 **Stream-only (never persisted)**；被持久化的只有 `agent.message` / `agent.thinking` / `agent.tool_use` / `agent.tool_result` /
span / 会话状态——与我们事实表那几类几乎一一对应。框架的 `TranscriptMiddleware` 同理：每轮结束把 `AgentState.context` 里的**消息**
分段追加（`TranscriptStore` 的不可变 JSONL 分段），不是 token。

**为什么我们也不需要**：审计要的是「口径 / 张表 / 过滤条件 / 返回结果」，评估要的是「最终答案」，两者都不因逐字而更有证据力；
排障要看「当时到底吐了什么」时，本地 append-only 日志里**本来就有**逐字事件（H-13 之后 7 天窗口内确实存着），不额外花钱。

**代价（为什么「能做」不等于「该做」）**：一轮回答大约几百到上千条 delta，1 万轮/天就是 **800 万行/天**，而块级只有约 10 万行/天，
差 80 倍；逐字还是高频小写入，正好会把 H-13 刚做好的滚动与裁剪推回去。

**替代（H-15）**：框架的 `ModelCallEndEvent` 带着 `ChatUsage`（input / output / cached / reasoning token 数，还带 `time` 耗时），
平台现在**一处都没映射**（全库检索 `MODEL_CALL` 为空，等于白丢）。接上就是一轮几行，换来首字延迟 / 总耗时 / token 用量 / 成本。

**排期补充（2026-09-26 晚些）**：H-15 本身也已由用户定为「**先不做**」——它在台账里是暂缓项，不是裁定不做；上面这套接法原样留着，用户说做就能照着直接接。

**触发条件（写死，免得反复讨论）**：出现「必须按逐字粒度作为证据」的要求（例如监管要求留存模型逐字输出）才开；
届时最小实现是只落**答案**的 delta、不落思考，单独一张表 + 独立保留窗口。
