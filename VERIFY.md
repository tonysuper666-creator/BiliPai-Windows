# 待验证的提交（Root 维护）

规则：对下面每一行，在对应分支上检出指定提交，运行 compileKotlin、compileTestKotlin 和“测试范围”一栏的内容，把结果推到 grokbot/verify-<短哈希> 分支（报告放 grokbot-reports/verify-<短哈希>.md）。已经验证过的行由 Root 标记为 VERIFIED。只汇报，不改代码。

| 状态 | 分支@提交 | 测试范围 | 备注 |
|---|---|---|---|
| TODO | desktop/integration-263@89a0aee85 | compileKotlin/compileTestKotlin；desktop/tools/tests/test_symbol_closure.py（约 6 分钟，Root 的符号闭包检查，期望全过）；nvidia 14 个测试类；评论 23 个测试类；Python：desktop/tools/nvidia 全部 | 这是集成分支新头，含 Root 写的符号闭包检查（约 5400 行 Python，从未在 Linux 上跑过） |
| TODO（上一行验证完成后做） | desktop/integration-263@dd37c7abc | compileKotlin/compileTestKotlin；danmaku 包全部测试类；DesktopWindowsEarlyVideoDetailTest 及 com.bilipai.desktop.ui 全包；desktop/tools/tests/test_early_video_detail_boundary.py、test_symbol_closure.py | 在上一行基础上多了 T8a（BAS 清理）、T8b（早期视频详情边界）两个已由你验证过的修复，Root 已审并摘入；这一行确认合并后无冲突、无新增失败 |
