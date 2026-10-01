# Retained real Home mount / one planner — prepared install contract

Source target remains stable `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`. This lane does not change shared source. Final prospective compilation uses immutable actual42 strict97 (Nav121/Share87 already installed). Only the existing Runtime and old TodayWatch families are overlaid; installed original Home VM, request ports, entry, four pages, CardSession, media, settings, return and NavDisplay are reused, not recompiled. This is a concrete factory/renderer/lifetime packet, not actual Root mounting or a new executable.

## Explicit installation

Install these four files only:
- prepared/manual/com/bilipai/desktop/ui/DesktopHomeRootFactory.kt
- prepared/manual/com/bilipai/desktop/ui/DesktopHomeRootRetainer.kt
- prepared/manual/com/bilipai/desktop/ui/DesktopRetainedHomePage.kt
- prepared/replacements/com/bilipai/desktop/plugins/DesktopTodayWatchRepository.kt (REPLACE old planner, do not coexist)

Apply the three semantic Runtime hunks from patches/runtime-retirement.patch after LF/base verification; do not install the task's full Runtime replacement. It is compilation evidence only. Apply Shell lifetime-only hunks from patches/shell-lifetime-only.patch after checking the current base; Root installs imports and full mount below. No new upstream identity, producer task, source directory, API, DTO, settings key, external dependency, resource or native DLL is required. The old DesktopTodayWatchRefillCoordinator may remain as unused policy/test source; no production instance or planner calls it after replacement.

The Runtime patch constructs recommendations with `{ repository?.sessionEpoch }, scope`, observes `sessionEpochFlow` (not account/MID flow), and drains recommendations before `beforeStoreFreeze`/Runtime scope cancellation. It removes the later duplicate drain. Public `state/reload/consume` call sites stay unchanged. PluginProvider and Controller READY consume wait for the one original Home owner if not yet bound; they never start the retired planner. The bridge lifetime actor serializes retire/join/construct without holding Store, projection monitor or Mutex during joining. Late old-owner projection is rejected. Old-account presentation is removed before slow draining. Failure has explicit initialization-error state; retry constructs the real factory again, never a default feed.

## App/window versus account/route ownership

Global once-per-main-window resources: the SAME Runtime.context/global PluginStore, original decoded Home preferences, wallpaper palette/cache, Theme actor, diagnostics/consent, image locations/lifetime, Root Window, actual Lifecycle, native/share actor, clipboard, root metrics/frame-clock and window capabilities. These are not keyed by MID, card epoch, selected tab, Home visibility or dialog shown. Construct DesktopOriginalHomePreferences once via its suspending create() with the already verified actual monitor default and actual cellular-network query, before ready factory publication. The settings window is the existing main Window. Do not use fake viewport size/cellular false. Supply nullable palette only when actual actor says absent.

One retained Home assembly per session epoch, stable while Home is covered by video, Favorites, Profile, music or detail: installed DesktopHomeRetainedEntry + eager original VM, same tagged request binding, complete four-page aggregate, Return38, existing Home media lifetime/Lottie and route-specific Live requests. All use the original gate and same CardSession at that epoch. Child native leases are from the existing DesktopHomeMediaLifetime; they never reference/stop Root's main or audio player. Gallery uses actual global image locations and the existing asset actor with caller Job. No second fetch/save protocol is introduced. Profile remains its real typed route/entry owner, not USER Space or a fake Home feed. Original Category/Live children preserve immutable route arguments and retire route requests on true pop/replacement; covering them with video is not an epoch retirement.

The factory requires DesktopHomeRootWindowBindings, DesktopHomeRootReturnPorts and actual repository/discovery/blocked/runtime/cardSession/Root scope. See the two classes for exact required signatures. Clipboard/link/feedback/match, consent/share, palette, media URI classification and preparation are real required consumers; no empty defaults. `overlays(gate, requests)` constructs installed DesktopOriginalHomeOverlayBindings with the actual Share87 binding and same global consent actor. Source-for-media URL must preserve existing immutable header and local-URI policy, never inherit the account Cookie implicitly. Global analytics explicitly selects the existing consent-gated local diagnostic consumer; unavailable original Firebase transport remains disclosed.

## Root effect / actual mount recipe

At ReadyApp scope (outside route/video/drawing branches), declare `homeRootRef` alongside existing Root references. The supplied Shell patch does this before Runtime and adds closeAndJoin before global freeze and before discovery guard disposal in both backup and app shutdown. ReadyApp's app/window root alive predicate must include !isClosing and restore/update retirement. Root closes Home before same global image location/native text share/diagnostic consumers.

After actual Window/resources/prefs/share/cardSession and navigation actors are ready, construct the required DesktopHomeRootWindowBindings and DesktopHomeRootReturnPorts with current-value delegates. Memoize a DesktopHomeRootFactory at epoch/CardSession and a DesktopHomeRootRetainer at Runtime/window lifetime. Do not capture mutable delegated epoch in callbacks:

