# Actual product lifecycle proof plan — snapshot pending

This new lane is independent of the frozen diagnostics producer/review. It never
changes Main, Shell, shared Gradle, the manifest, or previous evidence. It will
compile fixture sources only against Root's next immutable three product jars,
first on the classpath, followed by the already pinned runtime dependencies. No
diagnostics/store/runtime/renderer class will be overridden. Until the snapshot is
received, this document is a plan and source review, not a product execution claim.

Current integration base is 416d5e3. Root installed the ten producer files and the
separate two boundary deltas; the Main/Shell lifecycle seams are being prepared.

## Source seams to verify after Root freezes them

1. Main's retained global applicationPluginStore is constructed once. NetworkProxyStore
   and diagnostics use that same object. Diagnostics initialize/install their
   bridge before Repository and native-player construction. No guest/account
   facade, independent mirror consent key, or fake successful false result.
2. Main retains the diagnostic Result, a fixed visible startup failure, and a
   nullable consumer lifecycle. Its initial shutdown callback works before a
   ReadyApp effect is installed. The startup storage failure branch has no Runtime
   and drains diagnostics before freezing the global backing.
3. Preserve current `DisposableEffect(startupGuard)` registration. Reintroducing
   the old frozen snippet's every-recomposition parent SideEffect could replace
   the ReadyApp shutdown callback and is not part of this integration.
4. ReadyApp tracks both existing actual native player state and native audio player
   state through the same retained lifecycle. Account/page changes do not install
   another consumer. Late observer registration is refused after retirement.
5. Backup and normal close stop source ownership/casting, cancel and join diagnostic
   observers, close/drain the actor, and only then retire SearchPreferences/Runtime
   stores. Runtime's new optional beforeStoreFreeze hook invokes that same lifecycle
   under the single actual shutdownMutex, including the `onDispose -> Runtime.close`
   path. An actor timeout/failure leaves Runtime stopped=false and stores writable
   for a deliberate retry; it must not be caught and followed by archive overwrite.

## Current lock review

The actual observer reads PlayerState and calls synchronous actor.record; it has
no Runtime callback. The actor serial file/settings jobs also have no Runtime
callback. Therefore single-product Runtime shutdownMutex -> diagnostic lifecycle
mutex -> observer join -> actor IO close has no inverse edge. Explicit shutdown
can invoke the lifecycle first and the Runtime hook can invoke it again after
completion. Joining must be cancellable internally but enclosed by the product
NonCancellable retirement boundary. Do not hold a PluginStore backing monitor
while awaiting the actor: its accepted settings job can be waiting for that monitor.

This review does not expand the product guarantee to multiple concurrent Runtime
instances or replacement global Store generations while the application remains open.

## Executable cases after the immutable snapshot arrives

Use actual product DesktopDiagnosticLifecycle, DesktopDiagnostics, both existing
log bridges, DesktopPluginStore, and DesktopPluginRuntime. Each Runtime scenario
runs in a fresh JVM because the original PluginManager and scope registry are
process singletons. A verified current worker resource directory is supplied through
the product's `bilipai.js.workerResources` property; no worker/schema override or
unverified fake runtime is used. Creating the Runtime must not start its worker or
LAN discovery. All environment/home/settings directories are task-owned and short.

- **Same backing/cold authority**: actual persisted consent is resolved before the
  first bridge event; a fresh facade reads the same key. Off detail remains absent,
  fixed startup/W/E remain local. A malformed object/array consent value must give a
  failed product initialization Result with a safe visible state rather than a
  falsely successful disabled consumer. Visibility can be proven with the actual
  product failure-state Composable only if Root exposes one; otherwise it remains
  a source contract plus actual failed factory result, explicitly labelled.
- **Two observers**: feed offline MutableStateFlow<PlayerState> values to the
  actual product observer API. Both actual Jobs are cancelled/completed before actor
  shutdown returns. This is the real observer implementation with offline input,
  not native mpv decoding, an HWND, or actual first API request.
- **Accepted writer barrier**: use the actor's supported ExecutorService parameter
  with a real gated single-thread executor. Enqueue an actual accepted consent/file
  action, start lifecycle/Runtime shutdown, and assert that shutdown has not returned
  and the global backing has not frozen until that real write is released and
  persisted. Observers must join before the gate is released; a late observer or
  actor record is rejected rather than queued onto a retired generation.
- **Runtime close bypass and repeated invocation**: run the actual product Runtime.close
  path with its beforeStoreFreeze hook, then repeat the explicit shutdown. Assert
  the hook drained before the global backing rejected writes, with no second
  consumer or mutation after retirement. Only one Runtime is constructed per JVM.
- **Hook failure/retry**: an intentional first hook failure prevents actual Runtime
  store.freezeWrites and stopped=true. A same-instance explicit retry drains and
  freezes, and later calls are idempotent. This verifies the real Runtime API, not
  a fixture imitation of Runtime ordering.

The fixture will use latch/state/actual-job completion conditions with bounded
timeouts, not arbitrary sleeps as evidence of acceptance. Reports preserve the
initial failure if a real product boundary fails. No HTTP/account operation,
native window, chooser, Firebase, fatal native crash, packaged EXE, or full Main
Window startup is claimed. Main/Shell startup order is a pinned source contract;
the actual compiled lifecycle and Runtime hook supply the executable barrier proof.
