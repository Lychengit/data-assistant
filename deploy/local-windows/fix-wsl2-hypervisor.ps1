<#
本机 WSL2 / Docker Desktop 起不来的修复脚本（这台开发机专用）。

症状：Docker Desktop 报「Docker Desktop distro installation failed — ensuring main distro is deployed:
      open \\wsl$\docker-desktop\etc\wsl_bootstrap_version: The specified network name is no longer available」，
直接跑 wsl 则报 Error code: Wsl/Service/CreateInstance/CreateVm/HCS/HCS_E_HYPERV_NOT_INSTALLED。

结论：**不是 Docker 装坏了，也不该重装 Docker**——是 Windows 的 WSL2 依赖（虚拟机平台）没启用，或者
      「Hyper-V 启动类型」被设成了 off，于是 WSL 连虚拟机都建不出来，Docker 自然连它的发行版都挂不上。
      本机 CPU 的 BIOS 虚拟化开关是开着的（VirtualizationFirmwareEnabled=True），所以只需要在 Windows 侧修。

为什么必须提权：启用 Windows 功能与改 bcdedit 都要管理员。
为什么脚本不替你重启：这两项都只在下次开机生效，重启要你自己来。

用法（管理员 PowerShell）：
  .\fix-wsl2-hypervisor.ps1                 # 直接修
  .\fix-wsl2-hypervisor.ps1 -WhatIfOnly      # 只看现状，什么都不改
  .\fix-wsl2-hypervisor.ps1 -ResetDockerDistros   # 顺手重建 Docker 的两个发行版（会丢镜像与卷）

从普通窗口触发 UAC（会弹一个「是否允许」）：
  Start-Process powershell -Verb RunAs -WindowStyle Hidden -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',"$PWD\fix-wsl2-hypervisor.ps1"
#>
param(
    # 结果同时写进这个日志文件，方便非管理员窗口回头看（默认放临时目录）
    [string]$LogPath = (Join-Path $env:TEMP 'fix-wsl2-hypervisor.log'),
    # 只诊断不改动：先看看现状就加这个开关
    [switch]$WhatIfOnly,
    # 跳过「启用 Windows 功能」，只把 hypervisorlaunchtype 改回 auto
    [switch]$SkipFeatureEnable,
    # 顺手重建 Docker 的两个 WSL 发行版（会丢掉里面的镜像与卷；发行版已经坏了才需要）
    [switch]$ResetDockerDistros
)

$ErrorActionPreference = 'Continue'

function Say([string]$message) {
    Write-Host $message
    Add-Content -LiteralPath $LogPath -Value $message -Encoding UTF8
}

Set-Content -LiteralPath $LogPath -Value ("== WSL2 / Docker 修复报告 {0} ==" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')) -Encoding UTF8

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
Say ("管理员：{0}" -f $isAdmin)
if (-not $isAdmin) {
    Say '这个脚本要以管理员身份运行（启用 Windows 功能、改 bcdedit 都需要）。请用「以管理员身份运行」的 PowerShell 再跑一次。'
    exit 2
}

Say ''
Say '---- 现状 ----'
Say ("CPU 虚拟化（BIOS）：{0}" -f (Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty VirtualizationFirmwareEnabled))
Say ("Hypervisor 在跑吗：{0}" -f (Get-CimInstance Win32_ComputerSystem).HypervisorPresent)
Say ("WSL 第一行版本信息：{0}" -f (((& wsl.exe --version 2>&1 | Out-String) -split "`r?`n")[0]).Trim())

# 1) Windows 功能：WSL2 只要这两个（Windows 家庭版没有 Hyper-V，所以不强求 Hyper-V）
$features = @('VirtualMachinePlatform', 'Microsoft-Windows-Subsystem-Linux')
$needReboot = $false
foreach ($name in $features) {
    $state = (Get-WindowsOptionalFeature -Online -FeatureName $name).State
    Say ("功能 {0}：{1}" -f $name, $state)
    if ($state -ne 'Enabled' -and -not $WhatIfOnly -and -not $SkipFeatureEnable) {
        Say ("  → 正在启用 {0} ..." -f $name)
        $result = Enable-WindowsOptionalFeature -Online -FeatureName $name -All -NoRestart
        Say ("  → 已启用 {0}；稍后需要重启：{1}" -f $name, $result.RestartNeeded)
        $needReboot = $true
    }
}

# 2) 启动类型：被别的工具（模拟器 / 「优化」软件）改成 off 时，功能开着也起不来
Say ''
Say '---- Hyper-V 启动类型 ----'
$bcd = (& bcdedit.exe /enum '{current}' 2>&1 | Select-String 'hypervisorlaunchtype')
if (-not $bcd) {
    Say 'hypervisorlaunchtype：未设置（默认就是 Auto，这一项没问题）'
} else {
    Say $bcd.Line.Trim()
    if ($bcd -match 'Off') {
        if ($WhatIfOnly) {
            Say '  → 是 Off，需要改回 auto（去掉 -WhatIfOnly 再跑一次）'
        } else {
            & bcdedit.exe /set hypervisorlaunchtype auto | Out-Null
            Say '  → 已改回 auto，重启后生效'
            $needReboot = $true
        }
    }
}

# 3) 可选：发行版坏了就重建（会丢镜像 / 卷；Docker Desktop 下次启动会自己重新创建这两个发行版）
if ($ResetDockerDistros) {
    Say ''
    Say '---- 重建 Docker 的 WSL 发行版（会丢掉其中的镜像与卷）----'
    $installed = (& wsl.exe -l -q 2>&1 | Out-String) -split "`r?`n" | ForEach-Object { $_.Trim() }
    foreach ($distro in @('docker-desktop', 'docker-desktop-data')) {
        if ($installed -notcontains $distro) { Say ("{0}：不存在，跳过" -f $distro); continue }
        if ($WhatIfOnly) { Say ("{0}：存在，会被注销（这次没执行）" -f $distro); continue }
        & wsl.exe --unregister $distro | Out-Null
        Say ("{0}：已注销，Docker Desktop 启动时会重新创建" -f $distro)
    }
}

Say ''
Say '---- 结论 ----'
if ($WhatIfOnly) {
    Say '这是只诊断模式，什么都没改。按上面的提示去掉 -WhatIfOnly 再跑一次。'
} elseif ($needReboot) {
    Say '有改动：**请重启电脑**，然后再启动 Docker Desktop。'
} else {
    Say '功能与启动类型看起来都正常；如果 WSL 还是起不来，把这份日志和 wsl --version 一起拿去查。'
}
Say ("日志：{0}" -f $LogPath)
