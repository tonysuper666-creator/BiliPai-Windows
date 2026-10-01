# Full fixed-tag Offline source, prospective Windows integration

This packet is source only. Fixed target is v0.2.3, commit `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`. It does not change the frozen initial taskId bridge 3146 or DownloadList58. Root has already installed initial bridge 3146; apply its five local hunks, not the compile-input whole copy.

The producer retains all four declarations from the original 1,225-line OfflineVideoPlayerScreen: GestureMode, OfflineVideoPlayerScreen, ProgressInfo and full OfflineProgressBar. There are 60 explicit UI/platform edits with an exact positional inverse. Six original gesture/seek/layer policies are selected, the existing persisted-position policy is referenced, the complete original session metadata policy is DIRECT once, and the original long-press getter shares the existing global settings namespace/default/normalization. Android phone orientation helpers remain retained review source: actual Windows full-screen/window placement and physical-monitor Danmaku presentation replace those platform operations.

Install only these four files:

- `prepared/desktop/tools/extract-upstream-offline-player.py`
- `prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalOfflinePlayerBindings.kt`
- `prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOfflineMpvControl.kt`
- `prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOfflineRootMediaEffects.kt`

Apply `bridge-delta.json` (5 hunks) and `overlay-delta.json` (16 hunks) in their recorded order. They include source-owned viewport dim, same-loader document owner guards, and a manual original-consumer branch of the existing bridge. Do not install any `compile-inputs` whole file. Both deltas pass exact inverse checks. Preserve Root48/49 Shell and physical owners.

Merge `registry-delta.json`: two new original identities (Offline UI and DIRECT OfflinePlaybackSessionPolicy); merge two existing identities (OfflineVideoPlaybackPolicy and PlayerSettingsStore). Do not add another persisted-position helper, task DTO, Store, player, HTTP client or GestureLevelOverlay renderer. The normal video lane exclusively supplies the full shared gesture renderer and `DesktopOriginalPlayerSurface`. Production extractor skips the DIRECT session policy; Sync copies it once. Standalone generation includes it only for the narrow proof.

Add a source task invoking the producer with `--repo <Candidate> --output <generated-output>`. Default production verifies all four exact fixed registry features/Git blobs. For example use feature `stable-offline-player-original` and register the producer generated output with the existing Kotlin source set/task scheme; no new dependency. Validate generated three files against `generation-audit-final` and the recorded source receipt; exact source generation is checked by the freezing runner.

## Actual retained leaf constructor

The existing `DesktopOfflineTaskPlayerBinding` continues to use Root's same manager, retainedMedia.offline, MPV, Overlay, entry child scope, captured epoch, volatile repository.sessionEpoch getter and Store→entry admission. It is the only load/release backend. Its original-consumer branch lets the complete original UI own its two-second checkpoint and local asset effects; the old bridge path keeps its previous behavior. Root supplies `gate::owns` or an equivalent nonblocking read-only native/page/Job/epoch/MID predicate: the offline document callback must never acquire SessionStore/entry monitors or perform writes from the Overlay request lock.

Create `DesktopOriginalOfflinePlayerBindings(context, backend, entryScope, preferences, presentation, window, surface, media, feedback)`, with:

- `context`: same actual global DesktopPluginContext, never another store.
- `entryScope`: same captured taskId leaf Job/scope that closes the backend on disposal/account replacement.
- `preferences`: the existing DesktopOriginalDanmakuPreferences over the same global Store and original block preferences/admission. Do not create another persistence authority. Use actual current original values, not an empty initial block string.
- `presentation`: same Root physical monitor + actual window-placement binding. Inline is original PORTRAIT; no video/window aspect inference.
- `window`: required DesktopOfflineWindowEffects. `applyFullscreen(target)` uses actual Main WindowState placement / Root callback: toggle only when `(placement == Fullscreen) != target`. `restoreCurrentRootChrome()` delegates to the same actual Root chrome authority (e.g. existing DesktopWindowsProfileChrome.refreshCurrentRootTheme/clientPolicy); it does not restore captured colors or pretend Android system bars. The active Root navigation/window actor decides placement on leaf exit; preserve Root/fullscreen back priority.
- `surface`: required DesktopOriginalOfflineSurface.Render(control, modifier, foreground), delegating to the normal-video lane's sole `DesktopOriginalPlayerSurface(control.nativePlayer, control.sourceVersion, control::isForegroundOwned, modifier, foreground)`. Loading foreground permission is separated from all native writes. Never pass control::isOwned here, because the loading source is deliberately null. This retains the entire original foreground Box/pointerInput/chrome above the existing same MPV Canvas via the actual shaped command popup. Retire the old PlayerPanel command popup for this same surface: one carrier/Canvas owner at a time.
- `media`: `DesktopOfflineRootMediaEffects(actualSystemMedia, actualPip, backend, feedback)`, passing the existing actors only. Nullable actor capability is allowed solely for real initialization unavailability; the adapter reports unavailable SMTC. No new actor or silent false success.
- `feedback`: actual Root feedback in the same live leaf/page admission.

