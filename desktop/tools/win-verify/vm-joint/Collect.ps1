$ErrorActionPreference='Stop'
$card='C:\BuildVM\cards\ton1111';$out="$card\evidence"
. "$card\tools\Json.ps1"
$src='C:\BuildVM\accept\vm-joint-20261030'
$app="$src\desktop\build\compose\binaries\main\app\BiliPai Windows"
$profile="$card\profile";$local="$profile\Temp\BiliPai-v025-root-routes-ton1111"
$signatures=@('DesktopOriginalVideoShellOwner.factoryFor','DesktopWindowsVideoFeedbackSource','Original resolved video metadata is required before transport publication')
$logs=@(Get-ChildItem "$local\BiliPaiWindows" -Recurse -File -ErrorAction SilentlyContinue|Where-Object {$_.Extension-in '.txt','.log'})
$logs+=@(Get-ChildItem $out -File|Where-Object {$_.Name-like 'client.*.log'})
$logs+=@(Get-ChildItem $card,$app -Filter 'hs_err_pid*.log' -File -ErrorAction SilentlyContinue)
$counts=@();$manifest=@();$snippet=@()
foreach($sig in $signatures){
 $total=0;$perFile=@()
 foreach($f in $logs){
  $matches=@(Select-String -LiteralPath $f.FullName -SimpleMatch $sig -AllMatches -ErrorAction SilentlyContinue)
  $n=0;foreach($m in $matches){$n+=$m.Matches.Count;$snippet+="$($f.Name):$($m.LineNumber) $($m.Line)"}
  $total+=$n;if($n){$perFile+=@([ordered]@{name=$f.Name;count=$n})}
 }
 $counts+=@([ordered]@{signature=$sig;count=$total;files=$perFile})
}
foreach($f in $logs){$manifest+=@([ordered]@{name=$f.Name;bytes=$f.Length;sha256=(Get-FileHash $f.FullName).Hash})}
Save-BpJson ([ordered]@{counts=$counts;scannedFiles=$manifest;method='literal occurrences across collected guest logs/stdout/stderr; repeated stack text in separate files is counted separately'}) "$out\signature-counts.json"
$crashes=@($logs|Where-Object {$_.Name-match 'last_crash|window-exception|hs_err_pid|crash'})
Save-BpJson ([ordered]@{freshProfile=$true;newCrashFiles=$crashes.Count;files=@($crashes|ForEach-Object {$_.Name})}) "$out\crash-files.json"
foreach($f in $crashes){
 $text=[IO.File]::ReadAllText($f.FullName)
 $text=[regex]::Replace($text,'https?://[^\s"<>]+','[URL]')
 $text=[regex]::Replace($text,'(?i)(SESSDATA|bili_jct|cookie|access_token|refresh_token|auth_key)\s*[:=]\s*[^\s,;]+','$1=[REDACTED]')
 [IO.File]::WriteAllText("$out\sanitized-$($f.Name)",$text,[Text.UTF8Encoding]::new($false))
}
[IO.File]::WriteAllText("$out\signature-matches.txt",(($snippet|ForEach-Object {[regex]::Replace($_,'https?://[^\s"<>]+','[URL]')})-join "`n"),[Text.UTF8Encoding]::new($false))
$checks=@();$accountEmpty=$true
foreach($base in @($local,"$profile\Roaming","$profile\AppData\Local","$profile\AppData\Roaming")){
 foreach($rel in @('BiliPai\session.json','BiliPaiWindows\session.json')){
  $file=Join-Path $base $rel
  if(Test-Path $file){
   $s=Get-Content $file -Raw -Encoding UTF8|ConvertFrom-Json
   $empty=(!$s.account -and @($s.accounts).Count-eq 0 -and !$s.cookies.SESSDATA -and !$s.accessToken -and !$s.refreshToken)
   # @($null).Count is 0 only after filtering; absent account arrays are also empty.
   if(!$s.accounts){$empty=(!$s.account -and !$s.cookies.SESSDATA -and !$s.accessToken -and !$s.refreshToken)}
   $checks+=@([ordered]@{relative=$rel;exists=$true;accountEmpty=[bool]$empty})
   if(!$empty){$accountEmpty=$false}
  }else{$checks+=@([ordered]@{relative=$rel;exists=$false;accountEmpty=$true})}
 }
}
Save-BpJson ([ordered]@{accountEmpty=$accountEmpty;checks=$checks;method='fresh redirected profile; parsed account/saved accounts/authentication presence only; no account content exported';realProfileAccessAudited=$false}) "$out\account-check.json"
$hashes=@()
foreach($f in @("$app\BiliPai Windows.exe","$card\crash-harness.jar","$card\ui-probe.jar")+@(Get-ChildItem "$app\app" -Filter '*.jar'|Where-Object {$_.Name-like '*desktop*'}|ForEach-Object {$_.FullName})){
 if(Test-Path $f){$hashes+=@([ordered]@{file=[IO.Path]::GetFileName($f);sha256=(Get-FileHash $f).Hash;bytes=(Get-Item $f).Length})}
}
Save-BpJson ([ordered]@{packageSha=(& git -C $src rev-parse HEAD).Trim();hashes=$hashes;renderApi='OPENGL';mainInvocations=1;restartIsSeparateMeasuredDescendant=$true}) "$out\artifact.json"
$shots=@(Get-ChildItem $out -Filter '*.png'|ForEach-Object {[ordered]@{file=$_.Name;bytes=$_.Length;sha256=(Get-FileHash $_.FullName).Hash}})
Save-BpJson $shots "$out\screenshot-hashes.json"
if(Test-Path "$out\pid-tree.json"){
 $tree=Get-Content "$out\pid-tree.json" -Raw|ConvertFrom-Json
 Save-BpJson ([ordered]@{observationCount=@($tree.observations).Count;systemDialogObservations=@($tree.observations|Where-Object systemDialogs -gt 0).Count;remaining=@($tree.processes|Where-Object alive).Count;processes=$tree.processes;method='sampled EnumWindows restricted to measured PID+creation identities'}) "$out\tree-summary.json"
}
Copy-Item "$out\*" '\\Mac\vmio\ton1111\evidence' -Recurse -Force
'COLLECTED_NO_ACCOUNT_FILES'
