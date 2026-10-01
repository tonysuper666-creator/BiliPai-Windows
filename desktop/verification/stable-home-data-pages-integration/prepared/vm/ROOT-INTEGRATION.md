# Stable full Home ViewModel / one planner handoff

This is prepared source against stable `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`, with actual product snapshot26 and the frozen Home UI524 as explicit compilation inputs. It is not an installed or mounted Home page. No network, account, native-window, EXE or Firebase acceptance is claimed.

## Installation whitelist

Install these seven source payloads only:

1. `prepared/tools/extract-upstream-home-viewmodel.py` -> `desktop/tools/extract-upstream-home-viewmodel.py`.
2. Its adjacent `extract-upstream-home-viewmodel-adaptations.json` -> the same tools directory.
3. `prepared/manual/com/bilipai/desktop/ui/DesktopHomeDataEnvironment.kt` -> `desktop/src/main/kotlin/com/bilipai/desktop/ui/`.
4. `DesktopHomeFollowingCache.kt` -> the same directory.
5. `DesktopHomeOwnedMutableStateFlow.kt` -> the same directory.
6. `DesktopOriginalTodayWatchOwner.kt` -> the same directory.
7. `prepared/replacements/com/bilipai/desktop/plugins/DesktopTodayWatchRepository.kt` replaces the existing file; it must not coexist with the old planner.

Apply `runtime-retirement.patch` only after checking `runtime-retirement-base.json`. Apply the child `backend-contract/follow-getter-and-planner-retirement-delta/sole-producer-two-getters.patch` to the existing dynamic-settings producer, not a second DynamicRepository producer. Both append original getter bodies to the existing feedPagination authority.

Merge the seven `source-inventory.json` records by path/features into the then-current registry, preserving all existing modes and sources. Four identities are new, three are feature merges. DIRECT3 are copied by prepareUpstreamSources; the production generator intentionally emits only three selected outputs. `--standalone` additionally emits the DIRECT3 for isolated compilation. Do not install standalone generated copies into the production selected-source directory.

Register one extraction task for `home-viewmodel`, depending on the stable source tree, tools script and JSON sidecar. Its output is one source directory; prepareUpstreamSources and Kotlin compilation depend on it. No dependencies beyond the existing graph are required. The frozen UI524 and its exact HomeSettings/HomeStateOwner producers remain separate prerequisites.

## Exact constructor and original authority

`DesktopOriginalHomeViewModel(environment: DesktopHomeDataEnvironment)` implements both `DesktopOriginalHomeStateOwner` and `DesktopOriginalTodayWatchOwner`. Every Environment constructor property is required. Its `video/history/live/messages/actions/follow/blockedUps/following` interfaces use original request/result models. There is no fallback DiscoveryPage-to-VideoItem supplier, default setting flow, separate account cache or optional no-op action.

The full pinned original body retains category and popular caches, refresh request index, incremental merge/divider and undo, live pagination, FOLLOW baseline/replacement, not-interested local-first/dissolve/server order, original history TTL and TodayWatch expanded/consumed/refill. The Windows edits add ownership, structured cancellation and platform bindings. The bridge only holds an owner reference and projects its existing plan/loading/error; it owns no plan, candidate, history, expanded, consumed or refill algorithm.

Existing plugins extraction owns internal `RecommendationResult.toTodayWatchPlan` and its conversions. The new full VM's conversions remain private file-local original declarations. Existing Discovery remains the sole global `resolveRecommendFeedRequestIndex`. The final compile passed with those existing product classes.

## Required retained Shell binding

Place the real factory binding at the ReadyShell level, outside any conditional Home/video/favorite/audio content branch. Retain it by Runtime and immutable sessionEpoch, not by whether Home is currently drawn. A video, Favorites or audio cover must not call close or rebind the VM.

Use the existing Root coroutine scope with the actual serial Main/EDT dispatcher. Construct the data Environment and the VM **inside** `recommendations.installOwner(capturedEpoch) { ... }`. The factory runs only after the bridge has closed and joined the previous owner. Calling the same epoch again returns the same original VM without calling the factory. A startup PluginProvider reload waits for this binding; it must not launch the retired flat-page planner while UI binding is pending.

