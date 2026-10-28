# Proves that the measurement scripts honour tools\PAUSE.
#
# Creates tools\PAUSE, runs one short measurement, records its exit code and log tail,
# then removes the file again. Only creates the file when it was absent; never removes a
# PAUSE file this run did not create.
#
# Exit codes: 0 the gate held, 3 the file was already there (nothing was done), 5 the gate did not hold.
[CmdletBinding()]
param([string]$Tag = 'run1')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'BpVerify.Common.ps1')

$dataDir = Initialize-BpRun -Name "pausegate-$Tag"
$pausePath = Join-Path $script:BpRoot 'tools\PAUSE'
$result = [ordered]@{ check = 'tools\PAUSE gate'; tag = $Tag; startedUtc = (Get-Date).ToUniversalTime().ToString('o') }

if (Test-Path -LiteralPath $pausePath) {
    Write-BpLog 'PAUSE already present: not touching it, gate not exercised.'
    $result.ok = $false
    $result.reason = 'PAUSE pre-existing'
    $result | ConvertTo-Json | Set-Content -Encoding utf8 -Path (Join-Path $dataDir 'summary.json')
    exit 3
}

$created = $false
try {
    New-Item -ItemType File -Path $pausePath -Force | Out-Null
    $created = $true
    Write-BpLog 'created tools\PAUSE'

    $childLog = Join-Path $script:BpLogDir ("pausegate-child-" + $script:BpRunStamp + ".log")
    $child = Start-Process -FilePath 'powershell.exe' -PassThru -WindowStyle Hidden -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', (Join-Path $PSScriptRoot 'Measure-FrameTime.ps1'),
        '-Seconds', '3', '-SettleSeconds', '1', '-Tag', "pausecheck-$Tag"
    ) -RedirectStandardOutput $childLog -RedirectStandardError ([IO.Path]::ChangeExtension($childLog, '.err.log'))
    [void]$child.WaitForExit()

    Write-BpLog ("child measurement exit code = " + $child.ExitCode)
    $tail = @(Get-Content -LiteralPath $childLog -ErrorAction SilentlyContinue | Select-Object -Last 5 | ForEach-Object { [string]$_ })
    foreach ($line in $tail) { Write-BpLog ("child: " + $line) }

    $result.child_exit_code = $child.ExitCode
    $result.child_log = $childLog
    $result.child_tail = $tail
    $result.paused_line = @($tail | Where-Object { $_ -match 'PAUSE file present' }).Count -gt 0
    $result.ok = ($child.ExitCode -eq 3 -and $result.paused_line)
} catch {
    $result.ok = $false
    $result.reason = $_.Exception.Message
    Write-BpLog ('FAILED: ' + $_.Exception.Message)
} finally {
    if ($created -and (Test-Path -LiteralPath $pausePath)) {
        Remove-Item -LiteralPath $pausePath -Force
        Write-BpLog 'removed the tools\PAUSE this run created'
    }
}

$result.endedUtc = (Get-Date).ToUniversalTime().ToString('o')
$result | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 -Path (Join-Path $dataDir 'summary.json')
Write-BpLog ('SUMMARY ' + ($result | ConvertTo-Json -Depth 4 -Compress))
if ($result.ok) { exit 0 } else { exit 5 }
