# The existing fetcher verifies the complete producer/source provenance first.
# This packaging seam copies only its declared companion, never arbitrary DLLs.
function Copy-VerifiedMpvCompanionResources {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$NativeRoot,
        [Parameter(Mandatory)][string]$ResourceRoot,
        [Parameter(Mandatory)][object]$Provenance
    )
    $ErrorActionPreference='Stop'
    $destination=Join-Path $ResourceRoot 'vulkan-1.dll'
    $property=$Provenance.PSObject.Properties['companionRuntime']
    if($null-eq$property-or$null-eq$property.Value){
        if($Provenance.variant-ceq'bilipai-veyra-rtx-present-v1'){throw 'Presentation MPV requires its declared Vulkan companion.'}
        # Remove only this known, owned resource from an earlier local build.
        if(Test-Path -LiteralPath $destination){
            if(-not(Test-Path -LiteralPath $destination -PathType Leaf)-or
               ((Get-Item -LiteralPath $destination -Force).Attributes-band[IO.FileAttributes]::ReparsePoint)-ne0){
                throw 'Stale companion resource is not a regular owned file.'
            }
            Remove-Item -LiteralPath $destination -Force
        }
        return
    }
    $record=$property.Value
    if($record.kind-cne'SOURCE_BUILT_VULKAN_LOADER'-or$record.path-cne'vulkan-1.dll'-or
       $record.architecture-cne'windows-x64'-or$record.sha256-isnot[string]-or
       $record.sha256-cnotmatch'\A[0-9a-f]{64}\z'-or
       ($record.bytes-isnot[int]-and$record.bytes-isnot[long])-or$record.bytes-le0-or$record.bytes-gt67108864){
        throw 'Declared MPV companion identity is invalid.'
    }
    $source=Join-Path $NativeRoot 'vulkan-1.dll'
    if(-not(Test-Path -LiteralPath $source -PathType Leaf)){throw 'Declared MPV companion is missing.'}
    if(((Get-Item -LiteralPath $source -Force).Attributes-band[IO.FileAttributes]::ReparsePoint)-ne0){
        throw 'MPV companion source cannot redirect.'
    }
    if((Get-Item -LiteralPath $source).Length-ne$record.bytes-or
       (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()-cne$record.sha256){
        throw 'Declared MPV companion source bytes differ.'
    }
    if(Test-Path -LiteralPath $destination){
        if(-not(Test-Path -LiteralPath $destination -PathType Leaf)-or
           ((Get-Item -LiteralPath $destination -Force).Attributes-band[IO.FileAttributes]::ReparsePoint)-ne0){
            throw 'MPV companion destination cannot redirect.'
        }
    }
    Copy-Item -LiteralPath $source -Destination $destination -Force
    if((Get-Item -LiteralPath $destination).Length-ne$record.bytes-or
       (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()-cne$record.sha256){
        throw 'Packaged MPV companion bytes differ.'
    }
}
