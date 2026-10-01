# Home Windows media/platform — prepared source-only candidate

Original target: v0.2.3 `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`. Runtime dependency: immutable actual Main30, manifest `e69b7b08805fc7a6aaec8557e8ba1cfd2368b701f99ecb94edc672efd22b6b69`, ordered92CP `dcab5cb81f9c62929c07966f76fe3bfd54ee0e7b93614296c576b24f218071c0`. Parent frozen Home UI `classes-full-07` is an explicitly declared prospective dependency, not installed Main30 UI.

This candidate executes real libmpv render calls when installed. Only its source was compiled here: no DLL/playback/window/API/account/HTTP operation or runtime acceptance. No shared source/Gradle/registry/frozen payload was changed. The two mpv header reads are official pinned source downloads, not product/media HTTP.

## Exact installation

Copy only four new manual files from `prepared/desktop/src/main/kotlin`:

- `com/bilipai/desktop/player/MpvSoftwareFrames.kt`
- `com/bilipai/desktop/ui/DesktopHomeOwnedMedia.kt`
- `com/bilipai/desktop/ui/DesktopHomeWallpaperImages.kt`
- `com/bilipai/desktop/ui/DesktopHomeWindowPlatform.kt`

Apply the two `hunks/MpvNative.kt.patch` and `hunks/MpvPlayer.kt.patch` against `patch-baselines.json` LF bases. Do not copy the proof-only whole MpvPlayer/MpvNative files. The original single-boolean/default constructors remain; the optional target uses a new secondary constructor. Root retains one sole MPV implementation, DLL locator/native client and event worker. There is no second media model, decoder engine, account store, Listen session or API client. These are manual platform additions, not new original registry identities. Do not replace any registry.

Header ABI/source pin: `official-mpv-headers.json`; existing `desktop/third-party/libmpv/SOURCES.json` names the same full mpv commit `69e63f425a531f814431fba12750bdb3721357f2`. No new JAR/dependency/native binary.

## Root owner and UI ports

Create outside Home's visible-only subtree, keyed to the SAME retained Home data owner and `capturedEpoch`:

```kotlin
val media = remember(homeOwner, data.capturedEpoch) {
    DesktopHomeMediaLifetime(
        scope = data.parentScope,
        isCurrent = data.isCurrent,
        commitIfCurrent = data.commitIfCurrent,
        sourceForUrl = homePlaybackSource, // Existing immutable PlaybackSource/HTTP header policy.
    )
}
val mediaPorts = remember(media, actualListenOverlayFlow, feedback) {
    DesktopHomeActualMediaPorts(
        owner = media,
        isVideoWallpaper = { uri -> desktopHomeWallpaperIsVideo(uri, ::desktopHomeWallpaperFile) },
        imageWallpaper = { uri, imageModel, playing, modifier ->
            DesktopHomeWallpaperImages(media, uri, imageModel, playing, modifier,
                ::desktopHomeWallpaperFile, feedback)
        },
        musicOverlayVisible = actualListenOverlayFlow,
        feedback = feedback,
    ).ports
}
```

`homePlaybackSource:(String)->PlaybackSource` is required; it uses Root's current original preview URL result. The adapter does not discover/request video URLs or read credentials. Preview original request uses guest fallback; do not add account cookies unless the original source/header policy calls for them. Local Windows file URIs must be normalized to mpv `file://...` using the existing `PlaybackSegment.nativeUrl` policy (File.toURI often emits `file:/...`). Preserve immutable stream headers. No logging of sources/cookies.

`actualListenOverlayFlow` derives the existing `ListenAudioSession.state` and actual Root section visibility. It projects whether the existing NowPlayingBar is visible (`current != null`, outside LISTEN), including paused audio. Do not construct another Listen session or use its acquisition callback: Home preview does not pause/stop the main player or change SMTC/native audio ownership.

Retire `media.close()` outside the SessionStore/app gate before old-owner restore/shutdown freezes. It clears admission/frame mailboxes immediately, then only stops/closes its own source version and private player. Scope/epoch watcher is a second cleanup route; it is not a substitute for Root's synchronous lifetime retirement hook. Never wait/join native workers while retaining Store/lifetime/player/target locks. Hiding Home behind video/Favorites/audio disposes only visible preview composition; parent VM/TodayWatch owner stays alive. Restore/account epoch/app shutdown retires the parent data owner and this media lifetime together.

Raw `VideoPreviewDialog` remembers URL state without target-keyed remember. Root must key the retained full Home UI by capturedEpoch. The private nested caller requires a separate thin adaptation in the SAME parent Home producer: wrap the one original call in `key(item.bvid,item.cid)`. Root cannot access the private item through an outer wrapper. Parent owns that subsequent delta; this six-source media payload does not change frozen524. See `preview-subject-boundary.json` for exact original caller lines. The media leaf additionally keys its own lease to owner+URL+mute, checks sourceVersion and atomic owner admission, and captures current callbacks through rememberUpdatedState; it cannot fix a caller that keeps the old URL state.

## Real frame transport

