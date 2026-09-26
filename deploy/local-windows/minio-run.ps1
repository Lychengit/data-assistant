<#
真正把 MinIO 拉起来的脚本——由计划任务「MinIO-Local」调用，平时不用手动跑。

为什么让它「在前台守着 minio」而不是自己起个后台进程就退出：
  1) 进程生命周期跟计划任务绑死——停任务就等于停 MinIO，不会留下没人管的孤儿进程；
  2) 计划任务是由「任务计划服务」拉起的，不继承任何控制台 / 管道句柄，
     所以 install / start 脚本就算把输出接进管道，也不会被 MinIO 一直卡住。

日志：MinIO 把日志写在**标准错误**上，所以排查问题看 minio.err.log（minio.out.log 通常是空的）。
#>
. (Join-Path $PSScriptRoot '_minio-common.ps1')

New-MinioDirs

# 已经有一个在跑了就别再起第二个：重新登录时计划任务会再触发一次，
# 而前一次登录留下的进程可能还活着（它不属于任务进程树，任务结束不会带走它）。
$existing = Get-MinioProcess
if ($existing.Count -gt 0) {
    Write-Host "已有 MinIO 实例在运行（PID $($existing.Id -join ', ')），这次不重复启动"
    exit 0
}

# 配置以环境变量形式喂给 minio.exe：MinIO 只认环境变量，不认配置文件。
$settings = Read-MinioEnv
foreach ($key in $settings.Keys) { Set-Item -Path "Env:$key" -Value $settings[$key] }

$stdout = Join-Path $MinioLogDir 'minio.out.log'
$stderr = Join-Path $MinioLogDir 'minio.err.log'
# 控制台端口必须显式指定：不指定的话 MinIO 会开在随机端口上，"控制台地址"这类文档立刻就过期。
$arguments = @(
    'server', $settings['MINIO_DATA_DIR'],
    '--address', ":$($settings['MINIO_API_PORT'])",
    '--console-address', ":$($settings['MINIO_CONSOLE_PORT'])"
)

$process = Start-Process -FilePath $MinioExe -ArgumentList $arguments `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
    -WindowStyle Hidden -PassThru
Set-Content -LiteralPath $MinioPidFile -Value $process.Id -Encoding ASCII

$process.WaitForExit()
Remove-Item -LiteralPath $MinioPidFile -ErrorAction SilentlyContinue