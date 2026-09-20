<#
.SYNOPSIS
  打一轮真实对话（SSE），不经过浏览器。

.DESCRIPTION
  做四件事：登录拿令牌 → 建会话 → 发一轮（拿一次性入场券）→ 订阅 SSE 并打印事件。
  用途是「排查模型为什么答得不对」：工具卡会显示模型实际传的参数与结果字节数（§12.2），
  这些都是浏览器里能看到、但日志里不好找的东西。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File demo/ask.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File demo/ask.ps1 -Username alice -Password alice123 `
      -Question "上个月各科室门诊量"
  # 接着老会话问（复现「历史会话里能力说法过期」这类问题）
  powershell -NoProfile -ExecutionPolicy Bypass -File demo/ask.ps1 -Username admin -Password admin123 `
      -SessionId 66662959-fe79-43df-aa50-9aa33b69d5ba -Question "你有哪些能力"
#>
param(
  [string]$AgentUrl = "http://127.0.0.1:8081",
  [string]$ManagementUrl = "http://127.0.0.1:8082",
  [string]$Username = "admin",
  [string]$Password = "admin123",
  [string]$Question = "上个月心内科各医生的门诊量排名",
  [string]$SessionId = "",
  [switch]$ShowTokens
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(180)

function New-Request($method, $url, $token) {
  $req = New-Object System.Net.Http.HttpRequestMessage($method, $url)
  if ($token) { $req.Headers.Add("Authorization", "Bearer $token") }
  return $req
}

# 1) 登录（令牌只走 Authorization 头，§19.4）
$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress
$loginReq = New-Request "POST" "$ManagementUrl/v1/auth/login" $null
$loginReq.Content = New-Object System.Net.Http.StringContent($loginBody, [Text.Encoding]::UTF8, "application/json")
$loginResp = $client.SendAsync($loginReq).Result
if (-not $loginResp.IsSuccessStatusCode) {
  throw "登录失败：HTTP $([int]$loginResp.StatusCode)（停用账号会停在这一步）"
}
$token = ($loginResp.Content.ReadAsStringAsync().Result | ConvertFrom-Json).token
Write-Host "[login] $Username 登录成功" -ForegroundColor DarkGray

# 2) 建会话；给了 -SessionId 就接着老会话聊
# （权限/技能变更后，**老会话**和新建会话的表现并不一样：工具面每轮刷新，但历史对话不会跟着变，
#   模型回答「你有哪些能力」时可能照着自己前几轮的说法讲。要复现这类问题就必须能指定老会话。）
if ($SessionId) {
  $sessionId = $SessionId
} else {
  $sessionResp = $client.SendAsync((New-Request "POST" "$AgentUrl/v1/agent/sessions" $token)).Result
  $sessionId = ($sessionResp.Content.ReadAsStringAsync().Result | ConvertFrom-Json).sessionId
}
Write-Host "[session] $sessionId" -ForegroundColor DarkGray

# 3) 发一轮：返回 { streamUrl, ticket }，ticket 只能用一次（§19.4）
$turnReq = New-Request "POST" "$AgentUrl/v1/agent/sessions/$sessionId/turns" $token
$turnReq.Content = New-Object System.Net.Http.StringContent(
  (@{ text = $Question } | ConvertTo-Json -Compress), [Text.Encoding]::UTF8, "application/json")
$ticket = ($client.SendAsync($turnReq).Result.Content.ReadAsStringAsync().Result | ConvertFrom-Json).ticket

# 4) 订阅 SSE
$streamReq = New-Request "GET" "$AgentUrl/v1/agent/chat/stream?ticket=$ticket" $token
$resp = $client.SendAsync($streamReq, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
$reader = New-Object System.IO.StreamReader($resp.Content.ReadAsStreamAsync().Result, [Text.Encoding]::UTF8)

Write-Host "[ask] $Question" -ForegroundColor Cyan
$name = ""; $data = ""; $answer = ""
while (-not $reader.EndOfStream) {
  $line = $reader.ReadLine()
  if ($line -eq $null) { break }
  if ($line.StartsWith("event:")) { $name = $line.Substring(6).Trim() }
  elseif ($line.StartsWith("data:")) { $data = $line.Substring(5).Trim() }
  elseif ($line -eq "") {
    if ($name) {
      $payload = $data | ConvertFrom-Json
      switch ($name) {
        "token" { $answer += $payload.delta; if ($ShowTokens) { Write-Host $payload.delta -NoNewline } }
        "tool" {
          if ($payload.phase -eq "start") {
            Write-Host ("[tool] 调用 {0}" -f $payload.toolName) -ForegroundColor Yellow
          } else {
            Write-Host ("[tool] {0} 参数 {1} → {2}（{3} 字节）" -f `
              $payload.toolName, $payload.args, $payload.status, $payload.resultSize) -ForegroundColor Yellow
          }
        }
        "step" { Write-Host ("[step] {0}" -f $payload.content) -ForegroundColor DarkGray }
        "error" { Write-Host ("[error] {0}" -f $payload.message) -ForegroundColor Red }
        "done" { }
      }
    }
    if ($name -eq "done") { break }
    $name = ""; $data = ""
  }
}
Write-Host ""
Write-Host "===== 回答 =====" -ForegroundColor Green
Write-Host $answer
