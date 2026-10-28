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

# A stopped scheduled task leaves its child measurement running, which then fights the next
# one for the single PresentMon ETW session. Refuse to start while another measurement runs.
$busy = @(Get-WmiObject Win32_Process | Where-Object {
        $_.CommandLine -ne $null -and $_.CommandLine.Contains('bp-mac-verify\measure\') -and
        $_.ProcessId -ne $PID -and $_.CreationDate -lt (Get-Date).AddMinutes(-2) })
if ($busy.Count -gt 0) {
    Write-Result ([ordered]@{ ok = $false; error = 'another measurement is still running'; pids = @($busy | ForEach-Object { $_.ProcessId }) })
    exit 1
}

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
exit $exit
