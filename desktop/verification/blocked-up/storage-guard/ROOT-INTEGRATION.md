# Discovery storage failure boundary — isolated handoff

Only the two new `prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/` helpers are production payload. They add a transient `Result<T>` UI boundary, not a preference schema, fallback store, or replacement repository. Do not install `marked-overrides`: those are the explicitly marked stage1 source byte identities used to reproduce the corruption. Root owns the current Preferences/Repository/Runtime restore-fence changes and must retain them.

The tested immutable product graph has three product snapshot JARs first, followed by 231 pinned dependencies. `dependency-identities.json` identifies all 234, checked before and after. `compile-evidence.json` identifies the 10 exact compiled sources and active `classes-attempt1`. The stage1 constructor behavior remains unchanged; Root's newer restore fences are **not** part of this isolated cohort.

## Exact helpers

```kotlin
DesktopDiscoveryStorageGuard<T : Any>(
    factory: () -> T,
    sessionEpoch: () -> Long = { 0L },
    stillOwned: () -> Boolean = { true },
)
// result: StateFlow<Result<T>?>, isActive: Boolean, canRetry: Boolean
// suspend load(): Boolean; close(): Unit (terminal, idempotent)

openDesktopDiscoveryStorage(
    repository: DesktopRepository,
    preferencesFactory: () -> DesktopDiscoveryPreferences,
): DesktopDiscoveryRepository

openDesktopDiscoveryFeedback(
    discovery: DesktopDiscoveryRepository,
    accountMid: Long?,
): StateFlow<TodayWatchFeedbackSnapshot>

@Composable DesktopDiscoveryStorageBoundary<T : Any>(
    guard: DesktopDiscoveryStorageGuard<T>,
    sessionEpoch: Long,
    onRestart: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
)
```

All are `internal`, available to Root Shell's existing `com.bilipai.desktop.ui.*` import. No new dependency, Gradle task, Android state model, account request, or native player is needed. Boundary closes its guard on disposal. Only the supplied real `onRestart` should be passed: Main already supplies `closeApp(restart = true)`; if no real restart is available, omit the button. The fixture verified callback invocation, not actual process restart.

## Root initialization seam

Move the **same** `applicationPluginStore` resolution/NetworkProxyStore initialization before Community/Discovery consumers; do not construct an alternate default store. Then retain a single global authority:

```kotlin
val sharedBlockedUps = remember(pluginStore) {
    DesktopBlockedUpStore(DesktopPluginContext(pluginStore))
}
val startupGuard = remember(repository, pluginStore, sharedBlockedUps) {
    DesktopDiscoveryStorageGuard(
        factory = {
            openDesktopDiscoveryStorage(repository) {
                DesktopDiscoveryPreferences(pluginStore.root, sharedBlockedUps)
            }
        },
        sessionEpoch = { repository.sessionEpoch },
        stillOwned = { !rootClosing.get() }, // Root's real shared terminal shutdown flag.
    )
}
DesktopDiscoveryStorageBoundary(startupGuard, sessionEpoch, onRestart) { discovery ->
    // Existing Community/Story/SettingsHome/PluginRuntime composition goes here.
    // Keep the actual non-null discovery in Runtime and every other consumer.
}
```

The entire dependent constructor/effect branch must sit behind this boundary. It is also valid for Root to use a conditional early return while collecting `startupGuard.result`, provided guard ownership/disposal and all dependent constructors stay behind readiness. Do not create `DesktopPluginRuntime(..., discovery = null)` as a fallback: the real plugin API binding requires the actual discovery repository. Startup failure leaves Main's default shutdown lambda intact and Main still closes its idle player; no fake Runtime is required.

The `rootClosing` field is Root's actual terminal lifecycle state, not a new always-false helper default. On restore/exit it must become false ownership **before** any retry can schedule a new factory. Helper `close()` does not freeze or replace stores, and a successful guard never invokes its factory again. Root's separate accepted-write drain/Preferences/Runtime fence remains required.

## Active account page seam

