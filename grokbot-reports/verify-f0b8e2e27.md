# VERIFY desktop/integration-263-claude-main@f0b8e2e27（#6 第二部分：HomeRefreshUiPolicy 整文件归档 + 一行 exclude；符号门禁退役 resolveOldContentDividerContainerColor）

本报告只汇报，不改代码。指令来源：origin/desktop/grokbot-queue@49a2339ca（VERIFY.md 的 TODO 行；队列提交作者 tonysuper666-creator，已核对）；12:45 BST 复核时队列头为 e0ac12ad6，这一行仍是 TODO、内容未变。

## 环境
- Linux（Debian 13，x86_64，16 GB），Temurin JDK 21，Python 3.13.5，Gradle 9.5.0（仓库 gradlew）。
- 两个新 worktree，都检出 f0b8e2e27（detached，未改任何被跟踪文件）：
  - Gradle 用：**干净构建**（新 worktree，没有 desktop/build；compileKotlin、compileTestKotlin 都是 executed，只有 `:miuix5157:compileKotlinJvm` 1 个任务 FROM-CACHE）。
  - Python 用：另一个新 worktree，与 Gradle 并行，互不写对方目录。
- 做法同 grokbot/linux-bootstrap：make-stubs.sh 两个桩（原生诊断钉 8d256ff3…；JS worker 目录哈希全零桩，只为编译）、python-strip-winpath 垫片、`-Xmx6g`、`--no-daemon --max-workers=1`，排除 3 个 Windows 专用任务，跑测试时再排除 prepareJsWorkerResources。

## 结果总览

| 项 | 结果 |
|---|---|
| compileKotlin + compileTestKotlin（干净构建） | **BUILD SUCCESSFUL，9 分 3 秒，0 个 `e:` 错误**（555 条 `w:` 警告，未逐条比较） |
| unresolved reference / redeclaration / conflicting | 编译日志中 0 处 |
| JVM（`com.bilipai.desktop.ui.*`、`com.android.purebilibili.core.ui.*`、`data.model.VideoProgressDisplayPolicyTest`） | 206 个类，1413 项。第一次整范围运行 4 项失败：3 项已知 JS worker initializationError + 1 项间歇失败（见观察 1）；**第二次整范围运行（12:44 BST，无并行负载）1413 项只有那 3 项已知失败** |
| Python（带垫片） | 5 个文件全过（见下表） |

## 重点检查：重复声明与 exclude 后的引用
- 干净构建后，`desktop/build/generated/upstream/app/.../feature/home/` 下**没有** HomeRefreshUiPolicy.kt（exclude 生效，直接模式旧副本不再编译）。
- 整个 `desktop/build/generated` 与 `desktop/src` 里 HomeRefreshUiPolicy.kt 只有一份：`generated/home-page/com/android/purebilibili/feature/home/HomeRefreshUiPolicy.kt`。
- `resolveOldContentDividerContainerColor`（第 108 行）和 `OLD_CONTENT_DIVIDER_CONTAINER_ALPHA`（第 111 行）各只声明一次，都在这份文件里。
- 编译日志里没有 unresolved reference、redeclaration、conflicting overloads；说明 exclude 后原来依赖直接模式副本的调用方都解析到了 home-page 产物。

## Python（desktop/tools/tests，带 pyshim 垫片）

| 文件 | 结果 | 耗时 |
|---|---|---|
| test_v046_home_refresh_ui_policy_source.py | 8/8 OK | 56 秒 |
| test_v046_home_card_cascade_definers_source.py | 8/8 OK | 57 秒 |
| test_v043_home_recommendation_badges_source.py | 8/8 OK | 5 秒 |
| test_sync_upstream.py | 74/74 OK | 80 秒 |
| test_symbol_closure.py | 99 项 OK（2 跳过，都是 Windows 专用规则） | 252 秒 |

## JVM 失败明细
1. 已知（与前几轮相同，不是回归）：DesktopOriginalPortraitMetadataPreparationTest、DesktopOriginalSponsorPresentationAssemblyTest、DesktopOriginalVideoCdnSelectionAssemblyTest 的 initializationError（`JS worker resource path is invalid`，Linux 上 JS worker 是全零桩）。
2. 间歇：见观察 1。

