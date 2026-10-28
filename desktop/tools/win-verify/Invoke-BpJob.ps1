# Runs one measurement inside the interactive desktop session.
#
# Window enumeration and posted input only work in the session the desktop lives in, so the
# measurement cannot be started from an ssh shell (session 0). The ssh side writes
# measure\job.json and starts the BiliPai-U1Measure scheduled task, which runs this file in
# the user's console session; this file runs the requested script in a child process and
# writes measure\job-result.json.
#
# job.json:  { "script": "Measure-FrameTime.ps1", "args": ["-Seconds","30","-Tag","run1"] }
[CmdletBinding()]
param([string]$JobPath, [string]$ResultPath)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
if (-not $JobPath) { $JobPath = Join-Path $here 'job.json' }
if (-not $ResultPath) { $ResultPath = Join-Path $here 'job-result.json' }

function Write-Result($obj) { $obj | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 -Path $ResultPath }

$job = Get-Content -LiteralPath $JobPath -Raw -Encoding utf8 | ConvertFrom-Json
$scriptName = [string]$job.script
$scriptArgs = @()
if ($job.args) { $scriptArgs = @($job.args | ForEach-Object { [string]$_ }) }
$scriptPath = Join-Path $here $scriptName
if (-not (Test-Path -LiteralPath $scriptPath)) { Write-Result @{ ok = $false; error = "unknown script $scriptName" }; exit 1 }

# Only one measurement may run at a time: two chains both open a "PresentMon" ETW session,
# which silently breaks the frame captures. The lock is a pid file, so a stale lock from a
# killed run does not block the next one.
$lockPath = Join-Path $here 'job.lock'
if (Test-Path -LiteralPath $lockPath) {
    $held = 0
    [void][int]::TryParse((Get-Content -LiteralPath $lockPath -Raw).Trim(), [ref]$held)
    if ($held -gt 0 -and (Get-Process -Id $held -ErrorAction SilentlyContinue)) {
        Write-Result ([ordered]@{ ok = $false; error = 'another measurement is still running'; pid = $held })
        exit 1
    }
    Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
}
Set-Content -LiteralPath $lockPath -Value $PID -Encoding ascii

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$childLog = Join-Path $here ("logs\job-$stamp.log")
New-Item -ItemType Directory -Force -Path (Join-Path $here 'logs') | Out-Null
$result = [ordered]@{
    script = $scriptName; args = $scriptArgs; startedUtc = (Get-Date).ToUniversalTime().ToString('o')
    session = (Get-Process -Id $PID).SessionId; childLog = $childLog
}
Write-Result $result
$exit = 1
try {
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scriptPath) + $scriptArgs
    $child = Start-Process -FilePath 'powershell.exe' -PassThru -WindowStyle Hidden -ArgumentList $argList `
        -RedirectStandardOutput $childLog -RedirectStandardError ([IO.Path]::ChangeExtension($childLog, '.err.log'))
    [void]$child.WaitForExit()
    $exit = $child.ExitCode
} catch {
    $result.error = $_.Exception.Message
    $exit = 1
}
$result.exitCode = $exit
$result.ok = ($exit -eq 0)
$result.endedUtc = (Get-Date).ToUniversalTime().ToString('o')
Write-Result $result
Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
exit $exit
