# Slice authoring playbook: BiliPai Windows, upstream v0.4.6 slices (2026-10-18)

How an upstream-derived "slice" is authored end to end in this repository, reconstructed from real precedent. Checklist style, with file:line citations. Every rule below is either enforced by a named test/validator in the tree, or is observed precedent (cited), or is my recommendation (worded "recommend", "rule of thumb" or "hand-off shape"); where precedent is mixed, section 10 says so.

## 0. Read this first

**Basis.** Worktree `C:/Users/TONYS/AppData/Local/Temp/bp-integration-263`, branch `desktop/integration-263`, HEAD `2ab2ef06c` (contains accepted head `610218f8b`, the comment closure `aad198489` + repair `e46fe930d` + test pin `ade424f63`, and the NVIDIA line S1-S8). Line numbers refer to that HEAD; when they drift use the quoted symbol with `git grep -n`. All paths are relative to the worktree root unless absolute.

**Evidence class of this document.** Read-only repository study plus Python runs. Every Python run wrote only to temp dirs; `git status --short` stayed empty. Nothing was compiled, no JVM/Gradle/Kotlin tool/GUI/network was used, and the upstream objects were read with `git cat-file` offline. The two helper scripts in Appendix A and B are NOT in the repository; they were validated by round trip against committed data (Section 3.5, 4.3, 7 P2) and are labelled where their limits matter.

**Measured at HEAD `2ab2ef06c` (Python 3.12.10, Windows 11, one process at a time unless noted).**

| Command (all with the env line of section 8.1) | Result | Wall time |
|---|---|---|
| `test_v046_favorite_editor_source.py` | 9 OK | 29 s |
| `test_v046_danmaku_like_source.py` | 7 OK | 2 s |
| `test_v046_comment_chrome_source.py` | 9 OK | 17 s |
| `test_v046_comment_thread_source.py` | 10 OK | 2 s |
| `test_v046_comment_list_source.py` | 26 OK | 20 s |
| 14 older tests that name `v046_favorite_editor`/its producers (list in 8.3) | all OK | 113 s summed |
| `test_validate_source_cohort.py` | 25 OK | 1 s |
| `test_package_source_qualification.py` | 14 OK | under 1 s |
| `test_sync_upstream.py` (whole file) | 74 OK | 417 s (about 7 min, measured while another Python job ran) |
| `test_sync_upstream.py -k AdaptedCompatibilityInventoryTests` (the archive-registration class) | 10 OK | 276 s |
| `-s desktop/tools/nvidia -p "test_nvidia_*.py"` | 323 OK | 2 s |
| the four known failures: `test_rtx_hdr_color` 1 failure/7, `test_extract_upstream_brand_success_mount` 2 failures/3, `test_extract_upstream_up_danmaku` 2 failures/10, `test_extract_upstream_comment_renderer` 2 failures + 1 error/5 | still failing, unchanged | 0 to 4 s each |
| `python desktop/tools/validate-source-cohort.py --check-only --repo .` | `AssertionError: .github/workflows/windows-mpv-rtx-core-manual.yml` (frozen plan is stale at this HEAD; expected, Root re-freezes) | under 1 s |

**Hard rules (from the task, repeated because every checklist below assumes them).** No JVM of any kind; do not touch `.github/**`, `desktop/native/**`, `desktop/third-party/**`, the vendored roots (`app/ design-system/ core-data/ core-player/ danmaku-engine/ settings-core/ plugin-sdk/ plugins/ network-core/ miuix-navigation/ gradle/`), `desktop/tools/v025-canonical-sources.json`, `desktop/tools/v025_source_paths.py`; LF only; no co-author/AI lines in commits; never relax a test or pin; SOURCE implemented + Python checks run is the maximum claim.

## 1. Vocabulary and the three slice kinds

- **Archive**: `desktop/upstream-slices/<vNNN-slug>/`, verbatim upstream files at the target commit plus `manifest.json` (and `LICENSE` in the v046 family). `.gitattributes:146` `desktop/upstream-slices/** -text` stores them byte-exact (no autocrlf).
- **Generator module**: `desktop/tools/vNNN_<snake_slug>.py`. Pure library: verifies the archive, performs hash-pinned adaptations, returns bodies + proofs. It has no CLI.
- **Producer**: an existing `desktop/tools/extract-*.py` script that a Gradle `Exec` task runs (`desktop/build.gradle.kts:284`, `1500`, `1551`, `2791`, ...). Slices never add a Gradle task; they hook a generator into an existing producer (precedent: `610218f8b` and `aad198489` add only `inputs` lines, one `exclude`, and a few `srcDir` lines).
- **Layer / predecessor / proof**: producers apply layers in a fixed order; each slice's `advance_*` function asserts the hash of the body it receives (its predecessor), returns the new body, and records an `inverse_proof(before, after)` receipt so tests can peel the layer off again.
- **Canonical / pinned tree**: the vendored v0.2.5 sources (`79e8fa30`), identity-mapped through `desktop/tools/v025-canonical-sources.json` and read with `canonical_source()` (`desktop/tools/v025_source_paths.py:14-45`, hash-checked). A vendored file is part of the compile universe only if it is listed in `desktop/upstream-sources.json` `sources[]` with mode `direct` (copied verbatim by `prepareUpstreamSources`, `build.gradle.kts:168`) or if a producer emits something from it (modes `policy-extract`, `extracted`, `selected`, `api-extract`, ...); `reference-only` entries are not compiled.
- **Root re-freeze**: Root regenerates `.github/windows-source-validation-plan.json` and the workflow test list for an exact head. Slices never edit `.github/**`.

**Kind A (upstream-derived archive slice)** is the subject of sections 2-9. **Kind B (desktop-owned slice, the NVIDIA line)**: new Kotlin under `desktop/src/main/kotlin/com/bilipai/desktop/<pkg>/`, JVM tests under `desktop/src/test/kotlin/...`, Python source checks in `desktop/tools/nvidia/` (see section 7, P20). No archive, manifest, inventory row or generator. **Kind C (repair or shim)**: a desktop-owned file plus generator guards (precedent `e46fe930d`: `DesktopUserVipPink.kt`, `COMPAT_REDUCED_MOTION`, `ensure_import`).

## 2. Preflight checklist (do all of it before writing a file)

1. Resolve the target files at the target commit: `git cat-file -t d6bf8b1655ae910999860bf13e65c8fcea5b345b` must print `commit` (upstream objects are local; tag refs such as `v0.4.6` are NOT present locally, `git tag -l v0.4.6` prints nothing, so always use the full 40-hex commit).
2. Decide whole file, complete declaration(s), or new file (section 5.3). Prefer whole upstream declarations/files with hash-pinned adaptations over hunks. The roadmap collision list (`roadmap-20261018.md` section 2.2) says shared files move to v0.4.6 content once, whole file.
3. Find the owner of every target name. Canonical identity and mode:
   `python -c "import json;d=json.load(open('desktop/upstream-sources.json',encoding='utf8'));print([(s.get('mode','direct'),s.get('features')) for s in d['sources'] if s['path'].endswith('/NAME.kt')])"` (bash quoting).
   Producer(s) and Gradle tasks that already mention it: `git grep -n "NAME" -- desktop/tools ':!desktop/tools/tests' desktop/build.gradle.kts`. Pick the producer whose Gradle task already lists the canonical file as an input (precedent: `v043_message_link_host` is emitted by `extract-space-platform.py:109-113`; `build.gradle.kts:1986-1989` lists its inputs).
4. Sole-producer lints in other tests will reject a second producer for a file someone else owns: `test_v041_video_status_labels_source.py:561` (`test_no_other_generator_names_the_four_replaced_model_files` scans EVERY `desktop/tools/*.py`, with an AST exception only for `v044_favorite_detail.py` and `v046_favorite_editor.py`), and the `exclude(...)` count==1 asserts (`test_v046_favorite_editor_source.py:115-116`, `test_v043_message_link_host_source.py:97-103`). `git grep -n -E "sole|no_other|only_one" -- desktop/tools/tests` before choosing.
5. Layer position: append your layer after every existing layer of that producer chain (section 5.5). Inserting mid-chain invalidates later layers' predecessor hashes.
6. Inventory the top-level names your copied declarations introduce and who else declares them (section 7, P1) and every simple name they use (P2, P3). Do it now, not after generation.
7. Decide the JVM regression story: emitted original tests (renamed or not), hand-written Windows tests in `desktop/src/test/kotlin/...` (source-only here; count them with a Python check).
8. List what Root must re-freeze for you (section 4.2) so the hand-off report can state it.

## 3. (a) Archive layout and `manifest.json`

### 3.1 Layout (v046 family, the current convention)

```
desktop/upstream-slices/v046-<slug>/
  <Leaf>.kt ...        flat; leaf name == basename(upstream path); basenames must be unique inside the archive
  LICENSE              upstream root LICENSE at the same commit, byte-exact
  manifest.json
```
No sub-directories (the four `v034-*` archives without a manifest use nested `app/...` trees; do not copy that form). `fixed()` in each generator asserts the directory listing equals `rows + {manifest.json, LICENSE}` (`v046_favorite_editor.py:22`, `v046_comment_list.py:28`, `v046_comment_chrome.py:23-25`).

### 3.2 `manifest.json` schema (observed; only the slice's own `fixed()` reads it)

Nothing outside the slice's own generator/producer code and tests parses `manifest.json`. `sync-upstream.py` and `.github` treat it as an opaque leaf whose bytes are pinned. So the schema is exactly what your `fixed()` reads; use the v046 shape.

