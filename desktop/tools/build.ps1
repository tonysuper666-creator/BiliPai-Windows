[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$SevenZipPath,
    [switch]$Installer,
    [switch]$SkipTests,
    [switch]$FixedJavaToolchain,
    [switch]$ReleaseGate,
    [switch]$NativeSmoke,
    [switch]$NativeMuxSmoke,
    [switch]$UpdaterSmoke,
    [string]$PreviousUpdateTestPackage,
    [string]$MpvRuntimeDescriptorPath,
    [string]$MpvRuntimeArchivePath,
    [string]$GradleUserHomePath
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
# Keep the winbox disk guard self-contained and ahead of all build writes.
# A child scope leaves ordinary builds and the existing build variables unchanged.
& {
    if (-not $env:USERPROFILE) { return }
    $verifyRoot = [IO.Path]::GetFullPath((Join-Path $env:USERPROFILE 'bp-mac-verify'))
    $separator = [IO.Path]::DirectorySeparatorChar
    $comparison = [StringComparison]::OrdinalIgnoreCase
    if (-not $PSCommandPath.StartsWith($verifyRoot + $separator, $comparison)) { return }

    $minFreeGB = 20.0
    if ($env:BP_MIN_FREE_GB) {
        $requestedGB = 0.0
        if (-not [double]::TryParse($env:BP_MIN_FREE_GB, [Globalization.NumberStyles]::Float,
                [Globalization.CultureInfo]::InvariantCulture, [ref]$requestedGB) -or
            [double]::IsNaN($requestedGB) -or [double]::IsInfinity($requestedGB) -or $requestedGB -le 0) {
            throw 'BP_MIN_FREE_GB must be a positive finite number (minimum floor: 20 GB).'
        }
        $minFreeGB = [Math]::Max($minFreeGB, $requestedGB)
    }
    $freeBytes = (Get-PSDrive -Name C -ErrorAction Stop).Free
    if ($null -eq $freeBytes) { throw 'Cannot read C: free space; refusing verification build.' }
    if ($freeBytes -lt $minFreeGB * 1GB) {
        throw ('C: has {0:N2} GB free, below the {1} GB floor; refusing verification build. Free space before retrying. BP_ALLOW_FULL_CLONE does not bypass this check.' -f ($freeBytes / 1GB), $minFreeGB)
    }

    $sharedRoot = [IO.Path]::GetFullPath((Join-Path $verifyRoot 'src/BiliPai-Windows'))
    if ($repoRoot.Equals($sharedRoot, $comparison)) { return }
    $gitPath = Join-Path $repoRoot '.git'
    if (Test-Path -LiteralPath $gitPath -PathType Container) {
        if ($env:BP_ALLOW_FULL_CLONE -eq '1') {
            Write-Warning 'BP_ALLOW_FULL_CLONE=1: allowing a full clone in bp-mac-verify; the C: free-space floor still applies.'
            return
        }
        $reason = 'Full clones are forbidden in bp-mac-verify.'
    } else {
        if (Test-Path -LiteralPath $gitPath -PathType Leaf) {
            $gitFile = (Get-Content -LiteralPath $gitPath -Raw).Trim()
            if ($gitFile -match '\Agitdir:\s*(.+?)\s*\z') {
                $gitDir = $matches[1]
                if (-not [IO.Path]::IsPathRooted($gitDir)) { $gitDir = Join-Path $repoRoot $gitDir }
                $gitDir = [IO.Path]::GetFullPath($gitDir).TrimEnd($separator)
                $worktreesRoot = [IO.Path]::GetFullPath((Join-Path $sharedRoot '.git/worktrees'))
                if ([IO.Path]::GetDirectoryName($gitDir).Equals($worktreesRoot, $comparison) -and
                    (Test-Path -LiteralPath $gitDir -PathType Container)) { return }
            }
        }
        $reason = 'Verification sources must be a worktree of src/BiliPai-Windows.'
    }

    $card = $repoRoot.Substring($verifyRoot.Length + 1).Split($separator)[0]
    if ($card -notmatch '^ton\d+[a-z0-9-]*$') { $card = 'tonNNNN' }
    $sha = '<sha>'
    try {
        $head = & git -C $repoRoot rev-parse HEAD 2>$null
        if ($LASTEXITCODE -eq 0 -and $head -match '^[0-9a-f]{40}$') { $sha = $head }
    } catch { } # The recovery instructions still work when Git is unavailable.
    $quotedRoot = $verifyRoot.Replace("'", "''")
    throw "$reason Preserve/move the existing card source first (New-BpCardSource refuses an existing src). Then run:`nSet-Location -LiteralPath '$quotedRoot'`n.\tools\New-BpCardSource.ps1 -Card $card -Sha '$sha'`n.\tools\build-card.ps1 -Card $card -Sha '$sha'"
}
$desktopRoot = Join-Path $repoRoot 'desktop'
# Even parameter/JDK failures must invalidate evidence from an earlier build.
$gatePath = Join-Path $desktopRoot 'build/release-gate.json'
if (Test-Path -LiteralPath $gatePath) { Remove-Item -LiteralPath $gatePath -Force }
if ($ReleaseGate -and $SkipTests) { throw 'ReleaseGate cannot skip unit tests.' }
$workspaceReuseArguments = @()
if ($GradleUserHomePath) {
    if (-not $SkipTests -or -not $FixedJavaToolchain -or $Installer -or $ReleaseGate -or
        $NativeSmoke -or $NativeMuxSmoke -or $UpdaterSmoke -or $PreviousUpdateTestPackage) {
        throw 'Validation workspace reuse is limited to an unlaunched fixed-JDK candidate; terminal qualification and runtime verification remain separate.'
    }
    $GradleUserHomePath = [IO.Path]::GetFullPath($GradleUserHomePath)
    if (-not (Test-Path -LiteralPath $GradleUserHomePath -PathType Container)) {
        throw 'The preceding source validation Gradle home must already exist.'
    }
    $workspaceReuseArguments = @('--gradle-user-home', $GradleUserHomePath,
        '--no-build-cache', '--no-daemon', '--max-workers=1', '--no-parallel',
        '-Pkotlin.compiler.execution.strategy=in-process')
}
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
$toolchainArguments = @()
$fixedToolchain = $null
if ($FixedJavaToolchain) {
    . (Join-Path $PSScriptRoot 'fixed-java-toolchain.ps1')
    $fixedToolchain = Get-BiliPaiFixedJavaToolchain -JavaHome $JavaHome
    $toolchainArguments = @($fixedToolchain.gradleArguments)
}
$originalJavaHome, $originalPath = $env:JAVA_HOME, $env:PATH
try {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + $originalPath
    & (Join-Path $JavaHome 'bin/java.exe') -version
    if ($LASTEXITCODE -ne 0) { throw 'The selected JDK could not start.' }

    $fetchArguments = @{}
    if ($SevenZipPath) { $fetchArguments.SevenZipPath = $SevenZipPath }
    if ($MpvRuntimeDescriptorPath) { $fetchArguments.RuntimeDescriptorPath = $MpvRuntimeDescriptorPath }
    if ($MpvRuntimeArchivePath) { $fetchArguments.RuntimeArchivePath = $MpvRuntimeArchivePath }
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
    . (Join-Path $PSScriptRoot 'native/stage-mpv-companion-resources.ps1')
    $mpvProvenance = Get-Content -LiteralPath (Join-Path $nativeSource 'provenance.json') -Raw | ConvertFrom-Json
    Copy-VerifiedMpvCompanionResources -NativeRoot $nativeSource -ResourceRoot $nativeResources -Provenance $mpvProvenance

    Push-Location $repoRoot
    try {
        $runtimeEvidenceRoot = Join-Path $desktopRoot ('build/reports/candidate-runtime-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $runtimeEvidenceRoot -Force | Out-Null
        $runtimeModel = Join-Path $runtimeEvidenceRoot 'runtime-model.json'
        $gradleArguments = @('-p', 'desktop', '--console=plain')
        $gradleArguments += @('--init-script', (Join-Path $PSScriptRoot 'original-root-ui-harness.init.gradle'),
            "-PcandidateProductionRuntimeModel=$runtimeModel")
        $gradleArguments += $toolchainArguments
        $gradleArguments += $workspaceReuseArguments
        if (-not $SkipTests) { $gradleArguments += 'test' }
        if ($ReleaseGate) { $gradleArguments += 'backendSmoke' }
        $gradleArguments += 'createDistributable'
        $gradleArguments += 'writeCandidateProductionRuntimeModel'
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
    $pythonExecutable = if ($env:PYTHON_EXECUTABLE) { $env:PYTHON_EXECUTABLE } else { 'python' }
    $runtimeSourceSha = & git -C $repoRoot rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Candidate runtime source identity is unavailable.' }
    & $pythonExecutable (Join-Path $PSScriptRoot 'canonicalize-candidate-classpath.py') `
        --candidate $executable.DirectoryName --model $runtimeModel --source-sha $runtimeSourceSha `
        --output (Join-Path $runtimeEvidenceRoot 'classpath-binding.json')
    if ($LASTEXITCODE -ne 0) { throw 'Production candidate classpath binding failed.' }
    if ($Installer) {
        # A separate task invocation packages the corrected app image.
        Push-Location $repoRoot
        try {
            & (Join-Path $repoRoot 'gradlew.bat') @toolchainArguments -p desktop --console=plain packageMsi -x createDistributable
            if ($LASTEXITCODE -ne 0) { throw 'Windows MSI packaging failed.' }
        } finally { Pop-Location }
    }
    if ($ReleaseGate -or $NativeMuxSmoke) {
        $packagedNativeRoot = Join-Path $executable.DirectoryName 'app/resources/native/windows-x64'
        $muxSmokeRoot = Join-Path $desktopRoot ('build/reports/native-mux-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $muxSmokeRoot -Force | Out-Null
        $muxReportPath = Join-Path $muxSmokeRoot 'native-download-mux.json'
        $packagedFfmpeg = Join-Path $packagedNativeRoot 'ffmpeg.exe'
        $packagedFfprobe = Join-Path $packagedNativeRoot 'ffprobe.exe'
        Push-Location $repoRoot
        try {
            & (Join-Path $repoRoot 'gradlew.bat') @toolchainArguments -p desktop --console=plain nativeMuxSmoke `
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
    Copy-Item -LiteralPath $runtimeModel -Destination (Join-Path $outputRoot 'candidate-runtime-model.json') -Force
    Copy-Item -LiteralPath (Join-Path $runtimeEvidenceRoot 'classpath-binding.json') `
        -Destination (Join-Path $outputRoot 'candidate-classpath.json') -Force
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
        $updaterSmokeArguments += $toolchainArguments
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
    if ($FixedJavaToolchain) {
        $toolchainSourceSha = & git -C $repoRoot rev-parse HEAD
        if ($LASTEXITCODE -ne 0) { throw 'Candidate source identity is unavailable.' }
        $toolchainEvidenceSha = Write-BiliPaiCandidateToolchainEvidence -Toolchain $fixedToolchain `
            -SourceSha $toolchainSourceSha -WindowsVersion $windowsVersion -Archive $archive `
            -ApplicationRoot $executable.DirectoryName -Output (Join-Path $outputRoot 'candidate-toolchain.json')
        Write-Host "BILIPAI_CANDIDATE_TOOLCHAIN_SHA256 $toolchainEvidenceSha"
    }
    Write-Host "Portable Windows package: $archive"
    Write-Host "SHA-256 checksum: $archive.sha256"
    Write-Host 'This package contains the Windows feature subset described in desktop/README.md.'
} finally {
    $env:JAVA_HOME = $originalJavaHome
    $env:PATH = $originalPath
}
