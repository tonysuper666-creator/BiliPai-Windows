# Reviewed local diagnostics integration

This lane never writes product files, runs shared Gradle, creates native windows, or
reads user settings/accounts. The frozen 322-artifact producer remains unchanged.
It contributes local diagnostics, not Firebase, Android process-exit diagnostics,
an OS save-dialog acceptance, or a packaged EXE acceptance.

## Installation order and exact scope

1. Verify this review's `frozen-handoff.json`, then run `install-reviewed.py`
   without arguments. Its default is read-only. The 10-file plan is pinned to
   `21c8a7374353858a6ecd29bcdb2d1210db69c141e15baeda90363367ba9ce047`.
   Only Root's explicit `--apply` installs the reviewed bytes.
2. Apply the separate `boundary-delta` two-file plan after those exact bytes.
   Its baselines are the original producer DesktopDiagnostics/Viewer payload,
   never an earlier main filename or unverified live build output.
3. Root merges the Main/Shell snippets, preserving the current startup storage
   guard, retained global store, account lifecycle, settings tree, and shutdown
   routes. Main/Shell are deliberately absent from both automatic installers.
4. Root compiles/generates and verifies the integrated product; the isolated proof
   here is not a replacement for that final compile or actual startup wiring.

Eight producer files are verbatim: five platform Kotlin files, two existing log
bridge replacements, one new extractor. Two Root-owned build inputs are reviewed
payloads: Gradle and source manifest. Fixture sources/classes are never installed.

The final plan captures the **current 431/209** manifest after Root's binary-resource
repair. Its union is **433/210**: only Logger.kt and CrashReporter.kt add source
identities; SettingsManager.kt and SettingsSections.kt keep their existing modes
and all features. One original `ms_pest_control_24.xml` is new. All eight PNG
`hashNormalization=raw` entries survive unchanged; the new XML uses the existing LF
default. The earlier 427/201 baseline and an intermediate pre-raw 431/209 draft are
obsolete. If Root changes any installed target, the installer must reject it and
the plan must be reviewed again rather than overwrite that change.

The sole new task is `extractUpstreamDiagnostics`, CLI:

```
python tools/extract-upstream-diagnostics.py --repo <repositoryRoot> --output <build/generated/diagnostics/sources>
```

It depends on prepareUpstreamSources and extractUpstreamSettingsCategories, declares
all parser/helper/source/XML inputs, and owns `generated/diagnostics` exclusively.
Only `generated/diagnostics/sources` is a Kotlin sourceSet. The generator's original
reference goes to `generated/diagnostics/reference-only/OriginalDiagnosticsSection.kt`.
Using `generated/diagnostics` directly as its output would collide with the existing
network-proxy task's `generated/reference-only/OriginalDiagnosticsSection.kt`; this
plan avoids that actual overlapping output without changing the frozen generator.
No source-copy duplication, duplicate FQN, new dependency, or binary resource-copy
task is introduced. All six regenerated Kotlin outputs match the producer's bytes.

## Original source, consent, and file consumers

All 322 producer bytes, 234 historical dependency identities, four original LF
sources, one LF XML, and both unchanged bridge baselines were verified. GPL-3.0
root LICENSE bytes and all six existing fixed appearance notices are checked by
the installation plan. No additional dependency was resolved. The XML is the exact
asset from the GPL repository; independent upstream Material Symbols provenance
is not newly asserted by this lane.

The original key `settings/enhanced_diagnostic_logging_enabled` is the only effective
authority, false by default. Main must pass its retained `applicationPluginStore`
to the consumer. Root's settings UI must call that same retained consumer setter.
No account facade and no `diagnostic_logging/enhanced_enabled` mirror is used.
Within this current product path only that consumer changes the key; its state is
published after actual atomic persistence. Restoring an archive quiesces and exits,
and must not hot-create another consumer on the old backing.

The bridge replacements route upstream android.util.Log and core.util.Logger
methods through the same serial consumer. Raw Throwable content is sanitized before
collector/disk publication and again on local export. Root should pass a fixed safe
UI error in `onFailure`, never `exception.message` (a failed file operation can
contain a path). The actual viewer reads bounded known files and exported real file
bytes; clear removes only the four original known diagnostic filenames. The actor's
error flow contains an exception class, not raw paths/messages.

