<#
重启本机 MinIO。改了 minio.env（口令 / 端口）或换了二进制之后用它。
等价于 .\minio-start.ps1 -Force，单独放一个只是让名字更直白。
#>
. (Join-Path $PSScriptRoot '_minio-common.ps1')
& (Join-Path $PSScriptRoot 'minio-start.ps1') -Force