<#
.SYNOPSIS
  技能沙箱执行器（宿主代理，§18.4.3 S0–S7 / §18.4.4）：跑 doctor_perf_excel_report，返回 Excel 下载链接。

.DESCRIPTION
  它扮演的正是规格书里那个「宿主代理」：

    容器内（脚本）                              容器外（本脚本）
    ────────────────────────────────            ────────────────────────────────────────────
    network=none，没有网卡                       白名单 = 只调本技能 manifest 声明的只读接口
    没有任何凭据（无令牌 / 无对象存储密钥）        持用户令牌 + 服务签名密钥（local-agent-service-secret）
    只读输入工件、只写 /out 挂载目录              代脚本调网关；把 /out 的产物经 /doctor/export/upload 上传

  为什么取数放在这里而不是脚本里：脚本是「不可信输入」的产物（可能是用户自己写的），令牌一旦进容器就可能
  被写进产物带出去，限流 / 审计 / 撤销随之失效（§18.4.4 的「为什么不用容器直连网关 + 脚本自持令牌」）。

  为什么上传也放在这里：`/doctor/export/upload` 是 **write** 接口，网关 G3 要求一次性确认凭据。
  确认由本脚本模拟「技能启动前那一次确认」（§19.9）——先在 pending_confirm 落一条待确认，
  再由网关消费掉。上传是平台侧的动作，不进技能包（写接口现在也能绑技能了，见平台 ADR-19 v3.7 修订）。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/sandbox-runner/run-skill.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/sandbox-runner/run-skill.ps1 -Month 2026-09 -MetricKey outpatient_visits
#>
param(
  [string]$GatewayUrl = "http://127.0.0.1:8080",
  [string]$ManagementUrl = "http://127.0.0.1:8082",
  [string]$Username = "admin",
  [string]$Password = "admin123",
  [string]$KeyId = "agent-service",
  [string]$SharedSecret = "local-agent-service-secret",
  [string]$SkillCode = "doctor_perf_excel_report",
  [string]$SkillDir = "",
  [string]$Month = "",
  [string]$MetricKey = "outpatient_visits",
  [string]$Image = "doctor-assistant/sandbox-python:3.12",
  [string]$DbHost = "127.0.0.1",
  [int]$DbPort = 5432,
  [string]$DbUser = "postgres",
  [string]$DbPassword = "lyc123456",
  [string]$DbName = "doctor_assistant",
  [switch]$SkipDocker
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromMinutes(5)

function Write-Step([string]$Text) {
  Write-Host ""
  Write-Host "== $Text" -ForegroundColor Cyan
}
function Write-Ok([string]$Text) { Write-Host "   [OK] $Text" -ForegroundColor Green }
function Write-Info([string]$Text) { Write-Host "   $Text" -ForegroundColor DarkGray }

# ---------------------------------------------------------------------------
# 路径与工具
# ---------------------------------------------------------------------------
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $SkillDir) { $SkillDir = Join-Path $repoRoot "samples\skills\$SkillCode" }
if (-not (Test-Path -LiteralPath $SkillDir)) { throw "找不到技能目录：$SkillDir" }
$runRoot = Join-Path $PSScriptRoot ".run"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDir = Join-Path $runRoot $stamp
$inDir = Join-Path $runDir "in"
$outDir = Join-Path $runDir "out"
foreach ($d in @($inDir, $outDir)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
Write-Info "本次执行目录：$runDir"

function Find-Psql {
  $onPath = Get-Command psql -ErrorAction SilentlyContinue
  if ($onPath) { return $onPath.Source }
  foreach ($root in @('D:\PostgreSQL', 'C:\Program Files\PostgreSQL')) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    $hit = Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
      Sort-Object Name -Descending |
      ForEach-Object { Join-Path $_.FullName 'bin\psql.exe' } |
      Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ($hit) { return $hit }
  }
  return $null
}
$psql = Find-Psql
if (-not $psql) { throw "没找到 psql，无法模拟技能启动前的确认（§19.9）" }

