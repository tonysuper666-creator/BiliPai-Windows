## D) `DesktopWindowsEarlyVideoDetailTest` (2)

**Classification: (ii) product defect.** The failing behavior is exactly upstream v034 `coroutineScope` concurrency. The test requires a desktop rule that upstream does not have, and that rule is still an explicit committed desktop requirement. `75522724c` stopped applying it. Current output is not a hand-edited deviation of the parallel block; it is the raw upstream block with the desktop boundary omitted.

### What fails

Both requests use `cid = 70`, so `resolvePlaybackBootstrapMode` selects `DETAIL_AND_PLAYURL_PARALLEL` (`DesktopOriginalVideoPlaybackUseCase.kt:42-47`).

1. `fastUrlFailureWaitsForDelayedDetailCallbackBeforeTypedError` (`38daac558`, test lines 107-132). The JUnit failure is line 132: `readonlyInfo` is null. Lines 129-131 already passed (`PlaybackLoadResult.Error`, `VideoLoadError.NetworkError`, `canRetry`). The URL child throws `IOException` (`test:113`). That fails the shared `coroutineScope`, so the still-suspended info child is cancelled and `onVideoInfo` never runs. The same `IOException` leaves the scope and the outer `catch` (`generated:466-472`) maps it to `NetworkError`.
2. `retiredOwnerEscapesCancellationWithoutPublishingLateDetail` (`38daac558`, test lines 174-188). Line 187’s `CancellationException` passes. Line 188 expects `callbacks == 0` and gets `1`. `onVideoInfo` runs inside the scope; `assertOwned()` runs only after the scope returns.

### Upstream vs current output

The parallel branch is byte-identical in three places (64 lines, `DETAIL_AND_PLAYURL_PARALLEL` through `DETAIL_ONLY`):

- Pin `desktop/upstream-slices/v034-detail-bootstrap/VideoPlaybackUseCase.kt:482-545`, `onVideoInfo` at **501**, `playUrlDeferred.await()` at **503**. Repo commit `38daac558` (pinned upstream `a69d4181a073aa3207def3fca43318dcf7ab2c70`).
- Seed `desktop/upstream-slices/v037-player-interaction-seed/VideoPlaybackUseCase.kt:484-547`, same text, `onVideoInfo` at **503**, `await()` at **505**. Commit `75522724c` (fixed upstream `8b47769d22c0d5bc88ae5248cee4aa04ff10d62c`).
- Generated `desktop/build/generated/original-video-state-core/com/android/purebilibili/feature/video/usecase/DesktopOriginalVideoPlaybackUseCase.kt:222-285`, `onVideoInfo` at **241** and **273**, `await()` at **243**. No `Result` box. `assertOwned()` is only at **175** (entry) and **293** (after the scope).

Upstream has no owner check. A failed `async` child fails `coroutineScope` and cancels the sibling, so a late detail callback is not awaited. `onVideoInfo` is before any ownership check. That is the behavior under test.

### Committed desktop requirement (why this is not (i))

Same commit as the test, `38daac558`, recipe label at `desktop/upstream-slices/v034-detail-bootstrap/manifest.json:63`:

“complete stable034 bootstrap with existing emote/ownership ports and boxed ordinary URL failure consumed only after info callback”

The recipe’s `after` body (manifest line 66) is not the upstream block. It boxes a non-cancellation playurl exception as `Result.failure` with the comment “An ordinary URL failure must wait for the original metadata result/callback before caller classification.”, calls `environment.assertOwned()` immediately before both `onVideoInfo(info)` sites, and consumes the box with `playUrlDeferred.await().getOrThrow()`.

`extract-upstream-video-state-core.py:100-102` still calls `v034_detail_bootstrap.apply_and_record` for other outputs. For this use case, `75522724c` skips it at **lines 85-95** (`legacyV034SemanticDeltasNotReapplied=True`) because “v037 inputs already include the related/detail/cache algorithms.” That is true of the bootstrap algorithm and false of the desktop boundary: the v037 seed block is byte-identical to the unboxed pin. The skip comment says to keep Root error boundaries. The boundaries that remain are the entry and post-scope `assertOwned()` swaps at **lines 193-198** (`b1ec651b88`), which do not cover `onVideoInfo`. The test file and the manifest recipe were not changed after `38daac558`. Nothing withdraws the rule. `HEAD` `b94591ed7` does not touch these files.

`v034_playurl_failure.py` adapts `DesktopOriginalVideoLoadProtocol`, not this use case. The test double throws `IOException` from `getInitialPlayUrlData` directly, so that adapter is not on this stack.

### Proposed generator patch

