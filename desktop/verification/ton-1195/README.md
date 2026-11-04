TON-1195 candidate: awaiting a source-patch scope correction

Base: `04f0e642ec81070340cb3d545c22a7fb6bfe0b6e` on
`desktop/integration-263-claude-main`.

This is an incomplete candidate. Compilation fails; no behavior tests have run.
The original reply host, state and callbacks are reused. The optional container
slot replaces SubReplySheet's container instead of wrapping its existing sheet.
Only dynamic detail opts in; the null default retains the original container.
The adapter supplies K2 READING_PANEL, the reply's navigation owner, and a finite
viewport for the original lazy list. Repost and common K2 code are untouched.

Actual compile ownership

The successful read-only source-set diagnostic reports exactly one source for
each declaration (see `source-ownership.log`):

| Declaration | Compiled source relative to desktop |
| --- | --- |
| DynamicSubReplyPreviewHost | ../app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicSubReplyPreviewHost.kt |
| SubReplySheet | build/transition-patched/com/android/purebilibili/feature/video/ui/components/SubReplySheet.kt |
| DesktopOriginalDynamicDetailLayout | build/transition-patched/com/android/purebilibili/feature/dynamic/DesktopOriginalDynamicDetailLayout.kt |

`desktop/build.gradle.kts:3593` excludes migrated identities under build except
patched-upstream. M4 includes the dynamic host in migratedAppPaths (lines
316–330, 436–437). Therefore its transition patch is applied but its output is
excluded from compilation. The actual original host has no sheetContainer
argument, producing the three compiler errors in `candidate-compile.log`.

Required scope correction

Replace the inactive transition host patch with
`desktop/upstream-core/patches/app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicSubReplyPreviewHost.kt.patch`.
Proposed change: add the optional `sheetContainer` composable parameter and
forward `sheetContainer = sheetContainer` to SubReplySheet, as in the current
inactive transition host patch. This has NOT been installed into the new directory.
Existing applyDesktopUpstreamPatches discovery and source exclusions
(build.gradle.kts:2829–2877, 3580–3596) already support this patch location.
No build registration, generator change, original-file edit, or K2 change is
proposed. Keep the layout and SubReplySheet patches in their current directory.
Architect should reconcile this one path with the issue's permitted directory
and current write scopes before implementation resumes.

Verification performed

From repository root, under the shared Mac build lease:

```
python3 desktop/verification/ton-1195/run_locked.py . candidate.log compileKotlin test --tests '*DesktopDynamicReplyReadingPanelTest' --tests 'com.bilipai.desktop.ui.modal.*'
```

Exit 1: applyTransitionPatches succeeds, compileKotlin fails with three diagnostics
at the real layout call. compileTestKotlin and all tests are unreached. The four
new behavior tests are candidates only: initial focus/Tab/Esc/return focus and
single popup; topmost-only dismissal; original lazy-list/background scrolling
and retained scroll state; unmodified default container. They still require
compilation and execution after the scope correction. Same-version upstream
failure comparison and Windows GUI are unverified. No exemptions were added.

```
python3 desktop/verification/ton-1195/run_locked.py . ownership.log --init-script verification/ton-1195/source-ownership.init.gradle ton1195SourceOwnership
```

Exit 0: all three actual compile owners identified. This diagnostic reads the
existing source set without altering it. Run normal producers first if using a
clean checkout. An earlier diagnostic attempt used a doubled desktop path and
was corrected. Initial patch development also hit an explicit zero-match anchor
failure: Gradle's pinned SubReplySheet producer differs from direct extraction
against the merged source tree. The current sheet patch targets the Gradle
producer output (source LF SHA256 78714b908e0e64a90325e7ebbcea38daaba57e8aa6e6bc3dec11d740ed7de783).

Resume with the approved migrated-host patch, recheck live write scopes, compile
main/tests, run the actual-host behavior tests plus K2 tests, and mechanically
compare same-version upstream failures against the base. QA-C and Architect
acceptance remain pending; this candidate must not be merged in its current state.
