# Effective local diagnostics slice — prepared, not installed

This directory is an isolated Windows adapter and executable evidence. Main,
Gradle, user settings/logs/accounts, native windows, and all prior frozen cohorts
were untouched. No network client, message sending, log upload, or Firebase SDK
is introduced. This is **local logging parity**, not complete Diagnostics/Firebase
or a packaged Windows acceptance.

## Original authority and sources

`source-inventory.json` has four original LF source identities; merge their features
into existing manifest identities rather than duplicating SettingsManager/Sections.
`extract-upstream-diagnostics.py` emits six files. One missing original XML icon,
`ms_pest_control_24`, is generated through the existing strict vector converter;
all existing semantic/export vectors and category card/divider helpers are reused.
No new dependency is needed.

- `Logger.kt`: entire pure region (paths, byte rolling, capture/persistence decisions,
  export eligibility, crash builder); original complete sanitizer and original
  LogEntry, add/dedupe/truncation/ring eviction/get/count/clear bodies. Only the
  collector type, clock/file sink, and Windows crash header label are adapted.
- `SettingsManager.kt`: original `enhanced_diagnostic_logging_enabled` key, false
  default, getter and setter. The original DataStore becomes the existing global
  DesktopPluginStore `settings` namespace. Atomic persistence is already real in
  DesktopPluginStore. The synchronous cold read uses **that same key**, so Windows
  does not maintain the Android second `diagnostic_logging/enhanced_enabled` mirror
  as another competing authority. The original mirror constants remain in the
  pure source for attribution; no dummy second store is created.
- `SettingsSections.kt`: exact effective enhanced switch, explicit original consent
  confirm/cancel, and original export row. Runtime/read state comes from the real
  retained consumer. Proxy belongs to the existing proxy adapter. The original
  crashTracking/analytics rows are excluded; full original section is reference-only.
- `CrashReporter.kt`: `shouldPersistLocalCrashSnapshot` always true independently
  of Firebase consent, reused for strictly local snapshots. No Crashlytics stub.

Original limits retained: 1000 entries, duplicate suppression 250ms, message cap
16Ki characters, basic64KiB and runtime256KiB UTF-8 rolling files. New Windows
local-export/crash limits are explicitly platform limits: export512KiB,
crash256KiB. Export reads each disk file with a fixed byte cap and sanitizes again.
The original sanitizer is unchanged; a prior Windows pass removes complete network
URLs, Windows user-home paths and additional JSON/header secret forms.

## Files to install and bridge patch

Place the five `Desktop*.kt` platform files from this directory under
`desktop/src/main/kotlin/com/bilipai/desktop/diagnostics`:

- DesktopDiagnostics.kt
- DesktopDiagnosticSettingsSection.kt
- DesktopLocalDiagnosticViewer.kt
- DesktopDiagnosticRuntimeBindings.kt
- DesktopDiagnosticFileChooser.kt

Register the extractor/task/sourceSet plus the four source/one resource inventory
entries using Root's existing workflow. Do not install fixtures or `.local` classes.
`bridge-integration.patch` is only the existing UpstreamLog and UpstreamLogger
replacement; `bridge-baselines.json` has exact byte/LF base and desired hashes.
Apply-check succeeded against the captured current main files. Recheck before apply.
Logger.d no longer discards opted-in upstream diagnostics; both bridge shapes call
the sole consumer. No raw Throwable is passed to JUL/console by these bridges.
Before the retained consumer is installed, bridge messages are intentionally dropped.
That makes the startup seam essential, not optional for actual parity.

## Exact Main/Shell seam

The current Main already retains `applicationPluginStore` and initializes original
NetworkProxyStore before constructing DesktopRepository. Immediately after that
same retained store and before repository/player/Runtime construction:

```kotlin
val diagnostics = remember(applicationPluginStore) {
    DesktopDiagnostics(applicationPluginStore,
        runCatching { com.bilipai.desktop.update.DesktopUpdater.packagedVersion() }
            .getOrDefault("unpackaged-development")).also {
        DesktopDiagnosticsBridge.install(it)
    }
}
```

The existing packaged-version reader is used; an unpackaged development fallback
is explicit. Pass the consumer to DesktopApp through
a compatible nullable tail parameter and retain it across account/page changes.
Do not create an account-specific logger or read cookies/session/library contents.
Its only private paths are the original known names under:

`DesktopLibrary.directoryForAccount(null)/logs/{basic.log,runtime.log,last_crash_log.txt,pending_crash.marker}`.

Initialization synchronously resolves consent before queueing the first fixed
startup stage. Subsequent startup stages must match `[a-z0-9_]{1,64}`; pass fixed
constants, never URLs/routes/user-entered strings. Construction/files are task-owned
in evidence; no user's LOCALAPPDATA has been read by this slice.

Settings content:

```kotlin
DesktopDiagnosticSettingsSection(
    diagnostics,
    onLocalLogs = { showDiagnosticViewer = true },
    onFailure = { /* Root safe UI error; don't echo exception paths */ },
    modifier = Modifier.fillMaxWidth(),
)
if (showDiagnosticViewer) DesktopLocalDiagnosticViewer(
    diagnostics,
    onDismiss = { showDiagnosticViewer = false },
    chooseExportPath = { chooseDesktopDiagnosticExportFile(hostWindow) },
)
```

The viewer uses original AppAlertDialog/AppDialogAction/AppText. View, explicit export,
and clear call the actual consumer. The chooser is a cancellable Swing adapter and
is invoked only by the real export click. Export uses CREATE_NEW: existing chosen
files are never overwritten; a failed choice shows a safe error. There is no share,
email, feedback submission, automatic export, or upload.

For actual player-state/request-result coverage rather than merely mounting UI:

```kotlin
val videoLogJob = diagnostics.observePlayback(nativePlayer.state, retainedScope)
val audioLogJob = diagnostics.observePlayback(nativeAudioPlayer.state, retainedScope)
```

The real API accepts StateFlow<PlayerState>. Only finite playback flags, validated
codec/hw tokens, failure kind/native code are recorded. Titles/URLs/subtitles/tracks/
error messages are excluded. No fake native player is constructed. New listener
jobs must be cancelled/joined before shutdown; page disposal must not close the
process consumer. Existing upstream request policy logging automatically flows
through the patched bridge. Proxy failures may be displayed as the existing actual
AtomicLong counter; never recover raw URI/address/error-message logging.

For optional actual JVM uncaught capture, install DesktopDiagnosticUncaughtHandler
once in Main with the prior handler; preserve and restore its identity at shutdown.
It does not itself install any global handler. Tests invoked it on one task-owned
Thread and proved local snapshot + prior handler chaining. JVM fatal/native crash
termination coverage has not been validated. Android process-exit/profiling APIs
are not available and no fake empty successful consumer is supplied.

## Restore and exit ordering

Current Shell `registerShutdown` and BackupUi restore callbacks both call
`pluginRuntime.shutdownForRestore()`, which freezes the shared store. Before that:

1. Cancel/join the retained diagnostic playback observer jobs.
2. `diagnostics.shutdownForRestore()` — rejects new work, retires only its own bridge,
   drains every accepted record/settings/export/clear action on the sole writer.
3. Existing SearchPreferences/PluginRuntime store freeze and actual restore/exit.

Accepted actions remain independent of a page coroutine waiting for them. A closed
facade rejects new settings/view/clear/export work and new records return false.
Repeated close still awaits the actual writer rather than treating an earlier timeout
as successful drainage. A timeout must block restore; do not catch it and proceed
with files still being written. No hot new Store is created by this slice.

## Proven and pending

`compile-evidence.json`: 15 isolated Kotlin sources on the current frozen product
three jars +231 fixed runtime dependencies, using the product module/friend ABI.
Active classes are **classes-stable**. `classes`, `classes-final`, `classes-verified`
are earlier diagnostic attempts, inactive and not the installed application.

`proof/result.json`: fourteen executable real-file/Store/bridge/actor cases. These
are not JUnit counts or a fresh packaged/native acceptance. Includes off queued
old/new detail no resurrection, ring/UTF-8 caps, actual atomic-write failure,
restore pending-write drain, local crashing Thread+previous handler, and a finite
PlayerState observer with explicit **offline input**. `proof/fresh-jvm.json` is one
additional fresh JVM test proving cold same-key consent restore before a detail log.

`ui-proof/result.json`: two styles, eighteen actual Press/Release click pairs total:
original consent cancel/confirm, switch off, export row routing, local viewer's
real export/clear/close. Actual exported files and private-file deletion were checked.
The export destination was a fixture callback, **not the OS save-dialog HWND**.
No AppDialog replacement or alternate visual model was used. Rendered PNGs were
visually inspected; native popup/focus/OS chooser and packaged EXE remain untested.

`source-contract-proof.json` verifies full original pure/sanitizer/add body identity
and documented adapter boundaries. All original source/resource LF hashes and fixed
dependency identities are checked by `verify-frozen.py` using long-path-safe reads.

The old updater's `launch-*/startup-output.log` and `startup-error.log`, ffmpeg
per-download `merge.log`, worker/process outputs, and any independent JUL producers
are separate evidence paths. This consumer does not blindly read/export/delete them
or claim their existing raw output is sanitized. They need their own bounded and
redacted consumers before a future full-log export includes them.

Crashlytics collection/anonymous daily-active statistics remain **missing actual
Windows consumers**. Never pass defaulttrue fields or mount fake enabled switches
to label the whole original DiagnosticsSection complete.
