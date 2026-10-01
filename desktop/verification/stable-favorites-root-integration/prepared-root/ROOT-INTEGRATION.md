# Stable Favorites Root retention and queue wiring — prepared only

Install only the explicit whitelist. Main/Candidate were never written by this lane.
The full `reference/.../DesktopShell.kt` is a compiler input/reference, **not** an installation payload.

## Verified basis and exact install

- Immutable actual Main17 manifest `3b56a4a47fbd1d06b1d3b4be274bc3f69597dea669f04358eb7282292ab51e44`, ordered CP `25533b03785344f3a4a360a6db292775ad370824449c7a9c3a5b98702e3812d6`: 92 actual entries. Original FavoriteVM, Controller and PluginStore load from that product JAR; no parent Favorites overlay in final compile/proof.
- Copy two new manual files `DesktopFavoritesRootEntry.kt` and `DesktopFavoriteQueueBridge.kt` from `prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/` to the same source paths.
- Apply `listen-owned-queue.patch` only to existing `audio/ListenAudioSession.kt`; exact normalized base/desired pins are in `listen-delta-baseline.json`.
- Apply `host-retained-initialization.patch` only to existing `ui/DesktopOriginalFavoritesHost.kt`; exact pins are in `host-delta-baseline.json`. New compatible tail `loadFavoriteViewModelOnEnter:Boolean=true`; Root's initialized retained VM passes false. Other callers retain old behavior.
- Apply **local** `root-shell.patch` hunks to `DesktopShell.kt` on normalized base `a5a84bc6d2aa41bc2672b3e16894a79f421a676a2aab9da9cd0809096ca41c2b`. Preserve all unrelated Root video-drawer/API/count/shared-tabs work. `shell-baseline.json` contains desired pin. All three patches passed read-only `git apply --check --ignore-space-change --ignore-whitespace` against the then-current Candidate.
- No Gradle dependency/source-dir/resource addition and no new original schema identity are needed for these manual Root adapters. Existing 741 Favorites original identities are reference-only. `original-reference-identities.json` records the source anchors used here, not append requests.

## Exact lifetime / Root consumer contract

Root keeps one `DesktopFavoritesRootEntry` for the actual `CLOUD_FAVORITES` navigation entry and captured repository epoch. It contains **the original** FavoriteViewModel, FavoriteCategoryViewModel and actual original collection-detail VMs, with a child SupervisorJob of Root's real scope. Its API clients are Root's existing `community.favoriteApi/favoriteSpaceApi/favoriteDynamicApi/favoriteBangumiApi`; it creates no Retrofit/client or cache. Its preferences use the same `pluginStore` instance. `AccountSummary` has no CSRF field: callback uses existing `repository.requireCsrf()`; MID comes from existing `repository.account.value`.

The entry remains alive when video covers the underlying section. Original Listen playback reveals `LISTEN` as a child cover without calling ordinary `navigate`, so full-folder continuation is retained there too. A real sidebar/navigation change to another destination closes the entry. Returning to `CLOUD_FAVORITES` reuses a still-owned covered entry. Epoch replacement closes old jobs and original conflated channels through Root composition disposal; a captured-epoch effect recreates the entry when Favorites is still selected. Restore/exit close the entry before PluginStore freeze and join its child job. Closing a navigation entry does **not** stop an already-playing valid queue; it retires future continuation and page-owned work.

Root's `SaveableStateHolder` preserves actual original CommonList state/list positions under entry+detail route keys; retired entry keys are removed on real navigation. Root uses existing `DesktopDetailWindow` around the sole original host so `LocalWindowSizeClass` and viewport are measured from bounded Compose constraints. No second HWND/window/chooser exists.

`FavoriteCollectionRoute` from collection callback and `(mediaId,ownerMid,title,ownerName)` from folder callback go to the retained original detail VM/host, not the old CommunityCollectionScreen. Note the original folder callback's **first** Long is mediaId. Back from detail returns to the retained root list; root Back navigates Home.

## Real playback ownership and continuation

