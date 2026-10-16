# File-only identity and arguments for the publisher-verified fixed JDK.
# This module never executes java.exe, Gradle or a Windows client.
function Get-BiliPaiFixedJavaToolchain {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$JavaHome)
    $homePath = [IO.Path]::GetFullPath($JavaHome)
    $releasePath = Join-Path $homePath 'release'
    if (-not (Test-Path -LiteralPath (Join-Path $homePath 'bin/java.exe') -PathType Leaf) -or
        -not (Test-Path -LiteralPath $releasePath -PathType Leaf)) { throw 'The complete fixed JDK is missing.' }
    $release = [IO.File]::ReadAllText($releasePath)
    if ([regex]::Matches($release, '(?m)^JAVA_VERSION="21\.0\.12\.1"\r?$').Count -ne 1 -or
        [regex]::Matches($release, '(?m)^JAVA_VERSION=').Count -ne 1) { throw 'The JDK must be the fixed 21.0.12.1 publisher image.' }
    return [pscustomobject]@{
        javaHome = $homePath
        javaVersion = '21.0.12.1'
        releaseSha256 = (Get-FileHash -LiteralPath $releasePath -Algorithm SHA256).Hash.ToLowerInvariant()
        gradleArguments = @('-Porg.gradle.java.installations.auto-detect=false',
            '-Porg.gradle.java.installations.auto-download=false', ('-Porg.gradle.java.installations.paths=' + $homePath))
    }
}

function Write-BiliPaiCandidateToolchainEvidence {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Toolchain,
        [Parameter(Mandatory)][string]$SourceSha,
        [Parameter(Mandatory)][string]$WindowsVersion,
        [Parameter(Mandatory)][string]$Archive,
        [Parameter(Mandatory)][string]$ApplicationRoot,
        [Parameter(Mandatory)][string]$Output)
    if ($SourceSha -cnotmatch '^[0-9a-f]{40}$' -or $WindowsVersion -notmatch '^\d+\.\d+\.\d+\.\d+$') {
        throw 'Exact candidate source and Windows version are required.'
    }
    $fresh = Get-BiliPaiFixedJavaToolchain -JavaHome $Toolchain.javaHome
    if ($fresh.releaseSha256 -cne $Toolchain.releaseSha256) { throw 'Fixed JDK release changed during packaging.' }
    $runtimeRelease = Join-Path $ApplicationRoot 'runtime/release'
    if (-not (Test-Path -LiteralPath $runtimeRelease -PathType Leaf)) { throw 'Packaged runtime release is missing.' }
    $runtimeText = [IO.File]::ReadAllText($runtimeRelease)
    if ([regex]::Matches($runtimeText, '(?m)^JAVA_VERSION="21\.0\.12\.1"\r?$').Count -ne 1 -or
        [regex]::Matches($runtimeText, '(?m)^JAVA_VERSION=').Count -ne 1) { throw 'Packaged runtime version differs from the fixed compiler JDK.' }
    $archiveFile = Get-Item -LiteralPath $Archive -ErrorAction Stop
    if ($archiveFile.PSIsContainer -or $archiveFile.Length -le 0) { throw 'Candidate ZIP is absent or empty.' }
    $record = [ordered]@{
        schema = 1; sourceSha = $SourceSha; windowsVersion = $WindowsVersion
        javaVersion = $fresh.javaVersion; javaReleaseSha256 = $fresh.releaseSha256
        runtimeReleaseSha256 = (Get-FileHash -LiteralPath $runtimeRelease -Algorithm SHA256).Hash.ToLowerInvariant()
        candidateZip = [ordered]@{ name = $archiveFile.Name; bytes = $archiveFile.Length
            sha256 = (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant() }
        toolchainAutoDetection = $false; toolchainAutoDownload = $false
        completeRuntimeByteBindingVerified = $false
        applicationExecuted = $false; hardwareExecuted = $false; releaseQualified = $false
    }
    $raw = [Text.UTF8Encoding]::new($false).GetBytes(($record | ConvertTo-Json -Depth 5) + "`n")
    $stream = [IO.File]::Open([IO.Path]::GetFullPath($Output), [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $stream.Write($raw, 0, $raw.Length); $stream.Flush($true) } finally { $stream.Dispose() }
    return (Get-FileHash -LiteralPath $Output -Algorithm SHA256).Hash.ToLowerInvariant()
}