Existing HWND transport stays default. The new required software target selects `vo=libmpv`, `hwdec=no`, no `wid`/D3D interop. One dedicated render thread handles every mpv_render call for the SAME core. The original client/event thread keeps every normal API/event call. Callback only wakes the render thread. It does not call MPV, Root, Store, AWT or Compose. Advanced-control is not enabled. Context destruction/join precedes `mpv_terminate_destroy`.

On replacement, sourceVersion+playbackRevision disables/clears frame output before queued load. Pending unowned/startup frames are acknowledged without publication. The original real playback-restart/output dimensions/codec receipt enables the matching source; a successful CPU render then copies immutable pixels. First-frame callback comes from that successful render result plus same-owner admission, not composition/mount. It is a CPU render receipt, not monitor/DWM presentation. Actual native startup ordering/paused-first-frame still needs the focused installed runtime gate below.

`mpv_render_param` x64 16-byte layout, 64-byte target pointer/stride alignment, `size_t` 64-bit, API string `sw`, BGRA target `bgr0` are pinned header contracts. The uninitialized fourth channel is overwritten to 255 and Skia receives OPAQUE pixels. Native buffers are not exposed; zeroed padding and copied frame memory remain private. No `report_swap` claim is made because actual monitor/vsync presentation was not observed. Default mpv video timing remains, preserving audio timing on unmuted preview. No normal client call runs on the render thread or under its mailbox lock.

Compose Image receives real rendered pixels inside the original caller modifier, so alpha/clip/blur/graphics-layer/Haze and original overlaid gradient/scrim are within Compose. There is no SwingPanel child HWND to escape those effects. Native panscan=1 preserves the original preview/wallpaper ZOOM into the actual onSizeChanged viewport. Raster budget scales aspect-preservingly to <=4096 per axis / 8,388,608 pixels; it does not truncate source/model dimensions. CPU software rendering is slower; realtime performance, HDR/color accuracy, GPU/OpenGL zero-copy and actual Haze/gradient pixels remain acceptance boundaries until observed. No transparent native-child workaround is claimed equivalent to TextureView.

## Image/GIF, window and metrics

Original `isVideoWallpaper` expression is referenced from the parent's sole selected policy. Actual file MIME supplements only that platform file path. Persisted Android `content:` URI grants do not magically migrate; required Windows chooser/prefs migration must return a real local file URI. Static AsyncImage keeps the original imageModel/request/cache/scale and Root's existing Coil loader. Local GIF/WebP uses the existing real `DesktopAnimatedSkinImage`/Skia codec, one retained decoded bitmap, actual Compose frame clock, pause/resume by playbackEnabled AND real LocalLifecycleOwner RESUMED, disposal closes only this animation. Root supplies a file URI; remote animated wallpaper and SAF migration are explicitly outside this local-document branch. Existing decoder's 32MiB/pixel/frame/time budgets are platform limits, not a changed original settings schema.

`DesktopHomeActualWindowBackground(hostWindow,data.isCurrent)` reads that actual process-owned AWT window and its modal descendants. A modal belonging to the same root remains foreground; iconify/not-visible/inactive owned window maps to background. It adds one removable AWT listener, closes old listeners and guards queued updates. This is Windows focus/client lifecycle, not Android AppBackgroundTracker timing equivalence. Root supplies real LocalLifecycleOwner independently.

`DesktopHomeActualMetrics(data.isCurrent, actualLogger)` supplies the required metric holder. Root composition's actual MonotonicFrameClock scope may run `startFrameClock` (no fabricated frame timestamps, no external telemetry). FINE is the actual logger capability. Original counter/jank state paths use the same sink; metrics are local and disappear with the window owner.

`desktopHomeActualPlatform(background,metrics,homeGraphicsLayerCaptureReady)` uses actual BlurEffect.isSupported and Root's REAL recorded-background readiness. Re-evaluate when readiness changes. Rectangular Windows client viewport explicitly selects physical screen corner radius 0; no DWM rounded-window value is invented. No Android status/navigation bar inset/Surface-resume workaround is claimed. Existing `rememberDesktopDynamicReduceMotion` Windows SPI helper is already used by the original Home producer; do not add another preference/OS watcher.

`DesktopHomeWindowsClientPolicy(hostWindow,data.isCurrent,currentActualThemeBackground,metrics)` binds ensureEdgeToEdge/applyHomeSystemBars as an explicit decorated Windows client-area policy. It updates only the real current window background from Root's actual theme Color and records the unsupported Android bar choice. It does not fabricate Android bars or remove Window decorations. Raw second argument is backgroundIsLight, not a navigation-bar dark flag.

## Necessary later acceptance gate

After Root whole compilation and new immutable graph: one small locally generated/owned video, actual pinned DLL, same MPV class and renderer, actual Compose scene. Observe changing CPU frames, first-frame receipt, actual original modifier alpha/crop/clip and scrim/layer result; paused wallpaper holds actual frame. Replace URL/source, retire same-account epoch before callback, ensure old frames/first-frame UI cannot publish, disposal affects only that lease and Root main/audio sourceVersions remain unchanged. Verify renderer frees before core and no live render thread after its worker stops. One local GIF pause/dispose gate if animation is claimed. No external video/API/account/target/share/window screenshot needed. Do not use a fake first-frame callback or declare installed acceptance from this source-only compile.
