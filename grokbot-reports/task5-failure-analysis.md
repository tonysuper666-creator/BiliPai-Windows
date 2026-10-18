# T5 JVM failure analysis

Checkout: `grokbot/full-jvm-scan-256` at `610218f8b` (2026-10-17, “Verify original comment chrome with current upstream geometry contract”). Read-only. Linux, Temurin 21, headless, `java.awt.headless=true`. Windows Gradle tasks `prepareJsWorker`, `fetchJsWorkerJdk`, `prepareJsWorkerResources`, and `prepareNativeDiagnosticShare` were excluded. The JS worker catalog hash is the zero-hash compile stub, so no JS worker resource tree exists.

Classes:

- **(a)** Windows-only / environment (Windows API, NTFS share mode, native DLL, missing JS worker resources, explicit opt-in).
- **(b)** Platform-independent real failure. The same production path fails on Windows under this test’s own double; it does not need a Windows API.
- **(c)** Stale test. The assertion disagrees with the current pinned upstream slice or the current production contract, and git history shows the contract moved after the test (or the test was never updated to the pin it claims to follow).

History used `git log` / `git show` on the test and the production or generator file. Line numbers are the tree at `610218f8b` plus `desktop/build/generated/**` from the failing run.

## 1. `DesktopCommunityRepositoryTest.requestParametersMatchUpstreamSpaceAndSearch`

- **Class:** (c)
- **Confidence:** high

The assertion at `desktop/src/test/kotlin/com/bilipai/desktop/data/DesktopCommunityRepositoryTest.kt:124` expects `communitySearchParams(...)["page_size"] == "20"`. `communitySearchParams` (`DesktopCommunityRepository.kt:703-704`) is only `desktopSearchTypeParams(...)`. The generated map at `desktop/build/generated/search/com/android/purebilibili/data/repository/DesktopSearchDeclarations.kt:90` (and the video-search map at line 107) sets `"page_size" to "30"`, extracted by `desktop/tools/extract-search-platform.py` from pinned `desktop/upstream-slices/v041-search-all/SearchRepository.kt` (`searchTypeParams`, page size 30). That pin landed in `eaa4c484e` (2026-10-16). The community test’s last commit is `f1392188d` (2026-09-30) and still expects 20. The same test already expects space `ps=30` at lines 127-128, and `DesktopSearchTest.kt:24` already expects search `page_size` 30. `core-data`’s older `SearchRepository` value of 20 is not what this desktop function compiles. The failure is the search page-size literal, not Windows.

## 2. `DesktopDynamicCacheTest.actualWindowsReplacementFailureKeepsDurableAndPublishedStateThenWriterRecovers`

- **Class:** (a)
- **Confidence:** high

The throw is in the test, before `session.markNotInterested("failed")`. `DesktopDynamicCacheTest.kt:209` opens the settings file with `com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE`. On this Linux JDK that option is rejected with `UnsupportedOperationException: NOSHARE_DELETE not supported`. The option is a Windows share-mode flag used to simulate a file that can be read but not replaced. Production is not on the stack. The test name and the open option are the Windows replacement fixture (`4bbf81119` / `263d55f01`).

## 3. `DanmakuTest` “duplicate merging keeps the original timing and styles and can be disabled”

- **Class:** (c)
- **Confidence:** high

`DanmakuTest.kt:121-124` builds four comments with text `"same"` at 0.0s, 0.3s, 1.5s, and 0.2s, the last with `color = 0xff0000`, and expects ids `[0, 3, 2]` plus text `"same x2"` and color `0xff0000` on `merged[1]`. That is the pre-v038 desktop merger: group by text+mode+color+size, default window 500ms, compare against the previous item. `git show d1555e6d9^:desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuMerge.kt` is that function; its comment said it kept different text styles separate. `d1555e6d9` (2026-10-14, “complete v038 danmaku panel color methods and original merge integration”) replaced it. Current `DanmakuMerge.kt:19-45` defaults `intervalMs` to `15_000`, coerces into `1_000..15_000`, and calls `DanmakuMerger.merge`. The compiled merger is the v038 pin (`desktop/tools/extract-upstream-danmaku-list-menu.py` emits `DanmakuMerger.kt` from `desktop/upstream-slices/v038-danmaku-merge/DanmakuMerger.kt`). That merger groups with `groupBy { it.text }` (line 41) and measures the window from the first item of the batch (lines 57-58), default 15_000ms (lines 20-27). Color is not an identity. All four comments fall in one 15s text batch, so the result is `[0]`. The scheduler path uses the same function with `DanmakuSettings.duplicateMergeWindowMs` default `15_000` (`DanmakuSettings.kt:38`, `DanmakuScheduler.kt:102`). The test’s `0xff0000` expectation last appears in `f1392188d` (2026-09-30); the test file was not updated for the v038 merger. `DanmakuMergerPolicyTest` does not require a color split.

