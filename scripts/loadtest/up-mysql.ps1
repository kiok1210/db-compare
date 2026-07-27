# 安装/启动本地 MySQL 双实例（Docker）
# 用法：
#   .\scripts\loadtest\up-mysql.ps1
#   .\scripts\loadtest\up-mysql.ps1 -InstallDocker   # 尝试 winget 安装 Docker Desktop

param(
    [switch]$InstallDocker
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$compose = Join-Path $PSScriptRoot "docker-compose.yml"

function Test-DockerReady {
    try {
        docker info 2>$null | Out-Null
        return ($LASTEXITCODE -eq 0)
    } catch {
        return $false
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    if (-not $InstallDocker) {
        Write-Host @"
未检测到 Docker。请先安装 Docker Desktop（需启用 WSL2 或 Hyper-V），然后重新打开终端再执行本脚本。

快速安装（管理员 PowerShell）：
  winget install -e --id Docker.DockerDesktop --source winget

若下载一直为 0 字节（访问不了 desktop.docker.com），见：
  scripts\loadtest\DOCKER_CN.md

或本机已有 MySQL：
  .\scripts\loadtest\up-mysql-native.ps1 -RootPassword <密码>

说明：GoldenDB 无公开 Docker 镜像，本地压测用 MySQL 8（协议兼容）。
"@
        exit 1
    }
    Write-Host "正在通过 winget 安装 Docker Desktop ..."
    winget install -e --id Docker.DockerDesktop --accept-package-agreements --accept-source-agreements
    Write-Host "安装完成后请启动 Docker Desktop，等待引擎就绪，再重新运行：.\scripts\loadtest\up-mysql.ps1"
    exit 0
}

if (-not (Test-DockerReady)) {
    Write-Host "Docker 已安装但引擎未就绪。请打开 Docker Desktop，等待托盘图标就绪后重试。"
    exit 1
}

Set-Location $root
Write-Host "启动 MySQL 源(3307) / 目标(3308) ..."
docker compose -f $compose up -d
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "等待健康检查 ..."
$deadline = (Get-Date).AddMinutes(3)
do {
    $s = docker inspect -f "{{.State.Health.Status}}" dbcompare-mysql-source 2>$null
    $t = docker inspect -f "{{.State.Health.Status}}" dbcompare-mysql-target 2>$null
    if ($s -eq "healthy" -and $t -eq "healthy") { break }
    Start-Sleep -Seconds 3
} while ((Get-Date) -lt $deadline)

Write-Host @"

已启动：
  源库  jdbc:mysql://127.0.0.1:3307/loadtest  user=sync / sync123  (root/root)
  目标  jdbc:mysql://127.0.0.1:3308/loadtest  user=sync / sync123

灌 100 万冒烟：
  .\scripts\loadtest\generate-60m.ps1 -JdbcUrl "jdbc:mysql://127.0.0.1:3307/loadtest?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" -User sync -Password sync123 -Rows 1000000

灌 6000 万：
  .\scripts\loadtest\generate-60m.ps1 -JdbcUrl "jdbc:mysql://127.0.0.1:3307/loadtest?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" -User sync -Password sync123

在应用中注册两个数据源后：
  .\scripts\loadtest\start-sync-60m.ps1 -SourceDsId <源ID> -TargetDsId <目标ID>
"@
