# Acceptance item 4: home feed scroll frame time with PresentMon.
#
# 30 s of continuous wheel scrolling on the home feed, then frame-time statistics
# from the PresentMon CSV. Definition of a dropped frame is written into the summary:
# an inter-present interval above 1.5 x the 165 Hz budget (9.09 ms).
#
# Exit codes: 0 measured, 3 paused by tools\PAUSE, 4 machine busy, 5 measurement failed.
[CmdletBinding()]
param(
    [int]$Seconds = 30,
    [int]$WheelIntervalMs = 40,
    [int]$SettleSeconds = 12,
    [double]$RefreshHz = 165,
    [string]$Tag = 'run1'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'BpVerify.Common.ps1')

$dataDir = Initialize-BpRun -Name "frametime-$Tag"

if (Test-BpPause) {
    Write-BpLog 'PAUSE file present: measurement paused, nothing was started.'
    exit 3
}
if (-not (Test-BpInteractiveSession)) { exit 7 }
if (-not (Test-BpMachineQuiet)) {
    Write-BpLog 'machine busy: measurement skipped.'
    exit 4
}

$exe = Get-BpAppExe
$csv = Join-Path $dataDir 'presentmon.csv'
$totalSeconds = $SettleSeconds + $Seconds + 8
$appPid = 0
$since = [datetime]::MinValue
$cap = $null
$summary = [ordered]@{
    item = 4; tag = $Tag; startedUtc = (Get-Date).ToUniversalTime().ToString('o')
    exe = $exe; seconds = $Seconds; refreshHz = $RefreshHz
}
try {
    $cap = Start-BpPresentMon -OutputCsv $csv -Seconds $totalSeconds
    Start-Sleep -Seconds 2
    $run = Start-BpAppViaTask -Exe $exe -MaxSeconds ($totalSeconds + 25)
    $appPid = $run.Pid
    $since = $run.Since
    $win = Wait-BpWindow -ProcessId $appPid -Since $since -TimeoutMs 60000
    $hwnd = $win.Hwnd
    $uiPid = $win.Pid
    Write-BpLog ("window=0x{0:x} ui_pid={1} root_pid={2}" -f [int64]$hwnd, $uiPid, $appPid)
    [void][BpMeasure]::ShowWindow($hwnd, 9)   # SW_RESTORE
    [void][BpMeasure]::SetForegroundWindow($hwnd)

    # Let the home feed finish its first load so the measurement does not count it.
    Write-BpLog "settling for $SettleSeconds s"
    Start-Sleep -Seconds $SettleSeconds

    $thumbBefore = Get-BpScreenThumb -Hwnd $hwnd

    # Idle baseline: 3 s without input, so we can prove the scroll actually moved the feed.
    $idleStart = (Get-Date).ToUniversalTime()
    Start-Sleep -Seconds 3
    $idleEnd = (Get-Date).ToUniversalTime()

    Write-BpLog "scrolling for $Seconds s (wheel every $WheelIntervalMs ms, posted to this window only)"
    $scrollStart = (Get-Date).ToUniversalTime()
    $deadline = (Get-Date).AddSeconds($Seconds)
    $posted = 0
    while ((Get-Date) -lt $deadline) {
        if (Test-BpPause) {
            Write-BpLog 'PAUSE file appeared mid-run: stopping the client we started.'
            Stop-BpApp -ProcessId $appPid -Since $since
            exit 3
        }
        if (-not [BpMeasure]::Responds($hwnd)) { Write-BpLog 'window stopped responding during the scroll' }
        Send-BpWheelReal -Hwnd $hwnd -Notches 3
        $posted++
        Start-Sleep -Milliseconds $WheelIntervalMs
    }
    $scrollEnd = (Get-Date).ToUniversalTime()
    $thumbAfter = Get-BpScreenThumb -Hwnd $hwnd
    $scrollDiff = Compare-BpThumb -A $thumbBefore -B $thumbAfter
    $summary.scroll_screen_diff = $scrollDiff
    $summary.scroll_effect = ($null -ne $scrollDiff -and $scrollDiff -ge 3)
    Write-BpLog "scroll finished, $posted wheel events injected, screen diff=$scrollDiff"

    Stop-BpPresentMon -Capture $cap
    $parsed = Read-BpPresentMonCsv -Path $csv
    $rows = @($parsed.Rows | Where-Object { $_.ProcessId -eq "$uiPid" })
    Write-BpLog ("presentmon rows=" + $rows.Count)

    $hasWall = ($rows | Where-Object { $_.WallClock }).Count -gt 0
    if ($hasWall) {
        $scrollRows = @($rows | Where-Object { $_.WallClock -and $_.WallClock -ge $scrollStart -and $_.WallClock -le $scrollEnd })
        $idleRows = @($rows | Where-Object { $_.WallClock -and $_.WallClock -ge $idleStart -and $_.WallClock -le $idleEnd })
        $summary.clock = 'presentmon --date_time (wall clock)'
    } else {
        # Fall back to PresentMon's own time base, anchored on its capture start.
        $t0 = $cap.StartedUtc
        $scrollRows = @($rows | Where-Object { $t0.AddSeconds($_.TimeSeconds) -ge $scrollStart -and $t0.AddSeconds($_.TimeSeconds) -le $scrollEnd })
        $idleRows = @($rows | Where-Object { $t0.AddSeconds($_.TimeSeconds) -ge $idleStart -and $t0.AddSeconds($_.TimeSeconds) -le $idleEnd })
        $summary.clock = 'TimeInSeconds anchored on capture start (approximate)'
        Write-BpLog 'no wall-clock column in the CSV: using TimeInSeconds anchored on capture start'
    }

    $scrollIntervals = @($scrollRows | Where-Object { $null -ne $_.BetweenMs } | ForEach-Object { $_.BetweenMs })
    $idleFrames = $idleRows.Count
    $stats = Get-BpFrameStats -Intervals $scrollIntervals -RefreshHz $RefreshHz
    $summary.scroll_frames = $scrollRows.Count
    $summary.idle_frames_3s = $idleFrames
    $summary.scroll_wheel_events = $posted
    if ($stats) {
        foreach ($k in $stats.PSObject.Properties.Name) { $summary["scroll_$k"] = $stats.$k }
    }
    $summary.dropped_definition = 'interval > 1.5 x 6.06 ms (165 Hz budget)'
    $summary.ui_pid = $uiPid
    $summary.swapchains = @($rows | Group-Object SwapChain | ForEach-Object { $_.Name }) -join ';'
    $summary.ok = ($null -ne $stats)
    if (-not $stats) { $summary.reason = 'no frames matched the scroll window' }

    $summary.endedUtc = (Get-Date).ToUniversalTime().ToString('o')
    $summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 -Path (Join-Path $dataDir 'summary.json')
    Write-BpLog ('SUMMARY ' + ($summary | ConvertTo-Json -Depth 4 -Compress))
    exit 0
} catch {
    $summary.ok = $false
    $summary.reason = $_.Exception.Message
    $summary.endedUtc = (Get-Date).ToUniversalTime().ToString('o')
    $summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 -Path (Join-Path $dataDir 'summary.json')
    Write-BpLog ("FAILED: " + $_.Exception.Message)
    exit 5
} finally {
    if ($cap) { Stop-BpPresentMon -Capture $cap }
    if ($appPid -gt 0) { Stop-BpApp -ProcessId $appPid -Since $since }
}