Keep only a UI reference to the returned VM, guarded by the captured epoch. Pass that very object to the frozen original HomeRoot's required state owner. Its `feedbackEvents` goes to the existing Root feedback presenter. Do not create another list or TodayWatch projection in Shell.

For factory construction, capture `repository.dynamicCacheSessionGuard.dynamicCacheOwner()` and require its epoch equals the captured sessionEpoch. A race rejects the factory with CancellationException and waits for the next epoch binding; do not throw from a composable remember initializer. The Environment `isCurrent` checks the same captured owner, Runtime/lifetime active state and epoch. For `commitIfCurrent`, call the existing `withCurrentDynamicCacheOwner(capturedOwner)` and then the retained-entry lock; execute the mutation only while the entry is active, and return an actual applied flag. The lock order is SessionStore -> entry lock -> canonical PluginStore. Never call back into SessionStore while holding a PluginStore lock.

`DesktopHomeFollowingCache` uses the exact same global PluginStore and original `following_cache` namespace/keys; its writes receive that same atomic admission. Feed feedback uses the current discovery recommendation context, not a new store. Settings flows are the actual original settings bridge flows (incrementalTimelineRefresh and homeRefreshTipVisible), not fixed booleans.

Root app-close/restore calls Runtime.shutdownForRestore. The runtime patch retires and joins Home before beforeStoreFreeze and before canceling Runtime scope. Epoch events use sessionEpochFlow so replacing credentials for the same MID retires the old VM. The original Controller READY consume and plugin settings reload continue calling the same public recommendations surface.

## Real data ports and honest pending activation

The frozen owner is compile- and lifecycle-verified with scripted original DTOs. Actual request port implementations are required before mounting Home. Use the existing API/guest API/WBI/SessionStore graph and request-time captured epoch. The original VideoRepository selection must be retained: recommendation preload and original raw idx, MOBILE->WEB fallback and MERGED four-fetch combination, precise original refreshCount/feedType settings, all four popular variants, region202 legacy/ranking fallback, region13 support, guest-only preview quality fallback, and Nav -101 guest success. Binding `discovery.page` indiscriminately loses those semantics.

Raw HistoryResult/cursor, LiveRoom pages, MessageUnreadData/MessageFeedUnreadData and original followings must remain original models. FOLLOW calls the one existing DesktopOriginalDynamicTimelineRepository with HOME_FOLLOW/video scope and the exact two original getters. WatchLater and server feedback must call original action bodies; the VM already records local feedback, so the existing combined discovery.notInterested would duplicate/reorder that write.

`setNavIdentityCache` is a same-SessionStore expected epoch/MID admission to the existing account projection. It must reject foreign nav MID without creating TokenManager cache. Analytics is an explicit Windows platform selection: the original Firebase transport is unavailable. Root may use its real consent-gated local anonymous diagnostic consumer for loggedIn/vip/privacy booleans only; never log MID, cookies, credentials or invent upload configuration. The future transport interface remains required and capability must be stated. A platform failure follows the original swallowed-failure behavior and must not stop category list loading.

## Proof scope and histories

`compile-vm-06.log` and its source identities prove all eleven prepared Kotlin sources were stable before/after compilation (143 classes). `fixture-06` uses actual product PluginStore, TodayWatchPlugin and PluginManager, explicit prepared UI/VM classes and seven narrow in-process original-DTO gates. It proves original recommend undo/divider, per-category/popular caches, FOLLOW probe/replacement, retired same-MID writes, original consumed/history cache, close/join before replacement construction, same-owner retention, and pending startup cancellation without a second planner.

Actual account HTTP, full Home mounted UI, preview native rendering, Root Shell retention wiring, Firebase, or a new packaged executable are not proven. Earlier compile/fixture failures remain immutable historical files. Producer replay initially compared CRLF source bytes to LF generated bytes incorrectly; `producer-replay-proof-02.json` records the corrected LF comparison and preserves the first failure. No evidence binaries/classes, task stores or JARs are installation payloads.
