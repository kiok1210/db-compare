<#
.SYNOPSIS
  渲染 DataX 作业 JSON 并执行（customer_payments / gcoll_tasks_func）
.EXAMPLE
  .\render-and-run.ps1 -Table customer_payments
  .\render-and-run.ps1 -All
#>
param(
    [ValidateSet('customer_payments', 'gcoll_tasks_func')]
    [string]$Table,
    [switch]$All,
    [string]$ConnectionsFile = (Join-Path $PSScriptRoot 'connections.properties'),
    [switch]$RenderOnly
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jobsDir = Join-Path $root 'jobs'
$outDir = Join-Path $root 'out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

if (-not (Test-Path $ConnectionsFile)) {
    throw "缺少 $ConnectionsFile ，请先复制 connections.example.properties 并填写"
}

$props = @{}
Get-Content $ConnectionsFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $i = $line.IndexOf('=')
    if ($i -lt 1) { return }
    $props[$line.Substring(0, $i).Trim()] = $line.Substring($i + 1).Trim()
}

foreach ($k in @('SRC_JDBC','SRC_USER','SRC_PASSWORD','TGT_JDBC','TGT_USER','TGT_PASSWORD','DATAX_HOME')) {
    if (-not $props.ContainsKey($k) -or [string]::IsNullOrWhiteSpace($props[$k])) {
        throw "connections.properties 缺少 $k"
    }
}
if (-not $props.ContainsKey('CHANNEL') -or [string]::IsNullOrWhiteSpace($props['CHANNEL'])) {
    $props['CHANNEL'] = '8'
}
$clearMode = 'DELETE'
if ($props.ContainsKey('CLEAR_MODE') -and -not [string]::IsNullOrWhiteSpace($props['CLEAR_MODE'])) {
    $clearMode = $props['CLEAR_MODE'].Trim().ToUpperInvariant()
}
$writeMode = 'insert'
if ($props.ContainsKey('WRITE_MODE') -and -not [string]::IsNullOrWhiteSpace($props['WRITE_MODE'])) {
    $writeMode = $props['WRITE_MODE'].Trim().ToLowerInvariant()
    if ($writeMode -ne 'replace') { $writeMode = 'insert' }
}
if (-not $props.ContainsKey('WRITE_MODE')) {
    $props['WRITE_MODE'] = $writeMode
} else {
    $props['WRITE_MODE'] = $writeMode
}

$dataxPy = Join-Path $props['DATAX_HOME'] 'bin\datax.py'
if (-not (Test-Path $dataxPy)) {
    $nested = Join-Path $props['DATAX_HOME'] 'datax\bin\datax.py'
    if (Test-Path $nested) { $dataxPy = $nested }
}
if (-not (Test-Path $dataxPy)) {
    throw "未找到 DataX: $($props['DATAX_HOME'])\bin\datax.py （检查 DATAX_HOME）"
}

function Get-PreSqlBlock([string]$tableName) {
    switch ($clearMode) {
        'TRUNCATE' { return ('"preSql": ["TRUNCATE TABLE `' + $tableName + '`"],') }
        'DELETE'   { return ('"preSql": ["DELETE FROM `' + $tableName + '`"],') }
        'NONE'     { return '' }
        default    { return ('"preSql": ["DELETE FROM `' + $tableName + '`"],') }
    }
}

function Render-Job([string]$name) {
    $tpl = Join-Path $jobsDir ($name + '.json.template')
    if (-not (Test-Path $tpl)) { throw "模板不存在: $tpl" }
    $text = Get-Content $tpl -Raw -Encoding UTF8
    foreach ($k in $props.Keys) {
        $text = $text.Replace('{{' + $k + '}}', $props[$k])
    }
    $text = $text.Replace('{{PRE_SQL_BLOCK}}', (Get-PreSqlBlock $name))
    $out = Join-Path $outDir ($name + '.json')
    [System.IO.File]::WriteAllText($out, $text, [System.Text.UTF8Encoding]::new($false))
    Write-Host "已生成 $out (CLEAR_MODE=$clearMode WRITE_MODE=$writeMode)"
    return $out
}

function Run-Datax([string]$jobJson) {
    Write-Host "==== DataX: $jobJson ===="
    & python $dataxPy $jobJson
    if ($LASTEXITCODE -ne 0) {
        throw "DataX 失败 exit=$LASTEXITCODE job=$jobJson"
    }
}

$tables = @()
if ($All) {
    $tables = @('customer_payments', 'gcoll_tasks_func')
} elseif ($Table) {
    $tables = @($Table)
} else {
    throw "请指定 -Table <name> 或 -All"
}

foreach ($t in $tables) {
    $json = Render-Job $t
    if (-not $RenderOnly) {
        Run-Datax $json
    }
}

Write-Host "全部完成。"
