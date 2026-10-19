# T10 test_v029_failure_recovery 两项旧失败（只读分析）

对象：desktop/integration-263@dd37c7abc。生成器和测试都没有提交改动；本分支只含报告、失败原文和补丁文件。

## 1. 失败原文
完整输出见 `t10-failing-run-dd37c7abc.txt`（带 `\\?\` 垫片，Ran 5，FAILED failures=2）。要点：
- `test_fresh_usecase_alias_consumes_three_classifiers_and_retry_with_complete_inverse`，第 93 行
  `self.assertEqual(related["afterSha256LF"], sha256(typed))`：`34257885…06e2 != 53456012…c2b8`（失败文件是 DesktopOriginalVideoLoadProtocol.kt；UseCase 没有 related 证明文件，不触发）。
- `test_fresh_whole_vm_inverse_and_actual_native_observer_consumption`，第 61 行 → `restore()` 第 31 行
  `assert body[index:index+len(after)] == after` 失败（从最终 VM 文本直接撤销 postRelated 编辑，偏移处不是预期文本）。

## 2. 根因：测试陈旧，证明链完好
**第一个坏提交：`38daac558 Align Windows playback cache and early detail with v0.3.4`（2026-10-12）**。父提交 `4f8ca6e75` 两项通过。
用 `git bisect run` 在隔离 worktree 中只读确定（好端 4e44a638c，即该测试最后一次修改；坏端 dd37c7abc）；轨迹：
4e44a638c OK、166fe22fa 失败、653504582 OK、f3bb84062 失败、4f8ca6e75 OK、53c0564c9 失败、aed5c58b1 失败、38daac558 失败。

38daac558 在两个生成器里，于测试所撤销的阶段**之后**插入了新阶段，后续提交又继续往后加：
- 核心（extract-upstream-video-state-core.py 的 `emit`）：`related → detail-bootstrap → on-demand 请求缓存`（38daac558），之后又加 `playurl-failure`。LoadProtocol 的最终文本经过 on-demand 和 playurl-failure 两层。
- 整 VM（extract-upstream-video-full-owner.py）：postRelated 之后依次是 `detail`（38daac558）、`metadata-related`、`next-watch`、`autoplay-continuation`、`v037 known-state`、`v029 comment-selection`。

每个新阶段都在生成器里自行做精确逆向断言，并写下证明（`*.v034-playurl-failure-inverse.json`、`*.v034-on-demand-inverse.json`、owner 行里的 `detail/metadataRelated/nextWatch/autoplayContinuationInverseEdits`、`v037-complete-known-state-owner-proof.json`、`v029-comment-selection-VideoPlaybackViewModel.json`）。owner 生成器还会在外层阶段开始**之前**，自己校验 postRelated → related → postSameSend 这段链。
测试却从**最终输出**直接撤销 related/postRelated，没有先撤销外层阶段，于是哈希或偏移对不上。

哈希链核对（探针脚本，只写临时目录）：
- 核心 LoadProtocol：最终 `53456012…` =playurl-failure.after → 撤销后 `b6d3b64d…` =on-demand.after → 撤销后 `34257885…` = related.after（正是测试期望值）。
- 整 VM：最终 = comment-selection.after → 撤销后 `6fc660ca…` = known-state.after → 撤销后 = known-state.before → 依次撤销 autoplay、next-watch、metadata-related、detail → 恰好等于 `postRelatedAfterSha256LF`。之后测试原有的全部断言，包括 `_failure_recovery_delta` 重放和 `_typed_failure_usecase/_protocol` 重放，**一字不改全部通过**。

结论：**测试陈旧**，不是逆向证明失效。

## 3. 对 Gradle 和编译产物的影响
证明没有失效，所以不存在“受影响的生成器输出”。两个生成器的输出仍由 Gradle 的现有生产任务消费，但生成的 Kotlin 不受这两项失败影响：失败只发生在测试的撤销顺序上，生成器内部的逆向断言每次 Gradle 运行都会执行，且通过（dd37c7abc 上无缓存编译 BUILD SUCCESSFUL，见 grokbot/verify-dd37c7abc）。这个测试不在 Windows 验证计划里，所以云端从没报过。

## 4. 最小修复方案（仅补丁，未提交到任何可合并分支）
`t10-test-v029-unwind-outer-stages.patch`（只改 desktop/tools/tests/test_v029_failure_recovery.py，+40/−2，由 Grok Build 编写，我审过）：
- `restore()` 加可选键参数（默认 `offset`；known-state 证明用 `afterOffset`）。
- 整 VM 测试：先按 comment-selection → known-state → autoplay → next-watch → metadata-related → detail 撤销，每层核对前后 sha256，最后断言等于 `postRelatedAfterSha256LF`，再进入原链。
- 核心测试：先撤销 playurl-failure（偏移式）和 on-demand（按 count 的替换式），每层核对前后 sha256；并要求 LoadProtocol 的这两份证明文件必须存在，防止以后阶段调整时静默跳过。
- 没有删除、放宽或跳过任何原断言，只新增校验。
- 验证：`git apply --check` 在 dd37c7abc 上通过；打补丁后 `test_v029_failure_recovery.py` Ran 5，OK（带垫片）。

另一种做法是改生成器，让它额外记录“外层阶段前”的中间文本或检查点。改动更大，也没有必要，不推荐。

## 证据分类
Linux 编译：dd37c7abc 已验证（另见 verify 报告）。Python：失败重现、二分、探针、打补丁后 5/5，均已验证。JVM：不适用。未验证：Windows 上运行（不在计划里）。
