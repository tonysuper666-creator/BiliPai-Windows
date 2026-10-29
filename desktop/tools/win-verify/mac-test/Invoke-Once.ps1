# Foreground entry point. Requires the recorded member decision and a ready offline package.
$ErrorActionPreference='Stop'
$root='C:\Users\TONYS\bp-mac-verify'
$out="$root\ton1047\client"
$tools="$root\tools"
$approval=Get-Content "$root\ton1047\approval.json" -Raw -Encoding UTF8 | ConvertFrom-Json
if($approval.choice -ne '破例再启动一次，修好截图工具后一次跑完' -or !$approval.comment_id){throw 'explicit approval absent'}
$package=Get-Content "$root\ton1047\package.json" -Raw -Encoding UTF8 | ConvertFrom-Json
if(!$package.passed){throw 'offline package incomplete'}
if((Get-ScheduledTask BiliPai-MacTest).State -ne 'Ready'){throw 'MacTest occupied; no launch'}
if(Test-Path "$tools\PAUSE"){throw 'PAUSE present'}
if(Test-Path "$root\measure\job.lock"){throw 'measurement occupied'}
if(Test-Path "$tools\job.lock"){throw 'shared tools occupied'}
New-Item -ItemType Directory -Force $out | Out-Null
$lease=[IO.File]::Open("$tools\job.lock",'CreateNew','Write','Read')
$bytes=[Text.Encoding]::UTF8.GetBytes("TON-1047 approved single session pid=$PID")
$lease.Write($bytes,0,$bytes.Length);$lease.Dispose()
$started=$false
try {
    if((Get-ScheduledTask BiliPai-MacTest).State -ne 'Ready'){throw 'task became occupied'}
    $actual=(Get-FileHash "$root\apps\TON1020\BiliPai Windows.exe" -Algorithm SHA256).Hash
    if($actual -ne $package.exeSha256){throw 'executable hash differs from offline build'}
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$root\apps\TON1020\Start-Isolated.ps1" -CheckOnly
    if($LASTEXITCODE -ne 0){throw 'profile isolation preflight failed'}
    Copy-Item "$tools\request.json" "$out\request-before.json"
    Copy-Item "$PSScriptRoot\Launch-MacTest.ps1" "$tools\launch.ps1" -Force
    Copy-Item "$PSScriptRoot\TreeEvidence.ps1" "$tools\TreeEvidence.ps1" -Force
    $request=[ordered]@{exe="$root\apps\TON1020\run-profile.cmd";args=@('--video=BV1GJ411x7h7');max_seconds=320;
        capture_tree=$true;evidence_dir=$out;session_script="$root\apps\TON1047\Verify-TON1047.ps1"}
    $request | ConvertTo-Json | Set-Content "$tools\request.json" -Encoding UTF8
    # Durable, exclusive single-use guard. Never remove it or retry this scheduled invocation.
    $guard=[IO.File]::Open("$root\ton1047\client-launch-used.json",'CreateNew','Write','Read')
    $record=[ordered]@{approval=$approval;requested=(Get-Date).ToString('o');head=$package.head;exeSha256=$actual}
    $bytes=[Text.Encoding]::UTF8.GetBytes(($record | ConvertTo-Json -Depth 5))
    $guard.Write($bytes,0,$bytes.Length);$guard.Dispose()
    Remove-Item "$tools\result.json" -ErrorAction SilentlyContinue
    Start-ScheduledTask BiliPai-MacTest
    $started=$true
    $end=(Get-Date).AddSeconds(380)
    do {
        Start-Sleep -Milliseconds 250
        if(Test-Path "$tools\result.json"){
            $result=Get-Content "$tools\result.json" -Raw -Encoding UTF8 | ConvertFrom-Json
            if($result.status -in @('done','error','refused')){break}
        }
    }while((Get-Date) -lt $end)
    if(!$result -or $result.status -eq 'running'){throw 'session exceeded foreground deadline; inspect exact recorded PID tree, never relaunch'}
    Copy-Item "$tools\result.json" "$out\launcher-result.json" -Force
    $result | ConvertTo-Json -Depth 8
    if($result.status -ne 'done'){throw "single session ended: $($result.reason)"}
} finally {
    if((Get-ScheduledTask BiliPai-MacTest).State -eq 'Ready') {
        if(Test-Path "$out\request-before.json"){Copy-Item "$out\request-before.json" "$tools\request.json" -Force}
        Remove-Item "$tools\job.lock" -ErrorAction SilentlyContinue
    }
    # An active task keeps its ownership marker; do not invite another card to overlap it.
}
