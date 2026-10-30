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
$launcher=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\Launch-MacTest.ps1",[ref]$tokens,[ref]$errors)
$writers=@($launcher.FindAll({param($a) $a -is [Management.Automation.Language.FunctionDefinitionAst] -and $a.Name -eq 'Save-Result'},$true))
if($writers.Count -ne 1){throw 'launcher result writer absent'}
# Execute only the actual result serializer, never the launcher entry point.
. ([scriptblock]::Create($writers[0].Extent.Text))
$resP="$root\ton1047\json-writer-review.json"
$message="Path 参数为空`nquoted: `"value`""
Save-Result ([ordered]@{message=$message;clientStarts=0;guiStarts=0})
$bytes=[IO.File]::ReadAllBytes($resP)
if($bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191){throw 'launcher JSON contains BOM'}
$roundtrip=[Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
if($roundtrip.message -cne $message){throw 'UTF-8 JSON roundtrip changed message'}
Write-Output 'RESULT_JSON_WRITER_UTF8_NO_BOM_OK'