| Key | v046 value / rule | Read by |
|---|---|---|
| `repository` | `"jay3-yy/BiliPai"` | `fixed()` identity tuple (`v046_favorite_editor.py:26-27`) |
| `tag` | `"v0.4.6"`. Label only; no local tag ref exists | same tuple |
| `commit` | full 40-hex upstream commit (`d6bf8b1655ae910999860bf13e65c8fcea5b345b`) | same tuple |
| `hashNormalization` | `"raw"` for every v043+ archive: hashes are over the exact upstream bytes. Legacy archives (v029-v038) carry `sha256LF`/`sha256Bytes` pairs instead | same tuple |
| `files[]` | one row per leaf: `path` (upstream repo path), `filename` (leaf name), `bytes`, `sha256`, `gitBlob`. Optional per-row `cases` (v043-theme-chrome, v044-favorite-detail) | `fixed()` loop (`:31-39`) |
| `license` | `{"path":"LICENSE","sha256":"3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"}` (editor, chrome, list) or the long form with `filename`, `bytes`, `gitBlob` (thread) | `fixed()` (`:40-42`) |
| `schemaVersion` | `1` in thread/v044/v043; absent in editor/chrome/list | nobody |
| `selection` / `purpose` / `scope` | free text describing what is taken | nobody |
| `beforeFinalOutputs` | `{ "<producer-output-relative path>": {"sha256": "<LF sha of the predecessor body>"} }`, asserted by `_before()` (`v046_favorite_editor.py:68-72`) and re-checked by tests (`test_v046_favorite_editor_source.py:72,92`) | generator + tests |
| `beforeWindows...SHA256` | comment-list specific predecessor pins (`v046_comment_list.py:138`) | generator |
| `currentWindowsParent`, `fullStableParityQualified` | informational (`5215ac2c8...`, `false`) | no tool and no test reads them (grep proven) |

Serialization: `json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"`, LF (reproduces all committed v046/v044 manifests byte for byte; v044-favorite-detail needs `ensure_ascii=False`). Key order is not validated.

Pins can live in three places (all precedent): constants in the generator (`v046_danmaku_like.py:7-8`, `v046_comment_chrome.py:10`), the manifest (`beforeFinalOutputs`), or the whole `files` list embedded in the generator and compared to the manifest (`v043_message_link_host.py:15`, `v043_link_open.py:6`). Rule of thumb from precedent: constants for one or two predecessors; `beforeFinalOutputs` when three or more outputs, or when tests must read them.

### 3.3 How each number is computed

- `bytes` = `len(raw)` of the upstream blob.
- `sha256` (raw) = `hashlib.sha256(raw)`. The LF variant (`raw.replace(b"\r\n", b"\n")`) is used only for canonical v0.2.5 files and for generator-side `sha256LfUtf8` bookkeeping (`sync-upstream.py:43-57`).
- `gitBlob` = `sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw)` = `git rev-parse <commit>:<path>` (`sync-upstream.py:60-62`; `v046_favorite_editor.py:37`).
- All three are taken from `git cat-file blob <commit>:<path>` bytes, which no filter or autocrlf touches. Never read upstream text through a working-tree checkout or through PowerShell redirection.

### 3.4 LICENSE

The leaf is the upstream root `LICENSE` of the same commit (GPL-3.0, 35149 bytes, sha256 `3972dc97...6986`, blob `f288702d2fa16d3cdf0035b15a9fcbc552cd88e7`): `git cat-file blob <commit>:LICENSE`. It is pinned in `manifest.license.sha256`, verified by `fixed()`, listed as a normal leaf in the catalog `archives[].files` (every file in the directory must be listed, `sync-upstream.py:310-311`), and gets NO `inputs` row (only `.kt .kts .java .js .glsl` leaves do, `sync-upstream.py:344`). Only the four v046 archives carry it (137 archive directories, 133 of them with a `manifest.json`); v044 and older have none. Whether new slices must carry one is not stated anywhere; follow the v046 family (cheap, and roadmap slice `cmp-third-party-notices` is about exactly this).

### 3.5 Creating and verifying an archive (offline, no JVM)

1. Choose the exact upstream paths (`git ls-tree -r d6bf8b16... --name-only | grep Name`). Check basenames are unique.
2. Capture predecessor hashes first if the manifest will carry `beforeFinalOutputs` (run the UNMODIFIED producer into a short temp dir, hash the LF body of the file you will advance).
3. Run Appendix A:
   `python slice_archive_tools.py make-archive --repo . --commit d6bf8b1655ae910999860bf13e65c8fcea5b345b --tag v0.4.6 --slice v046-<slug> --out-root <short temp dir> --path <upstream path> [--path ...] [--extra-json @extra.json]`
   (refuses to overwrite; prints the manifest sha256 to paste into `MANIFEST_SHA256`). Move the directory to `desktop/upstream-slices/` with a binary-safe copy (Python `shutil.copytree`, not PowerShell `Copy-Item` through text cmdlets).
4. `python slice_archive_tools.py verify-archive --repo . --slice v046-<slug>` must print `"ok": true`.
   Round-trip proof of the tool (2026-10-18): `make-archive` reproduced `v046-comment-chrome` and `v046-favorite-editor` byte for byte (including manifest sha `18a7deb1...` and `d735f17a...`); `verify-archive` is `ok` for all four v046 archives and reports only "no LICENSE" for `v044-favorite-detail`.
5. `git add` then `git -c core.autocrlf=false ls-files --eol desktop/upstream-slices/v046-<slug>` must show `i/lf ... attr/-text` for every leaf. (Older manifests `v029-chat-timeline`, `v036-comment-route`, ... are committed `i/crlf`/`i/mixed`; that is a frozen historical accident, pinned by hash, do not "fix" them.)

## 4. (b) Registration: what the slice changes itself vs what Root re-freezes

### 4.1 The slice's own commit must touch these (each is enforced by a named check)

