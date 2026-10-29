# Regression for the real failed session: callback/helpers must share measured tree state.
# No client, new GUI, input or process termination. The root is this existing shell.
$ErrorActionPreference='Stop'
$root='C:\Users\TONYS\bp-mac-verify'
$env:TEMP="$root\ton1020\profile\Temp";$env:TMP=$env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null
. "$PSScriptRoot\TreeEvidence.ps1"
$out="$root\ton1047\callback-review-fix"
$null=Initialize-BpTree $PID $out
$callback=Join-Path $PSScriptRoot 'Verify-TON1047.ps1'
$expected=(Get-Content "$root\ton1047\client\launcher-result.json" -Raw -Encoding UTF8 | ConvertFrom-Json).reason
$originalError=$null
try { & $callback -Deadline ((Get-Date).AddSeconds(10)) -OfflineContextCheck } catch { $originalError=$_ }
if (!$originalError -or $originalError.Exception.Message -cne $expected -or
    $originalError.Exception.ParameterName -ne 'Path' -or
    $originalError.FullyQualifiedErrorId -ne 'ParameterArgumentValidationErrorNullNotAllowed,Microsoft.PowerShell.Commands.JoinPathCommand') {
    throw "expected the recorded null Path binding error; observed: $originalError"
}
Invoke-BpEvidenceSession $callback ((Get-Date).AddSeconds(10)) -OfflineContextCheck
$result=Get-Content "$out\context-check.json" -Raw -Encoding UTF8 | ConvertFrom-Json
if($result.rootPid -ne $PID -or $result.directory -ne $out){throw 'callback lost measured tree identity or output directory'}
Save-BpJson ([ordered]@{originalMessage=$originalError.Exception.Message;
    parameter=$originalError.Exception.ParameterName;errorId=$originalError.FullyQualifiedErrorId;
    matchesRecordedFailure=$true;callback=$callback;fixedCallbackPassed=$true;
    clientStarts=0;guiStarts=0;rootPid=$PID}) "$out\regression.json"
foreach($name in @('pid-tree.json','context-check.json','regression.json')) {
    $bytes=[IO.File]::ReadAllBytes("$out\$name")
    if($bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191){throw 'JSON writer emitted BOM'}
}
Write-Output 'CALLBACK_SCOPE_REGRESSION_PASS (exact recorded Path error; real Verify script; UTF-8 without BOM)'