Do not re-enable the whole v034 recipe: its `before` hash is the pre-v034 body, and v037 already contains the algorithm. Restore only the dropped boundary in `build_usecase()`.

```diff
--- a/desktop/tools/extract-upstream-video-state-core.py
+++ b/desktop/tools/extract-upstream-video-state-core.py
@@ -196,6 +196,43 @@ def build_usecase():
         try {''','Task-owned entry admission before original cooldown/network work')
 swap('''            return detailResult.fold(''','''            environment.assertOwned()
             return detailResult.fold(''','Retired task cannot apply original load result/cooldown success')
+# v037 already has the stable034 bootstrap algorithm. It does not have the
+# desktop boundary from v034_detail_bootstrap: box an ordinary playurl failure
+# and admit ownership before onVideoInfo. Do not reapply that whole recipe.
+swap('''                        val playUrlDeferred = async {
+                            VideoRepository.getInitialPlayUrlData(
+                                bvid = bvid,
+                                cid = bootstrapCid,
+                                targetQuality = defaultQuality,
+                                audioLang = audioLang
+                            )
+                        }''','''                        val playUrlDeferred = async<Result<PlayUrlData?>> {
+                            try {
+                                Result.success(VideoRepository.getInitialPlayUrlData(
+                                    bvid = bvid,
+                                    cid = bootstrapCid,
+                                    targetQuality = defaultQuality,
+                                    audioLang = audioLang
+                                ))
+                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
+                                throw cancelled
+                            } catch (failure: Exception) {
+                                // An ordinary URL failure must wait for the original
+                                // metadata result/callback before caller classification.
+                                Result.failure(failure)
+                            }
+                        }''','Ordinary playurl failure must not cancel the original detail waiter')
+swap('''                                    playUrlDeferred.await()''',
+     '''                                    playUrlDeferred.await().getOrThrow()''',
+     'Consume boxed playurl failure only after the detail callback')
+needle='                                onVideoInfo(info)\\n'
+assert s.count(needle)==2,('Retired owner cannot publish original detail callback',s.count(needle))
+owned='                                environment.assertOwned()\\n'+needle
+s=s.replace(needle,owned)
+replacements.append(dict(label='Retired owner cannot publish original detail callback',before=needle,after=owned,count=2))
 # Windows transport restrictions must survive the original IOException branch.
 # Only the error presentation boundary changes; original requests/cooldown stay intact.
```

The `\\n` in that diff is one backslash in the `.py` source. `build_usecase` lives in an `r"""` string that is `exec`’d, and the existing `loadVideo(\n` replace on line 189 uses that same form. The inner literal must be a real newline when `exec` compiles it.

Effect on the two tests, using only the existing outer `catch`:

- A playurl `IOException` completes the child as `Result.failure`, so the info child stays active. After it returns, `assertOwned()` passes, `onVideoInfo` runs, then `getOrThrow()` throws the same `IOException` into the `catch` at generated lines 466-472 (`NetworkError`, `canRetry = true`). Order is `detail`, then `result`.
- `owned == false` makes `assertOwned()` throw `CancellationException("Original playback UseCase owner retired")` before `onVideoInfo`. That catch rethrows, so cooldown is not recorded and the caller job stays active.

### Risk

- Desktop then deliberately differs from upstream structured concurrency: an ordinary playurl failure no longer cancels the in-flight detail request, so a slow detail delays the typed URL error. Cancelling the load job still cancels both children. `CancellationException` is rethrown, so `playUrlDeferred.cancel()` still cancels a suspended wrong-CID prefetch (`cachedCidMismatchCancelsActualWrongPrefetchAndUsesResolvedCid`).
- New edge: first URL already failed and the resolved CID differs. Previously the scope died before the callback. After the patch the callback runs, `cancel()` on the completed deferred is a no-op, and the resolved CID is fetched. That matches the `38daac558` recipe. The passing mismatch test does not use a failed first URL.
- `assertOwned()` before both `onVideoInfo` sites (parallel and `DETAIL_ONLY`) throws before publication and before `recordFailure`. The post-scope `assertOwned()` at generated line 293 stays, so retirement during VIP refresh or selection still rejects the result. Passing tests in this class leave `owned == true`.
- `getOrThrow()` reuses the existing outer `catch`. No new error mapping. `v034_playurl_failure.py` stays untouched.
- `desktop/tools/tests/test_v029_failure_recovery.py` counts `catch (e: kotlinx.coroutines.CancellationException)`, not `catch (cancelled: ...)`, and the failure delta does not edit this block. Re-enabling `apply_detail` for this path would fail its `beforeSha256` or double-apply the bootstrap.