# No GUI, no client launch, no media session enumeration.
$ErrorActionPreference='Stop'
$root='C:\Users\TONYS\bp-mac-verify'
$env:TEMP="$root\ton1020\profile\Temp"; $env:TMP=$env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null
$bad=0
Get-ChildItem "$PSScriptRoot\*.ps1" | ForEach-Object {
    $errors=$null;$tokens=$null
    $null=[Management.Automation.Language.Parser]::ParseFile($_.FullName,[ref]$tokens,[ref]$errors)
    if($errors){$errors;$bad++}
}
if($bad){throw 'PowerShell parse errors'}
$s=Get-Content "$PSScriptRoot\Verify-TON1047.ps1" -Raw -Encoding UTF8
$index=$s.IndexOf("`ntry {")
if($index -lt 0){throw 'session entry point absent'}
. ([scriptblock]::Create($s.Substring(0,$index)))
if(!$asTask){throw 'WinRT AsTask binding absent'}
Write-Output $managerType.FullName
Write-Output $mediaType.FullName
Write-Output 'ALL_PARSE_AND_SMTC_TYPES_OK'
