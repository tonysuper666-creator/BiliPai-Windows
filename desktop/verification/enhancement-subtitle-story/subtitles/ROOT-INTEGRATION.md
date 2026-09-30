# Subtitle cancellation, credential ownership, refresh and partial-load parity

Frozen isolated implementation: **31/31 focused JUnit tests passed**, then **50/50 original settings/HiRes/controller/queue tests passed**, with independent Kotlin 2.4 compilation. Actual loopback sockets were used for metadata and document HTTP. No shared Gradle, native DLL/HWND, real account request, user settings or main file was changed. Earlier 12 native gates, FSR evidence and 50-test foundation files were kept frozen.

Only two main product files are proposed for replacement. `owned-files.json` contains exact baseline and desired byte SHA-256 values; `apply-owned.py` defaults to checking those hashes and applies only with Root's explicit `--apply`. It never touches MpvPlayer, PlayerVideoShaders, Controller, Shell or the BiliSubtitleDocument source. Thus the Root's independently merged FSR renderer cannot be overwritten by this slice.

Baseline hashes:

- DesktopSubtitleAssets.kt: `0af27de3f459f3f7525edf01d7aa02960ab4b713d3540c3ce19353a209659360`
- DesktopAutomaticSubtitles.kt: `7724a3e30b1d928064f7d4dec9436e8aa9d742f260a2e565b8c370b704bc0d99`
- Unmodified BiliSubtitleDocument.kt: `13aeb13df3d7f7b159107983f3c1b7e1ab07c1b9ecee3c745972435402d5bdc0`

## Root hooks and constructor

`DesktopAutomaticSubtitles(source, player, dispatcher = Dispatchers.Default, sessionEpoch: () -> Long = { 0L })` retains the first three parameters and adds the getter last. Pass `sessionEpoch = { repository.sessionEpoch }` in the real app. Call `onSessionChanged()` at the credential-epoch transition, including new credentials for the same MID. Getter checks also run before/after metadata, document import, original cue lookup, refreshed metadata/import, installation and display-mode changes; noncooperative old results cannot install.

An epoch transition cancels the session job, resets binding/manual owner/mode/tracks/error to the existing original Windows state shape, and retires only a previous automatic native pair whose source AND exact control version remain current. That retirement increments mpv's existing subtitle-control generation, so an older pending actor transaction is rejected. A new media owner or a newer manual control is never cleared. Actual application-owned documents stay on disk until DesktopSubtitleAssets.close; no epoch/session method deletes them. Continue closing/stopping the player before closing asset storage.

Call `onUserTrackSelection` immediately before a successful actual user track mutation after captured-target validation. Opening/dismissing a dialog must not trigger it. This slice does not edit the Root's fixed Shell action.

`DesktopAutomaticSubtitleDataSource` keeps `metadata` and `import` signatures and adds a default `suspend fun cues(file: Path): List<SubtitleCue>? = null`. The real RepositoryAutomaticSubtitleDataSource supplies the original parsed cue list via the actual asset cache. The nullable default is compatibility for existing file-only fixtures; it does not fabricate cue data. No new wire model or UI state schema was introduced.

## Native file and HTTP binding

DesktopSubtitleAssets registers only its own OkHttp Call objects. A cancellable continuation owns the entire header/body download, so canceling the coroutine invokes Call.cancel even while response-body read blocks. Asset close snapshots and cancels only its owned Calls; it never cancels unrelated work on the shared client. Response bodies always close. Both declared and streamed 8 MiB bounds remain. Files are written under the existing ownership lock through a temporary sibling and atomic move, with cancellation checks around commit; closed/canceled work cannot write a late partial cache. Already valid same-URL documents are reused and not overwritten by a competing canceled import.

The body parser and Windows SRT adapter remain the actual BiliSubtitleDocument/original parseBiliSubtitleBody. Its cue list is retained with the cache for original quality decisions. Request flags reproduce the upstream VideoRepository.getSubtitleCues builder: FORCE_NETWORK, GET, Referer=https://www.bilibili.com, Cache-Control=no-cache and Pragma=no-cache. All requests use the supplied existing client; cookies and client interceptors are not reconstructed or replaced. No real credentials were used in fixtures.

## Original source registration

Copy `extract-subtitle-load-policy.py` into Root-owned tools, add its generated source directory/task, and merge `source-inventory.json` into the source manifest. It extracts the exact original `SubtitleTrackLoadDecision` and four functions from VideoPlaybackViewModel.kt without changing bodies: buildSubtitleTrackBindingKey, shouldRetrySubtitleLoadWithPlayerInfo, isLikelyLowQualitySubtitleTrack, resolveSubtitleTrackLoadDecision. Imports only bind existing original SubtitleCue and normalizeBilibiliSubtitleUrl. No new library or protocol dependency is required.

The adapter follows the original retry flow: HTTP 401/403/404/410/412 trigger at most one new playerinfo request; failed tracks first match the original binding key (ignoring expired signed query) and then language; already successful tracks are retained. No second refresh loop is allowed. Loaded cues are then passed through the exact original single-track/low-quality decision; a surviving secondary may become the primary native slot. Errors shown by the automatic UI stay generic and cannot expose transport URLs or credentials.

Copy `DesktopSubtitleCancellationTest.kt` to player tests. Its two test classes contain 31 individually reported focused cases. Existing `DormantActor` from DesktopAdvancedPlaybackSettingsTest is reused once to execute the real private Session.perform with a fake C ABI: an epoch-retired old action produces zero native calls and the replacement action sets both visibility properties to no. Other focused cases use actual TCP sockets and the generated original Retrofit BilibiliApi.getPlayerInfo. Compiler/JUnit runners here are isolation tools and should not be copied into product test source sets.

Not claimed: actual remote Bilibili behavior/account privileges, native font/renderer behavior after this new subtitle slice, or the newly merged FSR provider. The 12 prior native settings gates remain valid historical evidence for their exact frozen main sources, not proof that this new draft is packaged. Root's final shared tests/package remain required.
