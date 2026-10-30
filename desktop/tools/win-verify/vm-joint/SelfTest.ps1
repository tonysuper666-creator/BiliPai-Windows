$ErrorActionPreference='Stop'
$card='C:\BuildVM\cards\ton1111';$out="$card\selftest";$tools="$card\tools"
. "$tools\TreeEvidence.ps1"
try{
 $p=Start-Process cmd.exe -ArgumentList '/d','/c',"powershell.exe -NoProfile -ExecutionPolicy Bypass -File $tools\SelfTest-Child.ps1" -PassThru
 Initialize-BpTree $p.Id $out|Out-Null
 $w=Wait-BpTreeWindow 20
 if($w.pid-eq $p.Id){throw 'Screenshot belongs to root rather than descendant'}
 Start-Sleep -Milliseconds 700
 $shot=Save-BpTreeShot $w 'descendant-window'
 Stop-BpTree
 Save-BpJson ([ordered]@{passed=$true;clientStarts=0;selftestStarts=1;rootPid=$p.Id;windowPid=$w.pid;cleanupRemaining=0;screenshot=$shot}) "$out\summary.json"
}catch{
 Save-BpJson ([ordered]@{passed=$false;error=$_.Exception.ToString()}) "$out\summary.json"
 if($script:BpTree){Stop-BpTree}
 throw
}finally{Copy-Item "$out\*" '\\Mac\vmio\ton1111\selftest' -Recurse -Force}
