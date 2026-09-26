# MinIO 本机安装的公共部分：路径、配置读取、进程与健康检查。
# 其余脚本都 dot-source 本文件（`. .\_minio-common.ps1`），路径与判定逻辑只写这一处。
# 说明：所有东西都装在 D:\minio 下（C 盘不放软件）——仓库里不放二进制、不放数据、不放口令。

$ErrorActionPreference = 'Stop'

# 安装根目录。想换盘符就改这一行（改完重跑 install-minio.ps1 即可）。
$MinioRoot = 'D:\minio'
$MinioBinDir = Join-Path $MinioRoot 'bin'
$MinioExe = Join-Path $MinioBinDir 'minio.exe'
$MinioMcExe = Join-Path $MinioBinDir 'mc.exe'
$MinioDataDir = Join-Path $MinioRoot 'data'
$MinioLogDir = Join-Path $MinioRoot 'logs'
$MinioConfigDir = Join-Path $MinioRoot 'config'
$MinioEnvFile = Join-Path $MinioConfigDir 'minio.env'
$MinioMcConfigDir = Join-Path $MinioConfigDir 'mc'
$MinioPidFile = Join-Path $MinioLogDir 'minio.pid'
$MinioTaskName = 'MinIO-Local'

# 本机安装用的默认值。**只用于开发机**：口令与 deploy/docker-compose.yml 里的保持一致，
# 免得本地两套环境（容器 / 原生）来回切换时老要改配置。生产一律走独立的密钥注入。
$MinioDefaultEnv = [ordered]@{
    MINIO_ROOT_USER     = 'djzy'
    MINIO_ROOT_PASSWORD = 'djzy-minio'
    MINIO_API_PORT      = '9000'
    MINIO_CONSOLE_PORT  = '9001'
    MINIO_BUCKET        = 'doctor-assistant'
    MINIO_DATA_DIR      = $MinioDataDir
}

function New-MinioDirs {
    foreach ($d in @($MinioRoot, $MinioBinDir, $MinioDataDir, $MinioLogDir, $MinioConfigDir)) {
        New-Item -ItemType Directory -Force -Path $d | Out-Null
    }
}

# 读 minio.env（KEY=VALUE，允许 # 注释与空行）；文件不存在就返回默认值。
function Read-MinioEnv {
    $values = [ordered]@{}
    foreach ($k in $MinioDefaultEnv.Keys) { $values[$k] = $MinioDefaultEnv[$k] }
    if (Test-Path -LiteralPath $MinioEnvFile) {
        foreach ($line in (Get-Content -LiteralPath $MinioEnvFile -Encoding UTF8)) {
            $trimmed = $line.Trim()
            if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
            $idx = $trimmed.IndexOf('=')
            if ($idx -le 0) { continue }
            $values[$trimmed.Substring(0, $idx).Trim()] = $trimmed.Substring($idx + 1).Trim()
        }
    }
    return $values
}

# 找出「跑的就是这个安装目录里那个 minio.exe」的进程；别的 minio 进程不碰。
function Get-MinioProcess {
    return @(Get-Process -Name 'minio' -ErrorAction SilentlyContinue | Where-Object {
        try { $_.Path -eq $MinioExe } catch { $false }
    })
}

function Get-MinioPort {
    param([Parameter(Mandatory)][string]$Name)
    return [int](Read-MinioEnv)[$Name]
}

# 探活：/minio/health/live 是官方健康端点，200 表示可以收请求了。
function Wait-MinioHealthy {
    param([int]$TimeoutSeconds = 30)
    $port = Get-MinioPort 'MINIO_API_PORT'
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$port/minio/health/live" -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return $true }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    return $false
}

function Wait-MinioStopped {
    param([int]$TimeoutSeconds = 20)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ((Get-MinioProcess).Count -eq 0) { return $true }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

# 跑 mc（MinIO 的命令行客户端）。配置目录固定在 D 盘，免得它在 C:\Users 下另起一套。
# 用法：Invoke-Mc @('mb', 'local/doctor-assistant', '--ignore-existing')
function Invoke-Mc {
    $settings = Read-MinioEnv
    New-Item -ItemType Directory -Force -Path $MinioMcConfigDir | Out-Null
    $env:MC_CONFIG_DIR = $MinioMcConfigDir
    $env:MC_HOST_local = "http://$($settings['MINIO_ROOT_USER']):$($settings['MINIO_ROOT_PASSWORD'])@127.0.0.1:$($settings['MINIO_API_PORT'])"
    # 用 $args 而不是自声明参数：mc 的子命令参数形形色色（--ignore-existing、--recursive…），
    # 原样转交最省心，也不会出现「整条命令被当成一个参数」的解包问题。
    return (& $MinioMcExe @args 2>&1)
}