# 发起 6000 万表同步
# 用法：
#   .\scripts\loadtest\start-sync-60m.ps1 -SourceDsId 1 -TargetDsId 2
#   .\scripts\loadtest\start-sync-60m.ps1 -SourceDsId 1 -TargetDsId 2 -BaseUrl http://88.x.x.x:8080

param(
    [Parameter(Mandatory = $true)][long]$SourceDsId,
    [Parameter(Mandatory = $true)][long]$TargetDsId,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$IncludeTables = "loadtest::big_sync_60m",
    [string]$SyncMode = "FULL_DDL",
    [int]$BatchSize = 50000
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root

$argsLine = "$BaseUrl $SourceDsId $TargetDsId $IncludeTables $SyncMode $BatchSize"
& mvn -q -DskipTests exec:java "-Dexec.classpathScope=test" `
    "-Dexec.mainClass=com.gcoll.dbcompare.loadtest.StartBigTableSync" `
    "-Dexec.args=$argsLine"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
