# Acceptance item 22: cold start, 5 launches, double-click to a usable home page.
#
# Two numbers per launch, both on one wall clock:
#   window_focus_ms     process creation -> the app's own window is visible and has keyboard
#                       focus (input can reach it). Precise: polled every 20 ms.
#   first_present_ms    process creation -> PresentMon's first presented frame for that
#                       process (picture on screen). PresentMon reports --date_time, so this
#                       is on the same wall clock; a missing column is reported instead of
#                       silently guessed.
#
# Exit codes: 0 measured, 3 paused by tools\PAUSE, 4 machine busy, 5 measurement failed.
[CmdletBinding()]
param(
    [int]$Iterations = 5,
    [int]$WaitForHomeSeconds = 6,
    [int]$BetweenIterationsSeconds = 8,
    [string]$Tag = 'run1'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'BpVerify.Common.ps1')

$dataDir = Initialize-BpRun -Name "coldstart-$Tag"
if (Test-BpPause) { Write-BpLog 'PAUSE file present: measurement paused, nothing was started.'; exit 3 }
if (-not (Test-BpInteractiveSession)) { exit 7 }
if (-not (Test-BpMachineQuiet)) { Write-BpLog 'machine busy: measurement skipped.'; exit 4 }

$exe = Get-BpAppExe
$results = New-Object System.Collections.ArrayList
$failures = New-Object System.Collections.ArrayList

for ($i = 1; $i -le $Iterations; $i++) {
    if (Test-BpPause) { Write-BpLog 'PAUSE file present: stopping the cold-start series.'; break }
    $csv = Join-Path $dataDir ("presentmon-{0:d2}.csv" -f $i)
    $appPid = 0
    $since = [datetime]::MinValue
    $cap = $null
    try {
        Write-BpLog ("--- iteration {0}/{1} ---" -f $i, $Iterations)
        # One PresentMon session per launch: it must be running before the exe appears.
        $cap = Start-BpPresentMon -OutputCsv $csv -Seconds 25
        Start-Sleep -Seconds 2
        $run = Start-BpAppViaTask -Exe $exe -MaxSeconds 40
        $appPid = $run.Pid
        $since = $run.Since
        # The outer launcher stub exits within a few hundred ms of Start-Process (the real
        # client is a second, orphaned stub plus the JVM), so t0 is the moment the launcher
        # reported the pid - which is right after it called Start-Process.
        $t0 = $run.Since.ToUniversalTime()

        $win = Wait-BpWindow -ProcessId $appPid -Since $since -TimeoutMs 60000
        $hwnd = $win.Hwnd
        $uiPid = $win.Pid
        $tWindow = (Get-Date).ToUniversalTime()
        $focused = $false
        $focusDeadline = (Get-Date).AddSeconds(10)
        while ((Get-Date) -lt $focusDeadline) {
            $fg = [BpMeasure]::GetForegroundWindow()
            if ($fg -eq $hwnd) { $focused = $true; $tWindow = (Get-Date).ToUniversalTime(); break }
            [void][BpMeasure]::SetForegroundWindow($hwnd)
            Start-Sleep -Milliseconds 20
        }

        # Let the home feed settle, then require the app to still answer window messages.
        Start-Sleep -Seconds $WaitForHomeSeconds
        $responsive = [BpMeasure]::Responds($hwnd)

        Stop-BpPresentMon -Capture $cap
        $parsed = $null
        try { $parsed = Read-BpPresentMonCsv -Path $csv } catch { Write-BpLog ("presentmon unusable: " + $_.Exception.Message) }
        $rows = @()
        if ($parsed) { $rows = @($parsed.Rows | Where-Object { $_.ProcessId -eq "$uiPid" }) }
        $firstPresentMs = $null
        if ($rows.Count -gt 0 -and $rows[0].WallClock) {
            $firstPresentMs = [math]::Round(($rows[0].WallClock.ToUniversalTime() - $t0).TotalMilliseconds, 0)
        } elseif ($rows.Count -gt 0) {
            $anchor = $cap.StartedUtc
            $firstPresentMs = [math]::Round((($anchor.AddSeconds($rows[0].TimeSeconds)) - $t0).TotalMilliseconds, 0)
        }

        $row = [ordered]@{
            iteration        = $i
            pid              = $uiPid
            launcher_pid     = $appPid
            startUtc         = $t0.ToString('o')
            window_focus_ms  = [math]::Round(($tWindow - $t0).TotalMilliseconds, 0)
            focused          = $focused
            first_present_ms = $firstPresentMs
            frames_in_25s    = $rows.Count
            responsive_after = $responsive
        }
        [void]$results.Add([pscustomobject]$row)
        Write-BpLog ('iter {0}: window_focus={1} ms first_present={2} ms frames={3} focused={4} responsive={5}' -f `
                $i, $row.window_focus_ms, $row.first_present_ms, $row.frames_in_25s, $focused, $responsive)
    } catch {
        Write-BpLog ("iteration {0} failed: {1}" -f $i, $_.Exception.Message)
        [void]$failures.Add([pscustomobject]@{ iteration = $i; error = $_.Exception.Message })
    } finally {
        if ($cap) { Stop-BpPresentMon -Capture $cap }
        if ($appPid -gt 0) { Stop-BpApp -ProcessId $appPid -Since $since }
    }
    if ($i -lt $Iterations) { Start-Sleep -Seconds $BetweenIterationsSeconds }
}

$summary = [ordered]@{
    item = 22; tag = $Tag; iterations = $Iterations
    ok = ($failures.Count -eq 0 -and $results.Count -eq $Iterations)
    failures = $failures
}
foreach ($metric in @('window_focus_ms', 'first_present_ms')) {
    $vals = @($results | ForEach-Object { $_.$metric } | Where-Object { $null -ne $_ })
    if ($vals.Count -gt 0) {
        $sorted = $vals | Sort-Object
        $summary["${metric}_p50"] = $sorted[[int][math]::Floor(($sorted.Count - 1) * 0.5)]
        $summary["${metric}_p95"] = $sorted[[int][math]::Floor(($sorted.Count - 1) * 0.95)]
        $summary["${metric}_max"] = $sorted[$sorted.Count - 1]
    }
}
if ($results.Count -gt 0) {
    $summary.frames_per_launch = ($results | Measure-Object -Property frames_in_25s -Average).Average
    $summary.focused_all = (@($results | Where-Object { -not $_.focused }).Count -eq 0)
    $summary.responsive_all = (@($results | Where-Object { -not $_.responsive_after }).Count -eq 0)
}
Write-BpCsv -Rows $results -Path (Join-Path $dataDir 'iterations.csv')
$summary | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 -Path (Join-Path $dataDir 'summary.json')
Write-BpLog ('SUMMARY ' + ($summary | ConvertTo-Json -Depth 5 -Compress))
if ($summary.ok) { exit 0 } else { exit 5 }
