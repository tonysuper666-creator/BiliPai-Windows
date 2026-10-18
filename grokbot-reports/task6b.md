# Task 6b: stale tests, test-only changes

The code was written by Grok Build. Its plan was checked for scope and strictness before any edits. I reviewed the diffs: only test files changed, LF line endings only, no assertion removed or weakened.

| Class | Basis (intentional product change) | Change | Commit |
|---|---|---|---|
| data.DesktopCommunityRepositoryTest (`requestParametersMatchUpstreamSpaceAndSearch`) | Pinned upstream `desktop/upstream-slices/v041-search-all/SearchRepository.kt:103` (`"page_size" to "30"` in `searchTypeParams`), pinned in `eaa4c484e`. Reaches the product via the generated `desktopSearchTypeParams` (`DesktopCommunityRepository.kt` → `communitySearchParams`). | `"20"` → `"30"`. Upstream has no named constant (inline literal, private function), so the test keeps a literal with a pin comment. Comparing against `desktopSearchTypeParams` would be tautological. | `392c708e8` |
| danmaku.DanmakuTest (`duplicate merging …`) | `d1555e6d9`: `DanmakuMerge.kt:19` delegates to the pinned `v038-danmaku-merge/DanmakuMerger.kt`. That merger groups by text only (line 41), uses a window from the first item of each batch (lines 57-58) with a default of 15 000 ms (line 27), and has no colour identity. It joins text as `"${text} x$count"`. | Asserts the v038 contract, with a comment in the test. Added one same-text comment at 16 s. Expected ids `[0,4]`, `"same x4"`, time 0.0, colour 0xffffff (the red comment is merged), second batch `"same"` @16.0, scheduler `"same x3"` (allowColorful=false filters red before the merge), disabled `[0,1]`. Assertions go from 6 to 8. | `dcc7aa781` |
| update.DesktopUpdaterRegistryTransactionTest (4 tests) | `6da71260b` added `downloadHandoffToken: String? = null` as the sixth property of `ActiveInstallation` (`DesktopUpdater.kt:1043-1050`). | Reflection now uses the 6×String constructor, and `newInstance` passes `null`, the product default. Registry assertions are unchanged. | `96eb7aed3` |
| ui.DesktopWindowsCommentSearchBindingTest | Already fixed on this branch by `ade424f63` (same fix as source261). Line 60 uses `VIDEO_COMMENT_PAGE_SIZE`, from pinned v036 `VideoCommentLoadPolicy.kt:12` (`= 30`, from `e3e1914cb`). | No change needed | (already `ade424f63`) |

## Results (Linux JVM, `--tests` per class, `--rerun`)
- Baseline on this branch before the edits: DanmakuTest 1 failure, CommunityRepository 1, UpdaterRegistryTransaction 4/4, CommentSearchBinding 0/13.
- After the edits: DanmakuTest 11/11, DesktopCommunityRepositoryTest 14/14, DesktopUpdaterRegistryTransactionTest 4/4, DesktopWindowsCommentSearchBindingTest 13/13. **42/42 passed.**

## Evidence classes
- **Linux compile:** test sources compile.
- **JVM:** 42/42 on Linux.
- **Python:** n/a.
- **Unverified:** Windows cloud. These tests contain no platform-specific logic, so the Linux results are expected to carry over, but that has not been verified.
