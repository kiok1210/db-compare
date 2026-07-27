# Extract and start portable MySQL (Aliyun zip), create dual DBs for loadtest
# Usage: .\scripts\loadtest\install-portable-mysql.ps1

param(
    [string]$ZipPath = "D:\db-compare\tools\downloads\mysql-8.0.28-winx64.zip",
    [string]$InstallRoot = "D:\db-compare\tools\mysql-8.0.28",
    [string]$RootPassword = "root",
    [int]$Port = 3306
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $ZipPath)) {
    throw "Zip not found: $ZipPath"
}
$zipSize = (Get-Item $ZipPath).Length
if ($zipSize -lt 150MB) {
    throw "Zip incomplete ($([math]::Round($zipSize/1MB,1)) MB), wait for download"
}

$parent = Split-Path $InstallRoot -Parent
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$mysqldPath = Join-Path $InstallRoot "bin\mysqld.exe"
if (-not (Test-Path $mysqldPath)) {
    Write-Host "Extracting MySQL to $InstallRoot ..."
    if (Test-Path $InstallRoot) { Remove-Item $InstallRoot -Recurse -Force }
    Expand-Archive -Path $ZipPath -DestinationPath $parent -Force
    $inner = Get-ChildItem $parent -Directory | Where-Object { $_.Name -like 'mysql-8.0.*' } | Select-Object -First 1
    if ($null -ne $inner -and $inner.FullName -ne $InstallRoot) {
        if (Test-Path $InstallRoot) { Remove-Item $InstallRoot -Recurse -Force }
        Rename-Item -Path $inner.FullName -NewName (Split-Path $InstallRoot -Leaf)
    }
}

$bin = Join-Path $InstallRoot "bin"
$data = Join-Path $InstallRoot "data"
$ini = Join-Path $InstallRoot "my.ini"
$basedirUnix = $InstallRoot -replace '\\','/'
$datadirUnix = $data -replace '\\','/'
@(
    "[mysqld]",
    "basedir=$basedirUnix",
    "datadir=$datadirUnix",
    "port=$Port",
    "character-set-server=utf8mb4",
    "collation-server=utf8mb4_unicode_ci",
    "max_allowed_packet=256M",
    "innodb_buffer_pool_size=512M",
    "default_authentication_plugin=mysql_native_password"
) | Set-Content -Path $ini -Encoding ASCII

$mysqlDataMarker = Join-Path $data "mysql"
if (-not (Test-Path $mysqlDataMarker)) {
    Write-Host "Initializing data directory..."
    & (Join-Path $bin "mysqld.exe") --defaults-file=$ini --initialize-insecure --console
    if ($LASTEXITCODE -ne 0) { throw "mysqld --initialize-insecure failed" }
}

$listening = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if (-not $listening) {
    Write-Host "Starting mysqld on port $Port ..."
    Start-Process -FilePath (Join-Path $bin "mysqld.exe") -ArgumentList "--defaults-file=$ini","--console" -WindowStyle Hidden
    $ok = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 2
        & (Join-Path $bin "mysqladmin.exe") --user=root --host=127.0.0.1 --port=$Port ping 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ok = $true; break }
    }
    if (-not $ok) { throw "mysqld start timeout" }
} else {
    Write-Host "Port $Port already listening, skip start"
}

Write-Host "Configuring root password and dual databases..."
$sql = @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$RootPassword';
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '$RootPassword';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
CREATE DATABASE IF NOT EXISTS loadtest DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS loadtest_tgt DEFAULT CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'sync'@'%' IDENTIFIED BY 'sync123';
CREATE USER IF NOT EXISTS 'sync'@'localhost' IDENTIFIED BY 'sync123';
CREATE USER IF NOT EXISTS 'sync'@'127.0.0.1' IDENTIFIED BY 'sync123';
GRANT ALL PRIVILEGES ON loadtest.* TO 'sync'@'%';
GRANT ALL PRIVILEGES ON loadtest.* TO 'sync'@'localhost';
GRANT ALL PRIVILEGES ON loadtest.* TO 'sync'@'127.0.0.1';
GRANT ALL PRIVILEGES ON loadtest_tgt.* TO 'sync'@'%';
GRANT ALL PRIVILEGES ON loadtest_tgt.* TO 'sync'@'localhost';
GRANT ALL PRIVILEGES ON loadtest_tgt.* TO 'sync'@'127.0.0.1';
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
$tmp = Join-Path $env:TEMP "init-loadtest.sql"
Set-Content -Path $tmp -Value $sql -Encoding ASCII
$mysqlExe = Join-Path $bin "mysql.exe"
& $mysqlExe --user=root --host=127.0.0.1 --port=$Port --connect-expired-password -e "source $tmp"
if ($LASTEXITCODE -ne 0) {
    & $mysqlExe --user=root --password=$RootPassword --host=127.0.0.1 --port=$Port -e "source $tmp"
}
Remove-Item $tmp -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "Portable MySQL ready:"
Write-Host "  root=$InstallRoot"
Write-Host "  port=$Port root/$RootPassword"
Write-Host "  src=jdbc:mysql://127.0.0.1:$Port/loadtest?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
Write-Host "  tgt=jdbc:mysql://127.0.0.1:$Port/loadtest_tgt?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
Write-Host "  user=sync/sync123"
