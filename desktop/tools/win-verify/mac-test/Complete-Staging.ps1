# Finish a successful distributable after missing untracked mpv/ffmpeg assets are discovered.
# Does not rebuild, start a client or replace the newly built diagnostic-share DLL.
$ErrorActionPreference='Stop'
$root='C:\Users\TONYS\bp-mac-verify'
$out="$root\ton1047"
$target="$root\apps\TON1020"
$report=Get-Content "$out\package.json" -Raw -Encoding UTF8 | ConvertFrom-Json
if($report.buildExit -ne 0){throw 'no successful distributable build to stage'}
if((Get-ScheduledTask BiliPai-MacTest).State -ne 'Ready'){throw 'measurement occupied'}
if(Test-Path "$root\tools\PAUSE"){throw 'PAUSE present'}
$head=(& git -C "$out\src" rev-parse HEAD).Trim()
if($head -ne $report.head){throw 'source head changed'}
$native="$root\apps\TON1020-before-TON1047\app\resources\native\windows-x64"
$runtime="$target\app\resources\native\windows-x64"
New-Item -ItemType Directory -Force $runtime | Out-Null
$supplemented=@()
foreach($file in Get-ChildItem $native -File){
    if(!(Test-Path "$runtime\$($file.Name)")){
        Copy-Item $file.FullName $runtime
        $supplemented+=@([ordered]@{source=$file.FullName;sha256=(Get-FileHash $file.FullName -Algorithm SHA256).Hash})
    }
}
foreach($name in @('libmpv-2.dll','ffmpeg.exe','ffprobe.exe','bilipai-diagnostic-share.dll')){
    if(!(Test-Path "$runtime\$name")){throw "native runtime missing: $name"}
}
Copy-Item "$PSScriptRoot\Start-Isolated.ps1","$PSScriptRoot\run-profile.cmd" $target -Force
$result=[ordered]@{head=$head;passed=$true;stage='ready-without-client-launch';buildExit=$report.buildExit;
    javaHome='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot';
    exe="$target\BiliPai Windows.exe";exeSha256=(Get-FileHash "$target\BiliPai Windows.exe" -Algorithm SHA256).Hash;
    nativeSupplement=$supplemented;started=$report.started;ended=(Get-Date).ToString('o');
    files=@(Get-ChildItem $target -Recurse -File | ForEach-Object {
        [ordered]@{path=$_.FullName.Substring($target.Length+1);length=$_.Length;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash}
    })}
$result | ConvertTo-Json -Depth 8 | Set-Content "$out\package.json" -Encoding UTF8
Write-Output "STAGED_HEAD=$head"
Write-Output "EXE_SHA256=$($result.exeSha256)"