function Invoke-Sql([string]$Sql) {
  # 走 UTF-8 文件 + PGCLIENTENCODING，而不是 -c "…"：
  # Windows PowerShell 5.1 把命令行参数按**系统 ANSI 代码页**（这台机器是 GBK）编码，
  # psql 按 UTF8 解析就会报「无效的 UTF8 编码字节顺序」——SQL 里只要有中文就必踩。
  # 写文件走的是明确的 UTF-8 字节，绕开命令行编码这一层。
  $env:PGPASSWORD = $DbPassword
  $env:PGCLIENTENCODING = 'UTF8'
  $sqlFile = Join-Path $runDir ("sql-" + [guid]::NewGuid().ToString("N") + ".sql")
  try {
    [System.IO.File]::WriteAllText($sqlFile, $Sql, $utf8NoBom)
    $out = & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -t -A -f $sqlFile 2>&1
    if ($LASTEXITCODE -ne 0) { throw "SQL 执行失败：$out" }
    return $out
  } finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:PGCLIENTENCODING -ErrorAction SilentlyContinue
    if ([System.IO.File]::Exists($sqlFile)) { [System.IO.File]::Delete($sqlFile) }
  }
}

# 签名串口径 §20.1.3：v1 / 方法大写 / 路径 / query（字典序）/ 时间戳 / nonce / SHA-256(请求体)
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
    $signature = "v1:" + [Convert]::ToBase64String($hmac.ComputeHash($utf8NoBom.GetBytes($canonical)))
  } finally { $hmac.Dispose() }
  return @{
    "X-Api-Key"   = $KeyId
    "X-Timestamp" = "$timestamp"
    "X-Nonce"     = $nonce
    "X-Signature" = $signature
  }
}

# ---------------------------------------------------------------------------
# 1) 认人：登录拿用户令牌（只走 Authorization 头，§19.4）
# ---------------------------------------------------------------------------
Write-Step "1/7 登录（$Username）"
$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress
$loginReq = New-Object System.Net.Http.HttpRequestMessage("POST", "$ManagementUrl/v1/auth/login")
$loginReq.Content = New-Object System.Net.Http.StringContent($loginBody, $utf8NoBom, "application/json")
$loginResp = $client.SendAsync($loginReq).Result
if (-not $loginResp.IsSuccessStatusCode) {
  throw "登录失败：HTTP $([int]$loginResp.StatusCode)"
}
$token = ($loginResp.Content.ReadAsStringAsync().Result | ConvertFrom-Json).token
Write-Ok "拿到用户令牌（角色：admin）"

