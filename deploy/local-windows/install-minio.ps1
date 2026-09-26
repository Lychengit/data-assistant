<#
本机原生安装 MinIO（对象存储），用于「多个 agent 实例共享同一份文件」的开发环境。

为什么不用容器：这台机器上没装 Docker，而 MinIO 是单文件绿色程序，原生跑最省事。

⚠️ 版本说明：MinIO 社区版已于 2025 年被官方归档，dl.min.io 不再分发（访问返回 410 Gone）。
这里用的是官方 GitHub Releases 上**最后一个开源版本**，只适合本机开发；
生产请换成仍在维护的对象存储（阿里云 OSS / 腾讯云 COS / Ceph 等）——
对接代码走 ObjectStorage 端口，换实现只需新增一个适配器，业务代码不动。

装完长什么样（全部在 D 盘，C 盘不留东西）：
  D:\minio\bin\minio.exe        服务端程序
  D:\minio\bin\mc.exe           命令行客户端（建桶、看文件用）
  D:\minio\data                 数据目录（所有对象都在这，重装不删）
  D:\minio\config\minio.env     账号口令 / 端口 / 桶名
  D:\minio\config\mc            mc 自己的配置目录（不放 C:\Users）
  D:\minio\logs                 运行日志（MinIO 写在 minio.err.log）
  计划任务「MinIO-Local」        登录后自动启动（由任务计划服务托管，见 minio-run.ps1）

可重复执行：已经装好且二进制没变就只做「补目录 + 确认自启 + 拉起来」，不会重复下载。
#>
param(
    # 想换版本就改这两个（必须与 GitHub Releases 上存在的 tag 完全一致）
    [string]$MinioVersion = 'RELEASE.2025-09-07T16-13-09Z',
    [string]$McVersion = 'RELEASE.2025-08-13T08-35-41Z',
    # 已经装过也想重新下载（例如二进制损坏）
    [switch]$Force
)

. (Join-Path $PSScriptRoot '_minio-common.ps1')

function Get-FileFromUrl {
    param([Parameter(Mandatory)][string]$Url, [Parameter(Mandatory)][string]$Destination)
    Write-Host "  下载 $Url"
    Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing
}