Mount `DesktopOriginalOfflinePlayerHost(taskId, bindings, onBack)`. The typed taskId is required and unchanged. This replaces the initial binding surface when the full source dependencies have been installed; do not wrap DownloadBrowserScreen or a no-argument task picker. The two original missing-file error screens remain. Only the existing list routing policy does missing-file→online; the full screen does not invent a new network fallback. Native initialization failure adds a visible Windows return surface before any native surface access.

## Root native commands, PiP and metadata

The original Offline page has no PiP button: it registers the external player with the global MiniPlayer manager. On Windows, the actual Root global PiP action must call the existing `pip.open(hostWindow, originalTaskTitle)` only when this leaf/control owns the exact native token and the current source is video (not audio-only). Existing queue actions are backend.memory.previous/next, and in original mode they update the UI's original currentTaskId; do not independently load twice. All native writes use the strict source/entry facade gate.

The normal-video shared surface currently has no PiP-active/surfaceOnly tail. Root must consume actual pip.active and refrain from attaching the Canvas/popup in the main window while the existing PiP window owns it. The original foreground controls may be rendered in the actual client area with the real “正在浮窗播放” binding status, or through a follow-on sole surface slot; no second native surface, actor or dispatcher. This is mandatory integration, not accepted by this packet's fixture-only Compose carrier.

Root49 is independently fixing Shell SMTC controls. Retained current native owner must take priority over stale ordinary playback.details for seek/FF/Rewind. NEXT/PREVIOUS on a retained source with no queued target must not fall through into ordinary playback. PIP's actual seek already uses retained ownership. Stop/dispose can clear only this exact source. A new foreign source must remain intact.

The existing 500 ms Root SMTC loop must derive the original offline metadata when `retainedMedia.current === retainedMedia.offline` and the exact accepted native token is current: look up the actual manager task by `retainedMedia.offline.current`, call original `resolveOfflinePlaybackSessionMetadata(task.item)` / `resolveOfflineMiniPlayerPayload(task.item)`, then use title/artist/bvid for the same WindowsMediaSnapshot. Otherwise that loop overwrites the adapter's correct artist/mediaId with its legacy empty artist/sourceTitle. Preserve other source branches and account/publication changes; Root owns the precise Shell hunk. The original cover is actually rendered by full Coil UI. Existing WindowsMediaSession has no cover-art ABI; OS thumbnail publication remains an explicit difference, never a false thumbnail/upload claim.

## Focused evidence and pending actual Root acceptance

Narrow compilation uses immutable actual47 strict 97-entry CP plus the explicitly recorded prospective full gesture JAR and only the declared existing Overlay / initial bridge source deltas. There are no shared edits, Gradle runs, installed JARs or user media/credentials. Real unchanged MPV DLL and a previously frozen synthetic local clip prove the actual facade/source/queue/checkpoint effects. The fixture uses the actual SessionStore, manager, global preference Store, original full UI, a real next-episode pointer pair, and same Overlay Java2D painting/late-publication admission. Window/fullscreen/SMTC/PiP boundaries in that fixture are explicit recording ports, not Root native acceptance. Physical monitor input is a declared synthetic fixture value, not a Root measurement.

Root whole compilation, actual HWND foreground pointer/dim behavior, real Root fullscreen/chrome, actual existing SMTC/PIP and the final leaf mount remain integration acceptance. Do not treat source compilation, synthetic software first frame or offscreen original UI as those acceptances. Historical compile/proof failures are retained; final compile/proof hashes are selected explicitly in install-contract/frozen-handoff.
