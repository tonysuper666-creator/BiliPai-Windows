[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ProfilePath,
    [Parameter(Mandatory=$true)][string]$TrustedProfileSha256,
    [string]$RuntimeRoot,
    [string]$CoreModulePath,
    [string]$MpvModulePath,
    [switch]$RuntimeOnly
)
# BiliPai owns this verifier. Windows PowerShell 5.1+. No DLL is loaded or run.
# The caller must pin this script and the profile from an independent trust anchor,
# and retain its own component leases until the eventual native unload.
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$leases = New-Object 'System.Collections.Generic.List[System.IO.FileStream]'
$result = [ordered]@{
    schema = 1; status = 'REJECTED'; engineStatus = 'UNAVAILABLE'
    failureCode = $null; missingInputs = @()
    checked = [ordered]@{
        profileSha256 = $null; moduleBuildSha256 = $null; mpvDllSha256 = $null
        coreSourceSha256 = $null; headerSha256 = $null
        coreAbi = $null; coreAbiWire = $null; runtimeFiles = @()
    }
}
function Reject([string]$Code) { throw ('BV_CODE:' + $Code) }
function Require-Hash([object]$Value, [string]$Code) {
    if ($Value -isnot [string] -or $Value -notmatch '^[0-9a-fA-F]{64}$') { Reject $Code }
    return $Value.ToUpperInvariant()
}
function Local-FullPath([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z]:[\\/]') { Reject 'LOCAL_ABSOLUTE_PATH_REQUIRED' }
    try { return [IO.Path]::GetFullPath($Value) } catch { Reject 'INVALID_PATH' }
}
function Assert-NoReparse([string]$Full) {
    $cursor = $Full
    while (-not [string]::IsNullOrEmpty($cursor)) {
        if (-not (Test-Path -LiteralPath $cursor)) { Reject 'PATH_NOT_FOUND' }
        $item = Get-Item -LiteralPath $cursor -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Reject 'REPARSE_PATH_REJECTED' }
        $parent = [IO.Path]::GetDirectoryName($cursor.TrimEnd('\','/'))
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
}
function Relative-Path([string]$Base, [object]$Relative) {
    if ($Relative -isnot [string] -or [string]::IsNullOrEmpty($Relative) -or
        $Relative -match '\\|:|^[\/]|[\x00-\x1f]') { Reject 'INVALID_RELATIVE_PATH' }
    foreach ($part in $Relative.Split('/')) {
        if ($part -eq '.' -or $part -eq '..' -or $part -notmatch '^[A-Za-z0-9_.-]+$' -or
            $part.EndsWith('.')) { Reject 'INVALID_RELATIVE_PATH' }
    }
    $baseFull = Local-FullPath $Base
    $full = [IO.Path]::GetFullPath([IO.Path]::Combine($baseFull, $Relative.Replace('/','\')))
    $prefix = $baseFull.TrimEnd('\','/') + '\'
    if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { Reject 'PATH_ESCAPES_ROOT' }
    return $full
}
function Open-Locked([string]$Full) {
    Assert-NoReparse $Full
    if (-not (Test-Path -LiteralPath $Full -PathType Leaf)) { Reject 'FILE_REQUIRED' }
    try { $stream = [IO.File]::Open($Full, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read) }
    catch { Reject 'FILE_LEASE_FAILED' }
    $leases.Add($stream)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $digest = $sha.ComputeHash($stream) } finally { $sha.Dispose() }
    $stream.Position = 0
    return [pscustomobject]@{
        Stream = $stream; Sha256 = ([BitConverter]::ToString($digest).Replace('-',''))
        Bytes = $stream.Length
    }
}
function Assert-X64Pe([IO.FileStream]$Stream) {
    if ($Stream.Length -lt 256) { Reject 'MODULE_NOT_X64_PE' }
    $reader = New-Object IO.BinaryReader($Stream, [Text.Encoding]::UTF8, $true)
    try {
        $Stream.Position = 0
        if ($reader.ReadUInt16() -ne 0x5A4D) { Reject 'MODULE_NOT_X64_PE' }
        $Stream.Position = 0x3C
        $offset = $reader.ReadUInt32()
        if ($offset -lt 0x40 -or $offset -gt ($Stream.Length - 26)) { Reject 'MODULE_NOT_X64_PE' }
        $Stream.Position = $offset
        if ($reader.ReadUInt32() -ne 0x00004550 -or $reader.ReadUInt16() -ne 0x8664) { Reject 'MODULE_NOT_X64_PE' }
        $Stream.Position = $offset + 24
        if ($reader.ReadUInt16() -ne 0x020B) { Reject 'MODULE_NOT_X64_PE' }
    } finally { $reader.Dispose(); $Stream.Position = 0 }
}
try {
    $trusted = Require-Hash $TrustedProfileSha256 'INVALID_TRUST_ANCHOR'
    $profileFull = Local-FullPath $ProfilePath
    $lockedProfile = Open-Locked $profileFull
    $result.checked.profileSha256 = $lockedProfile.Sha256
    if ($lockedProfile.Sha256 -cne $trusted) { Reject 'PROFILE_TRUST_MISMATCH' }
    if ($lockedProfile.Bytes -gt 262144) { Reject 'PROFILE_TOO_LARGE' }
    $reader = New-Object IO.StreamReader($lockedProfile.Stream, (New-Object Text.UTF8Encoding($false,$true)), $true, 1024, $true)
    try { $profile = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    $required = @('schema','variant','producerVariant','filterName','architecture','coreAbi','coreAbiWire',
        'veyraSourceCommit','coreSourceSha256','headerSha256','moduleBuildSha256','mpvSourceCommit',
        'bridgeSourceSha256','vfSourceSha256','filterSourceManifestSha256','mpvDllSha256',
        'coreModuleRelativePath','mpvModuleRelativePath','runtimeRoot','featureDirectory',
        'projectId','engineVersion','runtimeFiles')
    foreach ($key in $required) {
        if ($profile.PSObject.Properties.Name -notcontains $key) { Reject 'PROFILE_SCHEMA_INVALID' }
    }
    $fixed = @{
        variant='bilipai-veyra-core-v1'; producerVariant='bilipai-veyra-rtx-core-v1'
        filterName='bilipai-rtx'; architecture='windows-x64'
        veyraSourceCommit='96a7c8de36bc195240161de6814739ad810722f1'
        coreSourceSha256='1DC853DE084EF333A762F4FC4CA7EADBA3A76FC70BAD0CADC0AD5529B5B84FE8'
        headerSha256='0B9521ABD2725E5DA969A1DAD81BFF51619847A989A07563DCF4B1DF4A64E569'
        mpvSourceCommit='69e63f425a531f814431fba12750bdb3721357f2'
        bridgeSourceSha256='868CFCF4AD01194DAFEC4312B0EFAC6CDAC947E6AE47F15101B2D2B1BC3BAF47'
        vfSourceSha256='D8B0A3A09C93732D63585BE8425D9582ADDF3FDF6A236770F412FDB5B38C1965'
        coreModuleRelativePath='core/bilipai_veyra_core.dll'; mpvModuleRelativePath='mpv/libmpv-2.dll'
        runtimeRoot='.'; featureDirectory='runtime/experimental'; engineVersion='BiliPai-Veyra-Core-1'
    }
    foreach ($key in $fixed.Keys) {
        if ($profile.$key -isnot [string] -or $profile.$key -cne $fixed[$key]) { Reject 'PROFILE_SOURCE_IDENTITY_MISMATCH' }
    }
    if ((Require-Hash $profile.filterSourceManifestSha256 'INVALID_FILTER_SOURCE_MANIFEST_HASH') -cne '9C0F19DE87DA2398F15D09DD27EBCA911BA292E5689D53BF7F62EA1742C3359F') { Reject 'FILTER_SOURCE_MANIFEST_MISMATCH' }
    if ($profile.schema -ne 1 -or $profile.coreAbi -ne 1 -or $profile.coreAbiWire -ne 65536) { Reject 'PROFILE_ABI_MISMATCH' }
    $result.checked.coreSourceSha256 = $profile.coreSourceSha256
    $result.checked.headerSha256 = $profile.headerSha256
    $result.checked.coreAbi = 1; $result.checked.coreAbiWire = 65536
    if ([string]::IsNullOrWhiteSpace($RuntimeRoot)) { $RuntimeRoot = [IO.Path]::GetDirectoryName($profileFull) }
    $rootFull = Local-FullPath $RuntimeRoot
    Assert-NoReparse $rootFull
    if (-not (Test-Path -LiteralPath $rootFull -PathType Container)) { Reject 'RUNTIME_ROOT_REQUIRED' }
    $runtimePins = @(
        [pscustomobject]@{ relativePath='runtime/experimental/nvngx_vsr.dll'; bytes=19140144; fileVersion='1.6.0.0'; sha256='C3D88EEA5FF7A548EDEFA66414CF6E77464D0947277C904F324DD23ABF58A1ED' },
        [pscustomobject]@{ relativePath='runtime/experimental/nvngx_truehdr.dll'; bytes=3955752; fileVersion='1.1.0.0'; sha256='9A80575F247190C05FE80EAC0C4BAA1D0D4D932348F26808310B5EC4BF9EEB4B' }
    )
    $signerSubject = 'CN=NVIDIA Corporation, OU=1-G, O=NVIDIA Corporation, L=Santa Clara, S=California, C=US'
    $signerThumbprint = '9EA06F2F21DCCF7E67AFA4DFA1BCD49681ECD509'
    if (@($profile.runtimeFiles).Count -ne 2) { Reject 'RUNTIME_PROFILE_COUNT_MISMATCH' }
    $seen = @{}
    foreach ($entry in $profile.runtimeFiles) {
        foreach ($key in @('relativePath','sha256','bytes','fileVersion','nvidiaSigner')) {
            if ($entry.PSObject.Properties.Name -notcontains $key) { Reject 'RUNTIME_PROFILE_INVALID' }
        }
        $pin = @($runtimePins | Where-Object { $_.relativePath -ceq $entry.relativePath })
        if ($pin.Count -ne 1 -or $seen.ContainsKey([string]$entry.relativePath)) { Reject 'RUNTIME_PROFILE_IDENTITY_MISMATCH' }
        $seen[[string]$entry.relativePath] = $true
        if ($entry.sha256 -cne $pin[0].sha256 -or $entry.bytes -ne $pin[0].bytes -or
            $entry.fileVersion -cne $pin[0].fileVersion -or $entry.nvidiaSigner.subject -cne $signerSubject -or
            $entry.nvidiaSigner.thumbprint -cne $signerThumbprint -or $entry.nvidiaSigner.status -cne 'Valid') {
            Reject 'RUNTIME_PROFILE_IDENTITY_MISMATCH'
        }
        $full = Relative-Path $rootFull $entry.relativePath
        $locked = Open-Locked $full
        Assert-X64Pe $locked.Stream
        $sig = Get-AuthenticodeSignature -LiteralPath $full
        $version = ([string](Get-Item -LiteralPath $full -Force).VersionInfo.FileVersion).Replace(',','.').Replace(' ','')
        $checkedFile = [ordered]@{
            relativePath=$entry.relativePath; sha256=$locked.Sha256; bytes=$locked.Bytes; fileVersion=$version
            authenticodeStatus=[string]$sig.Status; signerSubject=$null; signerThumbprint=$null
        }
        if ($null -ne $sig.SignerCertificate) {
            $checkedFile.signerSubject = $sig.SignerCertificate.Subject
            $checkedFile.signerThumbprint = $sig.SignerCertificate.Thumbprint.ToUpperInvariant()
        }
        $result.checked.runtimeFiles += $checkedFile
        if ($locked.Sha256 -cne $pin[0].sha256 -or $locked.Bytes -ne $pin[0].bytes -or $version -cne $pin[0].fileVersion) { Reject 'RUNTIME_BYTES_MISMATCH' }
        if ($checkedFile.authenticodeStatus -cne 'Valid' -or $checkedFile.signerSubject -cne $signerSubject -or
            $checkedFile.signerThumbprint -cne $signerThumbprint) { Reject 'NVIDIA_SIGNATURE_MISMATCH' }
    }
    if ($RuntimeOnly) {
        $result.status = 'VERIFIED'
        $result.missingInputs = @('FULL_COMPONENT_BINDING_NOT_REQUESTED')
    } else {
        $missing = @()
        if ($null -eq $profile.moduleBuildSha256) { $missing += 'CORE_MODULE_BUILD_SHA256' }
        if ($null -eq $profile.mpvDllSha256) { $missing += 'PATCHED_MPV_DLL_SHA256' }
        if ($null -eq $profile.projectId -or [string]::IsNullOrWhiteSpace([string]$profile.projectId)) { $missing += 'NGX_PROJECT_ID' }
        $result.missingInputs = $missing
        if ($missing.Count -gt 0) { Reject 'MISSING_BOUND_MODULE_IDENTITIES' }
        $coreExpected = Require-Hash $profile.moduleBuildSha256 'INVALID_CORE_MODULE_HASH'
        $mpvExpected = Require-Hash $profile.mpvDllSha256 'INVALID_MPV_MODULE_HASH'
        $ownGuid = [Guid]::Empty
        if ($profile.projectId -isnot [string] -or $profile.projectId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' -or
            -not [Guid]::TryParseExact($profile.projectId,'D',[ref]$ownGuid) -or $ownGuid -eq [Guid]::Empty) { Reject 'INVALID_OWN_PROJECT_ID' }
        if ([string]::IsNullOrWhiteSpace($CoreModulePath)) { $CoreModulePath = Relative-Path $rootFull $profile.coreModuleRelativePath }
        if ([string]::IsNullOrWhiteSpace($MpvModulePath)) { $MpvModulePath = Relative-Path $rootFull $profile.mpvModuleRelativePath }
        $core = Open-Locked (Local-FullPath $CoreModulePath)
        Assert-X64Pe $core.Stream
        $result.checked.moduleBuildSha256 = $core.Sha256
        if ($core.Sha256 -cne $coreExpected) { Reject 'CORE_MODULE_BYTES_MISMATCH' }
        $mpv = Open-Locked (Local-FullPath $MpvModulePath)
        Assert-X64Pe $mpv.Stream
        $result.checked.mpvDllSha256 = $mpv.Sha256
        if ($mpv.Sha256 -cne $mpvExpected) { Reject 'MPV_MODULE_BYTES_MISMATCH' }
        $result.status = 'VERIFIED'; $result.engineStatus = 'AVAILABLE'
    }
} catch {
    $detail = [string]$_.Exception.Message
    if ($detail -match '^BV_CODE:([A-Z0-9_]+)$') { $result.failureCode = $Matches[1] }
    else { $result.failureCode = 'VERIFICATION_ERROR' }
} finally {
    foreach ($stream in $leases) { $stream.Dispose() }
}
[Console]::Out.WriteLine(($result | ConvertTo-Json -Depth 10 -Compress))
if ($result.status -ceq 'VERIFIED') { exit 0 }
exit 2
