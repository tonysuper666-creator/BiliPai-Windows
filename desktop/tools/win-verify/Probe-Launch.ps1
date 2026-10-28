# One-off probe: what the client actually does after the launcher starts it.
# Records the process tree and window count once a second for $Seconds.
[CmdletBinding()]
param([int]$Seconds = 90)
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'BpVerify.Common.ps1')

$out = Join-Path $script:BpDataDir 'probe-launch.txt'
$lines = New-Object System.Collections.ArrayList
$exe = Get-BpAppExe
[void]$lines.Add("probe at $(Get-Date -Format o) exe=$exe")

$run = Start-BpAppViaTask -Exe $exe -MaxSeconds ($Seconds + 20)
$root = $run.Pid
[void]$lines.Add("launcher pid=$root startedAt=$(Get-Date -Format o)")
$sw = [Diagnostics.Stopwatch]::StartNew()
while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
    $snap = New-Object System.Collections.ArrayList
    foreach ($proc in (Get-Process -Name $script:BpAppName -ErrorAction SilentlyContinue)) {
        $wins = @([BpMeasure]::WindowsOfPid([uint32]$proc.Id))
        $titles = @($wins | ForEach-Object { $t = [BpMeasure]::ClassOf($_); "$t" })
        [void]$snap.Add(("pid={0} ws={1}MB threads={2} windows={3} [{4}]" -f $proc.Id, [math]::Round($proc.WorkingSet64 / 1MB), $proc.Threads.Count, $wins.Count, ($titles -join ',')))
    }
    if ($snap.Count -eq 0) { [void]$snap.Add('no BiliPai process') }
    [void]$lines.Add(("t={0,4:n0}s {1}" -f $sw.Elapsed.TotalSeconds, ($snap -join ' | ')))
    Start-Sleep -Seconds 1
}
$lines | Set-Content -LiteralPath $out -Encoding utf8
foreach ($p in (Get-Process -Name $script:BpAppName -ErrorAction SilentlyContinue)) { Stop-Process -Id $p.Id -Force }