# 校验 SHA256：二进制来自第三方托管，装到机器上的东西必须验一遍再落地。
function Assert-Sha256 {
    param([Parameter(Mandatory)][string]$File, [Parameter(Mandatory)][string]$Sha256Url)
    $response = Invoke-WebRequest -Uri $Sha256Url -UseBasicParsing
    $text = [System.Text.Encoding]::UTF8.GetString($response.Content)
    $expected = ($text.Trim() -split '\s+')[0].ToLower()
    $actual = (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLower()
    if ($expected -ne $actual) {
        throw "校验失败：$File 的 SHA256 是 $actual，官方给的是 $expected（下载可能被改动，已中止）"
    }
    Write-Host "  校验通过 sha256=$actual"
}

function Install-OneBinary {
    param(
        [Parameter(Mandatory)][string]$Repo,          # minio 或 mc
        [Parameter(Mandatory)][string]$Version,
        [Parameter(Mandatory)][string]$FileName,
        [Parameter(Mandatory)][string]$TargetPath
    )
    if ((Test-Path -LiteralPath $TargetPath) -and -not $Force) {
        Write-Host "  已存在，跳过下载：$TargetPath"
        return
    }
    $base = "https://github.com/$Repo/releases/download/$Version"
    $temp = "$TargetPath.download"
    Get-FileFromUrl -Url "$base/$FileName" -Destination $temp
    Assert-Sha256 -File $temp -Sha256Url "$base/$FileName.sha256sum"
    Move-Item -LiteralPath $temp -Destination $TargetPath -Force
}

# 写配置文件：只在文件不存在时写，避免把用户改过的口令覆盖回去。
function Write-MinioEnvFile {
    if (Test-Path -LiteralPath $MinioEnvFile) {
        Write-Host "  配置已存在，保持不动：$MinioEnvFile"
        return
    }
    $lines = @(
        '# 本机 MinIO 配置（开发用）。改完重跑 .\minio-restart.ps1 生效。',
        '# 口令与 deploy/docker-compose.yml 保持一致，方便容器 / 原生两种跑法来回切。',
        '# 生产不要用这份文件：密钥一律由部署环境注入，不进仓库、不进镜像。'
    )
    foreach ($key in $MinioDefaultEnv.Keys) { $lines += "$key=$($MinioDefaultEnv[$key])" }
    Set-Content -LiteralPath $MinioEnvFile -Value $lines -Encoding UTF8
    Write-Host "  已写入配置：$MinioEnvFile"
}

# 登录自启：优先用「登录时触发」的计划任务（不需要管理员权限）；
# 如果这台机器不让普通用户建计划任务，就退回「启动文件夹放个快捷方式」。
function Register-MinioAutostart {
    # 任务动作指向 minio-run.ps1（前台守着 minio），不是 minio-start.ps1（那会绕成递归）。
    $runner = Join-Path $PSScriptRoot 'minio-run.ps1'

    try {
        $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
            -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$runner`""
        $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
        $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
            -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -Hidden
        Register-ScheduledTask -TaskName $MinioTaskName -Action $action -Trigger $trigger -Settings $settings `
            -Description '本机 MinIO 对象存储（登录后自动启动，供 data-assistant 多实例共享文件）' -Force | Out-Null
        Write-Host "  已注册计划任务「$MinioTaskName」（登录时自动启动，无需管理员）"
        Write-Host "  注意：任务里记的是这个仓库的路径；仓库挪位置后重跑本脚本即可"
        return
    } catch {
        Write-Host "  计划任务注册失败（$($_.Exception.Message)），退回启动文件夹快捷方式"
    }
    $startup = [Environment]::GetFolderPath('Startup')
    $shortcutPath = Join-Path $startup 'MinIO.lnk'
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = 'powershell.exe'
    $shortcut.Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$runner`""
    $shortcut.WindowStyle = 7
    $shortcut.Description = '本机 MinIO 对象存储（data-assistant 开发用）'
    $shortcut.Save()
    Write-Host "  已放入启动文件夹：$shortcutPath"

}

Write-Host '[1/6] 准备目录'
New-MinioDirs

Write-Host '[2/6] 下载服务端（约 108MB）'
Install-OneBinary -Repo 'minio/minio' -Version $MinioVersion `
    -FileName "minio.windows-amd64.$MinioVersion.exe" -TargetPath $MinioExe

Write-Host '[3/6] 下载命令行客户端 mc（建桶 / 排查问题用）'
Install-OneBinary -Repo 'minio/mc' -Version $McVersion `
    -FileName "mc.windows-amd64.$McVersion.exe" -TargetPath $MinioMcExe

Write-Host '[4/6] 写配置'
Write-MinioEnvFile

Write-Host '[5/6] 注册登录自启'
[void](Register-MinioAutostart)

Write-Host '[6/6] 启动并探活'
& (Join-Path $PSScriptRoot 'minio-start.ps1') -Force

$settings = Read-MinioEnv
Write-Host ''
Write-Host '准备技能包 / 工作区用的桶'
$bucket = $settings['MINIO_BUCKET']
Invoke-Mc mb "local/$bucket" --ignore-existing | ForEach-Object { Write-Host "  $_" }

Write-Host ''
Write-Host "完成：API  http://127.0.0.1:$($settings['MINIO_API_PORT'])   控制台 http://127.0.0.1:$($settings['MINIO_CONSOLE_PORT'])"
Write-Host "账号：$($settings['MINIO_ROOT_USER']) / $($settings['MINIO_ROOT_PASSWORD'])（本机开发用，见 $MinioEnvFile）"
Write-Host "状态：.\minio-status.ps1    启停：.\minio-start.ps1 / .\minio-stop.ps1 / .\minio-restart.ps1"