## 4. `DesktopBasOverlayPublicationTest` (four await timeouts)

Tests, all failing in `Fixture.await` at `DesktopBasOverlayPublicationTest.kt:92` after ~5.01s:

- “same-version recovery retires the old full-source BAS publication” (line 199; count stays 1)
- “a fresh binding cannot revive a retired document owner” (line 245; count stays 1)
- “rejected final presentation admission cannot publish a processed BAS result” (line 274; count stays 1)
- “blocked old BAS worker cannot publish after a different load wins” (line 305; installation stays non-null, so the count==0 half of the predicate is not enough)

- **Class:** (b)
- **Confidence:** high

Publication itself does not need a frame. `refreshBasDocument` (`DanmakuOverlay.kt:572-594`) installs under the standalone or binding admit and calls `updateVodCount()` immediately. That is why “BAS-only XML publishes…”, “same-version valid document recovery retickets BAS”, and the other publish tests pass in milliseconds. Retirement of a now non-current `basInstallation`, and the drop of `commentCount`, happens only in `tick()` at lines 1313-1315 (`clearBasPresentation()` then `updateVodCount()`). `tick` is not on a free timer anymore. Line 779 wires `DesktopOriginalDanmakuFrameRequests(framePlatform, ::canRenderOverlay, ::tick)`. Generated `desktop/build/generated/original-danmaku-list-menu/com/bilipai/desktop/danmaku/DesktopOriginalDanmakuFrameRequests.kt:21` calls `render()` (`tick`) only when `canRender()` is already true, and line 74 sets `canRender()` to `visible()`, which is `canRenderOverlay`. `canRenderOverlay` (`DanmakuOverlay.kt:844-854`) requires a showing, non-zero, non-iconified surface and `state.ready && firstVideoFrameReady && videoCodec != null && error == null && !ended && !audioOnly`. `tick` itself returns at line 1301 before the BAS clear when that predicate is false. The fixture (`DesktopBasOverlayPublicationTest.kt:19-21`) states that no display, Skia paint, or native decoder is created. `MpvPlayer.load` (`MpvPlayer.kt:651-657`), which `recoverSource` calls, clears `videoCodec` and `firstVideoFrameReady`. Headless or not, this player never becomes paintable, so `tick` never clears the old installation and `await` times out with count 1 (or a non-null installation when the count was already 0).

This used to run without a window. Before `fb4db9959` (2026-10-14), `DanmakuOverlay` had `private val timer = Timer(16) { tick() }` and `tick` cleared a non-current BAS installation *before* the visibility return. The test was added in `1b7600295` (2026-10-05), nine days earlier. The v038 cadence port (`desktop/tools/v038_danmaku_cadence.py:124-127`, `canRender() = visible()`) moved that clear behind a predicate this fixture cannot satisfy. A Windows JVM running the same fixture also has no realized window and no decoded frame, so `canRenderOverlay` is false there too. It is not an opt-in Windows API test. Passing tests either publish through `updateVodCount` or call `clearBasPresentation` from the idle-cache path (`DanmakuOverlay.kt` around the explicit cache clear), which does not wait on `tick`.

## 5. `DesktopPreferenceReplacementRetryWindowsTest` (5 tests)

- **Class:** (a)
- **Confidence:** high

Every test calls `hold`. `DesktopPreferenceReplacementRetryWindowsTest.kt:33` is `check(os.name starts with "windows") { "Opt-in Windows native file test" }`. On Linux that throws `IllegalStateException` before JNA `Native.load("kernel32")` and before `CreateFileW` (lines 34-36), which denies `FILE_SHARE_DELETE`. No production replacement code runs. The test file’s only commit is `676f873e5` (2026-10-03); the guard is the test’s own opt-in.

