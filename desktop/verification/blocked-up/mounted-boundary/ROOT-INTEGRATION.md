# Continuously mounted discovery boundary — thin two-helper delta

This supersedes the **conditional wrapper placement recommendation** in the old frozen162 handoff. That recommendation disposed Boundary after successful readiness, prematurely closing its guard. Do not unmount Boundary at successful retry. The old frozen files/evidence remain unchanged; only this new delta should be installed after their two new helper files.

Production changes:

1. `DesktopDiscoveryStorageBoundary` adds an optional `errorTheme: @Composable (@Composable () -> Unit) -> Unit = { body -> body() }` immediately before its existing last `content` parameter. Boundary remains mounted throughout readiness. Ready content is invoked directly. Only loading/error/stopped content runs inside `errorTheme`.
2. `DesktopDiscoveryStorageGuard.close()` additionally publishes `mutableResult.value = null`. This retires a published ready result and notifies the observing Boundary; it does not modify preference data or reopen/drop/replace a store. `closed`, request generation, retained object, and all source/epoch checks retain their existing semantics. A successful retry itself never calls close.

## Exact Root placement

Root's thin wrapper still resolves **one** actual `pluginStore`, constructs the same global `sharedBlockedUps`, and uses `DesktopDiscoveryPreferences(pluginStore.root, sharedBlockedUps)`. Actual error theme values come from read-only `DesktopThemePrefs` on that same backing. The wrapper should be:

```kotlin
val guard = remember(repository, pluginStore, sharedBlockedUps) {
    DesktopDiscoveryStorageGuard(
        factory = {
            openDesktopDiscoveryStorage(repository) {
                DesktopDiscoveryPreferences(pluginStore.root, sharedBlockedUps)
            }
        },
        sessionEpoch = { repository.sessionEpoch },
        stillOwned = { !isClosing() }, // Main's actual AtomicBoolean closing::get.
    )
}
DesktopDiscoveryStorageBoundary(
    guard = guard,
    sessionEpoch = epochCollected,
    onRestart = onRestart,
    errorTheme = { body ->
        DesktopAppearanceTheme(actualThemeSettings) { body() }
    },
) { discovery ->
    DesktopReadyShell(/* actual non-null discovery and same global store */)
}
```

`DesktopReadyShell` retains its existing single `DesktopAppearanceTheme`; there is no outer theme wrapping ready content and therefore no double density/font scaling. Do not initialize Runtime/SettingsHome/Story before success. Do not invoke theme migration or any write merely to draw the error/loading branch. If the wrapper reads the existing legacy Windows dark fallback, pass its real existing value to `DesktopThemePrefs` rather than inventing one.

Before restore and shutdown, Root must call `guard.close()` explicitly and set its actual closing flag. `stillOwned` is a plain AtomicBoolean getter and does not itself publish a Compose invalidation. The guard's result subscription now provides the terminal ready-to-stopped invalidation. A boundary already showing an unpublished loading `null` has no ready consumer to retire. No original store is frozen solely to implement retry.

## Account feedback key capture

The whole active-account `DiscoveryContentReady` body should remain behind a separate guard. Capture **plain immutable values**, not delegated State getters:

```kotlin
val epochCollected by repository.sessionEpochFlow.collectAsState()
val accountCollected by repository.account.collectAsState()
val capturedEpoch = epochCollected
val capturedMid = accountCollected?.mid
val feedbackGuard = remember(discovery, capturedMid, capturedEpoch) {
    DesktopDiscoveryStorageGuard(
        factory = { openDesktopDiscoveryFeedback(discovery, capturedMid) },
        sessionEpoch = { repository.sessionEpoch },
        stillOwned = {
            !isClosing() && repository.sessionEpoch == capturedEpoch &&
                repository.account.value?.mid == capturedMid
        },
    )
}
DesktopDiscoveryStorageBoundary(
    feedbackGuard, capturedEpoch, onRestart,
    errorTheme = { body -> DesktopAppearanceTheme(actualThemeSettings) { body() } },
) { feedbackSource ->
    // Original ready page and feed collect the resolved original feedbackSource.
}
```

Do **not** write `val capturedEpoch by ...` and then capture it in `stillOwned`: that getter would read the new live epoch and could let an old owner remain valid after same-MID credential replacement. MID and epoch must both use live Repository getters on the comparison side and immutable captured values on the other side.

## Proof identity and scope

`classes-attempt4` is the final active isolated class directory. It contains the immutable product snapshot graph plus explicitly marked original stage1 Preferences/Repository/Store overrides, these two helpers, and fixtures. Root's newer main restore fences are not overwritten or claimed tested here.

`proof/mounted-pointer/result.json`: M3 and Miuix actual Press/Release retry through the original App controls, actual corrupt temp guest backing, actual persisted theme read/write, same global blocked-UP Store, same original Repository. Ready initializer runs once and remains undisposed across epoch and theme recomposition. Actual stored DPI 110% then 105% reads once-applied density 1.10 then 1.05; errorTheme is not composed while ready. `closing::get` plus explicit close publishes null, disposes ready once, draws stopped UI with error theme, and rejects a new factory.

`proof/actual-same-mid.json`: real task-owned `DesktopSessionStore.saveAccount`, fake fixture credentials only, same unchanged AccountSummary/MID, real Repository epoch increments by one. An actual feedback source factory blocked on a task latch is rejected on late return using immutable captured epoch. A new guard reads the original feedback and preserves file bytes and the same global blocked-UP Store. No account HTTP is sent.

`proof/old-disk.json` and `proof/old-pointer/result.json`: old 11 disk/guard and four original pointer cases passed under the final production helper semantics at `classes-attempt3`. `same-production-classes-regression.json` verifies the old fixture classes and all production helper classes are byte-identical between attempt3 and final attempt4; the only later source change is fixing a duplicate `ImageComposeScene.close()` in the new fixture's cleanup. No old foundation assertions were weakened.

Preliminary attempts are not passed evidence: attempts1/2 exposed missing AtomicBoolean invalidation at close, and attempt3's mounted fixture cleanup closed its scene twice after all M3 assertions. Active proof is attempt4 only. The prepare script finished all final test processes successfully, then hit a Python default-GBK read while producing metadata; `finalize-and-freeze.py` performs only UTF-8 metadata/patch/freeze finalization without rerunning tests.

This proves guard composition/lifecycle, not Main's actual process restart or complete Archive restoration. Closing this parent disposes ReadyShell and can cancel composition-owned coroutines; Root must independently retain accepted restore work in its real `NonCancellable` lifecycle operation. No fake restore callback is counted as restore completion.

No main file, old frozen162 file, shared Gradle, HWND, user account data, or external HTTP is changed/used. The 234 dependency byte identities are rechecked before/after. Only the two files in `prepared/` are installable payload; marked overrides, fixtures, and class directories are proof-only.
