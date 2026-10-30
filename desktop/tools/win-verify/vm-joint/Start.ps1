param([switch]$SelfTest)
$ErrorActionPreference='Stop'
$card='C:\BuildVM\cards\ton1111';$tools="$card\tools"
. "$tools\Json.ps1"
if((Get-PSDrive C).Free-lt 20GB){throw 'C drive below 20 GB minimum'}
C:\BuildVM\tools\queue.cmd
if(@(Get-ScheduledTask BiliPai-VM-Test,BiliPai-AcceptRun,BiliPai-Accept -ErrorAction SilentlyContinue|Where-Object State -eq Running).Count){throw 'Shared GUI task busy'}
$clients=@(Get-CimInstance Win32_Process -Filter "Name = 'BiliPai Windows.exe'")
if($clients.Count){throw 'Existing BiliPai Windows process; cannot claim GUI'}
if(Get-CimInstance Win32_Process -Filter "Name='java.exe'"|Where-Object {$_.CommandLine-match 'Fixture|com.bilipai.desktop.Main'}){throw 'Existing client JVM; cannot claim GUI'}
$stage=if($SelfTest){'selftest'}else{'evidence'}
$scriptName=if($SelfTest){'SelfTest.ps1'}else{'Session.ps1'}
$out="$card\$stage"
New-Item -ItemType Directory -Force $out | Out-Null
if(!$SelfTest){
 $build=Get-Content '\\Mac\vmio\accept-vm-joint-20261030-last.json' -Raw | ConvertFrom-Json
 if($build.sha-ne '4d1cda2b45de5c9ff808b716f4895d3073b628c1'){throw 'Wrong package SHA'}
 $test=Get-Content "$card\selftest\summary.json" -Raw|ConvertFrom-Json
 if(!$test.passed){throw 'Non-client selftest did not pass'}
 if(Test-Path "$card\profile"){throw 'Client profile already exists; no retry'}
}
$marker=[IO.File]::Open("$card\$stage-used",'CreateNew','Write','Read');$marker.Dispose()
$wrapper="$tools\$stage.cmd"
[IO.File]::WriteAllText($wrapper,"@echo off`r`npowershell.exe -NoProfile -ExecutionPolicy Bypass -File $tools\$scriptName`r`nexit /b %ERRORLEVEL%`r`n",[Text.ASCIIEncoding]::new())
$a=New-ScheduledTaskAction -Execute 'C:\BuildVM\toolchain\pwsh\pwsh.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -File C:\BuildVM\tools\launch-user.ps1 -Executable $wrapper -EvidencePath $out\launch.json -WarmupSeconds 1" -WorkingDirectory $card
$p=New-ScheduledTaskPrincipal -UserId builder -LogonType Interactive -RunLevel Highest
$s=New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([timespan]::FromMinutes(35)) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName 'BiliPai-VM-Test' -Action $a -Principal $p -Settings $s -Force|Out-Null
Start-ScheduledTask BiliPai-VM-Test
$deadline=(Get-Date).AddMinutes(34)
Start-Sleep -Milliseconds 700
while((Get-ScheduledTask BiliPai-VM-Test).State-eq 'Running'){
 if((Get-Date)-gt $deadline){throw 'Session deadline reached; inspect exact task descendants before cleanup'}
 Start-Sleep -Seconds 2
}
$info=Get-ScheduledTaskInfo BiliPai-VM-Test
Save-BpJson ([ordered]@{stage=$stage;lastTaskResult=$info.LastTaskResult;at=(Get-Date).ToString('o')}) "$out\task-result.json"
Copy-Item "$out\task-result.json" "\\Mac\vmio\ton1111\$stage\task-result.json" -Force
if($info.LastTaskResult-ne 0){throw "Interactive task failed: $($info.LastTaskResult)"}
