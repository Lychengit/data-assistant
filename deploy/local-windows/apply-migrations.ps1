<#
把 deploy\migrations 下的建表脚本按序应用到本机 PostgreSQL（原生安装，没有容器那套入口脚本）。

为什么需要它：容器里是 PostgreSQL 镜像的 docker-entrypoint-initdb.d 自动执行的；
本机原生安装没有这个环节，所以补一个幂等的小脚本。

幂等怎么保证：本脚本只认「自己建的一张记账表」schema_migration_local。
已经记过的文件不会重复执行，没记过的按文件名排序执行；执行失败即中止（ON_ERROR_STOP），
并把已经成功的那些留在记账表里，下次接着往下跑。

用法：
  .\apply-migrations.ps1 -User assistant -Password '<本机 PG 口令>'
  # 也可以先把口令放进环境变量 PGPASSWORD，然后省略 -Password
#>
param(
    [string]$User = 'assistant',
    [string]$Password = $env:PGPASSWORD,
    [string]$Database = 'doctor_assistant',
    [string]$DbHost = '127.0.0.1',
    [int]$Port = 5432,
    # 想只看会执行什么、不真的执行，就加 -WhatIfOnly
    [switch]$WhatIfOnly
)

$ErrorActionPreference = 'Stop'

$migrationsDir = Join-Path (Split-Path $PSScriptRoot -Parent) 'migrations'
if (-not (Test-Path -LiteralPath $migrationsDir)) { throw "找不到迁移目录：$migrationsDir" }

# 找 psql：先看 PATH，再在本机常见的 PostgreSQL 安装位置里按版本号从高到低找。
function Find-Psql {
    $onPath = Get-Command psql -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $candidates = @()
    foreach ($root in @('D:\PostgreSQL', 'C:\Program Files\PostgreSQL')) {
        if (Test-Path -LiteralPath $root) {
            $candidates += Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { Join-Path $_.FullName 'bin\psql.exe' }
        }
    }
    foreach ($candidate in $candidates) { if (Test-Path -LiteralPath $candidate) { return $candidate } }
    return $null
}

$psql = Find-Psql
if (-not $psql) { throw '没找到 psql。请把 PostgreSQL 的 bin 目录加进 PATH，或在本脚本的 Find-Psql 里补上安装路径。' }

if ($Password) { $env:PGPASSWORD = $Password }

function Invoke-Sql {
    param([Parameter(Mandatory)][string]$Sql)
    $output = & $psql -h $DbHost -p $Port -U $User -d $Database -v ON_ERROR_STOP=1 -t -A -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "SQL 执行失败：$Sql`n$output" }
    return $output
}

Write-Host "psql：$psql"
Write-Host "目标：$User@$DbHost`:$Port/$Database"

# 记账表：只记「本脚本执行过哪些文件」，与 Flyway 的 schema_history 无关。
$createTrackingTable = 'create table if not exists schema_migration_local (' +
    ' file_name text primary key, applied_at timestamptz not null default now())'
Invoke-Sql -Sql $createTrackingTable | Out-Null

$applied = @(Invoke-Sql -Sql 'select file_name from schema_migration_local')
# 按**版本号**排序，不是按文件名字符串排：字符串排序会把 V10 排在 V2 前面，
# 于是 V13（引用 V2 建的 data_access_audit）会先于 V2 执行，从零建库时必然失败。
$files = Get-ChildItem -LiteralPath $migrationsDir -Filter 'V*.sql' |
    Sort-Object { [int]($_.Name -replace '^V(\d+)__.*$', '$1') }
Write-Host "共 $($files.Count) 个脚本，已应用 $($applied.Count) 个"

$pending = @($files | Where-Object { $applied -notcontains $_.Name })
if ($pending.Count -eq 0) { Write-Host '没有需要执行的脚本，数据库已是最新'; exit 0 }

foreach ($file in $pending) {
    Write-Host "  执行 $($file.Name)"
    if ($WhatIfOnly) { continue }
    $output = & $psql -h $DbHost -p $Port -U $User -d $Database -v ON_ERROR_STOP=1 -q -f $file.FullName 2>&1
    if ($LASTEXITCODE -ne 0) { throw "执行失败：$($file.Name)`n$output" }
    Invoke-Sql -Sql "insert into schema_migration_local (file_name) values ('$($file.Name)')" | Out-Null
}
Write-Host '完成'