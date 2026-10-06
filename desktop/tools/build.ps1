[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$SevenZipPath,
    [switch]$Installer,
    [switch]$SkipTests,
    [switch]$ReleaseGate,
    [switch]$NativeSmoke,
    [switch]$NativeMuxSmoke,
    [switch]$UpdaterSmoke,
    [string]$PreviousUpdateTestPackage
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$desktopRoot = Join-Path $repoRoot 'desktop'
# Even parameter/JDK failures must invalidate evidence from an earlier build.
$gatePath = Join-Path $desktopRoot 'build/release-gate.json'
if (Test-Path -LiteralPath $gatePath) { Remove-Item -LiteralPath $gatePath -Force }
if ($ReleaseGate -and $SkipTests) { throw 'ReleaseGate cannot skip unit tests.' }
$releaseSourceSha = $null
if ($ReleaseGate) {
    $releaseSourceSha = & git -C $repoRoot rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $releaseSourceSha -cnotmatch '^[0-9a-f]{40}$') {
        throw 'ReleaseGate requires a fixed Windows source commit.'
    }
    $sourceChanges = & git -C $repoRoot status --porcelain --untracked-files=all
    if ($LASTEXITCODE -ne 0 -or $sourceChanges) {
        throw 'ReleaseGate requires committed Windows sources in a clean checkout.'
    }
}
# Independent smoke checks may use SkipTests; only the full ReleaseGate creates release evidence.
if ($PreviousUpdateTestPackage -and -not ($ReleaseGate -or $UpdaterSmoke)) {
    throw 'PreviousUpdateTestPackage requires UpdaterSmoke or ReleaseGate.'
}
if ($PreviousUpdateTestPackage) {
    $PreviousUpdateTestPackage = [IO.Path]::GetFullPath($PreviousUpdateTestPackage)
    if (-not (Test-Path -LiteralPath $PreviousUpdateTestPackage -PathType Leaf)) { throw 'The previous updater test ZIP is missing.' }
}

