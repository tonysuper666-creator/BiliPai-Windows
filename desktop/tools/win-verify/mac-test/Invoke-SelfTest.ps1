# Exactly one scheduled-task invocation, using a non-client GUI child.
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Json.ps1"
$root='C:\Users\TONYS\bp-mac-verify'
$here=$PSScriptRoot
$tools="$root\tools"
$out="$root\ton1047\selftest"
if((Test-Path "$out\summary.json") -or (Test-Path "$out\invoked.json")){throw 'TON-1047 self-test already completed; never repeat'}
if(Test-Path "$tools\PAUSE"){throw 'PAUSE present'}
if((Get-ScheduledTask BiliPai-MacTest).State -ne 'Ready'){throw 'MacTest occupied; queue without launching'}
if(Test-Path "$tools\job.lock"){throw 'shared tools lock occupied'}
if(Test-Path "$root\measure\job.lock"){throw 'measurement occupied'}
New-Item -ItemType Directory -Force $out,"$root\apps\TON1047-selftest" | Out-Null
# Exclusive cooperative lock; never remove another card's lock.
$lease=[IO.File]::Open("$tools\job.lock",'CreateNew','Write','Read')
$bytes=[Text.Encoding]::UTF8.GetBytes("TON-1047 selftest pid=$PID")
$lease.Write($bytes,0,$bytes.Length)
$lease.Dispose()
try {
    if((Get-ScheduledTask BiliPai-MacTest).State -ne 'Ready'){throw 'task became busy'}
    Copy-Item "$tools\request.json" "$out\request-before.raw"
    $previousRequest=Get-Content "$out\request-before.raw" -Raw -Encoding UTF8 | ConvertFrom-Json
    Save-BpJson $previousRequest "$out\request-before.json"
    Copy-Item "$tools\launch.ps1" "$out\launch-before.ps1"
    Copy-Item "$here\Launch-MacTest.ps1" "$tools\launch.ps1" -Force
    Copy-Item "$here\TreeEvidence.ps1","$here\Json.ps1" $tools -Force
    Copy-Item "$here\selftest-parent.cmd","$here\SelfTest-Child.ps1" "$root\apps\TON1047-selftest" -Force
    $request=[ordered]@{exe="$root\apps\TON1047-selftest\selftest-parent.cmd";max_seconds=25;
        capture_tree=$true;evidence_dir=$out}
    Save-BpJson $request "$out\request-submitted.json"
    Copy-Item "$out\request-submitted.json" "$tools\request.json" -Force
    Remove-Item "$tools\result.json" -ErrorAction SilentlyContinue
    $marker=[IO.File]::Open("$out\invoked.json",'CreateNew','Write','Read');$marker.Dispose()
    Start-ScheduledTask BiliPai-MacTest
    $end=(Get-Date).AddSeconds(60)
    $result=$null
    do {
        Start-Sleep -Milliseconds 200
        if(Test-Path "$tools\result.json"){
            $result=Get-Content "$tools\result.json" -Raw -Encoding UTF8 | ConvertFrom-Json
            if($result.status -in @('done','error','refused')){break}
        }
    }while((Get-Date) -lt $end)
    if(!$result -or $result.status -ne 'done'){throw "one self-test failed: $($result.reason)"}
    Copy-Item "$tools\result.json" "$out\launcher-result.json" -Force
    $tree=Get-Content "$out\pid-tree.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    $shot=Get-Content "$out\descendant-window.window.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    if($shot.pid -eq $tree.rootPid){throw 'screenshot was root, not descendant'}
    if(@($tree.processes | Where-Object alive).Count){throw 'self-test left live descendants'}
    if(@($tree.processes | Where-Object identity -eq $shot.identity).Count -ne 1){throw 'screenshot identity absent from tree'}
    $summary=[ordered]@{passed=$true;clientStarts=0;selfTestStarts=1;rootPid=$tree.rootPid;windowPid=$shot.pid;
        processCount=@($tree.processes).Count;systemDialogs=@($tree.observations | Where-Object systemDialogs -gt 0).Count;
        cleanupRemaining=0;at=(Get-Date).ToString('o')}
    Save-BpJson $summary "$out\summary.json"
    $summary | ConvertTo-Json
} finally {
    if((Get-ScheduledTask BiliPai-MacTest).State -eq 'Ready' -and (Test-Path "$out\request-before.raw")){
        Copy-Item "$out\request-before.raw" "$tools\request.json" -Force
    }
    Remove-Item "$tools\job.lock" -ErrorAction SilentlyContinue
}
