# T8b: early video detail boundary (isolated branch grokbot/fix-early-video-detail)

- Base: `origin/grokbot/r1-test-currency` @ `3d0deb5cf`. Not merged.
- Fix commit: `456c38d69`. Report: the next commit.
- Code by Grok Build. I checked the plan for scope and reviewed the diff: one generator `desktop/tools/extract-upstream-video-state-core.py` (+75 lines, additions only) and one new Python test.
- No test, upstream slice, manifest, hash pin, build.gradle.kts or protected file was changed. LF.

## 1. Where the rule lives, and its history
- It is **not** in `desktop/PARITY.md`, `desktop/WINDOWS.md` or `docs/` (searched for the rule's wording).
- Committed statements, all from `38daac558` (2026-10-12, "Align Windows playback cache and early detail with v0.3.4"):
  - `desktop/upstream-slices/v034-detail-bootstrap/manifest.json:63`: recipe label "complete stable034 bootstrap with existing emote/ownership ports and boxed ordinary URL failure consumed only after info callback".
  - The `after` body on `:66`: boxes non-cancellation play-URL failures as `Result.failure` with the comment "An ordinary URL failure must wait for the original metadata result/callback before caller classification.", calls `environment.assertOwned()` before both `onVideoInfo(info)` sites, and consumes the box with `await().getOrThrow()`.
  - `desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopWindowsEarlyVideoDetailTest.kt:107-140` ("Ordinary URL failure must not cancel the metadata waiter", line 124) and `:174-191` (retired owner publishes no late detail).
  - `desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRequestCaches.kt:136-146` (the same rule at the cache layer).
- Stopped being applied: `75522724c` (2026-10-14). `extract-upstream-video-state-core.py:85-102` sets `legacyV034SemanticDeltasNotReapplied=True` and skips `v034_detail_bootstrap.apply_and_record` for the use case, because "v037 inputs already include the related/detail/cache algorithms". The remaining `assertOwned()` swaps (from `b1ec651b88`) sit outside the `coroutineScope` and do not cover `onVideoInfo`.
- **Withdrawn? No.** `git log 75522724c..HEAD` shows no change to the test, the manifest or the docs. `git log -S` for the rule's wording finds only `38daac558`.

## Conflict with upstream (documented, not ruled on)
- The pinned upstream parallel block is byte-identical in three places (SHA-256 `fd4c49c1…`, 3220 B):
  - `v034-detail-bootstrap/VideoPlaybackUseCase.kt:482` (upstream `a69d4181`, `onVideoInfo` @501, `await()` @503);
  - `v037-player-interaction-seed/VideoPlaybackUseCase.kt:484` (upstream `8b47769d`);
  - `v037-player-closure/VideoPlaybackUseCase.kt:484`.
- In that block a play-URL `async` failure fails the `coroutineScope` and cancels the detail sibling, and `onVideoInfo` has no ownership check.
- The desktop rule (`38daac558`) deliberately does the opposite.
- **This is an explicit desktop-vs-upstream behavioural difference. Root should decide whether the desktop rule stands.** This branch implements the rule as committed because nothing withdrew it.

## 2. Why the 6c patch failed, and the rewrite
- The 6c patch put `'…onVideoInfo(info)\\n'` (two backslashes) into the `r"""` exec string. `exec` turned that into a backslash plus "n" instead of a newline, so the needle count was 0.
- A corrected needle would still not have failed closed on double application: the `after` text contains the `before` text, and `await()` is a prefix of `await().getOrThrow()`.
- The rewrite is a module-level `apply_early_video_detail_boundary(body)`, called from `build_usecase()` after the fold swap. Three anchors, each with an exact count asserted:
  - the play-URL `async { … }` block (1);
  - `playUrlDeferred.await()\n` (1);
  - `onSuccess = { info ->\n` + `onVideoInfo(info)\n` (2: the parallel and DETAIL_ONLY sites).
- Inverse proof:
  - Every edit's offset is recorded; undoing them in reverse must reproduce the pre-adaptation text byte for byte (SHA-256 equal).
  - A replace-all inverse must do the same.
  - `after.count(before)==0` is asserted for every step, so a second application fails closed.
  - Records are appended to `usecase-adaptations.json`. The whole v034 recipe stays disabled.
- Hash pins: the Gradle task treats the script as an input and has no output hash. The manifest before/after hashes are used only by the disabled recipe path. `SOURCE_PINS` is checked before this step. **No pin changed.**

## 3. Results
| Check | Result |
|---|---|
| `test_early_video_detail_boundary.py` (new: anchors, inverse, double application, generated output) | 3/3 OK with the `\\?\` shim. Plain Linux fails with the known Windows long-path prefix (Linux-only problem). |
| `test_v029_failure_recovery.py` (existing tests for this generator) | 2/5 fail, **identical names and messages on base `3d0deb5cf`**, so they predate this change (afterSha mismatch in the related/whole-VM inverse). Not introduced here and not fixed. Worth a separate look. |
| Generated `DesktopOriginalVideoPlaybackUseCase.kt` | `async<Result<PlayUrlData?>>` ×1, `await().getOrThrow()` ×1, `assertOwned()` directly before both `onVideoInfo(info)` |
| compileKotlin + compileJava + compileTestKotlin + compileTestJava | BUILD SUCCESSFUL, 0 errors (`extractOriginalVideoStateCore` re-ran) |
| DesktopWindowsEarlyVideoDetailTest | **4/4** (the 2 earlier failures now pass) |
| DesktopWindowsRelatedVideosRegressionTest, DesktopWindowsVideoLoadErrorTest, DesktopOriginalNativeRecoveryAdmissionTest, DesktopUnifiedPlaybackAudioSelectionTest, DesktopOriginalPlaybackRecoveryTest | all pass (45/45 together with the class above) |
| Whole `com.bilipai.desktop.ui.*` | 1347 tests, 3 failures: the known Linux-only JS-worker `initializationError` (Portrait/Sponsor/Cdn). No new failures. |

## Risks
- Desktop deliberately differs from upstream structured concurrency: a slow detail request delays the typed play-URL error. Cancelling the load still cancels both children, and `CancellationException` is rethrown, so wrong-CID prefetch cancellation keeps working.
- If the first URL fails and the resolved CID differs, the detail callback now runs before the resolved CID is fetched. This matches the `38daac558` recipe.
- `assertOwned()` before `onVideoInfo` throws before publication and before `recordFailure`.

## Evidence classes
- **Linux compile:** OK.
- **Python:** new test 3/3 (with the shim); v029 2 failures pre-existing.
- **JVM:** as listed above (Linux).
- **Unverified:** Windows cloud, real network timing, real device, UI.
