# Acceptance item 21: click a home-feed card, measure until the picture changes.
#
# Endpoint is the same for every sample (TON-998 review round 2): the first on-screen
# change of the app window after the click, sampled from a thumbnail every 150 ms. A
# sample that never changes inside $ObserveSeconds is recorded as a timeout failure and is
# never counted as a success. PresentMon still runs, but only to say whether the player
# surface (a new swap chain) appeared - it is not the timing source any more.
#
# Exit codes: 0 measured, 3 paused by tools\PAUSE, 4 machine busy, 5 measurement failed.
[CmdletBinding()]
param(
    [int]$Iterations = 20,
    [int]$SettleSeconds = 12,
    [int]$ObserveSeconds = 15,
    [int]$BudgetMs = 3000,
    [double]$ClickXRatio = 0.30,
    [double]$ClickYRatio = 0.30,
    [int]$MotionWindow = 20,
    [int]$MotionWindowHits = 15,
    [int]$CalibrationShots = 3,
    [string]$Tag = 'run1'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'BpVerify.Common.ps1')

Add-Type -AssemblyName System.Drawing
$dataDir = Initialize-BpRun -Name "clicktoframe-$Tag"
if (Test-BpPause) { Write-BpLog 'PAUSE file present: measurement paused, nothing was started.'; exit 3 }
if (-not (Test-BpInteractiveSession)) { exit 7 }
if (-not (Test-BpMachineQuiet)) { Write-BpLog 'machine busy: measurement skipped.'; exit 4 }

