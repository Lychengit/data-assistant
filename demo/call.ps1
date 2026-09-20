<#
.SYNOPSIS
  只测数据面：拿用户令牌 + 服务签名直接打网关，不经过模型。

.DESCRIPTION
  走的正是 agent-service 调接口的那条路：登录拿用户令牌 → 签名 → `POST /v1/gateway/api-call`。
  网关在这里做 G1 身份 → G2 查注册行 → G3 权限判定（授权到没授权到这个接口）→ G4 熔断 → G5 记账，
  然后把 `{caller, args}` 信封转给接口服务。

  什么时候用这条：页面上「模型答得不对」时，先用它把**数据面**和**模型面**切开——
  数据面通、页面不通 ⇒ 问题在工具 schema / 系统提示词；数据面也不通 ⇒ 看网关与接口服务日志
  （接口服务的「不支持的参数 / 缺少必填参数 / 未授权」会直接告诉你是哪一项对不上）。

  为什么必须带签名：网关对 `/v1/*` 全挂了服务间验签，裸 curl 一律 401（§20.1.3）。
  签名串 = v1 / 方法大写 / 路径 / query（字典序）/ 时间戳 / nonce / SHA-256(请求体)，
  `X-Signature` 的形状是 `v1:` + Base64(HMAC-SHA256)。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File demo/call.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File demo/call.ps1 -DeptCode 心内科
  powershell -NoProfile -ExecutionPolicy Bypass -File demo/call.ps1 -Username admin -Password admin123 `
      -HttpPath /doctor/list -DeptCode 呼吸科
#>
param(
  [string]$GatewayUrl = "http://127.0.0.1:8080",
  [string]$ManagementUrl = "http://127.0.0.1:8082",
  [string]$Username = "alice",
  [string]$Password = "alice123",
  [string]$KeyId = "agent-service",
  [string]$SharedSecret = "local-agent-service-secret",
  [string]$Service = "interface-doctor",
  [string]$HttpMethod = "POST",
  [string]$HttpPath = "/doctor/performance",
  [string]$ArgsJson = "",
  [string]$Month = "",
  [string]$MetricKey = "",
  [string]$DeptCode = "",
  [string]$DoctorId = "",
  [string]$SkillCode = "",
  [string]$ConfirmId = "",
  [switch]$NoDefaults
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(60)

# 签名串口径 §20.1.3：v1 / 方法大写 / 路径 / query（字典序）/ 时间戳 / nonce / SHA-256(请求体)
# 请求体摘要必须在签名串里：网关下发的 userId 就在请求体里，改一个字签名就对不上。
function Get-SignatureHeaders([string]$method, [string]$path, [string]$query, [byte[]]$bodyBytes) {
  $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
  $nonce = -join (1..32 | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
  $sha256 = [System.Security.Cryptography.SHA256]::Create()
  try { $bodyHash = ($sha256.ComputeHash($bodyBytes) | ForEach-Object { $_.ToString('x2') }) -join '' }
  finally { $sha256.Dispose() }
  $canonical = @("v1", $method.ToUpper(), $path, $query, "$timestamp", $nonce, $bodyHash) -join "`n"
  $hmac = New-Object System.Security.Cryptography.HMACSHA256
  try {
    $hmac.Key = $utf8NoBom.GetBytes($SharedSecret)
    # X-Signature 的形状是 "v1:" + Base64(HMAC-SHA256)，不是 hex —— 写错会得到 401 INVALID_SIGNATURE_FORMAT
    $signature = "v1:" + [Convert]::ToBase64String($hmac.ComputeHash($utf8NoBom.GetBytes($canonical)))
  } finally { $hmac.Dispose() }
  return @{
    "X-Api-Key"   = $KeyId
    "X-Timestamp" = "$timestamp"
    "X-Nonce"     = $nonce
    "X-Signature" = $signature
  }
}

# 1) 登录（令牌只走 Authorization 头，§19.4）
$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress
$loginReq = New-Object System.Net.Http.HttpRequestMessage("POST", "$ManagementUrl/v1/auth/login")
$loginReq.Content = New-Object System.Net.Http.StringContent($loginBody, $utf8NoBom, "application/json")
$loginResp = $client.SendAsync($loginReq).Result
if (-not $loginResp.IsSuccessStatusCode) {
  throw "登录失败：HTTP $([int]$loginResp.StatusCode)（停用账号会停在这一步）"
}
$token = ($loginResp.Content.ReadAsStringAsync().Result | ConvertFrom-Json).token
Write-Host "[login] $Username 登录成功" -ForegroundColor DarkGray

# 2) 组请求体：身份三元组只由调用方声明，网关会拿它查注册行，再用注册行里的值拼下游地址
# 入参用**具名参数**拼，而不是让调用方传一段 JSON：经 `powershell -File` 传带引号的 JSON 时，
# 引号会被参数解析吃掉（表现为 ConvertFrom-Json: Invalid JSON primitive）。Chinese 值直接写就行。
$args = [ordered]@{}
if ($Month)     { $args["month"] = $Month }
if ($MetricKey) { $args["metric_key"] = $MetricKey }
if ($DeptCode)  { $args["dept_code"] = $DeptCode }
if ($DoctorId)  { $args["doctor_id"] = $DoctorId }
if ($ArgsJson) {
  ($ArgsJson | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $args[$_.Name] = $_.Value }
}
# 只补「本接口有、而调用方没给」的必填项：漏掉 month/metric_key 会被接口服务按 I3 拒成 400，
# 那样 `-DeptCode 心内科` 看上去就像探针坏了。想验必填校验就加 -NoDefaults。
if (-not $NoDefaults -and $HttpPath -eq "/doctor/performance") {
  if (-not $args.Contains("month"))      { $args["month"] = "2026-08" }
  if (-not $args.Contains("metric_key")) { $args["metric_key"] = "outpatient_visits" }
}
$body = [ordered]@{
  service    = $Service
  httpMethod = $HttpMethod
  httpPath   = $HttpPath
  skillCode  = if ($SkillCode) { $SkillCode } else { $null }
  confirmId  = if ($ConfirmId) { $ConfirmId } else { $null }
  requestId  = [guid]::NewGuid().ToString()
  args       = $args
} | ConvertTo-Json -Compress -Depth 8
$bodyBytes = $utf8NoBom.GetBytes($body)

# 3) 签名 + 调用（先字节签名，再把同一串字节发出去——重新序列化会让签名对不上）
$path = "/v1/gateway/api-call"
$req = New-Object System.Net.Http.HttpRequestMessage("POST", "$GatewayUrl$path")
$req.Headers.Add("Authorization", "Bearer $token")
(Get-SignatureHeaders "POST" $path "" $bodyBytes).GetEnumerator() |
  ForEach-Object { $req.Headers.Add($_.Key, $_.Value) }
$req.Content = New-Object System.Net.Http.ByteArrayContent($bodyBytes, 0, $bodyBytes.Length)
$req.Content.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue("application/json")
$resp = $client.SendAsync($req).Result
$text = $resp.Content.ReadAsStringAsync().Result

Write-Host "[call] $Username → $Service $HttpMethod $HttpPath" -ForegroundColor Cyan
Write-Host "[call] args = $(if ($args.Count) { $args | ConvertTo-Json -Compress } else { "（无）" })" -ForegroundColor DarkGray
Write-Host "[call] HTTP $([int]$resp.StatusCode)" -ForegroundColor $(if ($resp.IsSuccessStatusCode) { "Green" } else { "Red" })
try { Write-Output ($text | ConvertFrom-Json | ConvertTo-Json -Depth 8) } catch { Write-Output $text }