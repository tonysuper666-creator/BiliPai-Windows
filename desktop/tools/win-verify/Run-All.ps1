# Runs the whole U1 measurement set and writes one summary table.
#
#   -Full        every measured item (30 min soak). ~45-60 minutes of machine time.
#   -Quick       1 iteration each of the interactive items, 5 min soak (harness self test).
#
# Each step writes its own data folder under measure\data; this script collects the
# summary.json files into measure\data\<run>\summary.<phase>.json plus a totals table.
#
# Exit codes: 0 all steps measured, 3 paused by tools\PAUSE, 5 one or more steps failed.
[CmdletBinding()]
param(
    [string]$Tag = 'run1',
    [switch]$Full,
    [switch]$Quick
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'BpVerify.Common.ps1')

$dataDir = Initialize-BpRun -Name "all-$Tag"
if (Test-BpPause) { Write-BpLog 'PAUSE file present: nothing was started.'; exit 3 }
if (-not (Test-BpInteractiveSession)) { exit 7 }

if ($Quick) {
    $plan = @(
        @{ name = 'pausegate'; script = 'Test-PauseGate.ps1'; args = @() },
        @{ name = 'coldstart'; script = 'Measure-ColdStart.ps1'; args = @('-Iterations', '1') },
        @{ name = 'clicktoframe'; script = 'Measure-ClickToFrame.ps1'; args = @('-Iterations', '1') },
        @{ name = 'frametime'; script = 'Measure-FrameTime.ps1'; args = @('-Seconds', '10', '-SettleSeconds', '6') },
        @{ name = 'idle'; script = 'Measure-Idle.ps1'; args = @('-SettleSeconds', '15', '-SampleSeconds', '15', '-PauseSeconds', '15') },
        @{ name = 'soak'; script = 'Measure-Soak.ps1'; args = @('-Minutes', '5') }
    )
} else {
    $plan = @(
        @{ name = 'pausegate'; script = 'Test-PauseGate.ps1'; args = @() },
        @{ name = 'coldstart'; script = 'Measure-ColdStart.ps1'; args = @('-Iterations', '5') },
        @{ name = 'clicktoframe'; script = 'Measure-ClickToFrame.ps1'; args = @('-Iterations', '20') },
        @{ name = 'frametime'; script = 'Measure-FrameTime.ps1'; args = @() },
        @{ name = 'idle'; script = 'Measure-Idle.ps1'; args = @() },
        @{ name = 'soak'; script = 'Measure-Soak.ps1'; args = @('-Minutes', '30') }
    )
}

$rows = New-Object System.Collections.ArrayList
foreach ($step in $plan) {
    if (Test-BpPause) { Write-BpLog ("PAUSE appeared before '{0}': stopping the set" -f $step.name); break }
    $scriptPath = Join-Path $PSScriptRoot $step.script
    $childLog = Join-Path $script:BpLogDir ("all-{0}-{1}.log" -f $step.name, $script:BpRunStamp)
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scriptPath) + $step.args + @('-Tag', $Tag)
    Write-BpLog ("--> {0} {1}" -f $step.name, ($step.args -join ' '))
    $child = Start-Process -FilePath 'powershell.exe' -PassThru -WindowStyle Hidden -ArgumentList $argList `
        -RedirectStandardOutput $childLog -RedirectStandardError ([IO.Path]::ChangeExtension($childLog, '.err.log'))
    [void]$child.WaitForExit()
    Write-BpLog ("<-- {0} exit={1} log={2}" -f $step.name, $child.ExitCode, $childLog)
    $latest = Get-ChildItem $script:BpDataDir -Directory -Filter ("{0}-*" -f $step.name) |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $sumPath = if ($latest) { Join-Path $latest.FullName 'summary.json' } else { $null }
    $sum = $null
    if ($sumPath -and (Test-Path $sumPath)) { $sum = Get-Content $sumPath -Raw -Encoding utf8 | ConvertFrom-Json }
    if ($sumPath) { Copy-Item -LiteralPath $sumPath -Destination (Join-Path $dataDir ("summary.{0}.json" -f $step.name)) -Force }
    [void]$rows.Add([pscustomobject]@{
        step       = $step.name
        exit_code  = $child.ExitCode
        ok         = $(if ($sum) { $sum.ok } else { $false })
        data_dir   = $(if ($latest) { $latest.FullName } else { '' })
        detail     = $(if ($sum) { ($sum | ConvertTo-Json -Depth 4 -Compress) } else { 'no summary.json' })
    })
}

Write-BpCsv -Rows $rows -Path (Join-Path $dataDir 'all-steps.csv')
$table = $rows | Format-Table step, exit_code, ok -AutoSize | Out-String
Write-Host $table
$failed = @($rows | Where-Object { $_.exit_code -ne 0 }).Count
$rows | ForEach-Object { Write-BpLog ("RESULT {0} exit={1} ok={2}" -f $_.step, $_.exit_code, $_.ok) }
if ($failed -eq 0) { exit 0 } else { exit 5 }