Guard before `DiscoveryFeed` performs `feedback(...).collectAsState()`, renders rows, or launches its fetch. Prefer wrapping the whole current `DiscoveryContentScreen` body if weekly-period and UI mutations should also be blocked for this page. Capture the account identity and epoch at guard construction:

```kotlin
val capturedMid = account?.mid
val capturedEpoch = sessionEpoch
val feedbackGuard = remember(discovery, capturedMid, capturedEpoch) {
    DesktopDiscoveryStorageGuard(
        factory = { openDesktopDiscoveryFeedback(discovery, capturedMid) },
        sessionEpoch = { repository.sessionEpoch },
        stillOwned = {
            !rootClosing.get() && repository.sessionEpoch == capturedEpoch &&
                repository.account.value?.mid == capturedMid
        },
    )
}
DesktopDiscoveryStorageBoundary(feedbackGuard, capturedEpoch, onRestart) { source ->
    // Original DiscoveryFeed(..., feedbackSource = source) after Root adds a tail parameter.
}
```

The precise private feed seam is a tail `feedbackSource: StateFlow<TodayWatchFeedbackSnapshot>? = null`; use `(feedbackSource ?: remember(discovery, accountMid) { discovery.feedback(accountMid) }).collectAsState()` only inside the already-guarded child. Alternatively, wrap/restructure its current body and collect the resolved source there. Root owns `DiscoveryScreens.kt`; no whole-file replacement is delivered. No existing Runtime is destroyed by an active-feedback page error. **This does not block the Runtime's already-running background plugin requests.** Wrapping only `DiscoveryFeed` also leaves the parent `weeklyPeriods()` request outside this guard; choose the larger parent boundary if claiming that page's complete requests are blocked.

## Truthful retry boundaries

* Illegal outer guest/account JSON fails the actual `DesktopPluginStore.Backing` constructor before registry insertion. Repairing that task-owned file and retrying the factory genuinely succeeds, keeps unknown namespaces/input bytes, and uses the same global blocked-UP authority. No bad JSON is discarded, renamed, or overwritten by the error path.
* Successful Preferences/feedback objects remain retained. An external disk edit after success is not hot-loaded. Existing cached global destination corruption continues to show stage1's restart-only message; another facade of the same backing cannot bypass it. No UI action calls `freezeWrites()` to simulate a restart.
* The fixed wrapper catches constructor exceptions only as retryable. It separately forces public `.value` once and classifies a later decode exception as restart-only. The held-decode classification test deliberately injects a failure; it does not claim a new original-schema failure mode.
* Original `TodayWatchFeedbackStore` catches an invalid embedded `feedback_payload_v1` and returns its existing empty snapshot. This cohort proves that original behavior and no file rewrite. It does **not** repair or replace that policy, and does not describe embedded corruption as fixed by the new guard.
* Stage1's failed global blocked-UP migration can remain published after a successful account-feedback repair. These are separate operations; no failed migration is hidden or silently retried. Original feedback writes may already commit before a separately failed UP-block mutation; no all-or-nothing transaction claim is made.

## Evidence and pending gates

`proof/disk-guard.json`: 11 direct executable `@Test` methods against real temporary disk and actual product Store/Repository APIs, plus controlled coroutine factory gates. Includes guest/account corruption, real repair, retained generation, frozen store, same-MID epoch retirement, replacement-finally isolation, close and cancellation. No request method is called.

`proof/ui-short/result.json`: M3/Miuix × startup/account = four offscreen cases, actual Press/Release on original `AppDialogAction`, real disk repair then original source values, safe labels, no consumer effect until ready, and closed-generation content rejection. Eight PNGs are retained. This is not an HWND/native popup or process-restart proof.

The initial UI process failed while Skiko's ICU resource extraction used an overlong task `user.home`; fatal log remains `hs_err_pid37768.log`. The same unchanged compiled classes passed with a short task-only temp HOME (`ui-short-runtime-evidence.json`). Product GPU/ICU behavior is not claimed fixed. Shared Gradle, main files, real credentials/account data, actual HWND, and external HTTP were not used.

Root must still verify the final current-product Shell placement, its closing/restore fence, and real restart lifecycle after integration. This isolated source cohort did not replace or retest Root's latest restore-fence delta.
