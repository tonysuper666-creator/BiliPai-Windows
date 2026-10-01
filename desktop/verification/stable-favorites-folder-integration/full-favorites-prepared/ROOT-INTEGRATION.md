# Prepared stable Favorites closure

Target is original `v0.2.3`, commit `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`. This is an installable source candidate, not mounted Main, native-window or EXE acceptance. The immutable actual Main dependency base is `stable-product-snapshot-15`, manifest `c665e8cc40604866341c9ac5f6ef992ed84b52fe709a1f934337f754012cab87`, ordered CP `bcbc863d545922aaa4308076a4d4651c219aafacf339e8cb3000e59696f9e335` (92 entries). Later Root comments/sharing registration is not overwritten.

Install only `prepared/desktop/tools/extract-upstream-favorites.py` and the four manual `prepared/desktop/src/main/kotlin` files. Generated reference payload is `prepared/generated`. The producer command is:

```text
python desktop/tools/extract-upstream-favorites.py --repo <actual Candidate root> --output <desktop/build/generated/favorites>
```

Add that output directory to the existing Main Kotlin source set and wire one extraction task into the existing upstream preparation sequence. Default production emits **34 selected outputs**; **25 direct outputs** are supplied once by upstream direct-copy preparation. `--standalone` emits all 59 for the isolated compile. There are **60 original source identities**, including 3 references; the registry delta has **57 active identities** (24 direct identities, 33 selected identities), **40 new + 17 merge-feature rows** against the read-only registry697 audited by the child. Never replace the whole source registry or change an existing mode/producer. Add feature `stable-favorites` and merge only the rows in `prepared/registry-delta.json` after the child drawer identities are reconciled.

The full original `CommonListScreen`, `FavoriteCategoryScreen`, Base/Favorite list algorithms and `SeasonSeriesDetailViewModel` are retained. Original category flows cover video, bangumi, cinema, article, public/unpublished note, topic and course; original requests, sorting, search type, pager, management confirmations, create/edit privacy/intro, bulk copy/move/remove/clean and subscription distinctions remain. `HistoryViewModel`, `LikedVideosViewModel`, their repositories/cards are full original compile dependencies, **not mounted History/Liked page acceptance** in this slice.

Existing original producers remain sole owners. Main's `FavoriteRequestException`, risk-control policy, `HistoryCursorQuery/resolveHistoryCursorQuery`, `DeviceUiProfileAdapter.toAdaptiveWidthClass`, original models, API builders, full video cards, shared liquid tabs, and `DissolveAnimationManager/Preset/DesktopReplyDissolvableContainer` are references. Only the original missing device-profile members and original `Modifier.jiggleOnDissolve` are emitted. No edit to `extract-upstream-dynamic-reply.py` is required. The existing failed-capture dissolve platform branch is referenced; full Android GL particle behavior is not proved here. Favorites does not mount the History delete-session branch.

# API and page owner

Manual source `DesktopFavoriteEnvironment.kt` is a reusable ordinary constructor, not a Composable or new transport. Its guarded Java proxy checks the supplied current owner before dispatch and before suspended/synchronous results reach the unchanged original algorithms. All supplied APIs must come from the **one current Root graph**. Expose existing Community `api`, `space` and `dynamic` internally, or construct the environment inside that class. If a `BangumiApi` interface is absent, add it on the existing Community `web` Retrofit/client; do not build a second client/jar/store. Bind `readMid` to live `repository.account.value?.mid`, CSRF to the existing repository admission, and `stillOwned` to immutable captured epoch plus retained entry/page alive. Keep the existing graph's actual session/request guards. Result guards are not a claim of an atomic server mutation after credentials change.

Full constructor:

```kotlin
DesktopFavoriteEnvironment(
    scope, api, spaceApi, dynamicApi, bangumiApi,
    stillOwned, readCsrf, readMid, feedback,
    historyChanges, getCachedPosition, privacyModeEnabled, watchLaterChanged,
    readAccessToken, readAccessTokenPlatform,
)
```

The last six ports are only required on use by unmounted History/Liked dependencies. `null` is an explicit absent dependency and throws on use; it is not a fabricated response or default store. Full Favorites needs the Space/Dynamic/PGC services, while the video drawer needs only its Bilibili API and the same original action methods:

```kotlin
DesktopFavoriteEnvironment.forFolderDrawer(
    scope: CoroutineScope,
    api: BilibiliApi,
    stillOwned: () -> Boolean,
    readCsrf: () -> String?,
    readMid: () -> Long?,
    feedback: (String) -> Unit,
)
```

Retain `environment + FavoriteViewModel` by the original navigation entry/account epoch, not merely the visible Composable. Original `loadAllForPlayback` continues after navigation to video/audio so it can append the rest of the folder. Close/cancel on removal of that entry, epoch change, restore or application shutdown. Pass the retained VM through `retainedViewModel`; do not mount Root's old handwritten Favorites `CommunityFeedState` alongside it. Detail retains one `SeasonSeriesDetailViewModel(environment)` initialized once by original `FavoriteCollectionRoute` fields. No second items/cache authority is created.

# UI and same global preferences

