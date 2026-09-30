# Root FSR configuration / lifecycle proof

16 JUnit tests passed in isolated fresh JVM groups: 14 configuration + session,
1 original settings UI case exercising both Material3 and Miuix, and 1 actual
DesktopPluginRuntime lifecycle case. No shared Gradle, HWND, DLL initialization,
GPU rendering, account request or account setting was used in this cohort.
Main sources were read only.

## Exact inputs

`complete-product-main-snapshot.jar` contains all 6176 current main Kotlin class
files, with extended Win32 paths and matching inventories before/after copying.
SHA-256: `05991401c6f6d8085a1c7b7325f56ef644fc397732ad92c7dd2fa02c86a428d9`.
The 141 actual Java classes and 42 product resources have their own immutable
snapshots and full identities in `java-resource-identity.json`.

`DesktopVideoEnhancementSession.kt` is compiled as an exact source override:
`5b84b94455c159199d5e9dc9f331d8f5cc44e5ef8a417cda6a4bda75812e8fec`.
This preserves Root's epoch and guarded-enable callback corrections. All other
product logic uses actual Root product classes, including the original generated
Anime4KPlugin, PluginManager, PluginStore, config schema, math and output policies.

`complete-baseline.json` records original source, generated source, extractor,
Root Shell/Main and native-player hashes. `baseline.json` and the initial
6158-class snapshot are deliberately preserved as historical inputs: the first
plain-path inventory omitted 11 long-path classes. The final 16 tests were all
rerun against the complete extended-path snapshot; the initial snapshot is not
on the final runtime classpath.

## Meaningful checks

- Disabled provider restores the original saved config without enabling it.
- A held original provider IO child blocks the next setter. The actual original
  PluginStore writes an atomic file; intermediate and final persisted fields are
  checked. Fixture reflection swaps only the provider's IO dispatcher/scope into
  an owned, gated scope registered with the real registry.
- A real Windows atomic-file failure is reported; a following changed setter
  succeeds and persists the combined fields. The expected AccessDeniedException
  in logic-tests.log belongs to this intentional failure case.
- Accepted changes survive closing. Both the configuration adapter and complete
  Runtime wait for pending actual IO before freezing the actual store; a new
  store generation reads the latest config from disk.
- Actual Runtime registration yields exactly one original Anime4KPlugin, identical
  to runtime.videoEnhancement/getInstance. Original optional casting providers
  are explicitly disabled in the fixture's temporary store to avoid LAN scans.
- Real awaitPluginReady and real Runtime playerMutex are held separately; a
  retired ownership predicate cannot enable the provider after either wait.
- An unmounted actual MpvPlayer supplies real source ownership tokens. Controlled
  coroutine scheduling verifies stale BV, epoch, foreign source, true-to-false
  override, closed bind and cancelled pending toggles. The actual enable exception
  becomes safe session state, and a new BV clears the failed pipeline flag.
- Injected flow inputs verify the original PiP, visibility, audio-only, HDR/Dolby
  and source guards. Zero dimensions/unready player prevent shader installation;
  those inputs are not presented as actual GPU capability evidence.
- The original generated settings content uses original App* controls in both
  styles. Real pointer press/move/release switches algorithms and presets, drags
  FSR to 30%, cancels/confirms remembering and turns it off. Final original config
  and actual disk agree; the provider stays disabled. Only the confirmation
  dialog's OS window primitive is replaced with an AppPopupSurface to stay
  offscreen. Six PNG captures are in `ui/`.

## Reproduction

Run `compile-run.py logic ui runtime` using the bundled Python runtime. The
compiler is standalone Kotlin/Compose 2.4.0, with the fixed Miuix source artifact
and Material3 1.12.0-alpha03; it never invokes shared Gradle. Runtime shutdown is
process terminal, therefore `runtime` always runs in a fresh JVM, separate from
the configuration and UI groups. `verify-frozen.py` checks every frozen artifact
with long-path-safe byte reads.

All four read-only findings were communicated before Root fixed them: closed
bind, new-BV failure retirement, epoch checks, and the shared Session-to-Runtime
ownership predicate for late enable/override. No remaining product defect was
demonstrated in the final cohort. This does not claim validation of the new
Root EXE/GPU path, physical HiRes audio output failures, or actual OS dialogs.
The previous frozen FSR 11-gate native evidence and 50 playback foundations were
preserved and were not rerun or rewritten.
