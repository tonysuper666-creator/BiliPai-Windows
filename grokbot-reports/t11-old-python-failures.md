# T11 三个旧 Python 测试失败（只读分析，WIP）

对象：desktop/integration-263 @ 3c4a93457。运行时带 `\\?\` 垫片（PYTHONPATH=pyshim:desktop/tools），失败原文在 logs/fail-*.txt。
bisect：好端取各测试文件最后一次修改的提交（已确认在好端通过），坏端 3c4a93457，在隔离 worktree 中执行 `git bisect run`，日志在 logs/bisect-*.log。

| 测试 | 结果 | 第一个坏提交 |
|---|---|---|
| test_extract_upstream_up_danmaku | 2 项失败 / 10 | 38512320c（2026-10-16，Validate full favorite schema and original agreement ownership） |
| test_extract_upstream_brand_success_mount | 2 项失败 / 3 | 75522724c（2026-10-14，Windows: verify original slider preview and admitted coin state fixes） |
| test_extract_upstream_comment_renderer | 2 项失败 + 1 个错误 / 5 | 2be215988（2026-10-10，Mount original related video cards and source-owned UP navigation） |

## 1. up_danmaku：测试陈旧，证明完好（已有补丁）
- 失败：`restore_later_owner_stages` 期望最终 VM 的 sha 等于 v037 known-state 的 after（6fc660ca…），实际是 b360f506…，和 T10 是同一种模式。
- 根因：38512320c 在 owner 链的最外层加了 v029 comment-selection 阶段（v029-comment-selection-VideoPlaybackViewModel.json），但测试的撤销列表没有包含它。
- 修复：只改测试，在撤销元组最前面加一行 `'v029-comment-selection-VideoPlaybackViewModel.json'`。现有循环已经能处理 `inverseEdits` 和 `offset` 键，并逐阶段校验 after/before sha。没有删除或放宽任何断言。
- 补丁：patches/t11-test-up-danmaku-unwind-v029-comment-selection.patch。在 3c4a93457 上打补丁后 **10/10 OK**（Python，带垫片）。
- 生成器的实际输出：不受影响（撤销链逐级哈希吻合，说明 comment-selection 的证明本身有效）。

## 2. brand_success_mount 和 3. comment_renderer
分析中，见后续提交。
