[CmdletBinding()]
param([string]$SevenZipPath, [string]$RuntimeDescriptorPath, [string]$RuntimeArchivePath)
$ErrorActionPreference = 'Stop'
$desktopRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$nativeRoot = Join-Path $desktopRoot 'native/windows-x64'
if ($RuntimeArchivePath -and -not $RuntimeDescriptorPath) { throw 'A patched runtime archive requires its descriptor.' }
if ($RuntimeDescriptorPath) {
    . (Join-Path $PSScriptRoot 'native/mpv-runtime-descriptor.ps1')
    Install-DescriptorMpvRuntime -DesktopRoot $desktopRoot -NativeRoot $nativeRoot -DescriptorPath $RuntimeDescriptorPath -ArchivePath $RuntimeArchivePath
    return
}
$downloadUrls = @(
    'https://github.com/tonysuper666-creator/BiliPai-Windows/releases/download/runtime-mpv-20260903/mpv-dev-20260903-x64.7z',
    'https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20260903/mpv-dev-x86_64-20260903-git-69e63f425a.7z'
)
$archiveSha256 = 'fac135c68a35b7639e39d72c0c365104edbaebdea39a0dfdd8c36e8c8e80faef'
$provenanceFile = Join-Path $nativeRoot 'provenance.json'
$nativeDll = Join-Path $nativeRoot 'libmpv-2.dll'
function Install-NativeNotices {
    $noticeRoot = Join-Path $desktopRoot 'third-party/libmpv'
    $catalogPath = Join-Path $noticeRoot 'SOURCES.json'
    $catalog = Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json
    if (@($catalog.licenseFiles).Count -eq 0) { throw 'Native player license inventory is empty.' }
    $licenseRoot = Join-Path $nativeRoot 'licenses'
    New-Item -ItemType Directory -Path $licenseRoot -Force | Out-Null
    foreach ($record in $catalog.licenseFiles) {
        $source = [IO.Path]::GetFullPath((Join-Path $noticeRoot $record.path))
        $allowedRoot = [IO.Path]::GetFullPath($noticeRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if (-not $source.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Native license path escaped its inventory directory.' }
        if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -ne $record.sha256) { throw "Native license checksum failed: $($record.path)" }
        Copy-Item -LiteralPath $source -Destination $licenseRoot -Force
    }
    Copy-Item -LiteralPath $catalogPath -Destination $licenseRoot -Force
    Copy-Item -LiteralPath (Join-Path $noticeRoot 'NOTICES.md') -Destination $licenseRoot -Force
}
if ((Test-Path -LiteralPath $provenanceFile) -and (Test-Path -LiteralPath $nativeDll)) {
    $stamp = Get-Content -LiteralPath $provenanceFile -Raw | ConvertFrom-Json
    if ($stamp.archiveSha256 -eq $archiveSha256 -and
        (Get-FileHash -LiteralPath $nativeDll -Algorithm SHA256).Hash.ToLowerInvariant() -eq $stamp.dllSha256) {
        Install-NativeNotices
        $stamp.licenseFiles = @(Get-ChildItem -LiteralPath (Join-Path $nativeRoot 'licenses') -File | Select-Object -ExpandProperty Name)
        $stamp | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $provenanceFile -Encoding utf8
        Write-Host 'Verified cached libmpv Windows x64 runtime.'
        return
    }
}
if (-not $SevenZipPath) {
    $command = Get-Command 7z.exe -ErrorAction SilentlyContinue
    if ($command) { $SevenZipPath = $command.Source }
}
if (-not $SevenZipPath) {
    $candidates = @(
        (Join-Path $env:ProgramFiles '7-Zip/7z.exe'),
        ([IO.Path]::GetFullPath((Join-Path $desktopRoot '../../toolchain/7z/7z.exe'))),
        ([IO.Path]::GetFullPath((Join-Path $desktopRoot '../../toolchain/7z/7za.exe')))
    )
    $SevenZipPath = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
$tarExtractor = Get-Command tar.exe -ErrorAction SilentlyContinue
if ($SevenZipPath -and -not (Test-Path -LiteralPath $SevenZipPath)) {
    throw 'The supplied 7-Zip executable does not exist.'
}
if (-not $SevenZipPath -and -not $tarExtractor) {
    throw 'Windows tar or a 7-Zip extractor is required once. Pass -SevenZipPath <7z.exe> if Windows tar cannot extract 7z; no system software is installed by this script.'
}
$cacheRoot = Join-Path $desktopRoot 'build/downloads'
New-Item -ItemType Directory -Path $cacheRoot -Force | Out-Null
$archive = Join-Path $cacheRoot 'mpv-dev-20260903-x64.7z'
if (-not (Test-Path -LiteralPath $archive) -or
    (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $archiveSha256) {
    Write-Host 'Downloading pinned Windows x64 libmpv runtime.'
    $downloaded = $false
    foreach ($downloadUrl in $downloadUrls) {
        try {
            Invoke-WebRequest -Uri $downloadUrl -OutFile $archive -TimeoutSec 180
            $downloaded = $true
            break
        } catch { Write-Host 'Pinned runtime mirror unavailable; checking its original source.' }
    }
    if (-not $downloaded) { throw 'No pinned native runtime source was available.' }
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $archiveSha256) {
    throw 'libmpv archive checksum mismatch. Download is not used.'
}
$extractRoot = Join-Path $cacheRoot ('mpv-extract-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $extractRoot | Out-Null
if ($SevenZipPath) {
    & $SevenZipPath x $archive "-o$extractRoot" -y | Out-Null
} else {
    & $tarExtractor.Source -xf $archive -C $extractRoot
}
if ($LASTEXITCODE -ne 0) { throw 'Cannot extract the verified libmpv archive.' }
$dll = Get-ChildItem -LiteralPath $extractRoot -Filter 'libmpv-2.dll' -File -Recurse | Select-Object -First 1
if (-not $dll) { throw 'The verified archive contains no libmpv-2.dll.' }
New-Item -ItemType Directory -Path $nativeRoot -Force | Out-Null
Copy-Item -LiteralPath $dll.FullName -Destination $nativeDll -Force
$licenseRoot = Join-Path $nativeRoot 'licenses'
New-Item -ItemType Directory -Path $licenseRoot -Force | Out-Null
$licenseFiles = Get-ChildItem -LiteralPath $extractRoot -File -Recurse |
    Where-Object { $_.Name -match '(?i)^(copying|license|copyright)' }
foreach ($license in $licenseFiles) {
    $relative = [IO.Path]::GetRelativePath($extractRoot, $license.FullName)
    $destination = Join-Path $licenseRoot $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath $license.FullName -Destination $destination -Force
}
Install-NativeNotices
$stamp = [ordered]@{
    sourceUrl = $downloadUrls[-1]
    mirrors = $downloadUrls
    sourceProject = 'https://github.com/shinchiro/mpv-winbuild-cmake'
    archiveSha256 = $archiveSha256
    dllSha256 = (Get-FileHash -LiteralPath $nativeDll -Algorithm SHA256).Hash.ToLowerInvariant()
    architecture = 'windows-x64'
    licenseFiles = @(Get-ChildItem -LiteralPath $licenseRoot -File | Select-Object -ExpandProperty Name)
    upstreamSource = 'https://github.com/mpv-player/mpv'
}
$stamp | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $provenanceFile -Encoding utf8
Write-Host "Verified libmpv staged: $nativeDll"