## 6. `DesktopDownloadBrandSuccessTest.restoredCompletedTaskDoesNotEmitAgain`

- **Class:** (b)
- **Confidence:** high

`DesktopDownloadBrandSuccessTest.kt:143` does `restored.tasks.value.single()` and throws `NoSuchElementException: List is empty`. The first manager does complete the task (the test gets past `h.completed(manager)` and the event wait). `closeManager` (lines 59-63) calls `manager.close()` and then joins the manager `scope` job, not the retirement job. `DesktopDownloadManager.close` (lines 973-976) only `markClosing()` (which cancels `scope` at line 941) and `startCloseRetirement()` (lines 963-970), which launches `shutdownForApplicationExit()` on `retirementScope` (`Dispatchers.IO`). The durable `persistLocked(force = true, durable = true)` and the `FileLock` release happen only inside that shutdown, at lines 952-957, after `scope.cancelAndJoin()`. The new manager’s `mutableTasks` initializer (line 222) is `if (queueAuthorityHeldLocked()) restore() else emptyList()`. `DesktopDownloadQueueAuthority.tryAcquire` (lines 175-178) treats `OverlappingFileLockException` as busy and returns a null lease. That exception is same-JVM: a second `FileChannel.tryLock` on a file this process already holds fails on Windows and on Linux. The test reads `tasks.value` immediately (0.045s in the XML), before the async retirement can persist and unlock, and before the 100ms × 50 busy-retry loop (lines 232-235) can adopt the queue. Sibling brand tests that do not reopen a manager pass.

`6da71260b` (2026-10-12, “persist download queue ownership across update handoff”) made this split. Before it, `close()` called `persistLocked(force = true)` on the caller and then `scope.cancel()`, still inside the manager lock. The brand test’s last edit is `8b7226383` (2026-10-09), before that change. `shutdownForApplicationExit` still persists, but this test never calls it. The empty list is the busy-lock initial state, not a missing JSON field and not an NTFS behavior.

## 7. `DesktopDownloadRefreshQualityTest` (two cold restores)

- `fallbackStreamUpdatesActualQualityWithoutMovingTaskOrOfflineFile` at line 83 (`restored.tasks.value.single()`)
- `managerPersistenceExplicitlyStoresCreatedAtAndColdRestoresTheSameTaskIdentity` at line 686 (same `single()` after `manager.close()` at line 682)

- **Class:** (b)
- **Confidence:** high

Same production path as item 6. Both tests call `manager.close()` and construct the next `DesktopDownloadManager` on the next line, with no join of `retirementScope`. Line 222 therefore stores `emptyList()` while the previous manager’s lock is still held (`DesktopDownloadQueueAuthority.kt:177-178`). The quality test even reads the state file successfully *before* `close()` (lines 677-680), so the bytes exist; the new manager never reaches `restore()` because `tryAcquire` is busy. Times in the run are a few milliseconds, inside the async gap. The quality test’s last commit is `c68825594` (2026-10-11), one day before `6da71260b` moved persist and unlock off `close()`. A later lock-contention retry (`4de46f98a`, 2026-10-15) does not help a caller that reads `tasks.value` before the retry delay. This overlaps in-process on Windows the same way.

## 8. `DesktopUpdaterRegistryTransactionTest` (4 tests)

- **Class:** (c)
- **Confidence:** high

All four fail in `RegistryFixture` init, before any registry assertion. `DesktopUpdaterRegistryTransactionTest.kt:204-206` asks for `ActiveInstallation.getDeclaredConstructor(String, String, String, String, String)`. The production type at `DesktopUpdater.kt:1043-1050` is a six-property data class: `repository`, `version`, `executablePath`, `previousVersion`, `previousExecutablePath`, and `downloadHandoffToken: String? = null`. Kotlin emits the full primary constructor plus a synthetic `DefaultConstructorMarker` constructor, not a public five-`String` constructor, so reflection throws `NoSuchMethodException`. `downloadHandoffToken` was added in `6da71260b` (2026-10-12). The test’s only commit is `a0ece02a5` (2026-10-11), the day before, when the type still had five constructor parameters. The class still constructs and commits through the six-argument primary constructor; the fixture’s reflective signature is what aged out. The failure is identical on Windows.

