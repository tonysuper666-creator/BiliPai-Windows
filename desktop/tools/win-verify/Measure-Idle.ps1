# Acceptance items 23 (idle private memory) and 24 (minimized CPU, tools\PAUSE effect).
#
# Four phases on the same pid, each $SampleSeconds long:
#   foreground  window in front, no input  -> idle CPU and private memory
#   minimized   after SW_MINIMIZE          -> "background" CPU (the <= 1 % single-core target)
#   pause       tools\PAUSE present        -> does background work actually stop?
#   recovery    tools\PAUSE removed again  -> control: the same measurement can see work resume
#
# The pause verdict is runtime evidence only: a PAUSE file that changes neither CPU nor the
# process I/O counters means the client does not honour it, whatever the source says. The
# file is only created and removed when this run owns it.
#
# Exit codes: 0 measured, 3 paused by tools\PAUSE, 4 machine busy, 5 measurement failed.
[CmdletBinding()]
param(
    [int]$SettleSeconds = 45,
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
    param([int]$ProcessId, [int]$Seconds, [string]$Phase, [switch]$IgnorePause)
    $p = Get-Process -Id $ProcessId -ErrorAction Stop
    $cpu0 = $p.TotalProcessorTime.TotalSeconds
    $first = Get-BpProcessSample -ProcessId $ProcessId
    if (-not $first) { throw 'process disappeared before the phase started' }
    $ops0 = $null
    if ($first.IoAvailable) { $ops0 = $first.IoReadOps + $first.IoWriteOps + $first.IoOtherOps }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $inner = New-Object System.Collections.ArrayList
    while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
        if (-not $IgnorePause) {
            if ((Test-BpPause) -and ($Phase -ne 'pause')) {
                Write-BpLog 'PAUSE appeared: aborting this phase'
                break
            }
        }
        $s = Get-BpProcessSample -ProcessId $ProcessId
        if (-not $s) { throw 'process disappeared during the measurement' }
        $ioSum = $null
        if ($s.IoAvailable) { $ioSum = $s.IoReadOps + $s.IoWriteOps + $s.IoOtherOps }
        [void]$inner.Add([pscustomobject]@{
                phase = $Phase; ts = $s.TimestampUtc; privateMB = $s.PrivateMB
                cpuSeconds = $s.CpuSeconds; ioOps = $ioSum
                threads = $s.Threads; handles = $s.Handles
            })
        Start-Sleep -Seconds 1
    }
    $sw.Stop()
    $p.Refresh()
    $cpuDelta = $p.TotalProcessorTime.TotalSeconds - $cpu0
    $last = Get-BpProcessSample -ProcessId $ProcessId
    $opsDelta = $null
    $ioAvailable = ($null -ne $ops0 -and $last -and $last.IoAvailable)
    if ($ioAvailable) { $opsDelta = ($last.IoReadOps + $last.IoWriteOps + $last.IoOtherOps) - $ops0 }
    foreach ($row in $inner) { [void]$samples.Add($row) }
    $meanPrivate = 0
    if ($inner.Count -gt 0) { $meanPrivate = [math]::Round((($inner | Measure-Object -Property privateMB -Average).Average), 1) }
    [pscustomobject]@{
        phase           = $Phase
        seconds         = [math]::Round($sw.Elapsed.TotalSeconds, 1)
        cpu_pct_one     = [math]::Round(100.0 * $cpuDelta / [math]::Max(0.1, $sw.Elapsed.TotalSeconds), 2)
        io_ops          = $opsDelta
        io_available    = $ioAvailable
        private_mean_mb = $meanPrivate
        private_max_mb  = [math]::Round((($inner | Measure-Object -Property privateMB -Maximum).Maximum), 1)
    }
}

