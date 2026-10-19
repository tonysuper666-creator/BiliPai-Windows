# 任务队列（Root 维护；你只读，不要改本分支）

规则见 STANDING-ORDERS.md。状态值：TODO / IN-PROGRESS / DONE / BLOCKED。你的实际状态写在 grokbot/status 分支的 STATUS.md，不要改本文件。Root 会按你的汇报更新这里。

> **Root 2026-10-19（用户明确要求“少让他干点活”）：你现在只做 VERIFY.md 里的编译与测试验证，以及我另外点名的只读审计。T9 和 T13 的所有切片开发工作一律停止（含 #4 字形一半、#3 调色板、#6 的 VideoCardAdaptiveTintPolicy、#46、#35）：这些由 Root 接手，已经在做的未提交内容不要再推进，已经推到 grokbot/slices-batch1 的提交我会自己看。没有 VERIFY 行时就空闲检查，不要自己找活。**

| 编号 | 状态 | 内容 | 细节文件 | 依赖 |
|---|---|---|---|---|
| T0 | DONE（grokbot/linux-bootstrap @ 7877e9a57；安装脚本尚未在刚重置的机器上实跑，只检查了语法） | 把你的 Linux 构建环境存进仓库：新分支 grokbot/linux-bootstrap，内含 bootstrap/ 目录：路径垫片（python 包装器和 sitecustomize）、两个桩文件的生成脚本（诊断 DLL 哈希桩和 JS worker 全零桩，注明全零只为编译）、完整的 gradle 命令行（-Xmx6g、-Xss16m 等）、装 JDK/Python 的命令、一个 README 说明“重置后怎么在 15 分钟内恢复”。不含任何令牌。 | tasks/T0.md | 无 |
| T6 | DONE（grokbot/r1-test-currency @ 3d0deb5cf 已推送；Root 已审并采用其中 6 个测试提交） | 任务 6（6a 验证 R1，6b 过时测试，6c 十二项分类，6d 全量复扫，另加大屏 UI 备份的只读检查）。基于 origin/desktop/r1-veyra-pins。 | tasks/T6.md | 无 |
| T8 | DONE（T8a、T8b 已由 Root 审并摘入 integration-263@dd37c7abc） | 两项产品缺陷的修复补丁，各用隔离分支（grokbot/fix-bas-publication、grokbot/fix-early-video-detail），验证后由 Root 决定是否合并。 | tasks/T8.md | T6 |
| T9 | CANCELLED（Root 接手剩余切片） | 路线图切片批次 1：实现 + Linux 编译/测试验证（#12、#14、#25、#3、#13+#35、#46、#2），累积在 grokbot/slices-batch1。 | tasks/T9.md | T8；基础分支 origin/desktop/integration-263 最新头（现为 dd37c7abc） |
| T13 | CANCELLED（Root 接手剩余切片，不再做 #4 字形一半等） | 路线图切片批次 2（Root 再次收紧分工后只剩次要的：#10 审计、#5、#6、#4；其余全部归 Root），接着 T9 做，累积在 grokbot/slices-batch1。 | tasks/T13.md | T9 |
| T12 | DONE（grokbot/t12-python-test-currency @ 24753a5a5；Root 已审，4 个测试提交已摘入 desktop/integration-263-claude-main@c11c5a98b，Windows 上 4 个文件全过） | 应用 T11 的三个测试补丁（Root 附条件）+ 分析第 4 个旧失败 v031_repost_coin；分支 grokbot/t12-python-test-currency。 | tasks/T12.md | 无（可与 T9 交替做） |
| T10 | DONE（Root 已审并摘入 integration-263：测试修复提交 578444aeb） | test_v029_failure_recovery 两项旧失败的只读分析（根因 + 最小修复补丁，不提交到可合并分支）。 | tasks/T10.md | 无 |
| T11 | DONE（grokbot/t11-old-python-failures @ c9bf8bbca；Root 的答复见 DECISIONS.md，补丁由 T12 应用） | 三个旧 Python 测试失败的只读分析（up_danmaku、brand_success_mount、comment_renderer），方法同 T10。 | tasks/T11.md | 无 |
| T7 | DONE（grokbot/T7-report；全部通过，test_rtx_hdr_color 在 R1 之后真实 7/7） | 在 T6 通过后：对 origin/grokbot/r1-test-currency 的头（含 R1 修复和测试更新）做一次“集成体检”：compileKotlin/compileTestKotlin，NVIDIA 14 个测试类，评论相关 23 个测试类，desktop/tools/nvidia 的 Python 检查，以及 tasks/T7.md 里列出的 Python 测试。只汇报，不改代码。 | tasks/T7.md | T6 |

VERIFY.md 里的条目优先于 TODO 任务（见 STANDING-ORDERS 第 1 节）。
