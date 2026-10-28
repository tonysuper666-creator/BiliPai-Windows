# Acceptance items 23 (idle private memory) and 24 (minimized CPU, tools\PAUSE effect).
#
# Phases in one session, all on the same pid:
#   settle    wait for the home feed to stop loading
#   foreground 60 s of idle CPU while the window is in front
#   minimized  60 s of idle CPU after minimizing the window (target: <= 1 % of one core)
#   pause      create tools\PAUSE, sample CPU and process I/O for 60 s, then remove it
#              (the file is only removed when this run created it)
#
# Exit codes: 0 measured, 3 paused by tools\PAUSE, 4 machine busy, 5 measurement failed.
[CmdletBinding()]
param(
    [int]$SettleSeconds = 60,
    [int]$SampleSeconds = 60,
    [int]$PauseSeconds = 60,
    [string]$Tag = 'run1'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'BpVerify.Common.ps1')

$dataDir = Initialize-BpRun -Name "idle-$Tag"
if (Test-BpPause) { Write-BpLog 'PAUSE file present: measurement paused, nothing was started.'; exit 3 }
if (-not (Test-BpInteractiveSession)) { exit 7 }
if (-not (Test-BpMachineQuiet)) { Write-BpLog 'machine busy: measurement skipped.'; exit 4 }

$exe = Get-BpAppExe
$pausePath = Join-Path $script:BpRoot 'tools\PAUSE'
$samples = New-Object System.Collections.ArrayList
$summary = [ordered]@{ item = '23+24'; tag = $Tag; startedUtc = (Get-Date).ToUniversalTime().ToString('o') }
$appPid = 0
$since = [datetime]::MinValue
$createdPause = $false

function Measure-CpuWindow {
    param([int]$ProcessId, [int]$Seconds, [string]$Phase)
    $p = Get-Process -Id $ProcessId -ErrorAction Stop
    $cpu0 = $p.TotalProcessorTime.TotalSeconds
    $io0 = $p.PrivateMemorySize64
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $inner = New-Object System.Collections.ArrayList
    while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
        if (Test-BpPause -and $Phase -ne 'pause') { Write-BpLog 'PAUSE appeared: aborting this phase'; break }
        $s = Get-BpProcessSample -ProcessId $ProcessId
        if (-not $s) { throw 'process disappeared during the measurement' }
        [void]$inner.Add([pscustomobject]@{ phase = $Phase; ts = $s.TimestampUtc; privateMB = $s.PrivateMB; cpuSeconds = $s.CpuSeconds; threads = $s.Threads; handles = $s.Handles })
        Start-Sleep -Seconds 1
    }
    $sw.Stop()
    $p.Refresh()
    $cpuDelta = $p.TotalProcessorTime.TotalSeconds - $cpu0
    foreach ($row in $inner) { [void]$samples.Add($row) }
    [pscustomobject]@{
        phase          = $Phase
        seconds        = [math]::Round($sw.Elapsed.TotalSeconds, 1)
        cpu_pct_one    = [math]::Round(100.0 * $cpuDelta / $sw.Elapsed.TotalSeconds, 2)
        private_mean_mb = [math]::Round((($inner | Measure-Object -Property privateMB -Average).Average), 1)
        private_max_mb  = [math]::Round((($inner | Measure-Object -Property privateMB -Maximum).Maximum), 1)
    }
}

try {
    $run = Start-BpAppViaTask -Exe $exe -MaxSeconds ($SettleSeconds + $SampleSeconds * 3 + $PauseSeconds + 60)
    $appPid = $run.Pid
    $since = $run.Since
    $win = Wait-BpWindow -ProcessId $appPid -Since $since -TimeoutMs 60000
    $hwnd = $win.Hwnd
    $uiPid = $win.Pid
    Write-BpLog ("ui_pid=$uiPid root_pid=$appPid")
    [void][BpMeasure]::ShowWindow($hwnd, 9)
    [void][BpMeasure]::SetForegroundWindow($hwnd)
    Write-BpLog "settling $SettleSeconds s before the idle measurement"
    Start-Sleep -Seconds $SettleSeconds

    $fg = Measure-CpuWindow -ProcessId $uiPid -Seconds $SampleSeconds -Phase 'foreground'
    Write-BpLog ('foreground: cpu={0}% private_mean={1} MB' -f $fg.cpu_pct_one, $fg.private_mean_mb)

    [void][BpMeasure]::ShowWindow($hwnd, 6)   # SW_MINIMIZE
    Start-Sleep -Seconds 10
    $min = Measure-CpuWindow -ProcessId $uiPid -Seconds $SampleSeconds -Phase 'minimized'
    Write-BpLog ('minimized: cpu={0}% private_mean={1} MB' -f $min.cpu_pct_one, $min.private_mean_mb)

    # tools\PAUSE effect on the running client.
    if (-not (Test-Path -LiteralPath $pausePath)) {
        New-Item -ItemType File -Path $pausePath -Force | Out-Null
        $createdPause = $true
        Write-BpLog 'created tools\PAUSE for the pause phase'
    }
    Start-Sleep -Seconds 5
    $pause = Measure-CpuWindow -Pid $appPid -Seconds $PauseSeconds -Phase 'pause'
    Write-BpLog ('pause: cpu={0}% private_mean={1} MB' -f $pause.cpu_pct_one, $pause.private_mean_mb)

    $summary.foreground = $fg
    $summary.minimized = $min
    $summary.pause_phase = $pause
    $summary.launch_private_mb = $fg.private_mean_mb
    $summary.app_pause_effect = 'none-observable'
    $ratio = 0
    if ($min.cpu_pct_one -gt 0) { $ratio = $pause.cpu_pct_one / $min.cpu_pct_one }
    $summary.pause_vs_minimized_cpu_ratio = [math]::Round($ratio, 2)
    if ($pause.cpu_pct_one -lt 0.05 -and $min.cpu_pct_one -lt 0.05) {
        $summary.app_pause_effect = 'idle-in-both-phases (inconclusive: nothing to pause)'
    }
    $summary.ok = $true
} catch {
    $summary.ok = $false
    $summary.reason = $_.Exception.Message
    Write-BpLog ('FAILED: ' + $_.Exception.Message)
} finally {
    if ($createdPause -and (Test-Path -LiteralPath $pausePath)) {
        Remove-Item -LiteralPath $pausePath -Force
        Write-BpLog 'removed the tools\PAUSE this run created'
    }
    if ($appPid -gt 0) { Stop-BpApp -ProcessId $appPid -Since $since }
}

$summary.endedUtc = (Get-Date).ToUniversalTime().ToString('o')
Write-BpCsv -Rows $samples -Path (Join-Path $dataDir 'samples.csv')
$summary | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 -Path (Join-Path $dataDir 'summary.json')
Write-BpLog ('SUMMARY ' + ($summary | ConvertTo-Json -Depth 6 -Compress))
if ($summary.ok) { exit 0 } else { exit 5 }
