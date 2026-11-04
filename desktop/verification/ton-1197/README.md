# TON-1197 dynamic image keyboard navigation

Base: `eb8c7b1fc` on `desktop/integration-263-claude-main`.

The actual dynamic-card consumer is `DynamicCardV2`: its Draw/Opus/article image
branches call `DrawGridV2`, retain the full image list and clicked index, and call
the existing `ImagePreviewDialog`. That request is rendered by the single
`ImagePreviewOverlayHost`.

`desktop/settings.gradle.kts` materializes the pinned `.legacy-root`; the
`repositoryRoot` passed to `extractUpstreamDynamicFullCard` is that directory.
Patching the live upstream source would not change this producer's output.
The renderer patch uses the already registered `dynamic-full-card` transition
lane. `DrawGrid.kt` has already migrated to direct compilation; its generated
`DesktopOriginalDrawGrid.kt` copy is retired by `m4b/retired-generated.tsv`.
Its focus patch therefore uses the existing `upstream-core/patches` lane against
the merged original. Both lanes apply unique, strict hunks and exclude the
unpatched counterpart. `source-ownership.init.gradle` observes the normal
source set, asserts exactly one owner for each file and confirms that the retired
grid is absent. It changes neither the task graph nor source selection.

The original pager, animations, chrome, image requests and dismissal authority
remain in their existing host. Left/right keys page only while the viewer itself
owns focus, clamping to the original list. Child controls receive keys first.
Esc dismisses an open image action menu before the image; the original window
dismissal request follows the same ordering. The clicked thumbnail restores focus
after the source becomes enabled again. Paging does not change that return target.

For native comment previews, the existing `DesktopWindowsCommentImagePreviewWindow`
captures its native owner and uses `DesktopWindowsPlayerDialog`. Its Compose Esc
handler bubbles after child content; its Swing fallback is scoped to the focused
dialog. The patch retains this window ownership and the existing request token
and lease guard. Native window/OS focus still needs Windows GUI acceptance.

The new headless tests mount a real `DynamicCardV2` and the sole production overlay
in `ImageComposeScene`, send pointer and key events, and inspect actual pager and
focus semantics. Synthetic image requests use the production loader with an
interceptor returning a local PNG; the test rejects network access. No account
or live service is used. The fixture does not add a test-only production policy.

The patch helper derives diffs from normal producer output and asserts unique
anchors. It does not copy a renderer or modify generated/compiled files.

Windows GUI, parent K6 layout and whole-application acceptance remain separate.

## Verification

Tested implementation commit: `6aa758ef1`; subsequent evidence-only commit adds
this section and `comparison.json`. Baseline: `eb8c7b1fc` in a clean detached
worktree. Both use the same non-desktop trees, default modal test fixture and
render hashes, checked mechanically by the existing TON-1194 comparator.

From each repository checkout, use the existing whole-machine lock runner
(`BASELINE` / `CANDIDATE` below are log destinations outside the checkout):

```sh
# Clean baseline, actual same-version source tests and existing consumer regressions:
python3 desktop/verification/ton-1195/run_locked.py . BASELINE.log test \
  --tests 'com.bilipai.desktop.ui.modal.*' \
  --tests 'com.bilipai.desktop.ui.DesktopInlineEmote*Test' \
  --tests com.bilipai.desktop.ui.DesktopDynamicCommentShortcutTest \
  --tests com.bilipai.desktop.ui.DesktopDynamicReplyReadingPanelTest \
  --tests com.bilipai.desktop.ui.DesktopWindowsCommentPreviewRequestTest \
  --tests com.bilipai.desktop.ui.DesktopWindowsDialogEscapeBindingTest

# Candidate, actual main/test compile, normal source ownership and consumer behavior:
python3 desktop/verification/ton-1195/run_locked.py . CANDIDATE.log \
  --init-script verification/ton-1197/source-ownership.init.gradle \
  compileKotlin compileTestKotlin ton1197SourceOwnership test \
  --tests 'com.bilipai.desktop.ui.modal.*' \
  --tests 'com.bilipai.desktop.ui.DesktopInlineEmote*Test' \
  --tests com.bilipai.desktop.ui.DesktopDynamicCommentShortcutTest \
  --tests com.bilipai.desktop.ui.DesktopDynamicReplyReadingPanelTest \
  --tests com.bilipai.desktop.ui.DesktopWindowsCommentPreviewRequestTest \
  --tests com.bilipai.desktop.ui.DesktopWindowsDialogEscapeBindingTest \
  --tests com.bilipai.desktop.ui.DesktopDynamicImagePreviewKeyboardTest

# From candidate, pass the clean baseline checkout path:
python3 desktop/verification/ton-1194/compare.py BASELINE_CHECKOUT . > comparison.json
```

Both Gradle commands exit **1**, solely for the same 47 upstream failures.
Candidate `compileKotlin`, `compileTestKotlin`, `ton1197SourceOwnership` and the
selected `test` task all pass. There is exactly one compiled renderer and grid,
and no retired generated grid. The 8 new dynamic-card tests cover both MATERIAL3
and MIUIX, including the original long-press action dialog, menu-first Esc and
restored viewer focus. Miuix delays dialog content creation, so its menu requests
focus inside that content rather than before the window is mounted.

| JUnit task | Baseline tests/failed/skipped | Candidate tests/failed/skipped |
| --- | --- | --- |
| test | 46/0/0 | 54/0/0 |
| upstreamDesignSourceTests | 59/0/0 | 59/0/0 |
| upstreamAppCoreSourceTests | 441/46/0 | 441/46/0 |
| applicationImageLoaderOwnerTests | 3/0/0 | 3/0/0 |
| sponsorPresentationTests | 1/1/0 | 1/1/0 |

Gate A comparator exits **0**: new failures 0, removed cases 0, new skips 0;
all default render hashes and upstream inputs match. `comparison.json` retains
both complete failure sets; no allowlist or test exclusions were introduced.
The full JVM suite is not green. Windows GUI/OS focus remains **unverified**.
Raw baseline/candidate command logs and JUnit XML are attached on TON-1197.

Patch context preserves upstream whitespace (including blank context lines).
Whitespace checks pass for code/docs normally and for `.patch` files with
`core.whitespace=-blank-at-eol`; removing their context whitespace would damage
strict patch anchors. No whitespace policy or test is changed in the repository.
