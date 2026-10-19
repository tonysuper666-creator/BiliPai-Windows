# T11 三个旧 Python 测试失败（只读分析）

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

## 2. brand_success_mount：证明链完好，但测试契约需要 Root 决定
- 根因：75522724c 在写出 `video-operations-members.fragment` 之后调用 `v037_player_interaction.extend_same_producer`，读回 fragment 再改写，并记录 `v037-original-interaction-root-operations-proof.json`（4 条 indexedEdits，exactCompleteInverse）。同一提交新加的 v037_video_feedback_adapter、v037_feedback_origin、v037_video_coin_request 只改 VideoEngagementViewModel.kt，不写这份 fragment。
- 哈希链首尾相接：品牌 before 890224a0… → 品牌 after 217a0a5e… = v037 before → v037 after 90f9d28c… = 最终 fragment。没有断环，Gradle 消费的就是链尾，`verifyOriginalVideoDetailMembers` 也照常通过。
- **语义变化（关键）**：v037 把 fragment 里带 `confirmBrandFollow(change.isFollowing)` 的回调整段换成了不带品牌的回调，品牌确认移到协议层（v037_player_interaction.py:62-63 把 `BrandSuccessEvents.followChanged(follow)` 映射成 `DesktopOriginalVideoEngagementPresentation.confirmBrandFollow(follow)`，出现在生成的 DesktopOriginalVideoEngagementProtocol.kt 里）。所以最终 fragment 里这个调用是 0 次，关掉品牌 adapt 再生成也得到同样的 90f9d28c…，原测试“最终产物含品牌确认、基线与逆向结果相等”在设计上已经不可能同时成立。
- 补丁（patches/t11-test-brand-success-unwind-v037-interaction-root.patch）：先按 v037 proof 校验 after/before sha 并解链，再跑原有断言，3/3 OK。**注意**：补丁把三处断言的对象从“磁盘最终文件”改成“解掉 v037 之后的中间文本”，baseline 等式的左值也一样。这等于把测试从“最终输出契约”变成“品牌阶段自身的证明”，属于改变断言含义，**需要 Root 决定**：接受这个补丁，还是改为断言品牌确认在协议层（DesktopOriginalVideoEngagementProtocol.kt 里 `confirmBrandFollow(follow)` 恰好一次）。我倾向于两者都要，后者由 Root 撰写。

## 3. comment_renderer：两件独立的事
- bisect 的第一坏提交 2be215988 只打掉了 COMMENTS 那条字符串断言。哈希失败和 inverse 失败来自后面的 aad198489：它在 v021 记下 `outputBodySha256` 之后、写盘之前接入了 v046 的 reply-list、subreply-list、comment-thread-host 阶段。
- 哈希链完好：ReplyComponents 落盘 0ddd57a3… = v046 reply-list after，其 before = v021 output 08263c70…；SubReplyDetail 落盘 ff0003b6… → fbc70b06… → v021 output 4fca255d…。ReportReasonDialog 和 ImagePreview 落盘就是 v021 的输出。四处 completeInverse 都为真，Gradle 编译的是链尾。
- 补丁（patches/t11-test-comment-renderer-unwind-v046-stages.patch）：按外层优先，用各阶段 `retainedToCurrent` 的 proof 解链（要求文件存在，并核对 before/after sha 与 wholeInverseExact），原断言全部保留。修完后哈希和 inverse 两项通过，**剩 1 项失败**（5 项里 4 项 OK）。
- 剩下的失败需要 Root 决定：2be215988 和 595cdc3c2 把 `if (selectedTab == DesktopWindowsVideoDetailsTab.COMMENTS) { Box(...) }` 重构成了 loading 骨架屏加 `when (selectedTab) { COMMENTS -> Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) { comments() } }`。这是 desktop 自有调用方的结构变化，补丁**没有**改这条断言。如果 Root 接受这个重构，建议只替换这一条 assertIn，保留后面两条：
  - `showComments = selectedTab == DesktopWindowsVideoDetailsTab.COMMENTS`
  - `DesktopWindowsVideoDetailsTab.COMMENTS ->\n                    Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) { comments() }`

## 额外发现：第 4 个旧失败
`test_extract_upstream_v031_repost_coin` 在干净的 3c4a93457 上也失败（1/4）：PortraitVideoPager.kt 最终字节 fdf7a9ee… ≠ v035 story 的 afterSha256LF 3ceff443…。看起来是同类“后加阶段未解链”的模式，但还没分析，建议列为 T12。

## 证据分类
- Linux 编译：无（只读分析，不需要编译）。
- Python：上面各测试在 Linux 上带垫片的运行结果；bisect 在隔离 worktree 中执行。
- JVM：无。
- 未验证：Windows 上的运行；Root 决定后的断言替换方案。