if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) {
    $portableRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot '../toolchain'))
    if (Test-Path -LiteralPath $portableRoot) {
        $JavaHome = Get-ChildItem -LiteralPath $portableRoot -Directory -Recurse |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } |
            Select-Object -First 1 -ExpandProperty FullName
    }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'JDK 21 is required. Pass -JavaHome <portable JDK directory> or set JAVA_HOME for this process.'
}
$JavaHome = [IO.Path]::GetFullPath($JavaHome)
$originalJavaHome, $originalPath = $env:JAVA_HOME, $env:PATH
try {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + $originalPath
    & (Join-Path $JavaHome 'bin/java.exe') -version
    if ($LASTEXITCODE -ne 0) { throw 'The selected JDK could not start.' }

    $fetchArguments = @{}
    if ($SevenZipPath) { $fetchArguments.SevenZipPath = $SevenZipPath }
    & (Join-Path $PSScriptRoot 'fetch-mpv.ps1') @fetchArguments
    & (Join-Path $PSScriptRoot 'fetch-ffmpeg.ps1')

    $nativeSource = Join-Path $desktopRoot 'native/windows-x64'
    $nativeResources = Join-Path $desktopRoot 'resources/common/native/windows-x64'
    New-Item -ItemType Directory -Path $nativeResources -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $nativeSource 'libmpv-2.dll') -Destination $nativeResources -Force
    foreach ($fileName in @('ffmpeg.exe', 'ffprobe.exe', 'ffmpeg-provenance.json')) {
        Copy-Item -LiteralPath (Join-Path $nativeSource $fileName) -Destination $nativeResources -Force
    }
    $licenseSource = Join-Path $nativeSource 'licenses'
    if (Test-Path -LiteralPath $licenseSource) {
        Copy-Item -LiteralPath $licenseSource -Destination $nativeResources -Recurse -Force
    }
    Copy-Item -LiteralPath (Join-Path $nativeSource 'provenance.json') -Destination $nativeResources -Force

    Push-Location $repoRoot
    try {
        $gradleArguments = @('-p', 'desktop', '--console=plain')
        if (-not $SkipTests) { $gradleArguments += 'test' }
        if ($ReleaseGate) { $gradleArguments += 'backendSmoke' }
        $gradleArguments += 'createDistributable'
        if ($Installer) { $gradleArguments += 'packageMsi' }
        & (Join-Path $repoRoot 'gradlew.bat') @gradleArguments
        if ($LASTEXITCODE -ne 0) { throw "Windows Gradle build failed ($LASTEXITCODE)." }
        if (-not $SkipTests) {
            # These contracts inspect the actual generated Kotlin and resources.
            # They must run after Gradle generation, before packaging/publication.
            $pythonExecutable = if ($env:PYTHON_EXECUTABLE) { $env:PYTHON_EXECUTABLE } else { 'python' }
            & $pythonExecutable (Join-Path $PSScriptRoot 'run-tool-tests.py') --stage generated
            if ($LASTEXITCODE -ne 0) { throw "Windows source contract tests failed ($LASTEXITCODE)." }
        }
    } finally { Pop-Location }

    $appRoot = Join-Path $desktopRoot 'build/compose/binaries/main/app'
    $executable = Get-ChildItem -LiteralPath $appRoot -Filter 'BiliPai Windows.exe' -File -Recurse | Select-Object -First 1
    if (-not $executable) { throw 'createDistributable did not produce a native Windows launcher.' }
    if ($ReleaseGate -or $NativeMuxSmoke) {
        $packagedNativeRoot = Join-Path $executable.DirectoryName 'app/resources/native/windows-x64'
        $muxSmokeRoot = Join-Path $desktopRoot ('build/reports/native-mux-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $muxSmokeRoot -Force | Out-Null
        $muxReportPath = Join-Path $muxSmokeRoot 'native-download-mux.json'
        $packagedFfmpeg = Join-Path $packagedNativeRoot 'ffmpeg.exe'
        $packagedFfprobe = Join-Path $packagedNativeRoot 'ffprobe.exe'
        Push-Location $repoRoot
        try {
            & (Join-Path $repoRoot 'gradlew.bat') -p desktop --console=plain nativeMuxSmoke `
                "-PnativeMuxFfmpeg=$packagedFfmpeg" "-PnativeMuxFfprobe=$packagedFfprobe" "-PnativeMuxReport=$muxReportPath"
            if ($LASTEXITCODE -ne 0) { throw "Packaged native download mux test failed ($LASTEXITCODE)." }
        } finally { Pop-Location }
        if (-not (Test-Path -LiteralPath $muxReportPath -PathType Leaf)) { throw 'Packaged native download mux produced no report.' }
        $muxReport = Get-Content -LiteralPath $muxReportPath -Raw | ConvertFrom-Json
        if ($muxReport.passed -ne $true -or
            $muxReport.ffmpegSha256 -cne (Get-FileHash -LiteralPath $packagedFfmpeg -Algorithm SHA256).Hash.ToLowerInvariant() -or
            $muxReport.ffprobeSha256 -cne (Get-FileHash -LiteralPath $packagedFfprobe -Algorithm SHA256).Hash.ToLowerInvariant()) {
            throw 'Native download mux evidence differs from the packaged binaries.'
        }
        foreach ($check in @('dualTrackCopyMux', 'audioOnlyCopyMux', 'progressiveCopyMux',
            'multiSegmentCopyMux', 'audioOnlyMultiSegmentCopyMux', 'videoOnlyMultiSegmentCopyMux', 'decodedAllOutputs')) {
            if ($muxReport.$check -ne $true) { throw "Native download mux did not verify $check." }
        }
        if (@($muxReport.outputs).Count -ne 6) { throw 'Native download mux must decode all six output variants.' }
    }
    if ($ReleaseGate -or $NativeSmoke) {
        $smokePrefix = if ($ReleaseGate) { 'release-gate-' } else { 'native-smoke-' }
        $smokeRoot = Join-Path $desktopRoot ('build/reports/' + $smokePrefix + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $smokeRoot -Force | Out-Null
        $nativeSmokeArgument = if ($env:CI -eq 'true') { '--player-self-test-ci' } else { '--player-self-test' }
        # Bound only this test-owned launcher; native/player pixel gates remain unchanged.
        $nativeTimeoutMilliseconds = 300000
        $nativeTerminationWaitMilliseconds = 10000
        $nativeProcess, $nativeProcessHandle = $null, $null
        $nativePhasePassed, $nativeNaturalExit = $false, $false
        $nativeClock = [Diagnostics.Stopwatch]::StartNew()
        $nativeParentPath = Join-Path $smokeRoot 'native-player-smoke-parent-timeout.json'
        $nativeParent = [ordered]@{
            schemaVersion = 1; phase = 'packaged-native-smoke'; passed = $false; outcome = 'failed'
            startedAtUtc = [DateTime]::UtcNow.ToString('o'); completedAtUtc = $null; elapsedMilliseconds = 0
            timeoutMilliseconds = $nativeTimeoutMilliseconds; terminationWaitMilliseconds = $nativeTerminationWaitMilliseconds
            executable = [IO.Path]::GetFullPath($executable.FullName); processId = $null; processStartedAtUtc = $null
            timedOut = $false; processExited = $false; exitCode = $null
            ownedHandleRetained = $false; cleanupAttempted = $false; cleanupSkippedReason = $null
            terminationRequested = $false; terminationObserved = $false; terminationErrorType = $null; errorType = $null
        }
        Write-Host "Native smoke phase begin: $($nativeParent.startedAtUtc); budget=$($nativeTimeoutMilliseconds)ms"
        try {
            $nativeProcess = Start-Process -FilePath $executable.FullName -ArgumentList @(
                $nativeSmokeArgument, ('"' + $smokeRoot + '"')
            ) -WorkingDirectory $executable.DirectoryName -WindowStyle Hidden -PassThru
            # Retain the handle from the returned Process through wait/termination; never rediscover by PID/name.
            $nativeProcessHandle = $nativeProcess.SafeHandle
            if ($nativeProcessHandle.IsInvalid -or $nativeProcessHandle.IsClosed) { throw 'Native test process handle is unavailable.' }
            $nativeParent.ownedHandleRetained = $true
            $nativeParent.processId = $nativeProcess.Id
            $nativeParent.processStartedAtUtc = $nativeProcess.StartTime.ToUniversalTime().ToString('o')
            $nativeWaitMilliseconds = [int][Math]::Max(0, $nativeTimeoutMilliseconds - $nativeClock.ElapsedMilliseconds)
            if ($nativeWaitMilliseconds -le 0 -or -not $nativeProcess.WaitForExit($nativeWaitMilliseconds)) {
                $nativeParent.timedOut = $true
                $nativeParent.outcome = 'timeout'
                $nativeParent.elapsedMilliseconds = $nativeClock.ElapsedMilliseconds
                $nativeParent | ConvertTo-Json | Set-Content -LiteralPath $nativeParentPath -Encoding utf8
                Write-Host "Native smoke phase timeout: budget=$($nativeTimeoutMilliseconds)ms; process=$($nativeParent.processId)"
                # Even a natural exit racing the deadline cannot turn a timeout into a pass.
                throw "Packaged native playback test exceeded $($nativeTimeoutMilliseconds)ms; see native-player-smoke-parent-timeout.json."
            }
            $nativeNaturalExit = $true
            $nativeParent.processExited = $true
            $nativeParent.exitCode = $nativeProcess.ExitCode
            if ($nativeProcess.ExitCode -ne 0) { throw "Packaged native playback test failed ($($nativeProcess.ExitCode))." }
            $nativeReportPath = Join-Path $smokeRoot 'native-player-smoke.json'
            if (-not (Test-Path -LiteralPath $nativeReportPath)) { throw 'Packaged player produced no native test report.' }
            $nativeReport = Get-Content -LiteralPath $nativeReportPath -Raw | ConvertFrom-Json
            if ($nativeReport.passed -ne $true) { throw 'Packaged native playback checks did not pass.' }
            $nativePhasePassed = $true
            $nativeParent.outcome = 'passed'
        } catch {
            $nativePrimaryError = $_
            $nativeParent.errorType = $nativePrimaryError.Exception.GetType().FullName
            # One cleanup path for deadlines and parent errors after safe ownership was established.
            try {
                if ($nativeParent.ownedHandleRetained -and $null -ne $nativeProcessHandle -and
                    -not $nativeProcessHandle.IsInvalid -and -not $nativeProcessHandle.IsClosed) {
                    $nativeParent.cleanupAttempted = $true
                    if ($nativeProcess.HasExited) {
                        $nativeParent.processExited = $true
                    } else {
                        $nativeParent.terminationRequested = $true
                        # The retained handle stays owned until finally; never reopen by PID or kill descendants.
                        $nativeProcess.Kill()
                        $nativeParent.processExited = $nativeProcess.WaitForExit($nativeTerminationWaitMilliseconds)
                        $nativeParent.terminationObserved = $nativeParent.processExited
                    }
                    if ($nativeParent.processExited) { $nativeParent.exitCode = $nativeProcess.ExitCode }
                } else {
                    $nativeParent.cleanupSkippedReason = 'no-valid-retained-process-handle'
                }
            } catch { $nativeParent.terminationErrorType = $_.Exception.GetType().FullName }
            throw $nativePrimaryError
        } finally {
            $nativeClock.Stop()
            $nativeParent.completedAtUtc = [DateTime]::UtcNow.ToString('o')
            $nativeParent.elapsedMilliseconds = $nativeClock.ElapsedMilliseconds
            try {
                if (-not $nativePhasePassed) {
                    $nativeParent | ConvertTo-Json | Set-Content -LiteralPath $nativeParentPath -Encoding utf8
                }
                Write-Host "Native smoke phase end: $($nativeParent.completedAtUtc); elapsed=$($nativeParent.elapsedMilliseconds)ms; success=$nativePhasePassed; naturalExit=$nativeNaturalExit"
            } finally {
                if ($null -ne $nativeProcess) { $nativeProcess.Dispose() }
            }
        }
    }
    $manifest = Get-Content -LiteralPath (Join-Path $desktopRoot 'upstream-sources.json') -Raw | ConvertFrom-Json
    $versionLabel = [regex]::Replace($manifest.upstreamTag, '[^A-Za-z0-9._-]', '-')
    $outputRoot = Join-Path $desktopRoot 'build/distributions'
    New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
    $buildInfoPath = Join-Path $desktopRoot 'build/resources/main/windows-update.json'
    $windowsVersion = if (Test-Path -LiteralPath $buildInfoPath) {
        $buildInfo = Get-Content -LiteralPath $buildInfoPath -Raw | ConvertFrom-Json
        if ($buildInfo.version) { $buildInfo.version } else { $buildInfo.buildVersion }
    } else { $versionLabel }
    $windowsVersion = [regex]::Replace($windowsVersion, '[^A-Za-z0-9._-]', '-')
    $archive = Join-Path $outputRoot "BiliPai-Windows-$windowsVersion-x64.zip"
    $stagedArchive = Join-Path $outputRoot ('package-' + [Guid]::NewGuid().ToString('N') + '.zip')
    $stagedChecksum = $stagedArchive + '.sha256'
    Compress-Archive -Path (Join-Path $appRoot '*') -DestinationPath $stagedArchive
    $digest = (Get-FileHash -LiteralPath $stagedArchive -Algorithm SHA256).Hash.ToLowerInvariant()
    ($digest + '  ' + [IO.Path]::GetFileName($archive)) | Set-Content -LiteralPath $stagedChecksum -Encoding ascii
    Move-Item -LiteralPath $stagedArchive -Destination $archive -Force
    Move-Item -LiteralPath $stagedChecksum -Destination ($archive + '.sha256') -Force
    if ($ReleaseGate -or $UpdaterSmoke) {
        $updaterSmokeRoot = Join-Path $desktopRoot ('build/reports/updater-smoke-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $updaterSmokeRoot -Force | Out-Null
        $updaterSmokeArguments = @('-p', 'desktop', '--console=plain', 'updaterSmoke',
            "-PupdateTestPackage=$archive", "-PupdateTestReport=$updaterSmokeRoot")
        if ($PreviousUpdateTestPackage) { $updaterSmokeArguments += "-PupdatePreviousPackage=$PreviousUpdateTestPackage" }
        Push-Location $repoRoot
        try {
            & (Join-Path $repoRoot 'gradlew.bat') @updaterSmokeArguments 2>&1 |
                Tee-Object -FilePath (Join-Path $updaterSmokeRoot 'updater-smoke-gradle.log')
            if ($LASTEXITCODE -ne 0) { throw "Packaged updater checks failed ($LASTEXITCODE)." }
        } finally { Pop-Location }
        $updaterReportPath = Join-Path $updaterSmokeRoot 'updater-smoke.json'
        if (-not (Test-Path -LiteralPath $updaterReportPath -PathType Leaf)) { throw 'Packaged updater produced no test report.' }
        $updaterReport = Get-Content -LiteralPath $updaterReportPath -Raw | ConvertFrom-Json
        if ($updaterReport.passed -isnot [bool] -or -not $updaterReport.passed) { throw 'Packaged updater checks did not pass.' }
        if ($updaterReport.windowsVersion -cne $windowsVersion -or $updaterReport.portableZipSha256 -cne $digest) {
            throw 'Packaged updater evidence differs from the exact Windows version or ZIP SHA-256.'
        }
    }
    if ($ReleaseGate) {
        $verifiedSourceSha = & git -C $repoRoot rev-parse HEAD
        if ($LASTEXITCODE -ne 0 -or $verifiedSourceSha -cne $releaseSourceSha) {
            throw 'Windows source commit changed during release verification.'
        }
        $sourceChanges = & git -C $repoRoot status --porcelain --untracked-files=all
        if ($LASTEXITCODE -ne 0 -or $sourceChanges) {
            throw 'Windows sources changed during release verification.'
        }
        $releaseGateReport = [ordered]@{
            passed = $true
            windowsSourceCommit = $releaseSourceSha
            kotlinUnitTests = 'passed'
            pythonSourceContractTests = 'passed'
            guestNetworkBackendSmoke = 'passed'
            packagedNativePlayerSmoke = 'passed'
            packagedUpdaterSmoke = 'passed'
            packagedNativeDownloadMuxSmoke = 'passed'
            windowsVersion = $windowsVersion
            portableZipSha256 = $digest
            nativeReport = $nativeReportPath
            updaterReport = $updaterReportPath
            nativeMuxReport = $muxReportPath
            verifiedAtUtc = [DateTime]::UtcNow.ToString('o')
        }
        $releaseGateReport | ConvertTo-Json | Set-Content -LiteralPath $gatePath -Encoding utf8
    }
    Write-Host "Portable Windows package: $archive"
    Write-Host "SHA-256 checksum: $archive.sha256"
    Write-Host 'This package contains the Windows feature subset described in desktop/README.md.'
} finally {
    $env:JAVA_HOME = $originalJavaHome
    $env:PATH = $originalPath
}
