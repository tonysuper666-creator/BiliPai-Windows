# Offline taskId navigation bridge

This independent source-only packet installs two Windows consumers, not a replacement task store, player, client, or a declaration claiming to be the complete original `OfflineVideoPlayerScreen`. The DownloadList58 packet and its historic proof remain unchanged. This packet changes no shared source, original registry row, producer, Gradle file, or binary. Copy only the two exact `install-contract.json` payloads; the compiled JAR is evidence and must not become a product dependency.

The source route is fixed at v0.2.3 commit `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`: `AppNavigation.kt:3427` passes the exact `BiliPaiNavKey.OfflineVideoPlayer.taskId`, with an actual back action. `DownloadListScreen.kt:202` and `DownloadListNavigationPolicy.kt` route a complete task with a real file offline, or a complete task whose file is missing online when network is available. The original offline screen's early missing-task/path/file UI only offers return. The bridge preserves the existing Windows list policy at the task boundary; its explicit online callback is a platform route effect, not an invented automatic request by the original offline screen.

The required API has no default effects:

```kotlin
DesktopOfflineTaskPlayerHost(
    initialTaskId: String,
    bindings: DesktopOfflineTaskPlayerBinding,
    onBack: () -> Unit,
    onOnlinePlay: (com.bilipai.desktop.download.DownloadTask) -> Unit,
    onToggleFullscreen: () -> Unit,
    playerContent: @Composable (com.bilipai.desktop.player.MpvPlayer) -> Unit,
)
DesktopOfflineTaskPlayerBinding(
    manager: DesktopDownloadManager,
    retained: DesktopRetainedMedia,
    overlay: DanmakuOverlay?,
    entryScope: CoroutineScope,
    capturedEpoch: Long,
    currentEpoch: () -> Long,
    stillOwned: () -> Boolean,
    withOwnedAdmission: ((() -> Unit) -> Boolean),
    networkAvailable: () -> Boolean,
    playerError: () -> String?,
)
```

Create the binding once per actual OfflineVideoPlayer nav entry and captured account epoch, using that entry's real child Job/Scope and stillOwned/admission. The Scope must use the same Root Swing UI dispatcher used to publish/acquire media. Admission must recheck the captured SessionStore owner and actual entry under the existing Store→entry lock order. Root passes its existing manager, `retainedMedia` and nullable existing `danmaku`. Null is only the actual initialized capability result (for example MPV initialization failure), never a stand-in for a missing port. The bridge never constructs a player or overlay. It consumes exactly `retainedMedia.offline` and uses Root's existing acquire callback, including its existing checkpoints and release effects. File selection, managed path validation and original episode queue resolution run before admission through the same manager; no new persistent storage path or HTTP is introduced.

For Root's leaf mapping:

```kotlin
val key = actualKey as BiliPaiNavKey.OfflineVideoPlayer
// binding is retained by this key/epoch's real entry, not by an unrelated Home tab.
DesktopOfflineTaskPlayerHost(
    initialTaskId = key.taskId,
    bindings = entry.offlineBinding,
    onBack = entry::performOriginalSystemBackAction,
    onOnlinePlay = { task ->
        // Use the actual owned Root route actor, preserving authoritative fields.
        // Ordinary video: task.item.bvid / task.item.cid; PGC: task.seasonId / task.episodeId;
        // course tasks: actual Root course route, with task.isCourse. These are not synthetic DTO fields.
        entry.navigateOnlineForActualDownloadTask(task)
    },
    onToggleFullscreen = actualRootFullscreenAction,
    playerContent = actualExistingRootNativeMediaContent,
)
```

