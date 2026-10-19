# VERIFY desktop/integration-263-claude-main@47f38a420（#4 sheet motion 已摘入 ecc3f879b + COMPAT_REDUCED_MOTION 垫片退役）

本报告只汇报，不改代码。指令来源：origin/desktop/grokbot-queue@021ea0c90（VERIFY.md 第 5 行；提交作者 tonysuper666-creator，已核对）。

## 环境
- Linux（Debian 13，x86_64），Temurin JDK 21.0.12.1，Python 3.13.5，Gradle 9.5.0（仓库 gradlew）。
- worktree 检出 47f38a420（分支 grokbot/verify-47f38a420，未改任何被跟踪文件），在上一轮 bd7ff2324 的构建目录上做**增量**构建（VERIFY 行允许增量）。
- grokbot/linux-bootstrap 的做法：两个桩（原生诊断钉、JS worker 全零桩，只为编译）、python-strip-winpath 垫片、`-Xmx6g -Xss16m`、`--no-daemon --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process`，排除 prepareNativeDiagnosticShare、fetchJsWorkerJdk、prepareJsWorker、prepareJsWorkerResources。
- Gradle：`compileKotlin compileTestKotlin test --continue --rerun --tests 'com.bilipai.desktop.ui.*' --tests 'com.android.purebilibili.core.ui.*' --tests 'com.android.purebilibili.feature.video.ui.components.*' --tests <评论 22 类中不在上述包里的 12 个类>`，2 分 48 秒。

## 结果总览

| 项 | 结果 |
|---|---|
| compileKotlin（增量） | **成功，0 个 `e:` 错误**；无 “extension is shadowed by a member”，无 “Unresolved reference” |
| compileTestKotlin | **成功，0 个 `e:` 错误** |
| JVM 合计 | 222 个类，1536 项，3 项失败（都是已知 JS worker initializationError），0 跳过 |
| Python（带垫片） | 5 个文件全过（见下表） |

## 重点：.reducedMotion 的解析（垫片退役）

1. **生成器不再发射垫片**：47f38a420 的 v046_comment_list.py 删除了 COMPAT_REDUCED_MOTION，emit_shared 不再写 DesktopOriginalCommentMotionCompat.kt。重新生成后的 `build/generated/dynamic-reply/.../feature/video/ui/components/` 里只有 9 个文件，没有这个文件。
2. **只解析到真实成员**：两处读取
   - CommentContentReveal.kt:28 `sheetMotion.reducedMotion`（`sheetMotion = rememberAppBottomSheetMotion()`，第 27 行）
   - CommentCascadingContent.kt:50 `motion.reducedMotion`（`motion = rememberAppBottomSheetMotion()`，第 49 行）
   
   都指向 `com.android.purebilibili.core.ui.AppBottomSheetMotion` 的真实成员 `val reducedMotion: Boolean = false`（build/generated/dynamic-detail-container/com/android/purebilibili/core/ui/DesktopOriginalDetailAppSheetComponents.kt:409 的 data class，成员在 :418；`rememberAppBottomSheetMotion` 在 :427 用 `LocalSystemReduceMotion.current || !LocalComponentMotionEnabled.current` 赋值），即 #4（ecc3f879b）带来的 v0.4.6 AppSheetComponents。
   在 desktop/src 和 desktop/build/generated 全部 .kt 里搜 `AppBottomSheetMotion.reducedMotion`：**0 处扩展定义**，所以不存在扩展与成员并存，编译 0 错误说明没有未解析引用。
3. **观察（重要，供 Root 判断）：增量构建会留下旧的垫片文件**。第一次增量构建（日志 gradle-20261019-111809.log）时，构建目录里还留着上一轮（bd7ff2324，10:33 BST）生成的 `DesktopOriginalCommentMotionCompat.kt`，编译器给出：
   ```
   w: .../generated/dynamic-reply/com/android/purebilibili/feature/video/ui/components/DesktopOriginalCommentMotionCompat.kt:5:35 This extension is shadowed by a member: 'val reducedMotion: Boolean' defined in 'com.android.purebilibili.core.ui.AppBottomSheetMotion'.
   ```
   原因：extractUpstreamDynamicReply（build.gradle.kts:1420–1439，Exec 任务，`outputs.dir(generated/dynamic-reply)`）和 extract-upstream-dynamic-reply.py 都不会先清空输出目录，生成器不再写的文件会一直留在那里并继续参与编译。Kotlin 规则是成员优先，所以这一次**语义上仍然解析到真实成员**，只是一条警告；但它意味着：在没有干净构建的机器上，将来如果真实成员丢失，旧扩展会默默顶上，“未解析引用”不会暴露。Windows 云端验证若从干净构建开始则不受影响。
   我只删掉了自己构建目录里的 `build/generated/dynamic-reply`（构建产物，非被跟踪文件）后重跑，第二次结果即上表：文件不再生成，警告消失，0 错误。要不要让这个任务（或所有 Exec 生成任务）在运行前清空输出目录，由 Root 决定；我没有改任何代码。

