# 本机原生部署（Windows）

PostgreSQL / Redis / MinIO 在这台机器上都是**原生进程**（不走容器）。本目录放「怎么在裸机上把它们跑起来」的脚本与说明。

容器那一套在 `deploy/docker-compose.yml`，定位是 **CI / 生产参考**。本机的 Docker Desktop 也已经能用了（2026-09-26 修好，修法见下面「Docker Desktop 起不来怎么修」），所以要走容器也行。

## 已经装了什么

| 组件 | 装在哪 | 端口 | 谁在托管 |
| --- | --- | --- | --- |
| MinIO | `D:\minio`（D 盘，C 盘不放软件） | 9000 API / 9001 控制台 | 计划任务 `MinIO-Local`（登录时自动启动） |
| PostgreSQL | 本机原生安装（不在本目录） | 5432 | Windows 服务 |
| Redis | 本机原生安装（不在本目录） | 6379 | Windows 服务 |
| Docker Desktop | `C:\Program Files\Docker\Docker` | 无 | 需要容器时手动启动（2026-09-26 已修好并验证，见下） |

## 一键启动整套（start-local.bat）

只想「把项目跑起来看看」，用仓库根目录的 `start-local.bat`——双击就行。它按顺序做五件事：

1. **前置检查**：Java / Maven / PostgreSQL / Redis（顺带看一眼该建的表建过没）；
2. **构建**：`mvn -DskipTests install`（不构建就起不来：`spring-boot:run` 是从本机仓库拿 jar 的）；
3. **起 4 个后端**：网关、接口服务、管理端、agent 服务；
4. **等它们真的能用**：不只是端口通，还要求管理端能登录、agent 能建会话、网关与接口服务「无签名即 401」；
5. **起前端**并打开浏览器。

| 你想干什么 | 怎么跑 |
| --- | --- |
| 默认：构建 + 全起 | `start-local.bat` |
| 已经构建过，省掉那一步 | `start-local.bat -SkipBuild` |
| 只起后端（用 `demo\*.ps1` 测） | `start-local.bat -SkipWeb` |
| 不接真模型（不烧 token，验证链路） | `start-local.bat -Runtime noop` |
| 换 MinIO 当对象存储 | `start-local.bat -ObjectStorage s3`（要先 `minio-start.ps1`） |
| 盯着实时日志 | `start-local.bat -ShowWindows` |
| 本机 PG 口令不是默认那个 | `start-local.bat -DbPassword <口令>` |
| 全部停掉 | `stop-local.bat` |

| 服务 | 端口 | 日志（`.local\logs\`） |
| --- | --- | --- |
| 前端（vite） | 5173 | `web.log` |
| 网关 | 8080 | `gateway.log` |
| agent 服务 | 8081 | `agent-service.log` |
| 管理端 | 8082 | `management-service.log` |
| 接口服务 | 8084 | `interface-doctor.log` |

账号：`admin / admin123`（管理员）、`alice / alice123`。

三个刻意的设计，免得被当成 bug：

- **默认不弹窗**。五个控制台窗口会把桌面挤满，而且关窗口的手势很容易误杀一个服务；日志一律进文件，
  起不来时脚本会把失败那个的末尾几行直接打出来。要盯实时日志就加 `-ShowWindows`。
- **不自动杀旧进程**。端口被占时直接报错、让你决定——「杀谁」这种事不替你做（`stop-local.bat` 是显式的）。
- **停服务按端口杀**（`taskkill /T`）。服务是 `mvn spring-boot:run` 起的，进程名是 `java`，
  本机可能还有别的 java（IDE、别的项目）；按端口杀才不会误伤，也不会漏掉子进程。

**第一次跑还差一步**：登录后进「管理端 → 模型供应商」启用一个模型（脚本已经把加解密用的 KEK 给两个服务配成同一把了）。
不配也能跑，只是对话会提示「未配置」——那是有意的，比「悄悄用别的模型」好。

> 脚本本身在 `deploy\local-windows\start-local.ps1`（`.bat` 只是双击入口，参数原样透传）；
> 固定的本机配置（端口、三个共享密钥、开发用 KEK）都写在该文件开头那一节，要改就改那一处。

## MinIO 怎么用

```powershell
cd deploy\local-windows

