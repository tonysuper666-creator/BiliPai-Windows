# Prepared full original Category UI and VM

Original target: v0.2.3 commit `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`, `feature/category/CategoryScreen.kt`, 368 lines, LF SHA `fc7bb877d961633bb0a3fe8aaa4ceb15476b394d0f2d3a3ffb3bc72acbaffc96`. The sole producer selects the complete file. Existing card, skeleton, pull-refresh, adaptive chrome, back-to-top, window globals and settings renderers are referenced. No new API, DTO, settings schema, HTTP client, or second category cache is introduced.

Install only:

- `prepared/desktop/tools/extract-upstream-category-page.py` with `generate(repo,output,standalone=False)`, producing the one original Category file.
- The two manual files `DesktopCategoryEnvironment.kt` and `DesktopCategoryRouteHost.kt`.
- The two **exact** existing sole-producer hunks in `sole-helper-producer-hunks.json`: fullPartition skeleton selects original `ContentVideoGridSkeletonFixedColumns`; existing BGM Lottie producer adds original `CutePersonLoadingIndicator` which forwards the existing `AdaptiveLoadingIndicator`. No whole-producer overwrite.
- Merge `registry-recipe.json`, preserving all existing helper identities/modes/features. Root's current fullPartition already owns `VideoLazyKeyPolicy` and `FeedRefreshPaging`. `reference-only` must not be installed or registered: those are explicit compilation references for actual36's missing declarations.

The required environment signature is:

```kotlin
DesktopCategoryEnvironment(
    tid: Int,
    settings: DesktopHomeSettingsPort,
    parentScope: CoroutineScope,
    stillOwned: () -> Boolean,
    commitIfCurrent: ((() -> Unit) -> Boolean),
    getRegionVideos: suspend (Int, Int) -> Result<List<VideoItem>>,
)
```

Root must use the **same** retained Home `requests.ports.video::getRegionVideos` and already-initialized full global `HomeSettingsPort`. Its port returns original raw VideoItem lists using the existing original region protocol; do not delegate to flattened `Discovery.page`. The global HomeSettings/card-style/online-count flows have no local constructor defaults in the adapted UI.

Construct `DesktopCategoryRouteOwner(name, environment)` once per immutable category navigation entry/TID. Supply a real route-entry parent scope and owner that combines current Root epoch/MID/retained Home with that route's lifetime. Root `commitIfCurrent` must atomically check the same Store → retained entry gate **and** current route. Keep this owner alive while video covers the category. Category exit/replacement, account change or restore closes it; hiding the UI under video does not. Joining cancelled jobs must occur outside Store/entry/VM monitors. This VM replaces Android ViewModel/viewModelScope with its actual entry child Job; no empty ViewModel shim or extra lifecycle dependency is needed.

Root renders the existing complete original page through:

```kotlin
DesktopCategoryRouteHost(
    owner,
    onBack = actualCategoryNavigationBack,
    onVideoClick = actualVideoNavigation, // (bvid:String,cid:Long,picture:String,isVertical:Boolean)
    isReturningFromVideoDetail = actualReturnState,
    isQuickReturningFromVideoDetail = actualQuickReturnState,
)
```

All navigation and return flags are required. The host forwards original CID/picture/vertical fields through the same current owner gate. Navigation callbacks should commit route state and let external lifecycle effects retire/join; they must not wait for native/IO work while holding that gate. Keep the actual shared transition/window/lifecycle/platform providers around the page, as around Home and other full card consumers. Parent Root owns these providers and real route navigation.

The original page/hasMore/append/replace-refresh/empty/error algorithms remain. `resolveReplaceRefreshPage` is the original helper. Synchronous busy admission prevents two queued calls from creating two requests. Request ID, immutable captured TID/page and caller cancellation are checked before success/failure and inside Root admission. A replacement refresh cancels the previous load outside monitors. Old finally can only release its own busy state. An instance rejects a different TID rather than grafting another route onto its in-flight state.

Evidence: `compile-01` retains the real missing-shared-helper failure. `compile-02` compiles seven sources (three installed Category sources and four explicit shared references), 25 classes, zero actual36 production class intersections. Production/standalone/prepared output bytes and inverse-normalized original bodies match. The original successful pagination body is byte-preserved. The prospective fixture has 3 groups/16 assertions with an in-memory required region callback, actual Main36 VideoItem/global preference decoder/real temporary DesktopPluginStore, and no sockets. It verifies single admission, old response/finally isolation, refresh page/replace behavior, and category-only retirement while Home/global prefs remain alive. It is **not** actual Root account/navigation or UI acceptance. No GUI/HWND/HTTP/Gradle operation or shared source edit occurred.
