# VERIFY desktop/integration-263-claude-main@bd7ff2324（第二轮采用：#3 identity badges、#2 第三方声明、#5 core-data 政策、#6 home 卡片定义 + T12 测试补丁 + #17 门控 + 符号门禁退役）

本报告只汇报，不改代码。指令来源：origin/desktop/grokbot-queue@9238072d9（VERIFY.md 第 4 行；提交作者 tonysuper666-creator，已核对）。

## 环境
- Linux（Debian 13，x86_64，16 GB），Temurin JDK 21.0.12.1，Python 3.13.5，Gradle 9.5.0（仓库 gradlew）。
- 两个独立 worktree，都检出 bd7ff2324（detached，未改任何被跟踪文件）：
  - Gradle 用：从**干净构建**开始（新 worktree，没有 desktop/build，无 Gradle 构建缓存命中 compileKotlin/compileTestKotlin：两者都是 executed，只有 miuix 子模块 1 个任务 FROM-CACHE）。
  - Python 用：另一个新 worktree，与 Gradle 并行，互不写对方目录。
- 使用 grokbot/linux-bootstrap 的做法：make-stubs.sh 两个桩（原生诊断钉 8d256ff3…，取仓库已审核 pin；JS worker 目录哈希**全零桩，只为编译**）、python-strip-winpath 垫片、`-Xmx6g -Xss16m`、`--no-daemon --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process`，排除 3 个 Windows 专用任务（prepareNativeDiagnosticShare、fetchJsWorkerJdk、prepareJsWorker），跑测试时再排除 prepareJsWorkerResources。
- 一次 Gradle：`compileKotlin compileTestKotlin test -x prepareJsWorkerResources --continue --rerun --tests 'com.bilipai.desktop.ui.*' --tests com.android.purebilibili.data.model.VideoProgressDisplayPolicyTest --tests <NVIDIA 14 类与评论 23 类中不在 ui 包的 28 个类>`，总耗时 8 分 19 秒。

## 结果总览

| 项 | 结果 |
|---|---|
| compileKotlin（干净构建） | **成功，0 个 `e:` 错误** |
| compileTestKotlin | **成功，0 个 `e:` 错误** |
| JVM 合计 | 217 个类，1825 项，3 项失败（都是已知 JS worker initializationError），0 跳过 |
| Python（带垫片） | 12 个文件全过（见下表；identity badges 需要先有 Gradle 生成物，见“观察 1”） |

## Python（desktop/tools/tests，带 pyshim 垫片）

