# Diagnostic: play a video and find out what the screen shows when the picture stops.
#
# Long soaks kept "running" while the picture moved in only a fraction of the checks. This
# probe records, for one clip: how long it played, the frame that was on screen when motion
# stopped, and whether anything resumed on its own afterwards. The screenshot is the evidence
# that separates "clip finished", "buffering", "error page" and "pause overlay".
[CmdletBinding()]
param(
    [string]$Tag = 'stop',
    [int]$SettleSeconds = 15,
    [int]$MaxSeconds = 420,
    [double]$ClickXRatio = 0.30,
    [double]$ClickYRatio = 0.45
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'BpVerify.Common.ps1')
Add-Type -AssemblyName System.Drawing

$dataDir = Initialize-BpRun -Name "stop-$Tag"
if (Test-BpPause) { Write-BpLog 'PAUSE file present: nothing was started.'; exit 3 }
if (-not (Test-BpInteractiveSession)) { exit 7 }

function Save-Shot($hwnd, $name) {
    $r = New-Object BpMeasure+RECT
    [void][BpMeasure]::GetWindowRect($hwnd, [ref]$r)
    $w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($r.Left, $r.Top, 0, 0, (New-Object System.Drawing.Size $w, $h), [System.Drawing.CopyPixelOperation]::SourceCopy)
    $bmp.Save((Join-Path $dataDir $name), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    return (Join-Path $dataDir $name)
}

function Test-Moving($hwnd) {
    $frames = @()
    for ($i = 0; $i -lt 4; $i++) {
        $frames += , (Get-BpScreenThumbCenter -Hwnd $hwnd)
        if ($i -lt 3) { Start-Sleep -Milliseconds 900 }
    }
    $max = 0; $maxCells = 0
    for ($i = 1; $i -lt $frames.Count; $i++) {
        $d = Compare-BpThumb -A $frames[$i - 1] -B $frames[$i]
        $c = Compare-BpThumbCells -A $frames[$i - 1] -B $frames[$i]
        if ($null -ne $d -and $d -gt $max) { $max = $d }
        if ($null -ne $c -and $c -gt $maxCells) { $maxCells = $c }
    }
    return [pscustomobject]@{ mean = $max; cells = $maxCells }
}

$exe = Get-BpAppExe
$appPid = 0
$since = [datetime]::MinValue
$summary = [ordered]@{ probe = 'playback-stop'; tag = $Tag; startedUtc = (Get-Date).ToUniversalTime().ToString('o') }
$series = New-Object System.Collections.ArrayList

try {
    $run = Start-BpAppViaTask -Exe $exe -MaxSeconds ($MaxSeconds + 180)
    $appPid = $run.Pid
    $since = $run.Since
    $win = Wait-BpWindow -ProcessId $appPid -Since $since -TimeoutMs 60000
    $hwnd = $win.Hwnd
    $uiPid = $win.Pid
    if (-not (Set-BpForeground -Hwnd $hwnd)) { throw 'window could not be brought to the foreground' }
    Start-Sleep -Seconds $SettleSeconds
    Save-Shot $hwnd 'a-home.png'

    $rect = New-Object BpMeasure+RECT
    [void][BpMeasure]::GetClientRect($hwnd, [ref]$rect)
    $clickUtc = Send-BpClickReal -Hwnd $hwnd -X ([int]($rect.Right * $ClickXRatio)) -Y ([int]($rect.Bottom * $ClickYRatio))
    Write-BpLog ('clicked a card at ' + $clickUtc.ToString('o'))

    $start = Get-Date
    $deadline = $start.AddSeconds($MaxSeconds)
    $playedFrom = $null
    $stoppedAt = $null
    $still = 0
    $stallOpen = $false
    $stallStart = 0
    $stalls = New-Object System.Collections.ArrayList
    while ((Get-Date) -lt $deadline) {
        $d = Test-Moving -Hwnd $hwnd
        $elapsed = [math]::Round(((Get-Date) - $start).TotalSeconds, 1)
        [void]$series.Add([pscustomobject]@{ secondsAfterClick = $elapsed; meanStep = $d.mean; changedCells = $d.cells })
        if ($d.cells -ge 5) {
            $still = 0
            if ($stallOpen) {
                $len = [math]::Round($elapsed - $stallStart, 1)
                $sampleAtEnd = Get-BpProcessSample -ProcessId $uiPid
                $cpuUsed = $null
                if ($null -ne $stallCpuStart -and $sampleAtEnd) {
                    $cpuUsed = [math]::Round($sampleAtEnd.CpuSeconds - $stallCpuStart, 2)
                }
                [void]$stalls.Add([pscustomobject]@{
                        startSeconds = $stallStart; lengthSeconds = $len
                        cpuSecondsDuringStall = $cpuUsed
                        cpuShareOneCore = $(if ($null -ne $cpuUsed -and $len -gt 0) { [math]::Round(100.0 * $cpuUsed / $len, 1) } else { $null })
                    })
                Write-BpLog ("stall #{0} ended after {1}s" -f $stalls.Count, $len)
                $stallOpen = $false
            }
            if ($null -eq $playedFrom) { $playedFrom = $elapsed; Write-BpLog ("picture moving at +{0}s" -f $elapsed) }
        } else {
            $still++
            Write-BpLog ("picture still at +{0}s" -f $elapsed)
            if ($still -ge 2 -and $null -ne $playedFrom) {
                if (-not $stallOpen) {
                    $stallOpen = $true
                    $stallStart = $elapsed
                    # What is the client doing while the picture is frozen? Zero CPU over a long
                    # stall means it is waiting (buffering / paused); steady CPU means it is
                    # decoding and the picture is stuck elsewhere.
                    $sampleAtStart = Get-BpProcessSample -ProcessId $uiPid
                    $stallCpuStart = if ($sampleAtStart) { $sampleAtStart.CpuSeconds } else { $null }
                    if ($stalls.Count -lt 3) { [void](Save-Shot $hwnd ("b-stall-{0:d2}.png" -f ($stalls.Count + 1))) }
                    Write-BpLog ("stall #{0} begins at +{1}s" -f ($stalls.Count + 1), $stallStart)
                }
            }
            if ($still -ge 3 -and $null -eq $playedFrom) {
                [void](Save-Shot $hwnd 'b-never-played.png')
                $summary.never_played = $true
                Write-BpLog 'picture never started moving; screenshot saved'
                break
            }
        }
    }

    if ($stallOpen) {
        $len = [math]::Round(((Get-Date) - $start).TotalSeconds - $stallStart, 1)
        $sampleAtEnd = Get-BpProcessSample -ProcessId $uiPid
        $cpuUsed = $null
        if ($null -ne $stallCpuStart -and $sampleAtEnd) { $cpuUsed = [math]::Round($sampleAtEnd.CpuSeconds - $stallCpuStart, 2) }
        [void]$stalls.Add([pscustomobject]@{
                startSeconds = $stallStart; lengthSeconds = $len; cpuSecondsDuringStall = $cpuUsed
                cpuShareOneCore = $(if ($null -ne $cpuUsed -and $len -gt 0) { [math]::Round(100.0 * $cpuUsed / $len, 1) } else { $null })
            })
    }
    [void](Save-Shot $hwnd 'c-after.png')
    $summary.played_from_seconds = $playedFrom
    $summary.stall_count = $stalls.Count
    if ($stalls.Count -gt 0) {
        $lens = @($stalls | ForEach-Object { $_.lengthSeconds } | Sort-Object)
        $summary.stall_median_s = $lens[[int][math]::Floor(($lens.Count - 1) / 2)]
        $summary.stall_max_s = $lens[$lens.Count - 1]
        $summary.stalled_seconds_total = [math]::Round(($lens | Measure-Object -Sum).Sum, 1)
    }
    @($stalls) | Export-Csv -LiteralPath (Join-Path $dataDir 'stalls.csv') -NoTypeInformation -Encoding utf8
    $summary.ok = $true
    Write-BpLog ('SUMMARY ' + ($summary | ConvertTo-Json -Depth 4 -Compress))
} catch {
    $summary.ok = $false
    $summary.reason = $_.Exception.Message
    Write-BpLog ('FAILED: ' + $_.Exception.Message)
} finally {
    # Write before stopping the client: a run that dies while stopping would otherwise lose
    # the whole series (observed once).
    $summary.endedUtc = (Get-Date).ToUniversalTime().ToString('o')
    try {
        @($series) | Export-Csv -LiteralPath (Join-Path $dataDir 'motion.csv') -NoTypeInformation -Encoding utf8
        $summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 -Path (Join-Path $dataDir 'summary.json')
    } catch { Write-BpLog ('could not write output: ' + $_.Exception.Message) }
    if ($appPid -gt 0) { Stop-BpApp -ProcessId $appPid -Since $since }
}
if ($summary.ok) { exit 0 } else { exit 5 }
