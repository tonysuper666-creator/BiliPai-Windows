$ErrorActionPreference = 'Stop'
$taskCandidate = 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/BiliPai-v023'
$taskLane = 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/BiliPai/desktop/.local/stable-portable-package-actual83/attempt02'
$taskToolchain = 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/toolchain'
$taskJava = Join-Path $taskToolchain 'jdk/jdk-21.0.12.1+1'
$env:GRADLE_USER_HOME = Join-Path $taskToolchain 'gradle-home'
$env:PYTHON_EXECUTABLE = 'C:/Users/TONYS/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$env:BILIPAI_NATIVE_SHARE_VC_ROOT = 'C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC/14.44.35207'
foreach ($taskVariable in @('BILIPAI_MPV_PATH','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS')) {
    [Environment]::SetEnvironmentVariable($taskVariable, $null, 'Process')
}
$env:CI = 'false'
$taskStarted = [DateTime]::UtcNow.ToString('o')
$taskBaseHead = (git -C $taskCandidate rev-parse HEAD).Trim()
if ($taskBaseHead -cne '6fd5bbd804f272650942f5a445385ba5428ffa9b') { throw 'Package source HEAD changed.' }
if ((git -C $taskCandidate status --porcelain)) { throw 'Package source is not clean.' }
$taskRecord = [ordered]@{
    startedAtUtc = $taskStarted
    codeHead = $taskBaseHead
    javaHome = $taskJava
    gradleUserHome = $env:GRADLE_USER_HOME
    pythonExecutable = $env:PYTHON_EXECUTABLE
    nativeShareVcRoot = $env:BILIPAI_NATIVE_SHARE_VC_ROOT
    switches = @('-SkipTests','-NativeSmoke','-NativeMuxSmoke','-UpdaterSmoke')
    releaseGate = $false
    installer = $false
    deployment = $false
    publish = $false
    removedProcessOnlyOverrides = @('BILIPAI_MPV_PATH','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS')
}
$taskRecord | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskLane 'run-info.json') -Encoding utf8
try {
    & (Join-Path $taskCandidate 'desktop/tools/build.ps1') -JavaHome $taskJava -SkipTests -NativeSmoke -NativeMuxSmoke -UpdaterSmoke 2>&1 |
        Tee-Object -FilePath (Join-Path $taskLane 'package.log')
    $taskRecord['buildScriptPassed'] = $true
    $taskRecord['finishedAtUtc'] = [DateTime]::UtcNow.ToString('o')
    $taskRecord | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskLane 'run-result.json') -Encoding utf8
} catch {
    $taskRecord['buildScriptPassed'] = $false
    $taskRecord['finishedAtUtc'] = [DateTime]::UtcNow.ToString('o')
    $taskRecord['failure'] = $_.Exception.Message
    $taskRecord['scriptStackTrace'] = $_.ScriptStackTrace
    $taskRecord | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskLane 'run-result.json') -Encoding utf8
    throw
}
