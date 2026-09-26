<#
两个 agent-service 实例同时在线的冒烟脚本（T1-10）。

它验的是「多副本部署」这件事本身，而不是模型答得对不对：

  B 换台机器接着聊 —— B 实例能看到 A 建的会话；B 能换券、能读历史
                      （跨实例换券从前会因为「本机没有轮次号」直接失败，这里专门钉住它）；
  A 断线重连      —— 拿 B 发的券到 A 上接流，连接必须**正常收尾**
                      （不能挂成一条永远不说话的连接：那等于用户永远看不到结果）；

  A 真的跑一轮   —— 跑完后共享 Redis 上必须能看到这一轮在总线上留下的条目；
                      这一条验的是 H-01「发布」这一半在真进程里真的走了（前三项只验到装配与读侧）。
  C 会话还在      —— 会话与历史都在共享存储（PG + Redis）里，和「哪台实例建的」无关。

  工作区共享      —— 两台实例都以 AGENT_WORKSPACE_STORE=jdbc 启动：装配日志里必须打出
                      「工作区存储：共享库」，且实例能起来。
                      这一条同时钉住两件事：装配真的接上了共享工作区（H-04），
                      以及 PG 里那张表 agentscope_store 在（表不存在时实例会直接启动失败）。
 跨实例的**实时**通路（H-01）默认就是 Redis 总线，所以这个脚本同时也在验「双实例各自把
 RedisMessageBus 装起来」这件事本身：总线在别的实例上装不起来，两台进程里就会有一台起不来。

为什么用 noop 运行时：这里验的是平台自己的协调逻辑（共享存储、一次性券、轮次状态），
模型那一段由 agent-service 的集成测试与运行时 TCK 覆盖；冒烟脚本不该依赖外部模型服务。

前置条件：本机 PostgreSQL（5432）与 Redis（6379）已启动，且**已经跑过 deploy/migrations 下的建表脚本**
（含 V17__workspace_store.sql——工作区共享那一条要用它）；数据库口令通过 -DbPassword 或环境变量 AGENT_DB_PASSWORD 传入。

用法：
  .\two-instance-smoke.ps1 -JwtSecret 'dev-smoke-secret' -DbPassword '<本机 PG 口令>'
  .\two-instance-smoke.ps1 -JwtSecret 'dev-smoke-secret' -DbPassword '...' -SkipBuild
#>
param(
    # 与两个实例的 AGENT_JWT_SECRET 相同即可（本地冒烟用，不是生产密钥）
    [Parameter(Mandatory = $true)][string]$JwtSecret,
    [int]$PortA = 8081,
    [int]$PortB = 8082,
    # 用哪个账号冒烟：V4 迁移里种了 alice（active）
    [string]$UserId = 'alice',
    [string]$DbUser = 'assistant',
    [string]$DbPassword = $env:AGENT_DB_PASSWORD,
    # 已经 install 过就加上它，省一次全量构建
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source
if (-not $mvn) { $mvn = 'D:\maven\apache-maven-3.9.6-bin\apache-maven-3.9.6\bin\mvn.cmd' }
if (-not (Test-Path -LiteralPath $mvn)) { throw "找不到 maven：$mvn" }

$runDir = Join-Path $PSScriptRoot '.smoke'
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

function Test-TcpPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        return $async.AsyncWaitHandle.WaitOne(500) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function New-DevToken([string]$Subject, [string]$Secret) {
    $now = [DateTimeOffset]::UtcNow
    $header = '{"alg":"HS256","typ":"JWT"}'
    $payload = '{"sub":"' + $Subject + '","iat":' + $now.ToUnixTimeSeconds() + ',"exp":' + $now.AddMinutes(10).ToUnixTimeSeconds() + ',"jti":"' + [Guid]::NewGuid() + '"}'
    $b64 = { param([byte[]]$bytes) [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_') }
    $head = & $b64 ([Text.Encoding]::UTF8.GetBytes($header))
    $body = & $b64 ([Text.Encoding]::UTF8.GetBytes($payload))
    $signingInput = "$head.$body"
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($Secret)
    $sig = & $b64 ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($signingInput)))
    return "$signingInput.$sig"
}

function Invoke-Api([string]$Method, [string]$Url, [string]$Token, $Body = $null, [int]$TimeoutSec = 30) {
    $params = @{
        Uri             = $Url
        Method          = $Method
        Headers         = @{ Authorization = "Bearer $Token" }
        UseBasicParsing = $true
        TimeoutSec      = $TimeoutSec
    }
    if ($null -ne $Body) {
        $params['Body'] = ($Body | ConvertTo-Json -Compress)
        $params['ContentType'] = 'application/json'
    }
    try {
        $response = Invoke-WebRequest @params
        return @{ ok = $true; status = [int]$response.StatusCode; body = $response.Content }
    } catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        return @{ ok = $false; status = $status; body = $_.Exception.Message }
    }
}

