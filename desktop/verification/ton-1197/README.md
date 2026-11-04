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