## 9. `DesktopOriginalNativeRecoveryAdmissionTest.sameActualAttemptPublishesOneAacReplacementPreservingSourceControlsAndReceipt`

- **Class:** (b)
- **Confidence:** high

The thrown message is `CancellationException: Accepted ordinary source/presenter retired` from `DesktopOriginalVideoNativeOwner.kt:452` (`checkCurrent`), reached from the second `prepareLegacyDash` at test line 107 (`acceptedMedia` line 473). The first `prepareLegacyDash` (test line 93), `accept` (line 95), and `actor.drain()` plus the loadfile assertion (lines 103-104) already succeeded. `accept` sets `lease = next` and `failureConsumed = desktopFailure != null` (lines 510-511). After that, `current()` (lines 448-451) requires `player.state.value.failure == null`. `recoverSource` → `load` does clear `failure` synchronously (`MpvPlayer.kt:656`) before `accept` returns, which is why `owner.current()` at test line 96 is non-null.

`actor.drain()` then runs the queued `Action.Load`. Inside that lambda, after a successful `mpv_command_node(loadfile)` (`MpvPlayer.kt:2305`, which is why `loads.last()` has the AAC url), playlist id `"11"` makes `expectedEntry != null`, `softwareTarget` is null, and lines 2317-2318 call `mpv_command(define-section, …)` then `enable-section`. `PremiumRecoveryNative.mpv_command` (`DesktopPremiumAudioRecoveryTest.kt:109`) is `check(first arg == "sub-add")` and throws otherwise. The hook `onLoadCommandAccepted()` at `MpvPlayer.kt:2321` is after that call, so it does not run. `perform`’s catch (lines 2533 and 2595) treats any `Action.Load` exception as `publishFailure`, which writes a new `state.failure` (`MpvPlayer.kt:2943-2947`). The recovery port now sees `failureConsumed && failure != null`, so the CDN `prepareLegacyDash` is rejected. The class comment (test lines 17-18) says this is the memory ABI actor and that no DLL is started. The same stub is what a Windows run of this test uses.

`define-section` was added in `7cb07b2fc` (2026-10-15, “relay native child input through owned MPV events”), after the recovery test’s last behavioral commit `75522724c` (2026-10-14). The `sub-add`-only stub dates to `8eb7cd2b6` (2026-09-30) and was not taught the new command (`610218f8b` only attached `DesktopMemoryMpvSessionLifecycle`). A real libmpv that returns 0 for `define-section` would not take `publishFailure`. This test never calls real libmpv, and a thrown input-section command after a successful loadfile is what republishes the failure and retires the continuation the test still holds. That is a production ordering/error-handling failure of the memory-backed load path, on any OS.

## 10. Portrait metadata, sponsor presentation, and CDN selection assembly (`initializationError`)

- `DesktopOriginalPortraitMetadataPreparationTest` (`createRuntime`, test line 70)
- `DesktopOriginalSponsorPresentationAssemblyTest` (`createRuntime`, test line 99)
- `DesktopOriginalVideoCdnSelectionAssemblyTest` (`createRuntime`, test line 60)

- **Class:** (a)
- **Confidence:** high

All three XML traces are the same chain: `DesktopPluginRuntime.<init>` line 75 → `DesktopJsWorkerResources.createProcess` line 17 → `verify` line 23 → `checked("classpath.json")` line 63, `require(... Files.isRegularFile ...) { "JS worker resource path is invalid" }`. `desktopJsWorkerResources()` (`DesktopPluginRuntime.kt:624-630`) points at `compose.application.resources.dir/js-engine` or the required property `bilipai.js.workerResources`. Those trees are produced by the excluded `prepareJsWorker` / `prepareJsWorkerResources` tasks. The catalog constant is the documented zero-hash compile stub, which passes the 64-hex check at `DesktopJsWorkerResources.kt:22` and then fails because `classpath.json` is not a file. Even if that file existed, line 56 still requires `runtime/bin/javaw.exe`. No test body runs. This is the missing Windows JS-worker resource set, not a logic failure in portrait, sponsor, or CDN selection.

