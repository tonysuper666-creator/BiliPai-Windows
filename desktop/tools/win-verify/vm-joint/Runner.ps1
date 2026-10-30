$ErrorActionPreference='Stop'
. "$PSScriptRoot\Native.ps1"
$card='C:\BuildVM\cards\ton1111';$profile="$card\profile";$report="$card\evidence"
$app='C:\BuildVM\accept\vm-joint-20261030\desktop\build\compose\binaries\main\app\BiliPai Windows\app'
if(Test-Path $profile){throw 'Profile exists: a second client invocation is forbidden'}
$local="$profile\Temp\BiliPai-v025-root-routes-ton1111"
New-Item -ItemType Directory -Force $local,"$profile\Roaming",$report | Out-Null
$token=[guid]::NewGuid().ToString()
[IO.File]::WriteAllText("$local\.bilipai-root-validation",$token)
$env:LOCALAPPDATA=$local;$env:APPDATA="$profile\Roaming";$env:TEMP="$profile\Temp";$env:TMP=$env:TEMP
$env:_JAVA_OPTIONS='';$env:SKIKO_RENDER_API='OPENGL'
# Inherited by the real application restart as well as the first JVM.
$env:JAVA_TOOL_OPTIONS="-Dskiko.renderApi=OPENGL -Dfile.encoding=UTF-8 -Duser.home=$profile -Djava.io.tmpdir=$env:TEMP"
$env:BP_SECOND_VIDEO='BV1mdxN68E7F'
$jdk=[Environment]::GetEnvironmentVariable('JAVA_HOME','Machine')
$cfg=Get-Content "$app\BiliPai Windows.cfg"
$cp=@("$card\crash-harness.jar")+@($cfg | Where-Object {$_.StartsWith('app.classpath=')} | ForEach-Object {$_.Substring(14).Replace('$APPDIR',$app)})
foreach($f in $cp){if(!(Test-Path $f -PathType Leaf)){throw "Missing runtime classpath: $f"}}
$opts=@($cfg | Where-Object {$_.StartsWith('java-options=')} | ForEach-Object {$_.Substring(13).Replace('$APPDIR',$app)})
$opts+="-Dbilipai.rootValidationToken=$token"
$arguments=@($opts)+@('-cp',($cp-join ';'),'com.bilipai.desktop.ui.WindowsVideoCrashRegressionFixture',$report,'BV1v6xj6gE6u')
$clientExit=Invoke-BpNativeWithLogs "$jdk\bin\java.exe" $arguments "$report\client.stdout.log" "$report\client.stderr.log"
[IO.File]::WriteAllText("$report\initial-exit.json",('{"exitCode":'+$clientExit+'}'))
# Keep the launch root alive while the real restarted descendant is measured.
while(!(Test-Path "$report\cleanup-ready")){Start-Sleep -Milliseconds 250}