$exe = Get-BpAppExe
$results = New-Object System.Collections.ArrayList
$failures = New-Object System.Collections.ArrayList
$perRunSeconds = $SettleSeconds + $ObserveSeconds + 10

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
        if (-not (Set-BpForeground -Hwnd $hwnd)) { throw 'the client window could not be brought to the foreground' }
        Start-Sleep -Seconds $SettleSeconds
        if (-not (Test-BpForeground -Hwnd $hwnd)) { throw 'the client window lost the foreground before the click' }

        $rect = New-Object BpMeasure+RECT
        [void][BpMeasure]::GetClientRect($hwnd, [ref]$rect)
        $clickX = [int]($rect.Right * $ClickXRatio)
        $clickY = [int]($rect.Bottom * $ClickYRatio)
        Write-BpLog ("client={0}x{1} click=({2},{3})" -f $rect.Right, $rect.Bottom, $clickX, $clickY)

        $before = Get-BpScreenThumbCenter -Hwnd $hwnd
        $clickUtc = Send-BpClickReal -Hwnd $hwnd -X $clickX -Y $clickY
        Write-BpLog ("clicked at " + $clickUtc.ToString('o'))

        # "Picture on screen" means the player is drawing, not that the page repainted once:
        # the baseline must have changed AND two consecutive samples must differ from each
        # other (motion). A one-shot repaint (hover, pressed state, page transition) never
        # produces two consecutive moving samples, so it can no longer be counted as success.
        # "Picture on screen" must survive the page transition: a paused player, a hover state
        # or a navigation animation all move for a moment. The endpoint is the first sample
        # where at least $MotionWindowHits of the last $MotionWindow samples are moving, i.e.
        # the picture keeps changing instead of settling.
        $changeMs = $null
        $series = New-Object System.Collections.ArrayList
        $prev = $before
        $flags = New-Object System.Collections.ArrayList
        $offsets = New-Object System.Collections.ArrayList
        $deadline = (Get-Date).AddSeconds($ObserveSeconds)
        while ((Get-Date) -lt $deadline) {
            if (Test-BpPause) { throw 'PAUSE appeared during the wait' }
            Start-Sleep -Milliseconds 150
            $now = Get-BpScreenThumbCenter -Hwnd $hwnd
            $vsBase = Compare-BpThumb -A $before -B $now
            $vsPrev = Compare-BpThumb -A $prev -B $now
            $offset = [math]::Round(((Get-Date).ToUniversalTime() - $clickUtc).TotalMilliseconds, 0)
            [void]$series.Add([pscustomobject]@{ offsetMs = $offset; diffBase = $vsBase; diffPrev = $vsPrev })
            $movingNow = ($null -ne $vsPrev -and $vsPrev -ge 2 -and $null -ne $vsBase -and $vsBase -ge 6)
            [void]$flags.Add($movingNow)
            [void]$offsets.Add($offset)
            if ($flags.Count -ge $MotionWindow) {
                $window = $flags.GetRange($flags.Count - $MotionWindow, $MotionWindow)
                $hits = @($window | Where-Object { $_ }).Count
                if ($hits -ge $MotionWindowHits) {
                    $changeMs = $offsets[$flags.Count - $MotionWindow]
                    # Keep the frame that triggered the decision for the first few iterations so
                    # "sustained motion" can be checked against what was actually on screen.
                    if ($i -le $CalibrationShots) {
                        try {
                            $shot = Join-Path $dataDir ("detected-{0:d2}.png" -f $i)
                            $rect2 = New-Object BpMeasure+RECT
                            [void][BpMeasure]::GetWindowRect($hwnd, [ref]$rect2)
                            $w2 = $rect2.Right - $rect2.Left; $h2 = $rect2.Bottom - $rect2.Top
                            $bmp = New-Object System.Drawing.Bitmap $w2, $h2
                            $g = [System.Drawing.Graphics]::FromImage($bmp)
                            $g.CopyFromScreen($rect2.Left, $rect2.Top, 0, 0, (New-Object System.Drawing.Size $w2, $h2), [System.Drawing.CopyPixelOperation]::SourceCopy)
                            $bmp.Save($shot, [System.Drawing.Imaging.ImageFormat]::Png)
                            $g.Dispose(); $bmp.Dispose()
                        } catch { Write-BpLog ('screenshot failed: ' + $_.Exception.Message) }
                    }
                    Write-BpLog ("player motion sustained after {0} ms ({1}/{2} samples moving)" -f $changeMs, $hits, $MotionWindow)
                    break
                }
            }
            $prev = $now
        }
        @($series) | Export-Csv -LiteralPath (Join-Path $dataDir ("change-series-{0:d2}.csv" -f $i)) -NoTypeInformation -Encoding utf8
        if ($null -eq $changeMs) { Write-BpLog ("no screen change within {0} s: recorded as a timeout" -f $ObserveSeconds) }

        Stop-BpPresentMon -Capture $cap
        $parsed = $null
        try { $parsed = Read-BpPresentMonCsv -Path $csv } catch { Write-BpLog ("presentmon unusable: " + $_.Exception.Message) }
        $rows = @()
        if ($parsed) { $rows = @($parsed.Rows | Where-Object { $_.ProcessId -eq "$uiPid" -and $_.WallClock } | Sort-Object WallClock) }
        $preChains = @($rows | Where-Object { $_.WallClock -lt $clickUtc } | ForEach-Object { $_.SwapChain } | Sort-Object -Unique)
        $newChain = @($rows | Where-Object { $_.WallClock -ge $clickUtc -and $preChains -notcontains $_.SwapChain })

        $result = 'changed'
        if ($null -eq $changeMs) { $result = 'timeout' }
        $row = [ordered]@{
            iteration          = $i
            pid                = $uiPid
            clickUtc           = $clickUtc.ToString('o')
            click_to_change_ms = $changeMs
            result             = $result
            within_budget      = ($null -ne $changeMs -and $changeMs -le $BudgetMs)
            new_player_chain   = ($newChain.Count -gt 0)
            frames_after_click = @($rows | Where-Object { $_.WallClock -ge $clickUtc }).Count
        }
        [void]$results.Add([pscustomobject]$row)
        Write-BpLog ('iter {0}: change={1} ms result={2} new_chain={3}' -f $i, $changeMs, $result, $row.new_player_chain)
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
    endpoint = 'first sample where at least 15 of the last 20 one-frame steps (3 s at 150 ms) change the centre of the window; a page transition or spinner cannot keep that up'
    endpoint_limits = 'screen change is a proxy: a video that starts on a still frame can be detected late, and this is not calibrated against a decoder-level first frame'
    observe_seconds = $ObserveSeconds; budget_ms = $BudgetMs
    timeouts = @($results | Where-Object { $_.result -eq 'timeout' }).Count
    over_budget = @($results | Where-Object { $_.result -eq 'changed' -and -not $_.within_budget }).Count
    failures = $failures
}
# Two views, both stated so neither can be mistaken for the other:
#   censored  - every attempted iteration counts; a timeout enters as "at least ObserveSeconds"
#               (this is the only one that can be called a p50/p95 over N launches)
#   detected  - only the iterations where a picture was actually seen; descriptive, N smaller
$censorMs = $ObserveSeconds * 1000
$censored = @($results | ForEach-Object { if ($null -ne $_.click_to_change_ms) { [double]$_.click_to_change_ms } else { [double]$censorMs } } | Sort-Object)
if ($censored.Count -gt 0) {
    $summary.percentile_basis = 'all attempted iterations, a timeout counts as >= ' + $censorMs + ' ms'
    $summary.attempts = $censored.Count
    $summary.click_to_change_p50_ms = $censored[[int][math]::Floor(($censored.Count - 1) * 0.5)]
    $summary.click_to_change_p95_ms = $censored[[int][math]::Floor(($censored.Count - 1) * 0.95)]
    $summary.click_to_change_max_ms = $censored[$censored.Count - 1]
    $summary.fraction_within_budget = [math]::Round(1.0 * @($censored | Where-Object { $_ -le $BudgetMs }).Count / $censored.Count, 2)
}
$vals = @($results | ForEach-Object { $_.click_to_change_ms } | Where-Object { $null -ne $_ })
if ($vals.Count -gt 0) {
    $sorted = $vals | Sort-Object
    $summary.detected_samples = $vals.Count
    $summary.detected_p50_ms = $sorted[[int][math]::Floor(($sorted.Count - 1) * 0.5)]
    $summary.detected_p95_ms = $sorted[[int][math]::Floor(($sorted.Count - 1) * 0.95)]
    $summary.detected_max_ms = $sorted[$sorted.Count - 1]
}
if ($results.Count -gt 0) {
    $summary.new_player_chain = @($results | Where-Object { $_.new_player_chain }).Count
}
$summary.ok = ($failures.Count -eq 0 -and $results.Count -eq $Iterations -and $summary.timeouts -eq 0)
Write-BpCsv -Rows $results -Path (Join-Path $dataDir 'iterations.csv')
$summary | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 -Path (Join-Path $dataDir 'summary.json')
Write-BpLog ('SUMMARY ' + ($summary | ConvertTo-Json -Depth 5 -Compress))
if ($summary.ok) { exit 0 } else { exit 5 }
