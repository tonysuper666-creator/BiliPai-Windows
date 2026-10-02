$ErrorActionPreference = 'Stop'
$taskCandidate = 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/BiliPai-v023'
$taskLane = 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/BiliPai/desktop/.local/stable-story-initial-failure-test-wiring82'
$taskToolchain = 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/toolchain'
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
$taskRecord = [ordered]@{
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    codeHead = (git -C $taskCandidate rev-parse HEAD).Trim()
    gradle = $taskGradle
    javaHome = $env:JAVA_HOME
    tests = @('com.bilipai.desktop.ui.DesktopStoryInitialFailureTest','com.bilipai.desktop.ui.DesktopStoryInitialFailureUiTest')
    productionEditsByThisLane = $false
    repackaged = $false
    committed = $false
}
Push-Location $taskCandidate
try {
    & $taskGradle -p desktop --console=plain compileTestKotlin 2>&1 | Tee-Object -FilePath (Join-Path $taskLane 'compileTestKotlin.log')
    $taskRecord['compileTestKotlinExitCode'] = $LASTEXITCODE
    if ($LASTEXITCODE -ne 0) { throw "compileTestKotlin failed ($LASTEXITCODE)." }
    & $taskGradle -p desktop --console=plain test --tests 'com.bilipai.desktop.ui.DesktopStoryInitialFailureTest' --tests 'com.bilipai.desktop.ui.DesktopStoryInitialFailureUiTest' 2>&1 | Tee-Object -FilePath (Join-Path $taskLane 'story-targeted-tests.log')
    $taskRecord['targetedTestsExitCode'] = $LASTEXITCODE
    if ($LASTEXITCODE -ne 0) { throw "Story targeted tests failed ($LASTEXITCODE)." }
    $taskRecord['passed'] = $true
} catch {
    $taskRecord['passed'] = $false
    $taskRecord['failure'] = $_.Exception.Message
    throw
} finally {
    $taskRecord['finishedAtUtc'] = [DateTime]::UtcNow.ToString('o')
    $taskRecord | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskLane 'run-result.json') -Encoding utf8
    $taskResults = Join-Path $taskLane 'junit'
    New-Item -ItemType Directory -Path $taskResults -Force | Out-Null
    foreach ($taskName in $taskRecord.tests) {
        $taskXml = Join-Path $taskCandidate ('desktop/build/test-results/test/TEST-' + $taskName + '.xml')
        if (Test-Path -LiteralPath $taskXml) { Copy-Item -LiteralPath $taskXml -Destination $taskResults }
    }
    Pop-Location
}
