<#
本机一键启动（Windows 开发机）：把「跑起来看看」需要的东西一次点起来。

它做五件事：
  1) 前置检查：Java / Maven / PostgreSQL / Redis（MinIO、Node 按需）
  2) 必要时先构建一次（mvn -DskipTests install）。不构建就起不来：spring-boot:run 是从本机仓库
     拿 jar 的，本仓库其它模块没 install 过，它就会报「找不到依赖」
  3) 起 4 个后端 + 前端，日志写进 deploy\local-windows\.local\logs\
  4) 等到「真的能用」才算好：不只是端口通，还要求管理端能登录、agent 能建会话
  5) 打开浏览器 http://127.0.0.1:5173

为什么默认不弹窗：五个控制台窗口会把桌面挤满，而且关窗口的手势很容易误杀一个服务。
日志都在 .local\logs\ 下，起不来时本脚本会把失败那个的末尾几行直接打出来；要盯实时日志就用 -ShowWindows。

用法（仓库根目录双击 start-local.bat，或在这里直接跑本脚本）：
  .\start-local.ps1                  # 默认：构建 + 起后端 + 起前端
  .\start-local.ps1 -SkipBuild       # 已经 mvn install 过，省掉构建这一步
  .\start-local.ps1 -SkipWeb         # 只起后端，不起前端
  .\start-local.ps1 -Runtime noop    # 不接真模型（默认 agentscope，需要先在管理端配好模型供应商）
  .\start-local.ps1 -ShowWindows     # 每个服务弹一个控制台窗口，方便盯实时日志

停止：仓库根目录的 stop-local.bat（或这里的 .\stop-local.ps1）
#>
[CmdletBinding()]
param(
    # 跳过构建。只在「刚跑过 mvn install、代码没改」时用，否则起不来或跑的是旧代码。
    [switch]$SkipBuild,

    # 只起后端。
    [switch]$SkipWeb,

    # 弹控制台窗口（实时看日志用）。默认关着：日志一律进 .local\logs\。
    [switch]$ShowWindows,

    # 运行时：agentscope = 真接模型（默认，需要在管理端启用一个模型供应商）；
    # noop = 不调模型（答案由输入按关键词决定），用来验证链路而不烧 token。
    [ValidateSet('agentscope', 'noop')][string]$Runtime = 'agentscope',

    # 对象存储：local = 本机目录（单实例够用）；s3 = MinIO（多副本、或想试对象存储时用）。
    [ValidateSet('local', 's3')][string]$ObjectStorage = 'local',

    # 本机 PostgreSQL 的账号与口令（默认值就是这台机器的开发库）。
    [string]$DbUser = 'postgres',
    [string]$DbPassword = 'lyc123456',
    [string]$DbName = 'doctor_assistant'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

# ---------------------------------------------------------------------------
# 本机开发用的固定配置（要换就改这里，不用去翻各个 yml）
# ---------------------------------------------------------------------------

# 登录令牌密钥：管理端签发、网关校验、agent 服务校验，三边必须同一把，
# 否则「登录成功了，但对话页说 401」。
$LoginSecret = 'local-login-token-secret'
# 网关调接口服务时报的密钥；接口服务侧必须配同一个（IFACE_DOCTOR_GATEWAY_SECRET）。
$GatewayOutSecret = 'local-gateway-outbound-secret'
# agent-service 调网关时报的密钥（demo\*.ps1 里默认就是它，配成它，演示脚本可以直接跑）。
$AgentServiceSecret = 'local-agent-service-secret'
# 管理端调网关时报的密钥（本机目前用不到，但网关要求这个 keyId 有值才肯启动）。
$ManagementSecret = 'local-management-service-secret'
# 模型 API Key 的加密根密钥（Base64 的 32 字节）。管理端用它加密、agent 侧用它解密，必须同一把。
# 这**只是本机开发值**；生产由部署环境注入，不进仓库。
$LlmKek = 'MSlviPl+fVpBksKDoviZ67NlMAqcLrhoqqKitCzFm0g='

$GatewayPort = 8080     # 网关
$AgentPort = 8081       # agent-service（对话与 SSE）
$ManagementPort = 8082  # 管理端（登录、M2-M6）
$IfacePort = 8084       # 接口服务（故意避开 8080：8080 留给网关）
$WebPort = 5173         # 前端开发服务器

$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent   # 仓库根
$runDir = Join-Path $PSScriptRoot '.local'                      # 本机运行痕迹（日志、事件日志、工作区）
$logDir = Join-Path $runDir 'logs'

# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------

function Write-Step([int]$Index, [int]$Total, [string]$Text) {
    Write-Host ''
    Write-Host ("[$Index/$Total] $Text") -ForegroundColor Cyan
}

function Test-Tcp([string]$HostName, [int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        return $async.AsyncWaitHandle.WaitOne(400) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Get-PortState([int]$Port) {
    # 两个回环都试：Java（Tomcat）默认绑 0.0.0.0，而 Node（vite）在 Windows 上默认只绑 ::1。
    # 只试 127.0.0.1 的话，前端明明起来了，脚本却会一直说「没起来」。
    return (Test-Tcp '127.0.0.1' $Port) -or (Test-Tcp '::1' $Port)
}

function Find-Mvn {
    $onPath = Get-Command mvn -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $fallback = 'D:\maven\apache-maven-3.9.6-bin\apache-maven-3.9.6\bin\mvn.cmd'
    if (Test-Path -LiteralPath $fallback) { return $fallback }
    return $null
}

function Find-Psql {
    $onPath = Get-Command psql -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    foreach ($rootDir in @('D:\PostgreSQL', 'C:\Program Files\PostgreSQL')) {
        if (-not (Test-Path -LiteralPath $rootDir)) { continue }
        $hit = Get-ChildItem -LiteralPath $rootDir -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'bin\psql.exe' } |
            Where-Object { Test-Path -LiteralPath $_ } |
            Select-Object -First 1
        if ($hit) { return $hit }
    }
    return $null
}

function Invoke-Api {
    # 只为了「探活 + 取一个字段」，所以不引第三方库：失败也不抛，把状态码原样交回来。
    param([string]$Method, [string]$Url, [string]$Token, $Body)

    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }

    $params = @{ Method = $Method; Uri = $Url; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 20 }
    if ($null -ne $Body) {
        $params['Body'] = ($Body | ConvertTo-Json -Compress)
        $params['ContentType'] = 'application/json'
    }
    try {
        $resp = Invoke-WebRequest @params
        return @{ Status = [int]$resp.StatusCode; Text = $resp.Content }
    } catch {
        $web = $_.Exception.Response
        $code = 0
        if ($web -and $web.StatusCode) { $code = [int]$web.StatusCode }
        return @{ Status = $code; Text = '' }
    }
}

function Show-LogTail([string]$Path, [int]$Lines = 20) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    Write-Host ("     日志末尾（" + $Path + "）：") -ForegroundColor DarkGray
    Get-Content -LiteralPath $Path -Tail $Lines -Encoding UTF8 -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host ("     " + $_) -ForegroundColor DarkGray }
}

