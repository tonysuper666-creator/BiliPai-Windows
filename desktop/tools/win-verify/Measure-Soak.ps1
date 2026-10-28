# Acceptance item 23: long-running playback soak (crash / hang / memory growth).
#
# Default is a 30 minute short run that proves the harness works; the 8 hour night run
# uses -Minutes 480 and must be started when the machine is free.
#
# Every 30 s: private memory, handled threads, window responsiveness (WM_NULL with a 1 s
# timeout). The run reports crash (process gone), hang (window not answering) and the
# private-memory growth relative to the first sample after the feed settled.
#
# Exit codes: 0 measured, 3 paused by tools\PAUSE, 4 machine busy, 5 measurement failed.
[CmdletBinding()]
param(
    [int]$Minutes = 30,
    [int]$SettleSeconds = 30,
    [int]$SampleSeconds = 30,
    [switch]$StartPlayback,
    [double]$ClickXRatio = 0.30,
    [double]$ClickYRatio = 0.45,
    [string]$Tag = 'run1'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'BpVerify.Common.ps1')

$dataDir = Initialize-BpRun -Name "soak-$Tag"
if (Test-BpPause) { Write-BpLog 'PAUSE file present: measurement paused, nothing was started.'; exit 3 }
if (-not (Test-BpInteractiveSession)) { exit 7 }
if (-not (Test-BpMachineQuiet)) { Write-BpLog 'machine busy: measurement skipped.'; exit 4 }
# tools\launch.ps1 refuses max_seconds above 3600 and kills the client at that point, so a
# single soak can only run inside that hour. The 8 hour night run needs a launch.ps1 change
# (owner decision, recorded in the TON-998 report) - this script says so instead of
# pretending the longer run happened.
$limit = [int][math]::Floor((3600 - $SettleSeconds - 120) / 60)
if ($Minutes -gt $limit) {
    Write-BpLog ("Minutes=$Minutes exceeds the {0} minute ceiling imposed by the launcher check-in (max_seconds 3600). Long night runs need a launcher change first." -f $limit)
    exit 6
}

$exe = Get-BpAppExe
$samples = New-Object System.Collections.ArrayList
$summary = [ordered]@{
    item = 23; tag = $Tag; minutes = $Minutes; startPlayback = [bool]$StartPlayback
    startedUtc = (Get-Date).ToUniversalTime().ToString('o')
}
$appPid = 0
$since = [datetime]::MinValue
$crash = $false
$hang = $false

try {
    $capSeconds = $Minutes * 60 + $SettleSeconds + 120
    $run = Start-BpAppViaTask -Exe $exe -MaxSeconds $capSeconds
    $appPid = $run.Pid
    $since = $run.Since
    $win = Wait-BpWindow -ProcessId $appPid -Since $since -TimeoutMs 60000
    $hwnd = $win.Hwnd
    $uiPid = $win.Pid
    Write-BpLog ("ui_pid=$uiPid root_pid=$appPid")
    [void][BpMeasure]::ShowWindow($hwnd, 9)
    [void][BpMeasure]::SetForegroundWindow($hwnd)
    Start-Sleep -Seconds $SettleSeconds

    if ($StartPlayback) {
        $rect = New-Object BpMeasure+RECT
        [void][BpMeasure]::GetClientRect($hwnd, [ref]$rect)
        $clickUtc = Send-BpClick -Hwnd $hwnd -X ([int]($rect.Right * $ClickXRatio)) -Y ([int]($rect.Bottom * $ClickYRatio))
        Write-BpLog ('started playback with a click at ' + $clickUtc.ToString('o'))
        Start-Sleep -Seconds 15
    }

    $deadline = (Get-Date).AddMinutes($Minutes)
    while ((Get-Date) -lt $deadline) {
        if (Test-BpPause) { Write-BpLog 'PAUSE appeared: soak stopped early'; break }
        $s = Get-BpProcessSample -ProcessId $uiPid
        if (-not $s) { $crash = $true; Write-BpLog 'process disappeared: crash/exit detected'; break }
        $responding = [BpMeasure]::Responds($hwnd)
        if (-not $responding) { $hang = $true; Write-BpLog 'window not responding: hang detected' }
        [void]$samples.Add([pscustomobject]@{
            ts        = $s.TimestampUtc
            privateMB = $s.PrivateMB
            workingMB = $s.WorkingSetMB
            threads   = $s.Threads
            handles   = $s.Handles
            responding = $responding
        })
        Start-Sleep -Seconds $SampleSeconds
    }

    $summary.samples = $samples.Count
    $summary.crash = $crash
    $summary.hang = $hang
    if ($samples.Count -ge 2) {
        $first = $samples[0].privateMB
        $last = $samples[$samples.Count - 1].privateMB
        $summary.private_first_mb = $first
        $summary.private_last_mb = $last
        $summary.private_max_mb = ($samples | Measure-Object -Property privateMB -Maximum).Maximum
        $summary.growth_pct = [math]::Round(100.0 * ($last - $first) / $first, 1)
    }
    $summary.ok = (-not $crash -and -not $hang)
} catch {
    $summary.ok = $false
    $summary.reason = $_.Exception.Message
    Write-BpLog ('FAILED: ' + $_.Exception.Message)
} finally {
    if ($appPid -gt 0) { Stop-BpApp -ProcessId $appPid -Since $since }
}

$summary.endedUtc = (Get-Date).ToUniversalTime().ToString('o')
Write-BpCsv -Rows $samples -Path (Join-Path $dataDir 'samples.csv')
$summary | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 -Path (Join-Path $dataDir 'summary.json')
Write-BpLog ('SUMMARY ' + ($summary | ConvertTo-Json -Depth 6 -Compress))
if ($summary.ok) { exit 0 } else { exit 5 }
