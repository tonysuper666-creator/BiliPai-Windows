# Controlled check that Invoke-BpJob.ps1 reports the child's real exit code.
# Runs three GUI-free child scripts (exit 0, exit 5, throw) in a temp copy of this folder's
# runner, and requires job-result.json exitCode / ok and the runner's own exit code to agree.
# Does not start the client and does not touch measure\ state.
[CmdletBinding()]
param([string]$PowerShellExe = 'powershell.exe')
$ErrorActionPreference = 'Stop'
$work = Join-Path ([IO.Path]::GetTempPath()) ("bpjob-test-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
try {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Invoke-BpJob.ps1') -Destination $work
    Set-Content -LiteralPath (Join-Path $work 'Case-Exit0.ps1') -Value 'exit 0'
    Set-Content -LiteralPath (Join-Path $work 'Case-Exit5.ps1') -Value 'exit 5'
    Set-Content -LiteralPath (Join-Path $work 'Case-Throw.ps1') -Value "`$ErrorActionPreference = 'Stop'; throw 'controlled failure'"
    $cases = @(
        @{ script = 'Case-Exit0.ps1'; code = 0 },
        @{ script = 'Case-Exit5.ps1'; code = 5 },
        @{ script = 'Case-Throw.ps1'; code = 1 }
    )
    $failed = 0
    foreach ($c in $cases) {
        $jobPath = Join-Path $work 'job.json'
        $resPath = Join-Path $work 'job-result.json'
        Remove-Item -LiteralPath $resPath -ErrorAction SilentlyContinue
        (@{ script = $c.script; args = @() } | ConvertTo-Json) | Set-Content -LiteralPath $jobPath -Encoding utf8
        & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $work 'Invoke-BpJob.ps1')
        $outer = $LASTEXITCODE
        $r = Get-Content -LiteralPath $resPath -Raw -Encoding utf8 | ConvertFrom-Json
        $good = ($null -ne $r.exitCode) -and ($r.exitCode -eq $c.code) -and ($outer -eq $c.code) -and ($r.ok -eq ($c.code -eq 0))
        if (-not $good) { $failed++ }
        '{0}: expected={1} outerExit={2} json.exitCode={3} json.ok={4} -> {5}' -f $c.script, $c.code, $outer,
            $(if ($null -eq $r.exitCode) { '<null>' } else { $r.exitCode }), $r.ok, $(if ($good) { 'PASS' } else { 'FAIL' })
    }
    if ($failed) { Write-Output "FAILED: $failed case(s)"; exit 1 }
    Write-Output 'PASS: all cases'
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
