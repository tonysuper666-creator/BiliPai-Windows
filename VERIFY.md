# 待验证的提交（Root 维护）

规则：对下面每一行，在对应分支上检出指定提交，运行 compileKotlin、compileTestKotlin 和“测试范围”一栏的内容，把结果推到 grokbot/verify-<短哈希> 分支（报告放 grokbot-reports/verify-<短哈希>.md）。已经验证过的行由 Root 标记为 VERIFIED。只汇报，不改代码。

| 状态 | 分支@提交 | 测试范围 | 备注 |
|---|---|---|---|
| VERIFIED（grokbot/verify-89a0aee85：编译 0 错误；NVIDIA+评论 37 类 566 项全过；nvidia Python 323 全过） | desktop/integration-263@89a0aee85 | compileKotlin/compileTestKotlin；desktop/tools/tests/test_symbol_closure.py（约 6 分钟，Root 的符号闭包检查，期望全过）；nvidia 14 个测试类；评论 23 个测试类；Python：desktop/tools/nvidia 全部 | 这是集成分支新头，含 Root 写的符号闭包检查（约 5400 行 Python，从未在 Linux 上跑过） |
| VERIFIED（grokbot/verify-dd37c7abc：编译通过；danmaku+ui+37 类只剩 3 个已知 JS worker 失败） | desktop/integration-263@dd37c7abc | compileKotlin/compileTestKotlin；danmaku 包全部测试类；DesktopWindowsEarlyVideoDetailTest 及 com.bilipai.desktop.ui 全包；desktop/tools/tests/test_early_video_detail_boundary.py、test_symbol_closure.py | 在上一行基础上多了 T8a（BAS 清理）、T8b（早期视频详情边界）两个已由你验证过的修复，Root 已审并摘入；这一行确认合并后无冲突、无新增失败 |
| VERIFIED（grokbot/verify-c985a0386：编译 0 错误；4 个类 + 额外 9 个类共 99 项通过；3 个 JS worker 类 Linux 上无法运行，静态审读无顺序依赖，以 Windows 云端为准） | desktop/integration-263-claude-main@c985a0386 | compileKotlin/compileTestKotlin；新测试类 DesktopOriginalVideoRelatedNetworkObservationTest；受“无带宽估计”语义影响的类：DesktopOriginalVideoMetadataRelatedViewModelTest、DesktopWindowsRelatedVideosRegressionTest、DesktopOriginalVideoCdnSelectionAssemblyTest、DesktopOriginalPortraitMetadataPreparationTest、DesktopOriginalSponsorPresentationAssemblyTest、DesktopBangumiDefaultQualityTest | Root 自己的切片 #17 第一步：Windows 没有下行带宽接口，相关列表门控此前把“无估计”当受限；现在无估计在非移动网络且有互联网时不受限（与上游一致）。重点看这些夹具里有没有依赖“相关列表要等播放就绪才发请求”的断言 |
