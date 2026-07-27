# 停止并删除容器（默认保留数据卷）
#   .\scripts\loadtest\down-mysql.ps1
#   .\scripts\loadtest\down-mysql.ps1 -Volumes   # 同时删数据

param([switch]$Volumes)

$ErrorActionPreference = "Stop"
$compose = Join-Path $PSScriptRoot "docker-compose.yml"
$args = @("compose", "-f", $compose, "down")
if ($Volumes) { $args += "-v" }
docker @args
