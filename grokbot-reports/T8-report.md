# T8 report (index)

Both fixes are on isolated branches based on `origin/grokbot/r1-test-currency` @ `3d0deb5cf`. They are not merged; Root decides.

| Item | Branch @ head | Product commit | Result (Linux) |
|---|---|---|---|
| T8a BAS publication | `grokbot/fix-bas-publication` @ `0990ba453` | `44155b273` (+ tests `a6fb2344f`) | DesktopBasOverlayPublicationTest 16/16 ×3 (the 4 earlier failures + 3 new scenarios). danmaku.* 196/196. Without the fix 6/16 fail. |
| T8b early video detail | `grokbot/fix-early-video-detail` @ `1e7f80696` | `456c38d69` (generator + Python test) | EarlyVideoDetail 4/4. ui.* 1347 with only the 3 known Linux JS-worker failures. New Python test 3/3. |

Open items for Root:
- **T8b:** the desktop rule (`38daac558`) explicitly conflicts with pinned upstream v034/v037 structured concurrency. It is documented, not ruled on.
- **T8b:** `test_v029_failure_recovery.py` has 2 failures that predate this work (identical on the base).
- **T8a:** upstream has no BAS concept, so the retirement timing is desktop-only. A real window show/hide is not verifiable headless.

Details: `T8a-fix-bas-publication.md`, `T8b-fix-early-video-detail.md`.
