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
        producerVariant=$null; presentationProtocolVersion=0; filterSourceManifestSha256=$null
        sourcePatchHelperSha256=$null; upstreamEditsSha256=$null; mpvNativeReceiptSha256=$null
    }
}
function Reject([string]$Code) { throw ('BV_CODE:' + $Code) }
function Require-Hash([object]$Value, [string]$Code) {
    if ($Value -isnot [string] -or $Value -notmatch '\A[0-9a-fA-F]{64}\z') { Reject $Code }
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
$sharedPins=[ordered]@{
    sourceManifestSha256='7320FED4931E22334A3D5A2086E93CDF0CB8B227EDA75A216AA7BC8EFBB80A5F'
    buildClosureManifestSha256='C33283F4F26AC0FA8117B341848CC2AA34749CD12B47C6D18B75765E2DA7E12D'
    v1CoreSourceSha256='84E0B6D9525944BEEBA01B2E7D222E4607801A2347FAC780B056754025138CC5'
    v1HeaderSha256='0B9521ABD2725E5DA969A1DAD81BFF51619847A989A07563DCF4B1DF4A64E569'
    v2CoreSourceSha256='D2CBC169CEF2A3350111B1DBC9A18012E8B53D897F00F31D6F74FC638FD622D5'
    v2HeaderSha256='AF884CC3D73262DAFA19A76A32F0B85C48A2E3BDE912BF59885D8DE5D4CDD6BC'
    sharedHostSourceSha256='E21DADB460222EF92C5DE38246BB34F0D78E245F3CA798C2116A259899A8A7AF'
    cmakeSha256='D184E623AF38CF9386A67838AA438C4E1CFFBEAF202F81836F68E4EA85922B8A'
    officialSdkManifestSha256='5FB7A798B0A753F9933FBA3B9BEC539D592FA7322F9F59B718BDD44FB1C5F812'
}
function Read-LockedJson([object]$Locked,[long]$MaxBytes) {
    if($Locked.Bytes -gt $MaxBytes){Reject 'JSON_TOO_LARGE'}
    $reader=New-Object IO.StreamReader($Locked.Stream,(New-Object Text.UTF8Encoding($false,$true)),$true,1024,$true)
    try{return ($reader.ReadToEnd()|ConvertFrom-Json)} finally{$reader.Dispose();$Locked.Stream.Position=0}
}
function Require-Properties([object]$Value,[string[]]$Names,[string]$Code) {
    if($null-eq$Value){Reject $Code}
    foreach($name in $Names){if($Value.PSObject.Properties.Name-notcontains$name){Reject $Code}}
}
function Same-Hash([object]$Value,[object]$Expected,[string]$Code) {
    if((Require-Hash $Value $Code)-cne(Require-Hash $Expected $Code)){Reject $Code}
}
function Read-SharedBuild([object]$Profile,[string]$Root,[string]$ReceiptHashField,[string]$ReceiptPathField,[object]$Pins) {
    $locked=Open-Locked (Relative-Path $Root $Profile.$ReceiptPathField)
    Same-Hash $locked.Sha256 $Profile.$ReceiptHashField 'BUILD_RECEIPT_TRUST_MISMATCH'
    $receipt=Read-LockedJson $locked 1048576
    Require-Properties $receipt @('schema','variant','sourceCommit','moduleRelativeName','adapterEngineVersions',
        'actualNgxHostEngineVersion','module','checks') 'BUILD_RECEIPT_SCHEMA_INVALID'
    Require-Properties $receipt @($Pins.Keys) 'BUILD_RECEIPT_SOURCE_SCHEMA_INVALID'
    if($receipt.schema-ne1 -or $receipt.variant-cne'bilipai-veyra-shared-core-v1-v2' -or
       $receipt.sourceCommit-cne'96a7c8de36bc195240161de6814739ad810722f1' -or
       $receipt.moduleRelativeName-cne'bilipai_veyra_core.dll' -or
       $receipt.actualNgxHostEngineVersion-cne'BiliPai-Veyra-Core-Shared-1'){Reject 'BUILD_RECEIPT_IDENTITY_MISMATCH'}
    foreach($key in $Pins.Keys){Same-Hash $receipt.$key $Pins[$key] 'BUILD_RECEIPT_SOURCE_MISMATCH'}
    Require-Properties $receipt.adapterEngineVersions @('v1','v2') 'BUILD_RECEIPT_ENGINE_INVALID'
    if($receipt.adapterEngineVersions.v1-cne'BiliPai-Veyra-Core-1' -or
       $receipt.adapterEngineVersions.v2-cne'BiliPai-Veyra-DLSS-SR-2'){Reject 'BUILD_RECEIPT_ENGINE_INVALID'}
    Require-Properties $receipt.checks @('actualObjectCompileExit','actualDllLinkExit','onlyOneHostTranslationUnit',
        'singleVerifiedIncludeRoot','eightActualExports','checkedShutdownRedirectCoff','sourceDependenciesExactClosure') 'BUILD_RECEIPT_CHECKS_MISSING'
    if($receipt.checks.actualObjectCompileExit-ne0 -or $receipt.checks.actualDllLinkExit-ne0 -or
       $receipt.checks.onlyOneHostTranslationUnit-ne$true -or $receipt.checks.singleVerifiedIncludeRoot-ne$true -or
       $receipt.checks.eightActualExports-ne$true -or $receipt.checks.checkedShutdownRedirectCoff-ne$true -or
       $receipt.checks.sourceDependenciesExactClosure-ne$true){Reject 'NATIVE_BUILD_NOT_SUCCESSFUL'}
    Require-Properties $receipt.module @('sha256','bytes','architecture','exports') 'BUILD_RECEIPT_MODULE_INVALID'
    if($receipt.module.architecture-cne'windows-x64'){Reject 'BUILD_RECEIPT_MODULE_INVALID'}
    $exports=@('bv_create_v1','bv_process_v1','bv_reset_v1','bv_destroy_v1','bvd_create_v2','bvd_process_v2','bvd_reset_v2','bvd_destroy_v2')
    if(@($receipt.module.exports).Count-ne8 -or
       (@($receipt.module.exports|Sort-Object)-join',')-cne(@($exports|Sort-Object)-join',')){Reject 'SHARED_ABI_EXPORTS_MISMATCH'}
    return [pscustomobject]@{Receipt=$receipt;Sha256=$locked.Sha256}
}
function Assert-PresentationSource([object]$Native,[object]$Manifest,[object[]]$Upstream,[object[]]$Registration) {
    if($Manifest.schema-ne2-or$Manifest.variant-cne'bilipai-veyra-rtx-present-v1'-or
       $Manifest.tokenProtocol-ne1-or$Manifest.presentationProperty-cne'bilipai-rtx-presentation'-or
       @($Manifest.sourceFiles).Count-ne5-or$Upstream.Count-ne13-or$Registration.Count-ne3){throw 'Presentation source manifest graph mismatch.'}
    if($Native.schema-isnot[int]-or$Native.tokenProtocol-isnot[int]){throw 'Presentation receipt protocol must use integer values.'}
    foreach($flag in @('nativeBinaryBuiltByThisProgram','gpuExecuted','displayProofRuntimeVerified')){if($Native.$flag-isnot[bool]-or$Native.$flag-ne$false){throw 'Presentation receipt may not claim native execution or runtime verification.'}}
    if($Native.schema-ne3-or$Native.patchId-cne$Manifest.variant-or$Native.sourceCommit-cne$Manifest.sourceCommit-or
       $Native.filterName-cne$Manifest.filterName-or$Native.filterSourceManifestSha256-cne'7aa01708316eb55a9a4fb7106f02d67932e1d8ae47480c7e19b920951611e338'-or
       $Native.coreAbiHeaderSha256-cne$Manifest.coreAbiHeaderSha256-or$Native.tokenProtocol-ne1-or
       $Native.presentationProperty-cne$Manifest.presentationProperty-or
       $Native.sourcePatchHelperSha256-cne'8cae5dc860f06c86a101f1bbba77d9822e4dd6f6b75a86bf8f483e44d2e37870'-or
       $Native.nativeBinaryBuiltByThisProgram-cne$false-or$Native.gpuExecuted-cne$false-or
       $Native.displayProofRuntimeVerified-cne$false){throw 'Actual presentation native receipt identity mismatch.'}
    if(@($Native.filterSourceFiles).Count-ne5){throw 'Incomplete presentation private source inventory.'}
    foreach($row in $Manifest.sourceFiles){
        $same=@($Native.filterSourceFiles|Where-Object{$_.targetPath-ceq$row.targetPath})
        if($same.Count-ne1){throw 'Ambiguous presentation private source target.'}
        foreach($key in @('sourcePath','fileName','targetPath','sha256','bytes')){
            if($same[0].$key-cne$row.$key){throw 'Presentation private source bytes differ.'}
        }
    }
    $expected=@([pscustomobject]@{path=$Manifest.originalNvidiaPatch.sourcePath;beforeSha256=$Manifest.originalNvidiaPatch.originalSha256;afterSha256=$Manifest.originalNvidiaPatch.patchedSha256})
    $expected+=@($Registration|ForEach-Object{[pscustomobject]@{path=$_.path;beforeSha256=$_.beforeSHA256;afterSha256=$_.afterSHA256}})
    $expected+=@($Upstream|ForEach-Object{[pscustomobject]@{path=$_.path;beforeSha256=$_.beforeSha256;afterSha256=$_.afterSha256}})
    if(@($Native.sourceGraph).Count-ne17-or$expected.Count-ne17){throw 'Incomplete presentation complete-file graph.'}
    $seen=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach($row in $expected){
        if(-not$seen.Add([string]$row.path)){throw 'Duplicate presentation graph target.'}
        $same=@($Native.sourceGraph|Where-Object{$_.path-ceq$row.path})
        if($same.Count-ne1-or$same[0].beforeSha256-cne$row.beforeSha256-or$same[0].afterSha256-cne$row.afterSha256){throw 'Presentation complete-file source graph differs.'}
    }
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
        'projectId','engineVersion','runtimeFiles','nativeVariant','sharedSourceIdentity',
        'ngxHostEngineVersion','nativeBuildReceiptSha256','nativeBuildReceiptRelativePath')
    foreach ($key in $required) {
        if ($profile.PSObject.Properties.Name -notcontains $key) { Reject 'PROFILE_SCHEMA_INVALID' }
    }
    $fixed = @{
        variant='bilipai-veyra-core-v1'; producerVariant='bilipai-veyra-rtx-core-v1'
        filterName='bilipai-rtx'; architecture='windows-x64'
        veyraSourceCommit='96a7c8de36bc195240161de6814739ad810722f1'
        coreSourceSha256='84E0B6D9525944BEEBA01B2E7D222E4607801A2347FAC780B056754025138CC5'
        headerSha256='0B9521ABD2725E5DA969A1DAD81BFF51619847A989A07563DCF4B1DF4A64E569'
        mpvSourceCommit='69e63f425a531f814431fba12750bdb3721357f2'
        bridgeSourceSha256='868CFCF4AD01194DAFEC4312B0EFAC6CDAC947E6AE47F15101B2D2B1BC3BAF47'
        vfSourceSha256='D8B0A3A09C93732D63585BE8425D9582ADDF3FDF6A236770F412FDB5B38C1965'
        coreModuleRelativePath='core/bilipai_veyra_core.dll'; mpvModuleRelativePath='mpv/libmpv-2.dll'
        runtimeRoot='.'; featureDirectory='runtime/experimental'; engineVersion='BiliPai-Veyra-Core-1'
        nativeVariant='bilipai-veyra-shared-core-v1-v2'; ngxHostEngineVersion='BiliPai-Veyra-Core-Shared-1'
        nativeBuildReceiptRelativePath='core/veyra-native-build-receipt.json'
    }
    $presentation=$profile.producerVariant-ceq'bilipai-veyra-rtx-present-v1'
    $manifestExpected='9C0F19DE87DA2398F15D09DD27EBCA911BA292E5689D53BF7F62EA1742C3359F'
    if($presentation){
        $fixed.producerVariant='bilipai-veyra-rtx-present-v1'
        $fixed.bridgeSourceSha256='61F7484D386B7B1C8940AC408267F040A89CEC050A1C2DAC678DA24446149E9B'
        $fixed.vfSourceSha256='86FA9F6F772AF490B39EA117D27D88AC4BEA878BA035E0FDB5F0471B07BA5DA7'
        $manifestExpected='7AA01708316EB55A9A4FB7106F02D67932E1D8AE47480C7E19B920951611E338'
        Require-Properties $profile @('presentationProtocolVersion','presentationProperty','upstreamEditsSha256',
            'sourcePatchHelperSha256','mpvNativeReceiptRelativePath','mpvNativeReceiptSha256') 'PRESENTATION_PROFILE_MISSING'
        if($profile.presentationProtocolVersion-isnot[int]-or$profile.presentationProtocolVersion-ne1-or$profile.presentationProperty-cne'bilipai-rtx-presentation'-or
           $profile.mpvNativeReceiptRelativePath-cne'mpv/licenses/native-patch-receipt.json'){Reject 'PRESENTATION_PROFILE_INVALID'}
        Same-Hash $profile.upstreamEditsSha256 '9c4b625ca178a34d67099234863a093a097cdb38f68bed25cc15715a07ce4bea' 'PRESENTATION_EDIT_IDENTITY_MISMATCH'
        Same-Hash $profile.sourcePatchHelperSha256 '8cae5dc860f06c86a101f1bbba77d9822e4dd6f6b75a86bf8f483e44d2e37870' 'PRESENTATION_HELPER_IDENTITY_MISMATCH'
    }
    foreach ($key in $fixed.Keys) {
        if ($profile.$key -isnot [string] -or $profile.$key -cne $fixed[$key]) { Reject 'PROFILE_SOURCE_IDENTITY_MISMATCH' }
    }
    if ((Require-Hash $profile.filterSourceManifestSha256 'INVALID_FILTER_SOURCE_MANIFEST_HASH') -cne $manifestExpected) { Reject 'FILTER_SOURCE_MANIFEST_MISMATCH' }
    if ($profile.schema -ne 1 -or $profile.coreAbi -ne 1 -or $profile.coreAbiWire -ne 65536) { Reject 'PROFILE_ABI_MISMATCH' }
    Require-Properties $profile.sharedSourceIdentity @($sharedPins.Keys) 'PROFILE_SHARED_SOURCE_INVALID'
    foreach($key in $sharedPins.Keys){Same-Hash $profile.sharedSourceIdentity.$key $sharedPins[$key] 'PROFILE_SHARED_SOURCE_MISMATCH'}
    $result.checked.producerVariant=$profile.producerVariant
    $result.checked.filterSourceManifestSha256=$manifestExpected
    $result.checked.sharedSourceIdentity=$sharedPins
    $result.checked.nativeVariant=$profile.nativeVariant
    $result.checked.ngxHostEngineVersion=$profile.ngxHostEngineVersion
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
        if ($null -eq $profile.nativeBuildReceiptSha256) { $missing += 'SHARED_NATIVE_BUILD_RECEIPT_SHA256' }
        if ($null -eq $profile.projectId -or [string]::IsNullOrWhiteSpace([string]$profile.projectId)) { $missing += 'NGX_PROJECT_ID' }
        $result.missingInputs = $missing
        if($presentation-and$null-eq$profile.mpvNativeReceiptSha256){$missing+='MPV_NATIVE_RECEIPT_SHA256';$result.missingInputs=$missing}
        if ($missing.Count -gt 0) { Reject 'MISSING_BOUND_MODULE_IDENTITIES' }
        $build=Read-SharedBuild $profile $rootFull 'nativeBuildReceiptSha256' 'nativeBuildReceiptRelativePath' $sharedPins
        $result.checked.nativeBuildReceiptSha256=$build.Sha256
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
        Same-Hash $core.Sha256 $build.Receipt.module.sha256 'MODULE_BUILD_RECEIPT_MISMATCH'
        if($core.Bytes-ne$build.Receipt.module.bytes){Reject 'MODULE_BUILD_RECEIPT_MISMATCH'}
        $mpv = Open-Locked (Local-FullPath $MpvModulePath)
        Assert-X64Pe $mpv.Stream
        $result.checked.mpvDllSha256 = $mpv.Sha256
        if ($mpv.Sha256 -cne $mpvExpected) { Reject 'MPV_MODULE_BYTES_MISMATCH' }
        if($presentation){
            $native=Open-Locked (Relative-Path $rootFull $profile.mpvNativeReceiptRelativePath)
            Same-Hash $native.Sha256 $profile.mpvNativeReceiptSha256 'MPV_NATIVE_RECEIPT_TRUST_MISMATCH'
            $sm=Open-Locked (Relative-Path $rootFull 'mpv/licenses/rtx-filter-source-manifest.json')
            Same-Hash $sm.Sha256 $manifestExpected 'MPV_PRESENTATION_MANIFEST_MISMATCH'
            $up=Open-Locked (Relative-Path $rootFull 'mpv/licenses/rtx-presentation-edits.json')
            Same-Hash $up.Sha256 '9c4b625ca178a34d67099234863a093a097cdb38f68bed25cc15715a07ce4bea' 'MPV_PRESENTATION_EDITS_MISMATCH'
            $reg=Open-Locked (Relative-Path $rootFull 'mpv/licenses/rtx-registration-edits.json')
            Same-Hash $reg.Sha256 '59d1c4ffbb4506d9d81586d6146ba4a54a0882557f1c8861a858cbe24cd2c5cf' 'MPV_PRESENTATION_REGISTRATION_MISMATCH'
            Assert-PresentationSource (Read-LockedJson $native 1048576) (Read-LockedJson $sm 1048576) @(Read-LockedJson $up 2097152) @(Read-LockedJson $reg 1048576)
            $result.checked.presentationProtocolVersion=1
            $result.checked.sourcePatchHelperSha256='8cae5dc860f06c86a101f1bbba77d9822e4dd6f6b75a86bf8f483e44d2e37870'
            $result.checked.upstreamEditsSha256='9c4b625ca178a34d67099234863a093a097cdb38f68bed25cc15715a07ce4bea'
            $result.checked.mpvNativeReceiptSha256=$native.Sha256
        }
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
