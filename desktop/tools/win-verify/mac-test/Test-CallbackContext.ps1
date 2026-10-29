# Regression for the real failed session: callback/helpers must share measured tree state.
# No client, new GUI, input or process termination. The root is this existing shell.
$ErrorActionPreference='Stop'
$root='C:\Users\TONYS\bp-mac-verify'
$env:TEMP="$root\ton1020\profile\Temp";$env:TMP=$env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null
. "$PSScriptRoot\TreeEvidence.ps1"
$out="$root\ton1047\callback-test"
$null=Initialize-BpTree $PID $out
$fixture=Join-Path $out 'callback.ps1'
@'
param([datetime]$Deadline)
$null=Update-BpTree
Save-BpJson ([ordered]@{rootPid=$script:BpRootProcessId;directory=$script:BpTreeDirectory;deadline=$Deadline.ToString('o')}) (Join-Path $script:BpTreeDirectory 'callback.json')
'@ | Set-Content $fixture -Encoding UTF8
$originalFailed=$false
try { & $fixture -Deadline ((Get-Date).AddSeconds(10)) } catch { $originalFailed=$true }
if(!$originalFailed){throw 'did not reproduce original callback scope failure'}
Invoke-BpEvidenceSession $fixture ((Get-Date).AddSeconds(10))
$result=Get-Content "$out\callback.json" -Raw -Encoding UTF8 | ConvertFrom-Json
if($result.rootPid -ne $PID -or $result.directory -ne $out){throw 'callback lost measured tree identity or output directory'}
Save-BpJson ([ordered]@{originalCallOperatorFailed=$true;fixedCallbackPassed=$true;clientStarts=0;guiStarts=0;rootPid=$PID}) "$out\regression.json"
Write-Output 'CALLBACK_SCOPE_REGRESSION_PASS (original reproduced; fixed callback recorded real current PID)'