# ---------------------------------------------------------------------------
# 2) 读技能包 manifest：**绑定接口就是取数白名单**（§18.4.4 四道检查的第 ① 道）
# ---------------------------------------------------------------------------
$manifest = (Get-Content -LiteralPath (Join-Path $SkillDir "manifest.json") -Raw -Encoding UTF8 | ConvertFrom-Json)
# 2026-09-27 起 boundRoutes 可以不写（绑定改在管理端「技能包 M3 → 绑定接口」里配），所以包内没写时
# 读**管理端生效的那份**（skill_api）。两处都不给就直接停：白名单空着不是「谁都能调」就是「谁都调不了」，
# 两种都不能靠猜——尤其这是脚本执行前唯一一道取数白名单。
$allowedRoutes = @($manifest.boundRoutes | Where-Object { $_ })
if ($allowedRoutes.Count -eq 0) {
  $detail = Invoke-RestMethod -Method GET -Uri "$ManagementUrl/v1/admin/skill/$($manifest.id)" `
    -Headers @{ Authorization = "Bearer $token" }
  $allowedRoutes = @($detail.boundRoutes | Where-Object { $_ })
  if ($allowedRoutes.Count -eq 0) {
    throw "包内没写 boundRoutes，管理端也没配绑定：先到 $ManagementUrl /admin/skill 这一行点「绑定接口」勾完再跑"
  }
  Write-Info "包内未声明 boundRoutes，白名单取管理端生效的那份（skill_api）"
}
$effectiveKind = if ($manifest.kind) { $manifest.kind } elseif ($manifest.script) { "script" } else { "agentic" }
$effectiveVersion = if ($manifest.version) { $manifest.version } else { "自动生成" }
Write-Info "技能 $($manifest.id) v$effectiveVersion（kind=$effectiveKind）"
Write-Info "取数白名单：$($allowedRoutes -join '；')"

function Invoke-GatewayCall {
  param(
    [string]$Service, [string]$HttpMethod, [string]$HttpPath,
    [hashtable]$ArgsMap, [string]$SkillCode, [string]$ConfirmId
  )
  $route = "$Service $HttpMethod $HttpPath"
  if ($allowedRoutes -notcontains $route) {
    throw "接口 $route 不在技能声明的白名单里，宿主代理拒绝代调（§18.4.4 检查①）"
  }
  $body = [ordered]@{
    service    = $Service
    httpMethod = $HttpMethod
    httpPath   = $HttpPath
    skillCode  = if ($SkillCode) { $SkillCode } else { $null }
    confirmId  = if ($ConfirmId) { $ConfirmId } else { $null }
    requestId  = [guid]::NewGuid().ToString()
    args       = $ArgsMap
  } | ConvertTo-Json -Compress -Depth 8
  $bodyBytes = $utf8NoBom.GetBytes($body)
  $path = "/v1/gateway/api-call"
  # 网关 G4 是按用户「每 1 秒 5 次」的固定窗口（RedisUserRateLimiter）。取数是逐人循环，
  # 一口气打出去必然撞限额——所以这里主动让出节拍；万一还是被限流，退避重试而不是把 429 当失败。
  Start-Sleep -Milliseconds 220
  $attempt = 0
  while ($true) {
    $req = New-Object System.Net.Http.HttpRequestMessage("POST", "$GatewayUrl$path")
    $req.Headers.Add("Authorization", "Bearer $token")
    (Get-SignatureHeaders "POST" $path "" $bodyBytes).GetEnumerator() |
      ForEach-Object { $req.Headers.Add($_.Key, $_.Value) }
    $req.Content = New-Object System.Net.Http.ByteArrayContent($bodyBytes, 0, $bodyBytes.Length)
    $req.Content.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue("application/json")
    $resp = $client.SendAsync($req).Result
    $text = $resp.Content.ReadAsStringAsync().Result
    if ($resp.StatusCode -eq 429 -and $attempt -lt 4) {
      $attempt++
      Write-Info "被网关限速（429），退避重试第 $attempt 次"
      Start-Sleep -Milliseconds 500
      continue
    }
    if (-not $resp.IsSuccessStatusCode) {
      throw "调用 $route 失败：HTTP $([int]$resp.StatusCode) $text"
    }
    return $text | ConvertFrom-Json
  }
}

# ---------------------------------------------------------------------------
# 2) 取本月医生名单（§18.6.2 步骤 3 的前半）
# ---------------------------------------------------------------------------
if (-not $Month) {
  # 相对时间由程序解析，不交给模型算（§18.7 硬要求 4）
  $Month = (Get-Date).ToString("yyyy-MM")
}
Write-Step "2/7 取 $Month 医生名单 → POST /doctor/list"
$listResult = Invoke-GatewayCall -Service "interface-doctor" -HttpMethod "POST" -HttpPath "/doctor/list" `
  -ArgsMap @{} -SkillCode $SkillCode -ConfirmId $null
$doctors = @($listResult.rows)
Write-Ok "名单 $($doctors.Count) 人：$((($doctors | ForEach-Object { $_.doctor_name }) -join '、'))"

# ---------------------------------------------------------------------------
# 3) 基于医生逐人查本月绩效（§18.6.2 步骤 3 的后半）
# ---------------------------------------------------------------------------
Write-Step "3/7 逐人查 $Month 绩效（metric_key=$MetricKey）→ POST /doctor/performance"
$rows = @()
$provenance = $null
$missing = @()
foreach ($doctor in $doctors) {
  $perf = Invoke-GatewayCall -Service "interface-doctor" -HttpMethod "POST" -HttpPath "/doctor/performance" `
    -ArgsMap @{ month = $Month; metric_key = $MetricKey; doctor_id = $doctor.doctor_id } -SkillCode $SkillCode -ConfirmId $null
  if (-not $provenance -and $perf.provenance) { $provenance = $perf.provenance }
  $hit = @($perf.rows)
  if ($hit.Count -eq 0) {
    $missing += $doctor.doctor_id
    $rows += [ordered]@{
      doctor_id = $doctor.doctor_id; doctor_name = $doctor.doctor_name
      dept_code = $doctor.dept_code; metric_value = $null
    }
    Write-Info "医生 $($doctor.doctor_id) $($doctor.doctor_name)：本月无绩效数据（如实留空，不补 0）"
  } else {
    foreach ($r in $hit) {
      $rows += [ordered]@{
        doctor_id = $r.doctor_id; doctor_name = $r.doctor_name
        dept_code = $r.dept_code; metric_value = $r.metric_value
      }
    }
    Write-Info "医生 $($doctor.doctor_id) $($doctor.doctor_name)：$($hit.Count) 行"
  }
}
Write-Ok "绩效行 $($rows.Count) 条（其中 $($missing.Count) 位医生本月无数据）"

$input = [ordered]@{
  month       = $Month
  metric_key  = $MetricKey
  metric_name = $provenance.metricName
  unit        = $provenance.unit
  basis       = $provenance.basis
  doctors     = $rows
  missing     = $missing
}
$inputPath = Join-Path $inDir "input.json"
[System.IO.File]::WriteAllText($inputPath, ($input | ConvertTo-Json -Depth 6), $utf8NoBom)
Write-Info "输入工件：$inputPath"

# ---------------------------------------------------------------------------
# 4) 沙箱执行：容器无网卡、无凭据、只读根 + 非 root（§18.4.4 / §18.12.5）
# ---------------------------------------------------------------------------
Write-Step "4/7 沙箱执行脚本（network=none，容器内无凭据）"
$outFile = Join-Path $outDir "doctor_perf_$Month.xlsx"
$scriptRel = $manifest.script.path
$dockerArgs = @(
  "run", "--rm",
  "--network", "none",
  "--user", "1000:1000",
  "--cap-drop", "ALL",
  "--security-opt", "no-new-privileges",
  "--read-only",
  "--tmpfs", "/tmp:rw,size=64m",
  "--memory", "512m", "--cpus", "1",
  "-v", "${SkillDir}:/skill:ro",
  "-v", "${inDir}:/in:ro",
  "-v", "${outDir}:/out",
  $Image,
  "python3", "/skill/$scriptRel", "--input", "/in/input.json", "--output", "/out/doctor_perf_$Month.xlsx"
)
if ($SkipDocker) {
  Write-Info "-SkipDocker：跳过容器执行（只验证宿主侧取数与上传）"
} else {
  $env:Path += ';C:\Program Files\Docker\Docker\resources\bin'
  Write-Info ("docker " + ($dockerArgs -join " "))
  $dockerOut = & docker @dockerArgs 2>&1
  $dockerCode = $LASTEXITCODE
  $dockerOut | ForEach-Object { Write-Info "容器: $_" }
  if ($dockerCode -ne 0) { throw "沙箱执行失败（退出码 $dockerCode）" }
  if (-not (Test-Path -LiteralPath $outFile)) { throw "沙箱执行完毕但没有产出文件：$outFile" }
  $produced = Get-Item -LiteralPath $outFile
  Write-Ok "容器内产出 $($produced.Name)（$($produced.Length) 字节）"
}

# ---------------------------------------------------------------------------
# 5) 技能启动前一次确认（§19.9）：写操作必须先拿到确认凭据
# ---------------------------------------------------------------------------
Write-Step "5/7 生成上传确认凭据（pending_confirm，§19.9）"
$confirmId = [guid]::NewGuid().ToString()
$sessionId = "skill-run-$stamp"
Invoke-Sql ("INSERT INTO pending_confirm (confirm_id, session_id, turn_id, user_id, action, summary, status, expires_at) " +
            "VALUES ('$confirmId', '$sessionId', NULL, '$Username', 'oss.upload', " +
            "'技能 $SkillCode 将把 $Month 绩效 Excel 上传到对象存储', 'pending', now() + interval '30 minutes')") | Out-Null
Write-Ok "确认凭据 $confirmId（一次性，网关消费）"

# ---------------------------------------------------------------------------
# 6) 上传：宿主代理经 /doctor/export/upload 走 W1–W3（沙箱永远拿不到凭据）
# ---------------------------------------------------------------------------
Write-Step "6/7 上传到对象存储 → POST /doctor/export/upload（write）"
# 上传接口不由技能发起（技能包不绑它），所以这里**不带 skillCode**：网关按「角色直配接口」
# 判定（admin 已有 role_api 授权），而不是按技能白名单。发起方是平台侧宿主代理身份，且已过启动前一次确认。
# §18.4.4：「宿主代理同时是产物搬运者」——沙箱只写挂载目录，上传由宿主代理经网关 W1–W3 完成。
$allowedRoutes += "interface-doctor POST /doctor/export/upload"
$uploadSkillCode = $null
$fileBytes = [System.IO.File]::ReadAllBytes($outFile)
$uploadResult = Invoke-GatewayCall -Service "interface-doctor" -HttpMethod "POST" -HttpPath "/doctor/export/upload" `
  -ArgsMap @{
    file_name      = "医生绩效报表_$Month.xlsx"
    content_base64 = [Convert]::ToBase64String($fileBytes)
    content_type   = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    session_id     = $sessionId
    artifact_name  = "医生绩效报表（$Month）"
  } -SkillCode $uploadSkillCode -ConfirmId $confirmId
$uploadRow = @($uploadResult.rows)[0]
Write-Ok "工件编号 $($uploadRow.artifact_id)（$($uploadRow.size_bytes) 字节，sha256=$($uploadRow.content_sha256.Substring(0,12))…）"

# ---------------------------------------------------------------------------
# 7) 验链接真的能下载（不然"给了个链接"这件事没法算完成）
# ---------------------------------------------------------------------------
Write-Step "7/7 验证下载链接"
$downloadUrl = $uploadRow.download_url
if (-not $downloadUrl) { throw "上传接口没有返回下载链接（object-storage.provider=local 时没有签名 URL）" }
$dlReq = New-Object System.Net.Http.HttpRequestMessage("GET", $downloadUrl)
$dlResp = $client.SendAsync($dlReq).Result
$dlBytes = $dlResp.Content.ReadAsByteArrayAsync().Result
if (-not $dlResp.IsSuccessStatusCode) { throw "下载链接不可用：HTTP $([int]$dlResp.StatusCode)" }
if ($dlBytes.Length -ne $fileBytes.Length) { throw "下载字节数对不上：$($dlBytes.Length) vs $($fileBytes.Length)" }
Write-Ok "下载链接可用（HTTP 200，$($dlBytes.Length) 字节）"

$artifactRow = Invoke-Sql ("SELECT artifact_id || '|' || name || '|' || size_bytes FROM artifact WHERE artifact_id = '$($uploadRow.artifact_id)'")
Write-Ok "工件登记：$artifactRow"

$result = [ordered]@{
  skill_code        = $SkillCode
  month             = $Month
  metric_key        = $MetricKey
  doctors           = $doctors.Count
  rows              = $rows.Count
  missing_doctors   = $missing
  artifact_id       = $uploadRow.artifact_id
  file_name         = $uploadRow.file_name
  size_bytes        = $uploadRow.size_bytes
  content_sha256    = $uploadRow.content_sha256
  download_url      = $uploadRow.download_url
  link_expires_at   = $uploadRow.download_url_expires_at
  sandbox           = if ($SkipDocker) { "skipped" } else { "docker:network=none,user=1000:1000,cap-drop=ALL,read-only" }
  run_dir           = $runDir
}
[System.IO.File]::WriteAllText((Join-Path $runDir "result.json"), ($result | ConvertTo-Json -Depth 6), $utf8NoBom)

Write-Host ""
Write-Host "===== 结果 =====" -ForegroundColor White
Write-Host ("技能          : {0} v{1}（{2}）" -f $manifest.id, $manifest.version, $manifest.kind)
Write-Host ("月份 / 指标   : {0} / {1}（{2}，单位 {3}）" -f $Month, $MetricKey, $provenance.metricName, $provenance.unit)
Write-Host ("医生 / 报表行 : {0} 人 / {1} 行（{2} 位本月无数据）" -f $doctors.Count, $rows.Count, $missing.Count)
Write-Host ("Excel 工件    : {0}（{1} 字节）" -f $uploadRow.file_name, $uploadRow.size_bytes)
Write-Host ("工件编号      : {0}" -f $uploadRow.artifact_id)
Write-Host ("下载地址      : {0}" -f $uploadRow.download_url) -ForegroundColor Green
Write-Host ("链接有效期至  : {0}" -f $uploadRow.download_url_expires_at)
Write-Host ("执行记录      : {0}" -f (Join-Path $runDir "result.json"))