[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$SevenZipPath,
    [switch]$Installer,
    [switch]$SkipTests,
    [switch]$ReleaseGate,
    [switch]$NativeSmoke,
    [switch]$UpdaterSmoke,
    [string]$PreviousUpdateTestPackage
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$desktopRoot = Join-Path $repoRoot 'desktop'
if ($ReleaseGate -and $SkipTests) { throw 'ReleaseGate cannot skip unit tests.' }
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
    # Evidence from an earlier successful build must never validate a failed rerun.
    $gatePath = Join-Path $desktopRoot 'build/release-gate.json'
    if (Test-Path -LiteralPath $gatePath) { Remove-Item -LiteralPath $gatePath -Force }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + $originalPath
    & (Join-Path $JavaHome 'bin/java.exe') -version
    if ($LASTEXITCODE -ne 0) { throw 'The selected JDK could not start.' }

    $fetchArguments = @{}
    if ($SevenZipPath) { $fetchArguments.SevenZipPath = $SevenZipPath }
    & (Join-Path $PSScriptRoot 'fetch-mpv.ps1') @fetchArguments

    $nativeSource = Join-Path $desktopRoot 'native/windows-x64'
    $nativeResources = Join-Path $desktopRoot 'resources/common/native/windows-x64'
    New-Item -ItemType Directory -Path $nativeResources -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $nativeSource 'libmpv-2.dll') -Destination $nativeResources -Force
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
    } finally { Pop-Location }

    $appRoot = Join-Path $desktopRoot 'build/compose/binaries/main/app'
    $executable = Get-ChildItem -LiteralPath $appRoot -Filter 'BiliPai Windows.exe' -File -Recurse | Select-Object -First 1
    if (-not $executable) { throw 'createDistributable did not produce a native Windows launcher.' }
    if ($ReleaseGate -or $NativeSmoke) {
        $smokePrefix = if ($ReleaseGate) { 'release-gate-' } else { 'native-smoke-' }
        $smokeRoot = Join-Path $desktopRoot ('build/reports/' + $smokePrefix + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $smokeRoot -Force | Out-Null
        $nativeSmokeArgument = if ($env:CI -eq 'true') { '--player-self-test-ci' } else { '--player-self-test' }
        $nativeProcess = Start-Process -FilePath $executable.FullName -ArgumentList @(
            $nativeSmokeArgument, ('"' + $smokeRoot + '"')
        ) -WorkingDirectory $executable.DirectoryName -WindowStyle Hidden -Wait -PassThru
        if ($nativeProcess.ExitCode -ne 0) { throw "Packaged native playback test failed ($($nativeProcess.ExitCode))." }
        $nativeReportPath = Join-Path $smokeRoot 'native-player-smoke.json'
        if (-not (Test-Path -LiteralPath $nativeReportPath)) { throw 'Packaged player produced no native test report.' }
        $nativeReport = Get-Content -LiteralPath $nativeReportPath -Raw | ConvertFrom-Json
        if ($nativeReport.passed -ne $true) { throw 'Packaged native playback checks did not pass.' }
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
        $releaseGateReport = [ordered]@{
            passed = $true
            kotlinUnitTests = 'passed'
            guestNetworkBackendSmoke = 'passed'
            packagedNativePlayerSmoke = 'passed'
            packagedUpdaterSmoke = 'passed'
            windowsVersion = $windowsVersion
            portableZipSha256 = $digest
            nativeReport = $nativeReportPath
            updaterReport = $updaterReportPath
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
