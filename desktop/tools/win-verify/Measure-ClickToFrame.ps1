# Acceptance item 21: click a home-feed card, measure until the first video frame.
#
# Each iteration is one fresh launch (home loaded), then a single left click posted to a
# fixed point of the video-card grid, then the first frame presented on a NEW swap chain
# (the player's surface) is taken as "picture appeared". PresentMon reports --date_time so
# the click and the frame share one wall clock.
#
# Exit codes: 0 measured, 3 paused by tools\PAUSE, 4 machine busy, 5 measurement failed.
[CmdletBinding()]
param(
    [int]$Iterations = 20,
    [int]$SettleSeconds = 12,
    [int]$PostClickCaptureSeconds = 20,
    [double]$ClickXRatio = 0.30,
    [double]$ClickYRatio = 0.45,
    [string]$Tag = 'run1'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'BpVerify.Common.ps1')

$dataDir = Initialize-BpRun -Name "clicktoframe-$Tag"
if (Test-BpPause) { Write-BpLog 'PAUSE file present: measurement paused, nothing was started.'; exit 3 }
if (-not (Test-BpInteractiveSession)) { exit 7 }
if (-not (Test-BpMachineQuiet)) { Write-BpLog 'machine busy: measurement skipped.'; exit 4 }

$exe = Get-BpAppExe
$results = New-Object System.Collections.ArrayList
$failures = New-Object System.Collections.ArrayList
$perRunSeconds = $SettleSeconds + $PostClickCaptureSeconds + 8

for ($i = 1; $i -le $Iterations; $i++) {
    if (Test-BpPause) { Write-BpLog 'PAUSE file present: stopping the series.'; break }
    $csv = Join-Path $dataDir ("presentmon-{0:d2}.csv" -f $i)
    $appPid = 0
    $since = [datetime]::MinValue
    $cap = $null
    try {
        Write-BpLog ("--- iteration {0}/{1} ---" -f $i, $Iterations)
        $cap = Start-BpPresentMon -OutputCsv $csv -Seconds ($perRunSeconds + 15)
        Start-Sleep -Seconds 2
        $run = Start-BpAppViaTask -Exe $exe -MaxSeconds ($perRunSeconds + 20)
        $appPid = $run.Pid
        $since = $run.Since
        $win = Wait-BpWindow -ProcessId $appPid -Since $since -TimeoutMs 60000
        $hwnd = $win.Hwnd
        $uiPid = $win.Pid
        [void][BpMeasure]::ShowWindow($hwnd, 9)
        [void][BpMeasure]::SetForegroundWindow($hwnd)
        Start-Sleep -Seconds $SettleSeconds

        $rect = New-Object BpMeasure+RECT
        [void][BpMeasure]::GetClientRect($hwnd, [ref]$rect)
        $clickX = [int]($rect.Right * $ClickXRatio)
        $clickY = [int]($rect.Bottom * $ClickYRatio)
        Write-BpLog ("client={0}x{1} click=({2},{3})" -f $rect.Right, $rect.Bottom, $clickX, $clickY)
        $thumbBefore = Get-BpScreenThumb -Hwnd $hwnd
        $clickUtc = Send-BpClickReal -Hwnd $hwnd -X $clickX -Y $clickY
        Write-BpLog ("clicked at " + $clickUtc.ToString('o'))

        # Screen-based "picture appeared": the first thumbnail that differs from the feed.
        $changeMs = $null
        $maxDiff = 0
        $deadline = (Get-Date).AddSeconds($PostClickCaptureSeconds)
        while ((Get-Date) -lt $deadline) {
            if (Test-BpPause) { throw 'PAUSE appeared during the wait' }
            Start-Sleep -Milliseconds 150
            $now = Get-BpScreenThumb -Hwnd $hwnd
            $diff = Compare-BpThumb -A $thumbBefore -B $now
            if ($null -ne $diff -and $diff -gt $maxDiff) { $maxDiff = $diff }
            if ($null -ne $diff -and $diff -ge 6 -and -not $changeMs) {
                $changeMs = [math]::Round(((Get-Date).ToUniversalTime() - $clickUtc).TotalMilliseconds, 0)
                Write-BpLog ("screen changed after {0} ms (diff {1})" -f $changeMs, $diff)
            }
        }
        Stop-BpPresentMon -Capture $cap

        # PresentMon is only a cross-check here; a missing capture must not lose a sample
        # whose screen-change timing is already measured.
        $parsed = $null
        try { $parsed = Read-BpPresentMonCsv -Path $csv } catch { Write-BpLog ("presentmon unusable: " + $_.Exception.Message) }
        $rows = @()
        if ($parsed) { $rows = @($parsed.Rows | Where-Object { $_.ProcessId -eq "$uiPid" -and $_.WallClock } | Sort-Object WallClock) }
        $preClick = @($rows | Where-Object { $_.WallClock -lt $clickUtc } | ForEach-Object { $_.SwapChain } | Sort-Object -Unique)
        $postClick = @($rows | Where-Object { $_.WallClock -ge $clickUtc })
        $newChain = @($postClick | Where-Object { $preClick -notcontains $_.SwapChain })
        $method = 'new_swap_chain'
        $ms = $null
        if ($newChain.Count -gt 0) {
            $ms = [math]::Round(($newChain[0].WallClock.ToUniversalTime() - $clickUtc).TotalMilliseconds, 0)
        } elseif ($postClick.Count -gt 0) {
            # No separate surface: fall back to the first frame after the navigation started.
            $method = 'first_frame_after_click'
            $ms = [math]::Round(($postClick[0].WallClock.ToUniversalTime() - $clickUtc).TotalMilliseconds, 0)
        } else {
            $method = 'screen_only'
        }
        $row = [ordered]@{
            iteration       = $i
            pid             = $uiPid
            launcher_pid    = $appPid
            clickUtc        = $clickUtc.ToString('o')
            click_to_frame_ms = $ms
            click_to_change_ms = $changeMs
            screen_diff_max = $maxDiff
            method          = $method
            swapchains_before = ($preClick -join ';')
            swapchains_after  = (@($postClick | ForEach-Object { $_.SwapChain } | Sort-Object -Unique) -join ';')
            frames_after_click = $postClick.Count
        }
        [void]$results.Add([pscustomobject]$row)
        Write-BpLog ('iter {0}: click_to_frame={1} ms method={2} frames_after={3}' -f $i, $ms, $method, $postClick.Count)
    } catch {
        Write-BpLog ("iteration {0} failed: {1}" -f $i, $_.Exception.Message)
        [void]$failures.Add([pscustomobject]@{ iteration = $i; error = $_.Exception.Message })
    } finally {
        if ($cap) { Stop-BpPresentMon -Capture $cap }
        if ($appPid -gt 0) { Stop-BpApp -ProcessId $appPid -Since $since }
    }
    Start-Sleep -Seconds 5
}