## JVM

| 范围 | 类 | 项 | 结果 |
|---|---|---|---|
| com.bilipai.desktop.ui 全包（含评论相关类） | 188 | 1350 | 1347 过，3 个已知 JS worker 类 initializationError |
| com.android.purebilibili.core.ui.*（含 .components、.animation、.renderer.material3） | 17 | 59 | 全过 |
| com.android.purebilibili.feature.video.ui.components.* | 5 | 58 | 全过（V046SubReplyDetailPresentationOriginalTest 32、V046CommentAvatarPushOriginalPolicyTest 13、V046CommentSortOriginalPolicyTest 6、DesktopV029ReplyLinkPolicyTest 4、DesktopV029CommentTimeTest 3） |
| 评论 22 类（89a0aee85 报告里的 23 类减去已删的 DesktopUserVipPinkTest） | 22/22 | 170 | 全过，与 bd7ff2324 的 170 项一致 |
| 其他评论相关（data.repository 2、feature.dynamic 2、feature.video.viewmodel 2、desktop.data 2、desktop.settings 4：都属于评论 22 类） | — | — | 已计入上一行 |

- 3 个失败类：DesktopOriginalPortraitMetadataPreparationTest、DesktopOriginalSponsorPresentationAssemblyTest、DesktopOriginalVideoCdnSelectionAssemblyTest，均为 `java.lang.IllegalArgumentException: JS worker resource path is invalid`（DesktopJsWorkerResources.kt:63，Linux 只有全零桩，没有真实 runtime/bin/javaw.exe）。89a0aee85、dd37c7abc、c985a0386、bd7ff2324 上完全相同，**不是回归，Linux 上无法验证**。两次增量构建结果相同。
- ui 包与 bd7ff2324 对比：类数（188）和项数（1350）相同，没有新增失败。#4 和 47f38a420 没有新增或修改 JVM 测试（diff bd7ff2324..47f38a420 只动了 Python 测试、生成器、归档和 Gradle 输入）。

## Python（desktop/tools/tests，带 pyshim 垫片，在 47f38a420 worktree，构建完成后重跑一次，两次一致）

| 文件 | 结果 |
|---|---|
| test_v046_comment_list_source.py（VERIFY 要求） | 26/26 OK（含断言新输出里没有 DesktopOriginalCommentMotionCompat.kt） |
| test_v046_sheet_motion_source.py（VERIFY 要求） | 6/6 OK |
| test_symbol_closure.py（VERIFY 要求） | 99 OK（2 跳过：`the producer path limit is a Windows rule`、`long-path forms are a Windows concern`），约 250 秒；含断言 v046_comment_list.py 里不再有 COMPAT_REDUCED_MOTION |
| test_v046_comment_thread_source.py（额外） | 10/10 OK |
| test_extract_upstream_window_modal_sheet.py（额外，47f38a420 改过） | 4/4 OK |

## 证据分级
- **Linux 通过**：compileKotlin/compileTestKotlin 增量 0 错误；`.reducedMotion` 两处只解析到 #4 的真实成员，构建目录清掉旧产物后无遮蔽警告、无未解析引用；JVM 222 类中 219 类 1533 项全过（ui 全包除 3 类、core.ui.* 17 类、feature.video.ui.components.* 5 类、评论 22 类）；Python 5 个文件全过。
- **只能在 Windows 上验证**：
  - 3 个 JS worker 类（需要真实 JS worker 目录哈希与运行时）。
  - Python 里 2 个 Windows 专用跳过项（生产者路径长度限制、长路径形式）；不带垫片的 Python 行为。
  - 被排除的 Windows 任务：原生诊断 DLL、JS worker 打包、Windows JDK。
  - 弹层动效、可控关闭、评论入场/级联动画在“系统减少动态效果”开关下的真实界面表现（Linux 只验证了编译和逻辑测试，没有在 Windows 上运行界面）。
  - Windows 增量构建是否也留着旧的 DesktopOriginalCommentMotionCompat.kt（见“重点 3”；Root 的 Windows 构建目录若在 47f38a420 之前生成过，应会看到同一条警告）。
- **Linux 通过不等于 Windows 云验证通过，更不等于真机可用**；我没有 Windows 和 GPU。

## 日志
/workspace/g-logs/v47f/（云电脑本地）：gradle-20261019-111809.log（第一次增量，带旧产物警告）、gradle-rerun-clean-dynreply.log（清掉 build/generated/dynamic-reply 后重跑）、py-*.log / py.summary（第一次）、py2-*.log / py2.summary（构建后重跑）。
