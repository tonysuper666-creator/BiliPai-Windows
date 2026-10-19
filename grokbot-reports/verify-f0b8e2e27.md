# VERIFY desktop/integration-263-claude-main@f0b8e2e27（#6 第二部分：HomeRefreshUiPolicy 整文件归档 + 一行 exclude；符号门禁退役 resolveOldContentDividerContainerColor）

本报告只汇报，不改代码。指令来源：origin/desktop/grokbot-queue@49a2339ca（VERIFY.md 的 TODO 行；队列提交作者 tonysuper666-creator，已核对）。

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
| JVM（`com.bilipai.desktop.ui.*`、`com.android.purebilibili.core.ui.*`、`data.model.VideoProgressDisplayPolicyTest`） | 206 个类，1413 项，4 项失败：3 项是已知 JS worker initializationError；1 项是间歇性失败（见观察 1），单独重跑 3/3 通过 |
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
1. **间歇失败，非本次改动引起**：`DesktopOriginalVideoFallbackCauseViewModelTest.actualIoRecoveryRecapturesNewRequestAndRetiresPreviousReadonlyPermission` 在整包运行中失败一次（第 391 行 `assertEquals(before.info?.title, h.vm.detailState.value.info?.title)`：expected `<Fixture>`，实际 `null`）。当时 Python test_symbol_closure 在并行跑，CPU 有负载。单独重跑该类 3 次，3/3 通过（每次 3 项）。47f38a420..f0b8e2e27 之间 `desktop/src` 没有任何改动，bd7ff2324、47f38a420 两轮该类都通过。推测：第 387 行的 `h.until { requestToken == … && relatedLoadState == READY }` 只等 related 就绪，没等 info 重新填入，负载下 related 可能先于 info 到达（c985a0386 以后，相关列表不再被带宽估算拖住，顺序更容易反过来）。是测试等待条件不够严还是 ViewModel 真有先后问题，需要 Root 判断；我没有改测试。
2. 本轮未出现新的符号门禁过期项（test_symbol_closure 99 项全过）。

## 证据类别
- Linux 编译：compileKotlin/compileTestKotlin 干净构建 0 错误。
- Python：上表 5 个文件（Linux + 垫片）。
- JVM：Linux 上上述三组测试，结果如上。
- 未验证：Windows 上的编译与测试、真实 JS worker、任何界面/NVIDIA/音频/安装包行为。
