# FSR post-integration guard review

Read-only review found one further actual stale-enable path: the original manager
waits for its own pluginStateMutex after the Runtime checks its ownership predicate.
The separate historical reproduction in ../fsr-post-integration-review showed a
retired same-video toggle publishing and persisting enabled=true. No main file was
edited by this agent.

Root fixed that path in the original manager extractor: optional stillOwned with
the original default true; check inside the original pluginStateMutex; after the
original onEnable returns, a retired operation calls the original onDisable and
returns before logs/hint/state/store. Runtime forwards the same predicate and
returns after a retired manager no-op. No alternate provider, config schema, or
parallel plugin switch was introduced.

## Exact current-main verification

- Current full snapshot: 6176 Kotlin classes, SHA-256
  `f864fc80a93a58c54235988c3cc4cdfd2140e417f44ec26f3c7187c77b981883`.
  141 actual Java classes and 42 resources are separately frozen in baseline.json.
  Every class path was read with the extended Windows prefix and inventories were
  identical before and after copying.
- Original 16 fixtures replayed: 14 config/session, 1 original settings pointer
  case covering both Material3 and Miuix, 1 complete actual Runtime lifecycle case.
  They all passed against the new ABI. Old frozen16 evidence was verified before
  preparing this cohort and remains unchanged.
- Manager-lock diagnostic passed: actual Session on→off retires the same supplied
  guard while the original manager mutex is held; after release the original
  provider is neither published nor persisted enabled. Real settings file bytes
  and original effect hint remain unchanged.
- Actual onEnable/config-read diagnostic passed: a fixture thread holds the same
  real DesktopPluginStore backing monitor. Recorded BLOCKED thread stack proves
  DesktopPluginStore.snapshot → PluginStore.getConfigJson → original Anime4KPlugin
  .loadConfig/.onEnable → PluginManager → DesktopPluginRuntime. After the on→off
  retirement and release, neither state, hint nor actual disk changes. There is
  no fake provider or substitute store.
- Both diagnostics run in separate fresh JVMs. They are recorded as two narrow
  regression cases, not additional JUnit test-count inflation.

The first config-read diagnostic matcher incorrectly assumed the internal JVM
method suffix was $main; it timed out and is preserved in
diagnostic-first-config-read.log. javap showed the actual suffix
$com_bilipai_desktop_bilipai_windows. Matching the internal snapshot method prefix
while still checking exact Store/Anime4K class frames corrected the harness.
The final two cases and all original 16 fixtures were then rerun successfully.

## Lifecycle review

Root Shell passes the exact Session ownsToggle predicate through Runtime to the
original manager. Identity and epoch are rechecked before native install; source
and shader-configuration ownership guards remain intact. New BV/epoch retire
manual overrides and failed pipeline state. Ordinary, PGC and offline BV identity
are supported; PiP and host visibility flows feed the original output policy.

Normal close/restore first closes enhancement and playback owners on the UI
dispatcher, then quiesces cast, awaits Runtime.shutdownForRestore and preference
writer flush. Runtime drains accepted enhancement changes before shutting down
the global scope registry and freezing the original store. Main awaits that
registered shutdown before closing the native player or exiting/restarting.

No additional evidenced FSR lifecycle/configuration error remained in this
review. No shared Gradle, HWND, GPU, real account request or main edit occurred.
This cohort does not add GPU/native EXE claims; Root owns the separate 19-gate
native acceptance and its exact input identity.

## Highest-value regular-test candidates from the original 16

1. nextSetterWaitsForTheActualOriginalIoChildAndDiskKeepsTheLatestFields, paired
   with shutdownDrainsAlreadyAcceptedPendingSettersBeforeFreezingTheRealStore:
   retain actual original provider jobs and real temporary disk, not timer-only
   or in-memory save assertions. If split into normal tests, context injection
   into the original global manager must be serialized and fixture-owned jobs
   cancelled afterward; do not shut down the process-wide registry here.
2. disablingSameVideoWhileProviderEnableIsSuspendedRetiresTheActualPassedGuard:
   keep actual unmounted MpvPlayer source tokens plus same-BV override/epoch/
   foreign-owner variants. This session-only fixture can be a normal isolated
   JUnit test without native DLL/window or global Runtime shutdown. The two new
   nested-lock diagnostics extend the same guard contract and deserve a separate
   regression slot rather than mirrored implementation assertions.
3. originalRuntimeRegistersOneProviderRejectsLateEnableAndDrainsPendingConfigBeforeClose:
   preserve unique original instance, disabled restore, actual wait boundaries,
   pending latest-disk close and store freeze. Always run in a fresh JVM, because
   DesktopPluginScopeRegistry.shutdown is deliberately process-terminal and
   PluginManager holds global context/registration state. Creating another
   provider in that same JVM would incorrectly test a cancelled IO scope.

The actual two-style pointer case is suitable for a separate offscreen UI job;
its confirmation substitutes only the OS dialog primitive, so retain that scope
description instead of labelling it an OS popup acceptance test.

Reproduce with compile-run.py manager config-read logic ui runtime. Each group
runs in a fresh JVM using the frozen current-main classpath and standalone
Kotlin/Compose compiler; shared Gradle is not invoked.
