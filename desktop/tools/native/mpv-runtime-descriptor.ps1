# Internal branch of the existing fetch-mpv.ps1 entry. No standalone fetch authority.
function Install-DescriptorMpvRuntime {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$DesktopRoot,
        [Parameter(Mandatory)][string]$NativeRoot,
        [Parameter(Mandatory)][string]$DescriptorPath,
        [string]$ArchivePath
    )
    $ErrorActionPreference = 'Stop'
    $descriptorFile = [IO.Path]::GetFullPath($DescriptorPath)
    if (-not (Test-Path -LiteralPath $descriptorFile -PathType Leaf)) { throw 'Requested patched runtime descriptor is missing.' }
    $descriptorSha = (Get-FileHash -LiteralPath $descriptorFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $runtime = Get-Content -LiteralPath $descriptorFile -Raw | ConvertFrom-Json
    $expected = [ordered]@{
        variant = 'bilipai-nvidia-native-v1'
        architecture = 'windows-x64'
        sourceCommit = '69e63f425a531f814431fba12750bdb3721357f2'
        nativePatchSha256 = 'e3599ec5fe4326a6713093e9834514f7c2b001b41f30c03fc26fc763b3345d30'
        originalNativeSourceSha256 = '9514d40109894e0bee4e4a2aa343a4e4e4d2368aee3729959180bf5f92d486c3'
        patchedNativeSourceSha256 = '1669c96fc95cfd7276a3149aa2d76058d29b2cc2dd848c77b949d434831c6d2f'
        recipeCommit = 'cd1edc11dc6887a50f705717619d879f5a93a488'
        recipeArchiveSha256 = '8b92a254771496b0dcc23017c2734bfa7545441d3e6a37958b063d6e7814a657'
        containerImage = 'ghcr.io/shinchiro/archlinux@sha256:6156ca503061914e1e73c3efa7276d14f5d45c78b3b8534c46e60294500beb66'
    }
    if ($runtime.schema -ne 1) { throw 'Unsupported patched runtime descriptor schema.' }
    foreach ($key in $expected.Keys) {
        if ($runtime.$key -cne $expected[$key]) { throw "Patched runtime source identity mismatch: $key" }
    }
    foreach ($digest in @($runtime.artifact.archiveSha256, $runtime.artifact.dllSha256, $runtime.buildReceiptSha256, $runtime.sourceBundle.sha256)) {
        if ($digest -cnotmatch '^[0-9a-f]{64}$') { throw 'Patched runtime descriptor needs real archive/DLL/build/source SHA256 values.' }
    }
    if ($runtime.artifact.archiveSha256 -eq 'fac135c68a35b7639e39d72c0c365104edbaebdea39a0dfdd8c36e8c8e80faef' -or
        $runtime.artifact.dllSha256 -eq '673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4') {
        throw 'The original unpatched native runtime cannot satisfy a patched descriptor.'
    }
    if ($runtime.artifact.fileName -cne 'bilipai-nvidia-native-v1-x64.zip' -or
        $runtime.sourceBundle.fileName -cne 'bilipai-nvidia-native-v1-source-materials.tar.gz') { throw 'Unexpected patched runtime asset name.' }
    $ownedReleasePrefix = 'https://github.com/tonysuper666-creator/BiliPai-Windows/releases/download/'
    $urls = @($runtime.artifact.downloadUrls)
    foreach ($url in $urls) {
        $uri = $null
        if (-not [Uri]::TryCreate([string]$url, [UriKind]::Absolute, [ref]$uri) -or
            $uri.Scheme -cne 'https' -or $uri.Host -cne 'github.com' -or $uri.Port -ne 443 -or
            $uri.UserInfo -or $uri.Query -or $uri.Fragment -or
            -not ([string]$url).StartsWith($ownedReleasePrefix, [StringComparison]::Ordinal) -or
            ([Uri]::UnescapeDataString($uri.AbsolutePath)) -cnotmatch '^/tonysuper666-creator/BiliPai-Windows/releases/download/[A-Za-z0-9][A-Za-z0-9._-]*/bilipai-nvidia-native-v1-x64\.zip$') {
            throw 'Patched runtime URLs must select the named asset from the owned release repository.'
        }
    }
    # Corresponding source is a separate artifact, not copied into the portable app.
    $sourceBundle = Join-Path (Split-Path -Parent $descriptorFile) $runtime.sourceBundle.fileName
    if (Test-Path -LiteralPath $sourceBundle -PathType Leaf) {
        if ((Get-FileHash -LiteralPath $sourceBundle -Algorithm SHA256).Hash.ToLowerInvariant() -cne $runtime.sourceBundle.sha256) { throw 'Patched runtime source bundle checksum mismatch.' }
    } else {
        $sourceUri = $null
        $sourceUrl = [string]$runtime.sourceBundle.downloadUrl
        if (-not [Uri]::TryCreate($sourceUrl, [UriKind]::Absolute, [ref]$sourceUri) -or
            $sourceUri.Scheme -cne 'https' -or $sourceUri.Host -cne 'github.com' -or $sourceUri.Port -ne 443 -or
            $sourceUri.UserInfo -or $sourceUri.Query -or $sourceUri.Fragment -or
            -not $sourceUrl.StartsWith($ownedReleasePrefix, [StringComparison]::Ordinal) -or
            ([Uri]::UnescapeDataString($sourceUri.AbsolutePath)) -cnotmatch '^/tonysuper666-creator/BiliPai-Windows/releases/download/[A-Za-z0-9][A-Za-z0-9._-]*/bilipai-nvidia-native-v1-source-materials\.tar\.gz$') {
            throw 'Patched runtime needs its local source bundle or an explicit owned-release source URL.'
        }
        # The URL is publication metadata. This branch does not claim to download/verify that remote source bundle.
    }
    $rows = @($runtime.artifact.entries)
    if ($rows.Count -lt 5 -or $rows.Count -gt 64) { throw 'Invalid patched runtime ZIP inventory.' }
    $paths = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $totalBytes = 0L
    foreach ($row in $rows) {
        if ($row.path -cnotmatch '^(libmpv-2\.dll|licenses/[a-zA-Z0-9_./-]+)$' -or
            ([string]$row.path).Split('/') -contains '..' -or ([string]$row.path).Contains('//') -or
            -not $paths.Add([string]$row.path) -or $row.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
            [long]$row.bytes -le 0 -or [long]$row.bytes -gt 536870912) { throw 'Unsafe or ambiguous patched runtime ZIP inventory.' }
        $totalBytes += [long]$row.bytes
    }
    if ($totalBytes -gt 1073741824) { throw 'Patched runtime ZIP inventory is too large.' }
    foreach ($required in @('libmpv-2.dll', 'licenses/build-receipt.json', 'licenses/native-patch-receipt.json', 'licenses/SOURCES.json', 'licenses/NOTICES.md')) {
        if (-not $paths.Contains($required)) { throw "Patched runtime required entry is missing: $required" }
    }
    $dllRow = $rows | Where-Object { $_.path -ceq 'libmpv-2.dll' }
    $buildRow = $rows | Where-Object { $_.path -ceq 'licenses/build-receipt.json' }
    if ($dllRow.sha256 -cne $runtime.artifact.dllSha256 -or $buildRow.sha256 -cne $runtime.buildReceiptSha256) { throw 'Patched runtime descriptor entry digests disagree.' }
    $buildInputs = Get-Content -LiteralPath (Join-Path $DesktopRoot 'third-party/libmpv/build/fixed-inputs.json') -Raw | ConvertFrom-Json
    $provenanceFile = Join-Path $NativeRoot 'provenance.json'
    if (Test-Path -LiteralPath $provenanceFile -PathType Leaf) {
        $cached = Get-Content -LiteralPath $provenanceFile -Raw | ConvertFrom-Json
        $cacheMatches = $cached.schema -eq 1 -and $cached.architecture -ceq 'windows-x64' -and
            $cached.variant -ceq $runtime.variant -and $cached.runtimeDescriptorSha256 -ceq $descriptorSha -and
            $cached.archiveSha256 -ceq $runtime.artifact.archiveSha256 -and $cached.dllSha256 -ceq $runtime.artifact.dllSha256 -and
            $cached.buildReceiptSha256 -ceq $runtime.buildReceiptSha256 -and $cached.nativePatchSha256 -ceq $runtime.nativePatchSha256 -and
            $cached.sourceBundleSha256 -ceq $runtime.sourceBundle.sha256 -and $cached.ffmpegCommit -ceq $buildInputs.ffmpegCommit
        foreach ($key in $expected.Keys) {
            if ($cached.$key -cne $expected[$key]) { $cacheMatches = $false }
        }
        if (-not $cached.patchedRecipeSha256) { $cacheMatches = $false }
        else {
            foreach ($property in $buildInputs.expectedPatchedRecipeSha256.PSObject.Properties) {
                $cachedProperty = $cached.patchedRecipeSha256.PSObject.Properties[$property.Name]
                if (-not $cachedProperty -or $cachedProperty.Value -cne $property.Value) { $cacheMatches = $false }
            }
        }
        if ($cacheMatches) {
            foreach ($row in $rows) {
                $file = Join-Path $NativeRoot $row.path
                if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or
                    (Get-Item -LiteralPath $file).Length -ne [long]$row.bytes -or
                    (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256) { $cacheMatches = $false; break }
            }
            if ($cacheMatches) { Write-Host 'Verified cached patched native candidate; hardware effect is unverified.'; return }
        }
    }
    $cacheRoot = Join-Path $DesktopRoot 'build/downloads'
    New-Item -ItemType Directory -Path $cacheRoot -Force | Out-Null
    $archive = Join-Path $cacheRoot ('mpv-' + $runtime.artifact.archiveSha256 + '.zip')
    if ($ArchivePath) {
        $localArchive = [IO.Path]::GetFullPath($ArchivePath)
        if (-not (Test-Path -LiteralPath $localArchive -PathType Leaf)) { throw 'The explicitly selected patched archive is missing.' }
    } else {
        $localArchive = Join-Path (Split-Path -Parent $descriptorFile) $runtime.artifact.fileName
    }
    if (Test-Path -LiteralPath $localArchive -PathType Leaf) {
        if ((Get-FileHash -LiteralPath $localArchive -Algorithm SHA256).Hash.ToLowerInvariant() -cne $runtime.artifact.archiveSha256) { throw 'Selected local patched archive checksum mismatch.' }
        if ([IO.Path]::GetFullPath($localArchive) -cne [IO.Path]::GetFullPath($archive)) { Copy-Item -LiteralPath $localArchive -Destination $archive -Force }
    } elseif (-not (Test-Path -LiteralPath $archive -PathType Leaf) -or
        (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -cne $runtime.artifact.archiveSha256) {
        if ($urls.Count -eq 0) { throw 'Patched runtime has no local archive or published owned artifact URL.' }
        $downloaded = $false
        foreach ($url in $urls) {
            try { Invoke-WebRequest -Uri $url -OutFile $archive -TimeoutSec 180; $downloaded = $true; break }
            catch { Write-Host 'Selected patched artifact mirror unavailable.' }
        }
        if (-not $downloaded) { throw 'Selected patched artifact is unavailable; original runtime is not substituted.' }
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -cne $runtime.artifact.archiveSha256) { throw 'Patched runtime archive checksum mismatch.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $extractRoot = Join-Path $cacheRoot ('mpv-variant-extract-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $extractRoot | Out-Null
    try {
        $zip = [IO.Compression.ZipFile]::OpenRead($archive)
        try {
            if ($zip.Entries.Count -ne $rows.Count) { throw 'Patched runtime ZIP differs from its full entry inventory.' }
            foreach ($row in $rows) {
                $matches = @($zip.Entries | Where-Object { $_.FullName -ceq $row.path })
                if ($matches.Count -ne 1 -or $matches[0].Length -ne [long]$row.bytes) { throw 'Patched ZIP entry size/name mismatch.' }
                $destination = Join-Path $extractRoot $row.path
                New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
                $inputStream = $matches[0].Open()
                try {
                    $outputStream = [IO.File]::Open($destination, [IO.FileMode]::CreateNew)
                    try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
                } finally { $inputStream.Dispose() }
                if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.sha256) { throw 'Patched ZIP entry checksum mismatch.' }
            }
        } finally { $zip.Dispose() }
        $receipt = Get-Content -LiteralPath (Join-Path $extractRoot 'licenses/build-receipt.json') -Raw | ConvertFrom-Json
        foreach ($key in @('variant', 'sourceCommit', 'nativePatchSha256', 'originalNativeSourceSha256', 'patchedNativeSourceSha256', 'recipeCommit', 'recipeArchiveSha256', 'containerImage')) {
            if ($receipt.$key -cne $expected[$key]) { throw "Actual build receipt identity mismatch: $key" }
        }
        if ($receipt.dllSha256 -cne $runtime.artifact.dllSha256 -or $receipt.sourceBundleSha256 -cne $runtime.sourceBundle.sha256) { throw 'Actual build receipt differs from artifact/source digests.' }
        if ($receipt.ffmpegCommit -cne $buildInputs.ffmpegCommit) { throw 'Actual build receipt has a different fixed FFmpeg source.' }
        foreach ($property in $buildInputs.expectedPatchedRecipeSha256.PSObject.Properties) {
            $actualProperty = $receipt.patchedRecipeSha256.PSObject.Properties[$property.Name]
            if (-not $actualProperty -or $actualProperty.Value -cne $property.Value) { throw 'Actual build receipt has a different selected recipe.' }
        }
        $nativeReceipt = Get-Content -LiteralPath (Join-Path $extractRoot 'licenses/native-patch-receipt.json') -Raw | ConvertFrom-Json
        if ($nativeReceipt.patchId -cne $runtime.variant -or $nativeReceipt.sourceCommit -cne $runtime.sourceCommit -or
            $nativeReceipt.patchSha256 -cne $runtime.nativePatchSha256 -or $nativeReceipt.patchedSourceSha256 -cne $runtime.patchedNativeSourceSha256) { throw 'Native source receipt mismatch.' }
        $baseCatalog = Get-Content -LiteralPath (Join-Path $DesktopRoot 'third-party/libmpv/SOURCES.json') -Raw | ConvertFrom-Json
        foreach ($license in $baseCatalog.licenseFiles) {
            $entry = 'licenses/' + $license.path
            $matching = @($rows | Where-Object { $_.path -ceq $entry -and $_.sha256 -ceq $license.sha256 })
            if ($matching.Count -ne 1) { throw 'Patched candidate omitted/changed an existing native license record.' }
        }
        # Validation completes before any existing staged DLL is replaced.
        New-Item -ItemType Directory -Path $NativeRoot -Force | Out-Null
        foreach ($row in $rows) {
            $destination = Join-Path $NativeRoot $row.path
            New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
            Copy-Item -LiteralPath (Join-Path $extractRoot $row.path) -Destination $destination -Force
        }
        $stamp = [ordered]@{
            schema = 1
            variant = $runtime.variant
            dllSha256 = $runtime.artifact.dllSha256
            sourceCommit = $runtime.sourceCommit
            nativePatchSha256 = $runtime.nativePatchSha256
            originalNativeSourceSha256 = $runtime.originalNativeSourceSha256
            patchedNativeSourceSha256 = $runtime.patchedNativeSourceSha256
            archiveSha256 = $runtime.artifact.archiveSha256
            runtimeDescriptorSha256 = $descriptorSha
            buildReceiptSha256 = $runtime.buildReceiptSha256
            sourceBundleSha256 = $runtime.sourceBundle.sha256
            sourceBundleUrl = $runtime.sourceBundle.downloadUrl
            recipeCommit = $runtime.recipeCommit
            ffmpegCommit = $receipt.ffmpegCommit
            recipeArchiveSha256 = $runtime.recipeArchiveSha256
            patchedRecipeSha256 = $receipt.patchedRecipeSha256
            containerImage = $runtime.containerImage
            actualDependencySources = $receipt.actualDependencySources
            actualTools = $receipt.actualTools
            sourceUrls = $urls
            architecture = 'windows-x64'
            licenseFiles = @($rows | Where-Object { $_.path.StartsWith('licenses/') } | ForEach-Object { $_.path.Substring(9) })
            reproducible = $false
            nativeResolutionPpeVerified = $false
        }
        $stamp | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $provenanceFile -Encoding utf8
        Write-Host 'Verified patched libmpv candidate staged; NVIDIA native-resolution effect remains unverified.'
    } finally {
        # Only this helper's checked, unique extraction directory is removed.
        $resolvedExtract = [IO.Path]::GetFullPath($extractRoot)
        $allowedCache = [IO.Path]::GetFullPath($cacheRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if (-not $resolvedExtract.StartsWith($allowedCache, [StringComparison]::OrdinalIgnoreCase)) { throw 'Native extraction cleanup escaped its owned cache directory.' }
        if (Test-Path -LiteralPath $resolvedExtract) { Remove-Item -LiteralPath $resolvedExtract -Recurse -Force }
    }
}