.\install-minio.ps1     # 安装 / 修复 / 重新注册自启（可重复执行，装过就只补缺的）
.\minio-start.ps1       # 启动（已在跑就不动）
.\minio-start.ps1 -Force # 重启
.\minio-stop.ps1        # 停止
.\minio-restart.ps1     # = start -Force，名字更直白
.\minio-status.ps1      # 一眼看状态：进程 / 任务 / 端口 / 健康 / 桶 / 最近日志
```

装完的目录结构：

```
D:\minio\bin\minio.exe        服务端
D:\minio\bin\mc.exe           命令行客户端（建桶、传文件、排查）
D:\minio\data                 数据目录（对象都在这，重装不删）
D:\minio\config\minio.env     账号口令 / 端口 / 桶名
D:\minio\config\mc            mc 自己的配置（不放 C:\Users）
D:\minio\logs                 minio.err.log 是日志（MinIO 把日志写在标准错误上）
```

默认账号 `djzy / djzy-minio`（与 `deploy/docker-compose.yml` 一致，方便容器 / 原生两种跑法切换）。
**只用于开发机**：生产密钥一律由部署环境注入，不进仓库、不进镜像。

控制台：<http://127.0.0.1:9001>，API：<http://127.0.0.1:9000>。

## ⚠️ 关于 MinIO 版本（重要）

MinIO 的**社区版已被官方归档**：`dl.min.io` 上的下载一律返回 **410 Gone**，官方不再提供支持与安全更新。

本机装的是官方 **GitHub Releases 上最后一个开源版本**（`RELEASE.2025-09-07T16-13-09Z`），安装脚本会用官方给出的 SHA256 校验一遍再落地。

这意味着：

- 它**只适合本机开发**，不要拿它当生产依赖；
- 生产要换成仍在维护的对象存储（阿里云 OSS / 腾讯云 COS / Ceph 等）；
- 换的时候**不用改业务代码**：对接走 `ObjectStorage` 端口，新增一个适配器即可（见 T0-08）。

## 自启是怎么做的

用「登录时触发」的计划任务 `MinIO-Local`（普通用户就能建，不需要管理员）。任务的动作是 `minio-run.ps1`：

- 它**在前台守着** `minio.exe`（`WaitForExit`），所以「任务在跑」= 「MinIO 在跑」，不会留下没人管的孤儿进程；
- 计划任务由「任务计划服务」拉起，**不继承任何控制台 / 管道句柄**——这就是为什么 `install-minio.ps1 | Select-Object ...` 这类写法能正常结束；
- 任务里记的是**本仓库的脚本路径**。仓库挪了位置，重跑一次 `install-minio.ps1` 就好。

如果这台机器不允许普通用户建计划任务，脚本会自动退回「启动文件夹放快捷方式」，效果一样。


## Docker Desktop 起不来怎么修（2026-09-26 已修好并验证）

**症状**：Docker Desktop 报「`Docker Desktop distro installation failed`」，展开详情是
`open \\wsl$\docker-desktop\etc\wsl_bootstrap_version: The specified network name is no longer available`；
同时在命令行跑 `wsl -l -v` 也起不来，报 `Wsl/Service/CreateInstance/CreateVm/HCS/HCS_E_HYPERV_NOT_INSTALLED`。

**结论：不是 Docker 装坏了，不用重装 Docker。** 是 Windows 侧的 WSL2 虚拟化平台压根没在跑。
本例里两个 Windows 功能（`VirtualMachinePlatform`、`Microsoft-Windows-Subsystem-Linux`）本来就是 `Enabled`，
真正的原因是**引导项里的 `hypervisorlaunchtype` 被设成了 `Off`**（装模拟器 / 「系统优化」类软件常干这事），
于是 WSL 连虚拟机都建不出来，Docker 自然连它自己的发行版都挂不上。
（BIOS 里的 CPU 虚拟化开关是好的：`VirtualizationFirmwareEnabled=True`，所以只需要在 Windows 侧修。）

**怎么修**（脚本先打印现状、再改；需要管理员，会弹 UAC）：

```powershell
cd deploy\local-windows
.\fix-wsl2-hypervisor.ps1                     # 直接修
.\fix-wsl2-hypervisor.ps1 -WhatIfOnly         # 只看现状，什么都不改
.\fix-wsl2-hypervisor.ps1 -ResetDockerDistros # 发行版已经坏了才用（会丢镜像与卷）
```

**改完必须重启电脑**——`hypervisorlaunchtype` 只在下次开机生效。重启后先验一下：

```powershell
wsl -d docker-desktop -- echo BOOT_OK   # 能打出 BOOT_OK 就说明 WSL 好了
```

然后再启动 Docker Desktop。脚本会把结果同时写一份到 `%TEMP%\fix-wsl2-hypervisor.log`，方便在普通窗口回头核对。

**本机实测结果（2026-09-26 重启后）**：`HypervisorPresent = True`；`wsl -d docker-desktop -- echo BOOT_OK` 打出 `BOOT_OK`；
Docker Desktop 起来后引擎是 `server 27.0.3`（`docker info`：8 CPU / ~16GB / `overlay2`），`docker run --rm hello-world` 通过。
**结论：H-05 与 H-06b 都不卡在环境上了**——H-05（沙箱装配）与 H-06b（把技能投影进容器、让技能脚本在容器里执行）都已于 2026-09-26 落地、**默认关着**（怎么打开见下面「沙箱（H-05）怎么在本机打开试一次」）。脚本执行仍是**可选升级档**：Docker 沙箱只在真要跑用户脚本、且需要隔离时才启用（见 `doc/refactor/TASKS.md` 的 DR-43 / DR-44 / DR-45 与 §6「H-06b 落地说明」，以及 L-16）。所以默认档下 Docker 不是「跑 agent 服务的前置条件」——只有把 `AGENT_SANDBOX_ENABLED` 打开时它才是（那时启动期自检拿不到 `docker version` 会拒绝启动）。

两个操作上的注意：

- **`docker` 命令要新开一个终端才认得**：Docker Desktop 之前**没有**把 CLI 加进 PATH（用户级和系统级都没有）。
  2026-09-26 已把 `C:\Program Files\Docker\Docker\resources\bin` 加进**用户级 PATH**（用户级就够了，不需要管理员，也避开了 `setx` 的 1024 字符截断）；
  已经开着的终端要**重开一个**才生效，实在不行用全路径 `C:\Program Files\Docker\Docker\resources\bin\docker.exe`。
- **别用「会被一起收掉的子进程」方式拉起 Docker Desktop**：它是托盘程序，从开始菜单 / 桌面图标点最省事。
  从脚本里拉起、而脚本一结束就连子进程一起收掉时，它的后台进程会收到关闭信号（日志里是 `backend shutdown after signal`），表现为「启动二十秒后又没了」。
  要脚本拉起就用与调用方脱钩的方式（例如 WMI `Win32_Process.Create`）。

**写本目录脚本的一个坑（请遵守）**：所有 `.ps1` 必须存成**带 BOM 的 UTF-8**。
Windows PowerShell 5.1 读没有 BOM 的 `.ps1` 会按 GBK 解，中文注释被解坏后会报一堆莫名其妙的语法错
（`fix-wsl2-hypervisor.ps1` 第一次就是这么挂的，加个 BOM 立刻 `PARSE_OK`）。本目录其它脚本都已有 BOM，新加脚本请照做。

## 本机数据库（PostgreSQL）怎么用

这台机器的 PG **只有 `postgres` 一个角色**（没有 `assistant`），所以两个脚本都要显式带上账号：

```powershell
# 建库（一次就够）：doctor_assistant 是配置文件里的默认库名
& 'D:\PostgreSQL\18\bin\psql.exe' -U postgres -d postgres -c "CREATE DATABASE doctor_assistant ENCODING 'UTF8'"