$summary = [ordered]@{
    item = 21; tag = $Tag; iterations = $Iterations
    ok = ($failures.Count -eq 0 -and (@($results | Where-Object { $_.click_to_change_ms }).Count -gt 0))
    failures = $failures
}
$vals = @($results | ForEach-Object { $_.click_to_frame_ms } | Where-Object { $null -ne $_ })
if ($vals.Count -gt 0) {
    $sorted = $vals | Sort-Object
    $summary.samples = $vals.Count
    $summary.click_to_frame_p50_ms = $sorted[[int][math]::Floor(($sorted.Count - 1) * 0.5)]
    $summary.click_to_frame_p95_ms = $sorted[[int][math]::Floor(($sorted.Count - 1) * 0.95)]
    $summary.click_to_frame_max_ms = $sorted[$sorted.Count - 1]
}
$changes = @($results | ForEach-Object { $_.click_to_change_ms } | Where-Object { $null -ne $_ })
if ($changes.Count -gt 0) {
    $cs = $changes | Sort-Object
    $summary.click_to_change_samples = $cs.Count
    $summary.click_to_change_p50_ms = $cs[[int][math]::Floor(($cs.Count - 1) * 0.5)]
    $summary.click_to_change_p95_ms = $cs[[int][math]::Floor(($cs.Count - 1) * 0.95)]
    $summary.click_verified = $true
}
if ($results.Count -gt 0) {
    $summary.methods = (@($results | Group-Object method | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join ',')
}
Write-BpCsv -Rows $results -Path (Join-Path $dataDir 'iterations.csv')
$summary | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 -Path (Join-Path $dataDir 'summary.json')
Write-BpLog ('SUMMARY ' + ($summary | ConvertTo-Json -Depth 5 -Compress))
if ($summary.ok) { exit 0 } else { exit 5 }
