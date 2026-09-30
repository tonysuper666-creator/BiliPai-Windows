[CmdletBinding()]
param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$desktopRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$nativeRoot = Join-Path $desktopRoot 'native/windows-x64'
$cacheRoot = Join-Path $desktopRoot 'build/downloads'
$noticeRoot = Join-Path $desktopRoot 'third-party/ffmpeg'
$catalogPath = Join-Path $noticeRoot 'SOURCES.json'
$catalog = Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json
$archiveName = 'ffmpeg-n8.1.2-50-g1a748fe2cd-win64-lgpl-8.1.zip'
$archiveHash = 'f6274bbd9c247f9e90c1bbed066b03ed4a3907cece2fb91be6dd352393936365'
$binaryHashes = [ordered]@{
    'ffmpeg.exe' = '9c60da6c0b083110d59084ea39f60ae149aa3e031c3b4bb4f573fafa1c1e7cea'
    'ffprobe.exe' = '67176fa62f89f94c3bcd379fd05677a25651569a2eb8880ec2194e62c82be412'
}
if ($catalog.binary.archiveSha256 -ne $archiveHash) { throw 'FFmpeg inventory disagrees with the fixed runtime pin.' }

function Get-VerifiedArchive([string]$FileName, [string]$Url, [string]$Sha256) {
    if ([IO.Path]::GetFileName($FileName) -ne $FileName) { throw 'Archive name must be a filename.' }
    $archivePath = Join-Path $cacheRoot $FileName
    if (Test-Path -LiteralPath $archivePath) {
        if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -eq $Sha256) { return $archivePath }
        if ($Offline) { throw "Cached archive checksum mismatch: $FileName" }
    } elseif ($Offline) { throw "Offline FFmpeg archive is missing: $FileName" }
    $curlCommand = Get-Command curl.exe -ErrorAction SilentlyContinue
    $stagedPath = "$archivePath.downloading"
    Write-Host "Downloading fixed FFmpeg source/runtime: $FileName"
    if ($curlCommand) {
        & $curlCommand.Source --fail --location --retry 3 --silent --show-error --output $stagedPath $Url
        if ($LASTEXITCODE -ne 0) { throw "Cannot download FFmpeg archive: $FileName" }
    } else {
        $previousProgress = $ProgressPreference
        try { $ProgressPreference = 'SilentlyContinue'; Invoke-WebRequest -Uri $Url -OutFile $stagedPath -TimeoutSec 600 }
        finally { $ProgressPreference = $previousProgress }
    }
    if ((Get-FileHash -LiteralPath $stagedPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256) {
        throw "FFmpeg archive checksum mismatch; file is not used: $FileName"
    }
    Move-Item -LiteralPath $stagedPath -Destination $archivePath -Force
    return $archivePath
}

New-Item -ItemType Directory -Path $cacheRoot,$nativeRoot -Force | Out-Null
$archivePath = Get-VerifiedArchive $archiveName $catalog.binary.archiveUrl $archiveHash
$sourceArchive = Get-VerifiedArchive 'ffmpeg-source-1a748fe2.zip' $catalog.ffmpegSource.archiveUrl $catalog.ffmpegSource.archiveSha256
$recipeArchive = Get-VerifiedArchive 'ffmpeg-build-recipes-8267213.zip' $catalog.buildRecipes.archiveUrl $catalog.buildRecipes.archiveSha256
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    foreach ($name in $binaryHashes.Keys) {
        $entryPath = $archiveName.Substring(0, $archiveName.Length - 4) + '/bin/' + $name
        $entry = $archive.GetEntry($entryPath)
        if (-not $entry) { throw "Fixed FFmpeg archive lacks exact expected entry: $entryPath" }
        $outputPath = Join-Path $nativeRoot $name
        if ((Test-Path -LiteralPath $outputPath) -and
            (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToLowerInvariant() -eq $binaryHashes[$name]) { continue }
        $stagedPath = "$outputPath.verified"
        $inputStream = $entry.Open()
        $outputStream = [IO.File]::Open($stagedPath, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose(); $inputStream.Dispose() }
        if ((Get-FileHash -LiteralPath $stagedPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $binaryHashes[$name]) {
            throw "Fixed FFmpeg executable checksum mismatch: $name"
        }
        Move-Item -LiteralPath $stagedPath -Destination $outputPath -Force
    }
} finally { $archive.Dispose() }

$ffmpegPath = Join-Path $nativeRoot 'ffmpeg.exe'
$versionText = (& $ffmpegPath -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $versionText -notmatch 'ffmpeg version n8\.1\.2-50-g1a748fe2cd-20260831') {
    throw 'Fixed FFmpeg version validation failed.'
}
if ($versionText -match '--enable-(gpl|nonfree)(\s|$)' -or $versionText -notmatch '--enable-version3(\s|$)') {
    throw 'Fixed FFmpeg LGPL variant validation failed.'
}
$licenseRoot = Join-Path $nativeRoot 'licenses/ffmpeg'
$sourceRoot = Join-Path $nativeRoot 'sources/ffmpeg'
New-Item -ItemType Directory -Path $licenseRoot,$sourceRoot -Force | Out-Null
$allowedNoticeRoot = [IO.Path]::GetFullPath($noticeRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
foreach ($record in $catalog.licenseFiles) {
    $licensePath = [IO.Path]::GetFullPath((Join-Path $noticeRoot $record.path))
    if (-not $licensePath.StartsWith($allowedNoticeRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'FFmpeg license path escaped the inventory directory.' }
    if ((Get-FileHash -LiteralPath $licensePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $record.sha256) { throw "FFmpeg license checksum mismatch: $($record.path)" }
    Copy-Item -LiteralPath $licensePath -Destination $licenseRoot -Force
}
Copy-Item -LiteralPath $catalogPath,(Join-Path $noticeRoot 'README.md') -Destination $licenseRoot -Force
Copy-Item -LiteralPath $sourceArchive,$recipeArchive -Destination $sourceRoot -Force
$stamp = [ordered]@{
    schemaVersion = 1
    architecture = 'windows-x64'
    variant = $catalog.binary.variant
    sourceUrl = $catalog.binary.archiveUrl
    archiveSha256 = $archiveHash
    ffmpegSha256 = $binaryHashes['ffmpeg.exe']
    ffprobeSha256 = $binaryHashes['ffprobe.exe']
    ffmpegSourceCommit = $catalog.ffmpegSource.commit
    ffmpegSourceArchiveSha256 = $catalog.ffmpegSource.archiveSha256
    buildRecipesCommit = $catalog.buildRecipes.commit
    buildRecipesArchiveSha256 = $catalog.buildRecipes.archiveSha256
    version = $versionText
    licenseFiles = @($catalog.licenseFiles | Select-Object -ExpandProperty path)
    dependencyLicenseInventory = $catalog.dependencyLicenseInventory
}
$stamp | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $nativeRoot 'ffmpeg-provenance.json') -Encoding utf8
Write-Host 'Verified fixed FFmpeg/FFprobe, licenses, source archives and provenance in native/windows-x64.'