```kotlin
val homeRetainer = remember(pluginRuntime, repository, actualWindowLifetime) {
    DesktopHomeRootRetainer(pluginRuntime.recommendations, { repository.sessionEpoch }, actualWindowLifetime::owns)
}
DisposableEffect(homeRetainer) {
    homeRootRef.set(homeRetainer)
    onDispose { homeRootRef.compareAndSet(homeRetainer, null); homeRetainer.close() }
}
LaunchedEffect(homeRetainer, sessionEpoch, actualFactory, homeRetryGeneration) {
    val capturedEpoch = sessionEpoch // immutable plain value
    val capturedMid = repository.account.value?.mid
    try { homeRetainer.install(capturedEpoch, capturedMid, actualFactory) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { presentActualRootHomeError(failure) }
}
val retainedRoot by homeRetainer.root.collectAsState()
```

`actualFactory` is the concrete DesktopHomeRootFactory in this packet. Retry/error are explicit Root UI bindings, not swallowed initialization failure. The Root scope must be retained independently of pages and use the actual serial UI dispatcher. Install uses Runtime's actual lifetime queue and closes/joins the previous owner before new construction. The factory drains its attempted gate/entry on construction failure, after entry cleanup; no native worker join occurs under the SessionStore/projection monitor. CloseAndJoin retires entry admission synchronously then drains native/private media on IO.

The original NavDisplay destination for **Home only** calls DesktopRetainedHomePage(retainedRoot, sameWindowBindings, actual31HomeNavigation, actualscroll/dock/clock/Haze/capture values...). No fabricated TopLevel/return flags: the page receives actual selected-top-level status and reads the SAME Return38 state. Original Category also consumes this return owner through its installed content. MainHost, Profile and other typed keys are distinct required Root renderers, not aliases to Home. Do not create new scroll globals or transition clocks. Supply the sole installed Favorites Home scroll locals and existing Dock state. The current Shell else/flat page fallback is not a complete original Home route and must not run for Home after this mount; existing history/local-favorites loader remains only for those explicit local pages.

Navigation uses the installed original BiliPaiNavBackStackController/policies, one SnapshotStateList, actual NavDisplay Host and actual window Lifecycle/VMStore/NavigationEvent owners. Required real return ports are checkpoint admission, current host origin, monotonic actual clock, existing transition clock, current original card-related/reduceMotion settings. onVideoClick must first run dispatchDesktopOriginalHomeVideo with the complete request. Preserve HomeVideoNavigationIntent CID/isVertical/source/sourceRoute and Dynamic redirect. Then Return38 enterVideo captures CardPositionManager/host geometry and prearms before original typed VideoDetail/Story navigation. Do not call the old `openVideo(VideoCard)` and discard those fields. Actual back passes real current/target keys/ancestor/related state to Return38, then original pop; related restore occurs on actual clock exposure. `onPrepare...` requires actual cover prefetch and same return session. Every non-Home destination must have a real renderer; nav-review/nav-map.json lists original31Home/17Profile required callbacks and present handlers.

Authentication invalidation receives immutable expected epoch/MID. It is called by the existing Store while its admission monitor is held; enqueue an actual Root lifecycle event, then process only under the same expected session owner. Never perform suspend/close/join inside that callback, silently relabel saved credentials guest, or perform an unguarded delayed logout on a later account. Root must wire the existing login/session authority, event feedback and entry replacement. WatchLater updates use the installed original WatchLaterBus from the VM/action protocol, not a second Root counter.

## Honest remaining Root mount requirements

The factory/class/lifetime packet is ready; full Root route assembly is not installed/proven here. The required callback map has fifteen concrete source gaps: real Profile route (not USER); original full video/Story dispatch and return geometry; actual stack/back exposure; account dialog/event; original Partition/Category; four Live child entries; precise Home vs Profile Bangumi entry; subscribed/folder favorite category/title; arbitrary Following MID; cloud vs local history/favorites; Profile active/refresh/skin policy; navigation checkpoint; embedded article settings/providers. Current handlers can be reused exactly for Settings/Search/Dynamic/Space/Downloads/WatchLater/Liked/Messages/Weekly/Plugins, respecting original parameter semantics. Do not mark missing destinations complete merely because mandatory ports compile. Native system-image share remains the existing explicit capability boundary; external Firebase transport remains unavailable. Palette/theme/native/account-selection follow their own Root installed actor contracts.

## Proof limits

Final compile/proof pins are in compile-04 and proof-02. All candidate classes load against actual42. Necessary in-process scripted owner gates prove pending startup waits, same-epoch reuse/coverage retention, same-MID epoch replacement closes/joins before construction, stale projection rejection, cancelled admission, shutdown of both bound and unbound startup. These scripts test the lifetime adapter; they do not fake acceptance of the original VM API requests, native media, Window, renderer, Profile or live Root stack. Their originalEntry code source is immutable Main.jar; only Runtime and old recommendation family are declared prospective overrides. Historical actual41/earlier compile/proof files are retained. No shared Gradle, HWND, external HTTP, user account or final package was touched.
