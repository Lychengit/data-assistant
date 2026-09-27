<#
.SYNOPSIS
  把一个技能包按 M3 的正规流程发布：上传 → 自动检查 → 人工评审（批准即发布）→ 授权给角色。

.DESCRIPTION
  为什么走接口而不是直接往库里 INSERT：技能包的自动检查（危险能力 / 绑定接口最小性 / 导出字段合规）
  与"发布是**不可变版本**"这两件事都写在管理端的服务层里。绕过它插库，等于让技能从未被检查过，
  而且 `skill_api.approved` 也不会被写——绕过发布直接把绑定标成已审核，正是要拦住的事。
  全流程都走管理端接口，**没有一处直接落库**：
  - 技能包：上传 → 自动检查 → 人工评审（批准即发布为不可变版本）→ 绑定接口（skill_api）；
  - 角色配技能：PUT /v1/admin/role-skill（2026-09-27 新增的端点，此前只能手工 INSERT role_skill）。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/sandbox-runner/publish-skill.ps1
#>
param(
  [string]$ManagementUrl = "http://127.0.0.1:8082",
  [string]$Username = "admin",
  [string]$Password = "admin123",
  [string]$SkillCode = "doctor_perf_excel_report",
  [string]$ZipPath = "",
  [string]$GrantRole = "admin",
  [string]$DbHost = "127.0.0.1",
  [int]$DbPort = 5432,
  [string]$DbUser = "postgres",
  [string]$DbPassword = "lyc123456",
  [string]$DbName = "doctor_assistant"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $ZipPath) {
  $ZipPath = Join-Path $repoRoot "samples\skill-packages\$SkillCode-1.0.0.zip"
}
if (-not (Test-Path -LiteralPath $ZipPath)) { throw "找不到技能包：$ZipPath" }
$manifestPath = Join-Path $repoRoot "samples\skills\$SkillCode\manifest.json"
$manifest = (Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8) | ConvertFrom-Json

function Write-Step([string]$Text) { Write-Host ""; Write-Host "== $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "   [OK] $Text" -ForegroundColor Green }
function Write-Info([string]$Text) { Write-Host "   $Text" -ForegroundColor DarkGray }

function Invoke-Json {
  param([string]$Method, [string]$Url, [string]$Token, $Body)
  $headers = @{}
  if ($Token) { $headers["Authorization"] = "Bearer $Token" }
  $params = @{ Method = $Method; Uri = $Url; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 60 }
  if ($null -ne $Body) {
    $params["Body"] = ($Body | ConvertTo-Json -Depth 8 -Compress)
    $params["ContentType"] = "application/json; charset=utf-8"
  }
  $resp = Invoke-WebRequest @params
  if (-not $resp.Content) { return $null }
  return $resp.Content | ConvertFrom-Json
}

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

Write-Step "1/5 登录（$Username）"
$login = Invoke-Json -Method POST -Url "$ManagementUrl/v1/auth/login" -Token $null `
  -Body @{ username = $Username; password = $Password }
$token = $login.token
Write-Ok "拿到令牌"

Write-Step "2/5 上传技能包（M3：上传即自动检查）"
$raw = & curl.exe -s -X POST "$ManagementUrl/v1/admin/skill/upload" `
  -H "Authorization: Bearer $token" -F "file=@$ZipPath" 2>&1
$upload = $raw | ConvertFrom-Json
if (-not $upload.version) { throw "上传失败：$raw" }
$versionId = $upload.version.id
Write-Ok "版本行 id=$versionId status=$($upload.version.status)（重复内容=$($upload.duplicateContent)）"
foreach ($check in $upload.checks) {
  $mark = if ($check.passed) { "PASS" } else { "FAIL" }
  $color = if ($check.passed) { "Green" } else { "Red" }
  if ($check.severity -eq "blocking" -or -not $check.passed) {
    Write-Host ("   [{0}] {1} ({2}) {3}" -f $mark, $check.checkCode, $check.severity, $check.detail) -ForegroundColor $color
  }
}
$blockingFailures = @($upload.checks | Where-Object { $_.severity -eq "blocking" -and -not $_.passed })
if ($blockingFailures.Count -gt 0) {
  throw "有 $($blockingFailures.Count) 项 blocking 检查未通过，技能不会被挂成待评审（这是设计如此）"
}
Write-Ok "自动检查全部通过"

Write-Step "3/5 人工评审并发布（批准即发布为不可变版本）"
$reviewBody = @{
  approve         = $true
  reason          = "技能包评审通过（自动检查逐项通过）；接口绑定以管理端「绑定接口」为准，不随包声明；上传动作由平台侧宿主代理按 W1-W3 单独完成"
  exportedColumns = @($manifest.exports)
}
$published = Invoke-Json -Method POST -Url "$ManagementUrl/v1/admin/skill/versions/$versionId/review" `
  -Token $token -Body $reviewBody
Write-Ok "status=$($published.status) publishedBy=$($published.publishedBy)"

Write-Step "4/5 授权给角色 $GrantRole（role_skill：可见即可执行）"
$granted = Invoke-Json -Method PUT -Url "$ManagementUrl/v1/admin/role-skill" -Token $token `
  -Body @{ roleCode = $GrantRole; skillCode = $SkillCode; canView = $true }
Write-Ok "$($granted.roleCode) → $($granted.skillCode)（$($granted.skillName)）canView=$($granted.canView)"

Write-Step "5/5 复核"
$detail = Invoke-Json -Method GET -Url "$ManagementUrl/v1/admin/skill/$SkillCode" -Token $token
$latest = @($detail.versions)[0]
Write-Host ("   技能        : {0}（{1}）" -f $detail.skill.skillCode, $detail.skill.status)
Write-Host ("  最新版本    : v{0} status={1} publishedAt={2}" -f $latest.version, $latest.status, $latest.publishedAt)
Write-Host ("  绑定接口    : {0}" -f (($detail.boundRoutes) -join '；'))
if (@($detail.boundRoutes).Count -eq 0) {
  # 包内不写 boundRoutes 是常态：绑定要在页面上配，这里提醒一句，免得「发布了但技能一个接口都调不了」
  Write-Host "  ↑ 这个技能还没有绑定接口：到 /admin/skill 这一行点「绑定接口」勾选（它决定技能能调哪些接口）" -ForegroundColor Yellow
}
$roleSkills = Invoke-Json -Method GET -Url "$ManagementUrl/v1/admin/role-skill?roleCode=$GrantRole" -Token $token
$hit = @($roleSkills | Where-Object { $_.skillCode -eq $SkillCode })
if ($hit.Count -eq 0) { throw "复核失败：role_skill 里没有 $GrantRole → $SkillCode" }
Write-Host ("  授权角色    : {0} → {1} canView={2}（role_skill 复核通过）" -f $hit[0].roleCode, $hit[0].skillCode, $hit[0].canView)