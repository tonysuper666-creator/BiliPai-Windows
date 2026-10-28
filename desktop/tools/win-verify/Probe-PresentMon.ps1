# One-off probe: which PresentMon argument combination works here, and what the CSV header is.
# Writes measure\data\probe-headers.txt (stdout/exit code per variant).
$ErrorActionPreference = 'Continue'
$here = $PSScriptRoot
$exe = Join-Path $here 'bin\PresentMon.exe'
$out = Join-Path $here 'data\probe-headers.txt'
$lines = New-Object System.Collections.ArrayList
[void]$lines.Add("probe at $(Get-Date -Format o) session=$((Get-Process -Id $PID).SessionId)")
$variants = @(
    @{ name = 'v1_date'; args = @('--v1_metrics', '--date_time', '--timed', '3', '--stop_existing_session') },
    @{ name = 'v2_date'; args = @('--v2_metrics', '--date_time', '--timed', '3', '--stop_existing_session') },
    @{ name = 'v1_plain'; args = @('--v1_metrics', '--timed', '3', '--stop_existing_session') }
)
foreach ($v in $variants) {
    $csv = Join-Path $here ("data\probe-$($v.name).csv")
    $log = Join-Path $here ("data\probe-$($v.name).log")
    Remove-Item -LiteralPath $csv, $log -Force -ErrorAction SilentlyContinue
    $argv = @($v.args) + @('--output_file', $csv, '--no_console_stats')
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $p = Start-Process -FilePath $exe -ArgumentList $argv -PassThru -WindowStyle Hidden -RedirectStandardOutput "$log.out" -RedirectStandardError "$log.err"
    [void]$p.WaitForExit(15000)
    if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force; Start-Sleep -Milliseconds 500 }
    $p.Refresh()
    [void]$lines.Add("== $($v.name) exit=$($p.ExitCode) seconds=$([math]::Round($sw.Elapsed.TotalSeconds,1)) csv=$(Test-Path -LiteralPath $csv) ==")
    if (Test-Path -LiteralPath $csv) {
        Get-Content -LiteralPath $csv -TotalCount 2 | ForEach-Object { [void]$lines.Add($_) }
    }
    foreach ($f in @("$log.err", "$log.out")) {
        if (Test-Path -LiteralPath $f) {
            $text = (Get-Content -LiteralPath $f -TotalCount 6) -join ' | '
            if ($text) { [void]$lines.Add("  $([IO.Path]::GetFileName($f)): $text") }
        }
    }
}
$lines | Set-Content -LiteralPath $out -Encoding utf8