| 文件 | 结果 | 不带垫片 |
|---|---|---|
| test_sync_upstream.py | 74/74 OK | 74/74 OK（不需要垫片） |
| test_validate_source_cohort.py | 25 项 OK（1 跳过：`Real Windows batch boundary`，只能在 Windows 上跑） | 同左 |
| test_package_source_qualification.py | 14/14 OK | 同左 |
| test_symbol_closure.py | 99 项 OK（2 跳过：`the producer path limit is a Windows rule`、`long-path forms are a Windows concern`），235 秒 | 未跑（之前各轮已确认要垫片） |
| test_v046_identity_badges_source.py | 7/7 OK（在 Gradle 跑完的 worktree 里）；在没有 desktop/build 的 worktree 里 6 OK + 1 ERROR（见观察 1） | 失败（`\\?\` 长路径前缀） |
| test_third_party_notices_source.py | 5/5 OK | 5/5 OK（不需要垫片） |
| test_v046_core_data_policies_source.py | 8/8 OK（构建前、构建后各一次） | 7 OK + 1 失败（`\\?\` 前缀） |
| test_v046_home_card_cascade_definers_source.py | 8/8 OK（构建前、构建后各一次） | 导入阶段失败（`\\?\` 前缀） |
| T12：test_extract_upstream_up_danmaku.py | 10/10 OK | 10/10 OK |
| T12：test_extract_upstream_brand_success_mount.py | 3/3 OK | 3 ERROR（`\\?\` 前缀） |
| T12：test_extract_upstream_comment_renderer.py | 5/5 OK | 5/5 OK |
| T12：test_extract_upstream_v031_repost_coin.py | OK | 导入阶段失败（`\\?\` 前缀） |

不带垫片的失败全部是生成器无条件加 Windows 长路径前缀 `\\?\` 的已知 Linux 问题，不是回归，也不是测试本身的问题。

## JVM

| 范围 | 类 | 项 | 结果 |
|---|---|---|---|
| com.bilipai.desktop.ui 全包 | 188 | 1350 | 1347 过，3 个已知 JS worker 类 initializationError |
| com.android.purebilibili.data.model.VideoProgressDisplayPolicyTest（#5 新归档带来的上游测试） | 1 | 4 | 4/4 过 |
| NVIDIA 14 类 | 14/14 | 395 | 全过（与 89a0aee85 上的 395 项一致） |
| 评论 23 类 | 22（见下） | 170 | 全过 |

- 3 个失败类：DesktopOriginalVideoCdnSelectionAssemblyTest、DesktopOriginalPortraitMetadataPreparationTest、DesktopOriginalSponsorPresentationAssemblyTest，均为 `JS worker resource path is invalid`（Linux 只有全零桩）。89a0aee85、dd37c7abc、c985a0386 上完全相同，**不是回归，Linux 上无法验证**。
- 评论 23 类里的 DesktopUserVipPinkTest 已由 de3967e68 删除（#3 退役 shim，DesktopUserVipPink.kt 一起删），所以只剩 22 类可跑；其余 22 类项数与 89a0aee85 完全一致（171 − 1 = 170）。
- ui 包与 dd37c7abc 对比：类数相同（188）；少了 DesktopUserVipPinkTest（1 项），多了 c985a0386 的 DesktopOriginalVideoRelatedNetworkObservationTest（4 项）；1347 − 1 + 4 = 1350。**其余共同的类项数没有任何变化**，没有新增失败。

## 观察（供 Root 判断，我没有改任何东西）
1. **test_v046_identity_badges_source 依赖 Gradle 生成物**：`test_pink_definers_are_unique_and_every_emitted_call_resolves_to_the_components_package` 会调用 extract-upstream-space-pages.py 的 generate，它读取 `desktop/build/generated/space-overview/.../DesktopOriginalSpaceOverview.kt`。在没跑过 Gradle 的干净 checkout 里这一项 FileNotFoundError；跑过 prepareUpstreamSources 之后 7/7 通过。Windows 上若测试总是在构建之后跑就不会暴露。这是测试的前置条件问题，不是产品缺陷；要不要让测试自己准备这份输入或给出明确的跳过/报错信息，由 Root 决定。
2. **VIP 粉色两值的 JVM 运行时断言没有了**：被删的 DesktopUserVipPinkTest 曾在 JVM 上断言 `resolveDesktopUserVipPink(true/false)` 等于 0xFFD44E7D / 0xFFFF6699。现在这两值由 test_v046_identity_badges_source 第 137 行的源码钉住（静态字符串断言 `if (isDark) Color(0xFFD44E7D) else Color(0xFFFF6699)`），并断言旧 shim 文件和旧测试不存在（第 291–293 行）。值本身仍被钉住，只是从运行时断言变成了源码断言；如需保留运行时覆盖，可以加一个调用 `resolveUserVipPink` 的 JVM 测试。只是提示，不是失败。

## 证据分级
- **Linux 通过**：compileKotlin/compileTestKotlin 干净构建 0 错误；Python 12 个文件（带垫片）全过；JVM 217 类中 214 类 1822 项全过（ui 全包除 3 类、VideoProgressDisplayPolicyTest、NVIDIA 14 类、评论 22 类）。
- **只能在 Windows 上验证**：
  - 3 个 JS worker 类（需要真实 JS worker 目录哈希与运行时，Linux 只有全零桩）。
  - Python 里 3 个 Windows 专用跳过项（cmd 批处理边界、生产者路径长度限制、长路径形式）。
  - 不带垫片的 Python 行为（Linux 需要垫片去掉 `\\?\`；Windows 上不需要）。
  - 被排除的 Windows 任务：原生诊断 DLL、JS worker 打包、Windows JDK；NVIDIA 类在 Linux 上只验证逻辑，没有真实 GPU/DXGI/RTX。
  - 第三方声明在安装包里的实际打包与展示、新卡片/徽章的界面效果。
- **Linux 通过不等于 Windows 云验证通过，更不等于真机可用**；我没有 Windows 和 GPU。

## 日志
/workspace/g-logs/vbd7/（云电脑本地）：gradle-20261019-103216.log、py-shim-*.log、py-plain-*.log、py-shim-postbuild-*.log。
