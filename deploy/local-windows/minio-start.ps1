<#
启动本机 MinIO。已经跑着就什么都不做（除非加 -Force，那就是「重启」）。

MinIO 不是直接起在这里，而是交给计划任务「MinIO-Local」托管（自启走的也是这一条），
好处是：进程由任务计划服务创建，不继承当前窗口的任何句柄，日志也统一落在 D:\minio\logs。

用法：
  .\minio-start.ps1            # 没跑就拉起来；已在跑就原样不动
  .\minio-start.ps1 -Force     # 先停再起（改了 minio.env 或换了二进制之后用这个）
#>
param([switch]$Force)

. (Join-Path $PSScriptRoot '_minio-common.ps1')

if (-not (Test-Path -LiteralPath $MinioExe)) {
    throw "还没装：找不到 $MinioExe。先跑 .\install-minio.ps1"
}

$running = Get-MinioProcess
if ($running.Count -gt 0) {
    if (-not $Force) {
        Write-Host "MinIO 已在运行（PID $($running.Id -join ', ')），跳过启动"
        exit 0
    }
    Write-Host '先停掉正在运行的 MinIO…'
    & (Join-Path $PSScriptRoot 'minio-stop.ps1') | Out-Null
}

if (-not (Get-ScheduledTask -TaskName $MinioTaskName -ErrorAction SilentlyContinue)) {
    throw "没找到计划任务「$MinioTaskName」：先跑 .\install-minio.ps1 注册一次"
}

Start-ScheduledTask -TaskName $MinioTaskName
Write-Host "已交给计划任务「$MinioTaskName」启动，等它通过健康检查…"

if (-not (Wait-MinioHealthy -TimeoutSeconds 60)) {
    throw "MinIO 起来了但 60 秒内没通过健康检查。日志：$MinioLogDir\minio.err.log"
}
$settings = Read-MinioEnv
Write-Host "健康检查通过：API http://127.0.0.1:$($settings['MINIO_API_PORT'])  控制台 http://127.0.0.1:$($settings['MINIO_CONSOLE_PORT'])"