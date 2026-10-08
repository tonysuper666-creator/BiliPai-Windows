[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ProfilePath,
    [Parameter(Mandatory=$true)][string]$TrustedProfileSha256,
    [string]$ComponentRoot,
    [switch]$RuntimeOnly
)
# Independent BiliPai DLSS metadata authentication. Windows PowerShell 5.1+.
# Never loads a DLL or selects an application backend. The caller must hash-pin
# this verifier and the profile independently. Leases last only for this call;
# a future host must authenticate and retain its own file leases before load.
# Fixed reviewed shared v1/v2 source closure; no installed module identity is preselected.
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$leases = New-Object 'System.Collections.Generic.List[System.IO.FileStream]'
$sourcePinRevision = 'SHARED_HOST_7320FED4_V1_V2'
# These must be populated from the reviewed actual shared native source packet,
# never from the profile being verified or from an upstream/latest lookup.
$nativePins = [ordered]@{
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

$result = [ordered]@{
    schema=1; status='REJECTED'; componentStatus='UNAVAILABLE'; engineStatus='UNAVAILABLE'
    failureCode=$null; missingInputs=@()
    checked=[ordered]@{
        profileSha256=$null; buildReceiptSha256=$null; moduleBuildSha256=$null
        sourcePinRevision=$sourcePinRevision; coreAbi=2; coreAbiWire=131072
        sourceManifestSha256=$null; v2CoreSourceSha256=$null
        v2HeaderSha256=$null; cmakeSha256=$null; runtimeFiles=@()
    }
    pendingIntegration=@('LINEAR_GUIDANCE_PRODUCER','PLAYER_CONSUMER_PRESENTATION')
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
try {
    $trusted=Require-Hash $TrustedProfileSha256 'INVALID_TRUST_ANCHOR'
    $profileFull=Local-FullPath $ProfilePath
    $lockedProfile=Open-Locked $profileFull
    $result.checked.profileSha256=$lockedProfile.Sha256
    if ($lockedProfile.Sha256 -cne $trusted) { Reject 'PROFILE_TRUST_MISMATCH' }
    $profile=Read-LockedJson $lockedProfile 262144
    Require-Properties $profile @('schema','variant','architecture','coreAbi','coreAbiWire',
        'sourcePinRevision','veyraSourceCommit','veyraReleaseCommit','veyraDlssManifestSha256',
        'officialDlssSdkCommit','dlssSdkManifestSha256','sourceIdentity','moduleBuildSha256',
        'buildReceiptSha256','coreModuleRelativePath','buildReceiptRelativePath','featureDirectory',
        'projectId','adapterEngineVersion','ngxHostEngineVersion','runtimeFiles') 'PROFILE_SCHEMA_INVALID'
    $fixed=@{
        variant='bilipai-veyra-dlss-sr-auth-v2'; architecture='windows-x64'
        sourcePinRevision=$sourcePinRevision
        veyraSourceCommit='96a7c8de36bc195240161de6814739ad810722f1'
        veyraReleaseCommit='e3aa842a00f693a4a17d1fc4c454d0ebc59428f6'
        officialDlssSdkCommit='374959484e79a640feaba44c93ac8cfb0a03f5b5'
        coreModuleRelativePath='core/bilipai_veyra_core.dll'
        buildReceiptRelativePath='core/veyra-native-build-receipt.json'
        featureDirectory='runtime/experimental'; adapterEngineVersion='BiliPai-Veyra-DLSS-SR-2'
        ngxHostEngineVersion='BiliPai-Veyra-Core-Shared-1'
    }
    foreach($key in $fixed.Keys) {
        if($profile.$key -isnot [string] -or $profile.$key -cne $fixed[$key]) { Reject 'PROFILE_IDENTITY_MISMATCH' }
    }
    if($profile.schema -ne 1 -or $profile.coreAbi -ne 2 -or $profile.coreAbiWire -ne 131072) { Reject 'PROFILE_ABI_MISMATCH' }
    Same-Hash $profile.veyraDlssManifestSha256 '5C05B471443750CDD5776B673257A68216413B3792E5A79BA5B5A6418943732E' 'UPSTREAM_MANIFEST_MISMATCH'
    Same-Hash $profile.dlssSdkManifestSha256 '5FB7A798B0A753F9933FBA3B9BEC539D592FA7322F9F59B718BDD44FB1C5F812' 'SDK_CLOSURE_MISMATCH'
    if([string]::IsNullOrWhiteSpace($ComponentRoot)) { $ComponentRoot=[IO.Path]::GetDirectoryName($profileFull) }
    $rootFull=Local-FullPath $ComponentRoot
    Assert-NoReparse $rootFull
    if(-not(Test-Path -LiteralPath $rootFull -PathType Container)) { Reject 'COMPONENT_ROOT_REQUIRED' }
    $runtimePin=[pscustomobject]@{
        relativePath='runtime/experimental/nvngx_dlss.dll'; bytes=58977904; fileVersion='310.7.0.0'
        sha256='BE6E434A94CA32499515EB62CA0E6C274526055D568D0426E4C652DCDFB6EE6E'
    }
    $subject='CN=NVIDIA Corporation, OU=1005bk6, O=NVIDIA Corporation, L=Santa Clara, S=California, C=US'
    $thumbprint='7B7B0B6697AFB438CF6F65A155F00E86676FB186'
    if(@($profile.runtimeFiles).Count -ne 1) { Reject 'RUNTIME_PROFILE_COUNT_MISMATCH' }
    $entry=@($profile.runtimeFiles)[0]
    Require-Properties $entry @('relativePath','sha256','bytes','fileVersion','nvidiaSigner') 'RUNTIME_PROFILE_INVALID'
    Require-Properties $entry.nvidiaSigner @('status','subject','thumbprint') 'RUNTIME_PROFILE_INVALID'
    if($entry.relativePath -cne $runtimePin.relativePath -or $entry.bytes -ne $runtimePin.bytes -or
       $entry.fileVersion -cne $runtimePin.fileVersion -or $entry.nvidiaSigner.status -cne 'Valid' -or
       $entry.nvidiaSigner.subject -cne $subject -or $entry.nvidiaSigner.thumbprint -cne $thumbprint) { Reject 'RUNTIME_PROFILE_IDENTITY_MISMATCH' }
    Same-Hash $entry.sha256 $runtimePin.sha256 'RUNTIME_PROFILE_IDENTITY_MISMATCH'
    $runtimeFull=Relative-Path $rootFull $runtimePin.relativePath
    $runtime=Open-Locked $runtimeFull
    Assert-X64Pe $runtime.Stream
    $sig=Get-AuthenticodeSignature -LiteralPath $runtimeFull
    $version=([string](Get-Item -LiteralPath $runtimeFull -Force).VersionInfo.FileVersion).Replace(',','.').Replace(' ','')
    $observed=[ordered]@{
        relativePath=$runtimePin.relativePath; sha256=$runtime.Sha256; bytes=$runtime.Bytes
        fileVersion=$version; authenticodeStatus=[string]$sig.Status
        signerSubject=$null; signerThumbprint=$null
    }
    if($null -ne $sig.SignerCertificate) {
        $observed.signerSubject=$sig.SignerCertificate.Subject
        $observed.signerThumbprint=$sig.SignerCertificate.Thumbprint.ToUpperInvariant()
    }
    $result.checked.runtimeFiles=@($observed)
    if($runtime.Sha256 -cne $runtimePin.sha256 -or $runtime.Bytes -ne $runtimePin.bytes -or $version -cne $runtimePin.fileVersion) { Reject 'RUNTIME_BYTES_MISMATCH' }
    if($observed.authenticodeStatus -cne 'Valid' -or $observed.signerSubject -cne $subject -or $observed.signerThumbprint -cne $thumbprint) { Reject 'NVIDIA_SIGNATURE_MISMATCH' }
    if($RuntimeOnly) {
        $result.status='VERIFIED';$result.componentStatus='RUNTIME_VERIFIED'
        $result.missingInputs=@('NATIVE_COMPONENT_BINDING_NOT_REQUESTED')
    } else {
        # A future source release changes this table and profile revision together.
        # A runtime-only or old private receipt never certifies the shared module.
        Require-Properties $profile.sourceIdentity @($nativePins.Keys) 'PROFILE_SOURCE_SCHEMA_INVALID'
        foreach($key in $nativePins.Keys) {
            Same-Hash $profile.sourceIdentity.$key $nativePins[$key] 'PROFILE_NATIVE_SOURCE_MISMATCH'
            $result.checked.$key=Require-Hash $profile.sourceIdentity.$key 'PROFILE_NATIVE_SOURCE_MISMATCH'
        }
        $missing=@()
        if($null -eq $profile.moduleBuildSha256) { $missing+='SHARED_MODULE_BUILD_SHA256' }
        if($null -eq $profile.buildReceiptSha256) { $missing+='NATIVE_BUILD_RECEIPT_SHA256' }
        if($null -eq $profile.projectId) { $missing+='OWN_PERSISTED_PROJECT_ID' }
        $result.missingInputs=$missing
        if($missing.Count -gt 0) { Reject 'MISSING_BOUND_NATIVE_IDENTITIES' }
        $ownGuid=[Guid]::Empty
        if($profile.projectId -isnot [string] -or $profile.projectId -notmatch '\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z' -or
           -not[Guid]::TryParseExact($profile.projectId,'D',[ref]$ownGuid) -or $ownGuid -eq [Guid]::Empty) { Reject 'INVALID_OWN_PROJECT_ID' }
        $build=Read-SharedBuild $profile $rootFull 'buildReceiptSha256' 'buildReceiptRelativePath' $nativePins
        $result.checked.buildReceiptSha256=$build.Sha256
        $receipt=$build.Receipt
        $module=Open-Locked (Relative-Path $rootFull $profile.coreModuleRelativePath)
        Assert-X64Pe $module.Stream
        Same-Hash $module.Sha256 $profile.moduleBuildSha256 'MODULE_BYTES_MISMATCH'
        Same-Hash $module.Sha256 $receipt.module.sha256 'MODULE_BUILD_RECEIPT_MISMATCH'
        if($module.Bytes -ne $receipt.module.bytes) { Reject 'MODULE_BUILD_RECEIPT_MISMATCH' }
        $result.checked.moduleBuildSha256=$module.Sha256
        $result.status='VERIFIED';$result.componentStatus='NATIVE_PROVENANCE_VERIFIED'
        # There is no player guidance/consumer binding in this independent helper.
        # Authentication, SDK acceptance and displayed output are distinct states.
    }
} catch {
    $detail=[string]$_.Exception.Message
    if($detail -match '\ABV_CODE:([A-Z0-9_]+)\z') { $result.failureCode=$Matches[1] }
    else { $result.failureCode='VERIFICATION_ERROR' }
} finally {
    foreach($stream in $leases) { $stream.Dispose() }
}
[Console]::Out.WriteLine(($result | ConvertTo-Json -Depth 12 -Compress))
if($result.status -ceq 'VERIFIED') { exit 0 }
exit 2
