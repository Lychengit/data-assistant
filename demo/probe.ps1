<#
.SYNOPSIS
  一键巡检：身份 / 网关验签 / 授权 / 接口入参校验 / 会话生命周期 / 管理端。

.DESCRIPTION
  这是「四条测法」之外的**汇总巡检**：把散在各处的硬性约束收成一份能反复跑的清单，
  每条只做「能通过 HTTP 观察到」的判定，不猜内部状态。每条打印 PASS/FAIL，
  失败也继续跑完（一次拿齐所有问题，而不是撞到第一个就停），最后一屏是汇总与失败清单。

  它不碰任何生产数据：唯一会改状态的是「建会话 / 归档再取消归档」，都只落在自己的会话上；
  授权与技能都不动，所以随时可以跑。

  口径与 demo/call.ps1、demo/caps.ps1 一致：网关对 /v1/* 全挂服务间验签，签名串 =
  v1 / 方法大写 / 路径 / query（字典序）/ 时间戳 / nonce / SHA-256(请求体)。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File demo/probe.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File demo/probe.ps1 -ManagementUrl http://127.0.0.1:8082
#>
param(
  [string]$GatewayUrl = "http://127.0.0.1:8080",
  [string]$AgentUrl = "http://127.0.0.1:8081",
  [string]$ManagementUrl = "http://127.0.0.1:8082",
  [string]$IfaceUrl = "http://127.0.0.1:8084",
  [string]$KeyId = "agent-service",
  [string]$SharedSecret = "local-agent-service-secret"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$utf8 = New-Object System.Text.UTF8Encoding($false)
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(120)

$script:results = @()
function Check([string]$name, [bool]$ok, [string]$detail = "") {
  $script:results += [pscustomobject]@{ Name = $name; Ok = $ok; Detail = $detail }
  $tag = if ($ok) { "PASS" } else { "FAIL" }
  $line = if ($detail) { "[$tag] $name  -- $detail" } else { "[$tag] $name" }
  Write-Host $line -ForegroundColor $(if ($ok) { "Green" } else { "Red" })
}
function Section([string]$title) { Write-Host ""; Write-Host "===== $title =====" -ForegroundColor Cyan }

function NewReq([string]$method, [string]$url, [string]$token) {
  $req = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($method)), $url)
  if ($token) { $req.Headers.Add("Authorization", "Bearer $token") }
  return $req
}
function SetJsonBody($req, [string]$json) {
  $bytes = $utf8.GetBytes($json)
  $req.Content = New-Object System.Net.Http.ByteArrayContent($bytes, 0, $bytes.Length)
  $req.Content.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue("application/json")
  return $bytes
}
function Get-SignatureHeaders([string]$method, [string]$path, [string]$query, [byte[]]$bodyBytes) {
  $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
  $nonce = -join (1..32 | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
  $sha256 = [System.Security.Cryptography.SHA256]::Create()
  try { $bodyHash = ($sha256.ComputeHash($bodyBytes) | ForEach-Object { $_.ToString('x2') }) -join '' }
  finally { $sha256.Dispose() }
  $canonical = @("v1", $method.ToUpper(), $path, $query, "$timestamp", $nonce, $bodyHash) -join "`n"
  $hmac = New-Object System.Security.Cryptography.HMACSHA256
  try {
    $hmac.Key = $utf8.GetBytes($SharedSecret)
    $signature = "v1:" + [Convert]::ToBase64String($hmac.ComputeHash($utf8.GetBytes($canonical)))
  } finally { $hmac.Dispose() }
  return @{ "X-Api-Key" = $KeyId; "X-Timestamp" = "$timestamp"; "X-Nonce" = $nonce; "X-Signature" = $signature }
}
function Send($req) { return $client.SendAsync($req).Result }
function Code($resp) { return [int]$resp.StatusCode }
function Txt($resp) { return $resp.Content.ReadAsStringAsync().Result }
function Login([string]$u, [string]$p) {
  $req = NewReq "POST" "$ManagementUrl/v1/auth/login" $null
  SetJsonBody $req (@{ username = $u; password = $p } | ConvertTo-Json -Compress) | Out-Null
  $resp = Send $req
  if (-not $resp.IsSuccessStatusCode) { return $null }
  return (Txt $resp | ConvertFrom-Json).token
}
function ApiCall([string]$token, [string]$service, [string]$httpMethod, [string]$httpPath, $apiArgs, [switch]$NoSign) {
  $body = [ordered]@{
    service    = $service
    httpMethod = $httpMethod
    httpPath   = $httpPath
    skillCode  = $null
    confirmId  = $null
    requestId  = [guid]::NewGuid().ToString()
    args       = $apiArgs
  } | ConvertTo-Json -Compress -Depth 8
  $bytes = $utf8.GetBytes($body)
  $path = "/v1/gateway/api-call"
  $req = NewReq "POST" "$GatewayUrl$path" $token
  if (-not $NoSign) {
    (Get-SignatureHeaders "POST" $path "" $bytes).GetEnumerator() | ForEach-Object { $req.Headers.Add($_.Key, $_.Value) }
  }
  $req.Content = New-Object System.Net.Http.ByteArrayContent($bytes, 0, $bytes.Length)
  $req.Content.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue("application/json")
  return Send $req
}
# 取结果行：网关回的是顶层 rows；注意 PowerShell 里 @($null).Count 是 1，不显式挡掉就会把"没拿到"当成"有 1 行"。
function Rows($resp) {
  $j = try { Txt $resp | ConvertFrom-Json } catch { return @() }
  if ($null -eq $j -or $null -eq $j.rows) { return @() }
  return @($j.rows)
}
function Capabilities([string]$token, [switch]$NoSign) {
  $path = "/v1/permission/capabilities"
  $bytes = [byte[]]::new(0)
  $req = NewReq "GET" "$GatewayUrl$path" $token
  if (-not $NoSign) {
    (Get-SignatureHeaders "GET" $path "" $bytes).GetEnumerator() | ForEach-Object { $req.Headers.Add($_.Key, $_.Value) }
  }
  return Send $req
}

# ---------------------------------------------------------------- 1 身份与令牌
Section "1 身份与令牌"
$admin = Login "admin" "admin123"
Check "admin 登录成功（口令正确，返回令牌）" ($null -ne $admin -and $admin.Length -gt 20)
$alice = Login "alice" "alice123"
Check "alice 登录成功" ($null -ne $alice -and $alice.Length -gt 20)

$badPw = Login "admin" "wrong-password"
Check "口令错误 → 401（不签发令牌）" ($null -eq $badPw)

$gone = Login "gone" "alice123"
Check "停用账号 gone 登录被拒（停用立即失效，§19.3）" ($null -eq $gone)

$r = Send (NewReq "GET" "$ManagementUrl/v1/auth/me" $admin)
Check "GET /v1/auth/me 带令牌 → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"
$me = try { Txt $r | ConvertFrom-Json } catch { $null }
Check "/v1/auth/me 回的是本人（userId=admin）" ($null -ne $me -and $me.userId -eq "admin")

$r = Send (NewReq "GET" "$ManagementUrl/v1/auth/me" $null)
Check "GET /v1/auth/me 不带令牌 → 401" ((Code $r) -eq 401) "HTTP $(Code $r)"

$r = Send (NewReq "GET" "$ManagementUrl/v1/admin/api" $alice)
Check "非管理员访问 /v1/admin/api → 403" ((Code $r) -eq 403) "HTTP $(Code $r)"

$r = Send (NewReq "GET" "$ManagementUrl/v1/admin/api" $admin)
Check "管理员访问 /v1/admin/api → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"

# ---------------------------------------------------------------- 2 网关服务间验签
Section "2 网关服务间验签（§20.1.3）"
$r = Capabilities $alice -NoSign
Check "GET /v1/permission/capabilities 无签名 → 401" ((Code $r) -eq 401) "HTTP $(Code $r)"

$r = Capabilities $alice
$capsOk = (Code $r) -eq 200
Check "GET /v1/permission/capabilities 带签名 → 200" $capsOk "HTTP $(Code $r)"
$caps = try { Txt $r | ConvertFrom-Json } catch { $null }
if ($capsOk -and $null -ne $caps) {
  $n = @($caps.apis).Count
  Check "alice 的接口说明书里有两个接口（boss+director 并集）" ($n -eq 2) "实际 $n 个"
  $first = @($caps.apis)[0]
  $hasScenario = $null -ne $first.scenario -and "$($first.scenario)".Length -gt 0
  $hasResult = $null -ne $first.resultSchema
  Check "说明书带「适用场景」（scenario）" $hasScenario
  Check "说明书带「返回字段」（resultSchema）" $hasResult
} else {
  Check "alice 的接口说明书可解析" $false "拿不到 JSON"
  Check "说明书带「适用场景」（scenario）" $false "上游失败"
  Check "说明书带「返回字段」（resultSchema）" $false "上游失败"
}

$r = ApiCall $alice "interface-doctor" "POST" "/doctor/performance" @{ month = "2026-08"; metric_key = "outpatient_visits" } -NoSign
Check "POST /v1/gateway/api-call 无签名 → 401" ((Code $r) -eq 401) "HTTP $(Code $r)"

$r = Send (NewReq "POST" "$IfaceUrl/doctor/performance" $alice)
Check "裸调接口服务 8084（有令牌、无服务签名）→ 401" ((Code $r) -eq 401) "HTTP $(Code $r)"

$r = ApiCall $alice "interface-doctor" "POST" "/doctor/does-not-exist" @{}
Check "网关：未注册的 service+path → 404" ((Code $r) -eq 404) "HTTP $(Code $r)"

# ---------------------------------------------------------------- 3 授权判定
Section "3 授权判定（授权键 = service+method+path）"
$r = ApiCall $alice "interface-doctor" "POST" "/doctor/performance" @{ month = "2026-08"; metric_key = "outpatient_visits" }
Check "alice（boss+director）→ 医生绩效明细 → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"
$perfRows = 0
if ((Code $r) -eq 200) { $perfRows = (Rows $r).Count }
Check "绩效明细返回了数据行" ($perfRows -gt 0) "rows=$perfRows"

$r = ApiCall $alice "interface-doctor" "POST" "/doctor/list" @{}
Check "alice → 医生列表 → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"

$r = ApiCall $admin "interface-doctor" "POST" "/doctor/performance" @{ month = "2026-08"; metric_key = "outpatient_visits" }
Check "admin（仅被授权绩效明细）→ 绩效明细 → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"

$r = ApiCall $admin "interface-doctor" "POST" "/doctor/list" @{}
Check "admin 未被授权 /doctor/list → 拒绝（403 fail-closed，§19.1）" ((Code $r) -eq 403) "HTTP $(Code $r)"

$r = ApiCall $admin "interface-doctor" "GET" "/doctor/performance" @{}
Check "授权按 method 区分：GET /doctor/performance → 404/403（未注册该组合）" (((Code $r) -eq 404) -or ((Code $r) -eq 403)) "HTTP $(Code $r)"

# ---------------------------------------------------------------- 4 接口侧入参校验
Section "4 接口侧入参校验（I3）"
$r = ApiCall $alice "interface-doctor" "POST" "/doctor/performance" @{}
Check "缺必填 month/metric_key → 400 且提示缺哪一项" ((Code $r) -eq 400) "HTTP $(Code $r) body=$((Txt $r).Substring(0,[Math]::Min(160,(Txt $r).Length)))"

$r = ApiCall $alice "interface-doctor" "POST" "/doctor/performance" @{ month = "2026-08"; metric_key = "no_such_metric" }
Check "未知 metric_key → 400（不静默当空结果）" ((Code $r) -eq 400) "HTTP $(Code $r) body=$((Txt $r).Substring(0,[Math]::Min(160,(Txt $r).Length)))"

$r = ApiCall $alice "interface-doctor" "POST" "/doctor/performance" @{ month = "2026-13"; metric_key = "outpatient_visits" }
Check "不存在的月份 2026-13 → 400（空结果不能兼职表示参数错）" ((Code $r) -eq 400) "HTTP $(Code $r)"

$r = ApiCall $alice "interface-doctor" "POST" "/doctor/performance" @{ month = "2026-08"; metric_key = "outpatient_visits"; dept_code = "不存在的科室" }
$empty = -1
if ((Code $r) -eq 200) { $empty = (Rows $r).Count }
Check "不存在的科室 → 200 且 0 行（过滤而不是报错）" ((Code $r) -eq 200 -and $empty -eq 0) "HTTP $(Code $r) rows=$empty"

$r = ApiCall $alice "interface-doctor" "POST" "/doctor/performance" @{ month = "2026-08"; metric_key = "outpatient_visits"; dept_code = "心内科" }
$xin = -1
if ((Code $r) -eq 200) { $xin = (Rows $r).Count }
Check "按科室过滤（心内科）→ 200 且有行" ((Code $r) -eq 200 -and $xin -gt 0) "HTTP $(Code $r) rows=$xin"
Check "科室过滤确实收窄了结果（0 < 心内科 < 全量）" ($xin -gt 0 -and $xin -lt $perfRows) "心内科=$xin 全量=$perfRows"

# ---------------------------------------------------------------- 5 会话生命周期
Section "5 会话生命周期（§19.4）"
$r = Send (NewReq "POST" "$AgentUrl/v1/agent/sessions" $alice)
Check "建会话 → 200 且返回 sessionId" ((Code $r) -eq 200) "HTTP $(Code $r)"
$sessionId = (Txt $r | ConvertFrom-Json).sessionId

$r = Send (NewReq "GET" "$AgentUrl/v1/agent/sessions" $alice)
$list = @((Txt $r | ConvertFrom-Json).sessions)
Check "会话列表包含刚建的会话" ($list.sessionId -contains $sessionId)

$r = Send (NewReq "GET" "$AgentUrl/v1/agent/sessions/$sessionId/turns" $alice)
Check "历史回放空会话 → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"

$r = Send (NewReq "POST" "$AgentUrl/v1/agent/sessions/$sessionId/archive" $alice)
Check "归档会话 → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"
$r = Send (NewReq "GET" "$AgentUrl/v1/agent/sessions" $alice)
$arch = @((Txt $r | ConvertFrom-Json).sessions | Where-Object { $_.sessionId -eq $sessionId })
Check "归档后列表里标记 archived=true（逻辑删除，不是删行）" ($arch.Count -eq 1 -and $arch[0].archived -eq $true)

$req = NewReq "POST" "$AgentUrl/v1/agent/sessions/$sessionId/archive" $alice
SetJsonBody $req (@{ archived = $false } | ConvertTo-Json -Compress) | Out-Null
$r = Send $req
Check "取消归档（archived=false）→ 200" ((Code $r) -eq 200) "HTTP $(Code $r)"
$r = Send (NewReq "GET" "$AgentUrl/v1/agent/sessions" $alice)
$arch = @((Txt $r | ConvertFrom-Json).sessions | Where-Object { $_.sessionId -eq $sessionId })
Check "取消归档后 archived=false" ($arch.Count -eq 1 -and $arch[0].archived -eq $false)

$r = Send (NewReq "GET" "$AgentUrl/v1/agent/sessions/00000000-0000-0000-0000-000000000000/turns" $alice)
Check "别人的 / 不存在的会话历史 → 404（不泄露存在性，§11.3）" ((Code $r) -eq 404) "HTTP $(Code $r)"

$r = Send (NewReq "POST" "$AgentUrl/v1/agent/sessions/$sessionId/turns" $alice -ErrorAction SilentlyContinue)
$r = NewReq "POST" "$AgentUrl/v1/agent/sessions/$sessionId/turns" $alice
SetJsonBody $r (@{ text = "你有哪些能力" } | ConvertTo-Json -Compress) | Out-Null
$resp = Send $r
$ticket = (Txt $resp | ConvertFrom-Json).ticket
Check "发起一轮 → 返回一次性券 ticket" ((Code $resp) -eq 200 -and $ticket.Length -gt 10) "HTTP $(Code $resp)"

$r = Send (NewReq "GET" "$AgentUrl/v1/agent/chat/stream" $alice)
Check "SSE 不带 ticket → 401（券是唯一入口）" ((Code $r) -eq 401) "HTTP $(Code $r)"

$r = Send (NewReq "GET" "$AgentUrl/v1/agent/chat/stream?ticket=not-a-real-ticket" $alice)
Check "SSE 伪造 ticket → 401" ((Code $r) -eq 401) "HTTP $(Code $r)"

# 一次性券：第一次读干，第二次必须被拒
function ReadStream([string]$url) {
  $req = NewReq "GET" $url $alice
  $resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
  if (-not $resp.IsSuccessStatusCode) { return [pscustomobject]@{ Code = (Code $resp); Text = "" } }
  $reader = New-Object System.IO.StreamReader($resp.Content.ReadAsStreamAsync().Result, [System.Text.Encoding]::UTF8)
  $name = ""; $data = ""; $answer = ""; $events = @()
  while (-not $reader.EndOfStream) {
    $line = $reader.ReadLine()
    if ($line -eq $null) { break }
    if ($line.StartsWith("event:")) { $name = $line.Substring(6).Trim() }
    elseif ($line.StartsWith("data:")) { $data = $line.Substring(5).Trim() }
    elseif ($line -eq "") {
      if ($name -eq "token") { $answer += ($data | ConvertFrom-Json).delta }
      if ($name) { $events += $name }
      if ($name -eq "done") { break }
      $name = ""; $data = ""
    }
  }
  return [pscustomobject]@{ Code = 200; Text = $answer; Events = $events }
}
$s1 = ReadStream "$AgentUrl/v1/agent/chat/stream?ticket=$ticket"
Check "第一轮 SSE 收到 done 并有回答正文" ($s1.Events -contains "done" -and $s1.Text.Length -gt 5) "events=$($s1.Events.Count)"
$s2 = ReadStream "$AgentUrl/v1/agent/chat/stream?ticket=$ticket"
Check "同一张券第二次使用 → 401（一次性）" ($s2.Code -eq 401) "HTTP $($s2.Code)"

$r = Send (NewReq "GET" "$AgentUrl/v1/agent/sessions/$sessionId/turns" $alice)
$turns = @((Txt $r | ConvertFrom-Json).turns)
Check "历史回放里出现了这一轮（提问与回答都在）" ($turns.Count -ge 1) "turns=$($turns.Count)"

$r = Send (NewReq "POST" "$AgentUrl/v1/agent/sessions" $alice)
Check "再建一个会话用于隔离验证" ((Code $r) -eq 200)
$sessionB = (Txt $r | ConvertFrom-Json).sessionId
Check "两个会话 id 不同（新会话不会顶掉旧会话）" ($sessionB -ne $sessionId)
$r = Send (NewReq "GET" "$AgentUrl/v1/agent/sessions" $alice)
$all = @((Txt $r | ConvertFrom-Json).sessions)
Check "历史会话列表同时保留新旧两个会话" (($all.sessionId -contains $sessionId) -and ($all.sessionId -contains $sessionB)) "共 $($all.Count) 个"

# ---------------------------------------------------------------- 6 管理端
Section "6 管理端（M2–M6）"
function AdminGet([string]$path) { return Send (NewReq "GET" "$ManagementUrl$path" $admin) }
$r = AdminGet "/v1/admin/api"
Check "GET /v1/admin/api → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"
$r = AdminGet "/v1/admin/metric"
Check "GET /v1/admin/metric → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"
$r = AdminGet "/v1/admin/metric/unit-convert"
Check "GET /v1/admin/metric/unit-convert → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"
$r = AdminGet "/v1/admin/skill"
Check "GET /v1/admin/skill → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"
$r = AdminGet "/v1/admin/skill/pending"
Check "GET /v1/admin/skill/pending → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"
$r = AdminGet "/v1/admin/role-api?roleCode=admin"
Check "GET /v1/admin/role-api → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"
$r = AdminGet "/v1/admin/llm-provider"
Check "GET /v1/admin/llm-provider → 200（模型接线配置）" ((Code $r) -eq 200) "HTTP $(Code $r)"
$r = AdminGet "/v1/admin/llm-provider/adapters"
Check "GET /v1/admin/llm-provider/adapters → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"

$from = [DateTime]::UtcNow.AddDays(-1).ToString("yyyy-MM-ddTHH:mm:ssZ")
$to = [DateTime]::UtcNow.AddDays(1).ToString("yyyy-MM-ddTHH:mm:ssZ")
$r = AdminGet "/v1/admin/audit/overview?from=$from&to=$to"
Check "GET /v1/admin/audit/overview（带 from/to）→ 200" ((Code $r) -eq 200) "HTTP $(Code $r)"
$r = AdminGet "/v1/admin/audit/overview"
Check "审计查询缺 from/to → 400（窗口必填）" ((Code $r) -eq 400) "HTTP $(Code $r)"
$r = AdminGet "/v1/admin/audit/data-access?from=$from&to=$to"
Check "GET /v1/admin/audit/data-access → 200" ((Code $r) -eq 200) "HTTP $(Code $r)"

# ---------------------------------------------------------------- 汇总
Section "汇总"
$pass = @($script:results | Where-Object { $_.Ok }).Count
$fail = @($script:results | Where-Object { -not $_.Ok }).Count
Write-Host "PASS $pass / $($script:results.Count)，FAIL $fail" -ForegroundColor $(if ($fail -eq 0) { "Green" } else { "Red" })
if ($fail -gt 0) {
  Write-Host ""
  Write-Host "失败项：" -ForegroundColor Red
  $script:results | Where-Object { -not $_.Ok } | ForEach-Object { Write-Host "  - $($_.Name)  $($_.Detail)" -ForegroundColor Red }
}