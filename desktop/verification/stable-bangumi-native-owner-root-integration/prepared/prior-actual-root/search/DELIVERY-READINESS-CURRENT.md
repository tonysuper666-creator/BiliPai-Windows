# Existing Windows delivery path

This is a read-only review of the existing source at `529a79eb3182f51f449f4bbf5f66ff615113e248` (full Search installed after Comment and Storage). It does not build, deploy, change a version or create another packager. Final packaging must use Root's final clean source commit after integration fixes.

`desktop/upstream-sources.json` still has `windowsRevision=1`. Upstream Android versionCode is 415. The current client/update/ZIP version is therefore `0.2.415.1`; the Compose launcher/installer package version is `0.2.415`. The existing user desktop v0.2.3 folder already contains a `0.2.415.1` package from source `6fd5bbd804f272650942f5a445385ba5428ffa9b`. Root should increment the real manifest revision before a new update release. A suffix on the desktop directory alone does not change the updater version. If the revision remains unchanged for an internal test build, first preserve the existing same-name ZIP/checksum and its receipt because `build.ps1` replaces those two paths.

The existing minimum portable build command is:

```powershell
$ErrorActionPreference = 'Stop'
$taskCandidate = 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/BiliPai-v023'
$taskToolchain = 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/toolchain'
$taskJava = Join-Path $taskToolchain 'jdk/jdk-21.0.12.1+1'
$env:GRADLE_USER_HOME = Join-Path $taskToolchain 'gradle-home'
$env:PYTHON_EXECUTABLE = 'C:/Users/TONYS/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$env:BILIPAI_NATIVE_SHARE_VC_ROOT = 'C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC/14.44.35207'
& (Join-Path $taskCandidate 'desktop/tools/build.ps1') -JavaHome $taskJava -SkipTests -NativeSmoke -NativeMuxSmoke -UpdaterSmoke
```

Save this invocation into a new attempt's transcript/log; preserve all prior reports. Native diagnostic producer remains the existing standalone task `gradle -p desktop prepareNativeDiagnosticShare`, invoked by the production build dependencies. It must use the approved current source/headers/compiler/pin and freshly verify its DLL. Current expected diagnostic DLL SHA is `22b3636176561782b055345f3c663b5674247bd9cda37f5d94945886f60dd7d3`; libmpv SHA is `673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4`. The existing mpv/FFmpeg native files and download archives are already cached; no new dependency or native downloader is required for this command.

`-SkipTests` skips the general test execution in the initial Gradle invocation. The requested smoke tasks still compile their test sources and execute their own tests. `-NativeSmoke` runs the packaged launcher with its existing native self-test flag; that early Main branch does not prove the ordinary Root mount or live Bilibili playback. `-NativeMuxSmoke` must decode all six packaged FFmpeg outputs and match packaged tool SHA. `-UpdaterSmoke` must match the exact package version and ZIP SHA, run the ordinary packaged Main in the test's owned isolated LocalAppData/temp directories, and finish all owned children. These smokes do not imply account playback, full feature parity or a public release. MSI and ReleaseGate are outside this minimum command.

Optional existing real previous-EXE boundary:

```powershell
& (Join-Path $taskCandidate 'desktop/tools/build.ps1') -JavaHome $taskJava -SkipTests -NativeSmoke -NativeMuxSmoke -UpdaterSmoke -PreviousUpdateTestPackage 'C:/Users/TONYS/Documents/Codex/2026-09-29/https-github-com-jay3-yy-bilipai/work/BiliPai/desktop/build/distributions/BiliPai-Windows-0.2.406.5-x64.zip'
```

This original previous ZIP has SHA `178f5c4dc584853d2da5e2733998987a1a2d13ded21e4d650369aeba1139a3e3`. Its actual forwarding to the old source-6fd current package already passed in `Main desktop/.local/stable-updater-real-previous-forwarding83/forwarding-summary.json`. That historical result does not prove a newly generated package. Never fake its cfg/version or launch the user desktop copy for this smoke. If only the new updater boundary needs verification after an already validated package, invoke the existing standalone `updaterSmoke` with `-PupdateTestPackage=<new ZIP> -PupdatePreviousPackage=<original old ZIP> -PupdateTestReport=<new owned report directory>`; do not repeat packaging/native mux for that single boundary.

Actual outputs:

- Native app image: `Candidate desktop/build/compose/binaries/main/app/BiliPai Windows/`, including root `BiliPai Windows.exe`, `app/`, and bundled `runtime/`.
- Archive: `Candidate desktop/build/distributions/BiliPai-Windows-<windowsVersion>-x64.zip` and `.sha256`.
- Real version metadata: `desktop/build/resources/main/windows-update.json`, and the same entry in the packaged main product JAR. Record the actual final source commit separately; upstream commit is not the Windows implementation commit.
- Native player/mux/updater reports: new UUID directories below `desktop/build/reports/`.

Before deployment, validate ZIP CRC, checksum and all entries against the app image. Inspect launcher cfg and resolved packaging dependencies. The old validated package had 114 launcher classpath entries versus 101 Gradle runtime entries; transforms, additional packaging resources and the merged main product JAR accounted for the difference. Count equality is not a valid acceptance rule. Check every JAR's source/hash and every actual product class/resource against the final actual Java/Kotlin/resource snapshot, allowing packaging manifest metadata and documenting platform transforms. Retain any real duplicate-class conflict or unaccounted source as a failure.

Deploy into a newly owned desktop folder such as `C:/Users/TONYS/Desktop/BiliPai Windows v0.2.3 (<actualWindowsVersion>-<finalCodeHEAD8>)`; if it already exists, choose another explicit unique suffix. Both old `BiliPai Windows` and `BiliPai Windows v0.2.3` folders remain available. Extract the entire app payload while stripping the ZIP's outer `BiliPai Windows/` prefix so EXE is directly at the new folder root. Validate every member's resolved absolute destination remains under that new folder; reject path traversal, links, Windows reserved names and case collisions before extraction. Verify per-file byte length/SHA and exact payload count after extraction. Only then write `部署信息.json` with actual source commit/upstream tag/version/ZIP SHA and account testing pending. Do not merge any old JAR or copy any account data into this app directory.

The program's existing data authorities use LocalAppData independently of the EXE folder: `BiliPaiWindows` for Library/accounts/history/settings/caches, and `BiliPai/updates` for updater health/registries. Download/image chooser defaults may also use `user.home`. Verification children must own separate LOCALAPPDATA, APPDATA, USERPROFILE, Java user.home and Java temp roots; do not redirect the user's installed application or copy/delete current cookies, history, favorites, downloads or caches. Only the user's later deliberate launch of the new EXE should use the existing real account directories. Account playback and the previous HTTP 412 boundary remain a separate user test.

Minimum handoff is the final source commit, actual version, ZIP and EXE paths/SHA, packaged product/dependency-byte audit, three smoke reports, extraction receipt and new folder path. No account Cookie output is needed.
