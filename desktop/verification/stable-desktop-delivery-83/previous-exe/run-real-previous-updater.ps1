$ErrorActionPreference = 'Stop'
$taskCandidate = 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/BiliPai-v023'
$taskLane = 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/BiliPai/desktop/.local/stable-updater-real-previous-forwarding83'
$taskToolchain = 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/toolchain'
$taskPreflight = Get-Content -LiteralPath (Join-Path $taskLane 'package-preflight.json') -Raw | ConvertFrom-Json
if ($taskPreflight.allPreflightChecksPassed -ne $true) { throw 'Package identity preflight did not pass.' }
$env:JAVA_HOME = Join-Path $taskToolchain 'jdk/jdk-21.0.12.1+1'
$env:GRADLE_USER_HOME = Join-Path $taskToolchain 'gradle-home'
$env:PYTHON_EXECUTABLE = 'C:/Users/TONYS/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$env:BILIPAI_NATIVE_SHARE_VC_ROOT = 'C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC/14.44.35207'
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
foreach ($taskVariable in @('BILIPAI_MPV_PATH','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS')) {
    [Environment]::SetEnvironmentVariable($taskVariable, $null, 'Process')
}
$env:CI = 'false'
$taskGradle = Join-Path $env:GRADLE_USER_HOME 'wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat'
$taskReportRoot = Join-Path $taskLane 'reports'
if (Test-Path -LiteralPath $taskReportRoot) { throw 'Keep previous optional smoke report immutable.' }
New-Item -ItemType Directory -Path $taskReportRoot | Out-Null
$taskArguments = @('-p','desktop','--console=plain','updaterSmoke',
    ('-PupdateTestPackage=' + $taskPreflight.currentArchive),
    ('-PupdatePreviousPackage=' + $taskPreflight.previousArchive),
    ('-PupdateTestReport=' + $taskReportRoot))
$taskRecord = [ordered]@{
    startedUtc = [DateTime]::UtcNow.ToString('o')
    harnessCodeHead = (git -C $taskCandidate rev-parse HEAD).Trim()
    packagedCodeHead = $taskPreflight.currentPackagedSourceCommit
    arguments = $taskArguments
    gradle = $taskGradle
    javaHome = $env:JAVA_HOME
    sourceStatusBefore = @(git -C $taskCandidate status --porcelain)
    packageRebuilt = $false
    nativeMuxRerun = $false
    productionEdits = $false
    testEdits = $false
    userDesktopExeStarted = $false
    userAccountDataTouched = $false
}
Push-Location $taskCandidate
try {
    & $taskGradle @taskArguments 2>&1 | Tee-Object -FilePath (Join-Path $taskLane 'updater-real-previous.log')
    $taskRecord['gradleExitCode'] = $LASTEXITCODE
    if ($LASTEXITCODE -ne 0) { throw "Real previous EXE forwarding updater smoke failed ($LASTEXITCODE)." }
    $taskRecord['passed'] = $true
} catch {
    $taskRecord['passed'] = $false
    $taskRecord['failure'] = $_.Exception.Message
    throw
} finally {
    $taskRecord['finishedUtc'] = [DateTime]::UtcNow.ToString('o')
    $taskRecord['sourceStatusAfter'] = @(git -C $taskCandidate status --porcelain)
    $taskRecord | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskLane 'run-result.json') -Encoding utf8
    $taskXml = Join-Path $taskCandidate 'desktop/build/test-results/updaterSmoke/TEST-com.bilipai.desktop.update.DesktopUpdaterIntegrationTest.xml'
    if (Test-Path -LiteralPath $taskXml) { Copy-Item -LiteralPath $taskXml -Destination $taskLane }
    Pop-Location
}
