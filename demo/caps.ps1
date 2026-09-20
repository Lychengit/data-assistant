<#
.SYNOPSIS
  看一眼「agent 此刻能用的接口说明书」——带签名的 capabilities 探针，不经过模型。

.DESCRIPTION
  这件事本身很小：登录拿用户令牌 → 带 ServiceSigner 口径的签名打网关
  `GET /v1/permission/capabilities` → 把返回的接口清单打印出来。

  为什么值得固化成脚本：网关对 `/v1/*` 全挂了服务间验签过滤器，**裸 curl 一律 401**，
  所以「agent 到底看到了哪些接口、每个接口的入参长什么样」这件事用手是试不出来的。
  这里打印的就是 agent-service 每轮真实拿到的那一份（含 `scenario` 适用场景、
  `paramSchema` 入参含义、`resultSchema` 返回字段）——模型选错接口或参数时，先看这份。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File demo/caps.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File demo/caps.ps1 -Username admin -Password admin123
#>
param(
  [string]$GatewayUrl = "http://127.0.0.1:8080",
  [string]$ManagementUrl = "http://127.0.0.1:8082",
  [string]$Username = "alice",
  [string]$Password = "alice123",
  [string]$KeyId = "agent-service",
  [string]$SharedSecret = "local-agent-service-secret",
  [switch]$Raw
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(30)

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
    # X-Signature 的形状是 "v1:" + Base64(HMAC-SHA256)，不是 hex —— 口径由 SignatureHeaders.SIGNATURE_PREFIX 定死，写错会得到 401 INVALID_SIGNATURE_FORMAT
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

# 2) 签名 + 调用
$path = "/v1/permission/capabilities"
$req = New-Object System.Net.Http.HttpRequestMessage("GET", "$GatewayUrl$path")
$req.Headers.Add("Authorization", "Bearer $token")
(Get-SignatureHeaders "GET" $path "" (New-Object byte[] 0)).GetEnumerator() |
  ForEach-Object { $req.Headers.Add($_.Key, $_.Value) }
$resp = $client.SendAsync($req).Result
$body = $resp.Content.ReadAsStringAsync().Result
if (-not $resp.IsSuccessStatusCode) {
  throw "capabilities 调用失败：HTTP $([int]$resp.StatusCode) $body"
}

if ($Raw) { Write-Output $body; return }

# 3) 按「agent 看到的样子」打印
$caps = $body | ConvertFrom-Json
Write-Host "[capabilities] user=$($caps.userId) 接口数=$($caps.apis.Count) 技能数=$($caps.viewableSkills.Count)" -ForegroundColor Cyan
foreach ($api in $caps.apis) {
  $tool = "iface_" + ($api.httpPath.TrimStart('/') -replace '[^A-Za-z0-9]+', '_')
  Write-Host ""
  Write-Host "● $($api.name)  →  工具名 $tool" -ForegroundColor Yellow
  Write-Host "  身份三元组   : $($api.service) $($api.httpMethod) $($api.httpPath)"
  Write-Host "  适用场景     : $($api.scenario)"
  $props = $api.paramSchema.properties
  if ($props) {
    $required = @($api.paramSchema.required)
    Write-Host "  入参         :"
    foreach ($name in $props.PSObject.Properties.Name) {
      $spec = $props.$name
      $flag = if ($required -contains $name) { "必填" } else { "可选" }
      $type = if ($spec.type) { $spec.type } else { "?" }
      $example = if ($spec.example) { "，例：$($spec.example)" } else { "" }
      Write-Host "    - $name（$type，$flag）$($spec.description)$example"
    }
  }
  # result_schema 有两种录法：完整 {"properties":{...}}，或直接「字段名 → 说明」的扁平表。
  # 两种都要认，否则一种录法会让这里打印不出返回字段（模型那份 schema 走的是同一套兼容逻辑）。
  $resultFields = if ($api.resultSchema -and $api.resultSchema.properties) { $api.resultSchema.properties } else { $api.resultSchema }
  if ($resultFields) {
    Write-Host "  返回字段     :"
    foreach ($name in $resultFields.PSObject.Properties.Name) {
      $spec = $resultFields.$name
      $type = if ($spec -and $spec.type) { $spec.type } else { "?" }
      $desc = if ($spec -and $spec.description) { $spec.description } else { "" }
      Write-Host "    - $name（$type）$desc"
    }
  }
}
Write-Host ""