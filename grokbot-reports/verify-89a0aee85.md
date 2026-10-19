# VERIFY desktop/integration-263@89a0aee85（只汇报，不改代码）

- 对象：`89a0aee85 Merge the Veyra re-pin and the reviewed test-currency commits into the 263 integration branch`（与 origin/desktop/integration-263 当前头一致）。
- 环境：Debian 13 x86_64，Temurin 21，Python 3.13.5，Gradle 9.5.0；命令行即 grokbot/linux-bootstrap 的 `bootstrap/gradle.sh`（-Xmx6g -Xss16m，单 worker，in-process，`-x prepareNativeDiagnosticShare -x fetchJsWorkerJdk -x prepareJsWorker`，测试再加 `-x prepareJsWorkerResources`）。**未排除** prepareVeyraRuntimeVerifier。
- 时间：2026-10-19 02:07–02:35 UTC。

## 结果

| 项 | 命令 | 结果 | 耗时 |
|---|---|---|---|
| 编译（缓存） | compileKotlin compileJava compileTestKotlin compileTestJava | BUILD SUCCESSFUL（两个 Kotlin 任务 FROM-CACHE：输入与此前已编译过的树一致） | 1m07s |
| 编译（无缓存，权威） | `rm -rf desktop/build` + make-stubs + `compileKotlin compileTestKotlin --no-build-cache` | BUILD SUCCESSFUL，0 个 `e:`；警告 main 457 / test 98（main 与 261 修复线 457 相同） | 6m22s |
| test_symbol_closure.py（Root 的符号闭包检查） | `python3 -B -m unittest discover -s desktop/tools/tests -p test_symbol_closure.py -v`（带 `\\?\` 垫片） | **Ran 98，OK，skipped=2**（两个都是 ScratchPathTests 的 Windows 长路径规则：“the producer path limit is a Windows rule”、“long-path forms are a Windows concern”） | 3m43s，峰值 RSS 272 MB |
| nvidia 14 个测试类 | `test --tests …`（列表见下） | 全过 | 合计见下 |
| 评论 23 个测试类 | 同上，同一次运行 | 全过 | |
| 37 类合计 | | **37/37 类，566 个测试，0 失败，0 跳过** | ~4 分钟 |
| Python desktop/tools/nvidia 全部 | `unittest discover -s desktop/tools/nvidia -p "test_nvidia_*.py"` | Ran 323，OK（不带垫片、带垫片各一次，结果相同） | 1 s |

### 关于符号闭包检查的一个环境发现（重要）

第一次运行：98 个里 **10 个被跳过**，其中 `CurrentIntegrationTree` 整类 8 个，原因
`the pinned upstream objects (v0.2.5 and v0.4.6) are not in this clone object store`。
本克隆不是浅克隆，v0.2.5 提交 `79e8fa301` 在，但 v0.4.6 目标提交 `d6bf8b165`（`gate.TARGET_COMMIT`）不在任何已拉取的分支历史里。
从**本仓库 origin** 按 SHA 拉取成功（`git fetch origin d6bf8b1655ae910999860bf13e65c8fcea5b345b`，没碰任何其它仓库）。之后重跑整份文件：CurrentIntegrationTree 8/8 全过，总计只剩上面 2 个 Windows 跳过。
**提醒 Root：** 这个门禁在“只拉了 desktop/* 分支”的克隆里会静默跳过最重的一类且仍报 OK。是否要让它在缺对象时失败（而不是跳过），由 Root 决定；我没有改任何东西。

## 测试类清单

nvidia 14：com.bilipai.desktop.nvidia.DesktopNvidia{GpuCapability,TierProfile,Activation,StatusText,AdaptiveGuard,HealthStore,MpvScalerProfile,CapabilityMonitor}Test；
com.bilipai.desktop.player.{DesktopNvidiaVideoAutoPolicyTest,DesktopVideoEnhancementSessionTest,PlayerNvidiaVideoTest,PlayerNvidiaActorWithdrawalTest,DesktopWindowsDxgiAdaptersTest}；
com.bilipai.desktop.plugins.DesktopVideoEnhancementConfigurationTest。

评论 23：与任务 3 相同（CommentReadRouteMemoTest、DesktopCommentReadRouteConsumptionTest、DynamicCommentLoadPolicyTest、DynamicCommentPreviewPolicyTest、DesktopV029CommentTimeTest、DesktopV029ReplyLinkPolicyTest、DesktopVideoCommentCacheConsumptionTest、VideoCommentLoadPolicyTest、DesktopDynamicCommentCacheConsumptionTest、DesktopReplyMedalColorTest、DesktopCommentAvatarSettingsTest、DesktopCommentFraudActualAdmissionTest、DesktopCommentFraudHistoryFilesTest、DesktopCommentFraudHistoryTest、DesktopBgmCommentLinkRootNavigationTest、DesktopChargedCommentProtocolTest、DesktopOriginalVideoComposerCommentsTest、DesktopUserVipPinkTest、DesktopWindowsCommentComposerLayoutTest、DesktopWindowsCommentPreviewRequestTest、DesktopWindowsCommentSearchBindingTest、DesktopWindowsVideoCommentActionsTest、WindowsCommentComposerReplayTest）。

## 证据分类

| 类别 | 内容 |
|---|---|
| Linux 编译 | 已验证：无缓存 compileKotlin + compileTestKotlin 通过，0 错误 |
| Python | 已验证：符号闭包 98（2 个 Windows 跳过），nvidia 323 |
| JVM（Linux） | 已验证：37 类 566 测试全过 |
| 未验证 | Windows 云验证、真机、UI、NVIDIA 硬件、音频、安装器；两个桩文件（诊断 DLL 哈希取仓库 pin，JS worker 全零只为编译）未在 Linux 上验证真实值 |

没有修改任何代码。
