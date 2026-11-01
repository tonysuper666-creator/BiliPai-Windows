# Loaded only by the opt-in launcher in the interactive session, after explicit approval.
# One client launch, one settings restart, no retry on a failed action.
param([datetime]$Deadline, [switch]$OfflineContextCheck)
$ErrorActionPreference='Stop'
$root='C:\Users\TONYS\bp-mac-verify'
$taskProfile="$root\ton1020\profile"
$jdk='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$summary=[ordered]@{passed=$false;started=(Get-Date).ToString('o');steps=@();
    video1='BV1GJ411x7h7';video2='BV1JYsd6sE4k';restartKind='settings language confirmation -> closeApp(restart=true)'}
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName System.Windows.Forms
if (-not ([System.Management.Automation.PSTypeName]'BpEvidenceInput').Type) {
Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public static class BpEvidenceInput {
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
'@
}
$managerType=[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
$mediaType=[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]
$asTask=[System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1
function Await-WinRT($Operation,[Type]$ResultType) {
    $task=$asTask.MakeGenericMethod($ResultType).Invoke($null,@($Operation))
    if(!$task.Wait(10000)){throw 'SMTC operation deadline exceeded'}
    return $task.Result
}
function Check-Session {
    if((Get-Date) -gt $Deadline){throw 'single session deadline exceeded'}
    if(Test-Path "$root\tools\PAUSE"){throw 'PAUSE present'}
    $windows=@(Update-BpTree)
    if(@($windows | Where-Object windowClass -eq '#32770').Count){throw 'system dialog observed in process tree'}
}
function Wait-Tracked([int]$Milliseconds) {
    $until=(Get-Date).AddMilliseconds($Milliseconds)
    while((Get-Date) -lt $until){Check-Session;Start-Sleep -Milliseconds 150}
}
function Step([string]$Name,$Evidence) {
    $summary.steps+=@([ordered]@{name=$Name;at=(Get-Date).ToString('o');evidence=$Evidence})
    Save-BpJson $summary (Join-Path $script:BpTreeDirectory 'session.json')
}
function Probe([string]$Command,[string]$Value='') {
    Check-Session
    $windowPid=[int]$script:window.pid
    $live=@($script:BpTree.Values | Where-Object { $_.alive -and $_.pid -eq $windowPid })
    if($live.Count -ne 1){throw 'UI target no longer in measured tree'}
    $p=Get-CimInstance Win32_Process -Filter "ProcessId=$windowPid"
    if(!$p -or $p.CreationDate.Ticks -ne $live[0].createdTicks){throw 'UI PID reused'}
    $tag=([Guid]::NewGuid().ToString('N'))
    $config="$taskProfile\ui-$tag.properties"; $result="$taskProfile\ui-$tag.result"
    $value64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
    @("command=$Command","value=$value64","result=$($result.Replace('\','/'))") | Set-Content $config -Encoding UTF8
    # Java Properties does not accept a UTF-8 BOM.
    [IO.File]::WriteAllText($config,([IO.File]::ReadAllText($config)).TrimStart([char]0xfeff),[Text.UTF8Encoding]::new($false))
    & cmd /d /c "`"$jdk\bin\java.exe`" --add-modules jdk.attach -cp `"$root\apps\TON1047\ui-probe.jar`" UiProbe $windowPid `"$root\apps\TON1047\ui-probe.jar`" `"$config`" > `"$taskProfile\attach-$tag.log`" 2>&1"
    if($LASTEXITCODE -ne 0){throw 'UI attach failed; no second client launch'}
    $lines=@(Get-Content $result -Encoding UTF8)
    $treeText=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($lines[1]))
    Step "ui-$Command" ([ordered]@{pid=$windowPid;status=$lines[0];tree=$treeText})
    if($lines[0] -ne 'OK'){throw $lines[0]}
    return $treeText
}
function Keys([string]$Sequence) {
    Check-Session
    $hwnd=[IntPtr]$script:window.hwnd
    if(![BpEvidenceInput]::SetForegroundWindow($hwnd)){throw 'cannot focus measured window'}
    if([BpEvidenceInput]::GetForegroundWindow() -ne $hwnd){throw 'input focus belongs to another window'}
    [Windows.Forms.SendKeys]::SendWait($Sequence)
    Wait-Tracked 600
}
function Session-Media {
    $sessions=@($script:mediaManager.GetSessions() | Where-Object {
        $_.SourceAppUserModelId -like '*BiliPai*'
    })
    # Read/control no unrelated app's media; exactly one BiliPai source is required.
    if($sessions.Count -eq 0){return $null}
    if($sessions.Count -ne 1){throw 'ambiguous BiliPai SMTC source'}
    $session=$sessions[0]
    $properties=Await-WinRT ($session.TryGetMediaPropertiesAsync()) $mediaType
    return [pscustomobject]@{session=$session;title=$properties.Title;
        source=$session.SourceAppUserModelId;state=$session.GetPlaybackInfo().PlaybackStatus.ToString();
        position=$session.GetTimelineProperties().Position.TotalSeconds}
}
function Wait-Media([string]$DifferentTitle='',[string]$State='Playing') {
    $end=(Get-Date).AddSeconds(35)
    while((Get-Date) -lt $end) {
        Check-Session
        $m=Session-Media
        if($m.title -and $m.title -ne $DifferentTitle -and $m.state -eq $State){return $m}
        Start-Sleep -Milliseconds 200
    }
    throw 'SMTC title/playback state did not meet deadline'
}
function Record-Video([string]$Name,[string]$DifferentTitle='') {
    $m=Wait-Media $DifferentTitle
    $tree=Probe 'dump'
    if(!$tree.Contains($m.title)){throw 'SMTC title absent from currently displayed accessible UI'}
    Wait-Tracked 2000
    $after=Session-Media
    if($after.position -le $m.position){throw 'media timeline did not progress'}
    $shot=Save-BpTreeShot $script:window $Name
    Step $Name ([ordered]@{title=$m.title;source=$m.source;state=$m.state;positionBefore=$m.position;positionAfter=$after.position;screenshot=$shot})
    return $m
}
try {
    if ($OfflineContextCheck) {
        # Execute the first tree update and the real error/finally reporting path,
        # without GUI, SMTC enumeration, input, launches or process termination.
        $null=Update-BpTree
        $summary.offlineContextCheck=$true
        Save-BpJson ([ordered]@{rootPid=$script:BpRootProcessId;directory=$script:BpTreeDirectory;
            deadline=$Deadline.ToString('o');clientStarts=0;guiStarts=0}) (Join-Path $script:BpTreeDirectory 'context-check.json')
        return
    }
    $script:window=Wait-BpTreeWindow 65
    Step 'initial-window' $script:window
    $script:mediaManager=Await-WinRT ($managerType::RequestAsync()) $managerType
    $first=Record-Video 'video-1'
    # Return from the player before invoking the shell's search shortcut.
    Keys '%{LEFT}'
    Keys '^k'
    $null=Probe 'text' $summary.video2
    Keys '{ENTER}'
    Wait-Tracked 3000
    # SearchSubmitPolicy resolves a BV identifier to OpenNativeTarget directly.
    Wait-Tracked 2000
    $second=Record-Video 'video-2' $first.title
    $paused=Await-WinRT ($second.session.TryPauseAsync()) ([bool])
    if(!$paused){throw 'SMTC pause request rejected'}
    $pauseState=Wait-Media '' 'Paused'
    Step 'smtc-paused' ([ordered]@{title=$pauseState.title;state=$pauseState.state})
    $played=Await-WinRT ($second.session.TryPlayAsync()) ([bool])
    if(!$played){throw 'SMTC play request rejected'}
    $playing=Wait-Media
    Step 'smtc-playing' ([ordered]@{title=$playing.title;state=$playing.state})
    Keys '%{LEFT}'
    Keys '^,'
    $null=Probe 'click' '(?s).*外观与动画.*'
    Wait-Tracked 800
    $null=Probe 'click' '(?s).*外观设置.*'
    Wait-Tracked 800
    $null=Probe 'click' 'English'
    Wait-Tracked 500
    $oldPid=$script:window.pid
    Step 'restart-request' ([ordered]@{oldPid=$oldPid;settingsShot=(Save-BpTreeShot $script:window 'settings-restart')})
    $null=Probe 'click' '立即重启'
    $script:window=Wait-BpTreeWindow 65 $oldPid
    if($script:window.pid -eq $oldPid){throw 'restart kept original PID'}
    # A successful settings shortcut must visibly change the NEW UI; WM_NULL alone is insufficient.
    $before=Probe 'dump'
    Keys '^,'
    $after=Probe 'dump'
    if($before -eq $after -or $after -notmatch 'Settings|Appearance'){throw 'new process window did not respond to settings input'}
    Step 'restarted-responsive' ([ordered]@{oldPid=$oldPid;newPid=$script:window.pid;
        screenshot=(Save-BpTreeShot $script:window 'restarted-settings');uiChanged=$true})
    $summary.passed=$true
} catch {
    $summary.error=$_.Exception.Message
    if($script:window){try{Step 'failure-shot' (Save-BpTreeShot $script:window 'failure')}catch{}}
    throw
} finally {
    $summary.ended=(Get-Date).ToString('o')
    Save-BpJson $summary (Join-Path $script:BpTreeDirectory 'session.json')
}
