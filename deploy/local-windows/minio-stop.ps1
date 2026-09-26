<#
停掉本机 MinIO：先停计划任务（连带它拉起的 minio 进程），再兜底清理可能残留的进程。
只会动「这个安装目录里那个 minio.exe」，不会误杀别的 minio。
#>
. (Join-Path $PSScriptRoot '_minio-common.ps1')

$task = Get-ScheduledTask -TaskName $MinioTaskName -ErrorAction SilentlyContinue
if ($task -and $task.State -eq 'Running') {
    Stop-ScheduledTask -TaskName $MinioTaskName
    Write-Host '已停止计划任务'
}

$leftover = Get-MinioProcess
foreach ($process in $leftover) {
    Write-Host "停掉残留进程 PID $($process.Id)"
    Stop-Process -Id $process.Id -Force
}

if (-not (Wait-MinioStopped)) {
    throw '有 MinIO 进程没停下来，请到任务管理器确认'
}
Remove-Item -LiteralPath $MinioPidFile -ErrorAction SilentlyContinue
Write-Host 'MinIO 已停止'