function Start-Instance([string]$name, [int]$port, [string]$instanceId) {
    $dir = Join-Path $runDir $name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    # 环境变量在 Start-Process 启动子进程的那一刻被继承，所以两台实例可以各带一套
    $env:SERVER_PORT = "$port"
    $env:AGENT_INSTANCE_ID = $instanceId
    $env:AGENT_JWT_SECRET = $JwtSecret
    $env:AGENT_RUNTIME_ID = 'noop'
    # 明确钉成 redis：两条实例共用一个 Redis，跨实例总线才成立（不写就会吃到本机环境变量）
    $env:AGENT_LIVE_BUS = 'redis'
    $env:AGENT_DB_USER = $DbUser
    $env:AGENT_DB_PASSWORD = $DbPassword
    $env:AGENT_EVENT_LOG_DIR = (Join-Path $dir 'eventlog')
    $env:AGENT_WORKSPACE_DIR = (Join-Path $dir 'workspace')
    # 工作区共享（H-04）：两台实例的工作区放到同一个库里，谁写谁读都看得见。
    # 表不存在时应用会直接启动失败（下面的 Wait-Instance 就会抛），这正是我们要的失败方式。
    $env:AGENT_WORKSPACE_STORE = 'jdbc'    # fork=false：应用跑在 Maven 自己的 JVM 里，停的时候停一个进程就够，不会留下孤儿
    $spArgs = @{
        FilePath               = $mvn
        ArgumentList           = @('-B', '-q', '-pl', 'agent-service/web', 'spring-boot:run', '-Dspring-boot.run.fork=false')
        WorkingDirectory       = $root
        PassThru               = $true
        WindowStyle            = 'Hidden'
        RedirectStandardOutput = (Join-Path $dir 'stdout.log')
        RedirectStandardError  = (Join-Path $dir 'stderr.log')
    }
    return Start-Process @spArgs
}

function Wait-Instance([string]$name, [int]$port, [string]$Token, $Process) {
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        if ($Process.HasExited) { throw "$name 提前退出了，日志见 $runDir\$name\stderr.log" }
        if (Test-TcpPort $port) {
            # 端口通了不等于接口就绪（Spring 还在装配），真正能建会话才算起来
            $probe = Invoke-Api 'POST' "http://127.0.0.1:$port/v1/agent/sessions" $Token @{}
            if ($probe.status -eq 200) { return $probe.body }
        }
        Start-Sleep -Milliseconds 800
    }
    throw "$name 在 180 秒内没起来，日志见 $runDir\$name\stdout.log"
}

# 停实例必须**连子进程一起停**：spring-boot:run 会把应用 fork 成独立的 JVM（子进程），
# 只杀 Maven 那个进程会留下一个还占着端口的孤儿。更糟的是：孤儿的 stdout 还挂着脚本的输出管道，
# 于是「脚本早就跑完了、调用方却永远等不到输出」——这一条是真跑一次才暴露出来的。
function Stop-Instance($Process) {
    if (-not $Process) { return }
    & taskkill /PID $Process.Id /T /F 2>&1 | Out-Null   # /T：连子进程一起；/F：强制
    Start-Sleep -Milliseconds 300
    if (-not $Process.HasExited) { Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue }
}

# 兜底：端口还占着就按端口找 PID 再杀一次（防止 fork 出来的 JVM 逃过进程树）
function Stop-PortOwner([int]$Port) {
    $listeners = netstat -ano | Select-String -Pattern (':' + $Port + '\s+\S+\s+LISTENING')
    foreach ($line in $listeners) {
        $owner = 0
        $text = ($line.Line -split '\s+')[-1]
        if ([int]::TryParse($text, [ref]$owner) -and $owner -gt 0) {
            Write-Host "端口 $Port 仍被 PID $owner 占用，补杀一次"
            & taskkill /PID $owner /T /F 2>&1 | Out-Null
        }
    }
}

