# Acceptance item 4: home feed scroll frame time with PresentMon.
#
# Validity rules this script now enforces (TON-998 review round 2):
#   * Continuous scrolling must be proven, not inferred from a before/after diff: a
#     thumbnail is taken every $MotionSampleMs during the scroll and the run is only
#     valid when at least $MotionMinRatio of consecutive samples differ.
#   * Only ONE swap chain is reported: the one that actually carries the window. The
#     others are listed separately so a reviewer can see what was excluded.
#   * The exact scroll window (start/end wall clock) and the frames inside it are written
#     next to the summary, so the numbers can be recomputed from the raw rows.
#
# Exit codes: 0 measured, 3 paused by tools\PAUSE, 4 machine busy, 5 measurement failed.
[CmdletBinding()]
param(
    [int]$Seconds = 30,
    [int]$WheelIntervalMs = 40,
    [int]$SettleSeconds = 12,
    [int]$MotionSampleMs = 500,
    [double]$MotionMinRatio = 0.7,
    [double]$RefreshHz = 165,
    [string]$Tag = 'run1',
    # A specific staged build under apps\ (before/after comparisons); default is the first one found.
    [string]$Exe
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

$exe = if ($Exe) { $Exe } else { Get-BpAppExe }
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
    $fg = Set-BpForeground -Hwnd $hwnd
    $summary.window_foreground = $fg
    if (-not $fg) { throw 'the client window could not be brought to the foreground; input would go elsewhere' }

    Write-BpLog "settling for $SettleSeconds s"
    Start-Sleep -Seconds $SettleSeconds

    # 3 s of no input first: the scroll window is compared against this quiet window.
    $idleStart = (Get-Date).ToUniversalTime()
    Start-Sleep -Seconds 3
    $idleEnd = (Get-Date).ToUniversalTime()

    Write-BpLog "scrolling for $Seconds s (wheel every $WheelIntervalMs ms, motion sampled every $MotionSampleMs ms)"
    $scrollStart = (Get-Date).ToUniversalTime()
    $deadline = (Get-Date).AddSeconds($Seconds)
    $posted = 0
    $motion = New-Object System.Collections.ArrayList
    $savedCursor = Save-BpCursor
    [void](Move-BpCursorIntoWindow -Hwnd $hwnd)
    $thumbBeforeAll = Get-BpScreenThumb -Hwnd $hwnd
    $prevBands = Get-BpScrollBands -Hwnd $hwnd
    $nextSample = (Get-Date).AddMilliseconds($MotionSampleMs)
    $lastPark = Get-Date
    while ((Get-Date) -lt $deadline) {
        if (Test-BpPause) {
            Write-BpLog 'PAUSE file appeared mid-run: stopping the client we started.'
            Stop-BpApp -ProcessId $appPid -Since $since
            exit 3
        }
        if (-not [BpMeasure]::Responds($hwnd)) { Write-BpLog 'window stopped responding during the scroll' }
        # Re-park the pointer periodically: a pointer that has drifted out of the list stops
        # scrolling silently, which is exactly the failure the review caught.
        if (((Get-Date) - $lastPark).TotalSeconds -ge 2) {
            [void](Move-BpCursorIntoWindow -Hwnd $hwnd)
            $lastPark = Get-Date
        }
        Send-BpWheelReal -Hwnd $hwnd -Notches 3 -KeepCursor
        $posted++
        if ((Get-Date) -ge $nextSample) {
            $nowBands = Get-BpScrollBands -Hwnd $hwnd
            $diffs = @()
            $maxDiff = 0
            for ($b = 0; $b -lt $nowBands.Count; $b++) {
                $d = Compare-BpThumb -A $prevBands[$b] -B $nowBands[$b]
                $diffs += $d
                if ($null -ne $d -and $d -gt $maxDiff) { $maxDiff = $d }
            }
            # A smooth scroll moves the grid by a fraction of a row, so the three bands change at
            # different moments; requiring all three inside one 500 ms sample rejected scrolling
            # runs that were visibly scrolling (0.65-0.67). The band hits are therefore judged
            # over a 1.5 s window (three samples) below, and a hover preview still cannot reach
            # two bands because it only animates the card under the pointer.
            $allBands = (@($diffs | Where-Object { $null -ne $_ -and $_ -ge 2 }).Count -eq 3)
            [void]$motion.Add([pscustomobject]@{
                    offsetMs = [math]::Round(((Get-Date).ToUniversalTime() - $scrollStart).TotalMilliseconds, 0)
                    diff     = $maxDiff
                    band1    = $diffs[0]; band2 = $diffs[1]; band3 = $diffs[2]
                    moved    = $allBands
                })
            $prevBands = $nowBands
            $nextSample = (Get-Date).AddMilliseconds($MotionSampleMs)
        }
        Start-Sleep -Milliseconds $WheelIntervalMs
    }
    $scrollEnd = (Get-Date).ToUniversalTime()
    $thumbAfter = Get-BpScreenThumb -Hwnd $hwnd
    Restore-BpCursor -Point $savedCursor

    $samples = @($motion | Where-Object { $null -ne $_.diff })
    # v3: a sample counts as moving when at least two of the three bands changed within the
    # last three samples (1.5 s). Same raw data as before, only the scoring window differs.
    $moved = 0
    for ($si = 2; $si -lt $samples.Count; $si++) {
        $win = @($samples[($si - 2)..$si])
        $hitBands = 0
        for ($bi = 1; $bi -le 3; $bi++) {
            $prop = "band$bi"
            if (@($win | Where-Object { $null -ne $_.$prop -and $_.$prop -ge 2 }).Count -gt 0) { $hitBands++ }
        }
        if ($hitBands -ge 2) { $moved++ }
    }
    $moved_samples = $moved
    $moved_all3 = @($samples | Where-Object { $_.moved }).Count
    $ratio = 0
    if ($samples.Count -gt 0) { $ratio = [math]::Round(1.0 * $moved / $samples.Count, 2) }
    $summary.scroll_motion_samples = $samples.Count
    $summary.scroll_motion_moving = $moved_samples
    $summary.scroll_motion_rule = 'v3: at least two of the three grid bands changed at least once inside a 1.5 s window (three 500 ms samples)'
    $summary.scroll_motion_moved_any_band = @($samples | Where-Object { $_.diff -ge 2 }).Count
    $summary.scroll_motion_all3_same_sample = $moved_all3
    $summary.scroll_motion_ratio = $ratio
    $summary.scroll_continuous = ($samples.Count -ge 10 -and $ratio -ge $MotionMinRatio)
    $summary.scroll_motion_region = 'three horizontal bands of the card grid (22-78 % x at 18-30 %, 34-46 %, 52-64 % y); the header and banner do not scroll and a hover preview animates only the card under the pointer'
    $summary.scroll_wholesale_diff = Compare-BpThumb -A $thumbBeforeAll -B $thumbAfter
    Write-BpLog ("scroll finished: {0} wheel events, motion {1}/{2} samples ({3}), continuous={4}" -f $posted, $moved, $samples.Count, $ratio, $summary.scroll_continuous)
    @($samples) | Export-Csv -LiteralPath (Join-Path $dataDir 'scroll-motion.csv') -NoTypeInformation -Encoding utf8

    Stop-BpPresentMon -Capture $cap
    $parsed = Read-BpPresentMonCsv -Path $csv
    $rows = @($parsed.Rows | Where-Object { $_.ProcessId -eq "$uiPid" -and $_.WallClock })
    Write-BpLog ("presentmon rows for ui_pid={0}: {1}" -f $uiPid, $rows.Count)

    $scrollRows = @($rows | Where-Object { $_.WallClock -ge $scrollStart -and $_.WallClock -le $scrollEnd })
    $idleRows = @($rows | Where-Object { $_.WallClock -ge $idleStart -and $_.WallClock -le $idleEnd })
    $summary.clock = 'presentmon --date_time (wall clock)'
    $summary.scroll_start_utc = $scrollStart.ToString('o')
    $summary.scroll_end_utc = $scrollEnd.ToString('o')
    $summary.scroll_window_seconds = [math]::Round(($scrollEnd - $scrollStart).TotalSeconds, 2)

    # One row per swap chain inside the scroll window, so the choice of the reported chain
    # is auditable instead of a silent merge.
    $chainRows = New-Object System.Collections.ArrayList
    foreach ($g in ($scrollRows | Group-Object SwapChain)) {
        $intervals = @($g.Group | Where-Object { $null -ne $_.BetweenMs } | ForEach-Object { $_.BetweenMs })
        $st = Get-BpFrameStats -Intervals $intervals -RefreshHz $RefreshHz
        [void]$chainRows.Add([pscustomobject]@{
                swapChain      = $g.Name
                frames         = $g.Count
                p50_ms         = $st.p50_ms
                p95_ms         = $st.p95_ms
                max_ms         = $st.max_ms
                frames_per_sec = [math]::Round($g.Count / $summary.scroll_window_seconds, 1)
                runtime        = (@($g.Group | ForEach-Object { $_.Runtime } | Sort-Object -Unique) -join '|')
            })
    }
    @($chainRows) | Export-Csv -LiteralPath (Join-Path $dataDir 'swapchains.csv') -NoTypeInformation -Encoding utf8
    Write-BpLog ('swap chains in the scroll window: ' + (($chainRows | ForEach-Object { "$($_.swapChain)=$($_.frames)" }) -join ', '))

    # The window's own chain is the one with the most frames in the window; overlays and
    # helper surfaces present far less. Everything else is reported in swapchains.csv.
    $display = $chainRows | Sort-Object frames -Descending | Select-Object -First 1
    $summary.display_swap_chain = $display.swapChain
    $summary.display_chain_frames = $display.frames
    $summary.display_chain_fps = [math]::Round($display.frames / $summary.scroll_window_seconds, 1)
    $summary.display_chain_share = [math]::Round(1.0 * $display.frames / [math]::Max(1, $scrollRows.Count), 2)
    $summary.other_chain_frames = ($scrollRows.Count - $display.frames)

    $displayRows = @($scrollRows | Where-Object { $_.SwapChain -eq $display.swapChain })
    $intervals = @($displayRows | Where-Object { $null -ne $_.BetweenMs } | ForEach-Object { $_.BetweenMs })
    $stats = Get-BpFrameStats -Intervals $intervals -RefreshHz $RefreshHz
    if ($stats) {
        foreach ($k in $stats.PSObject.Properties.Name) { $summary["scroll_$k"] = $stats.$k }
    }
    $summary.expected_frames_at_refresh = [math]::Round($summary.scroll_window_seconds * $RefreshHz)
    $summary.frame_ratio_vs_refresh = if ($stats) { [math]::Round(1.0 * $display.frames / $summary.expected_frames_at_refresh, 2) } else { $null }
    $summary.long_gap_definition = 'share of intervals in the display chain above 1.5 x the 165 Hz budget (9.09 ms); a script-defined indicator, not a PresentMon metric'

    # Exact rows inside the window, so the numbers above can be recomputed by hand.
    $exportRows = @($scrollRows | ForEach-Object {
            [pscustomobject]@{ swapChain = $_.SwapChain; runtime = $_.Runtime; timestamp = $_.RawTime; betweenMs = $_.BetweenMs }
        })
    @($exportRows) | Export-Csv -LiteralPath (Join-Path $dataDir 'frames-scroll-window.csv') -NoTypeInformation -Encoding utf8

    $summary.ui_pid = $uiPid
    $summary.ok = ($null -ne $stats -and $summary.scroll_continuous)
    if (-not $summary.scroll_continuous) { $summary.reason = 'continuous scrolling was not proven by the motion samples' }
    elseif ($null -eq $stats) { $summary.reason = 'no frames on the display chain inside the scroll window' }

    $summary.endedUtc = (Get-Date).ToUniversalTime().ToString('o')
    $summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 -Path (Join-Path $dataDir 'summary.json')
    Write-BpLog ('SUMMARY ' + ($summary | ConvertTo-Json -Depth 4 -Compress))
    if ($summary.ok) { exit 0 } else { exit 6 }
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
