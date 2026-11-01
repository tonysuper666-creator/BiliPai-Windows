$ErrorActionPreference='Stop'
. "$PSScriptRoot\Native.ps1"
. "$PSScriptRoot\Json.ps1"
$out='C:\BuildVM\cards\ton1111\native-regression'
New-Item -ItemType Directory -Force $out | Out-Null
$jdk=[Environment]::GetEnvironmentVariable('JAVA_HOME','Machine')
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
$legacy=$null
try{& "$jdk\bin\java.exe" '-version' > "$out\legacy.out.log" 2> "$out\legacy.err.log"}
catch{$legacy=[ordered]@{id=$_.FullyQualifiedErrorId;message=$_.Exception.Message}}
if(!$legacy -or $legacy.id-notmatch 'NativeCommandError' -or $legacy.message-notmatch 'Picked up JAVA_TOOL_OPTIONS'){throw 'Did not reproduce the actual native-stderr failure'}
$exit=Invoke-BpNativeWithLogs "$jdk\bin\java.exe" @('-version') "$out\fixed.out.log" "$out\fixed.err.log"
if($exit-ne 0){throw "Fixed native invocation failed with $exit"}
if((Get-Item "$out\fixed.err.log").Length-eq 0){throw 'Expected successful JVM version output on stderr'}
if($ErrorActionPreference-ne 'Stop'){throw 'Native wrapper changed the caller error preference'}
Save-BpJson ([ordered]@{passed=$true;clientStarts=0;guiStarts=0;legacy=$legacy;fixedExitCode=$exit;fixedStderrBytes=(Get-Item "$out\fixed.err.log").Length;errorPreferenceRestored=$true;test='same JDK invocation and redirection, java -version only'}) "$out\summary.json"
Copy-Item "$out\*" '\\Mac\vmio\ton1111\native-regression' -Force
'NATIVE_STDERR_REGRESSION_PASS NO_CLIENT_START'
