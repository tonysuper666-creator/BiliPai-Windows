# TON-1196 comment keyboard submission

Base: `beb70651218231accb5def2f49dbeb81403551d7` on `desktop/integration-263-claude-main`.

## Actual consumer and boundary

The dynamic-detail producer emits `DesktopOriginalDynamicDetailLayout.kt`. Its `commentComposer` calls `DynamicInlineCommentComposer`, passing the existing `session.postComment(state.item.id_str, message, images, ...)`. The same layout's `DynamicSubReplyPreviewHost.onReplyClick` closes the reading panel and calls `session.startCommentReply(reply)`. That selects the target and focuses the same inline composer; there is no second send action, route or ViewModel.

`DynamicInlineCommentComposer` and its private `DynamicCommentComposer` are emitted by the already registered `dynamic-reply` producer. The new strict transition patch replaces only this source's behavior. The source-ownership diagnostic asserts the normal production Kotlin source set contains exactly one compiled owner, under `build/transition-patched`. No build, generator, upstream original or class-source selection was changed.

The native Swing `DesktopCommentEmoteTextField` / `CommentInputDialog` investigated in the earlier handoff is a different consumer. It is unchanged by this implementation.

## Behavior

Both existing style branches keep `TextFieldValue`, including immediate composition and selection. The Miuix branch calls the same underlying Miuix field's value overload and preserves the original AppOutlinedTextField container, colors and field sizing. The Material branch uses its value overload.

Ctrl+Enter calls the original local `submit()` used by the send button and IME action. The action checks current ownership, current sending state, composition and current draft/images. A latch claims Ctrl+Enter until release, including blocked attempts and typed newline events. Ordinary Enter retains the existing single-line IME Send behavior. Adding Ctrl during an already-held ordinary Enter cannot introduce another submission.

A release-only AWT observer resets the cycle after release outside the field, including when successful send clears focus. It always returns false and is removed on disposal. Outside-field key events are not consumed. Image-only sends and the original success/failure callback semantics are retained.

## Verification

All heavy commands use the existing Mac-wide lock wrapper. From repository root:

```sh
python3 desktop/verification/ton-1195/run_locked.py . ../ton1196-verified.log \
  --init-script verification/ton-1196/source-ownership.init.gradle \
  compileKotlin ton1196SourceOwnership test \
  --tests 'com.bilipai.desktop.ui.modal.*' \
  --tests 'com.bilipai.desktop.ui.DesktopInlineEmote*Test' \
  --tests 'com.bilipai.desktop.ui.DesktopDynamicCommentShortcutTest' \
  --tests 'com.bilipai.desktop.ui.DesktopDynamicReplyReadingPanelTest'
python3 desktop/verification/ton-1194/compare.py ../ton1196-baseline .
```

The clean baseline worktree uses the exact base above; its locked Gradle command is `test` with the first two filters above. Gradle's existing test dependencies also run all four upstream/owner tasks in both worktrees. The comparator compares individual JUnit case identities/failures/skips, non-desktop committed input trees and default-sheet rendering hashes, with no hand-written failure allowlist.

The new test mounts the actual public inline composer in ImageComposeScene under both themes. It sends real Compose key events and native typed/released events, intercepts the actual platform text-input request to deliver IME edit commands, and records the original `onPostComment` callback. It covers hold/release, synchronous failure/reentrancy, composition before rendering, commit while held, blank/sending/unowned, ordinary Enter, outside focus/release, success clearing text/focus/reply target, and image-only parity with the send button. No test-only production policy function is used.

Windows native GUI/IME and login validation remain on parent TON-1057, using `winvm`. This headless verification does not claim native GUI acceptance.

Final candidate: main compilation and unique-source ownership passed; desktop `test` **39 tests, 0 failures, 0 skipped**, including **16 new cases across two themes**, 4 reading-panel cases and 19 existing cases. Baseline desktop: 19/0/0.

Both baseline and candidate full commands exit **1** due to the same existing 47 upstream failures: design 59/0/0, app-core 441/46/0, image-loader owner 3/0/0, sponsor 1/1/0 (tests/failures/skips). Gate A comparator exits **0**: new failures 0, removed cases 0, new skips 0; upstream inputs, fixture and default-render hashes equal. These failures were neither removed nor exempted. See `comparison.json` and attached raw XML/log evidence.
