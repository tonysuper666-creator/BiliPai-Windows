# Prepared original crash prompt slice; no main integration claim

Fixed original source: `app/src/main/java/com/android/purebilibili/MainActivity.kt`,
LF SHA `9100a8fbea8e7e581f642653cca5a18429d9a1c55ee0bf3189d5a6a2fe725d90`.
`extract-crash-prompt.py --repo <repo> --output <generated/sources>` emits exactly
two uniquely named Kotlin files. `--inventory` returns the single source row;
merge its feature into an existing identical row if one appears later. At the
read-only current manifest inspection MainActivity was absent. Do not replace
other manifest rows or resource hashNormalization fields. No new resources or
dependencies. Original GPL license remains the source license.

The original enum and both expression-body functions are copied byte-for-byte.
The original dialog text, AppAlertDialog, Text, AppDialogAction, labels, order and
external-dismiss callback are preserved. Only three Android Logger/state side
effect bodies become callbacks. IGNORE exists in the original policy but is not
an original UI button; none is invented. The historical original dialog remains
outside the compile source directory for review.

Install only the two `prepared/*.kt` into the existing diagnostics package and
the new unique extractor tool. Suggested unique producer `extractUpstreamCrashPrompt`:
inputs MainActivity plus extractor/platform generator dependencies, outputs
`desktop/build/generated/crash-prompt/sources` (only that directory added to main
source set), depends on existing upstream source verification. Keep
`reference-only` outside the Kotlin source set. Root owns Gradle/manifest/Main/Shell
changes and recalculates current baselines before installation.

## Actual retained owner and consumer ports

```kotlin
// Same Main-retained actor; never construct another actor/store/account facade.
val crashPrompt = remember(diagnostics) {
    diagnostics?.let { DesktopCrashPromptController(it) }
}
```

Inside the Root's existing actual appearance theme, mount once for the process
owner (outside account/page route conditionals):

```kotlin
crashPrompt?.let { owner ->
    DesktopCrashPromptHost(owner) { chooseDesktopDiagnosticExportFile(hostWindow) }
}
```

The snippet uses the existing actual DesktopDiagnosticFileChooser function.
This lane supplied a labelled fixture path port and did not operate an OS chooser.
The Host uses actual DesktopLocalDiagnosticViewer with `controller.diagnostics`;
no raw paths, new buffer, automatic upload or analytics/Firebase consent control.
Windows SHARE first reads the same actor's local exportable log, clears the
marker according to original policy, and then opens the local viewer. Export
requires a separate explicit user click and chosen file. Unlike Android's Intent
chooser, this path does not send a message or launch an external sharing app.
The export contains the local sanitized diagnostics and retained crash snapshot.

In the retained single Main/Shell shutdown path and Runtime `beforeStoreFreeze`
callback, retire this prompt owner before the same diagnostic lifecycle:

```kotlin
crashPrompt?.shutdownForRestore()
diagnosticLifecycle?.shutdownForRestore()
```

The controller uses a serial operations mutex and a synchronous generation
retirement gate. It waits accepted operations while refusing new work; the actor
then drains its accepted file queue before storage freezes. UI disposal retires
only the prompt owner, never closes the process actor. Existing held actor remains
usable by other diagnostics consumers. Do not construct a second concurrent
prompt owner or hot-replace actor/store generations. A new process/session owner
can prompt again after IGNORE because marker and snapshot remain.

Original handled=true is set at action acceptance. SHARE/DISMISS delete only
marker; IGNORE keeps it. Existing snapshot bytes are never deleted by prompt
actions. The separately explicit viewer "清理日志" remains its existing clear-all
user action, not prompt cleanup. If share's read fails, the original SHARE marker
cleanup policy still applies and a fixed safe error is shown. A disposed owner
whose share read has not completed cannot enqueue a new marker clear or open a
viewer from that late result; its evidence remains for next startup.

Windows adds fixed safe IO failure states and explicit retry, rethrowing coroutine
cancellation. Marker-clear failure preserves its obstacle/snapshot and exposes
"重试清理"; pending-reader failure exposes "重试读取". The original Android Logger
suppresses deletion/share failures, so these visible IO recovery controls are a
documented Windows platform addition.

## Executed scope

`actual-2` compiled four new unique prepared/generated source files plus one
fixture against the immutable final viewer product snapshot
`5065fe8052e80c0c73f6342a4afc03fd62814fb0a144b6de46feeb5ddce1d3ae`.
Three real product jars lead the classpath plus 231 verified dependencies; no
existing production class/renderer is overridden. Three fresh JVM scenarios
passed **98 fixture assertions** (not JUnit counts), including real queued actor
reads/close barriers and actual temp-disk marker/snapshot mutations. Two styles
passed **14 original pointer pairs**, including external dismiss, repaired reader
retry, original share/dismiss and same actor viewer explicit export. Ten 1080×900
PNGs retain the original dialogs and complete viewer footer; representatives were
visually checked. Three source-preserving extractor tests pass independently.

Each crash is a synthetic Throwable saved through the actual actor API; no fatal
native crash is induced. Chooser is an explicitly labelled fixture path port.
Zero network/listener/multicast/process attempts, no HWND, real account/settings,
shared Gradle, Main/Shell edits or frozen cohort changes. Existing actual product
actor/Windows path validation is reused, not a fixture implementation. Initial
`actual-1/compile.log` preserves a fixture-only nested-function formatting error;
the corrected `actual-2` ran all cases. No product source was changed for it.