Original CommonList first calls `bindings.openQueue` then invokes its video/audio callback. `DesktopFavoriteQueueBridge` passes a real opaque owner into the actual Controller or Listen session. The following callback consumes a one-shot admission ticket and **only reveals** that already-opened player; it must not ordinary-open the video/audio again. Normal unrelated playback remains Root's normal open path and retires the old real lease.

- Video uses existing `DesktopPlaybackController.openQueue(cards,index,owner)`, `ownsQueue(owner)`, and `updateQueueForOwner(owner,cards,currentIndex)`. No Controller patch is supplied, and Root's new count method / native-load ownership change must remain intact.
- Listen's minimal same-file lease is `playQueueForOwner(owner,items,index):Boolean`, `ownsQueue(owner):Boolean`, `appendQueueForOwner(owner,items):Boolean`, `stopQueueForOwner(owner):Boolean`. It owns no second queue. Normal start retires; actual internal next/previous keeps lease with updated generation/baseline; loaded pause keeps lease; pending pause retires; clear/close retire. Pending checks native version **and** absence of foreign source. Loaded checks actual same source snapshot.
- Full-folder continuation follows original PlaylistManager: preserve current list/index and prepared CID/metadata; append only BVIDs not already present. This is not a full-list replacement. Adapter reads Controller/Listen's current real state at append time, so actual next does not invalidate the original owner. Root's existing `playing` / `listening` observers remain the only playback UI authority; CommonList has no independent playlist observer/store.
- The original `FavoriteViewModel.loadAllForPlayback` remains untouched, running in that retained environment's scope and applying its own folder request generation/order/media-ID guard. If epoch/route/native owner/current target changes before completion, the final append is rejected.
- Root applies original SEQUENTIAL policy through its existing `changePreferences`, not a new preference file. Existing Controller 10k / Listen 5k safety bounds remain unchanged and are not claimed as unlimited Android parity.

## Real channels and share

`entry.searchChannel` and `entry.scrollToTopChannel` are original `Channel.CONFLATED` channels (stable AppNavigation anchors 1555/1562), passed to the actual CommonList receiver. LinkedDock can use `favoritesEntryRef.get()?.takeIf{it.isOwned()}?.searchChannel` as its Favorite SendChannel. This slice supplies no fake History/WatchLater channels; those original receivers remain a separate integration task. The Favorite route has `isCurrentPage=!showVideo&&!activatingUpdate`; UI unmount does not close the entry.

Share uses the existing `requestDesktopTextShare(rootTextShareBindings,entry.scope,...,entry::isOwned)` and real Root native-share callback. Actual global settings flows/default decoders are passed from `DesktopFavoritePreferences`. Windows Haze backdrop sampling is still explicitly unavailable; `hazeEffectSupported=false` is a platform capability boundary, not a replacement persisted setting. Original liquid/shared-tabs code remains intact.

## Proof and limits

Final compile `compile-final-04.log` passes against actual17/92 with only declared prepared `RootShell`, `ListenAudioSession`, `DesktopOriginalFavoritesHost` and two new helper sources. `compile-final-evidence.json` pins those exact final inputs. `run-final-03.log` is the unchanged narrow 4-group / 28-assertion fixture: actual Controller/Listen/player ownership APIs, original FavoriteVM continuation, real task PluginStore, synthetic terminal APIs and unmounted synthetic media. No libmpv worker/HWND/GPU/socket/account mutation runs. The last compile added only the Root captured-epoch re-entry effect; the already-passed unchanged ownership fixture was not repeated.

Earlier compiler mistakes and successful Main16+declared parent-overlay proof remain historical (`compile.log`, `compile-final-01.log`, prior evidence/logs), not Main17 acceptance. Read-only current generated-source audit finds all **25 direct outputs have exactly one exact-LF owner**, including existing extracted-mode identities supplied by their already-owned producers (`direct-owner-audit.json`). No producer/mode change is required.

Mounted Root full Favorites page, real folder background HTTP, real audio/video decoding, scrollbar state restoration through actual Root navigation, native share pane and HWND/PiP are **not** accepted by this prepared proof. Root must install/compile and run its actual product consumer check. Parent Favorites and child FolderSheet frozen cohorts are unchanged. No broad original API/UI matrix was rerun here.
