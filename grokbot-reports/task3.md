# Task 3 — 评论修复分支编译检查（desktop/source261-comment-closure-compile-repair @ e46fe930d）

结论：修复分支**不用再改代码**就能在 Linux 上编过主代码和测试代码；要求的 7 个 Python 测试全部通过（需加路径垫片）；23 个评论相关 JVM 测试类共 171 项，只有 1 项失败，这个失败在 256 基线上就有，不是这次修复引入的（推断，依据见下）。

## 环境（14:4x 伦敦时间重新准备）
- 云电脑之前被重置过，这次重装了 Temurin JDK 21.0.12.1+1（packages.adoptium.net apt 源）和 GNU time。
- 沿用 Task 1 的变通：仓库外 Python 路径垫片（/workspace/g-logs/python-strip-winpath.sh + pyshim/sitecustomize.py）；两个 build 目录桩文件（诊断 DLL 用审核 pin，JS worker 用**全零哈希**）；命令行 `-Dorg.gradle.jvmargs=-Xmx6g -Xss16m -XX:MaxMetaspaceSize=1g -XX:ReservedCodeCacheSize=256m -Dfile.encoding=UTF-8`；`-x prepareNativeDiagnosticShare -x fetchJsWorkerJdk -x prepareJsWorker`（跑 test 时另外加 `-x prepareJsWorkerResources`，它会打包 Windows JS worker 资源）。没改构建文件。
- 顺带一提：仓库自己的云端校验器 validate-source-cohort.py 在 Windows 上用的是 `-Xmx8g`（CLOUD_GRADLE_HEAP），所以 Linux 上要 6g 跟云端的配置是一致的。

## 0. 校准：260（aad198489）compileKotlin
`BUILD FAILED`（冷启动 10m28s），**共 24 个错误**，与 Windows 云端的 24 个**逐类一致**：

| 类别 | 云端 | Linux |
|---|---|---|
| shouldUseFloatingLiquidBottomInputBar / resolveBottomInputBarContentBottomPadding 重复声明或二义性 | ~8 | 8（DetailInputPadding 2 个 Conflicting overloads、BottomInputBar 2 个 Conflicting overloads、二义性 4 个：BottomInputBar:185、DynamicDetailLayout:281、BangumiCommentPanel:119、VideoDetailPhoneContent:158） |
| userVipPink 未解析 | 4 | 4 |
| AppContentColors 未解析 | 3 | 3 |
| reducedMotion 未解析 | 2 | 2 |
| mutableIntStateOf/intValue | 4 | 4（含 1119:33 Cannot infer type 'T'） |
| pinnedReplyIds 未解析 | 1 | 1 |
| String/Dp 类型不匹配 | 2 | 2 |

没有差异，说明 Linux 编译能代表 Windows 云端的 Kotlin 编译错误。按文件列出（路径相对 desktop/build/generated/）：

- `dynamic-detail-container/com/android/purebilibili/feature/dynamic/DesktopOriginalDynamicDetailLayout.kt`
  - 281:47 Overload resolution ambiguity between candidates:
- `dynamic-detail-container/com/android/purebilibili/feature/video/ui/components/DesktopOriginalDetailInputPadding.kt`
  - 14:10 Conflicting overloads:
  - 8:10 Conflicting overloads:
- `dynamic-reply/com/android/purebilibili/feature/video/ui/components/CommentCascadingContent.kt`
  - 50:27 Unresolved reference 'reducedMotion'.
- `dynamic-reply/com/android/purebilibili/feature/video/ui/components/CommentContentReveal.kt`
  - 28:37 Unresolved reference 'reducedMotion'.
- `dynamic-reply/com/android/purebilibili/feature/video/ui/components/DesktopOriginalReplyComponents.kt`
  - 616:93 Unresolved reference 'userVipPink'.
  - 859:53 Unresolved reference 'AppContentColors'.
  - 931:97 Unresolved reference 'userVipPink'.
- `dynamic-reply/com/android/purebilibili/feature/video/ui/components/DesktopOriginalSubReplyDetailComponents.kt`
  - 1119:33 Cannot infer type for type parameter 'T'. Specify it explicitly.
  - 1119:55 Unresolved reference 'mutableIntStateOf'.
  - 1192:65 Unresolved reference 'userVipPink'.
  - 1329:68 Unresolved reference 'intValue'.
  - 1359:83 Unresolved reference 'intValue'.
  - 1666:53 Unresolved reference 'AppContentColors'.
- `original-bangumi-player-ui/com/android/purebilibili/feature/bangumi/ui/player/DesktopOriginalBangumiCommentPanel.kt`
  - 119:25 Overload resolution ambiguity between candidates:
  - 154:57 Argument type mismatch: actual type is 'String', but 'Dp' was expected.
  - 234:52 Argument type mismatch: actual type is 'String', but 'Dp' was expected.
- `original-video-content-full/com/android/purebilibili/feature/video/screen/DesktopOriginalVideoContentSection.kt`
  - 1683:46 Unresolved reference 'pinnedReplyIds'.
- `original-video-detail-holder-full/com/android/purebilibili/feature/video/screen/VideoDetailPhoneContent.kt`
  - 158:60 Overload resolution ambiguity between candidates:
- `original-video-fullscreen-pager/com/android/purebilibili/feature/video/ui/components/VideoCommentSheetHost.kt`
  - 990:45 Unresolved reference 'AppContentColors'.
