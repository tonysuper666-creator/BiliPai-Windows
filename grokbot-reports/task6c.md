# Task 6c: the 12 platform-independent failures

Grok Build did the analysis per class and wrote the test edits. I checked every claim for scope and spot-checked the key evidence myself. Full analyses are in `task6c-analysis-{A..E}-*.md`.

| Class (tests) | Class. | Basis | Action | Commit |
|---|---|---|---|---|
| download.DesktopDownloadBrandSuccessTest (1) + DesktopDownloadRefreshQualityTest (2) | **(i)** | `6da71260b` made `close()` async. Persist and FileLock release happen in `shutdownForApplicationExit()` (`DesktopDownloadManager.kt:944-960`). See "Production paths" below for why no production path builds a second manager. | Test awaits the existing internal `shutdownForApplicationExit()` before the cold successor. `DesktopDownloadManagerTest.kt:75` already does this. No sleeps; assertions unchanged. | `119f57fa2` |
| ui.DesktopOriginalNativeRecoveryAdmissionTest (1) + ui.DesktopWindowsVideoStatisticsLeaseRegressionTest (1) | **(i)** | `7cb07b2fc` intentionally sends `define-section`/`enable-section bilipai-window` after loadfile (`MpvPlayer.kt:2391-2400`). It relays child-window input (MBTN_LEFT/MOUSE_MOVE/SPACE) as mpv client messages, handled at `MpvPlayer.kt:2886-2893` (`MpvNativeWindowInput.kt:11-18`). The memory double `PremiumRecoveryNative` (`DesktopPremiumAudioRecoveryTest.kt:109`, from `8eb7cd2b6`) accepted only `sub-add`. | Double accepts and records the two commands (`commands` list) and returns 0. Every other command still fails the existing check; no assertion changed. All 16 users of the double were re-run. | `83098f51a` |
| ui.DesktopOriginalVideoCoverFileAdmissionTest (1, flaky) | **(i)** | Same `6da71260b` async close. `persistLocked` creates and deletes `downloads-*.tmp` (`DesktopDownloadManager.kt:872/881`) inside the @TempDir after `Fixture.close` returns. | Fixture awaits `shutdownForApplicationExit()` before the remaining cleanup (original OkHttp shutdown + evictAll kept). Gallery assertions unchanged. | `b94591ed7` |
| danmaku.DesktopBasOverlayPublicationTest (4) | **(ii)** | See "BAS overlay" below. | No product change. Proposed patch: `task6c-ii-bas-overlay-retirement.patch`. | — |
| ui.DesktopWindowsEarlyVideoDetailTest (2) | **(ii)** | See "Early video detail" below. | No product change. Proposed generator patch: `task6c-ii-early-video-detail-boundary.patch`. | — |

## Download: production paths (required check)

- The only production construction site is `DesktopShell.kt:461`: `remember(repository, storageSettingsContext, brandSuccessEvents, startupDownloadHandoffToken)`. These keys trace back to `remember {}` constants in `Main.kt:81` (applicationPluginStore) and to `DesktopApp` parameters. I verified this by reading the code. The keys do not change for the life of the composition.
- Account switch, download-dir change, plugin reload and window paths all keep the same manager. The download dir is read lazily inside the destination lambda.
- Restart is a new OS process, after `shutdownForApplicationExit()` (`DesktopShell.kt:706-709`).
- Update handoff uses `awaitUpdateHandoff` on the same object.
- Backup restore closes the discovery guard permanently and exits through `onExit` → shutdown.
- Conclusion: only the tests construct a successor while the old manager is still retiring. Hence (i).
- Separately, the product's busy-retry adoption (100 ms × 50, `DesktopDownloadManager.kt:232-257`) would restore tasks within 5 s if that ever happened. The tests read `tasks.value` before the first retry.

## BAS overlay: why (ii)

- Since `fb4db9959`, `tick()` runs only via `DesktopOriginalDanmakuFrameRequests` when `canRenderOverlay` is true (`DanmakuOverlay.kt:779`, `:1301`). Retirement of a non-current BAS installation (`:1313-1314`) is inside `tick()`.
- `recoverSource`/`load` clear `firstVideoFrameReady`/`videoCodec` (`MpvPlayer.kt:670/673`). When the overlay is hidden, or after a failed recovery, a stale `commentCount`/`basInstallation` stays published.
- The upstream v038 cadence (`v038_danmaku_cadence.py:124-127`, `v038-danmaku-cadence/DanmakuView.kt:50-57`) gates only paint scheduling, not BAS document lifetime.
- Before `fb4db9959`, `Timer(16)` cleared the stale installation before the visibility return.
- There is no test-only seam: `canRenderOverlay` is private, and it needs a realized window plus a decoded frame.

## Early video detail: why (ii)

- The failing behaviour is byte-identical to the upstream v034 `coroutineScope` (pin `v034-detail-bootstrap/VideoPlaybackUseCase.kt:482-545`).
- But `38daac558` committed an explicit desktop boundary, in the recipe at `v034-detail-bootstrap/manifest.json:63-66`: box ordinary URL failures until the info callback, and `assertOwned()` before `onVideoInfo`.
- `75522724c` (`extract-upstream-video-state-core.py:85-95`, `legacyV034SemanticDeltasNotReapplied`) stopped reapplying it, while the v037 seed is the unboxed upstream block.
- The requirement was never withdrawn, so this is (ii) and was not fixed test-only.

## Proposed (ii) patches: validation status (honest)

- `git apply --check --recount`: both apply to b94591ed7.
- `task6c-ii-bas-overlay-retirement.patch`: a brace error in Grok's first draft was found and corrected. Its effect on the 4 tests is **unverified**, because the scratch build stopped at the generator below before compiling Kotlin.
- `task6c-ii-early-video-detail-boundary.patch`: **defective as written**. In the scratch worktree the generator itself fails: `AssertionError: ('Retired owner cannot publish original detail callback', 0)`. The `onVideoInfo(info)` needle (indentation or `\n` escaping) does not match. Its intent and risk notes stand, but the needle must be corrected and the patch re-verified before use.

### Risk notes, BAS patch
- While the overlay is hidden, the count drops to the ordinary base immediately after a revoked refilter.
- A rejected publish clears one frame earlier.
- A `stillOwned` flip with no state, settings or publish change is still noticed only from `tick`.

### Risk notes, Early-detail patch
- Desktop deliberately diverges from upstream structured concurrency: a slow detail delays the typed URL error.
- `assertOwned()` is added before both `onVideoInfo` sites.
- Cancellation is still rethrown, so wrong-CID prefetch cancellation is kept.

## Results (Linux JVM)
- The 6 (i) classes plus all users of the memory double (20 classes): **205/205 passed**.
- CoverFile, BrandSuccess and RefreshQuality were rerun 3 times: 26/26 each time.

## Evidence classes
- **Linux compile:** test sources compile.
- **JVM:** as listed above (Linux).
- **Python:** n/a.
- **Unverified:** Windows cloud; whether the two (ii) patches fix their tests; real device and UI.
