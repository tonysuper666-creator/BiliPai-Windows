# TON-998 U1: build the current desktop baseline as a portable Windows package.
# Source: local clone of the integration checkout (branch desktop/integration-263-claude-main).
# Output: apps\BiliPai Windows\ plus build\status.json. No admin escalation, no install.
#
# The child build runs through cmd so that PowerShell 5.1 never turns the native
# stderr of java/gradle/curl into terminating error records (build.ps1 sets
# ErrorActionPreference=Stop and is meant for pwsh).
$ErrorActionPreference = 'Stop'
$root    = 'C:\Users\TONYS\bp-mac-verify'
if ($env:BP_VERIFY_ROOT) { $root = $env:BP_VERIFY_ROOT }
$src     = Join-Path $root 'src\BiliPai-Windows'
# Local source of the desktop baseline. A clone/fetch of the public repository works too;
# this path is the integration checkout that already exists on the machine.
$source  = 'C:\Users\TONYS\AppData\Local\Temp\bp-integration-263'
$logs    = Join-Path $root 'tools\logs'
$appDir  = Join-Path $root 'apps\BiliPai Windows'
$statusP = Join-Path $root 'build\status.json'
$logP    = Join-Path $logs ('build-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
$jdk     = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
New-Item -ItemType Directory -Force -Path $logs, (Split-Path $statusP) | Out-Null

function Save-Status($obj) { $obj | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 -Path $statusP }

$st = [ordered]@{ stage = 'start'; ok = $false; sha = $null; branch = $null; log = $logP; error = $null; started = (Get-Date).ToString('o'); ended = $null; zip = $null }
Save-Status $st

try {
  if (Test-Path (Join-Path $root 'tools\PAUSE')) { $st.stage = 'paused'; $st.error = 'PAUSE present'; Save-Status $st; return }

  if (-not (Test-Path (Join-Path $src '.git'))) {
    $st.stage = 'clone'; Save-Status $st
    New-Item -ItemType Directory -Force -Path (Split-Path $src) | Out-Null
    & cmd /c "git -c core.longpaths=true clone --quiet --branch desktop/integration-263-claude-main `"$source`" `"$src`" >> `"$logP`" 2>&1"
    if ($LASTEXITCODE -ne 0) { throw "git clone failed ($LASTEXITCODE)" }
    & cmd /c "git -C `"$src`" config core.longpaths true >> `"$logP`" 2>&1"
  }

  $st.sha = (& git -C $src rev-parse HEAD).Trim()
  $st.branch = (& git -C $src rev-parse --abbrev-ref HEAD).Trim()
  $dirty = ((& git -C $src status --porcelain) -join '').Trim()
  if ($dirty) { throw "source tree is dirty: $dirty" }
  $st.stage = 'build'; Save-Status $st

  $buildPs1 = Join-Path $src 'desktop\tools\build.ps1'
  # desktop/gradle.properties caps the daemon at -Xmx4g, which this module's Kotlin
  # compile reports as "not enough memory" on this machine. Raise it for this build only;
  # the repository file stays untouched.
  $env:GRADLE_OPTS = '-Dorg.gradle.jvmargs=-Xmx8192m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8'
  & cmd /c "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$buildPs1`" -JavaHome `"$jdk`" -SkipTests >> `"$logP`" 2>&1"
  if ($LASTEXITCODE -ne 0) { throw "build.ps1 failed ($LASTEXITCODE)" }

  $zip = Get-ChildItem (Join-Path $src 'desktop\build\distributions') -Filter 'BiliPai-Windows-*-x64.zip' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $zip) { throw 'portable zip missing' }
  $st.zip = $zip.FullName

  $st.stage = 'stage-apps'; Save-Status $st
  if (Test-Path $appDir) { Remove-Item -LiteralPath $appDir -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $appDir | Out-Null
  Expand-Archive -LiteralPath $zip.FullName -DestinationPath $appDir -Force

  $exe = Get-ChildItem -LiteralPath $appDir -Filter 'BiliPai Windows.exe' -Recurse -File | Select-Object -First 1
  if (-not $exe) { throw 'staged app has no launcher' }
  $st.ok = $true; $st.stage = 'done'; $st.exe = $exe.FullName
  $st.zipSha256 = (Get-FileHash -LiteralPath $zip.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
  Save-Status $st
} catch {
  $st.stage = 'failed'; $st.error = $_.Exception.Message; Save-Status $st
} finally {
  $st.ended = (Get-Date).ToString('o'); Save-Status $st
}
