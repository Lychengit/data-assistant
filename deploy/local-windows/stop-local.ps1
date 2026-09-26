<#
本机一键停止：把 start-local 拉起来的那些服务全停掉。

怎么判定的：按端口找占用者，再连子进程一起杀（taskkill /T）。
为什么按端口而不是按进程名：服务是 mvn spring-boot:run 起的，进程名是 java，
本机可能有别的 java（IDE、别的项目）；按端口杀才不会误伤，也不会漏掉（fork 出来的子 JVM 一样占着端口）。

前端也会一起停（npm run dev 占着 5173）。MinIO / PostgreSQL / Redis 是系统服务，不在这里停。
#>
[CmdletBinding()]
param(
    # 端口和 start-local.ps1 保持一致；改了那边就改这里。
    [int[]]$Ports = @(8080, 8081, 8082, 8084, 5173)
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

function Get-PortOwners([int]$Port) {
    # netstat 的老写法兼容性最好，也不需要管理员权限。
    $owners = @()
    $lines = netstat -ano | Select-String -Pattern (':' + $Port + '\s+\S+\s+LISTENING')
    foreach ($line in $lines) {
        $text = ($line.Line -split '\s+')[-1]
        $ownerPid = 0
        if ([int]::TryParse($text, [ref]$ownerPid) -and $ownerPid -gt 0) { $owners += $ownerPid }
    }
    return $owners | Select-Object -Unique
}

Write-Host '=== 停止本机服务 ===' -ForegroundColor White
$killed = 0
foreach ($port in $Ports) {
    foreach ($ownerPid in (Get-PortOwners $port)) {
        Write-Host ("   端口 " + $port + " 被 pid " + $ownerPid + " 占着，停掉它") -ForegroundColor DarkGray
        & taskkill /PID $ownerPid /T /F 2>&1 | Out-Null
        $killed++
    }
}

Start-Sleep -Milliseconds 500

# 复查一遍：杀掉之后端口必须真的空出来，不然要如实说，不能装作停干净了。
$stillBusy = @()
foreach ($port in $Ports) { if (Get-PortOwners $port) { $stillBusy += $port } }

if ($killed -eq 0) {
    Write-Host '   没有服务在跑，什么都没做' -ForegroundColor Green
} elseif ($stillBusy.Count -eq 0) {
    Write-Host ("   全部停掉了（动了 " + $killed + " 个进程）") -ForegroundColor Green
} else {
    Write-Host ("   还有端口没空出来：" + ($stillBusy -join '、') + "（可能不是这个项目占的，或者需要管理员权限）") -ForegroundColor Yellow
}
exit 0