| # | File | Exact edit | What fails if you skip it |
|---|---|---|---|
| 1 | `desktop/upstream-slices/<name>/**` | the archive (section 3) | `sync-upstream.py:320-324` "Unreviewed adapted source directory" |
| 2a | `desktop/tools/compatibility-source-inputs.json` `buildInputRoots` | append `desktop/upstream-slices/<name>` | `sync-upstream.py:254-259` "Desktop build input coverage changed (buildInputRoots)". The list must equal the set of `upstream-slices/<name>` strings found by regex in `build.gradle.kts` (`sync-upstream.py:232`), so row 4 must exist first |
| 2b | same file, `archives[]` | append `{directory, files:[{path, sha256, hashNormalization:"raw", blob}]}` for EVERY file incl. `LICENSE` and `manifest.json` | `:310-311` "Unreviewed adapted archive leaf", `:318-319` "Incomplete adapted archive", `:316` "Adapted archive bytes changed" |
| 2c | same file, `inputs[]` | one row per `.kt/.kts/.java/.js/.glsl` leaf: `path` (upstream path), `localPath`, `upstreamCommit`, `blob`, `sha256`, `hashNormalization`, `kind:"adaptedSource"`, `features:[..]`, `referenceOnly:bool`, optional `partialReuse`, `usedScope` (free text), `reusedMethods` | `:337-338` "Uninventoried or duplicate adapted source input", `:347-348` "Complete retained BiliPai source files are missing compatibility pins" |
| 3 | `desktop/tools/sync-upstream.py:36` | `COMPATIBILITY_CATALOG_SHA256` = sha256 of the catalog after LF normalisation | `:243-245` "Unknown adapted input inventory; manual review is required." |
| 4 | `desktop/build.gradle.kts` | see 4.4 | `sync-upstream.py:254-259`; the wiring asserts in `test_vNNN_*` |
| 5 | `desktop/tools/tests/test_sync_upstream.py:982-985` (and the same literal at `1732`, `1735`, `1752`) | new entry count, distinct `(upstreamCommit, path)` count, `buildInputRoots` count, `archives` count | the four `assertEqual`s |
| 6 | `desktop/tools/vNNN_<slug>.py` | generator (section 5) | n/a |
| 7 | the producer(s) `desktop/tools/extract-*.py` | hook calls (section 5.4) | your test |
| 8 | `desktop/tools/tests/test_vNNN_<slug>_source.py` | section 6 | n/a |
| 9 | older tests whose layer you now sit on top of | peel/pin updates (section 6.3) | those tests |
| 10 | optional: hand-written `desktop/src/test/kotlin/...` regression class | source only | Python count check in your test |
| 11 | optional: a descriptive block in `desktop/upstream-sources.json` (`v046FavoriteFolderEditor` at `:14965`, `v046DanmakuLike` `:14982`, `v046CommentChrome` `:14994`) | free-form; NO schema test. `v046-comment-list`/`-thread` have none | nothing (only `test_v041_video_status_labels_source.py:647` pins its own v041 record's `manifestSHA256`) |

Current numbers to start from: `entries 736, distinct 722, buildInputRoots 135, archives 139`, catalog sha256 `e681880bdd3dd25bc2f662efcee99f51b8d131df92fb49979bd5ce2db325b968`. `distinct` is smaller than `entries` because several archives carry the same upstream path (for example `SettingsManager.kt`).

### 4.2 What Root re-freezes later (never edit; report your delta instead)

- `.github/windows-source-validation-plan.json`: `sources[]` (sha256 + normalisation of every changed and pinned path, including each new archive leaf and `manifest.json`), `classes` (FQCN -> case count; 142 classes, 1296 cases now) and `expectedTests`, `changedPaths`, `baseHead`, `windowsVersion`, `createdAt`. A JVM test class that is not in `classes` NEVER runs on Windows CI (the filter is `failOnNoMatchingTests` over exactly this list, `desktop/tools/source-cohort-test-filter.init.gradle`; the 1 failing comment test of the Linux run, `DesktopWindowsCommentSearchBindingTest`, was absent from it). The NVIDIA classes are not in it either.
- `.github/workflows/windows-source-validation.yml`: one `python -m unittest discover -s desktop/tools/tests -p test_vNNN_<slug>_source.py -v` line per new Python test file (87 such lines now; v046 ones at `:105-114`). `test_sync_upstream.py` is covered by `run-tool-tests.py --stage policies` (`:43`).
- `windowsRevision` (`desktop/upstream-sources.json:11660`, value `"201"`) and with it the production version `0.2.<versionCode>.<revision>` (`build.gradle.kts:147-148`). `validate-source-cohort.py:149-168` asserts `plan.windowsVersion == actual_windows_version(repo)`, so a revision change without a re-freeze fails the cohort check. Precedent slice commits bumped it (189 -> 199 -> 201) but `AGENTS.md` assigns versioning to Root: do NOT bump it in a slice; say so in the report.
- The "stale plan at this HEAD" failure of `validate-source-cohort.py --check-only` is expected and is not yours to fix.

Hand-off report to Root (recommended shape, derived from the list above): files changed; new archive dir + manifest sha256; inventory delta (`entries +N, distinct +M, buildInputRoots +1, archives +1`) and the new catalog sha256; new Python test files; new JVM test classes with FQCN and exact `@Test` counts (for `classes`); anything that changed a pre-existing pin and why (exact recomputed value); the evidence statement ("source implemented, Python checks run, nothing compiled").

### 4.3 Computing rows, digest and counts (Appendix A)

- `python slice_archive_tools.py inventory --repo . --slice <name> --feature <name-of-feature> --used-scope "<ascii text>" [--partial-reuse] [--reference-only]` prints the new row counts and the resulting catalog counts + digest; add `--apply` to rewrite `desktop/tools/compatibility-source-inputs.json` (appends to all three lists, LF, `indent=2`). Then put `catalogSha256Lf` into `sync-upstream.py:36` and the four counts into `test_sync_upstream.py:982-985, 1732, 1735, 1752` with the Edit tool (or a Python script using `newline="\n"`), never with PowerShell `Set-Content`.
- Round-trip proof: removing `v046-comment-thread` from a copy of the catalog and re-adding it with the tool reproduced the committed catalog byte for byte, and `counts` on the committed file returns the values pinned at `sync-upstream.py:36` and `test_sync_upstream.py:982-985`.
- Order inside the three lists is not validated and not uniform (97 of 139 archives list files case-insensitively sorted, the rest do not). Append at the end, as every precedent does (`v046-comment-thread` is last).
- Use ASCII only in `--used-scope`/`--feature` (console code pages); the file itself would accept UTF-8.
- Parallel slices collide on exactly these hot spots (inventory JSON, the digest pin, the four counts, `build.gradle.kts` tail, `windows-source-validation` list). After any merge, recompute with the tool; never pick one side of a conflict.

### 4.4 Gradle wiring (the only `build.gradle.kts` edits slices make)

Convention: three edit types, no new tasks. AGENTS.md says Root owns build files, but `610218f8b`/`aad198489` and this task's brief put these edits in the slice; keep them minimal and list each in the report.

1. **Task inputs** (so Gradle re-runs the producer when the generator or archive changes): in every `Exec` task whose producer imports your generator or reads your archive add `inputs.file("tools/vNNN_<slug>.py")` and `inputs.dir("upstream-slices/<name>")`. Examples: `build.gradle.kts:285-287` (`extractUpstreamApi`), `:1500-1502` (`extractBgmDetail`), `:1551-1553` (`extractOriginalFavorites`). For several tasks use one trailing block, as `aad198489` did at `:4214-4230`: `tasks.matching { it.name in setOf("taskA","taskB") }.configureEach { inputs.files("tools/..."); inputs.dir("upstream-slices/...") }`. The string `upstream-slices/<name>` MUST appear at least once inside a quoted string, otherwise `buildInputRoots` cannot match (`sync-upstream.py:232,254-259`).
2. **Exclude the canonical copy** when your generator emits a complete replacement of a `direct`-mode v0.2.5 file: one line in `prepareUpstreamSources`, e.g. `build.gradle.kts:226` `exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/list/FavoriteManagementPolicy.kt"))`. Without it the same top-level names are compiled twice. Tests assert the line occurs exactly once (`test_v046_favorite_editor_source.py:115-116`). Files in any other mode are owned by their producer: advance that producer's chain instead, no exclude.
3. **Source-set roots**: prefer emitting through an existing producer so no new `srcDir` is needed. If you emit tests, keep main and test roots apart: main `kotlin.srcDir(".../generated/<x>/com")`, tests `kotlin.sourceSets.named("test") { kotlin.srcDir(".../generated/<x>/test") }` (`:2255` + `:4225`, `:2266` + `:4212`; `aad198489` moved the reply producer's main root to `.../dynamic-reply/com` at the moment the producer started emitting `test/`). `compileTestKotlin` follows `compileKotlin`'s `dependsOn` transitively.

Dead mechanism, ignore: `revisionNeutralSourceRecipePins` / `revisionNeutralSourceGradleRecipeSha256` (`build.gradle.kts:31-96`). The self-digest at `:81` has not matched the file since `2bc5c4879` and 9 of 28 recipe pins differ at HEAD (checked), so `revisionNeutralSourceRecipesMatch` is always false and the full manifest incl. `windowsRevision` is an input. Do not edit these pins.

## 5. (c) Generator authoring patterns

### 5.1 Module skeleton (`v046_favorite_editor.py` is the reference; `v043_message_link_host.py` the minimal one)

| Part | Where (reference) | Contract |
|---|---|---|
| constants | `v046_favorite_editor.py:7-12` | `COMMIT`, `ARCHIVE`, `MANIFEST_SHA256`, `EXPECTED` leaf set |
| `sha()` / `need()` | `:15-16`; `v044_favorite_detail.py:21-23` | `need` raises `ValueError` (family A). Family B (`v046_comment_list/thread/chrome`) uses bare `assert` (`AssertionError`). Pick one family per slice; tests assert the matching exception type. Asserts vanish under `python -O`; `need` does not |
| `fixed(repo)` | `:19-43` | directory not a symlink; leaf set == expected + `manifest.json` + `LICENSE`; manifest sha == constant; identity tuple; per leaf: not a link, `len`, `sha256`, git blob; LICENSE sha. Returns `(sources dict decoded utf8, manifest)` |
| `validate_outputs(repo, *outputs)` | `:46-53` | calls `fixed()`, rejects any output path inside or containing the archive ("overlaps"). Call it first in every producer `generate()` |
| `restore(value, proof)` | `:56-65` | applies an `inverse_proof` backwards; asserts `afterSHA256`, each hunk's `after` text, final `beforeSHA256` |
| `advance_*(repo, before[, output])` | `:80-268` | assert predecessor (`_before` against `manifest['beforeFinalOutputs']` or a constant), build the new body, return `(body, proof)` (family A) or write proof JSON via `save()` and return the body (family B, `v046_comment_list.py:50-55`) |
| `emit_*` | `:94-117, 271-298` | write whole-file outputs / original tests to the producer's output dirs |

### 5.2 Helpers you reuse (do not rewrite them)

- `inverse_proof(before, after)`: `v044_favorite_detail.py:61-71`. Line-level `difflib.SequenceMatcher(autojunk=False)` opcodes; result `{beforeSHA256, afterSHA256, wholeInverseExact, hunks:[{beforeLines, afterLines, before, after}]}`; it self-checks both directions. Cost is quadratic: a 3600-line file costs seconds; `SettingsManager.kt` (8853 lines) is the scale to watch.
- `once(text, old, new)` (`v046_comment_list.py:85-87`, AssertionError) / `_replace_once` (`v046_favorite_editor.py:75-77`, ValueError): replace exactly one occurrence or fail. Every hunk of every adaptation goes through one of these; never `str.replace` an anchor you did not count.
- `ensure_import(text, name)`: `v046_comment_list.py:90-96`. Adds `import <name>` after the first existing import if absent (fails with `ValueError` if the file has no import line). Use it for every symbol a pasted declaration names that the retained file does not import (section 7 P2).
- Declaration spans (all four are in the tree; their behaviour on expression-body functions is NOT the same, measured on `internal fun shortOne(a: Int): Boolean = a > 1` followed by a block-body function):

  | Selector | Location | Result for an expression-body `fun` |
  |---|---|---|
  | `fun_span` / `declaration(parser, text, name)` | `v046_comment_list.py:58-82` | SILENTLY WRONG: returns the expression function plus the next block-body function (walks to the next `{`). Only a uniqueness assert protects you |
  | `span` / `declaration(source, kind, name, syntax)` | `v044_favorite_subscription.py:66-93` | `ValueError: Unbounded original declaration` (fails closed; a lambda `{` before the next keyword would mis-span) |
  | `kotlin_structure` | `sync-upstream.py:625-658` | `UpdateError: Cannot delimit reviewed fun ...` (fails closed, breaks at `=`) |
  | `declarations(parser, source, names)` | `extract-appearance-platform.py:46-67` | correct, expression bodies and properties included. Quirk: the KDoc of the NEXT declaration is included in the previous selection |

  Use `declarations()` for helpers that may be expression-bodied (this is what the Source260 repair did, `v046_comment_list.py:649-654`); use `span`/`kotlin_structure` for block-body or class declarations. Parser: `syntax = parser(repo)` (`v044_favorite_subscription.py:47-52`) loads `sync-upstream.py`; `kotlin_tokens` (`sync-upstream.py:545-622`) skips comments and keeps literals, so brace counting is safe.
- `call_spans(parser, text, name)` (`v046_comment_list.py:363`) for call sites; `_guard_lambda` / `_action_span` (`v046_favorite_editor.py:162-190`) show token-driven lambda surgery.
- `bind_vm_member` (`v044_favorite_subscription.py:96-109`): the sanctioned way to turn an Android ViewModel member into a Windows-Root member (fixed text substitutions, then `need` that no Android port name survives).
- Windows ports used by precedent: `LocalDesktopCommentBindings.current` (`platform`), `com.bilipai.desktop.ui.DesktopWindowsCommentAlertDialog`, `com.bilipai.desktop.appearance.DesktopWindowConfiguration.current`, `AppLazyColumn`, `desktopUserVipPink()`. Grep the real file for a callee before using it; copy signatures from the real file (hard rule 6).

### 5.3 Three emission modes

1. **Whole original file**: copy the archive bytes unchanged (`emit_policy`, `v046_favorite_editor.py:271-275`; `FavoriteManagementPolicy.kt`), plus the canonical exclude (4.4 item 2). Record `policy_source()`-style row (`:278-283`).
2. **Whole original declaration advanced into the retained Windows file** (the dominant mode): select the complete original declaration, apply the minimum hash-pinned platform adaptation with `once`, replace the retained declaration, record BOTH proofs: `originalToWindows = inverse_proof(original, adapted)` and `retainedToCurrent = inverse_proof(before, after)` (`v046_favorite_editor.py:120-129, 132-159`; `v046_comment_list.py:136-176`). The test must be able to recover the untouched original from the output (6.2).
3. **New file for a new declaration owned by another producer** (never edit the other producer's file): `emit_response` writes `FavoriteInfoResponse.kt` next to the API output and proves the existing sole model producer (`v041_video_status_labels.py`) is unchanged (`v046_favorite_editor.py:94-117`). `package` must equal the directory (`com/android/purebilibili/...`).

Whole-original-vs-selected rule of thumb: a verbatim file that differs from the canonical copy still needs the exclude; a declaration inside a file you do not own must go through that file's producer chain.

### 5.4 Hooking into the producer (what `610218f8b` did)

- Top of `generate()`: `from vNNN_x import validate_outputs; validate_outputs(repo, output[, test_output])` (`extract-upstream-api.py:116-123`, `extract-upstream-favorites.py:321-324`).
- In the chain, after the previous layers: `body, proof = advance_x(repo, body)`; write the proof JSON `json.dumps(proof, ensure_ascii=False, indent=2) + "\n"` with `encoding="utf8", newline="\n"` next to the output (`extract-upstream-api.py:177-185`).
- Producers keep their own bookkeeping that you must extend: in `extract-upstream-favorites.py` the output `mode` (`:16-23`), the audit key allow-list (`:344`), and per-layer `records[-1][...] = audit` (`:69-74`). See P9, P10.
- Emitted file layout: main files under `<out>/com/android/purebilibili/<pkg>/Name.kt`; original tests under a separate test root (`--test-output` for the favorites producer, `<out>/test/com/...` for the BGM/reply producers); proof JSON in the output root (Kotlin ignores it).

### 5.5 Layer order

Producers are long chains (`extract-upstream-api.py:167-185`: live -> story -> search-all -> subscription -> editor -> danmaku-like). Rules observed: append your layer last; assert the exact predecessor you were authored against; never reorder. When your layer sits on top of earlier layers of the same output, every earlier slice's test that compares the FINAL output to its own proof must first peel your layer (section 6.3).

### 5.6 How Gradle consumes the result

`Exec` task in `desktop/` runs `python tools/extract-*.py --repo <repo root> --output build/generated/<x> [--test-output ...]` (`build.gradle.kts:284-314`), declares `outputs.dir`, main `kotlin.srcDir(...)` at `:2204-2273`, `tasks.named("compileKotlin") { dependsOn(...) }`. Generated files are build outputs and are never committed.

## 6. (d) Test-writing pattern: what a `test_v046_*_source.py` asserts

Template files: `test_v043_message_link_host_source.py` (113 lines, whole file + tests), `test_v046_danmaku_like_source.py` (117, one declaration), `test_v046_favorite_editor_source.py` (171, multi-layer), `test_v046_comment_chrome_source.py` (154, two producers), `test_v046_comment_list_source.py` (490, big). Skeleton: `REPO = Path(__file__).resolve().parents[3]`, `sys.path.insert(0, str(REPO/'desktop/tools'))`, import the generator, load producers with `importlib.util.spec_from_file_location`, `setUpClass` runs the REAL producers into `tempfile.TemporaryDirectory()` (short path, P13).

### 6.1 Assertion families (each is a test method in precedent)

1. **Archive integrity**: for every leaf, `manifest.json`, `LICENSE` and a made-up extra leaf, append a byte (or add the file) in a temp copy of the archive and assert `fixed()` raises (`test_v046_favorite_editor_source.py:43-50`; `test_v046_comment_chrome_source.py:67-82` also deletes `LICENSE`; `test_v043_message_link_host_source.py:58-79`).
2. **Original recoverable from output**: `restore(current, proof['originalToWindows'])` equals the original declaration, and `original in self.sources[leaf]`; `restore(current_file, proof['retainedToCurrent'])` equals the predecessor and its sha equals the manifest pin (`test_v046_favorite_editor_source.py:68-106`).
3. **Before/after comparison by patching the layer out**: run the producer twice, the second time with your `advance_*` patched to `lambda repo, before, output: before` (`test_v046_comment_list_source.py:33-47`, `test_v046_comment_chrome_source.py:33-34`), then assert that exactly the intended outputs differ (`test_v046_comment_chrome_source.py:57-65`) and that unrelated declarations are byte-identical (`test_v046_danmaku_like_source.py:70-77`).
4. **Fail-closed predecessor/anchor drift**: foreign body, missing declaration, duplicated declaration, already-advanced body each raise before any output (`test_v046_danmaku_like_source.py:60-68`; `test_v043_message_link_host_source.py:54-57`).
5. **Archive/output overlap**: `producer.generate(REPO, REPO/ARCHIVE/'child')` raises "overlaps" and archive bytes are unchanged (`test_v046_favorite_editor_source.py:130-139`).
6. **Determinism**: generate again, compare every output byte.
7. **Semantic tokens in the generated Kotlin**: the specific guards/calls the adaptation introduces (`test_v046_favorite_editor_source.py:88-106`), and `assertNotIn` of the Android ports it must remove (`Toast.makeText`, `SettingsManager.`, ... as in `v046_comment_list.py:158-161`).
8. **Gradle and inventory wiring**: parse the task block by name (`build.split('val NAME by tasks.registering', 1)[1]...`) and assert `inputs.file("tools/vNNN_x.py")` and `inputs.dir("upstream-slices/<name>")` appear once; assert the canonical `exclude(...)` count is 1 when applicable; `sync.adapted_compatibility_inputs(REPO)` rows for `ARCHIVE` exist, `not referenceOnly`, count == leaf count; `ARCHIVE in catalog['buildInputRoots']` (`test_v046_favorite_editor_source.py:141-150`, `test_v046_comment_chrome_source.py:105-116`, `test_v046_danmaku_like_source.py:35-48`).
9. **Every top-level name declared once and imports present** (the Source260 regression style): `test_v046_comment_list_source.py:459-473` (helper owned by exactly one generated file), `:187-204` (imports and shim symbols), `:475-487` (new parameter and both callers).
10. **Balanced delimiters** of every changed generated Kotlin file and every hand-written JVM regression file, via `kotlin_tokens` (`test_v046_favorite_editor_source.py:152-167`, `test_v046_danmaku_like_source.py:99-113`) plus `@Test fun` counts of hand-written classes.
11. **Emitted original suites**: `@Test` count equals the original count, only the documented import/class-name/path rewrites differ (`test_v046_favorite_editor_source.py:118-128`, `test_v046_comment_chrome_source.py:94-103,145-150`).

### 6.2 JVM tests

Hand-written Windows regression classes (`desktop/src/test/kotlin/com/bilipai/desktop/...`, for example `DesktopDanmakuLikeProtocolTest.kt` 8 cases, `DesktopFavoriteEditorRootRegressionTest.kt` 8) are source only: have the Python test count `@Test fun` and check delimiters. Original upstream tests are emitted by the generator: rewrite exactly the three JUnit import lines to `kotlin.test` when needed (`v046_favorite_editor.py:292-295`), rename the class with a `V046...Original...Test` prefix when the same FQCN is already emitted elsewhere (`v046_comment_chrome.py:86-87`; the favorites tests keep their names, so the rule is not uniform), assert the `@Test` count, and record an inverse proof (`v046_comment_thread.py:33-46`). Some upstream tests read source files by path (`File("src/main/java/...")`) or encode stale expectations: adapt the path with one pinned replacement; changing an assertion literal is only acceptable when production code is the unchanged upstream code and the exact original/current literals and calculation are recorded and the change is reported to Root (`v046_comment_chrome.py:88-95,109-118`, 8 -> 5 dp). Under hard rule 5 treat that as a case to flag, not a licence.

### 6.3 Updating older tests when you add a layer on an already layered output

Adding `v046-favorite-editor` forced these older tests to peel the new layer first (`610218f8b`): `test_extract_upstream_brand_callers.py`, `test_v044_fans_source.py`, `test_v044_favorite_detail_source.py`, `test_v044_favorite_search_source.py`, `test_v044_favorite_subscription_source.py`, `test_v044_list_foreground_source.py` (pattern: `stage = editor.restore(actual, row['v046FavoriteEditorAdvance']['retainedToCurrent'])` before their own `undo`), `test_extract_upstream_history_recap.py` (switched to the `historyFinalGeneratedAdaptation` audit), `test_v041_video_status_labels_source.py` (AST lint exception, 3 literals). `aad198489` did the same for `test_v029_comment_search.py`, `test_v029_reply_renderer.py`, `test_v041_dynamic_comment_identity_source.py`, `test_windows_pgc_screen_mount.py`. These are legitimate "pin update with exact recomputed value" cases; say so in the report. Find yours with the closure grep of 8.3 BEFORE you finish.

## 7. (e) Pitfalls (each with evidence, rule, check)

**P1 Duplicate top-level declarations** (Source260 class 1: 8 errors in 5 files, `shouldUseFloatingLiquidBottomInputBar` and `resolveBottomInputBarContentBottomPadding`, `root-source260-compile-failure-repair-20261018.json`). The copied whole `BottomInputBar.kt` re-declared helpers that `DesktopOriginalDetailInputPadding` already owns. Rule: before copying a whole file or declaration group, list its top-level names and assert exactly one owner across the compile universe: `desktop/src/main`, every producer output, canonical `direct` files not excluded, other slice emissions. Two flavours: two generated files (fix: remove the duplicate with `declarations()` and assert it equals the original's, `v046_comment_list.py:649-654`), or generated vs canonical `direct` file (fix: the exclude, 4.4 item 2). Check (ran it for `shouldUseFloatingLiquidBottomInputBar`: it finds the canonical `app/.../BottomInputBar.kt:100` and the archive copy `v046-comment-list/BottomInputBar.kt:103`; generated outputs are not in the tree, so also run the producers): `git grep -n -E "^[[:space:]]*(internal |private |public |inline |suspend |@Composable )*(fun|val|var|class|object|interface|typealias)[[:space:]]+(<[^>]*>[[:space:]]+)?([A-Za-z0-9_.<>?, ]+\.)?NAME\b" -- desktop/src desktop/upstream-slices app design-system core-data core-player network-core settings-core` plus, in your test, count `'fun NAME('` over all emitted `*.kt` (`test_v046_comment_list_source.py:459-473`). Function overloads with different signatures are legal; identical signatures are not.

**P2 Names used by a copied declaration but not imported by the retained file** (Source260: `mutableIntStateOf` 4 errors incl. inference fallout, `AppContentColors` 3). A retained Windows file is baseline-derived; the original's import list does not come with the pasted declaration. Rule: after every paste, diff the original's imports against the retained file's and `ensure_import(after, fqcn)` (`v046_comment_list.py:166,236`), with the FQCN copied from the original's import line; same-package symbols need none. Check: Appendix B (`import_audit.py <repo> <original.kt> <retained.kt>`): validated on 2026-10-18 against the repaired `DesktopOriginalSubReplyDetailComponents.kt` (0 candidates) and against the same file with the two imports removed (flags exactly `AppContentColors` and `mutableIntStateOf`). Known false positive on the repaired reply file: `imageLoader` (a parameter name that is also an import name). It cannot see same-package symbols or wildcard imports; it is a candidate list, read each hit.

**P3 Symbols the pinned tree lacks** (Source260: `userVipPink` 4 errors; `AppBottomSheetMotion.reducedMotion` 2 errors, absent from pinned v0.3.7 `AppSheetComponents`). Rule: for every simple name in a copied declaration prove a definer exists in the compile universe, not merely in the vendored tree: only `direct` `sources[]` entries and producer emissions are compiled (section 1), and some definers live only in other slice archives (a search of `design-system app core-data desktop/src desktop/upstream-slices` finds `object AppContentColors` only in `desktop/upstream-slices/v038-danmaku-merge/` and `v042-search-dependencies/`). Fix patterns: a desktop-owned function (`desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopUserVipPink.kt`, call sites rewritten with `windows_vip_pink`, `v046_comment_list.py:117-119`) or a generated compatibility extension that a real member outranks (`COMPAT_REDUCED_MOTION`, `:105-114`). Both came with a JVM test and a Python assertion (`test_v046_comment_list_source.py:187-204`). The roadmap's expected-miss seed list (slice `fnd-symbol-resolution-gate`) is the current known open set; that gate does not exist yet, so this check is manual.

**P4 A Windows-owned function that the pasted call site gives a new argument** (Source260: `LandscapeCommentPanel` used `pinnedReplyIds` it never declared, 1 error, plus 2 consequence errors from the ambiguous helper, String vs Dp). Rule: when you add an argument at a call site or a use inside a retained function, declare the parameter on that function and forward it from every caller; count callers with `call_spans` and assert the count (`v046_comment_list.py:593-604`, `test_v046_comment_list_source.py:475-487`).

**P5 Expression-body functions and span selectors**: see the table in 5.2. `fun_span` is the dangerous one. Rule: for any helper you did not read yourself use `declarations()` or assert `selected.count('fun ') == 1` as `v046_comment_list.py:654` does.

**P6 KDoc leakage from `declarations()`**: it keeps the next declaration's KDoc in the previous selection. When you remove a selected helper (`once(body, selected, '')`) you may delete a neighbour's comment; read the diff.

**P7 Layer order and predecessor pins**: append only (5.5). A changed anchor in an earlier layer breaks every later `_before()` hash and every older test that peels (6.3). If you must change an earlier layer's output, recompute dependent pins and report them.

**P8 Cross-slice lints**: the "no other generator names file X" scan (`test_v041_video_status_labels_source.py:561`) and the AST import allowlist for `v041_video_status_labels.py` (`:629`). A new generator that mentions a filename owned elsewhere fails them; the precedent exceptions for `v044_favorite_detail.py` (`:572`) and `v046_favorite_editor.py` (`:589`, exactly three read-side literals) end at `:628`.

**P9 Producer output-mode classification can silently skip your file.** In `extract-upstream-favorites.py:16-23` an output whose bytes equal the row's recorded `sha256LfUtf8` is classed `direct` and, outside `STANDALONE`, is NOT written. A whole-original emission must be forced to `selected` (the `v046FavoriteEditorOriginal` clause on `:20`). Read the target producer's `emit()` before assuming a whole file is written.

**P10 Audit allow-lists**: new audit keys must be added to the allow-list at `extract-upstream-favorites.py:344` or the proof silently disappears from the emitted inventory and your test sees a `KeyError`.

**P11 Test sources under a main source root would be compiled into main.** Keep `<out>/com` and `<out>/test` apart (4.4 item 3; `aad198489` changed `generated/dynamic-reply` to `generated/dynamic-reply/com` when the producer began to emit `test/`).

**P12 Canonical copy not excluded** (P1 flavour 2). Check: `grep -c 'exclude(canonicalOriginalIdentity("<path>"))' desktop/build.gradle.kts` is exactly 1.

**P13 Windows MAX_PATH (260).** Reproduced during this study: a producer output under a 270-character scratch path failed with `FileNotFoundError` on `.../test/com/android/.../V046CommentAvatarPushOriginalPolicyTest.kt` (the directory was created, the file path was too long). Some producers wrap paths in `\\?\` (`extract-upstream-favorites.py:12-15`, `extract-upstream-video-detail-holder.py:25-27`), `v046_comment_list.emit_shared` does not. Run tests and probes with a short root such as `tempfile.TemporaryDirectory(prefix='bp-x-')` (about 50 characters), never under the agent scratchpad or a deep project path.

**P14 Line endings.** Global `core.autocrlf=true` (`C:/Program Files/Git/etc/gitconfig`), so checkouts can hold CRLF working copies of LF blobs (the 8 tracked files of different kinds that I checked in this worktree hold LF in the working tree). Rules: (a) run git as `git -c core.autocrlf=false`; (b) every touched tracked file must show `i/lf` in `git -c core.autocrlf=false ls-files --eol <paths>` (archive leaves show `attr/-text`, still `i/lf`); (c) untracked new files: scan for any carriage return before `git add`, command in 8.4; (d) every Python write uses `write_text(..., encoding="utf8", newline="\n")` or `write_bytes` (default text mode on Windows writes CRLF; `Set-Content`/`>` in PowerShell 5.1 write CRLF or UTF-16); (e) read working-tree text defensively (`.replace('\r\n','\n')`; the favorites producer also folds lone `\r`, `extract-upstream-favorites.py:15`); (f) hash with the right normalisation: raw bytes for slice archives, LF for canonical files (`v025-canonical-sources.json` `hashNormalization`) and for the catalog digest (`sync-upstream.py:36,243`). NVIDIA precedent: `desktop/tools/nvidia/nvidia_core_source.py:35` normalises CRLF on read and `assert_lf_only` (`:70`) asks git for the index word, and `test_nvidia_line_endings.py` lists every file of the work (`assertGreaterEqual(len(files), 45)`; add your files to such a registry if your kind has one).

**P15 Escaping and encoding when scripting edits.** Always `encoding="utf8"` (Windows default code page is not UTF-8; run with `PYTHONUTF8=1 python -X utf8`). Python string literals containing Kotlin `\n`/`\"` escapes need doubled backslashes or raw strings: no precedent uses raw strings, they use real newlines inside Python literals (`v046_favorite_editor.py:154,214`), so a Kotlin string escape is the one place to be careful. Kotlin `$` templates are safe in plain (non-f) Python strings. Do not pass non-ASCII through a console argument; use `@file.json` (Appendix A). `json.dumps(..., ensure_ascii=False)` for manifests with Chinese text.

**P16 Python version/perf.** `Path.write_text(newline=...)` needs Python 3.10+ (3.12 locally and in CI, `windows-source-validation.yml:33`). `inverse_proof` is quadratic in lines; big files make tests slow (editor tests 29 s).

**P17 JVM tests never run in Windows CI unless in the plan** (4.2). Also: the Linux run found a hard-coded `ps=20` against the pinned `VIDEO_COMMENT_PAGE_SIZE=30` (`ade424f63`): when a test hard-codes an upstream constant, reference the constant.

**P18 Dead recipe pins** (4.4 end). Do not spend time on them.

**P19 Stale-plan failures are expected** at integration HEADs (`validate-source-cohort.py --check-only` fails on the first changed pinned path).

**P20 Kind B (NVIDIA) specifics.** Python checks live in `desktop/tools/nvidia/`, are NOT in `run-tool-tests.py`'s `desktop/tools/tests` discovery and are not wired into `.github` (run with `-s desktop/tools/nvidia -p "test_nvidia_*.py"`, 323 cases, 2 s; tell Root to wire them). `test_nvidia_gate_preservation.py:49` `GATE_FILE_SHA256` pins the presentation-proof files; only Root updates it. No file of the work may spell the shader-list option; it is assembled once (`nvidia_core_source.py:32`, asserted at `test_nvidia_gate_preservation.py:820`). Golden vectors are one JSON shared by Kotlin and Python (`desktop/src/test/resources/nvidia-gpu-classification-vectors.json`); Python only reads data and never re-implements the Kotlin logic. No inventory/manifest registration is needed for Kind B.

**P21 `upstream-sources.json` descriptive digests can be stale.** `v046CommentChrome.manifestSHA256` (`:14994`, `cf040529...`) differs from the real manifest sha (`18a7deb1...`, the constant at `v046_comment_chrome.py:8`); no test reads it. If you write such a field use the real digest from the tool (the v041 test does enforce its own record). Report the stale one to Root rather than editing it silently.

**P22 Commit hygiene.** No co-author/generated-by lines. Whole-history `git log --format=%B | grep -ci co-authored-by` returns 8 (legacy/upstream commits), so audit only your range: `git log --format=%B 2ab2ef06c..HEAD | grep -ci co-authored-by` must print 0. Commit message precedent for source-only work (NVIDIA `126c9976e`, `4347ac2af`): the first paragraph says the slice is source only and that nothing was compiled or run on hardware.

**P23 Do not claim the four known failing suites pass** and do not try to repair them (`test_rtx_hdr_color`, `test_extract_upstream_brand_success_mount`, `test_extract_upstream_up_danmaku`, `test_extract_upstream_comment_renderer`).

## 8. (f) Commands, by kind of change

### 8.1 Environment line (run from the worktree root)

bash: `PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/tests -p <file>`
PowerShell: `$env:PYTHONUTF8='1'; $env:PYTHONDONTWRITEBYTECODE='1'; $env:PYTHONPATH='desktop/tools'; python -X utf8 -B -m unittest discover -s desktop/tools/tests -p <file>`
Run one Python job at a time (memory policy); the timings above were taken that way except the 417 s run.

### 8.2 What to run after each change kind

| Change | Commands | Time |
|---|---|---|
| New or edited archive / manifest | Appendix A `verify-archive`; `counts`; then `test_sync_upstream.py -k AdaptedCompatibilityInventoryTests` | about 5 min |
| Catalog / digest pin / counts edited | Appendix A `counts` must equal the pins; then the whole `test_sync_upstream.py` | about 7 min |
| New or edited generator module or producer hook | your `test_vNNN_<slug>_source.py`; every file from the closure grep (8.3); `test_sync_upstream.py`; `test_validate_source_cohort.py`; `test_package_source_qualification.py` | tens of seconds + closure (about 2 min) + 7 min + 1 s |
| `build.gradle.kts` only | closure grep on `build.gradle.kts` consumers (your test, `test_v041_video_status_labels_source.py` 9 s), then `test_sync_upstream.py` (the regex at `sync-upstream.py:232` reads it) | about 7 min |
| Older test updated for a new layer (6.3) | that file, plus the owner's own test | seconds each |
| Kind B Kotlin/Python | `-s desktop/tools/nvidia -p "test_nvidia_*.py"` and the eol scan (8.4); closure grep for any `desktop/tools/tests` file that names the touched Kotlin file | 2 s + eol |
| Before hand-off to Root | everything above that applies, plus 8.4 | about 10 min total |

`python desktop/tools/run-tool-tests.py --stage generated` runs all 184 `test_*.py` files; it was not measured and is not required by the brief (Root/CI runs it).

### 8.3 Closure grep (find every test that names what you touched)

```
names='v046_my_slice|v046-my-slice|extract-upstream-favorites'   # generator module, archive dir, each producer you hooked
grep -l -E "$names" desktop/tools/tests/*.py
```
For the favorite-editor family this lists 15 files (14 older + its own). Measured run of the 14 older ones: `test_extract_upstream_brand_callers` 8 s, `test_v029_comment_search` 10, `test_v029_reply_renderer` 4, `test_v041_dynamic_comment_identity_source` 4, `test_v041_video_status_labels_source` 9, `test_v044_fans_source` 2, `test_v044_favorite_detail_source` 15, `test_v044_favorite_search_source` 7, `test_v044_favorite_subscription_source` 27, `test_v044_list_foreground_source` 7, `test_windows_pgc_screen_mount` 1, `test_comment_selection_consumer_closure` 9, `test_extract_upstream_dynamic_reply` 3, `test_v029_comment_composer` 7 (all OK).

### 8.4 Hygiene commands

- Line endings of tracked files: `git -c core.autocrlf=false ls-files --eol -- <paths>` -> `i/lf` everywhere (archive leaves also `attr/-text`).
- Carriage-return scan of working files (also untracked): `python -c "import sys;[print(p, 'CR' if b'\r' in open(p,'rb').read() else 'ok') for p in sys.argv[1:]]" <paths>`
- Attribution: `git log --format=%B 2ab2ef06c..HEAD | grep -ci co-authored-by` -> `0`; also `grep -ci "generated with"` -> `0`.
- Scope: `git -c core.autocrlf=false status --short` shows only your paths; none under the protected list of section 0.
- Digest sanity: Appendix A `counts` equals `sync-upstream.py:36` and `test_sync_upstream.py:982-985`.

## 9. (g) Worked example: a one-file slice (modelled on `v043-message-link-host`)

Hypothetical `v046-foo-policy`: replace `FooPolicy.kt` (canonical `direct`-mode v0.2.5 file) by the v0.4.6 file with one platform adaptation, and emit its original test `FooPolicyTest.kt`. Real analogue: `v043_message_link_host.py` (62 lines), `test_v043_message_link_host_source.py` (113 lines), `build.gradle.kts:176,1986-1989`, `extract-space-platform.py:109-113`.

1. Preflight (section 2): mode `direct`, owner producer `P` (the Gradle task that already lists the file in `inputs.file(canonicalOriginalSource(...))`), no sole-producer lint, layer appended last.
2. `make-archive` for the two paths (+LICENSE), `verify-archive`; capture the manifest sha.
3. Generator `desktop/tools/v046_foo_policy.py`:
   ```python
   from pathlib import Path
   import hashlib, json
   COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
   ARCHIVE = 'desktop/upstream-slices/v046-foo-policy'
   MANIFEST_SHA256 = '<from make-archive>'
   EXPECTED = {'FooPolicy.kt', 'FooPolicyTest.kt'}
   PREDECESSOR_SHA256 = '<LF sha of canonical_source(repo, <path>) body>'
   ANCHOR_BEFORE, ANCHOR_AFTER = '<one exact line>', '<its Windows binding>'

   def sha(v): return hashlib.sha256(v.encode('utf8') if isinstance(v, str) else v).hexdigest()
   def need(c, m):
       if not c: raise ValueError(m)

   def fixed(repo):   # copy the structure of v046_favorite_editor.fixed (:19-43): listing, manifest pin, identity, per-leaf len/sha/blob, LICENSE
       ...
   def validate_outputs(repo, *outputs):   # copy :46-53 (overlap guard)
       ...
   def advance(repo, predecessor):
       sources, _ = fixed(repo); original = sources['FooPolicy.kt']
       need(sha(predecessor.replace('\r\n', '\n')) == PREDECESSOR_SHA256, 'Foo policy predecessor changed')
       need(original.count(ANCHOR_BEFORE) == 1, 'Foo policy anchor changed')
       adapted = original.replace(ANCHOR_BEFORE, ANCHOR_AFTER, 1)
       need(adapted.replace(ANCHOR_AFTER, ANCHOR_BEFORE, 1) == original, 'Whole original inverse failed')
       return adapted, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, wholeOriginal=True,
                            originalSHA256=sha(original), adaptedSHA256=sha(adapted), jvmTestsExecuted=False)

   def original_tests(repo):
       raw = fixed(repo)[0]['FooPolicyTest.kt']
       need(raw.count('@Test') == <N>, 'Original Foo test count changed')   # then the same 3 import rewrites as v046_favorite_editor.py:292-295 if it uses org.junit
       return raw
   ```
   (Skeleton, not run; the real versions of every function exist in the cited files.)
4. Hook into producer `P`: `validate_outputs(...)` at the top of its `generate()`; after its chain `body, proof = advance(repo, body)`; write `v046-foo-policy-proof.json` (`ensure_ascii=False, indent=2, newline='\n'`); write the adapted file under `<out>/com/android/purebilibili/<pkg>/FooPolicy.kt`; write the test into the producer's test root.
5. `build.gradle.kts`: `exclude(canonicalOriginalIdentity("<path>/FooPolicy.kt"))` in `prepareUpstreamSources`; `inputs.file("tools/v046_foo_policy.py")` and `inputs.dir("upstream-slices/v046-foo-policy")` in `P`'s task (or one trailing `tasks.matching` block); make sure a test source root exists for the emitted test.
6. `inventory --apply` (feature `v046-foo-policy`, scope text), copy `catalogSha256Lf` to `sync-upstream.py:36`, the four counts to `test_sync_upstream.py:982-985,1732,1735,1752` (entries +2, distinct +2 unless the upstream path is already inventoried, buildInputRoots +1, archives +1).
7. `test_v046_foo_policy_source.py` from the `test_v043_message_link_host_source.py` template: archive tamper (changed leaf, changed test, manifest +1 byte, extra leaf), predecessor drift, only-the-anchor-changed + inverse, emitted file equals `advance()` output and proof equals the written JSON, overlap rejection, gradle wiring (exclude once, inputs once), test count.
8. Closure grep (8.3) for `v046_foo_policy|v046-foo-policy|<P filename>`; run each; peel/pin any older test that fails because of the new layer.
9. P1-P4 audits: top-level names of `FooPolicy.kt` unique across the universe; import audit original vs emitted; every callee grepped; no pinned-tree-lacking symbol.
10. Section 8.2 commands, 8.4 hygiene, then the hand-off report (4.2). Do not touch `.github/**`, `windowsRevision`, or the plan.

## 10. Ambiguities (stated, not resolved)

1. Manifest field names and optional keys differ per era (`filename` vs `archiveFile`, `sha256` vs `sha256Bytes`/`sha256LF`/`sha256Raw`; 133 manifests: 91 distinct top-level key sets, 51 distinct file-row key sets). No validator enforces a schema; the v046 shape is the recommendation only.
2. Error style: `need`/`ValueError` (v043/v044/v046-editor) vs `assert`/`AssertionError` (v046-comment-*). Tests are written to the family's type.
3. LICENSE leaf: required by nothing, present in 4 of 133 archives.
4. Who owns `desktop/build.gradle.kts` and the inventory edits: AGENTS.md says Root owns build files and version metadata; precedent slice commits and this task's brief place the wiring in the slice. Treat wiring as the slice's, `windowsRevision` and `.github/**` as Root's.
5. Test class rename rule (`V046...Original...Test` vs original name).
6. Where pins live (generator constant, manifest, embedded `PINS`).
7. Row order in the catalog and archives (not validated, not uniform).
8. Whether a descriptive `upstream-sources.json` block is expected (comment-list/thread have none; one existing digest is stale).
9. Whether Kind B Python checks should be wired into CI (currently not).
10. Roadmap slice `fnd-symbol-resolution-gate` (not yet implemented) is meant to replace the manual P1-P3 audits; until then they are manual and heuristic.

## 11. Precedent index

| Slice | Archive leaves | Generator (lines) | Test (lines) | Introduced by |
|---|---|---|---|---|
| v046-favorite-editor | 8 + LICENSE + manifest | `v046_favorite_editor.py` (298) | `test_v046_favorite_editor_source.py` (171) | `610218f8b` |
| v046-danmaku-like (shares the editor archive; row `features` lists both) | none own | `v046_danmaku_like.py` (64) | `test_v046_danmaku_like_source.py` (117) | `610218f8b` |
| v046-comment-chrome | 4 + LICENSE + manifest | `v046_comment_chrome.py` (119) | `test_v046_comment_chrome_source.py` (154) | `610218f8b` |
| v046-comment-list | 22 + LICENSE + manifest | `v046_comment_list.py` (754) | `test_v046_comment_list_source.py` (490) | `aad198489`, repaired by `e46fe930d`, pin `ade424f63` |
| v046-comment-thread | 1 + LICENSE + manifest | `v046_comment_thread.py` (117) | `test_v046_comment_thread_source.py` (141) | `aad198489` |
| v044-favorite-detail | 8 + manifest (no LICENSE) | `v044_favorite_detail.py` (229; defines `inverse_proof`) | `test_v044_favorite_detail_source.py` (163) | `90a47197a` |
| v043-message-link-host | 2 + manifest | `v043_message_link_host.py` (62) | `test_v043_message_link_host_source.py` (113) | `93c843b81` |
| NVIDIA S1-S8 (Kind B) | none | `desktop/tools/nvidia/nvidia_core_source.py` + `test_nvidia_*.py` | same dir | `dc78695fd`..`98956d1b6`, `64eaf32ce` |

Registration deltas of `610218f8b` (the reference commit, 59 files): `desktop/build.gradle.kts` +18, `desktop/tools/compatibility-source-inputs.json`, `desktop/tools/sync-upstream.py` (1 line), `desktop/tools/tests/test_sync_upstream.py` (counts), `desktop/upstream-sources.json`, plus Root-side `.github/windows-source-validation-plan.json` and `.github/workflows/windows-source-validation.yml`.

---

## Appendix A: `slice_archive_tools.py` (not in the repository; stdlib only)

Save outside the repository (for example next to your scratch files), run from the worktree root. Validated 2026-10-18: `make-archive` byte-identical on two archives, `verify-archive` ok on the four v046 archives, `inventory --apply` byte-identical to the committed catalog when re-adding `v046-comment-thread`, `counts` equals the pinned values.

```python
#!/usr/bin/env python3
"""slice_archive_tools.py - helper for the slice authoring playbook. NOT part of the repository.

Stdlib only, Python 3.10+, no JVM, no network (reads local git objects with `git cat-file`).

  make-archive   write desktop/upstream-slices/<name>/ (leaves + LICENSE + manifest.json) from local upstream objects
  verify-archive re-derive every leaf, the LICENSE and the manifest rows from local upstream objects and compare
  inventory      print / apply the compatibility-source-inputs.json rows for an archive that exists on disk
  counts         print the four numbers test_sync_upstream.py pins and the catalog digest sync-upstream.py pins
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

UPSTREAM = 'jay3-yy/BiliPai'
SOURCE_SUFFIXES = ('.kt', '.kts', '.java', '.js', '.glsl')   # sync-upstream.py:344 - these leaves need an `inputs` row
CATALOG = 'desktop/tools/compatibility-source-inputs.json'


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def blob_oid(raw):
    return hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()


def git(repo, *args):
    # `git cat-file` never applies autocrlf or attributes, so the bytes are exactly the upstream blob.
    return subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(repo), *args],
                          check=True, capture_output=True).stdout


def dump(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + '\n').encode('utf8')   # LF, trailing newline


def make_archive(repo, commit, tag, name, paths, out_root, extra):
    assert re.fullmatch(r'[0-9a-f]{40}', commit), 'use the full 40-hex upstream commit'
    assert git(repo, 'cat-file', '-t', commit).strip() == b'commit', 'upstream commit is not in the local object store'
    root = Path(out_root) / name
    root.mkdir(parents=True, exist_ok=False)            # never overwrite an archive
    rows, names = [], set()
    for path in paths:
        leaf = Path(path).name                          # flat archive: leaf name == basename(path), must be unique
        assert leaf not in names and leaf not in {'LICENSE', 'manifest.json'}, 'basename collision: ' + leaf
        names.add(leaf)
        raw = git(repo, 'cat-file', 'blob', commit + ':' + path)
        assert oid_matches(repo, commit, path, raw)
        (root / leaf).write_bytes(raw)                  # binary write: no newline translation
        rows.append(dict(path=path, filename=leaf, bytes=len(raw), sha256=sha256(raw), gitBlob=blob_oid(raw)))
    licence = git(repo, 'cat-file', 'blob', commit + ':LICENSE')
    (root / 'LICENSE').write_bytes(licence)
    manifest = dict(repository=UPSTREAM, tag=tag, commit=commit, hashNormalization='raw', files=rows,
                    license=dict(path='LICENSE', sha256=sha256(licence)))
    manifest.update(extra)                              # selection / purpose / beforeFinalOutputs ... (slice specific)
    raw = dump(manifest)
    (root / 'manifest.json').write_bytes(raw)
    return root, sha256(raw)


def oid_matches(repo, commit, path, raw):
    return git(repo, 'rev-parse', commit + ':' + path).strip().decode() == blob_oid(raw)


def verify_archive(repo, name):
    root = Path(repo) / 'desktop/upstream-slices' / name
    raw = (root / 'manifest.json').read_bytes()
    manifest = json.loads(raw)
    problems = []
    commit = manifest['commit']
    if raw != dump(manifest):
        problems.append('manifest.json is not json.dumps(indent=2, ensure_ascii=False) + LF')
    expected = {row['filename'] for row in manifest['files']} | {'manifest.json'} | ({'LICENSE'} if 'license' in manifest else set())
    if {p.name for p in root.iterdir()} != expected:
        problems.append('leaf set differs from manifest rows + manifest.json + LICENSE')
    for row in manifest['files']:
        upstream = git(repo, 'cat-file', 'blob', commit + ':' + row['path'])
        leaf = (root / row['filename']).read_bytes()
        if leaf != upstream:
            problems.append('leaf differs from the upstream blob: ' + row['filename'])
        if (row['bytes'], row['sha256'], row['gitBlob']) != (len(leaf), sha256(leaf), blob_oid(leaf)):
            problems.append('manifest row does not describe the leaf: ' + row['filename'])
        if Path(row['path']).name != row['filename']:
            problems.append('filename is not basename(path): ' + row['filename'])
    licence = git(repo, 'cat-file', 'blob', commit + ':LICENSE')
    if 'license' not in manifest or not (root / 'LICENSE').is_file():
        problems.append('no LICENSE leaf / license key (pre-v046 archives have none)')
    elif (root / 'LICENSE').read_bytes() != licence or manifest['license']['sha256'] != sha256(licence):
        problems.append('LICENSE differs from the upstream root LICENSE or its pin')
    return problems


def archive_rows(repo, name):
    directory = 'desktop/upstream-slices/' + name
    root = Path(repo) / directory
    manifest = json.loads((root / 'manifest.json').read_bytes())
    files = []
    for leaf in sorted(root.iterdir(), key=lambda p: p.name.lower()):
        raw = leaf.read_bytes()
        files.append(dict(path=directory + '/' + leaf.name, sha256=sha256(raw), hashNormalization='raw', blob=blob_oid(raw)))
    inputs = [dict(path=row['path'], localPath=directory + '/' + row['filename'], upstreamCommit=manifest['commit'],
                   blob=row['gitBlob'], sha256=row['sha256'], hashNormalization='raw', kind='adaptedSource')
              for row in manifest['files'] if row['filename'].endswith(SOURCE_SUFFIXES)]
    return directory, dict(directory=directory, files=files), inputs


def catalog_counts(catalog_bytes):
    catalog = json.loads(catalog_bytes)
    inputs = catalog['inputs']
    return dict(entries=len(inputs), distinct=len({(r['upstreamCommit'], r['path']) for r in inputs}),
                buildInputRoots=len(catalog['buildInputRoots']), archives=len(catalog['archives']),
                catalogSha256Lf=sha256(catalog_bytes.replace(b'\r\n', b'\n')))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    a = sub.add_parser('make-archive')
    a.add_argument('--repo', required=True); a.add_argument('--commit', required=True); a.add_argument('--tag', required=True)
    a.add_argument('--slice', required=True); a.add_argument('--out-root', required=True)
    a.add_argument('--path', action='append', required=True, help='upstream repo path; repeat')
    a.add_argument('--extra-json', help='JSON object (or @file.json) merged after `license`: selection, beforeFinalOutputs, ...')
    v = sub.add_parser('verify-archive')
    v.add_argument('--repo', required=True); v.add_argument('--slice', required=True)
    i = sub.add_parser('inventory')
    i.add_argument('--repo', required=True); i.add_argument('--slice', required=True)
    i.add_argument('--feature', required=True); i.add_argument('--used-scope', required=True)
    i.add_argument('--partial-reuse', action='store_true'); i.add_argument('--reference-only', action='store_true')
    i.add_argument('--catalog', help='catalog file to rewrite (default: <repo>/' + CATALOG + ')')
    i.add_argument('--apply', action='store_true')
    c = sub.add_parser('counts'); c.add_argument('--catalog', required=True)
    args = parser.parse_args()
    if args.command == 'make-archive':
        extra = args.extra_json or '{}'
        extra = json.loads(Path(extra[1:]).read_text(encoding='utf8') if extra.startswith('@') else extra)   # '@file.json' avoids console code-page damage
        root, digest = make_archive(args.repo, args.commit, args.tag, args.slice, args.path, args.out_root, extra)
        print(json.dumps(dict(archive=str(root), manifestSha256=digest), indent=2))
        return 0
    if args.command == 'verify-archive':
        problems = verify_archive(args.repo, args.slice)
        print(json.dumps(dict(slice=args.slice, ok=not problems, problems=problems), indent=2))
        return 1 if problems else 0
    if args.command == 'counts':
        print(json.dumps(catalog_counts(Path(args.catalog).read_bytes()), indent=2))
        return 0
    path = Path(args.catalog or Path(args.repo) / CATALOG)
    raw = path.read_bytes()
    catalog = json.loads(raw)
    directory, archive, inputs = archive_rows(args.repo, args.slice)
    assert directory not in catalog['buildInputRoots'] and all(x['directory'] != directory for x in catalog['archives']), 'already registered'
    for row in inputs:   # same key order as every existing row
        row.update(features=[args.feature], referenceOnly=args.reference_only)
        if args.partial_reuse or args.used_scope:
            row.update(partialReuse=args.partial_reuse, usedScope=args.used_scope)
    catalog['buildInputRoots'].append(directory)         # only valid if build.gradle.kts mentions "upstream-slices/<slice>"
    catalog['archives'].append(archive)
    catalog['inputs'].extend(inputs)
    out = dump(catalog)
    print(json.dumps(dict(newInputRows=len(inputs), archiveLeaves=len(archive['files']), after=catalog_counts(out)), indent=2))
    if args.apply:
        path.write_bytes(out)
    return 0


if __name__ == '__main__':
    sys.exit(main())
```

Limits: the tool writes only what the schema above needs. Slice-specific manifest keys go through `--extra-json`. It does not edit `build.gradle.kts`, `sync-upstream.py` or `test_sync_upstream.py`; do those by hand from its output. `inventory` assumes `hashNormalization: raw`.

## Appendix B: `import_audit.py` (not in the repository; stdlib only)

Source260-class detector. Usage: `python import_audit.py <repo> <original.kt> <retained-or-generated.kt>`; exit 1 when it finds candidates. Validated 2026-10-18 as described in P2; it is a heuristic (candidates, not proofs).

```python
#!/usr/bin/env python3
"""import_audit.py - Source260-class detector, stdlib only, no JVM. NOT part of the repository.

Reports simple names that the ORIGINAL (upstream) file imports, that the RETAINED/GENERATED file uses as code
tokens, and that the retained file neither imports nor declares at top level. This is the failure class
`mutableIntStateOf` / `AppContentColors` of the Source260 compile run (a copied original declaration names a
symbol that only the original file's import list provided).

    python import_audit.py <repo> <original.kt> <retained-or-generated.kt>

Heuristic limits (read them): same-package symbols need no import and are invisible to this check; extension
members, annotation arguments inside strings and wildcard imports are not resolved; a hit is a candidate, not a proof.
"""
import importlib.util
import re
import sys
from pathlib import Path


def load_parser(repo):
    spec = importlib.util.spec_from_file_location('syntax', Path(repo) / 'desktop/tools/sync-upstream.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def imports(text):
    names = {}
    for match in re.finditer(r'(?m)^import\s+([\w.]+)(?:\.\*)?(?:\s+as\s+(\w+))?\s*$', text):
        full, alias = match.group(1), match.group(2)
        if match.group(0).rstrip().endswith('.*'):
            continue
        names[alias or full.rsplit('.', 1)[-1]] = full
    return names


def top_level_names(syntax, text):
    tokens = syntax.kotlin_tokens(text)
    depth, names = 0, set()
    for index, (word, _, _) in enumerate(tokens[:-1]):
        if depth == 0 and word in {'fun', 'val', 'var', 'class', 'object', 'interface', 'typealias'}:
            following = tokens[index + 1][0]
            if following == '<':                      # generic extension: fun <T> name(
                continue
            names.add(following)
            if index + 3 < len(tokens) and tokens[index + 2][0] == '.':   # extension receiver: fun Recv.name(
                names.add(tokens[index + 3][0])
        depth += (word == '{') - (word == '}')
    return names


def audit(repo, original, retained):
    """Returns [(kind, simple name, original import)], kind is 'bare' (definite candidate) or 'dotted' (extension?)."""
    syntax = load_parser(repo)
    imported = set(imports(retained))
    declared = top_level_names(syntax, retained)
    body = re.sub(r'(?m)^import .*$', '', retained)
    tokens = [word for word, _, _ in syntax.kotlin_tokens(body)]
    findings = []
    for name, full in sorted(imports(original).items()):
        if name in imported or name in declared:
            continue
        # a bare token followed by ':' or '=' is a parameter declaration or a named argument, not a use of the import
        bare = sum(1 for i, word in enumerate(tokens) if word == name and (i == 0 or tokens[i - 1] != '.')
                   and (i + 1 == len(tokens) or tokens[i + 1] not in {':', '='}))
        dotted = sum(1 for i, word in enumerate(tokens) if word == name and i > 0 and tokens[i - 1] == '.')
        if bare:
            findings.append(('bare', name, full))
        elif dotted > body.count(full):          # dotted uses that are not the fully qualified import path itself
            findings.append(('dotted', name, full))
    return findings


if __name__ == '__main__':
    repo, original_path, retained_path = sys.argv[1:4]
    original = Path(original_path).read_bytes().decode('utf8').replace('\r\n', '\n')
    retained = Path(retained_path).read_bytes().decode('utf8').replace('\r\n', '\n')
    found = audit(repo, original, retained)
    for kind, name, full in found:
        print('MISSING IMPORT candidate (%s use):' % kind, name, '<-', full)
    print(len(found), 'candidate(s)')
    sys.exit(1 if found else 0)
```

## Appendix C: what was NOT done

No Kotlin/Java compile, no JVM test, no Gradle, no GUI, no network. The repository was not modified (verified `git status --short` empty after every run). The skeleton in section 9 is illustrative and was not executed; the P1 grep was run for one name only (output quoted there), and the 8.3 closure grep was run per name, not as the loop shown; every cited function, line and number was read from the tree or measured as stated. The four known-failing suites were not repaired. Windows validation and the Linux Grok Bot compile remain Root's, later.
