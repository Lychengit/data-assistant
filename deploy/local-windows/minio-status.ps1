<#
看一眼本机 MinIO 的状态：进程、端口、健康、桶、最近日志。
排查「对象存不进去」时先跑它。
#>
. (Join-Path $PSScriptRoot '_minio-common.ps1')

$settings = Read-MinioEnv
$apiPort = $settings['MINIO_API_PORT']
$consolePort = $settings['MINIO_CONSOLE_PORT']

Write-Host '--- 进程 ---'
$running = Get-MinioProcess
if ($running.Count -eq 0) {
    Write-Host '未运行（启动：.\minio-start.ps1）'
} else {
    $running | ForEach-Object { Write-Host "PID $($_.Id)  启动于 $($_.StartTime)  内存 $([math]::Round($_.WorkingSet64/1MB))MB" }
}

Write-Host '--- 计划任务 ---'
$task = Get-ScheduledTask -TaskName $MinioTaskName -ErrorAction SilentlyContinue
if ($task) { Write-Host "$MinioTaskName  $($task.State)（登录时自动启动）" } else { Write-Host "没注册「$MinioTaskName」" }

Write-Host '--- 端口 ---'
foreach ($port in @($apiPort, $consolePort)) {
    $listening = Test-NetConnection -ComputerName 127.0.0.1 -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue
    Write-Host "$port  $(if ($listening) { '监听中' } else { '没在听' })"
}

Write-Host '--- 健康检查 ---'
if (Wait-MinioHealthy -TimeoutSeconds 5) {
    Write-Host "API 正常：http://127.0.0.1:$apiPort"
} else {
    Write-Host "API 不健康，看日志：$MinioLogDir\minio.err.log"
}

Write-Host '--- 桶（对象存储里的"顶层目录"）---'
if (Test-Path -LiteralPath $MinioMcExe) {
    Invoke-Mc ls local | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host "  没装 mc，跳过（控制台也能看：http://127.0.0.1:$consolePort）"
}

Write-Host '--- 最近 8 行日志（MinIO 的日志在标准错误流上，看 err.log）---'
$logFile = Join-Path $MinioLogDir 'minio.err.log'
if (Test-Path -LiteralPath $logFile) {
    Get-Content -LiteralPath $logFile -Tail 8 | ForEach-Object { Write-Host "  $_" }
}