function Wait-Port {
    # 等的是「端口能连上」——这只是必要条件，能不能用由后面的探活说了算。
    param([string]$Name, [int]$Port, $Process, [int]$TimeoutSec = 240)

    Write-Host ("   " + $Name.PadRight(18) + " :" + $Port + " ") -NoNewline
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Get-PortState $Port) {
            Write-Host '就绪' -ForegroundColor Green
            return
        }
        if ($Process -and $Process.HasExited) {
            Write-Host '提前退出了' -ForegroundColor Red
            throw "$Name 没起来就退出了（退出码 $($Process.ExitCode)）。"
        }
        Write-Host '.' -NoNewline
        Start-Sleep -Milliseconds 700
    }
    Write-Host '超时' -ForegroundColor Red
    throw "$Name 在 $TimeoutSec 秒内没起来。"
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

try {
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null

    Write-Host '=== data-assistant 本机一键启动 ===' -ForegroundColor White
    Write-Step 1 5 '前置检查'

    $mvn = Find-Mvn
    if (-not $mvn) { throw '找不到 maven：既不在 PATH 里，也不在 D:\maven 下。装一个或把 mvn 加进 PATH。' }
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw '找不到 java：本项目要 Java 21，装好并加进 PATH。' }
    Write-Host '   Java / Maven       OK' -ForegroundColor Green

    foreach ($item in @(@{ N = 'PostgreSQL'; P = 5432 }, @{ N = 'Redis'; P = 6379 })) {
        if (-not (Get-PortState $item.P)) {
            throw "$($item.N)（端口 $($item.P)）没在跑。它俩是必备依赖：PG 放会话与权限，Redis 放一次性券、轮次坑位与跨副本总线。"
        }
        Write-Host ("   " + $item.N.PadRight(18) + " OK（端口 " + $item.P + "）") -ForegroundColor Green
    }

    if ($ObjectStorage -eq 's3') {
        if (-not (Get-PortState 9000)) { throw '选了 -ObjectStorage s3，但 MinIO（端口 9000）没在跑。先跑 .\minio-start.ps1，或者去掉这个开关。' }
        Write-Host '   MinIO              OK（端口 9000）' -ForegroundColor Green
    }

    # 端口被占：多半是已经起着一份。这里不自动杀旧进程——那是替用户做决定，容易误伤别的项目。
    $busy = @()
    foreach ($item in @(@{ N = 'gateway'; P = $GatewayPort }, @{ N = 'agent-service'; P = $AgentPort },
                         @{ N = 'management-service'; P = $ManagementPort }, @{ N = 'interface-doctor'; P = $IfacePort })) {
        if (Get-PortState $item.P) { $busy += "$($item.N) :$($item.P)" }
    }
    if ($busy.Count -gt 0) {
        throw ("这些端口已经有服务在跑：" + ($busy -join '、') + "。先跑 stop-local.bat 停掉旧的，再启动。")
    }

    # 表检查：不做成硬失败（真正缺表时服务启动会自己报），只提前提醒一句，
    # 省得对着「表不存在」的报错找半天。
    $psql = Find-Psql
    if ($psql) {
        $env:PGPASSWORD = $DbPassword
        $need = @('sys_user', 'sys_api', 'role_api', 'agentscope_sessions', 'agentscope_store', 'event_outbox')
        $quoted = ($need | ForEach-Object { "'" + $_ + "'" }) -join ','
        $sql = "select t from unnest(array[$quoted]) as t where to_regclass('public.' || t) is null"
        $missing = & $psql -h 127.0.0.1 -p 5432 -U $DbUser -d $DbName -t -A -c $sql 2>$null
        if ($LASTEXITCODE -ne 0) {
            Write-Host '   数据库连不上，或库还没建（跳过表检查）' -ForegroundColor Yellow
            Write-Host ("     当前用的是 " + $DbUser + '@' + $DbName + "；口令不对就加 -DbPassword，库没建就先跑 .\apply-migrations.ps1") -ForegroundColor DarkGray
        } elseif ($missing) {
            Write-Host ("   缺这些表：" + (($missing | Where-Object { $_ }) -join '、')) -ForegroundColor Yellow
            Write-Host '     先跑 .\apply-migrations.ps1 -User postgres -Password <口令> 建表，否则服务起不来。' -ForegroundColor DarkGray
        } else {
            Write-Host '   数据库表           OK' -ForegroundColor Green
        }
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    } else {
        Write-Host '   找不到 psql，跳过「表建过没」的检查' -ForegroundColor DarkGray
    }

    # -----------------------------------------------------------------------
    Write-Step 2 5 '构建'
    if ($SkipBuild) {
        Write-Host '   跳过（-SkipBuild）：直接用本机仓库里已有的 jar' -ForegroundColor DarkGray
    } else {
        Write-Host '   mvn -DskipTests install（首次或改过代码后需要，可能要一两分钟）' -ForegroundColor DarkGray
        # 用 Start-Process 而不是 & mvn：mvn 往标准错误写的一句告警，在 $ErrorActionPreference='Stop'
        # 下会被 PowerShell 当成异常抛出来，构建明明成功也会中断。
        $build = Start-Process -FilePath $mvn -ArgumentList @('-B', '-DskipTests', 'install') `
            -WorkingDirectory $root -NoNewWindow -Wait -PassThru
        if ($build.ExitCode -ne 0) { throw "构建失败（mvn 退出码 $($build.ExitCode)）。先把上面的报错解决掉再启动。" }
        Write-Host '   构建成功' -ForegroundColor Green
    }

    # -----------------------------------------------------------------------
    # 起服务
    # -----------------------------------------------------------------------

    $dbUrl = "jdbc:postgresql://localhost:5432/$DbName"

    # 对象存储：管理端与 agent 侧必须指向同一处，否则「管理端上传的技能，agent 侧找不到」。
    if ($ObjectStorage -eq 's3') {
        $storageEnv = @{
            OBJECT_STORAGE_PROVIDER      = 's3'
            OBJECT_STORAGE_S3_ENDPOINT   = 'http://127.0.0.1:9000'
            OBJECT_STORAGE_S3_ACCESS_KEY = 'djzy'
            OBJECT_STORAGE_S3_SECRET_KEY = 'djzy-minio'
            OBJECT_STORAGE_S3_BUCKET     = 'doctor-assistant'
        }
    } else {
        $storageEnv = @{
            OBJECT_STORAGE_PROVIDER = 'local'
            OBJECT_STORAGE_ROOT_DIR = (Join-Path $runDir 'object-storage')
        }
    }

    $eventLogDir = Join-Path $runDir 'agent\eventlog'
    $workspaceDir = Join-Path $runDir 'agent\workspace'

    # 每个服务要的环境变量写在各自那一块里。名字带前缀看着啰嗦，但好处是「这个值是谁的」一眼能看出来
    # ——数据库地址、密钥在四个服务里叫法都不一样，混在一起最容易配错。
    $services = @(
        @{
            Name = 'gateway'; Module = 'gateway'; Port = $GatewayPort
            Env = @{
                GATEWAY_DB_URL                    = $dbUrl
                GATEWAY_DB_USER                   = $DbUser
                GATEWAY_DB_PASSWORD               = $DbPassword
                GATEWAY_JWT_SECRET                = $LoginSecret
                GATEWAY_OUTBOUND_SECRET           = $GatewayOutSecret
                GATEWAY_AGENT_SERVICE_SECRET      = $AgentServiceSecret
                GATEWAY_MANAGEMENT_SERVICE_SECRET = $ManagementSecret
                # 本机没有内网 DNS，下游只有接口服务一个，所以模板直接写死「本机 + 接口服务端口」。
                # 有多个下游时，这里要换成能按服务名解析的地址（见 gateway 的 application.yml）。
                GATEWAY_SERVICE_URL_TEMPLATE      = "http://127.0.0.1:$IfacePort"
            }
        }
        @{
            Name = 'interface-doctor'; Module = 'interface-doctor'; Port = $IfacePort
            Env = @{
                SERVER_PORT                 = "$IfacePort"
                IFACE_DOCTOR_DB_URL         = $dbUrl
                IFACE_DOCTOR_DB_USER        = $DbUser
                IFACE_DOCTOR_DB_PASSWORD    = $DbPassword
                IFACE_DOCTOR_GATEWAY_SECRET = $GatewayOutSecret
            }
        }
        @{
            Name = 'management-service'; Module = 'management-service'; Port = $ManagementPort
            Env = @{
                SERVER_PORT            = "$ManagementPort"
                MANAGEMENT_DB_URL      = $dbUrl
                MANAGEMENT_DB_USER     = $DbUser
                MANAGEMENT_DB_PASSWORD = $DbPassword
                MANAGEMENT_JWT_SECRET  = $LoginSecret
                MANAGEMENT_LLM_KEK     = $LlmKek
            } + $storageEnv
        }
        @{
            Name = 'agent-service'; Module = 'agent-service/web'; Port = $AgentPort
            Env = @{
                SERVER_PORT            = "$AgentPort"
                AGENT_DB_URL           = $dbUrl
                AGENT_DB_USER          = $DbUser
                AGENT_DB_PASSWORD      = $DbPassword
                AGENT_JWT_SECRET       = $LoginSecret
                AGENT_SERVICE_SECRET   = $AgentServiceSecret
                # 留空 = 不接数据面（模型调不到任何工具）。指到网关，模型才能用上注册过的接口。
                AGENT_GATEWAY_URL      = "http://127.0.0.1:$GatewayPort"
                AGENT_RUNTIME_ID       = $Runtime
                # 准入闸门：运行时 id 必须在清单里才会被选中（见 RuntimeRegistry）。
                AGENT_TCK_PASSED       = 'noop,agentscope'
                AGENT_LLM_KEK          = $LlmKek
                AGENT_INSTANCE_ID      = 'local-a'
                AGENT_EVENT_LOG_DIR    = $eventLogDir
                AGENT_WORKSPACE_DIR    = $workspaceDir
                # 工作区放共享库（PG）：多实例才能看到同一份；技能按用户隔离也靠它。
                # 它依赖 V17 建的表 agentscope_store——表不在就会直接启动失败（这是刻意的失败方式）。
                AGENT_WORKSPACE_STORE  = 'jdbc'
                # 把「这个用户可见的技能」下发到他的工作区，模型才看得到技能。
                AGENT_WORKSPACE_SKILLS = 'workspace'
                # 跨副本实时总线：Redis（多实例续看本轮靠它；单实例也照跑同一份代码）。
                AGENT_LIVE_BUS         = 'redis'
            } + $storageEnv
        }
    )

    Write-Step 3 5 '启动后端（4 个）'
    Write-Host ("   日志目录：" + $logDir) -ForegroundColor DarkGray
    if ($ShowWindows) { Write-Host '   -ShowWindows：每个服务一个窗口，关窗口就等于停掉那个服务' -ForegroundColor DarkGray }

    # 每个服务起之前先把它要的变量清一遍再设：避免「上一个服务的残留值」被下一个服务吃到。
    $allKeys = @('SERVER_PORT', 'MAVEN_OPTS')
    foreach ($svc in $services) { $allKeys += $svc.Env.Keys }
    $allKeys = $allKeys | Select-Object -Unique

    $procs = @{}
    foreach ($svc in $services) {
        foreach ($key in $allKeys) { Remove-Item -Path ("Env:" + $key) -ErrorAction SilentlyContinue }
        foreach ($key in $svc.Env.Keys) { Set-Item -Path ("Env:" + $key) $svc.Env[$key] }
        # 日志统一 UTF-8：默认跟着控制台代码页走（中文机器上就是 GBK），那样日志里的中文全是乱码。
        $env:MAVEN_OPTS = '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'

        # fork=false：应用跑在 Maven 自己的 JVM 里，一个服务就一个进程，停的时候不会留下占端口的孤儿。
        $svcArgs = @('-B', '-q', '-pl', $svc.Module, 'spring-boot:run', '-Dspring-boot.run.fork=false')
        $outLog = Join-Path $logDir ($svc.Name + '.log')
        $errLog = Join-Path $logDir ($svc.Name + '.err.log')
        Remove-Item -LiteralPath $outLog, $errLog -ErrorAction SilentlyContinue

        if ($ShowWindows) {
            $procs[$svc.Name] = Start-Process -FilePath $mvn -ArgumentList $svcArgs -WorkingDirectory $root -PassThru
        } else {
            $procs[$svc.Name] = Start-Process -FilePath $mvn -ArgumentList $svcArgs -WorkingDirectory $root -PassThru `
                -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog
        }
        Write-Host ("   " + $svc.Name.PadRight(18) + " 已拉起（pid " + $procs[$svc.Name].Id + "）") -ForegroundColor DarkGray
    }

    Write-Step 4 5 '等服务就绪'
    foreach ($svc in $services) {
        $errLog = Join-Path $logDir ($svc.Name + '.err.log')
        try {
            Wait-Port -Name $svc.Name -Port $svc.Port -Process $procs[$svc.Name]
        } catch {
            Write-Host ''
            Write-Host ('   ' + $_.Exception.Message) -ForegroundColor Red
            Show-LogTail (Join-Path $logDir ($svc.Name + '.log')) 25
            Show-LogTail $errLog 25
            throw
        }
    }

    Write-Host '   探活（端口通 ≠ 能用，所以再真打几个接口）：' -ForegroundColor DarkGray

    # 1) 管理端登录：证明它能连上库、种子账号在、令牌密钥配对。
    $login = Invoke-Api -Method POST -Url "http://127.0.0.1:$ManagementPort/v1/auth/login" -Body @{ username = 'admin'; password = 'admin123' }
    if ($login.Status -ne 200) {
        Write-Host ('   管理端登录失败（HTTP ' + $login.Status + '）') -ForegroundColor Red
        Show-LogTail (Join-Path $logDir 'management-service.log') 25
        throw '管理端起来了但登录不通，先看上面日志。'
    }
    Write-Host '   管理端登录         OK（admin）' -ForegroundColor Green

    # 2) agent 建会话：这一条同时证明「令牌密钥三边一致」和「agent 能读 sys_user」。
    $token = ($login.Text | ConvertFrom-Json).token
    $session = Invoke-Api -Method POST -Url "http://127.0.0.1:$AgentPort/v1/agent/sessions" -Token $token -Body @{}
    if ($session.Status -ne 200) {
        Write-Host ('   建会话失败（HTTP ' + $session.Status + '）') -ForegroundColor Red
        Show-LogTail (Join-Path $logDir 'agent-service.log') 25
        Show-LogTail (Join-Path $logDir 'agent-service.err.log') 25
        throw 'agent-service 起来了但建会话不通，先看上面日志。'
    }
    Write-Host '   agent 建会话        OK（令牌三边一致）' -ForegroundColor Green

    # 3) 网关与接口服务：不带服务间签名时必须被拒（401）。能返回 401 才说明它们真的在服务。
    $gatewayProbe = Invoke-Api -Method GET -Url "http://127.0.0.1:$GatewayPort/v1/permission/capabilities"
    if ($gatewayProbe.Status -ne 401) { throw "网关探活异常：不带签名应为 401，实际 $($gatewayProbe.Status)。" }
    Write-Host '   网关验签           OK（无签名即 401）' -ForegroundColor Green

    $ifaceProbe = Invoke-Api -Method POST -Url "http://127.0.0.1:$IfacePort/doctor/performance" -Body @{}
    if ($ifaceProbe.Status -ne 401) { throw "接口服务探活异常：不经网关应为 401，实际 $($ifaceProbe.Status)。" }
    Write-Host '   接口服务验签       OK（不经网关即 401）' -ForegroundColor Green

    # -----------------------------------------------------------------------
    # 前端
    # -----------------------------------------------------------------------

    Write-Step 5 5 '前端'
    $webDir = Join-Path $root 'web'
    if ($SkipWeb) {
        Write-Host '   跳过（-SkipWeb）' -ForegroundColor DarkGray
    } elseif (-not (Test-Path -LiteralPath $webDir)) {
        Write-Host '   没有 web 目录，跳过' -ForegroundColor Yellow
        $SkipWeb = $true
    } else {
        $npm = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
        if (-not $npm) {
            Write-Host '   找不到 npm（前端要 Node）：只起了后端，接口可以用 demo\*.ps1 直接测' -ForegroundColor Yellow
            $SkipWeb = $true
        } else {
            if (-not (Test-Path -LiteralPath (Join-Path $webDir 'node_modules'))) {
                Write-Host '   前端依赖没装过，先 npm install（要联网，可能要一两分钟）' -ForegroundColor DarkGray
                $install = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', 'npm', 'install') `
                    -WorkingDirectory $webDir -NoNewWindow -Wait -PassThru
                if ($install.ExitCode -ne 0) { throw "npm install 失败（退出码 $($install.ExitCode)）。" }
            }
            $webLog = Join-Path $logDir 'web.log'
            $webErr = Join-Path $logDir 'web.err.log'
            Remove-Item -LiteralPath $webLog, $webErr -ErrorAction SilentlyContinue
            # 交给 cmd 执行：npm 是 .cmd，重定向输出时 Start-Process 不能直接跑它。
            $procs['web'] = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', 'npm', 'run', 'dev', '--', '--host', '127.0.0.1') `
                -WorkingDirectory $webDir -PassThru -WindowStyle Hidden -RedirectStandardOutput $webLog -RedirectStandardError $webErr
            try {
                Wait-Port -Name 'web' -Port $WebPort -Process $procs['web'] -TimeoutSec 120
            } catch {
                Write-Host ('   ' + $_.Exception.Message) -ForegroundColor Red
                Show-LogTail $webErr 25
                throw
            }
        }
    }

    # -----------------------------------------------------------------------
    # 汇总
    # -----------------------------------------------------------------------

    Write-Host ''
    Write-Host '=== 全部就绪 ===' -ForegroundColor Green
    if (-not $SkipWeb) { Write-Host ("   页面      " + "http://127.0.0.1:$WebPort") }
    Write-Host ("   网关      " + "http://127.0.0.1:$GatewayPort")
    Write-Host ("   对话服务  " + "http://127.0.0.1:$AgentPort")
    Write-Host ("   管理端    " + "http://127.0.0.1:$ManagementPort")
    Write-Host ("   接口服务  " + "http://127.0.0.1:$IfacePort")
    Write-Host '   账号      admin / admin123（管理员）、alice / alice123'
    if ($Runtime -eq 'agentscope') {
        Write-Host '   ⚠ 第一次跑要先去「管理端 - 模型供应商」启用一个模型（没配的话对话会提示未配置）。' -ForegroundColor Yellow
    } else {
        Write-Host '   当前是 noop 运行时：不调真模型，用来验证链路。' -ForegroundColor DarkGray
    }
    Write-Host ("   日志      " + $logDir)
    Write-Host '   停止      stop-local.bat（仓库根目录）' -ForegroundColor DarkGray

    if (-not $SkipWeb) { Start-Process "http://127.0.0.1:$WebPort" | Out-Null }
    exit 0
} catch {
    Write-Host ''
    Write-Host ('启动中断：' + $_.Exception.Message) -ForegroundColor Red
    Write-Host ("日志在 " + $logDir + "；停掉已起的服务用 stop-local.bat。") -ForegroundColor DarkGray
    exit 1
}