function Get-RedisReply([string[]]$Command) {
    # 只用得着 SCAN 一条命令，所以不必引第三方库或 redis-cli：按 RESP 协议拼一个数组命令发过去，
    # 再把回复原样读回来。键的空间很小（这一轮刚写、还带 TTL），一次读就能读完。
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', 6379)
        $stream = $client.GetStream()
        $payload = "*$($Command.Count)`r`n"
        foreach ($part in $Command) { $payload += "`$$($part.Length)`r`n$part`r`n" }
        $bytes = [Text.Encoding]::UTF8.GetBytes($payload)
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
        Start-Sleep -Milliseconds 250
        $buf = New-Object byte[] 65536
        $read = $stream.Read($buf, 0, $buf.Length)
        return [Text.Encoding]::UTF8.GetString($buf, 0, $read)
    } finally {
        $client.Close()
    }
}

$results = New-Object System.Collections.Generic.List[object]
function Add-Result([string]$step, [string]$expect, [string]$actual, [bool]$pass) {
    $mark = if ($pass) { 'PASS' } else { 'FAIL' }
    $results.Add([pscustomobject]@{ 步骤 = $step; 期望 = $expect; 实际 = $actual; 结果 = $mark })
    Write-Host ("[{0}] {1} -> {2}" -f $mark, $step, $actual)
}

if (-not (Test-TcpPort 5432)) { throw 'PostgreSQL (5432) 没在跑，先起本机 PG' }
if (-not (Test-TcpPort 6379)) { throw 'Redis (6379) 没在跑，先起本机 Redis' }
if ([string]::IsNullOrWhiteSpace($DbPassword)) { throw '缺少数据库口令：用 -DbPassword 或环境变量 AGENT_DB_PASSWORD 传入' }

if (-not $SkipBuild) {
    Write-Host '构建（mvn -DskipTests install）...'
    & $mvn -B -q -DskipTests install
    if ($LASTEXITCODE -ne 0) { throw '构建失败，先修构建再跑冒烟' }
}

