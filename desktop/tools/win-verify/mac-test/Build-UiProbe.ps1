$ErrorActionPreference='Stop'
$root='C:\Users\TONYS\bp-mac-verify'
$jdk='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$out="$root\apps\TON1047"
$env:TEMP="$root\ton1020\profile\Temp"; $env:TMP=$env:TEMP
New-Item -ItemType Directory -Force $out,"$out\classes",$env:TEMP | Out-Null
$env:JAVA_TOOL_OPTIONS="-Duser.home=$root\ton1020\profile -Djava.io.tmpdir=$env:TEMP"
& cmd /d /c "`"$jdk\bin\javac.exe`" --add-modules jdk.attach -encoding UTF-8 -d `"$out\classes`" `"$PSScriptRoot\UiProbe.java`" > `"$out\compile.log`" 2>&1"
if($LASTEXITCODE -ne 0){throw 'UiProbe compile failed'}
@('Agent-Class: UiProbe','Main-Class: UiProbe','') | Set-Content "$out\MANIFEST.MF" -Encoding ascii
& cmd /d /c "`"$jdk\bin\jar.exe`" --create --file `"$out\ui-probe.jar`" --manifest `"$out\MANIFEST.MF`" -C `"$out\classes`" . > `"$out\jar.log`" 2>&1"
if($LASTEXITCODE -ne 0){throw 'UiProbe jar failed'}
Copy-Item "$PSScriptRoot\Verify-TON1047.ps1" $out -Force
Write-Output 'UI_PROBE_COMPILE_OK'