## 11. `DesktopOriginalVideoCoverFileAdmissionTest.actualBindingRejectsRetiredCaptureAtFinalMoveAndCleansGalleryStage`

- **Class:** (b)
- **Confidence:** medium
- **Flaky:** failed once, passed on rerun. The failure is teardown, not the gallery assertion.

The JUnit XML failure is `TempDirectory` cleanup, not a line in the test body: `IOException: Failed to delete temp directory /tmp/junit14868909206451020616` with a suppressed `DirectoryNotEmptyException` whose only listed path is `<root>`. JUnit’s walk deletes children, then `postVisitDirectory` deletes the root; a child that appears after the children were deleted and before the root delete produces exactly that “only `<root>`” message. The body assertions (retired-capture message, empty gallery, no `Cover.jpg`) are absent from the stack. The sibling `actualBindingSavesOriginalCoverBytesToConfiguredGallery` passed. The gallery stage itself is created as `.bilipai-profile-gallery-*` and removed in the binding `finally`; the expected rejection string `"Original cover click retired before file publication"` is the production throw the body asserts, and that path had already returned before `@TempDir` closed.

`Fixture.close` (`DesktopOriginalVideoCoverFileAdmissionTest.kt:82-85`) calls `manager.close()` and then `client.dispatcher.executorService.shutdown()` without awaiting either. `close()` is the async retirement from items 6-7 (`DesktopDownloadManager.kt:973-976`): it can still create `download-state.json.bilipai-lock` (the lock file at `DesktopDownloadQueueAuthority.kt:170`) or a temp sibling under the `@TempDir` (`download-state.json` is `root.resolve("download-state.json")` at test lines 67-70) after the walk has cleared the directory and before the root delete. OkHttp `shutdown()` likewise does not wait for in-flight dispatcher files. That race exists on Windows too; Windows can additionally fail with a sharing violation if a handle is still open, but this trace is `DirectoryNotEmptyException` on the root only. The rerun passing is the race not overlapping the walk.

## 12. `DesktopWindowsCommentSearchBindingTest.originalTimePagingOffsetsProgressAndTopDedupeAreConsumed`

- **Class:** (c)
- **Confidence:** high

`DesktopWindowsCommentSearchBindingTest.kt:60` expects both comment calls to use `ps=20` (`Call(..., 1, 20, 2, null)` and page 2 the same). The load path passes `ps = VIDEO_COMMENT_PAGE_SIZE` at `desktop/upstream-slices/v036-video-comment-cache/VideoCommentViewModel.kt:401` (and again at line 520). The constant is `internal const val VIDEO_COMMENT_PAGE_SIZE = 30` at `VideoCommentLoadPolicy.kt:12`, introduced in `e3e1914cb` (2026-10-13). The binding test’s only commit is `fe40c13dc` (2026-10-05), which still hard-codes 20. `VideoCommentLoadPolicyTest.kt:22` already asserts `VIDEO_COMMENT_PAGE_SIZE >= 30`. Actual `ps` 30 is the pinned v036 contract. This matches the note on the failure list.

## 13. `DesktopWindowsEarlyVideoDetailTest` (two tests)

- `fastUrlFailureWaitsForDelayedDetailCallbackBeforeTypedError`: test line 132 expected the fixture `ViewInfo`, was null. Lines 124-131 had already passed (`loading.isActive`, `NetworkError`, `canRetry`).
- `retiredOwnerEscapesCancellationWithoutPublishingLateDetail`: test line 188 expected `callbacks == 0`, was 1. `assertFailsWith<CancellationException>` at line 187 passed.

- **Class:** (b)
- **Confidence:** medium

Both requests are `PlaybackRequest.create("BVfixture", …, cid > 0)`, so `resolvePlaybackBootstrapMode` selects `DETAIL_AND_PLAYURL_PARALLEL`. The generated use case `desktop/build/generated/original-video-state-core/com/android/purebilibili/feature/video/usecase/DesktopOriginalVideoPlaybackUseCase.kt:206-263` starts info and play URL as `async` children of one `coroutineScope`. `onVideoInfo(info)` is line 241, inside the info-success fold, *before* `playUrlDeferred.await()`. There is no `isCurrent()` check on that call. The desktop ownership check `environment.assertOwned()` is at line 293, after the scope, which is the generator swap in `desktop/tools/extract-upstream-video-state-core.py:197-198` (“Retired task cannot apply original load result”). `assertOwned` (`DesktopOriginalVideoPlaybackUseCaseEnvironment.kt:95-97`) throws `CancellationException("Original playback UseCase owner retired")` when the fixture’s `owned` flag is false. `IOException` from the URL child is mapped to `VideoLoadError.NetworkError` by `DesktopOriginalPlaybackFailure.kt:35-43` and the use case’s non-cancellation `catch`, which is why lines 129-131 pass.