The snippet describes required Root-owned calls; it does not install methods with those illustrative entry names. Parent owns the sole leaf renderer and must map them to its concrete route actor. The network getter may reuse the now-installed DownloadList58 `desktopDownloadNetworkAvailable()`: it inspects real Windows interface state without an HTTP probe. `playerError` reads the actual Root initialization error. Actual player content and fullscreen actions remain Root-owned. The visible bridge surface consists of the existing `MediaPlaybackHeader`, native content slot, offline Danmaku toggle and original manager queue neighbors; it never shows the legacy download task list after receiving a taskId. Failed native initialization or missing/changed task displays return/error. Loaded native source actions and episode requests are guarded by task identity, epoch, entry Job and exact native version. A real task's bvid, cid, quality, audio mode, status, directory and filePath are checked again before publication. Entry cancellation rejects late validation, and cleanup joins the existing Swing actor before stopping only its own native source. A foreign source is preserved. Host disposal closes the binding; real nav entry retirement must also close/cancel that entry, including when its composition is not drawn.

Validation uses immutable actual44 and all 97 pinned ordered runtime entries, with zero existing-class overrides. The new two-file JAR has 17 loadable classes, no class/top-method overlap or invalid JVM names. Four focused groups pass 25 assertions and one real return pointer pair. The fixture uses the actual manager and an in-memory actual SessionStore admission, copied synthetic local media from the frozen actual33 fixture, existing retained.offline and the real MPV DLL/software frame path: initialTaskId publishes the selected task/version, restores its position, decodes a first frame and follows the exact original queue. Delayed supersession, child Job cancellation, changed epoch and foreign native source disposal are checked. No account, user files, network, chooser or external process is used. Fixture-only null overlay and software viewport explicitly do not prove Root HWND/overlay rendering or the full original controls.

## Complete original offline UI follow-on boundary

The entire original file is retained from its Git blob under `retained-original/feature/download/OfflineVideoPlayerScreen.kt`, with exact source hash/line count in `source-inventory.json`. All four declarations are retained: `GestureMode`, whole `OfflineVideoPlayerScreen`, `ProgressInfo`, whole `OfflineProgressBar`. No body is deleted or emitted as an alias claiming original parity.

The complete UI can reuse original Compose controls, overlays, double tap/long press/seek gestures, episode controls, audio cover, 4-second auto hide, seek feedback, progress renderer and 2-second persistence. Its required concrete Windows seam is the existing selected-task MPV view/state/control façade and same offline overlay; actual measured viewport dimensions and DPI; Root fullscreen/window lifecycle/back/PIP actor; actual player-volume/viewport brightness effects; same original SettingsManager/DanmakuPrefs/longPressSpeed keys and original gesture/chrome policies. Android `ExoPlayer.Builder`/`PlayerView`, Activity orientation/system bars, AudioManager/system brightness and MiniPlayer callbacks must be replaced by explicit Windows effects, never simulated Android classes or no-op ports. The original Player.STATE constants must map to actual MPV idle/loading/ready/ended state, not arbitrary default states. The original cover uses a real local file and only the existing shared image loader for its remote fallback. Full gesture capture over the native viewport requires the actual Root input surface; a Compose wrapper behind an HWND is not proof of gesture delivery.

The existing media producer selects only `resolveOfflinePersistedPlaybackPosition` from `OfflineVideoPlaybackPolicy.kt` into `DesktopOfflinePositionPolicy.kt`; extend that same identity/sole extraction for additional original helper methods instead of emitting that method again. `OfflineEpisodeQueuePolicy` and `DownloadDanmakuAssetService` are already original direct sources. `OfflineVideoRoutingPolicy` is already the navigation producer's direct source. Additional unselected orientation/seek/Danmaku helper methods and `OfflinePlaybackSessionPolicy` must be checked against the latest actual registry before a complete UI producer is installed. These complete policy Git blobs are retained for that review. Android orientation policy differs from the actual Windows in-window/fullscreen capability, which must be declared rather than claiming phone portrait UI acceptance.

This packet does not claim complete original offline UI, Root mount/runtime acceptance, Root HWND paint, system brightness/PIP behavior or interactive native viewport gestures. Those are the explicit next source closure, not hidden default ports in the taskId bridge.
