# Roadmap 切片 #12 plr-subtitle-menu-hotfix（字幕菜单滚动，临时单 hunk）

- 分支 grokbot/slices-batch1，基于 origin/desktop/integration-263@dd37c7abc。提交：`9398d2f7e Roadmap plr-subtitle-menu-hotfix: …`。代码由 Grok Build 编写，我审查范围并运行验证。
- 改动文件（只限 desktop/tools/*.py 和新测试，**没有碰受保护文件**；没有改归档、build.gradle.kts、upstream-sources.json、.github）：
  - `desktop/tools/v042_player_readability.py`：新增 BottomControlBar 路径。上游 hunk 以常量固定：5 行原文 + sha256 `e8b9cd88…fb80`，上游 blob `d2d07232…905f`（v0.4.2 `5c34a824` 与 v0.4.6 `d6bf8b16` 的 BottomControlBar.kt 是同一个 blob）。只在 140–220.dp 字幕菜单 Column 内插入 `.verticalScroll(rememberScrollState())`；要求锚点唯一、两条 import 已存在（不新增 import），并用现有逆向检查和证明 JSON（附 upstreamBlob、upstreamCommits、wholeFileNotPinned、supersededBy=plr-overlay-and-controlbar-repin）。TITLE/SUBTITLE 逻辑原样移进 else 分支，生成结果逐字节不变（见下）。
  - `desktop/tools/extract-upstream-video-player-full-controls.py`：BottomControlBar 在 emit 前最后一步调用该层（+2 行）。
  - 新测试 `desktop/tools/tests/test_v042_subtitle_menu_scroll_source.py`（3 项）：hunk 哈希；两个上游提交里 hunk 恰好出现一次且 blob 一致（对象缺失时跳过并给出明确信息）；真实运行生产者，生成文件恰含一次 hunk、证明正确、逆向还原输入哈希、二次应用和锚点变动都失败关闭。
- 为什么不改 v042 归档：归档在受保护的 `desktop/upstream-slices/**` 下。按路线图，这个切片会被 plr-overlay-and-controlbar-repin 整文件重新钉扎取代，所以做成自成一体、容易被取代的层。

## 验证
| 项 | 结果 |
|---|---|
| compileKotlin compileJava compileTestKotlin compileTestJava | BUILD SUCCESSFUL，0 错误（增量：extractOriginalPlayerFullControls、extractOriginalVideoFullscreenPager、verifyOriginalPlayerFullControls 重跑并通过） |
| 生成物逐字节对比（改前/改后） | original-video-player-full-controls 中**只有 BottomControlBar.kt 多出一行** `.verticalScroll(rememberScrollState())`；另有 v037 只读回执和 v037 BottomControlBar 证明 JSON 记录了新输出哈希和这一处声明的增量；新增 v042 证明 JSON。TopControlBar、PortraitSubtitleOverlay 以及 original-video-fullscreen-pager 全部**逐字节相同** |
| 新测试 test_v042_subtitle_menu_scroll_source | 3 OK |
| test_original_player_nvidia_slot（垫片） | 3 OK |
| test_extract_video_enhancement | 7 OK |
| test_validate_source_cohort / test_package_source_qualification / test_sync_upstream | 25 OK（skip 1）/ 14 OK / 74 OK |
| test_extract_upstream_video_interactions | 1 失败/3，**基线（stash 掉改动）完全相同**：`6fc660ca… != b360f506…`。这两个值正是 v029 comment-selection 外层阶段的前后哈希，与 T10 同类（测试没撤销后加的外层阶段），与本切片无关 |
| JVM：feature.video.ui.overlay.* + DesktopOriginalVideoChaptersTest + DesktopWindowsCommand* + nvidia 14 | 27 类，517 测试，0 失败 |
| 符号闭包检查 | pass（见文末补充） |

## 交给 Root
- 不涉及归档、登记计数、目录摘要变化，无需重新冻结 inventory。新增 Python 测试文件需要加进 windows-source-validation.yml（Root 负责）。
- 已有的缺口（与本切片无关，只记录）：Gradle 任务 extractOriginalPlayerFullControls 的 inputs 没列 `tools/v042_player_readability.py` 和 v042 归档，单改 v042 时该任务可能被判为最新而不重跑。本次因为生产者文件也改了，所以重跑了。修它要改 build.gradle.kts，我没动。
- 小瑕疵：BottomControlBar 证明里仍带着 v042 归档的 sourceManifestSHA256（字段沿用），以 sourceSHA256（hunk 哈希）和 upstreamBlob 为准。

## 证据分类
Linux 编译：已验证。Python：已验证（上表）。JVM：已验证（27 类）。未验证：Windows 云验证；菜单在真实窗口里能否滚动（UI 未运行）。

## 补充：符号闭包检查
- `test_symbol_closure.py`：Ran 98，OK（skipped=2，均为 Windows 长路径规则），3m43s。
- `check_slice_symbol_closure.py --repo .`（本提交）：result **pass**，unexpected 0，stale 0，known 58，latent 4，retired 1，77s。
