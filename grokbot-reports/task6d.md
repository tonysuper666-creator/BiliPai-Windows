# Task 6d: full JVM rescan on grokbot/r1-test-currency

- Commit scanned: `b94591ed7`. That is HEAD after all 6b/6c test changes; later commits are reports only.
- Batching was the same as Task 5 (one Gradle at a time). The `nvidia.*` package was added to the leftover batch.
- prepareVeyraRuntimeVerifier was not excluded. The usual 3 Windows `-x` exclusions plus `prepareJsWorkerResources` were used.

| Batch | Classes | Tests | Fail | Skip | Gradle |
|---|---|---|---|---|---|
| android-upstream | 134 | 1086 | 0 | 0 | OK |
| data | 45 | 375 | 1 | 0 | FAILED (tests) |
| desktop-misc1 | 76 | 496 | 9 | 0 | FAILED (tests) |
| desktop-misc2 | 34 | 322 | 0 | 5 | OK |
| desktop-root | 6 | 30 | 0 | 0 | OK |
| leftover (+nvidia) | 10 | 249 | 0 | 0 | OK |
| player | 42 | 439 | 0 | 0 | OK |
| ui | 188 | 1347 | 5 | 0 | FAILED (tests) |
| **Total** | **535** | **4344** | **15** | **5** | |

- Passed: 4324.
- The 5 skips are the same Windows assumptions as in Task 5 (DesktopDiagnosticsTest ×3, DesktopUpdaterSafetyTest ×2).
- Not run by the test task: the same 3 tagged classes as before (DesktopUpdaterIntegrationTest, NativeDownloadMuxTest, DesktopJsPluginIntegrationTest).
- Every other `@Test` source was executed.

## Compared with Task 5 (523 classes / 3984 tests / 28 failures)
- **New failures: 0.**
- **12 new classes, all passing:** 8× nvidia, DesktopCommentAvatarSettingsTest, DesktopUserVipPinkTest, V046CommentAvatarPushOriginalPolicyTest, V046SubReplyDetailPresentationOriginalTest.
- **13 failures gone:**
  - DanmakuTest 1, DesktopCommunityRepositoryTest 1, DesktopUpdaterRegistryTransactionTest 4: fixed in 6b.
  - DesktopWindowsCommentSearchBindingTest 1: already fixed by `ade424f63` on this base.
  - DesktopDownloadBrandSuccessTest 1, DesktopDownloadRefreshQualityTest 2, DesktopOriginalNativeRecoveryAdmissionTest 1, DesktopWindowsVideoStatisticsLeaseRegressionTest 1, DesktopOriginalVideoCoverFileAdmissionTest 1: fixed in 6c.
- **15 failures remain, classified:**
  - **Windows-only / Linux environment, 9:**
    - DesktopDynamicCacheTest ×1: NOSHARE_DELETE is not supported on Linux.
    - DesktopPreferenceReplacementRetryWindowsTest ×5: "Opt-in Windows native file test".
    - DesktopOriginalPortraitMetadataPreparationTest, DesktopOriginalSponsorPresentationAssemblyTest, DesktopOriginalVideoCdnSelectionAssemblyTest: initializationError, "JS worker resource path is invalid", because the JS worker resources are excluded on Linux.
  - **Product defect, (ii), 6:**
    - DesktopBasOverlayPublicationTest ×4.
    - DesktopWindowsEarlyVideoDetailTest ×2.
    - See task6c.md and the patches.

## Plan coverage (`.github/windows-source-validation-plan.json`)
- 142 plan classes (1296 tests). All were executed and none failed. No plan class is missing.
- 3 of the new classes are in the plan: V046CommentAvatarPushOriginalPolicyTest, V046SubReplyDetailPresentationOriginalTest, DesktopCommentAvatarSettingsTest.
- 393 executed classes are not in the plan, including the 8 nvidia classes, DesktopUserVipPinkTest and all 15 failing tests' classes.

## Evidence classes
- **Linux compile:** OK.
- **JVM:** 4324/4344 passed on Linux.
- **Python:** n/a.
- **Unverified:** Windows cloud (the 9 Windows-only failures need a Windows run), real device, NVIDIA, UI, audio and installer.
