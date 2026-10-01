# Complete original Profile primary navigation closure

Fixed tag v0.2.3 / `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`. This packet is source-only. It does not mount the page, change the actual Root, run shared Gradle, modify a native DLL, or deploy an EXE.

Install the three exact payloads in install-contract.json, merge registry records while preserving existing modes/features, and merge the single Gradle recipe. Do not install prepared generated Kotlin or the proof JAR. Production emits 10 selected original sources; the sole upstream Sync producer copies the 12 entire DIRECT files. Standalone compilation deliberately supplies those same DIRECT files once.

## Source boundary and sole owners

ProfileScreen retains its whole original UI, including mobile/tablet layouts, guest/error/loading states, account and playback authorization dialogs, privacy/theme actions, personal space/contributions/dynamics/favorites/bangumi, services, signature editing, image preview and the triple-jump interaction. ProfileViewModel retains all original profile-load generations, parallel enrichment, WBI requests, edit/delete actions, wallpaper/save state and incomplete search behavior. OfficialWallpaperSheet, WallpaperAdjustmentSheet and ProfileLoadingSkeleton retain whole original bodies. Every adapter difference is recorded in adaptation-diffs and invertible reverse-adapters. method-inventory.json distinguishes unchanged business tokens from adapted methods.

Reused declarations: actual39 full raw APIs/DTO, StoredAccountSession, FavoriteRepository/Pgc counterparts, MyFollowPolicy, buildSpaceAggregateParams, ProgressiveTopChromeKt, TopReadabilityChromePolicy, the sole skin-repeat policy, shared adaptive tabs/glass, shared skeleton and Windows system reduce-motion reader. New full TripleProgressIcon/TripleSuccessAnimation reuse the original complete pure color/motion policies. WallpaperMedia reuses Home's existing isVideoWallpaper rather than duplicating that package-level declaration.

Android media/file/window operations are required typed physical ports. The original Android bodies remain exact in original-stable. No fake Android class, old USER Space page, fake callback, extra client/player/store or credentials schema is generated.

## Actual Root navigation and page lifetime

Original HomeScreen PROFILE and avatar OPEN_PROFILE call onProfileClick. AppNavigation routes it to ScreenRoutes.Profile (lines 2847–2908), not ScreenRoutes.User. Keep the retained Profile binding on the same navigation entry/epoch child scope. Entry owns DesktopProfileEnvironment and one ProfileViewModel; covering it with a video/list child must preserve the original page lifetime, whereas actual leave/account switch/restore/close retires scope and admission. The scrollToTopChannel is that same entry's actual original Channel<Unit>, with its real producer, never an unconsumed fabricated channel.

Construct DesktopProfileNavigation with all 17 real actions. Required route mapping:

- Back→Home; login→Login; settings→actual SettingsNavigator; search→Search; history→History; favorite→Favorite; subscription→FavoriteSubscribed; following→Following(mid); download→DownloadList; watch later→WatchLater; inbox→Inbox.
- Folder (mediaId, ownerMid, title)→original SeasonSeriesDetail(type="favorite", id=mediaId, mid=ownerMid, title).
- Video bvid→original Video(bvid,cid=0,cover=""). Bangumi (season,ep) uses original resolveBangumiNavigationIds then ep>0→player, otherwise detail. More→original Bangumi type=1.
- Logout/account-switch success must use the same Root account lifecycle and original Home refresh, not a private profile account variable.

Mount DesktopOriginalProfileHost with actual isCurrentPage, accountSessionRefreshGeneration, the original shouldShowProfileHistoryService based on visible bottom tabs, actual HomeSkin's profileBackground/profileSquaredBackground/profileVideoBackground/profileVideoPlayMode, exact bottom-pager deferProfileImmersiveBackground budget, and that retained scroll channel. All parameters are mandatory, including nullable values. Keep existing original Gallery/TextShare/Space/Dynamic image preview/Aicu navigation providers around the page; never create a second gallery or comment provider.

## Same transport and account authority

Use actual39 repository.ownedHomeService(type,base,capturedEpoch,entry::owns), backed by the SAME existing owned Call.Factory. BilibiliApi, SpaceApi, DynamicApi and SearchApi use the original https://api.bilibili.com/ base; SplashApi uses https://app.bilibili.com/. DesktopOriginalProfileSplashProtocol only receives the real SplashApi and preserves signed brand/list→splash/list fallback, filtering and response DTO. The original Android device/build fields are API protocol constants, not fabricated Windows platform capabilities.

Favorite/Pgc objects are the already unique DesktopOriginalFavoriteRepository/DesktopOriginalFavoritePgc, bound to the SAME owner environment. Pass real csrf and accessTokenCredentials getters. The selected original Space aggregate extension snapshots actual credentials once and calls the existing exact buildSpaceAggregateParams; it does not copy account tokens into Profile state.

