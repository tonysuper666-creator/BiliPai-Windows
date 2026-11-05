# TON-1194 development verification

Base: `df8cafacbc5b2df4e648f42072e487bcfe7ecb6f` on
`desktop/integration-263-claude-main`. This is a focused Mac development comparison,
not Root's full build/package gates or Windows GUI acceptance.

## Actual production paths

`extract-upstream-dynamic-detail-container.py` produces the compiled upstream
`AppModalBottomSheet` through `adapt_sheet` and `v046_sheet_motion.advance`.
The new same-package overload requires `purpose`; omitted-purpose calls still
resolve to that generated implementation. Existing source discovery compiles the
new file; no build registration or generator changes are needed.

The wallpaper draft editor opts into `CONFIRM` and supplies a fixed action footer.
Its crop, zoom, image loading and save callbacks remain the original implementation.
The collection and playback queue actually bypass the upstream sheet in a native
player dialog. Their real hosts now use an owned, document-modal, transparent
reading window over the player's native Canvas. The panel shares layout/input
handling with the popup; existing source/queue identities and admission callbacks
remain intact. The shared player-dialog implementation is unchanged.

## Reproduce

From the candidate repository, create an independent baseline worktree and copy
**only** the unchanged default-sheet render fixture into it:

```sh
git worktree add --detach ../ton1194-baseline df8cafacbc5b2df4e648f42072e487bcfe7ecb6f
mkdir -p ../ton1194-baseline/desktop/src/test/kotlin/com/bilipai/desktop/ui/modal
cp desktop/src/test/kotlin/com/bilipai/desktop/ui/modal/DesktopModalDefaultBaselineTest.kt ../ton1194-baseline/desktop/src/test/kotlin/com/bilipai/desktop/ui/modal/
python3 desktop/verification/ton-1194/run_locked.py . ../ton1194-candidate-tests.log test --tests 'com.bilipai.desktop.ui.modal.*' --tests '*DesktopWallpaperCropTest' --tests '*DesktopWindowsVideoCollectionQueueTest'
python3 desktop/verification/ton-1194/run_locked.py ../ton1194-baseline ../ton1194-baseline-tests.log test --tests 'com.bilipai.desktop.ui.modal.*' --tests '*DesktopWallpaperCropTest' --tests '*DesktopWindowsVideoCollectionQueueTest'
python3 desktop/verification/ton-1194/compare.py ../ton1194-baseline . > comparison.json
python3 -m unittest desktop/tools/tests/test_v046_sheet_motion_source.py
git diff --check
```

Run Gradle sequentially. The runner atomically acquires the shared
`~/dev/.coach-heavy.lock`, records its PID, uses the installed Homebrew JDK 21,
and releases only its own lease. It fails if another build owns the lock.
It preserves Gradle's real exit code and never suppresses test failures.

## Recorded results

| Task | Baseline tests/failures/skips | Candidate tests/failures/skips |
| --- | --- | --- |
| `test` (the patterns above) | 16 / 0 / 0 | 26 / 0 / 0 |
| `upstreamDesignSourceTests` | 59 / 0 / 0 | 59 / 0 / 0 |
| `upstreamAppCoreSourceTests` | 441 / 46 / 0 | 441 / 46 / 0 |
| `applicationImageLoaderOwnerTests` | 3 / 0 / 0 | 3 / 0 / 0 |
| `sponsorPresentationTests` | 1 / 1 / 0 | 1 / 1 / 0 |

Both Gradle commands exit **1**, due to the same 46 app-core failures plus sponsor
initialization failing with `JS worker catalog digest is invalid` on this Mac.
The 46 app-core failures are also recorded by the existing TON-1085 / TON-1113
baseline work. Nothing here changes those failures or their tests.

`compare.py` exits **0**: new failures **0**, missing baseline cases **0**, new
skips **0**, identical non-desktop committed inputs and identical render fixture.
`comparison.json` contains the complete machine-extracted failure identities;
there is no hand-written failure allowlist. Both Material3 and Miuix untagged-sheet
PNG hashes match the clean base, after asserting the actual sheet body is laid out.

The 11 new modal tests include three geometry tests, seven `ImageComposeScene`
input tests and the default-sheet fixture. Together with 15 existing wallpaper /
collection / queue tests, the targeted task passes **26/26**. Real pointer/key
events exercise initial focus, forward/reverse Tab wrapping, nested Escape and
opener focus restoration, background scroll isolation and fixed footer, anchor
flip, native-host shared layer, 160 ms exit/reduced motion and saving protection.
Compose effects and frames run deterministically on the test dispatcher.

The separate sheet-motion Python suite passes **6/6** (exit **0**).
Production `compileKotlin` passes (exit **0**); the final targeted command also
compiles the final production/test sources. `verifyUniqueClassNames` reports
**60,951** classes with no duplicates for the candidate. `git diff --check` exits
**0**. No existing test was deleted, skipped or relaxed.

## Still reserved for TON-1053

Windows GUI/native-window activation, DPI/zoom positioning, MPV occlusion,
native close/refocus, visual review and real collection/queue interaction are
**not verified** by these headless tests. No Windows client was launched, no
package/release was produced, K1 PR28 was not merged, and parent dependencies
remain unchanged. The development PR awaits independent review and layout
cross-review; it does not complete parent acceptance.