$token = New-DevToken $UserId $JwtSecret
$procA = $null
$procB = $null
try {
    Write-Host "启动实例 A（端口 $PortA）与 B（端口 $PortB）..."
    $procA = Start-Instance 'a' $PortA 'smoke-a'
    $procB = Start-Instance 'b' $PortB 'smoke-b'
    $created = Wait-Instance 'A' $PortA $token $procA
    $null = Wait-Instance 'B' $PortB $token $procB

    # 工作区共享（H-04）：装配日志里必须打出「共享库」——只看接口看不出这件事（工作区平面现在是关着的，
    # 读日志**必须带 -Encoding UTF8**：Windows PowerShell 默认按本地代码页读，中文会变成乱码、匹配不上。
    # 没有业务接口会去读写它），所以这里看的是**装配时自己打的那一行**。
    $logA = Get-Content -LiteralPath (Join-Path $runDir 'a\stdout.log') -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    $logB = Get-Content -LiteralPath (Join-Path $runDir 'b\stdout.log') -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    $sharedA = [bool]($logA -match '工作区存储：共享库')
    $sharedB = [bool]($logB -match '工作区存储：共享库')
    $sharedActual = $(if ($sharedA -and $sharedB) { '两台实例都打出「工作区存储：共享库」' } else { "A=$sharedA B=$sharedB" })
    Add-Result '工作区共享存储（H-04）' '两台实例都装配成共享库（表 agentscope_store）' $sharedActual ($sharedA -and $sharedB)

    $sessionId = ($created | ConvertFrom-Json).sessionId
    Add-Result 'A 建会话' '200 且带 sessionId' "sessionId=$sessionId" (-not [string]::IsNullOrWhiteSpace($sessionId))

    # B 成立：会话属于共享存储，A 建的会话在 B 上照样在
    $listB = Invoke-Api 'GET' "http://127.0.0.1:$PortB/v1/agent/sessions" $token
    $sessionIds = @()
    if ($listB.ok) { $sessionIds = @(($listB.body | ConvertFrom-Json).sessions.sessionId) }
    Add-Result 'B 看到 A 建的会话（B/C）' '列表里包含该会话' "HTTP $($listB.status)" ($sessionIds -contains $sessionId)

    # 跨实例换券：这一条从前会直接失败（本机没有轮次号）
    $ticketResp = Invoke-Api 'POST' "http://127.0.0.1:$PortB/v1/agent/sessions/$sessionId/tickets" $token @{}
    $ticket = $null
    if ($ticketResp.ok) { $ticket = ($ticketResp.body | ConvertFrom-Json).ticket }
    Add-Result 'B 换券（跨实例）' '200 且拿到 ticket' "HTTP $($ticketResp.status)" ($ticketResp.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($ticket))

    $historyB = Invoke-Api 'GET' "http://127.0.0.1:$PortB/v1/agent/sessions/$sessionId/turns" $token
    Add-Result 'B 读历史' '200' "HTTP $($historyB.status)" ($historyB.status -eq 200)

    if ($ticket) {
        # 拿 B 发的券去 A 接流：A 本机没有这一轮，必须**立刻正常收尾**而不是挂着
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $stream = Invoke-Api 'GET' "http://127.0.0.1:$PortA/v1/agent/chat/stream?ticket=$ticket" $token $null 20
        $sw.Stop()
        $elapsedMs = [int]$sw.Elapsed.TotalMilliseconds
        Add-Result 'A 用 B 的券接流（本机没有这一轮）' '20 秒内收尾，不空挂' "HTTP $($stream.status)，耗时 $elapsedMs ms" ($stream.status -eq 200 -and $elapsedMs -lt 20000)
    }

    # 前面几项都还没真的「跑一轮」，验的是装配；这一项真的跑一轮，再回头看共享 Redis：
    # 跑这一轮的那台机器必须把自己产出的每条事件都记到总线上（H-01 的发布这一半）。
    # 为什么不看接口：走接口时「从总线实时读到」与「等跑完整段补」给用户的画面是一样的，
    # 只有直接看键，才能证明发布这条路真的走了，而不是碰巧走了兜底。
    $slot = "$UserId`:$sessionId"
    $turnResp = Invoke-Api 'POST' "http://127.0.0.1:$PortA/v1/agent/sessions/$sessionId/turns" $token @{ text = 'hello' }
    $turnTicket = $null
    if ($turnResp.ok) { $turnTicket = ($turnResp.body | ConvertFrom-Json).ticket }
    Add-Result 'A 起一轮（真的跑）' '200 且拿到 ticket' "HTTP $($turnResp.status)" ($turnResp.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($turnTicket))
    if ($turnTicket) {
        $run = Invoke-Api 'GET' "http://127.0.0.1:$PortA/v1/agent/chat/stream?ticket=$turnTicket" $token $null 30
        Add-Result '这一轮在 A 上跑完' '200' "HTTP $($run.status)" ($run.status -eq 200)
        $reply = Get-RedisReply @('SCAN', '0', 'MATCH', "agent-service:bus:*$slot*", 'COUNT', '1000')
        $onBus = $reply -match [regex]::Escape($slot)
        $busActual = if ($onBus) { "共享总线里有这一轮的条目（$slot）" } else { '共享总线上没有找到这一轮的条目' }
        Add-Result '事件进了共享总线（H-01 发布）' 'Redis 里有该会话的总线条目' $busActual $onBus
    }

    $stop = Invoke-Api 'POST' "http://127.0.0.1:$PortA/v1/agent/sessions/$sessionId/stop" $token @{}
    Add-Result 'A 停止（幂等）' '200' "HTTP $($stop.status)" ($stop.status -eq 200)
} finally {
    Stop-Instance $procA
    Stop-Instance $procB
    Stop-PortOwner $PortA
    Stop-PortOwner $PortB
}

Write-Host ''
$results | Format-Table -AutoSize | Out-String | Write-Host
$failed = @($results | Where-Object { $_.结果 -eq 'FAIL' }).Count
Write-Host ("冒烟结果：{0} 项通过，{1} 项失败（日志目录 {2}）" -f ($results.Count - $failed), $failed, $runDir)
if ($failed -gt 0) { exit 1 }