Test 1 sets `loadUrl` to throw `IOException` after completing `urlJob`, and holds `loadInfo` on `releaseInfo`. A failed child fails the `coroutineScope`, which cancels the still-suspended info `async` before line 241. `readonlyInfo` stays null. Line 124’s `loading.isActive` passes because this is `runBlocking`: `Job.join()` on an already-finished exceptional child does not rethrow, and the scope’s failure handler does not run until the test next suspends at `loading.await()`. That checkpoint is scheduling, not proof that the waiter is isolated. Completing `releaseInfo` then loses to the cancellation, so the detail callback never runs. The test message at line 124 requires the opposite: an ordinary URL failure must not cancel the metadata waiter, and line 132 requires the callback before the typed error.

Test 2 leaves the URL successful, sets `owned = false`, then releases info. The success fold still calls `onVideoInfo` at line 241, so `callbacks` becomes 1. `assertOwned()` at line 293 then throws, which satisfies line 187 and fails only line 188.

The parallel block is pinned v034, unchanged since `38daac558` (2026-10-12), the same commit that added this test (`desktop/upstream-slices/v034-detail-bootstrap/VideoPlaybackUseCase.kt:499-520` is the same `onVideoInfo`-then-`await`). The generator comment at `extract-upstream-video-state-core.py:199-200` says original requests stay intact and only the error-presentation boundary changes. `v034_playurl_failure.py` adapts the repository protocol’s fallback witness, not this use case, and the test double throws `IOException` directly, so that adapter is not on this stack. Two sibling tests in the class pass; they do not cover “URL failure must not cancel detail” or “retirement must suppress `onVideoInfo`”.

This fails on Windows for the same generated file. It is not a missing DLL or a headless AWT limit. Confidence is medium because there is a real (c) reading: if the contract is “copy v034 `coroutineScope` and only reject the final result”, test 1’s isolation sentence and test 2’s callback count are ahead of that pin. The (b) call is the desktop rule the test states and that `assertOwned` was added to enforce: a retired owner must not publish late detail, and a fast URL failure must not drop the in-flight info callback. The hook is after `onVideoInfo`, and the scope cancels that callback. Those are the lines that fail the tests.

## 14. `DesktopWindowsVideoStatisticsLeaseRegressionTest.acknowledgedSourceCopiesExactBvOnEdtOutsideActualAdmissionAfterRequestJobRetires`

- **Class:** (b)
- **Confidence:** high

`DesktopWindowsVideoStatisticsLeaseRegressionTest.kt:210` is `assertTrue(origin.nativeSource.source.nativePublication.admit {})` after `f.initialJob.cancel()` at line 209. The comment at lines 207-208 says the loadfile ACK already consumed the transient request guard, so cancelling that job must not revoke the accepted source. Seven sibling tests in the class passed; they do not cancel `initialJob` before `admit`.

`publishCapturedSource` installs `DesktopOriginalVideoInitialPublication`. Its `current()` (`DesktopOriginalVideoNativeOwner.kt:157-158`) is `ownsAccepted() && (consumed || (!requestJob.isCancelled && isRequestCurrent()))`. `admit` (lines 160-168) returns false on `CancellationException`. `consumed` is set only by `onLoadCommandAccepted()` (line 171). The class comment (lines 141-146) says MPV invokes that hook only after a real loadfile and a playlist-entry readback, and that a stale command must not consume the guard.