# 按序应用迁移（幂等：记在 schema_migration_local，跑过的不会重复执行）
.\apply-migrations.ps1 -User postgres -Password '<口令>' -Database doctor_assistant

# 两实例冒烟（T1-15；已经在 CI / 本机 install 过就加 -SkipBuild）
.\two-instance-smoke.ps1 -JwtSecret 'dev-smoke-secret' -DbUser postgres -DbPassword '<口令>' -SkipBuild
```

两个坑值得写下来（都是真跑一次才暴露的，现已修）：

- **迁移必须按版本号排序**：按文件名字符串排会把 `V10` 排在 `V2` 前面，于是 `V13` 先于 `V2` 执行，而 `V13` 引用了 `V2` 建的 `data_access_audit`——从零建库必然失败。
- **停实例要连子进程一起停**：`spring-boot:run` 会把应用 fork 成独立的 JVM，只杀 Maven 进程会留下占着端口、还挂着输出管道的孤儿（表现为「脚本跑完了但调用方一直不结束」）。脚本现在用 `taskkill /T /F`，并按端口兜底补杀一次。

结果怎么看：脚本最后打印一张表，**10 项全 PASS** 才算过（工作区共享：两台实例都装配成共享库 / A 建会话 / B 看到 A 的会话 / B 跨实例换券 / B 读历史 / 拿 B 的券到 A 接流且不空挂 / A 起一轮真的跑 / 这一轮跑完 / 事件进了共享总线 / A 停止幂等）；日志在 `.smoke\<实例>\stdout.log`。

补充（H-09）：停止现在除了写共享的信号键，还会往总线推一条（持有那一轮的实例收到就当场取消，不用等下一个事件）。脚本里「A 停止」这项验的是**接口幂等**，推送这条链由 `TurnStopChannelTest` 那例真 Redis 用例（A 推 B 收）兜着；「取消是否真的打断模型」由 runtime 模块的 `TurnInterruptTest` 兜着（它拿一个慢模型数停止之后还产出多少，见 TASKS.md 的 DR-48）。

前置条件补充（H-04）：这个脚本会把两台实例的 `AGENT_WORKSPACE_STORE` 设成 `jdbc`，也就是工作区落共享 PG 表 `agentscope_store`；**表不存在时实例会直接启动失败**（这正是我们要的失败方式）。所以跑冒烟之前必须先 `apply-migrations.ps1`，让 `V17__workspace_store.sql` 跑过。

## 技能下发（H-06a）怎么在本机打开

默认是**关着**的（`agent-service.workspace-skills=none`，日志会打「技能下发：关闭」）。要打开：

```powershell
# 1) 工作区必须是共享的：技能要写进「用户的工作区」，而工作区在多副本下就是那张共享表
AGENT_WORKSPACE_STORE = 'jdbc'      # 表 agentscope_store（V17）
AGENT_WORKSPACE_SKILLS = 'workspace' # 打开技能下发

