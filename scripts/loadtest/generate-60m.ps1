# 生成源库大表（Windows PowerShell）
# 用法：
#   .\scripts\loadtest\generate-60m.ps1 -JdbcUrl "jdbc:mysql://127.0.0.1:3306/loadtest" -User root -Password xxx
#   .\scripts\loadtest\generate-60m.ps1 ... -Rows 1000000   # 先跑 100 万冒烟

param(
    [Parameter(Mandatory = $true)][string]$JdbcUrl,
    [Parameter(Mandatory = $true)][string]$User,
    [Parameter(Mandatory = $true)][string]$Password,
    [long]$Rows = 60000000,
    [int]$Batch = 2000,
    [string]$Table = "big_sync_60m"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root

$argsLine = "$JdbcUrl $User $Password $Rows $Batch $Table"
Write-Host "生成 $Rows 行 → $Table @ $root"
& mvn -q -DskipTests exec:java "-Dexec.classpathScope=test" `
    "-Dexec.mainClass=com.gcoll.dbcompare.loadtest.BigTableLoadGenerator" `
    "-Dexec.args=$argsLine"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