The hook in `MpvPlayer.kt` is inside the load lambda at line 2321, after `mpv_command(define-section)` and `enable-section` at lines 2315-2319. The fixture uses `PremiumRecoveryActor` / `PremiumRecoveryNative` (the same memory ABI as item 9; this test does not load libmpv). `mpv_command` throws unless the command is `sub-add` (`DesktopPremiumAudioRecoveryTest.kt:109`). Playlist id `"11"` is present, so `expectedEntry != null` and the stub throws before the hook. `perform` catches it (line 2595) and `publishFailure`s. `loads.size == 1` still holds because loadfile was recorded at line 2305, and `loaded()` then clears the “still loading” checks, so fixture init passes while `consumed` stays false. Cancelling `initialJob` makes `current()` false and `admit {}` returns false. `define-section` arrived in `7cb07b2fc` (2026-10-15), after this test (`104fcdc68`, 2026-10-11). The same memory double fails on Windows. A healthy libmpv `define-section` would reach the hook and this assertion could pass; the defect the test hits is that an input-section failure aborts ACK while leaving the source looking accepted (`isCurrent` still true, request job still the guard).

## Compact table

| # | Class | Confidence | Test | Cause in one line |
|---|-------|------------|------|-------------------|
| 1 | (c) | high | `DesktopCommunityRepositoryTest.requestParametersMatchUpstreamSpaceAndSearch` | Test line 124 expects search `page_size` 20; v041-generated `desktopSearchTypeParams` is 30 since `eaa4c484e`. |
| 2 | (a) | high | `DesktopDynamicCacheTest.actualWindowsReplacementFailure…` | Test line 209 opens `NOSHARE_DELETE`; Linux JDK rejects that Windows share option before production runs. |
| 3 | (c) | high | `DanmakuTest` duplicate merging | Test expects color-split 500ms merge `[0, 3, 2]`; v038 `DanmakuMerger` groups by text in a 15s window (`d1555e6d9`). |
| 4 | (b) | high | `DesktopBasOverlayPublicationTest` (4 awaits) | BAS retirement runs only in `tick()`, and `tick` now requires `canRenderOverlay` (showing surface + decoded frame). Fixture has neither. |
| 5 | (a) | high | `DesktopPreferenceReplacementRetryWindowsTest` (5) | `hold` line 33 throws “Opt-in Windows native file test” before `kernel32`. |
| 6 | (b) | high | `DesktopDownloadBrandSuccessTest.restoredCompletedTaskDoesNotEmitAgain` | `close()` no longer persists or unlocks before return; the new manager sees a busy `FileLock` and starts as `emptyList()`. |
| 7 | (b) | high | `DesktopDownloadRefreshQualityTest` (2 cold restores) | Same async `close()` / overlapping lock as 6; both tests read `tasks.value.single()` immediately. |
| 8 | (c) | high | `DesktopUpdaterRegistryTransactionTest` (4) | Fixture reflects a 5-string `ActiveInstallation` ctor; `downloadHandoffToken` made it 6-arg in `6da71260b`. |
| 9 | (b) | high | `DesktopOriginalNativeRecoveryAdmissionTest.sameActualAttempt…` | Recovery `drain` hits `define-section`, the memory stub throws, `publishFailure` sets `failure`, and `failureConsumed` retires the next `prepareLegacyDash`. |
| 10 | (a) | high | Portrait, sponsor, and CDN assembly init | `DesktopJsWorkerResources.checked` line 63: excluded JS worker prep, no `classpath.json` (and no `javaw.exe`). |
| 11 | (b) | medium | `DesktopOriginalVideoCoverFileAdmissionTest.actualBindingRejectsRetiredCapture…` | Flaky `@TempDir` root `DirectoryNotEmptyException` after async `manager.close()`; gallery assertions are not in the stack. |
| 12 | (c) | high | `DesktopWindowsCommentSearchBindingTest.originalTimePaging…` | Test expects `ps=20`; pinned v036 `VIDEO_COMMENT_PAGE_SIZE` is 30 (`e3e1914cb`). |
| 13 | (b) | medium | `DesktopWindowsEarlyVideoDetailTest` (2) | Generated v034 `coroutineScope` cancels detail on URL failure and calls `onVideoInfo` before `assertOwned()`. |
| 14 | (b) | high | `DesktopWindowsVideoStatisticsLeaseRegressionTest.acknowledgedSourceCopiesExactBv…` | `define-section` throws in the memory stub before `onLoadCommandAccepted`, so cancelling the request job still fails `admit`. |
