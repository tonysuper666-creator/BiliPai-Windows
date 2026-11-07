TON-1195: dynamic reply reading panel

Base: `04f0e642ec81070340cb3d545c22a7fb6bfe0b6e` on
`desktop/integration-263-claude-main`. Implementation tested at `8d1c52f1c`.
PR77 targets that integration branch. Independent QA and layout review remain pending.

Dynamic detail passes an optional container through the actual
DynamicSubReplyPreviewHost into SubReplySheet. The slot replaces the original
sheet, reusing its SubReplyDetailContent, navigation callbacks and session.
The adapter supplies K2 READING_PANEL and the reply navigation owner; it does
not remount the page or create another route/VM. Its finite viewport leaves
scrolling and pagination in the original LazyColumn. The null default retains
the original sheet. Repost and common K2 code are unchanged.

Actual compilation chain (read-only diagnostic, each count is exactly one):

| Declaration | Compiled source relative to desktop |
| --- | --- |
| DynamicSubReplyPreviewHost | build/patched-upstream/app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicSubReplyPreviewHost.kt |
| SubReplySheet | build/transition-patched/com/android/purebilibili/feature/video/ui/components/SubReplySheet.kt |
| DesktopOriginalDynamicDetailLayout | build/transition-patched/com/android/purebilibili/feature/dynamic/DesktopOriginalDynamicDetailLayout.kt |

The migrated host patch is under upstream-core/patches/app; the excluded
transition host patch was deleted. Existing automatic discovery is used.
No build, generator or upstream original was edited. applyUnifiedPatch in
build.gradle.kts:2798–2820 requires exactly one match per hunk and throws on
zero/multiple matches. The SubReplySheet patch targets the actual pinned
producer (LF SHA256 78714b908e0e64a90325e7ebbcea38daaba57e8aa6e6bc3dec11d740ed7de783).

Run from repository root on the configured Mac, using JDK21 and the atomic
shared ~/dev/.coach-heavy.lock lease provided by run_locked.py. A held lease
fails immediately rather than stealing it.

```
python3 desktop/verification/ton-1195/run_locked.py . ../ton1195-candidate-final.log compileKotlin test --tests 'com.bilipai.desktop.ui.modal.*' --tests 'com.bilipai.desktop.ui.DesktopDynamicReplyReadingPanelTest'
```

Main/test compilation succeeds. Target test task: 16 passed, 0 failed/skipped
(12 existing K2 tests + 4 actual-host tests). New tests send real pointer,
wheel and keyboard events through ImageComposeScene and assert:

- One page plus one reply popup, right-aligned reading bounds, initial close
  focus, Tab cycling, Esc/close return focus, and page mount count remains one.
- Reply Esc closes only the topmost popup over an actual K2 parent modal.
- Original lazy list scrolls, sort callback fires, updates retain scroll,
  K2 outer scroll remains zero, and background wheel input cannot scroll it.
- Omitting the slot renders the original sheet and original reply list.

The fixture uses temporary Stores and a real request owner; its transport
intercepts emote requests locally and rejects every other request. It has no
real account or network dependency. Asynchronous composition errors fail the
test. Initial fixture failures (missing request owner, proxy Object methods,
and querying the wrapper instead of the LazyColumn) were corrected without
weakening interaction assertions. Final composition stderr is empty.

The full Gradle command exits 1 because finalized upstream suites have 47
failures also present on the same-version baseline. This is not an all-green
Gradle run; gate A below proves there are no added failures.

```
git worktree add --detach ../ton1195-baseline 04f0e642ec81070340cb3d545c22a7fb6bfe0b6e
python3 desktop/verification/ton-1195/run_locked.py ../ton1195-baseline ../ton1195-baseline.log compileKotlin test --tests 'com.bilipai.desktop.ui.modal.*'
python3 desktop/verification/ton-1194/compare.py ../ton1195-baseline . > desktop/verification/ton-1195/comparison.json
```

Baseline Gradle exit 1; comparator exit 0. Mechanical JUnit comparison, with
no failure allowlist: added failures 0, removed test cases 0, new skips 0.
Non-desktop committed inputs and the default-render fixture are identical;
all untagged-sheet render hashes match.

| Task | Baseline tests / failures | Candidate tests / failures |
| --- | --- | --- |
| test | 12 / 0 | 16 / 0 |
| upstreamDesignSourceTests | 59 / 0 | 59 / 0 |
| upstreamAppCoreSourceTests | 441 / 46 | 441 / 46 |
| applicationImageLoaderOwnerTests | 3 / 0 | 3 / 0 |
| sponsorPresentationTests | 1 / 1 | 1 / 1 |

```
python3 desktop/verification/ton-1195/run_locked.py . ../ton1195-source-ownership.log --init-script verification/ton-1195/source-ownership.init.gradle compileKotlin compileTestKotlin ton1195SourceOwnership
```

Exit 0. Both compilations succeed and all three actual compile owners above
are confirmed. Evidence: candidate-compile.log (final run), baseline.log,
source-ownership.log, behavior-results.txt, comparison.json. Logs replace
machine-local repository paths with <repo>/<baseline>.

Windows GUI, dark/light screenshots and native-window interaction remain
unverified here and belong to parent TON-1057. Headless scene tests cover the
real reply host and content; the actual layout slot is compiled, but the
entire dynamic-detail page is not instantiated by these tests. QA-C should
independently verify PR77, then hand layout-contract review to Architect.