The two separately reviewed deltas fix demonstrated platform failures:

- Initial viewLocal failure is caught inside the real Compose LaunchedEffect,
  displays a safe error, and leaves the original dialog close action usable.
  CancellationException is still rethrown, rather than treated as a successful
  empty log or a user-visible read failure.
- Every private-file operation reuses current UpdateStorage.existingPathWithoutLinks
  before directory creation, verifies the resulting directory, and checks existing
  files. Real Windows junctions and redirected ancestors are rejected for source
  reads, export source reads, and clear. This is the same existing path guard, not a
  separate invented Windows link detector or a claim of race-proof handle IO.

## Lifecycle seams that Root must actually install

`ROOT-MAIN-SHELL-SNIPPETS.kt.txt` is based on the current guarded Shell. Create the
consumer once from `applicationPluginStore` before Repository/player construction,
install its bridge, and pass it through nullable-compatible tail parameters of
DesktopApp and DesktopReadyApp. DesktopDiagnostics is internal, so the current
public DesktopApp must become internal before its signature can accept that type;
Main and same-module/friend fixtures keep access. The default shutdown callback in Main must already
drain it before any ready-page effect runs. The startup storage failure branch has
no Runtime and also needs diagnostic shutdown before its global-store freeze.
The synchronous typed consent read can reject a malformed JSON object/array value
before the consumer error flow exists. Root should retain the supplied Result and
display the fixed diagnostic initialization error; do not convert a failed read to
a fabricated working false consent state or expose the exception message.

ReadyApp observers use the actual existing `player.state` and `audioPlayer.state`.
Keep them stable across account/page changes. Cancel and join both observers before
draining the consumer; drain it before SearchPreferences/Runtime/global backing
freeze in both restore and normal shutdown. Page disposal never closes this
process consumer. A writer-drain timeout blocks restore; catching it and continuing
would violate the existing accepted-write barrier. Repeated close awaits actual
termination; bridge retirement only removes the same instance.

Installing DesktopDiagnosticUncaughtHandler is optional but requires an actual
single process handler and prior-handler restoration. The frozen fixture proved
one crashing task Thread, a local snapshot, and prior-handler chaining. It did not
prove recovery from JVM fatal/native crashes. The previous handler and unrelated
JUL/process producers retain their existing output behavior.

SYSTEM_ABOUT uses the actual retained consumer, original effective enhanced consent
controls, and local viewer. There are no fabricated crashTracking/analytics switches.
Updater startup stdout/stderr, merge.log, JS worker outputs, DesktopPluginLog, and
independent JUL producers are outside these two patched bridges and are not blindly
exported or described as covered by this consumer.

## Evidence and limits

`installer-proof.json`: nine actual temporary-disk rehearsals, including read-only
verification, exact installation/idempotence, baseline/source/payload refusal,
rollback, late Root edit refusal, path traversal refusal, and the exact sequential
two-file delta (rejected before producer install, idempotent afterwards). No product apply.

The frozen producer has fourteen executable disk/actor cases, a separate cold JVM
consent case, and eighteen actual pointer pairs across the original two styles.
They remain historical evidence against the prior network-proxy snapshot; they
are not retold as new JUnit cases or the current integrated application.

`boundary-delta/compile-and-run-proof.json` pins the immutable foundation's three
product jars first on the classpath and the existing 231 runtime jars. Two isolated
15-source compiles explicitly replace only the diagnostics/bridge classes plus
two fixtures; no original renderer is shadowed. Original and corrected JVMs both
use task-owned filesystem victims and environment directories.

Each mode runs two real Windows-junction cases (logs directory and ancestor), an
ordinary real-file/consent/Throwable case, and the original M3/Miuix dialog failure
path. The original failure escaped; the reviewed version rendered a safe error and
both actual close pointer pairs succeeded. The two reviewed PNGs were inspected:
text, controls and rounded dialog boundaries are fully visible.

The first isolated attempt used an overly long task-owned user.home and Skiko
could not locate its ICU file. Its native error log remains in this cohort. The
second attempt uses `work/.local/dg-env`, and both complete runs pass. This is an
environment-path correction, not a modified renderer or suppressed product error.
No HWND, OS chooser, real account/request, shared Gradle, TLS, full Main first
request, native fatal-crash recovery, or packaged runtime is claimed here.
