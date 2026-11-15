TON-1004: bounded BgmDetail Root recovery, 2026-11-14

This candidate recovers the independently writable Root owner fix from PR56. It
does not claim that the whole BgmDetailScreen now compiles directly from app/.

Base: `0cb58c3fe890f7a88860acb03fafc3e1b0fdacd8`
Base tree: `81c03ae092d61a4e7b20edc6072699495593df33`
Target: `desktop/integration-263-claude-main`
Old PR56 head: `6cac3abc0011e087a59e8629ec7c2a1d3fe48506` (closed, not merged).
PR18 head read this run: `20b4bd5074e2fcbe158019644ee11afc31aff5d5`
(open draft, conflicting; its old test evidence is not evidence for this base).

## Actual producer and remaining prerequisite

`desktop/build.gradle.kts:381` still excludes the app BgmDetailScreen;
`:3693` compiles `build/generated/bgm-detail/com`. There is no registered app
screen patch and no corresponding retired-generated entry. The generated whole
screen comes from `extract-upstream-bgm-detail.py:358-378`. Its emit hook at
`:184` replaces only BgmDetailContent through `v046_comment_list.py:446`, using
retained upstream `d6bf8b1655ae910999860bf13e65c8fcea5b345b`. A newer content
declaration does not mean a newer whole screen or generic comment VM.

The actual generic VideoCommentViewModel is selected at producer line 281 by
`v036_video_comment_cache.select_vm`, using retained upstream
`3608e0347ba3314ac74190d724d78598fdbad879`. It lacks `composerDrafts`,
`beginCommentDraft`, and `updateCommentDraft`, which the canonical app screen
already consumes. The other video composer implementation is not this VM.
The exact provider/API integration is a TON-1002 prerequisite. This candidate
does not change that provider, protected Gradle files, migration tables, pins,
version/baseline files, integration refs, or any live-family files.

The attachment contains the exact old screen patch (13 hunks) and the two-file
Root registration patch. Both pass `git apply --check` on this base. That only
proves textual applicability: registration must wait for the real generic VM
draft provider and a current same-version gate. They are not applied here.

## PR56 screen patch: hunk-by-hunk semantic comparison

"Equivalent" below means the actual generated desktop screen already supplies
that adaptation; it does not mean direct app compilation is registered.

| Old patch header | Current base | Remaining difference |
| --- | --- | --- |
| `-4,12 +4,6` | Equivalent | Android clipboard/Intent/MediaStore/Toast imports removed. |
| `-18,7 +12,7` | Equivalent | Foundation text SelectionContainer already used. |
| `-26,12 +20,8` | Equivalent | Android context/lifecycle/default VM/Token imports removed. |
| `-41,6 +31,7` | Equivalent | LocalDesktopCommentBindings supplied. |
| `-56,30 +47,38` | Partial | Injected VM/requests/overlay/media parameters and platform state collection exist. Owner admission on emotes/initialization/feedback and session-based draft completion do not. |
| `-87,31 +86,41` | Partial | Desktop login/share/media forwarding exist. UI owner guards and retry preserving comment state do not; actual retry still calls clearForVideoChange. |
| `-119,36 +128,37` | Missing | Keyed composer sessions, draft restoration/update and guarded dialog callbacks require the missing generic VM draft API. |
| `-175,6 +185,8` | Equivalent | Content receives actual overlay and media callback. |
| `-202,9 +214,6` | Equivalent | Content does not read the Android audio session. |
| `-233,7 +242,7` | Equivalent | First info-card call forwards media callback. |
| `-271,7 +280,7` | Equivalent | Wide info-card call forwards media callback. |
| `-288,13 +297,16` | Partial | Desktop clipboard and media signature exist; explicit platform.isOwned admission is absent. |
| `-304,13 +316,7` | Partial | External media-search fallback exists; explicit platform.isOwned admission is absent. |

Other old PR56 files: both protected registration edits remain absent; the old
screen tests were only run on an old PR18 overlay. Neither those tests nor their
old results are substituted for current validation. Root's original two hunks
remain absent on the base and are the independent delta recovered here.

## User-visible Root fix and regression coverage

The Root used to remember models by operations while requests can be replaced
by fraud bindings without changing operations (`DesktopOriginalCommentRootBindings`
lines 106-120). It now remembers one page state by the real owner. The detail
model, generic comment model and nested route rebind together. Nested content
also keys on the owner. Retained nested navigation callbacks check ownership.

The three new tests run the production remember function in a real Compose
Composition and the actual generated generic comment VM. They verify same-owner
recomposition retains subject/reply/route; a replacement owner with the same
operations uses its new requests and live scope; retired navigation callbacks
cannot publish. Requests are strict memory test ports; no window, HTTP,
credentials, playback or Windows client is used. These are Root tests, not a
claim about whole-screen composer draft retention, retry, or screenshot behavior.

## Current verification

Every Gradle command below ran through the active Coach heavy/FIFO script with
`--local` (not winbox). Its full argv/cwd/exit and raw logs are in the attachment.

Baseline: `./gradlew -p desktop test --tests com.bilipai.desktop.ui.DesktopBgmDetailViewModelTest --no-daemon --console=plain`

Candidate: same command plus `--tests com.bilipai.desktop.ui.DesktopBgmDetailMigrationTest`.

Both exit 1 because the unchanged upstreamAppCoreSourceTests finalizer fails.
Baseline: 512 cases, 466 pass / 46 fail / 0 skip.
Candidate: 515 cases, 469 pass / 46 fail / 0 skip, including 8/8 BGM tests.
`test-delta.json` compares task + class + method identities: exactly 3 added
passing tests, no missing cases, no added/removed failures or skips. The 46
inherited structure failures are enumerated in the attached XML/JSON, not waived.
`audit.py` exits 0 and asserts this comparison plus actual source/API facts.
Its baseline snapshot is in the attachment; extract it beside the script to
repeat the read-only comparison after running the candidate command.

`./gradlew -p desktop verifyUniqueClassNames --no-daemon --console=plain`: exit 0.
The unchanged production classpath was scanned during the first candidate run:
61071 classes / 116 entries / 0 duplicates; the final run correctly reports
UP-TO-DATE. The first candidate attempt had two strict test-port failures because
the fixture expected the mobile method instead of actual getCommentsForSubject;
only that newly added fixture was corrected, and all 8 BGM cases were rerun.

Full desktop gate, Windows, GUI, whole-screen draft/retry/share regressions and
the original issue's live/download acceptance remain unverified this run.
No existing test was deleted, skipped, or relaxed. No merge or release is requested.
