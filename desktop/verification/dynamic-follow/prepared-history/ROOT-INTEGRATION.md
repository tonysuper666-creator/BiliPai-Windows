# Prepared follow observer integration

This lane has not edited Main, Gradle, the source registry, or formal verification files. `runs/08` is the final prepared-candidate acceptance. Its 102 explicitly listed class overlaps are candidate overrides compiled against the immutable 89-entry editor Main dependency snapshot, not a zero-override proof of a new Main integration. No Shell, native window, packaged EXE, external socket, or account request was executed.

Apply the seven prepared Kotlin source files under `candidate/desktop/src/main/kotlin/com/bilipai/desktop/`, using `patches/` for the six existing files. Compare `baseline-pins.json` first; rebase a patch if another Root change has altered the file rather than copying an outdated whole file. `DesktopFollowStateEvents.kt` is new. Repository owns exactly one event source; every Social instance for that Repository publishes to that source. The source contains no feed rows or cache. Its authority is the existing SessionStore guard, including MID, epoch, and credential namespace.

Root must launch collection on the existing Compose model owner through an epoch-keyed effect immediately beside the current `dynamicCardSession` / `dynamicCardRegistry` lifetime block in `DesktopShell.kt`:

```kotlin
LaunchedEffect(dynamicCardSession, dynamicCardRegistry) {
    dynamicCardSession.observeFollowStateChanges(dynamicCardRegistry)
}
```

Do not add `Dispatchers.IO` to this effect. Social transports remain on IO; the original event returns to the Root Compose effect before mutable page models are reduced. Changing the session epoch cancels the old effect, closes the old Session and Registry through their existing `DisposableEffect`, and starts collection with the replacement owner. The session additionally checks its live owner/expected epoch; the Registry performs the whole reduction under `withCurrentDynamicCacheOwner`. Shutdown's existing `stillOwned = !isClosing` check refuses new changes. No new scope, callback registry, or list owner is needed.

Keep the existing Community page registration and sole All cache binding in `CommunityDynamicFeedReady`:

```kotlin
cardRegistry.register(users)
// Every home timeline, including a retained model, is registered through this existing call:
createdOrRetainedTimeline.also(cardRegistry::register)

onAllTimelineChanged = { rows ->
    if (cardRegistry.isCurrentAll(model)) cache.saveTimeline(rows)
}
```

`cache` here is the existing `DesktopDynamicCacheSession` opened before startup, and Root retains its sole `DesktopDynamicCache` actor. The Registry's unfollow handler reduces retained home Timeline/Users objects then calls `currentAll?.persistCurrentItems()`, including an empty All page. `isCurrentAll` must remain in the original callback, so an older same-epoch retained All cannot replace the current All's persisted rows. Registry does not instantiate a cache or persist any second items copy. Its follow handler only invalidates the Users model's original followings TTL/full flag and requests the original refresh. Existing generic Space, Topic, and detail card mutation owners remain registered for like/repost/delete/unfold, but are excluded from this follow-state handler.

Copy the three prepared producers from `prepared-tools/desktop/tools/`: the new `extract-upstream-dynamic-follow.py` plus the two existing generators with only cursor checkpoint/restore method additions. Do not copy candidate generated Kotlin into permanent hand-maintained source. The new producer emits the exact original `FollowStateChange`, `DynamicUiState`, and six original reducer/page helpers into `generated/dynamic-follow`. DynamicUiState is used only as a transient reducer input/output; retained rows stay in the existing Timeline/Users objects.

Wire `extractUpstreamDynamicFollow` as an Exec task depending on `prepareUpstreamSources`, `extractUpstreamDynamicSettings`, and `extractUpstreamDynamicTabs`; declare the producer plus its parser/media dependencies, and the original sources carrying feature `dynamic-follow-observer-parity` as inputs. Output `generated/dynamic-follow`; add that directory to Main Kotlin source roots and add the task to `compileKotlin` dependencies. Merge the new producer's three `inventory()` entries into the source registry using the existing manifest merge flow without replacing other features/modes already assigned to those sources.

The two original Repository generators only add `checkpointForFollowChange` / `restoreAfterFollowChange`. They preserve the existing fetch loops, retry policy, pagination registries, and merge bodies. A Timeline/Users request captures one transient original cursor snapshot while holding its existing requests Mutex; an unfollow revision retires its pending response, and the request's finalizer restores the same original cursor owner after the old repository call ends. This avoids rejecting old rows while silently advancing past them. The restoration is not a second persistent cursor or items authority. Selected-UP cancellation/token checks and original tab-selection behavior remain in place.

Retain original result semantics: a server code=0 remains setFollowing success if `tryEmit` returns false. Capacity 32, replay 0, dropped notifications under saturation/no subscribers are explicit original boundaries. Do not raise a local notification failure that makes the calling UI roll back the already confirmed follow state, and do not automatically resend the backend mutation. Account retirement and actual cancellation still refuse a stale local event. Fresh mounting obtains authoritative data through the existing startup fetch/hydration.

After Root integration, compile and freeze a new actual Main, then re-run the focused fixture against that new Main with zero candidate class overrides. Re-run the existing timeline/tabs/card Registry checks and the source extraction contracts. A new Main receipt must identify the new Main/CP hashes and actual Root collection/currentAll seams; it cannot cite this prepared 08 as proof that Root Shell was executed. Desktop package/account/UI acceptance remains a separate boundary.
