[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$TemplatePath,
    [Parameter(Mandatory=$true)][string]$TrustedTemplateSha256,
    [Parameter(Mandatory=$true)][string]$ProfilePath,
    [string]$TrustedExistingProfileSha256
)
# Authors an explicitly selected BiliPai profile; never reads another app's
# identity. CUSTOM NGX Init_with_ProjectID accepts our own GUID-like identifier.
# NVIDIA/DLSS fixed374959 include/nvsdk_ngx.h:114-116,198-216; upstream fixed96a7
# scripts/stage-runtime.ps1 uses the same NewGuid persistence pattern.
# This authoring receipt is not a trust anchor. The owner must independently pin
# the returned profile SHA before using verify-veyra-runtime.ps1.
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
$out = [ordered]@{ schema=1; status='REJECTED'; profileSha256=$null; projectId=$null; engineVersion=$null; failureCode=$null }
$streams = New-Object 'System.Collections.Generic.List[System.IO.FileStream]'
$createdStage = $null
function Reject([string]$Code) { throw ('BV_CODE:'+$Code) }
function Full([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z]:[\\/]') { Reject 'LOCAL_ABSOLUTE_PATH_REQUIRED' }
    try { return [IO.Path]::GetFullPath($Value) } catch { Reject 'INVALID_PATH' }
}
function No-Reparse([string]$Path) {
    $cursor=$Path
    while (-not [string]::IsNullOrEmpty($cursor)) {
        if(-not(Test-Path -LiteralPath $cursor)){Reject 'PATH_NOT_FOUND'}
        if(((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)-ne 0){Reject 'REPARSE_PATH_REJECTED'}
        $parent=[IO.Path]::GetDirectoryName($cursor.TrimEnd('\','/'))
        if($parent-eq$cursor){break}
        $cursor=$parent
    }
}
function Hash-Stream([IO.FileStream]$Stream) {
    $Stream.Position=0; $hasher=[Security.Cryptography.SHA256]::Create()
    try { $hash=$hasher.ComputeHash($Stream) } finally { $hasher.Dispose() }
    $Stream.Position=0
    return ([BitConverter]::ToString($hash).Replace('-',''))
}
function Read-Trusted([string]$Path,[string]$TrustedHash) {
    if($TrustedHash -notmatch '^[0-9a-fA-F]{64}$'){Reject 'INVALID_TRUST_ANCHOR'}
    No-Reparse $Path
    $stream=[IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    $streams.Add($stream)
    $actual=Hash-Stream $stream
    if($actual-cne$TrustedHash.ToUpperInvariant()){Reject 'PROFILE_TRUST_MISMATCH'}
    if($stream.Length-gt262144){Reject 'PROFILE_TOO_LARGE'}
    $reader=New-Object IO.StreamReader($stream,(New-Object Text.UTF8Encoding($false,$true)),$true,1024,$true)
    try {$data=$reader.ReadToEnd()|ConvertFrom-Json} finally {$reader.Dispose()}
    return [pscustomobject]@{Data=$data;Sha256=$actual}
}
try {
    $templateFull=Full $TemplatePath
    $template=Read-Trusted $templateFull $TrustedTemplateSha256
    if($template.Data.schema-ne1 -or $template.Data.variant-cne'bilipai-veyra-core-v1' -or
        $null-ne$template.Data.projectId -or $template.Data.engineVersion-cne'BiliPai-Veyra-Core-1'){Reject 'INVALID_IDENTITY_TEMPLATE'}
    $profileFull=Full $ProfilePath
    if([string]::Equals($templateFull,$profileFull,[StringComparison]::OrdinalIgnoreCase)){Reject 'OUTPUT_EQUALS_TEMPLATE'}
    No-Reparse ([IO.Path]::GetDirectoryName($profileFull))
    if(Test-Path -LiteralPath $profileFull) {
        if([string]::IsNullOrWhiteSpace($TrustedExistingProfileSha256)){Reject 'EXISTING_PROFILE_TRUST_ANCHOR_REQUIRED'}
        $existing=Read-Trusted $profileFull $TrustedExistingProfileSha256
        foreach($key in @('schema','variant','producerVariant','filterName','architecture','coreAbi','coreAbiWire',
            'veyraSourceCommit','coreSourceSha256','headerSha256','mpvSourceCommit','bridgeSourceSha256',
            'vfSourceSha256','filterSourceManifestSha256','coreModuleRelativePath','mpvModuleRelativePath',
            'runtimeRoot','featureDirectory','engineVersion')) {
            if($existing.Data.PSObject.Properties.Name-notcontains$key -or
                ($existing.Data.$key|ConvertTo-Json -Compress)-cne($template.Data.$key|ConvertTo-Json -Compress)){Reject 'EXISTING_SOURCE_IDENTITY_MISMATCH'}
        }
        $ownGuid=[Guid]::Empty
        if($existing.Data.projectId-isnot[string] -or $existing.Data.projectId-notmatch'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' -or
            -not[Guid]::TryParseExact($existing.Data.projectId,'D',[ref]$ownGuid) -or $ownGuid-eq[Guid]::Empty){Reject 'INVALID_OWN_PROJECT_ID'}
        $out.status='REUSED'; $out.profileSha256=$existing.Sha256
        $out.projectId=$existing.Data.projectId; $out.engineVersion=$existing.Data.engineVersion
    } else {
        if(-not[string]::IsNullOrWhiteSpace($TrustedExistingProfileSha256)){Reject 'UNEXPECTED_EXISTING_PROFILE_ANCHOR'}
        $template.Data.projectId=[Guid]::NewGuid().ToString('D')
        $bytes=(New-Object Text.UTF8Encoding($false)).GetBytes(($template.Data|ConvertTo-Json -Depth 15))
        # Stage only our unique sibling; a failed write/hash never creates a
        # partial final profile. File.Move on .NET Framework never overwrites.
        $stageFull=$profileFull+'.new-'+[Guid]::NewGuid().ToString('N')
        if([IO.Path]::GetDirectoryName($stageFull)-cne[IO.Path]::GetDirectoryName($profileFull)){Reject 'PROFILE_STAGE_ESCAPED_PARENT'}
        try {$stream=[IO.File]::Open($stageFull,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}
        catch {Reject 'PROFILE_CREATE_FAILED'}
        $createdStage=$stageFull; $streams.Add($stream)
        $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true)
        $completedSha=Hash-Stream $stream
        $stream.Dispose(); [void]$streams.Remove($stream)
        [IO.File]::Move($stageFull,$profileFull)
        $createdStage=$null
        $out.profileSha256=$completedSha
        $out.projectId=$template.Data.projectId; $out.engineVersion=$template.Data.engineVersion
        $out.status='CREATED'
    }
} catch {
    $out.status='REJECTED'
    $detail=[string]$_.Exception.Message
    if($detail-match'^BV_CODE:([A-Z0-9_]+)$'){$out.failureCode=$Matches[1]}else{$out.failureCode='PROFILE_INITIALIZATION_FAILED'}
} finally {
    foreach($stream in $streams){$stream.Dispose()}
    if($null-ne$createdStage){
        try {
            if(Test-Path -LiteralPath $createdStage -PathType Leaf){[IO.File]::Delete($createdStage)}
        } catch {
            $out.status='REJECTED'; $out.failureCode='PROFILE_STAGE_CLEANUP_FAILED'
        }
    }
}
[Console]::Out.WriteLine(($out|ConvertTo-Json -Depth 6 -Compress))
if($out.status-ceq'CREATED' -or $out.status-ceq'REUSED'){exit 0}
exit 2