try {
    $run = Start-BpAppViaTask -Exe $exe -MaxSeconds ($SettleSeconds + $SampleSeconds * 4 + $PauseSeconds * 2 + 120)
    $appPid = $run.Pid
    $since = $run.Since
    $win = Wait-BpWindow -ProcessId $appPid -Since $since -TimeoutMs 60000
    $hwnd = $win.Hwnd
    $uiPid = $win.Pid
    Write-BpLog ("ui_pid={0} root_pid={1}" -f $uiPid, $appPid)
    [void][BpMeasure]::ShowWindow($hwnd, 9)
    [void][BpMeasure]::SetForegroundWindow($hwnd)
    Write-BpLog "settling $SettleSeconds s before the idle measurement"
    Start-Sleep -Seconds $SettleSeconds

    $fg = Measure-CpuWindow -ProcessId $uiPid -Seconds $SampleSeconds -Phase 'foreground'
    Write-BpLog ('foreground: cpu={0}% io={1} private_mean={2} MB' -f $fg.cpu_pct_one, $fg.io_ops, $fg.private_mean_mb)

    [void][BpMeasure]::ShowWindow($hwnd, 6)   # SW_MINIMIZE
    Start-Sleep -Seconds 10
    $min = Measure-CpuWindow -ProcessId $uiPid -Seconds $SampleSeconds -Phase 'minimized'
    Write-BpLog ('minimized: cpu={0}% io={1} private_mean={2} MB' -f $min.cpu_pct_one, $min.io_ops, $min.private_mean_mb)

    if (-not (Test-Path -LiteralPath $pausePath)) {
        New-Item -ItemType File -Path $pausePath -Force | Out-Null
        $createdPause = $true
        Write-BpLog 'created tools\PAUSE (this run owns it)'
    }
    Start-Sleep -Seconds 5
    $pause = Measure-CpuWindow -ProcessId $uiPid -Seconds $PauseSeconds -Phase 'pause' -IgnorePause
    Write-BpLog ('pause: cpu={0}% io={1} private_mean={2} MB' -f $pause.cpu_pct_one, $pause.io_ops, $pause.private_mean_mb)

    if ($createdPause -and (Test-Path -LiteralPath $pausePath)) {
        Remove-Item -LiteralPath $pausePath -Force
        Write-BpLog 'removed the tools\PAUSE this run created'
    }
    Start-Sleep -Seconds 5
    $recovery = Measure-CpuWindow -ProcessId $uiPid -Seconds $PauseSeconds -Phase 'recovery'
    Write-BpLog ('recovery: cpu={0}% io={1} private_mean={2} MB' -f $recovery.cpu_pct_one, $recovery.io_ops, $recovery.private_mean_mb)

    $summary.foreground = $fg
    $summary.minimized = $min
    $summary.pause_phase = $pause
    $summary.recovery_phase = $recovery
    $summary.launch_private_mb = $fg.private_mean_mb

    # Verdict on "does the client pause background work when tools\PAUSE exists".
    # Both CPU and I/O have to fall for a pause to count as effective.
    $cpuDrop = 0
    if ($min.cpu_pct_one -gt 0) { $cpuDrop = 1 - ($pause.cpu_pct_one / $min.cpu_pct_one) }
    $ioDrop = 0
    $ioComparable = ($min.io_available -and $pause.io_available -and $null -ne $min.io_ops -and $min.io_ops -gt 0)
    if ($ioComparable) { $ioDrop = 1 - ($pause.io_ops / $min.io_ops) }
    $summary.pause_cpu_drop_ratio = [math]::Round($cpuDrop, 3)
    $summary.pause_io_drop_ratio = [math]::Round($ioDrop, 3)
    $summary.pause_io_comparable = $ioComparable
    if ($ioComparable) { $summary.pause_io_ops_delta = ($pause.io_ops - $min.io_ops) }
    if ($pause.seconds -lt 10) {
        $summary.pause_verdict = 'not measured (phase too short)'
    } elseif ($ioComparable -and $min.io_ops -lt 200) {
        # 36 -> 25 operations per minute is noise, not a pause: the client was already idle.
        $summary.pause_verdict = 'not verifiable (background I/O already below 200 operations per minute when minimized; CPU unchanged, so no evidence either way)'
    } elseif (-not $ioComparable) {
        # Without real I/O counters the CPU reading alone cannot tell "nothing to pause" from
        # "pause honoured"; say so instead of guessing.
        $summary.pause_verdict = 'not verified (I/O counters unavailable, CPU alone is inconclusive)'
    } elseif ($cpuDrop -ge 0.5 -and $ioDrop -ge 0.5) {
        $summary.pause_verdict = 'effective (CPU and I/O both dropped while PAUSE existed)'
    } elseif ($cpuDrop -le 0.15 -and $ioDrop -le 0.15) {
        $summary.pause_verdict = 'not honoured (CPU and I/O unchanged with PAUSE present)'
    } else {
        $summary.pause_verdict = 'inconclusive (partial change)'
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
