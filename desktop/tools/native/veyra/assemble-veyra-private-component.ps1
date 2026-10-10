[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$CoreModulePath,
    [Parameter(Mandatory=$true)][string]$CoreBuildReceiptPath,
    [Parameter(Mandatory=$true)][string]$TrustedCoreBuildReceiptSha256,
    [Parameter(Mandatory=$true)][string]$MpvStagedRoot,
    [Parameter(Mandatory=$true)][string]$MpvDescriptorPath,
    [Parameter(Mandatory=$true)][string]$TrustedMpvDescriptorSha256,
    [Parameter(Mandatory=$true)][string]$TrustedMpvProvenanceSha256,
    [Parameter(Mandatory=$true)][string]$MpvArchivePath,
    [Parameter(Mandatory=$true)][string]$SourceBundlePath,
    [Parameter(Mandatory=$true)][string]$NvidiaRuntimeDirectory,
    [Parameter(Mandatory=$true)][string]$TrustedTemplateSha256,
    [string]$DesktopRoot = (Join-Path $PSScriptRoot '../../..'),
    [Parameter(Mandatory=$true)][string]$IdentityProfilePath,
    [Parameter(Mandatory=$true)][string]$TrustedIdentityProfileSha256,
    [switch]$ActivateForCurrentUser,
    [string]$TrustedPreviousSelectionSha256,
    [ValidateSet('bilipai-veyra-rtx-core-v1','bilipai-veyra-rtx-present-v1')][string]$ProducerVariant='bilipai-veyra-rtx-core-v1'
)
# Local private assembly only. No HTTP, SDK invocation, DLL load, application
# start, registry mutation, upload or public redistribution is performed here.
# Independent receipt/descriptor/profile anchors are explicit operator inputs.
Set-StrictMode -Version 2.0
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
$leases=New-Object 'System.Collections.Generic.List[System.IO.FileStream]'
$stage=$null; $moved=$false; $selectionStage=$null
$result=[ordered]@{schema=1;status='REJECTED';engineStatus='UNAVAILABLE';failureCode=$null;componentRelativePath=$null;profileSha256=$null;selectionSha256=$null;selected=$false;gpuVerified=$false}
function Reject([string]$Code){throw ('BV_CODE:'+$Code)}
function Hash-Value([object]$Value){if($Value-isnot[string]-or$Value-notmatch'\A[0-9a-fA-F]{64}\z'){Reject 'INVALID_HASH'};return $Value.ToLowerInvariant()}
function Full([string]$Path){if([string]::IsNullOrWhiteSpace($Path)-or$Path-notmatch'\A[A-Za-z]:[\\/]' -or $Path-match'[\x00-\x1f"]'){Reject 'LOCAL_ABSOLUTE_PATH_REQUIRED'};return [IO.Path]::GetFullPath($Path)}
function No-Reparse([string]$Path){
    $cursor=$Path
    while(-not[string]::IsNullOrEmpty($cursor)){
        if(-not(Test-Path -LiteralPath $cursor)){Reject 'PATH_NOT_FOUND'}
        if(((Get-Item -LiteralPath $cursor -Force).Attributes-band[IO.FileAttributes]::ReparsePoint)-ne0){Reject 'REPARSE_PATH_REJECTED'}
        $parent=[IO.Path]::GetDirectoryName($cursor.TrimEnd('\','/'));if($parent-eq$cursor){break};$cursor=$parent
    }
}
function Relative([string]$Root,[object]$Name){
    if($Name-isnot[string]-or$Name-notmatch'\A[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\z'){Reject 'UNSAFE_RELATIVE_PATH'}
    foreach($part in $Name.Split('/')){if($part-in@('.','..')-or$part.EndsWith('.')){Reject 'UNSAFE_RELATIVE_PATH'}}
    $base=Full $Root;$path=[IO.Path]::GetFullPath([IO.Path]::Combine($base,$Name.Replace('/','\')))
    if(-not$path.StartsWith($base.TrimEnd('\','/')+'\',[StringComparison]::OrdinalIgnoreCase)){Reject 'PATH_ESCAPES_ROOT'};return $path
}
function Open-Locked([string]$Path,[string]$Expected=''){
    $full=Full $Path;No-Reparse $full
    $stream=[IO.File]::Open($full,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read);$leases.Add($stream)
    $hash=[Security.Cryptography.SHA256]::Create()
    try{$digest=([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$hash.Dispose();$stream.Position=0}
    if($Expected-and$digest-cne(Hash-Value $Expected)){Reject 'INPUT_HASH_MISMATCH'}
    return [pscustomobject]@{Path=$full;Stream=$stream;Sha256=$digest;Bytes=$stream.Length}
}
function Read-Json([object]$Locked,[long]$Limit=1048576){
    if($Locked.Bytes-le0-or$Locked.Bytes-gt$Limit){Reject 'JSON_SIZE_INVALID'}
    $reader=New-Object IO.StreamReader($Locked.Stream,(New-Object Text.UTF8Encoding($false,$true)),$true,1024,$true)
    try{return ($reader.ReadToEnd()|ConvertFrom-Json)}finally{$reader.Dispose();$Locked.Stream.Position=0}
}
function Fields([object]$Value,[string[]]$Names){if($null-eq$Value){Reject 'SCHEMA_INVALID'};foreach($name in $Names){if($Value.PSObject.Properties.Name-notcontains$name){Reject 'SCHEMA_INVALID'}}}
function Equal-Hash([object]$Value,[object]$Expected){if((Hash-Value $Value)-cne(Hash-Value $Expected)){Reject 'IDENTITY_HASH_MISMATCH'}}
function False-Field([object]$Value){if($Value-isnot[bool]-or$Value-ne$false){Reject 'UNVERIFIED_CANDIDATE_BOUNDARY_CHANGED'}}
function Copy-Locked([object]$Locked,[string]$Destination){
    $parent=[IO.Path]::GetDirectoryName($Destination);[void][IO.Directory]::CreateDirectory($parent);No-Reparse $parent
    $out=[IO.File]::Open($Destination,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$Locked.Stream.Position=0;$Locked.Stream.CopyTo($out);$out.Flush($true)}finally{$out.Dispose();$Locked.Stream.Position=0}
}
function Write-New([string]$Path,[byte[]]$Bytes){$out=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None);try{$out.Write($Bytes,0,$Bytes.Length);$out.Flush($true)}finally{$out.Dispose()}}
function Json-Bytes([object]$Value){return (New-Object Text.UTF8Encoding($false)).GetBytes(($Value|ConvertTo-Json -Depth 30))}
function Bytes-Hash([byte[]]$Bytes){$h=[Security.Cryptography.SHA256]::Create();try{return ([BitConverter]::ToString($h.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant()}finally{$h.Dispose()}}
function Invoke-Verifier([string]$Script,[string]$Profile,[string]$Digest,[string]$Root){
    # All paths are validated Windows filenames, no embedded quotes or controls.
    $exe=Join-Path ([Environment]::GetFolderPath('Windows')) 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $verifierArgs=@('-NoLogo','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$Script,'-ProfilePath',$Profile,'-TrustedProfileSha256',$Digest,'-RuntimeRoot',$Root)
    $info=New-Object Diagnostics.ProcessStartInfo;$info.FileName=$exe;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $info.Arguments=($verifierArgs|ForEach-Object{if($_-match'["\r\n]'){Reject 'INVALID_CHILD_ARGUMENT'};'"'+$_+'"'})-join' '
    foreach($key in @('GITHUB_TOKEN','GH_TOKEN','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS')){[void]$info.EnvironmentVariables.Remove($key)}
    $child=New-Object Diagnostics.Process;$child.StartInfo=$info
    try{
        if(-not$child.Start()){Reject 'VERIFIER_START_FAILED'}
        # Bounded original verifier emits one JSON object; read asynchronously to
        # avoid pipe deadlock. The selected immutable script does not load DLLs.
        $stdout=$child.StandardOutput.ReadToEndAsync();$stderr=$child.StandardError.ReadToEndAsync()
        if(-not$child.WaitForExit(30000)){try{$child.Kill()}catch{};Reject 'VERIFIER_TIMEOUT'}
        $text=$stdout.GetAwaiter().GetResult();[void]$stderr.GetAwaiter().GetResult()
        if($child.ExitCode-ne0-or$text.Length-gt65536){Reject 'FULL_VERIFIER_REJECTED'}
        $proof=$text|ConvertFrom-Json
        if($proof.schema-ne1-or$proof.status-cne'VERIFIED'-or$proof.engineStatus-cne'AVAILABLE'){Reject 'FULL_VERIFIER_REJECTED'}
        Equal-Hash $proof.checked.profileSha256 $Digest
        return $proof
    }finally{$child.Dispose()}
}
function Assert-PresentationSource([object]$Native,[object]$Manifest,[object[]]$Upstream,[object[]]$Registration) {
    if($Manifest.schema-ne2-or$Manifest.variant-cne'bilipai-veyra-rtx-present-v1'-or
       $Manifest.tokenProtocol-ne2-or$Manifest.presentationProperty-cne'bilipai-rtx-presentation'-or
       @($Manifest.sourceFiles).Count-ne21-or$Upstream.Count-ne24-or$Registration.Count-ne3){throw 'Presentation source manifest graph mismatch.'}
    if($Native.schema-isnot[int]-or$Native.tokenProtocol-isnot[int]){throw 'Presentation receipt protocol must use integer values.'}
    foreach($flag in @('nativeBinaryBuiltByThisProgram','gpuExecuted','displayProofRuntimeVerified')){if($Native.$flag-isnot[bool]-or$Native.$flag-ne$false){throw 'Presentation receipt may not claim native execution or runtime verification.'}}
    if($Native.schema-ne3-or$Native.patchId-cne$Manifest.variant-or$Native.sourceCommit-cne$Manifest.sourceCommit-or
       $Native.filterName-cne$Manifest.filterName-or$Native.filterSourceManifestSha256-cne'a1cda53ef043749e006c88a84abee129bccc7bfc649b011976cd421e75e5c607'-or
       $Native.coreAbiHeaderSha256-cne$Manifest.coreAbiHeaderSha256-or$Native.tokenProtocol-ne2-or
       $Native.presentationProperty-cne$Manifest.presentationProperty-or
       $Native.sourcePatchHelperSha256-cne'748199ed370c169b17337154a3f7d0fede10a1c9420b1a8b02b3844aa6e6bebf'-or
       $Native.nativeBinaryBuiltByThisProgram-cne$false-or$Native.gpuExecuted-cne$false-or
       $Native.displayProofRuntimeVerified-cne$false){throw 'Actual presentation native receipt identity mismatch.'}
    if(@($Native.filterSourceFiles).Count-ne21){throw 'Incomplete presentation private source inventory.'}
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
    if(@($Native.sourceGraph).Count-ne28-or$expected.Count-ne28){throw 'Incomplete presentation complete-file graph.'}
    $seen=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach($row in $expected){
        if(-not$seen.Add([string]$row.path)){throw 'Duplicate presentation graph target.'}
        $same=@($Native.sourceGraph|Where-Object{$_.path-ceq$row.path})
        if($same.Count-ne1-or$same[0].beforeSha256-cne$row.beforeSha256-or$same[0].afterSha256-cne$row.afterSha256){throw 'Presentation complete-file source graph differs.'}
    }
}
try{
    $presentation=$ProducerVariant-ceq'bilipai-veyra-rtx-present-v1'
    $templateContract='tools/native/veyra/veyra-profile-template.json';$buildFolder='third-party/libmpv/build/rtx-core-v1';$manifestLeaf='bilipai-rtx-source-manifest.json'
    $contracts=[ordered]@{
        'tools/native/veyra/verify-veyra-runtime.ps1'='b5a2e65a25c0ecfb0f894cc2ea248befc9453541526c7e864c146e3ee9dd9b5b'
        'tools/native/veyra/veyra-profile-template.json'='b9d6201b7e33d3e042edaf0175683d6166a5e013dc85825c98ecc9e740cd2bbc'
        'third-party/libmpv/build/rtx-core-v1/fixed-inputs.json'='b1cfbd180bd2c0c00257f29176707cc965cd7f849a22271cae402259778bceb7'
        'third-party/libmpv/build/rtx-core-v1/bilipai-rtx-source-manifest.json'='9c0f19de87da2398f15d09dd27ebca911ba292e5689d53bf7f62ea1742c3359f'
        'third-party/libmpv/build/rtx-core-v1/filter-registration-edits.json'='59d1c4ffbb4506d9d81586d6146ba4a54a0882557f1c8861a858cbe24cd2c5cf'
        'third-party/libmpv/SOURCES.json'='0e5fd9b4f5d2698d2404b115b9f7995cb41887f450c74eab3d7fe9ccb0b624d3'
    }
    if($presentation){
        foreach($key in @('tools/native/veyra/veyra-profile-template.json','third-party/libmpv/build/rtx-core-v1/fixed-inputs.json','third-party/libmpv/build/rtx-core-v1/bilipai-rtx-source-manifest.json','third-party/libmpv/build/rtx-core-v1/filter-registration-edits.json')){[void]$contracts.Remove($key)}
        $templateContract='tools/native/veyra/veyra-presentation-profile-template.json';$buildFolder='third-party/libmpv/build/rtx-present-v1';$manifestLeaf='bilipai-rtx-presentation-source-manifest.json'
        $contracts[$templateContract]='aa24c3e09ccc247ef5641aefe5da779c67700a58c64b1d12852fb169da600cac'
        $contracts[$buildFolder+'/fixed-inputs.json']='14d9e9ae6e40a81b3f7af47883c5658fa7907821f66d16f0907ed26ddc921cd0'
        $contracts[$buildFolder+'/'+$manifestLeaf]='a1cda53ef043749e006c88a84abee129bccc7bfc649b011976cd421e75e5c607'
        $contracts[$buildFolder+'/filter-registration-edits.json']='95b48fb8e6073c493a91f0373e778fc7c6c22c4f9d3b23e74bc889ca08c9e042'
        $contracts[$buildFolder+'/presentation-edits.json']='e84fd26d22eb7ac012747960d72ae384191e93dc5ab68a0d1e81fa9dfaf3d34f'
    }
    $desktop=Full $DesktopRoot;No-Reparse $desktop;$contractFiles=@{}
    foreach($key in $contracts.Keys){$contractFiles[$key]=Open-Locked (Relative $desktop $key) $contracts[$key]}
    Equal-Hash $TrustedTemplateSha256 $contracts[$templateContract]
    $profile=Read-Json $contractFiles[$templateContract] 65536
    $fixed=Read-Json $contractFiles[$buildFolder+'/fixed-inputs.json']
    $manifest=Read-Json $contractFiles[$buildFolder+'/'+$manifestLeaf]
    $registrations=@(Read-Json $contractFiles[$buildFolder+'/filter-registration-edits.json'])
    $catalog=Read-Json $contractFiles['third-party/libmpv/SOURCES.json']
    if($null-ne$profile.moduleBuildSha256-or$null-ne$profile.mpvDllSha256-or$null-ne$profile.nativeBuildReceiptSha256-or$null-ne$profile.projectId){Reject 'TEMPLATE_NOT_UNSELECTED'}
    $coreReceiptFile=Open-Locked $CoreBuildReceiptPath $TrustedCoreBuildReceiptSha256;$coreReceipt=Read-Json $coreReceiptFile
    Fields $coreReceipt @('schema','variant','sourceCommit','moduleRelativeName','module','checks','actualNgxHostEngineVersion','adapterEngineVersions')
    if($coreReceipt.schema-ne1-or$coreReceipt.variant-cne$profile.nativeVariant-or$coreReceipt.sourceCommit-cne$profile.veyraSourceCommit-or
        $coreReceipt.moduleRelativeName-cne'bilipai_veyra_core.dll'-or$coreReceipt.actualNgxHostEngineVersion-cne$profile.ngxHostEngineVersion){Reject 'SHARED_CORE_IDENTITY_MISMATCH'}
    foreach($p in $profile.sharedSourceIdentity.PSObject.Properties){Fields $coreReceipt @($p.Name);Equal-Hash $coreReceipt.($p.Name) $p.Value}
    Fields $coreReceipt.module @('sha256','bytes','architecture','exports')
    $exports=@('bv_create_v1','bv_process_v1','bv_reset_v1','bv_destroy_v1','bvd_create_v2','bvd_process_v2','bvd_reset_v2','bvd_destroy_v2')
    if($coreReceipt.module.architecture-cne'windows-x64'-or@($coreReceipt.module.exports).Count-ne8-or
       (@($coreReceipt.module.exports|Sort-Object)-join',')-cne(@($exports|Sort-Object)-join',')){Reject 'SHARED_CORE_EXPORTS_INVALID'}
    $core=Open-Locked $CoreModulePath (Hash-Value $coreReceipt.module.sha256)
    if($core.Bytes-ne[long]$coreReceipt.module.bytes){Reject 'CORE_BYTES_MISMATCH'}
    # The original full verifier below checks all seven actual build-check fields,
    # exact adapter versions, x64 PE and runtime signature pins without running SDK.
    $descriptorFile=Open-Locked $MpvDescriptorPath $TrustedMpvDescriptorSha256;$descriptor=Read-Json $descriptorFile
    $mpvRoot=Full $MpvStagedRoot;No-Reparse $mpvRoot
    $provenanceFile=Open-Locked (Relative $mpvRoot 'provenance.json') $TrustedMpvProvenanceSha256;$provenance=Read-Json $provenanceFile
    if($descriptor.schema-ne2-or$provenance.schema-ne2){Reject 'MPV_SCHEMA_INVALID'}
    $fixedFields=@('variant','filterName','filterSourceManifestSha256','coreAbiHeaderSha256','sourceCommit','nativePatchSha256','originalNativeSourceSha256','patchedNativeSourceSha256','recipeCommit')
    foreach($key in $fixedFields){Fields $descriptor @($key);Fields $provenance @($key);if($descriptor.$key-cne$fixed.$key-or$provenance.$key-cne$fixed.$key){Reject 'MPV_FIXED_IDENTITY_MISMATCH'}}
    if($descriptor.architecture-cne'windows-x64'-or$provenance.architecture-cne'windows-x64'-or
        $descriptor.recipeArchiveSha256-cne$fixed.archives[0].sha256-or$provenance.recipeArchiveSha256-cne$fixed.archives[0].sha256-or
        $descriptor.containerImage-cne'ghcr.io/tonysuper666-creator/bilipai-windows-builder@sha256:c7dffe77b57d98b10e327dde12d3977faf4cb90aa7cb4f5eeac4e9d68d724239'-or
        $provenance.containerImage-cne$descriptor.containerImage){Reject 'MPV_BUILD_ENVIRONMENT_MISMATCH'}
    foreach($key in @('closedSdkOrRuntimeIncluded','vfgImplemented','rtxCoreBridgeVerified')){False-Field $descriptor.$key}
    foreach($key in @('rtxCoreBridgeVerified','reproducible','nativeResolutionPpeVerified')){False-Field $provenance.$key}
    if($descriptor.artifact.fileName-cne($fixed.variant+'-x64.zip')-or$descriptor.sourceBundle.fileName-cne($fixed.variant+'-source-materials.tar.gz')){Reject 'MPV_ASSET_NAME_INVALID'}
    Equal-Hash $provenance.runtimeDescriptorSha256 $descriptorFile.Sha256
    Equal-Hash $provenance.archiveSha256 $descriptor.artifact.archiveSha256
    Equal-Hash $provenance.dllSha256 $descriptor.artifact.dllSha256
    Equal-Hash $provenance.buildReceiptSha256 $descriptor.buildReceiptSha256
    Equal-Hash $provenance.sourceBundleSha256 $descriptor.sourceBundle.sha256
    if($descriptor.artifact.dllSha256-ceq$fixed.originalDllSha256){Reject 'ORIGINAL_MPV_NOT_COMPATIBLE'}
    $archive=Open-Locked $MpvArchivePath (Hash-Value $descriptor.artifact.archiveSha256)
    $sourceBundle=Open-Locked $SourceBundlePath (Hash-Value $descriptor.sourceBundle.sha256)
    $rows=@($descriptor.artifact.entries);if($rows.Count-lt7-or$rows.Count-gt64){Reject 'MPV_INVENTORY_COUNT_INVALID'}
    $seen=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $rowFiles=@{};$total=0L
    foreach($row in $rows){
        Fields $row @('path','sha256','bytes')
        if($row.path-cnotmatch'\A(?:libmpv-2\.dll|licenses/[A-Za-z0-9_./-]+)\z'-or-not$seen.Add([string]$row.path)-or[long]$row.bytes-le0-or[long]$row.bytes-gt536870912){Reject 'MPV_INVENTORY_INVALID'}
        $file=Open-Locked (Relative $mpvRoot $row.path) (Hash-Value $row.sha256)
        if($file.Bytes-ne[long]$row.bytes){Reject 'MPV_STAGED_BYTES_MISMATCH'};$rowFiles[$row.path]=$file;$total+=$file.Bytes
    }
    if($total-gt1073741824){Reject 'MPV_INVENTORY_TOO_LARGE'}
    foreach($path in @('libmpv-2.dll','licenses/build-receipt.json','licenses/native-patch-receipt.json','licenses/SOURCES.json','licenses/NOTICES.md','licenses/bilipai-veyra-core-GPL3.txt','licenses/rtx-filter-source-manifest.json')){if(-not$seen.Contains($path)){Reject 'MPV_REQUIRED_FILE_MISSING'}}
    Equal-Hash $rowFiles['libmpv-2.dll'].Sha256 $descriptor.artifact.dllSha256
    Equal-Hash $rowFiles['licenses/build-receipt.json'].Sha256 $descriptor.buildReceiptSha256
    Equal-Hash $rowFiles['licenses/rtx-filter-source-manifest.json'].Sha256 $fixed.filterSourceManifestSha256
    Equal-Hash $rowFiles['licenses/bilipai-veyra-core-GPL3.txt'].Sha256 $fixed.bridgeGpl3LicenseSha256
    $mpvReceipt=Read-Json $rowFiles['licenses/build-receipt.json'];$nativeReceipt=Read-Json $rowFiles['licenses/native-patch-receipt.json']
    foreach($key in $fixedFields){if($mpvReceipt.$key-cne$descriptor.$key){Reject 'MPV_BUILD_RECEIPT_IDENTITY_MISMATCH'}}
    foreach($key in @('recipeArchiveSha256','containerImage')){if($mpvReceipt.$key-cne$descriptor.$key){Reject 'MPV_BUILD_RECEIPT_IDENTITY_MISMATCH'}}
    if($mpvReceipt.ffmpegCommit-cne$fixed.ffmpegCommit-or$provenance.ffmpegCommit-cne$fixed.ffmpegCommit){Reject 'FFMPEG_IDENTITY_MISMATCH'}
    Equal-Hash $mpvReceipt.dllSha256 $descriptor.artifact.dllSha256;Equal-Hash $mpvReceipt.sourceBundleSha256 $sourceBundle.Sha256
    foreach($p in $fixed.expectedPatchedRecipeSha256.PSObject.Properties){if($mpvReceipt.patchedRecipeSha256.($p.Name)-cne$p.Value-or$provenance.patchedRecipeSha256.($p.Name)-cne$p.Value){Reject 'MPV_RECIPE_MISMATCH'}}
    if($presentation){
        $upstream=@(Read-Json $contractFiles[$buildFolder+'/presentation-edits.json'] 2097152)
        foreach($item in @(@{path='licenses/rtx-presentation-edits.json';sha256='e84fd26d22eb7ac012747960d72ae384191e93dc5ab68a0d1e81fa9dfaf3d34f'},@{path='licenses/rtx-registration-edits.json';sha256='95b48fb8e6073c493a91f0373e778fc7c6c22c4f9d3b23e74bc889ca08c9e042'})){
            if(-not$rowFiles.ContainsKey($item.path)){Reject 'MPV_PRESENTATION_SOURCE_MISSING'};Equal-Hash $rowFiles[$item.path].Sha256 $item.sha256
        }
        $protocol=@{presentationProtocolVersion=2;presentationProperty='bilipai-rtx-presentation';upstreamEditsSha256='e84fd26d22eb7ac012747960d72ae384191e93dc5ab68a0d1e81fa9dfaf3d34f';sourcePatchHelperSha256='748199ed370c169b17337154a3f7d0fede10a1c9420b1a8b02b3844aa6e6bebf'}
        foreach($key in $protocol.Keys){if($descriptor.$key-cne$protocol[$key]-or$provenance.$key-cne$protocol[$key]-or$mpvReceipt.$key-cne$protocol[$key]){Reject 'MPV_PRESENTATION_PROTOCOL_MISMATCH'}}
        Assert-PresentationSource $nativeReceipt $manifest $upstream $registrations
    }else{
    if($nativeReceipt.schema-ne2-or$nativeReceipt.patchId-cne$fixed.variant-or$nativeReceipt.sourceCommit-cne$fixed.sourceCommit-or
        $nativeReceipt.patchSha256-cne$fixed.nativePatchSha256-or$nativeReceipt.patchedSourceSha256-cne$fixed.patchedNativeSourceSha256-or
        $nativeReceipt.filterName-cne$fixed.filterName-or$nativeReceipt.filterSourceManifestSha256-cne$fixed.filterSourceManifestSha256-or$nativeReceipt.coreAbiHeaderSha256-cne$fixed.coreAbiHeaderSha256){Reject 'MPV_NATIVE_SOURCE_RECEIPT_MISMATCH'}
    if(@($nativeReceipt.filterSourceFiles).Count-ne@($manifest.sourceFiles).Count-or@($nativeReceipt.registrations).Count-ne$registrations.Count){Reject 'MPV_SOURCE_CLOSURE_COUNT_MISMATCH'}
    foreach($source in $manifest.sourceFiles){$matches=@($nativeReceipt.filterSourceFiles|Where-Object{$_.targetPath-ceq$source.targetPath});if($matches.Count-ne1){Reject 'MPV_SOURCE_CLOSURE_INVALID'};foreach($key in @('sourcePath','fileName','targetPath','sha256','bytes')){if($matches[0].$key-cne$source.$key){Reject 'MPV_SOURCE_CLOSURE_INVALID'}}}
    foreach($reg in $registrations){$matches=@($nativeReceipt.registrations|Where-Object{$_.path-ceq$reg.path});if($matches.Count-ne1-or$matches[0].beforeSha256-cne$reg.beforeSHA256-or$matches[0].afterSha256-cne$reg.afterSHA256){Reject 'MPV_REGISTRATION_RECEIPT_MISMATCH'}}
    }
    foreach($license in $catalog.licenseFiles){$path='licenses/'+$license.path;if(-not$rowFiles.ContainsKey($path)){Reject 'MPV_ORIGINAL_LICENSE_MISSING'};Equal-Hash $rowFiles[$path].Sha256 $license.sha256}
    # The existing identity initializer is the only first-use GUID author.
    # Every assembled update must carry that independently anchored own identity.
    $identity=Read-Json (Open-Locked $IdentityProfilePath $TrustedIdentityProfileSha256) 65536;$parsed=[Guid]::Empty
    if($identity.schema-ne1-or$identity.variant-cne$profile.variant-or$identity.engineVersion-cne$profile.engineVersion-or
        $identity.projectId-isnot[string]-or$identity.projectId-notmatch'\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z'-or
        -not[Guid]::TryParseExact($identity.projectId,'D',[ref]$parsed)-or$parsed-eq[Guid]::Empty){Reject 'OWN_IDENTITY_PROFILE_INVALID'}
    $guid=$identity.projectId
    $runtime=Full $NvidiaRuntimeDirectory;No-Reparse $runtime;$runtimeFiles=@{}
    foreach($pin in $profile.runtimeFiles){$name=[IO.Path]::GetFileName($pin.relativePath);$runtimeFiles[$pin.relativePath]=Open-Locked (Join-Path $runtime $name) (Hash-Value $pin.sha256)}
    $local=[Environment]::GetEnvironmentVariable('LOCALAPPDATA');if([string]::IsNullOrWhiteSpace($local)){Reject 'LOCAL_APPDATA_REQUIRED'}
    $root=Full (Join-Path $local 'BiliPaiWindows/private-components/veyra');[void][IO.Directory]::CreateDirectory($root);No-Reparse $root
    $components=Join-Path $root 'components';[void][IO.Directory]::CreateDirectory($components);No-Reparse $components
    $id=[Guid]::NewGuid().ToString('N');$stage=Join-Path $components ('.stage-'+$id);$destination=Join-Path $components $id
    if(Test-Path -LiteralPath $stage){Reject 'STAGE_ALREADY_EXISTS'};[void][IO.Directory]::CreateDirectory($stage);No-Reparse $stage
    Copy-Locked $core (Relative $stage 'core/bilipai_veyra_core.dll');Copy-Locked $coreReceiptFile (Relative $stage 'core/veyra-native-build-receipt.json')
    foreach($path in $rowFiles.Keys){Copy-Locked $rowFiles[$path] (Relative $stage ('mpv/'+$path))}
    Copy-Locked $provenanceFile (Relative $stage 'mpv/provenance.json')
    Copy-Locked $descriptorFile (Relative $stage 'mpv/runtime-descriptor.json')
    foreach($path in $runtimeFiles.Keys){Copy-Locked $runtimeFiles[$path] (Relative $stage $path)}
    $profile.projectId=$guid;$profile.moduleBuildSha256=$core.Sha256.ToUpperInvariant();$profile.mpvDllSha256=$rowFiles['libmpv-2.dll'].Sha256.ToUpperInvariant();$profile.nativeBuildReceiptSha256=$coreReceiptFile.Sha256.ToUpperInvariant()
    if($presentation){$profile.mpvNativeReceiptSha256=$rowFiles['licenses/native-patch-receipt.json'].Sha256.ToUpperInvariant()}
    $profile.selection.boundStatus='MATERIALS_BOUND_NOT_LOADED';$profile.selection.reason='explicit-private-authenticated-build-pair-not-gpu-verified'
    $bytes=Json-Bytes $profile;$profileHash=Bytes-Hash $bytes;$profilePath=Relative $stage 'profile.json';Write-New $profilePath $bytes
    $proof=Invoke-Verifier $contractFiles['tools/native/veyra/verify-veyra-runtime.ps1'].Path $profilePath $profileHash $stage
    Equal-Hash $proof.checked.moduleBuildSha256 $core.Sha256;Equal-Hash $proof.checked.mpvDllSha256 $rowFiles['libmpv-2.dll'].Sha256;Equal-Hash $proof.checked.nativeBuildReceiptSha256 $coreReceiptFile.Sha256
    if($proof.checked.producerVariant-cne$fixed.variant){Reject 'VERIFIER_PRODUCER_MISMATCH'}
    if($presentation-and$proof.checked.presentationProtocolVersion-ne2){Reject 'VERIFIER_PRESENTATION_PROTOCOL_MISMATCH'}
    $receipt=[ordered]@{schema=1;status='PACKAGE_VERIFIED_NOT_LOADED';profileSha256=$profileHash;moduleBuildSha256=$core.Sha256;nativeBuildReceiptSha256=$coreReceiptFile.Sha256;mpvDllSha256=$rowFiles['libmpv-2.dll'].Sha256;descriptorSha256=$descriptorFile.Sha256;mpvProvenanceSha256=$provenanceFile.Sha256;archiveSha256=$archive.Sha256;sourceBundleSha256=$sourceBundle.Sha256;sourceBundleCopied=$false;closedRuntimesPrivateOnly=$true;gpuVerified=$false;coreLoaded=$false;mpvLoaded=$false;verifierSha256=$contracts['tools/native/veyra/verify-veyra-runtime.ps1']}
    Write-New (Relative $stage 'component-assembly-receipt.json') (Json-Bytes $receipt)
    # A unique directory publication does not select it or make a permanent seal.
    # Reopen the actual copied bytes after the rename and hold no-write/no-delete
    # leases through the optional anchor publication; startup verifies again.
    $checkedComponents=Full $components;$checkedStage=Full $stage;$checkedDestination=Full $destination
    $movePrefix=$checkedComponents.TrimEnd('\','/')+'\'
    if(-not$checkedStage.StartsWith($movePrefix,[StringComparison]::OrdinalIgnoreCase)-or
       -not$checkedDestination.StartsWith($movePrefix,[StringComparison]::OrdinalIgnoreCase)-or
       [IO.Path]::GetDirectoryName($checkedStage)-cne$checkedComponents-or
       [IO.Path]::GetDirectoryName($checkedDestination)-cne$checkedComponents-or
       [IO.Path]::GetFileName($checkedStage)-cne('.stage-'+$id)-or
       [IO.Path]::GetFileName($checkedDestination)-cne$id-or(Test-Path -LiteralPath $checkedDestination)){Reject 'PACKAGE_MOVE_PATH_INVALID'}
    No-Reparse $checkedComponents;No-Reparse $checkedStage
    [IO.Directory]::Move($checkedStage,$checkedDestination);$moved=$true
    $result.componentRelativePath='components/'+$id
    [void](Open-Locked (Relative $destination 'profile.json') $profileHash)
    [void](Open-Locked (Relative $destination 'core/bilipai_veyra_core.dll') $core.Sha256)
    [void](Open-Locked (Relative $destination 'core/veyra-native-build-receipt.json') $coreReceiptFile.Sha256)
    foreach($path in $rowFiles.Keys){[void](Open-Locked (Relative $destination ('mpv/'+$path)) $rowFiles[$path].Sha256)}
    [void](Open-Locked (Relative $destination 'mpv/provenance.json') $provenanceFile.Sha256)
    [void](Open-Locked (Relative $destination 'mpv/runtime-descriptor.json') $descriptorFile.Sha256)
    foreach($path in $runtimeFiles.Keys){[void](Open-Locked (Relative $destination $path) $runtimeFiles[$path].Sha256)}
    $result.status='PACKAGE_VERIFIED_NOT_LOADED';$result.profileSha256=$profileHash
    if($ActivateForCurrentUser){
        $current=Join-Path $root 'current.json';$old=$null
        if(Test-Path -LiteralPath $current){if(-not$TrustedPreviousSelectionSha256){Reject 'PREVIOUS_SELECTION_ANCHOR_REQUIRED'};$old=Open-Locked $current $TrustedPreviousSelectionSha256}
        elseif($TrustedPreviousSelectionSha256){Reject 'UNEXPECTED_PREVIOUS_SELECTION_ANCHOR'}
        $selection=[ordered]@{schema=1;selectionKind='EXPLICIT_PRIVATE_COMPONENT';variant='bilipai-veyra-core-v1';componentRelativePath=$result.componentRelativePath;trustedProfileSha256=$profileHash}
        $selectionBytes=Json-Bytes $selection;$selectionStage=Join-Path $root ('selection-'+[Guid]::NewGuid().ToString('N')+'.tmp');Write-New $selectionStage $selectionBytes
        if($null-ne$old){
            # Close only this captured leaf immediately before atomic replacement.
            # Preserve exact prior anchor as a rollback candidate, not a health claim.
            $backup=Join-Path $root ('previous-'+[Guid]::NewGuid().ToString('N')+'.json');Copy-Locked $old $backup
            $old.Stream.Dispose();[void]$leases.Remove($old.Stream);[IO.File]::Replace($selectionStage,$current,$null)
        }else{[IO.File]::Move($selectionStage,$current)}
        $selectionStage=$null;$result.selected=$true;$result.selectionSha256=Bytes-Hash $selectionBytes
    }
}catch{
    $result.status='REJECTED';$result.engineStatus='UNAVAILABLE';$detail=[string]$_.Exception.Message
    if($detail-match'\ABV_CODE:([A-Z0-9_]+)\z'){$result.failureCode=$Matches[1]}else{$result.failureCode='ASSEMBLY_FAILED'}
}finally{
    foreach($stream in $leases){$stream.Dispose()}
    if($null-ne$selectionStage){try{if(Test-Path -LiteralPath $selectionStage -PathType Leaf){No-Reparse $selectionStage;[IO.File]::Delete($selectionStage)}}catch{}}
    if($null-ne$stage-and-not$moved){
        try{
            $fullStage=Full $stage;$allowed=(Full $components).TrimEnd('\','/')+'\'
            if(-not$fullStage.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)-or[IO.Path]::GetFileName($fullStage)-cne('.stage-'+$id)){Reject 'CLEANUP_PATH_INVALID'}
            No-Reparse $fullStage
            foreach($entry in Get-ChildItem -LiteralPath $fullStage -Recurse -Force){if(($entry.Attributes-band[IO.FileAttributes]::ReparsePoint)-ne0){Reject 'CLEANUP_REPARSE_REJECTED'}}
            [IO.Directory]::Delete($fullStage,$true)
        }catch{$result.failureCode='STAGE_RETAINED_FOR_OWNER_REVIEW'}
    }
}
[Console]::Out.WriteLine(($result|ConvertTo-Json -Depth 8 -Compress))
if($result.status-ceq'PACKAGE_VERIFIED_NOT_LOADED'){exit 0};exit 2