`DesktopOriginalFavoritesHost` accepts environment, required `DesktopFavoriteBindings`, existing routing callbacks and these optional original route/visibility ports: `initialSearchQuery`, `initialSearchScope`, `initialSubscribed`, `detail`, `listScopedSearchChannel: Channel<String>?`, `scrollToTopChannel: Channel<Unit>?`, `isSearchDestination`, `onOpenSearchDestination`, `isCurrentPage`, `retainedViewModel`. These are passed to the whole original `CommonListScreen`. Root retains the actual Channel receiver; the LinkedBottomDock submit callback receives its SendChannel. This host supplies Favorites only; do not advertise History/WatchLater channel consumption before those actual original pages are mounted.

Create `DesktopFavoritePreferences(actualGlobalPluginStore)` once at Root. It projects original `SettingsManager` keys/defaults/legacy header blur and duration decisions directly from the existing `settings` namespace; BackToTop offset writes preserve unrelated keys. `DesktopFavoriteBindings` requires actual preference flows, initial snapshots, one `FavoriteCategoryViewModel(environment)`, Root queue callbacks, existing typed native text-share callback, **actual** haze capability, and same-global BackToTop ports. It is not a new persistent state model. Provide Root's real window/semantic/skin/shared-transition contexts; measured viewport comes from actual `LocalWindowSizeClass`. Provide the original bottom-bar content padding/visibility callbacks from the real dock when mounted.

`DesktopFavoriteInteractionPreferences(actualGlobalPluginStore)` comes from the full original `FavoriteInteractionSettingsStore` getter/default/selected key and setter binding. `getQuickSaveDefaultFolder(): Flow<Boolean>` observes **actual key `favorite_quick_save_default_folder`**, original default false. `setQuickSaveDefaultFolder(Boolean)` writes the same global `settings` namespace. Do not hardcode false or bind this global setting to MID/dialog visibility.

# Existing Root queues and drawer

Required `openQueue(List<PlaylistItem>, selectedIndex, playAllAudio): DesktopFavoriteQueueToken?` must use Root's existing video controller or Listen session, set original `SEQUENTIAL` mode, preserve the original raw aid/BV/CID and store its real queue owner token. The following original `onVideoClick/onPlayAllAudio` callback is navigation/selection continuation and must not call normal `open()` that retires that freshly opened queue. `appendQueue` must call existing guarded `ownsQueue/updateQueueForOwner` (or the equivalent Listen queue owner), retaining current item/CID/position/pause. A different source/queue rejects old append. No fake success token should be returned.

The separately frozen child drawer is `stable-favorites-folder-sheet-parity/frozen-handoff.json`, SHA `df54bc254658fd114a75ec96f5f01776629b02b0623aef7b80c39a44cb3db9d1`. Install its sole producer/2 manual files and direct3 once; do not install another ActionRepository. Construct child protocol using parent `env.api`, `env::currentMid`, `env::csrf`, `env::assertOwned`; create uses `env.actions::createFavFolder` (full title/intro/privacy). Original membership read uses `getFavFolders(mid,type=2,rid=aid)`, while the page catalog uses the original general `getFavFolders(mid)`; these are distinct contracts. Membership save is one sorted `dealFavorite` add/delete delta, not per-folder social mutation.

For Root video drawer use immutable aid+epoch+page alive, child `close()` and owned scope cancellation. The child callbacks update the current Root cloud `VideoRelation` and current raw favorite count, not local library bookmarks. Required `currentFavoriteCount` reads that same authority. Count/version readback protection remains Root-owned.

The parent's sole `DesktopOriginalFavoriteActions` also contains entire original `favoriteVideo(aid, favorite, folderId=null)` and private `getDefaultFolderId()`. Quick-save uses original first created folder lookup then one original `dealFavorite` call; explicit folder removal skips lookup. Root uses installed original `VideoFavoriteActionPolicy` and original count policy/result. After await, call the same current-owner guard before feedback/relation/count updates; original ActionRepository may return cancellation as `Result.failure`, so Root must not show it on a retired page. Ordinary taps obey actual global quick preference; long press/audio entry uses the original drawer rule.

# Evidence and remaining acceptance

Final 63-source product closure plus pure fixture compiles against immutable Main15, with zero candidate class/public top-level function overlap. One fresh JVM has **5 groups / 26 assertions**: exact request clamps/fields and raw aid/CID/type21 routes; original VM owned/subscribed/search/order; retired suspended result cancellation; original default-folder/one quick-save mutation; actual same-global preferences and disk keys. Java Proxy API fixtures have no socket and no real service mutation. Six CodeSources distinguish actual product API/models/store from prepared candidate VM/adapters. Final Host retained-entry/channel tail has a separate exact-source compile receipt. Child drawer independently has 9-source compile and 4 groups/33 assertions.

Still required: Root API/epoch/retained navigation and queue continuation wiring; all original route targets; same-global settings/dock ports; actual original whole page/category/drawer pointer proof after Main integration; real native popup/window and EXE acceptance. Auxiliary History/Liked page mounting, Android GL dissolve capture, runtime glass/animation behavior and whole-product feature parity are not accepted by this prepared compile. No shared Main/Candidate source, Gradle, manifest, account data or packaging has been edited by this lane.