DesktopProfileAccountPort must use actual SessionStore accountRecords/current account/credentials under the same atomic epoch admission. Full StoredAccountSession records are ephemeral original VM state only; never log or reserialize them. Nav MID must match the captured account; use existing updateHomeNavIdentity/Root authentication-invalidated actor and record the original name/face/VIP/lastUsedAt through the existing account persistence.

**Playback authorization is still a required backend boundary:** actual39 SessionStore has no selected playback MID or playback URL authorization consumer. Do not implement getPlaybackAccountMid as constant null or setPlaybackAccountMid as a successful no-op. The original AccountSessionStore validates an existing session with nonblank SESSDATA, allows null to follow the main account, clears the selection when that account is removed, and invalidates the playback API cache. ApiClient NetworkModule playbackAccount/playbackApi/playbackBangumiApi uses this choice only for playback URL requests. Root must implement the same selection in the existing encrypted SessionStore (no second file/store), route both video and PGC URL requests through the SAME owned transport with that verified session, and invalidate/reject URL results after selection change. Main account navigation/data remains the main account. Preserve the captured account epoch plus selection/version ownership on URL requests; do not pretend merely exposing a chooser completes authorization.

## Same global preferences, theme and physical Windows ports

DesktopOriginalProfilePreferences receives the actual Root DesktopPluginStore, actual entry ownership and SessionStore atomic admission. It preserves profile six transform keys/sanitization, profile URI deletion, privacy settings+privacy_mode cache, splash history max12/move-to-front/deduplication and splash_prefs sync caches, mobile/tablet alignment, enabled/random flags, home wallpaper URI, show-edit/triple keys. No profile-specific store/MID namespace is created.

setActualOriginalTheme is REQUIRED: bind the same Root settings/theme actor that applies original theme_mode_v2, dark_theme_style_v1 migration, theme_cache and current native theme. Writing only an old Windows dark Boolean would not meet this contract. The complete original SettingsManager source and exact original setter remain pinned for the Root adaptation.

DesktopProfilePlatform requires actual Root window/DPI-derived content DP dimensions, real native-window chrome lease (restore on dispose), actual source-resource launcher image model, real Root Window file chooser and real diagnostic/feedback/clipboard actions. Android status/navigation-bar UI has no separate Windows counterpart; record the actual title/chrome mapping and do not claim an Android status-bar runtime proof. No chooser/native Window/network/account was opened by this packet.

Import image/media accepts real file URI, copies bytes while source is valid, checks caller Job/entry ownership on every read/write, validates image or video metadata with the existing Windows decoder/media services, deletes an invalid/cancelled temp, and closes all streams/metadata/native handles. Preserve GIF/video original bytes and original profile_wallpaper/splash/home_wallpaper directory conventions. Use original WallpaperImageImport as the pinned algorithm reference. URL downloads use the SAME existing owned Call.Factory and bounded body/owned file actor. All three Response objects are closed with use, including failure/cancellation. writeOwnedFile must atomically publish a complete bounded temp file with owner admission; saveImageToGallery uses the sole existing gallery actor. No raw partially written final file or stale toast/clipboard publication is acceptable.

DesktopProfileMedia must bind the existing DesktopHomeMediaLifetime/Mpv software lease/image actor. Actual39 Home wallpaper slot does not yet take alignment or repeat mode. Required extension: original wallpaper uses crop+alignment, muted loop ONE and lifecycle foreground gating; skin video uses the sole resolveProfileSkinVideoRepeatMode (including NONE). GIF animation follows original lifecycle/playing. Do not adapt to unconditional loop, ignore alignment, or add a second playback actor. New accurate media extension/Root runtime proof is a separate delta; historical Home74/actual33 receipts remain unchanged.

## Verification and explicit limits

Final narrow compile uses actual39 strict97 immutable CP with zero class overrides/prospective inputs: 24 sources, 180 classes. ABI audit checks all 2,035 candidate methods; class FQN overlap 0, package-level public/static method overlap 0, invalid JVM names 0. All 180 classes load without initialization against that same CP. Source/replay/reverse audit has 313 checks and recovers seven full original UI/VM/platform source files exactly.

Original wallpaper search calls searchAll but keeps the upstream TODO and never parses/publishes search results. It is upstream unfinished behavior, not a Windows feature claim. This packet has no Root-window/UI/media/account/network acceptance, no installed Profile backend authorization/theme/file/media bindings, no whole candidate Gradle build, and no deployed EXE. Root must satisfy required ports and then whole compile/mount/test the actual combined product before claiming operational Profile parity.
