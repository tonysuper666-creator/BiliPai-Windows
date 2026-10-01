# Controller to original owner assembly inventory

Read-only source audit. No compilation or runtime proof. The old Controller is terminal after `drainForOriginalVideoOwner`, including a failed or cancelled final handoff. The existing native source remains running.

| Consumer | Exact retained contract | Assembly requirement |
|---|---|---|
| Favorites | Opaque owner, selected branch, BVID-only append, no-reload update, one-shot reveal | Replace fixed old Controller with real current-authority facade |
| Story | Same epoch/owner/revision; append vs select; foreign retry/stop rejection | Same facade and current state projection |
| Listen | Own existing audio actor, queue lease, native version; loaded pause keeps lease | Preserve branch; route milliseconds to seconds only at exact audio boundary |
| Shell / PiP / SMTC / comment / download | Current source and same authority state | Switch old `playback.state` consumers after drain |

Original `loadVideo` supports exact CID and fallback resume milliseconds. Original `seekTo` takes Long milliseconds; old Controller `seekTo` takes Double seconds. Original `switchPage` owns its request/quality/resume algorithm. These are required semantic adapters, not name aliases.

## Concrete blockers if left wired to old Controller

- **authority-handoff**: drainForOriginalVideoOwner is terminal for the old Controller, even on failed/canceled final adoption: closed is set, old producers joined outside gates, finally clears current/queue/state. It preserves the native source without stop/pause/reload. No later old Controller command or state collector may remain the ordinary authority. Root-selected current-authority facade must switch queue commands and state atomically with full owner installation; never call old close on the adopted source.
- **favorites-queue**: FavoriteQueueBridge constructor captures the old Controller. Exact opaque token and isSelectedTarget checks are required. Original append deduplicates BVID, while Controller raw queue identity is BVID/CID/page. revealIfOwned consumes its one-shot ticket and reveals the existing source; it must not reopen it. Replace only the fixed Controller dependency with the same authority facade. Transfer the same opaque queue owner from handoff; preserve BVID append and no-reload update behavior.
- **story-queue**: ControllerStoryQueuePlayer and snapshot accept the old Controller/state. Append updates the selected queue without reload, Select opens only the stable request owner, retry/release reject foreign owners and old account epochs. Provide real full-owner implementations of open/update/stop/retry/ownsQueue and current state projection, preserving the same owner identity/revision. No second story queue or controller.
- **listen**: Listen is a separate existing audio actor with exact owner/generation/native-version guards. Loaded pause keeps its queue; loading pause retires it. append only adds unseen BVIDs. Audio route resume is seconds; Controller route resume is milliseconds. Keep Listen unchanged and route only the active audio branch to it. Do not make it a compatibility scheduler for ordinary full VideoVM.
- **root-consumers**: Shell still constructs Controller Story/Favorites bridges and observes playback.state for seek, PiP, comments, favorites and download metadata. Old state becomes empty at drain. One real current-authority state/command facade must cover these callers. Fullscreen uses real Main Window placement getter/setter; never a blind toggle or inferred video aspect.
- **projection**: Original canonical UiState/subject is richer than DesktopPlaybackState. loadVideo supports aid/cid/fallbackResumePositionMs; switchPage takes page index/ignoreSavedProgress; seekTo takes Long milliseconds. Use canonical original VM and its same playlist/domain owners. Do not recreate original UI state from the old flattened Controller state or drop CID/ms/source/openId.

## Source pins

- `C:\Users\TONYS\Documents\Codex\2026-09-29\https-github-com-jay3-yy-bilipai\work\BiliPai-v023\desktop\src\main\kotlin\com\bilipai\desktop\DesktopPlaybackController.kt` LF SHA256 `4ceac1d05e306f00c66b4cf306226db96ec44b2f3fc08d31ee429395153eba6f`.
- `C:\Users\TONYS\Documents\Codex\2026-09-29\https-github-com-jay3-yy-bilipai\work\BiliPai-v023\desktop\src\main\kotlin\com\bilipai\desktop\ui\DesktopFavoriteQueueBridge.kt` LF SHA256 `1ca11e61906f6a98da809612a3068c0ea303191ad6762c93bfb0c43695f256fb`.
- `C:\Users\TONYS\Documents\Codex\2026-09-29\https-github-com-jay3-yy-bilipai\work\BiliPai-v023\desktop\src\main\kotlin\com\bilipai\desktop\ui\DesktopStoryPlaybackHost.kt` LF SHA256 `d810cd6eb19faa4055ae7052cdaf7758e7dae04436999a6b11d47172aee8e1a8`.
- `C:\Users\TONYS\Documents\Codex\2026-09-29\https-github-com-jay3-yy-bilipai\work\BiliPai-v023\desktop\src\main\kotlin\com\bilipai\desktop\audio\ListenAudioSession.kt` LF SHA256 `4f4ec3ad0dfc0aa1eb5ba5147b9632f3619d0f79961bbb783755ff853ab96523`.
- `C:\Users\TONYS\Documents\Codex\2026-09-29\https-github-com-jay3-yy-bilipai\work\BiliPai-v023\desktop\src\main\kotlin\com\bilipai\desktop\DesktopShell.kt` LF SHA256 `ea6a5c124ad58f574c9489bcd17a38a48acaf1583980725b29e1ae15a7e770dd`.
- `C:\Users\TONYS\Documents\Codex\2026-09-29\https-github-com-jay3-yy-bilipai\work\BiliPai-v023\desktop\build\generated\original-video-full-owner\com\android\purebilibili\feature\video\viewmodel\VideoPlaybackViewModel.kt` LF SHA256 `c74ece85f0e0f5a35c18de23056c9356a6b2a733ca32fcacfca4d47bdb170585`.

Full method inventory, exact line anchors and route mapping are recorded in the adjacent JSON. The full canonical VideoVM/Success/playlist remains the sole authority; this review creates no new store, cache, queue, player or UI model.
