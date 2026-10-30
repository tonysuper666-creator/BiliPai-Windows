# Windows PowerShell 5.1's Set-Content -Encoding UTF8 emits a BOM.
# Evidence must also parse with a plain UTF-8 JSON reader.
function Save-BpJson($Value, [string]$Path) {
    $json = $Value | ConvertTo-Json -Depth 16
    [IO.File]::WriteAllText("$Path.new", $json, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$Path.new" -Destination $Path -Force
}
