# Add only java.instrument to the SAME JDK 21 runtime for the test accessibility driver.
# Product JARs, launcher and cfg are untouched. Retain the original runtime for comparison.
$ErrorActionPreference='Stop'
$root='C:\Users\TONYS\bp-mac-verify'
$target="$root\apps\TON1020"
$jdk='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:TEMP="$root\ton1020\profile\Temp"; $env:TMP=$env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null
$env:JAVA_TOOL_OPTIONS="-Duser.home=$root\ton1020\profile -Djava.io.tmpdir=$env:TEMP"
if((Get-ScheduledTask BiliPai-MacTest).State -ne 'Ready'){throw 'measurement occupied'}
if(Test-Path "$root\tools\PAUSE"){throw 'PAUSE present'}
$release=Get-Content "$target\runtime\release"
$modules=($release | Where-Object { $_.StartsWith('MODULES=') }).Split('=')[1].Trim('"').Split(' ')
$newModules=@($modules + 'java.instrument' | Sort-Object -Unique)
$output="$root\ton1047\runtime-with-probe"
$list=$newModules -join ','
& cmd /d /c "`"$jdk\bin\jlink.exe`" --module-path `"$jdk\jmods`" --add-modules $list --no-header-files --no-man-pages --strip-debug --output `"$output`" > `"$root\ton1047\probe-runtime.log`" 2>&1"
if($LASTEXITCODE -ne 0){throw 'probe runtime creation failed'}
Move-Item "$target\runtime" "$root\ton1047\runtime-original"
Move-Item $output "$target\runtime"
$report=[ordered]@{purpose='test-only accessibility agent';javaVersion='21.0.12.1';
    originalModules=$modules;testModules=$newModules;addedModule='java.instrument';productJarsChanged=$false;
    originalRuntime="$root\ton1047\runtime-original";at=(Get-Date).ToString('o')}
$report | ConvertTo-Json -Depth 5 | Set-Content "$root\ton1047\probe-runtime.json" -Encoding UTF8
Write-Output 'PROBE_RUNTIME_READY'
