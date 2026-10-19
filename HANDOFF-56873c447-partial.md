# 56873c447 partial findings (executor, stopped at 12:58 BST because a second worker was running the same VERIFY)

- Worktree /workspace/verify-f0b8-wt moved f0b8e2e27 -> 56873c447 (detached), build dir = the f0b8e2e27 clean build.
- Planted at 12:51:32 BST: build/generated/dynamic-reply/.../feature/video/ui/components/GrokbotStrayProbe.kt and
  build/generated/home-page/.../feature/home/GrokbotStrayProbeHome.kt (both reference an unresolved type, so they fail compile if they survive).
- Incremental compileKotlin compileTestKotlin (inc1/gradle-20261019-125141.log): BUILD SUCCESSFUL 1m17s, 0 e:, 0 w:.
  167 actionable tasks: 159 executed, 8 up-to-date. Every Exec producer re-ran (build script changed => task actions changed).
  Both planted files gone. compileKotlin/compileTestKotlin UP-TO-DATE => regenerated Kotlin sources byte-identical to the f0b8 clean build.
- build/generated hash compare (gen-before-f0b8clean.sha vs gen-after-inc1.sha, 2491 files): identical except 2 JSON proofs:
  original-space-pages/source-transform-replay.json, original-tablet-owner-space/selection-proof.json (copies in nd/). Not yet determined
  whether this is producer nondeterminism or dependence on previous output; next step was to compare with a clean build.
- Steady-state probe (inc2, after all producers are UP-TO-DATE): a stray .kt planted in build/generated/dynamic-reply is NOT removed:
  extractUpstreamDynamicReply stays UP-TO-DATE, compileKotlin fails with Unresolved reference on the stray. The doFirst hook only runs when
  the producer itself re-executes (input/script change), which is the retired-file case it targets; a foreign file added to an
  up-to-date producer's dir is not cleaned. (Second worker's decoy GrokbotStaleDecoy.kt hit the same.)
- Static audit of build.gradle.kts at 56873c447: 151 Exec tasks, 165 generated output dirs (4 declared via vals generatedAppearance,
  generatedAppearanceResources, generatedThemeRoleTests, generatedChromeActionTests, which the regex in test_gradle_generated_outputs_clean.py
  does not see); no shared/nested dirs incl. those 4; no Exec task declares its own output dir as input; no overlap with the Sync dirs
  generated/upstream, generated/plugin-resources; outputs.file verification JSONs are files so the hook (isDirectory filter) skips them.
- Not done: clean build, timings vs clean, JVM ui + core.ui.*, Python 3 files, report, push.

## 13:04 BST: second executor (this one) took over 56873c447
- inc2 (decoy GrokbotStaleDecoy.kt in dynamic-reply/com/grokbot) reproduced the steady-state finding; decoy removed; inc3 BUILD SUCCESSFUL 9s, 0 e:.
- Now running: clean build in /workspace/v5687-clean (fresh worktree), Python 3 files in /workspace/v5687-py; then JVM ui + core.ui.*, JSON-proof compare, report, push grokbot/verify-56873c447.