## 观察
1. **间歇失败，归类为测试等待条件不够严（测试侧竞态），不是本次改动引起；没有改任何代码。**
   - 失败项：`DesktopOriginalVideoFallbackCauseViewModelTest.actualIoRecoveryRecapturesNewRequestAndRetiresPreviousReadonlyPermission`，第一次整范围运行（gradle-20261019-121934.log，12:19 BST 开始）里失败 1 次，日志只有 `AssertionFailedError at DesktopOriginalVideoFallbackCauseViewModelTest.kt:363`（363 是 `runBlocking` 所在行）。当时记下的断言是第 391 行 `assertEquals(before.info?.title, h.vm.detailState.value.info?.title)`：expected `<Fixture>`，实际 `null`。**说明：那次的原始 XML/HTML 报告已被后面的单类重跑覆盖，我现在无法再核对原文，这一条以当时的记录为准。** 那次运行时 Python test_symbol_closure（约 250 秒、CPU 密集）在并行跑。
   - 重跑到底覆盖了什么（如实说明）：之前的 rerun1/2/3（12:25、12:27、12:27 BST）**只跑了这一个类**（`--tests …DesktopOriginalVideoFallbackCauseViewModelTest`，每次 3 项，其中 rerun1 仍与 symbol_closure 重叠），不是整范围。所以我补了：
     - 整范围重跑 1 次（gradle-20261019-124405.log，同样的三个 `--tests` 范围、`--rerun`，无并行负载）：206 类 1413 项，只有 3 个已知 JS worker 类失败，该类 3/3 通过。
     - 6 个忙循环 Python 进程占满 6/8 核的情况下单类重跑 6 次（stress/，12:46–12:48 BST）：6/6 通过。
     - 合计：该测试执行 10 次，失败 1 次（第一次、有并行负载的整范围运行），没能复现第二次。
   - 原因分析（看的是生成后的产品代码，未修改）：
     - `VideoPlaybackViewModel.kt`（build/generated/original-video-full-owner/…/viewmodel/）第 3572–3576 行，每个新请求先把 `_detailState` 重置成 `VideoDetailUiState(requestToken = 新令牌)`，此时 `info = null`、`relatedLoadState = LOADING`（VideoDetailUiState.kt 第 11–13 行的默认值）。
     - 第 3616–3619 行：对 BV 号入口，相关列表 `startDesktopMetadataRelated` 在 `getVideoInfoOnly` **之前**并行启动（注释写明“related must never delay videoInfoDeferred.await()”），info 要等第 3630 行 `publishVideoDetailInfo` 才写入；相关列表成功后在第 1007–1009 行只写 `related` 和 `relatedLoadState = READY`。恢复请求时相关列表还可能直接命中 `scope.cachedRelated(bvid)`（第 964 行）。所以“新令牌 + READY 而 info 仍为 null”是产品**设计上允许**的瞬时状态。
     - 测试第 387–388 行的 `h.until { requestToken == fresh && relatedLoadState == READY }` 恰好可能停在这个瞬时状态，紧接着第 391 行读 info 就是 null。同一个测试文件里的 `related()` 辅助函数（第 291–293 行）专门加了 `it.info != null`，说明作者知道两者先后不定；第 387 行没有加。
     - 所以我判断是测试的等待条件比同文件的辅助函数宽，不是 ViewModel 的先后缺陷。但只复现了 1/10，且原始失败信息已经无法核对，**结论置信度中等**，请 Root 判断。
   - 建议的最小测试改动（仅供 Root 参考，我没有提交，也没有放进可合并分支；断言数量和严格度不变，只把等待条件收紧到与 `related()` 一致，仍用产品的 detailState 状态流）：
     ```diff
     -            h.until { h.vm.detailState.value.requestToken == freshCapture.state.currentLoadRequestToken &&
     -                h.vm.detailState.value.relatedLoadState == VideoPlaybackUiState.RelatedLoadState.READY }
     +            h.until { h.vm.detailState.value.requestToken == freshCapture.state.currentLoadRequestToken &&
     +                h.vm.detailState.value.relatedLoadState == VideoPlaybackUiState.RelatedLoadState.READY &&
     +                h.vm.detailState.value.info != null }
     ```
     风险：如果 Root 认为“READY 先于 info”本身就不该出现，那么这个改动会把一个产品先后问题藏起来；那种情况下应改产品而不是测试。
   - 与本次提交的关系：47f38a420..f0b8e2e27 没有改 `desktop/src` 下任何文件，只改了 home 刷新策略的归档/生成器/测试；bd7ff2324、47f38a420 两轮整包运行该类都通过。
2. 本轮未出现新的符号门禁过期项（test_symbol_closure 99 项全过）。

## 证据类别
- Linux 编译：compileKotlin/compileTestKotlin 干净构建 0 错误。
- Python：上表 5 个文件（Linux + 垫片）。
- JVM：Linux 上上述三组测试，结果如上（第二次整范围运行只剩 3 个已知 JS worker 类）。
- 只能在 Windows 上验证：3 个 JS worker 类；Python 里 2 个 Windows 专用跳过项；Windows 上该间歇测试的表现。
- 未验证：Windows 上的编译与测试、真实 JS worker、任何界面/NVIDIA/音频/安装包行为。

## 日志
/workspace/g-logs/f0b8/（云电脑本地）：gradle-20261019-120647.log（干净编译）、gradle-20261019-121934.log（第一次整范围 JVM）、rerun1–3/（单类重跑）、rerun-full/（第二次整范围，含 206 个 XML）、stress/（负载下单类 6 次，含 run1–6.xml）、py-*.log。
