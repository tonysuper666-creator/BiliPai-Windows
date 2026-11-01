# Invoked by apps\TON1020\run-profile.cmd. No client starts before all checks pass.
param([switch]$CheckOnly,[Parameter(ValueFromRemainingArguments=$true)][string[]]$ClientArgs)
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\TONYS\bp-mac-verify'
$env:TEMP="$root\ton1020\profile\Temp"; $env:TMP=$env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null
. "$root\tools\TreeEvidence.ps1"
if (Test-Path "$root\tools\PAUSE") { throw 'PAUSE present' }
$taskProfile = "$root\ton1020\profile"
$exe = Assert-BpEvidencePath (Join-Path $PSScriptRoot 'BiliPai Windows.exe') "$root\apps\TON1020"
if (!(Test-Path -LiteralPath $exe -PathType Leaf)) { throw 'client executable missing' }
$directories = [ordered]@{
    LOCALAPPDATA="$taskProfile\Local"; APPDATA="$taskProfile\Roaming"; USERPROFILE=$taskProfile;
    HOME=$taskProfile; TEMP="$taskProfile\Temp"; TMP="$taskProfile\Temp";
    XDG_CACHE_HOME="$taskProfile\Cache"; XDG_CONFIG_HOME="$taskProfile\Config"; XDG_DATA_HOME="$taskProfile\Data"
}
foreach ($name in $directories.Keys) {
    $path = Assert-BpEvidencePath ($directories[$name] + '\guard') $taskProfile
    New-Item -ItemType Directory -Force -Path $directories[$name] | Out-Null
    [Environment]::SetEnvironmentVariable($name, $directories[$name], 'Process')
}
$env:HOMEDRIVE = 'C:'
$env:HOMEPATH = '\Users\TONYS\bp-mac-verify\ton1020\profile'
# HotSpot's home property uses the account API, so USERPROFILE alone is insufficient.
# Inherited by DesktopApplicationRestart's ProcessBuilder too.
$javaOptions = "-Duser.home=$taskProfile -Djava.io.tmpdir=$taskProfile\Temp -Djava.util.prefs.userRoot=$taskProfile\Prefs"
$env:JAVA_TOOL_OPTIONS = $javaOptions
$env:_JAVA_OPTIONS = $javaOptions
Remove-Item Env:JDK_JAVA_OPTIONS,Env:BILIPAI_MPV_PATH -ErrorAction SilentlyContinue
foreach ($name in $directories.Keys) {
    if ([Environment]::GetEnvironmentVariable($name,'Process') -ne $directories[$name]) { throw "profile self-check failed: $name" }
}
if ([IO.Path]::GetFullPath($env:HOMEDRIVE + $env:HOMEPATH) -ne $taskProfile) { throw 'home drive self-check failed' }
Save-BpJson ([ordered]@{at=(Get-Date).ToString('o'); executable=$exe; directories=$directories;
    javaToolOptions=$env:JAVA_TOOL_OPTIONS; javaOptions=$env:_JAVA_OPTIONS; check='all redirected before client launch'}) "$taskProfile\isolation.json"
if($CheckOnly){Write-Output 'ISOLATION_PREFLIGHT_OK';exit 0}
Set-Location $taskProfile
$start = @{FilePath=$exe; PassThru=$true; WorkingDirectory=$taskProfile}
if ($ClientArgs.Count) { $start.ArgumentList=$ClientArgs }
$client = Start-Process @start
$client.WaitForExit()
exit $client.ExitCode
