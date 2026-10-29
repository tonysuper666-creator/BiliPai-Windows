# Offline packaging only. Never starts a client or modifies PR #19 source.
param([string]$ExpectedHead = '8ee3ab796cc81e4389c1a5688cb47a1e27434f08')
$ErrorActionPreference = 'Stop'
$root='C:\Users\TONYS\bp-mac-verify'
$out="$root\ton1047"
New-Item -ItemType Directory -Force $out | Out-Null
$src="$out\src"
$jdk='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$report=[ordered]@{head=$ExpectedHead; passed=$false; stage='preflight'; started=(Get-Date).ToString('o')}
try {
    if(Test-Path "$root\tools\PAUSE"){throw 'PAUSE present'}
    if((Get-ScheduledTask -TaskName BiliPai-MacTest).State -eq 'Running'){throw 'another card is measuring; queue packaging'}
    if(Test-Path "$root\tools\job.lock"){throw 'shared tools lock occupied'}
    if(Test-Path "$root\measure\job.lock"){throw 'measurement lock occupied'}
    $lease=[IO.File]::Open("$root\tools\job.lock",'CreateNew','Write','Read')
    $leaseOwned=$true
    $bytes=[Text.Encoding]::UTF8.GetBytes("TON-1047 offline packaging pid=$PID")
    $lease.Write($bytes,0,$bytes.Length); $lease.Dispose()
    if(!(Test-Path "$src\.git")) {
        & git -c core.longpaths=true clone --no-hardlinks --no-checkout "$root\ton1020\src" $src
        if($LASTEXITCODE -ne 0){throw 'isolated source clone failed'}
        & git -C $src fetch https://github.com/tonysuper666-creator/BiliPai-Windows.git $ExpectedHead
        if($LASTEXITCODE -ne 0){throw 'PR head fetch failed'}
        & git -C $src -c core.longpaths=true checkout --detach $ExpectedHead
        if($LASTEXITCODE -ne 0){throw 'PR checkout failed'}
    }
    & git -C $src config core.longpaths true
    $head=(& git -C $src rev-parse HEAD).Trim()
    if($LASTEXITCODE -ne 0 -or $head -ne $ExpectedHead){throw "source head differs: $head"}
    $dirty=@(& git -C $src status --porcelain)
    if($dirty.Count){throw 'source has uncommitted changes'}
    & git -C $src merge-base --is-ancestor 6afeb4b03 HEAD
    if($LASTEXITCODE -ne 0){throw 'required fix absent'}
    $env:JAVA_HOME=$jdk; $env:Path="$jdk\bin;$env:Path"
    $env:GRADLE_USER_HOME="$root\d9595dc75\raa6e7815\gradle_user_home"
    $env:USERPROFILE="$out\build-profile"; $env:HOME=$env:USERPROFILE
    $env:LOCALAPPDATA="$env:USERPROFILE\Local"; $env:APPDATA="$env:USERPROFILE\Roaming"
    $env:TEMP="$env:USERPROFILE\Temp"; $env:TMP=$env:TEMP
    New-Item -ItemType Directory -Force $env:TEMP,$env:LOCALAPPDATA,$env:APPDATA | Out-Null
    $env:JAVA_TOOL_OPTIONS="-Duser.home=$env:USERPROFILE -Djava.io.tmpdir=$env:TEMP"
    # Windows bsdtar rejects a legacy Unicode documentation filename in this locale.
    # Materialize the EXACT pinned archive with Python instead; no tracked source edits.
    $legacy=(Get-Content "$src\desktop\upstream-core\legacy-root.commit" | Where-Object { $_.Trim() -and !$_.StartsWith('#') }).Trim()
    if(!(Test-Path "$src\.legacy-root\.legacy-commit")) {
        & git -C $src archive --format=tar -o "$out\legacy.tar" $legacy
        if($LASTEXITCODE -ne 0){throw 'legacy archive failed'}
        & 'C:\Users\TONYS\AppData\Local\Programs\Python\Python312\python.exe' -I -S -c "import tarfile; tarfile.open(r'$out\legacy.tar').extractall(r'\\?\$src\.legacy-root',filter='data')"
        if($LASTEXITCODE -ne 0){throw 'Unicode-safe legacy extraction failed'}
        New-Item -ItemType Directory -Force "$src\desktop\build" | Out-Null
        & cmd /d /c "mklink /J `"$src\.legacy-root\desktop\build`" `"$src\desktop\build`""
        if($LASTEXITCODE -ne 0){throw 'legacy build junction failed'}
        [IO.File]::WriteAllText("$src\.legacy-root\.legacy-commit",$legacy,[Text.UTF8Encoding]::new($false))
        $report.legacyMaterializer='Python tarfile of exact git archive (Windows tar Unicode filename failure)'
    }
    $report.stage='createDistributable'
    Set-Location $src
    # cmd preserves native exit status and sends Gradle's native stderr to the log on PS 5.1.
    & cmd /d /c ".\gradlew.bat -p desktop --console=plain --max-workers=1 -Dorg.gradle.jvmargs=-Xmx6g createDistributable > `"$out\package.log`" 2>&1"
    $report.buildExit=$LASTEXITCODE
    if($LASTEXITCODE -ne 0){throw "createDistributable failed: $LASTEXITCODE"}
    if((Get-ScheduledTask -TaskName BiliPai-MacTest).State -eq 'Running'){throw 'measurement began; defer staging'}
    $package="$src\desktop\build\compose\binaries\main\app\BiliPai Windows"
    if(!(Test-Path "$package\BiliPai Windows.exe")){throw 'distributable missing'}
    $target="$root\apps\TON1020"
    # Keep the previous package for rollback; never delete another card's data.
    if(Test-Path $target){Move-Item $target "$root\apps\TON1020-before-TON1047"}
    Copy-Item $package $target -Recurse
    $native="$src\desktop\native\windows-x64"
    $runtime="$target\app\resources\native\windows-x64"
    New-Item -ItemType Directory -Force $runtime | Out-Null
    Copy-Item "$native\*" $runtime -Recurse -Force
    foreach($name in @('libmpv-2.dll','bilipai-diagnostic-share.dll')){
        if(!(Test-Path "$runtime\$name")){throw "native runtime missing: $name"}
    }
    Copy-Item "$PSScriptRoot\Start-Isolated.ps1","$PSScriptRoot\run-profile.cmd" $target -Force
    $report.exeSha256=(Get-FileHash "$target\BiliPai Windows.exe" -Algorithm SHA256).Hash
    $report.exe="$target\BiliPai Windows.exe"
    $report.javaHome=$jdk
    $report.files=@(Get-ChildItem $target -Recurse -File | ForEach-Object {
        [ordered]@{path=$_.FullName.Substring($target.Length+1);length=$_.Length;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash}
    })
    $report.passed=$true; $report.stage='ready-without-client-launch'
} catch { $report.error=$_.Exception.Message }
finally { if($leaseOwned){Remove-Item "$root\tools\job.lock" -ErrorAction SilentlyContinue} }
$report.ended=(Get-Date).ToString('o')
$report | ConvertTo-Json -Depth 8 | Set-Content "$out\package.json" -Encoding UTF8
if(!$report.passed){throw $report.error}