# 2) 内容从哪来：管理端发布时放进去的那个对象存储（本机就是 D:\minio，provider=s3）
#    object-storage.provider / root-dir / s3.* 见 agent-service 的 application.yml，与 management-service 同名同默认
```

打开之后，每一轮对话开始前会做一次「同步」：读工作区里的索引 → 一次批量 SQL 问「这些技能现在算数的是哪一版」→
**只有版本变了的技能**才去对象存储取包、解包、写进 `skills/<技能编码>/`。所以稳定的情况下它很便宜（一次读 + 一次查，不进对象存储）。

两个前提，缺一个就等于「模型看不见技能」：

- **网关上这个人得能看到技能**（能力清单里的技能编码就是这里要下发的清单）——本机没有真网关时，模型自然看不到任何技能；
- **管理端得真发布过那个技能**（`sys_skill_version.status = 'published'`）。只上传没发布 = 这次不下发，这是刻意的口径。

**版本口径：一个技能只认一个版本。** agent 侧每次都取「该技能下最新一条 `status = 'published'`」的那一版，
历史版本行只作留档、不参与下发；**发布新版本 = 旧版本自动不再下发**，不做版本并存、不做回滚。
（配套约束：包内单个文件 ≤ 256KB、整包 ≤ 5MB，且 `content_sha256` 唯一——内容没变不会产生第二个版本。）

冒烟脚本**验不到这一条**：它跑的是 `noop` 运行时（本机没有模型服务），而技能下发挂在 AgentScope 运行时的每一轮开始处。
用例这一层有 `SkillProvisioningIntegrationTest`（真表 + 真对象存储 Bean + 共享工作区表）兜着，见仓库根 `README.md`。

## 沙箱（H-05）怎么在本机打开试一次

默认**关着**：命令不在容器里跑（没有沙箱、没有壳工具），因为脚本跑在宿主机上等于没有隔离。想试一次：

```powershell
AGENT_SANDBOX_ENABLED = 'true'
# 其余可选：AGENT_SANDBOX_IMAGE（默认 ubuntu:22.04）/ AGENT_SANDBOX_MEMORY_BYTES / AGENT_SANDBOX_CPU_COUNT（0 = 不限制）
#           AGENT_SANDBOX_NETWORK（空 = Docker 默认）/ AGENT_SANDBOX_SHARED_PREFIXES（默认 skills/）
```

打开后三件事同时发生：容器变成 agent 的主文件系统（命令在容器里跑）、`skills/` 这类共享前缀照旧读写共享库（多副本看到同一份）、
壳工具被放行（模型能跑命令了，只是场所变成了容器）。

**打开后技能也进容器**（H-06b）：每一轮先把共享库里的 `skills/` 抄一份到本机、再交给框架投影进容器，所以技能里的脚本
（例如 `sh skills/<技能>/scripts/run.sh`，路径相对工作区根）在容器里跑得动。前提是**技能下发也开着**（`AGENT_WORKSPACE_SKILLS=workspace`）：
只开沙箱不开下发，容器里就没有技能可投影。

**先确认 Docker 在跑**：启动时会探一次 `docker version`，拿不到就**拒绝启动**（不会偷偷退回本机执行）。
自检通过时启动日志长这样：`沙箱：开（Docker 27.0.3，镜像 ubuntu:22.04，工作区 /workspace，共享前缀 [skills/]）`。

**Windows 上的已知限制**（台账 L-19）：`docker exec` 会吃掉双引号分组，所以框架「往容器里写文件」在 Windows 上会失败
（`write` 的检查命令里有 `mkdir -p "$(dirname <路径>)"`，进容器就成语法错误）；只读 / 执行类命令不受影响。
真实环境请在 Linux 上跑，本机 Windows 只适合验证「命令确实跑在容器里」——真容器用例就是这么设计的。
## 常见问题

**日志是空的？**
看 `D:\minio\logs\minio.err.log`。MinIO 把日志写在标准错误上，`minio.out.log` 通常一直是空的。

**9000 端口被占用 / 起了两个？**
`.\minio-status.ps1` 看进程；`.\minio-stop.ps1` 会把「本安装目录里的那个 minio.exe」全部停掉。重新登录时 `minio-run.ps1` 会先检查有没有实例在跑，不会起第二份。

**想换盘 / 换端口 / 换口令？**
改 `_minio-common.ps1` 里的 `$MinioRoot`，或改 `D:\minio\config\minio.env` 后跑 `.\minio-restart.ps1`。

**想彻底卸载？**
1. `.\minio-stop.ps1`
2. `Unregister-ScheduledTask -TaskName MinIO-Local -Confirm:$false`
3. 删掉 `D:\minio` 整个目录

## 事件日志多大就滚动 / 多久清（H-13）

append-only 事件日志（`AGENT_EVENT_LOG_DIR`）是审计的唯一事实源，但它**不会只增不减**了：每个实例按固定间隔
（默认 1 分钟）在自己那个文件上做一遍「落盘 → 裁剪 → 滚动 → 清理留档」。三个旋钮都有默认值，本机一般不用改：

```powershell
AGENT_EVENT_LOG_MAX_BYTES = '268435456'            # 单文件上限，默认 256 MB；到这个大小就换一个文件接着写
AGENT_EVENT_LOG_RETENTION = '7d'                   # 已确认投递的记录保留多久，默认 7 天
AGENT_EVENT_LOG_MAINTENANCE_INTERVAL_MS = '60000'  # 维护间隔，默认 1 分钟
```

两件容易被误解的事：①**前提是「已确认投递」**——还没送进队列的记录，多旧都不会删（宁可占磁盘也不丢事件）；
②滚动**不会**删东西，它只是把旧文件带上时间戳留档（`agent-{实例号}-{时间戳}.jsonl`），留档到点才整份删。
所以「文件看着变多了」是正常的中间状态，不是泄漏。此外，万一某次裁剪「抄到一半」时断电，留下的
`agent-{实例号}.jsonl.tmp` 会被下一次清理按年龄收走（够老才删，不会误删正在写的那个）。
