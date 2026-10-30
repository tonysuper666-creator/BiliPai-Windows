param([Parameter(Mandatory)][string]$EvidenceArchive)
$ErrorActionPreference='Stop'
$card='C:\BuildVM\cards\ton1111'
. "$card\tools\Json.ps1"
if(!(Test-Path $EvidenceArchive -PathType Leaf)){throw 'Verified evidence archive is required before reclaiming anything'}
if((Get-Item $EvidenceArchive).Length-gt 5MB){throw 'Evidence archive exceeds 5 MB'}
# Refuse to remove this card's package while a process still references it.
$owned=@(Get-CimInstance Win32_Process|Where-Object {$_.CommandLine -and ($_.CommandLine-like '*C:\BuildVM\accept\vm-joint-20261030*' -or $_.CommandLine-like '*WindowsVideoCrashRegressionFixture*C:\BuildVM\cards\ton1111*')})
if($owned.Count){throw 'Card package is still referenced by live processes'}
$wt='C:\BuildVM\accept\vm-joint-20261030'
if(Test-Path $wt){
 & git -C C:\BuildVM\src\BiliPai-Windows worktree remove --force $wt
 if($LASTEXITCODE-ne 0){throw 'Own acceptance worktree cleanup failed'}
}
$retained=Join-Path $card 'TON1111-evidence.zip'
if([IO.Path]::GetFullPath($EvidenceArchive)-ne $retained){Copy-Item $EvidenceArchive $retained -Force}
$record=[ordered]@{at=(Get-Date).ToString('o');worktreeRemoved=!(Test-Path $wt);archiveSha256=(Get-FileHash $retained).Hash;archiveBytes=(Get-Item $retained).Length}
# These are this card's fresh guests and copied harness tools. Retain only
# the verified evidence and immutable invocation markers; source lives in the PR.
foreach($entry in Get-ChildItem $card -Force){
 if($entry.Name -in @('TON1111-evidence.zip','selftest-used','evidence-used')){continue}
 Remove-Item $entry.FullName -Recurse -Force
}
$record.retainedBytesBeforeReceipt=(Get-ChildItem $card -Recurse -File|Measure-Object Length -Sum).Sum
if($record.retainedBytesBeforeReceipt-gt (5MB-4096)){throw 'Evidence plus receipt would exceed the 5 MB card limit'}
Save-BpJson $record "$card\cleanup.json"
Copy-Item "$card\cleanup.json" '\\Mac\vmio\ton1111\cleanup.json' -Force
$record|ConvertTo-Json
