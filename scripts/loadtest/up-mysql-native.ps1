# 无 Docker 时：假定本机已安装 MySQL，用双库模拟源/目标
# 用法（管理员或 mysql 在 PATH）：
#   .\scripts\loadtest\up-mysql-native.ps1 -RootPassword root

param(
    [string]$HostName = "127.0.0.1",
    [int]$Port = 3306,
    [string]$RootUser = "root",
    [Parameter(Mandatory = $true)][string]$RootPassword,
    [string]$MysqlExe = "mysql"
)

$ErrorActionPreference = "Stop"
if (-not (Get-Command $MysqlExe -ErrorAction SilentlyContinue)) {
    $candidates = @(
        "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
        "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
        "C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { $MysqlExe = $c; break }
    }
}
if (-not (Test-Path $MysqlExe) -and -not (Get-Command $MysqlExe -ErrorAction SilentlyContinue)) {
    Write-Host "找不到 mysql 客户端。请先安装 MySQL，或先解决 Docker 后使用 up-mysql.ps1。"
    Write-Host "见 scripts/loadtest/DOCKER_CN.md"
    exit 1
}

$sql = @"
CREATE DATABASE IF NOT EXISTS loadtest DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS loadtest_tgt DEFAULT CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'sync'@'%' IDENTIFIED BY 'sync123';
CREATE USER IF NOT EXISTS 'sync'@'localhost' IDENTIFIED BY 'sync123';
GRANT ALL PRIVILEGES ON loadtest.* TO 'sync'@'%';
GRANT ALL PRIVILEGES ON loadtest.* TO 'sync'@'localhost';
GRANT ALL PRIVILEGES ON loadtest_tgt.* TO 'sync'@'%';
GRANT ALL PRIVILEGES ON loadtest_tgt.* TO 'sync'@'localhost';
FLUSH PRIVILEGES;
USE loadtest;
CREATE TABLE IF NOT EXISTS big_sync_60m (
  id BIGINT NOT NULL, c1 VARCHAR(64) NOT NULL, c2 INT NOT NULL,
  c3 DECIMAL(18,2) NOT NULL, created_at DATETIME NOT NULL, PRIMARY KEY (id)
) ENGINE=InnoDB;
USE loadtest_tgt;
CREATE TABLE IF NOT EXISTS big_sync_60m (
  id BIGINT NOT NULL, c1 VARCHAR(64) NOT NULL, c2 INT NOT NULL,
  c3 DECIMAL(18,2) NOT NULL, created_at DATETIME NOT NULL, PRIMARY KEY (id)
) ENGINE=InnoDB;
"@

$tmp = [System.IO.Path]::GetTempFileName() + ".sql"
Set-Content -Path $tmp -Value $sql -Encoding UTF8
& $MysqlExe -h$HostName -P$Port -u$RootUser "-p$RootPassword" --default-character-set=utf8mb4 -e "source $tmp"
$code = $LASTEXITCODE
Remove-Item $tmp -Force -ErrorAction SilentlyContinue
if ($code -ne 0) { exit $code }

Write-Host @"

双库已就绪（同一 MySQL 实例）：
  源   jdbc:mysql://${HostName}:${Port}/loadtest?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
  目标 jdbc:mysql://${HostName}:${Port}/loadtest_tgt?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
  账号 sync / sync123

灌数：
  .\scripts\loadtest\generate-60m.ps1 -JdbcUrl "jdbc:mysql://${HostName}:${Port}/loadtest?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" -User sync -Password sync123 -Rows 1000000

应用里注册两个数据源（库名不同），同步 includeTables 用 loadtest::big_sync_60m，目标 schema 映射到 loadtest_tgt。
"@
