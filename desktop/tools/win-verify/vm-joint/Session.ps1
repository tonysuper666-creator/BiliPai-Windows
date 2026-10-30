$ErrorActionPreference='Stop'
$card='C:\BuildVM\cards\ton1111';$out="$card\evidence";$tools="$card\tools"
. "$tools\TreeEvidence.ps1"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type @'
using System;using System.Runtime.InteropServices;
public class BpFocus {
 [DllImport("user32.dll")]public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")]public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")]public static extern bool ShowWindow(IntPtr h,int n);
 [DllImport("user32.dll")]public static extern bool SetProcessDPIAware();
}
'@
[BpFocus]::SetProcessDPIAware() | Out-Null
$summary=[ordered]@{clientStarts=1;steps=@();started=(Get-Date).ToString('o')}
function Step($name,[scriptblock]$body){
 try{$value=& $body;$row=[ordered]@{name=$name;passed=$true;value=$value}}
 catch{$row=[ordered]@{name=$name;passed=$false;error=$_.Exception.ToString()}}
 $script:summary.steps+=@($row);Save-BpJson $summary "$out\session.json"
}
function Tick{ $null=Update-BpTree;Start-Sleep -Milliseconds 180 }
function Wait-File($file,[int]$seconds=900){$end=(Get-Date).AddSeconds($seconds);while(!(Test-Path $file)){
 if((Get-Date)-gt $end){throw "Timed out waiting for $file"};Tick
 if(Test-Path "$out\fixture-finished"){throw 'Fixture ended before checkpoint'}
}}
function Keys($keys){
 if(![BpFocus]::SetForegroundWindow([IntPtr]$script:window.hwnd)){throw 'Cannot focus measured window'}
 if([BpFocus]::GetForegroundWindow() -ne [IntPtr]$script:window.hwnd){throw 'Focus belongs to another window'}
 [Windows.Forms.SendKeys]::SendWait($keys);1..5 | ForEach-Object {Tick}
}
function Probe($cmd,$value=''){
 $null=Update-BpTree
 $live=@($script:BpTree.Values | Where-Object {$_.alive -and $_.pid -eq $script:window.pid})
 if($live.Count-ne 1){throw 'UI target not a measured live process'}
 $p=Get-CimInstance Win32_Process -Filter "ProcessId=$($script:window.pid)"
 if(!$p -or $p.CreationDate.Ticks-ne $live[0].createdTicks){throw 'UI PID reused'}
 $tag=[guid]::NewGuid().ToString('N');$config="$card\profile\ui-$tag.properties";$result="$card\profile\ui-$tag.result"
 $value64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($value))
 [IO.File]::WriteAllText($config,"command=$cmd`nvalue=$value64`nresult=$($result.Replace('\','/'))`n",[Text.UTF8Encoding]::new($false))
 $jdk=[Environment]::GetEnvironmentVariable('JAVA_HOME','Machine')
 & "$jdk\bin\java.exe" --add-modules jdk.attach -cp "$card\ui-probe.jar" UiProbe $script:window.pid "$card\ui-probe.jar" $config > "$out\attach-$tag.log" 2>&1
 if($LASTEXITCODE-ne 0){throw "UI attach failed: $LASTEXITCODE"}
 $lines=Get-Content $result -Encoding UTF8
 $dump=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($lines[1]))
 [IO.File]::WriteAllText("$out\ui-$cmd-$tag.txt",$dump,[Text.UTF8Encoding]::new($false))
 if($lines[0]-ne 'OK'){throw $lines[0]};return $dump
}
$managerType=[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
$mediaType=[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]
$asTask=[System.WindowsRuntimeSystemExtensions].GetMethods()|Where-Object {$_.Name-eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count-eq 1 -and $_.GetParameters()[0].ParameterType.Name-eq 'IAsyncOperation`1'}|Select-Object -First 1
function Await($op,[Type]$type){$task=$asTask.MakeGenericMethod($type).Invoke($null,@($op));if(!$task.Wait(10000)){throw 'SMTC timeout'};return $task.Result}
function Media{
 $ss=@($script:manager.GetSessions()|Where-Object {$_.SourceAppUserModelId-like '*BiliPai*'})
 if($ss.Count-ne 1){throw "Expected one BiliPai SMTC session; observed $($ss.Count)"}
 $s=$ss[0];$p=Await ($s.TryGetMediaPropertiesAsync()) $mediaType
 return [pscustomobject]@{session=$s;title=$p.Title;state=$s.GetPlaybackInfo().PlaybackStatus.ToString();source=$s.SourceAppUserModelId;position=$s.GetTimelineProperties().Position.TotalSeconds}
}
function Wait-State($state){$end=(Get-Date).AddSeconds(15);do{$m=Media;if($m.state-eq $state){return $m};Tick}while((Get-Date)-lt $end);throw "SMTC did not become $state"}
function MediaRecord($m){return [ordered]@{title=$m.title;state=$m.state;source=$m.source;position=$m.position}}
try{
 $p=Start-Process "$tools\run.cmd" -WorkingDirectory $card -PassThru
 Initialize-BpTree $p.Id $out | Out-Null
 Step 'initial-window' {
  $script:window=Wait-BpTreeWindow 100
  [BpFocus]::ShowWindow([IntPtr]$script:window.hwnd,3)|Out-Null
  [BpFocus]::SetForegroundWindow([IntPtr]$script:window.hwnd)|Out-Null
  $script:window
 }
 Step 'smtc' {
  try{
   Wait-File "$out\smtc-ready"
   $script:manager=Await ($managerType::RequestAsync()) $managerType
   $before=Media
   if(!$before.title){throw 'SMTC title empty'}
   Save-BpJson (MediaRecord $before) "$out\smtc-before.json"
   if(!(Await ($before.session.TryPauseAsync()) ([bool]))){throw 'SMTC pause rejected'}
   $paused=Wait-State 'Paused';Save-BpJson (MediaRecord $paused) "$out\smtc-paused.json"
   if(!(Await ($before.session.TryPlayAsync()) ([bool]))){throw 'SMTC play rejected'}
   $after=Wait-State 'Playing';Save-BpJson (MediaRecord $after) "$out\smtc-playing.json"
   [ordered]@{before=(MediaRecord $before);paused=(MediaRecord $paused);after=(MediaRecord $after)}
  }finally{[IO.File]::WriteAllText("$out\smtc-done",'true')}
 }
 Step 'restart' {
  try{
   Wait-File "$out\restart-ready"
   $oldPid=$script:window.pid
   # Use the real settings language confirmation that invokes closeApp(restart=true).
   Keys '^,'
   $null=Probe 'click' '(?s).*外观与显示.*'
   1..6|ForEach-Object {Tick}
   $null=Probe 'reveal' '(?s).*应用语言.*'
   $null=Probe 'click' '(?s).*应用语言.*'
   1..3|ForEach-Object {Tick}
   $null=Probe 'click' 'English'
   1..4|ForEach-Object {Tick}
   Save-BpTreeShot $script:window 'settings-restart' | Out-Null
   $null=Update-BpTree
   $null=Probe 'click' '立即重启|立即重啟|Restart Now'
   $script:window=Wait-BpTreeWindow 100 $oldPid
   [BpFocus]::SetForegroundWindow([IntPtr]$script:window.hwnd)|Out-Null
   $before=Probe 'dump'
   Keys '^,'
   $after=Probe 'dump'
   if($before-eq $after -or $after-notmatch 'Settings|Appearance'){throw 'New PID window failed settings interaction'}
   $shot=Save-BpTreeShot $script:window 'restarted-settings'
   [ordered]@{oldPid=$oldPid;newPid=$script:window.pid;uiChanged=$true;screenshot=$shot}
  }finally{[IO.File]::WriteAllText("$out\restart-done",'true')}
 }
}catch{$summary.error=$_.Exception.ToString()}
finally{
 if($script:window){try{Save-BpTreeShot $script:window 'final-window'|Out-Null}catch{$summary.screenshotError=$_.Exception.Message}}
 if($script:BpTree){try{Stop-BpTree;$summary.cleanupRemaining=0}catch{$summary.cleanupError=$_.Exception.Message}}
 $summary.ended=(Get-Date).ToString('o');Save-BpJson $summary "$out\session.json"
 Copy-Item "$out\*" '\\Mac\vmio\ton1111\evidence' -Recurse -Force
}