- `video-comment-ui/com/android/purebilibili/feature/video/ui/components/CommentSearchSheet.kt`
  - 515:89 Unresolved reference 'userVipPink'.
- `video-tablet-full/com/android/purebilibili/feature/video/ui/components/DesktopOriginalBottomInputBar.kt`
  - 103:10 Conflicting overloads:
  - 117:10 Conflicting overloads:
  - 185:31 Overload resolution ambiguity between candidates:

## 1–2. 修复分支编译
- 本地工作分支 `grokbot/source261-compile-fix` 从 origin/desktop/source261-comment-closure-compile-repair（e46fe930d）切出。
- compileKotlin：**BUILD SUCCESSFUL in 4m30s**，0 个错误，**457 条 warning**（256 基线是 455，**+2，增幅不明显**）。新增的两条都是 `Condition is always 'true'`，位置在 DesktopOriginalReplyComponents.kt:1049 和 CommentSearchSheet.kt:647，代码是复制来的 v046 原版 `if (pushAvatar && avatarListState != null)`，无害。（260 校准那次只打出 1 条 warning，因为编到一半就失败了，不能拿来比。）采样到 Gradle java 进程合计 RSS 峰值约 7.0 GiB。
- compileTestKotlin：**BUILD SUCCESSFUL in 2m4s**，0 个错误，98 条 warning（跟 256 一样）。

## 3. 逐条错误 / 修复
无。修复提交 e46fe930d 已经把 24 个错误全部消掉，Linux 上没有新错误，所以**没让 Grok Build 改代码，本分支也没有代码改动**。

## 4. Python 测试（PYTHONPATH=desktop/tools，加 \\?\ 垫片）
| 测试 | 加垫片 | 不加垫片（CI 方式） |
|---|---|---|
| test_v046_comment_list_source.py | OK 26 | 1 error（导入期就挂了，只跑到 10 项） |
| test_v046_comment_thread_source.py | OK 10 | OK 10 |
| test_v029_reply_renderer.py | OK 3 | 1 error |
| test_extract_upstream_dynamic_reply.py | OK 2 | OK 2 |
| test_v029_comment_search.py | OK 7 | 导入错误 |
| test_windows_pgc_screen_mount.py | OK 11 | 导入错误 |
| test_validate_source_cohort.py | OK 25（skip 1） | OK 25（skip 1） |
不加垫片时的失败都是生成器 `\\?\` 前缀的 Linux 路径问题（跟 Task 2 同一类）。

## 5. JVM 测试（一次 Gradle，23 个类，用 --tests 指定）
共 171 项：**170 通过，1 失败，0 跳过**。DesktopUserVipPinkTest 1/1 通过。
各类：CommentReadRouteMemoTest 4、DesktopCommentReadRouteConsumptionTest 4、DynamicCommentLoadPolicyTest 10、DynamicCommentPreviewPolicyTest 5、DesktopV029CommentTimeTest 3、DesktopV029ReplyLinkPolicyTest 4、DesktopVideoCommentCacheConsumptionTest 5、VideoCommentLoadPolicyTest 5、DesktopDynamicCommentCacheConsumptionTest 11、DesktopReplyMedalColorTest 4、DesktopCommentAvatarSettingsTest 6、DesktopCommentFraudActualAdmissionTest 3、DesktopCommentFraudHistoryFilesTest 6、DesktopCommentFraudHistoryTest 6、DesktopBgmCommentLinkRootNavigationTest 27、DesktopChargedCommentProtocolTest 4、DesktopOriginalVideoComposerCommentsTest 22、DesktopUserVipPinkTest 1、DesktopWindowsCommentComposerLayoutTest 4、DesktopWindowsCommentPreviewRequestTest 2、**DesktopWindowsCommentSearchBindingTest 12/13**、DesktopWindowsVideoCommentActionsTest 17、WindowsCommentComposerReplayTest 5。

**失败项**：`DesktopWindowsCommentSearchBindingTest.originalTimePagingOffsetsProgressAndTopDedupeAreConsumed`，测试期望全文搜索每页 ps=20，实际是 30。
- 根因：生成的 VideoCommentViewModel 全文搜索用的是 `VIDEO_COMMENT_PAGE_SIZE`，它在固定上游切片 `desktop/upstream-slices/v036-video-comment-cache/VideoCommentLoadPolicy.kt:12` 里是 30（10-13 的提交 e3e1914cb 引入）；这个测试是 10-05 写的（fe40c13dc），当时按 20 写。
- 跟本次修复无关：这两个提交都在 256 里；测试文件和 v036 切片在 256 和 261 之间完全相同；260/261 也没碰评论加载策略或页大小。**推断 256 上也失败，但我没在 256 上实际跑过。** 云端校验器跑的是按 cohort 过滤后的 test 子集，这个测试多半不在里面，所以云端一直没报。
- **没改**：到底该改测试期望（30，跟固定上游一致）还是改页大小，是语义问题，需要用户决定。

## 6. 提交
本分支只加报告（grokbot-reports/task3.md），没有代码改动。

## 证据分类
- 已在 Linux 编译通过：261 的 compileKotlin、compileTestKotlin（上述变通下）；260 校准复现了 24 个错误。
- Python 检查通过：7 个指定测试全部通过（加垫片）。
- JVM 测试通过：评论相关 23 个类里 170/171 项通过，DesktopUserVipPinkTest 通过；1 项失败，看起来是基线就有的问题。
- 未验证：Windows 云端验证、界面、真机。
