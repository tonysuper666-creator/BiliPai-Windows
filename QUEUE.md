# 任务队列（Root 维护；你只读，不要改本分支）

规则见 STANDING-ORDERS.md。状态值：TODO / IN-PROGRESS / DONE / BLOCKED。你的实际状态写在 grokbot/status 分支的 STATUS.md，不要改本文件。Root 会按你的汇报更新这里。

| 编号 | 状态 | 内容 | 细节文件 | 依赖 |
|---|---|---|---|---|
| T0 | TODO | 把你的 Linux 构建环境存进仓库：新分支 grokbot/linux-bootstrap，内含 bootstrap/ 目录：路径垫片（python 包装器和 sitecustomize）、两个桩文件的生成脚本（诊断 DLL 哈希桩和 JS worker 全零桩，注明全零只为编译）、完整的 gradle 命令行（-Xmx6g、-Xss16m 等）、装 JDK/Python 的命令、一个 README 说明“重置后怎么在 15 分钟内恢复”。不含任何令牌。 | tasks/T0.md | 无 |
| T6 | DONE（grokbot/r1-test-currency @ 3d0deb5cf 已推送；Root 已审并采用其中 6 个测试提交） | 任务 6（6a 验证 R1，6b 过时测试，6c 十二项分类，6d 全量复扫，另加大屏 UI 备份的只读检查）。基于 origin/desktop/r1-veyra-pins。 | tasks/T6.md | 无 |
| T8 | TODO | 两项产品缺陷的修复补丁，各用隔离分支（grokbot/fix-bas-publication、grokbot/fix-early-video-detail），验证后由 Root 决定是否合并。 | tasks/T8.md | T6 |
| T7 | TODO | 在 T6 通过后：对 origin/grokbot/r1-test-currency 的头（含 R1 修复和测试更新）做一次“集成体检”：compileKotlin/compileTestKotlin，NVIDIA 14 个测试类，评论相关 23 个测试类，desktop/tools/nvidia 的 Python 检查，以及 tasks/T7.md 里列出的 Python 测试。只汇报，不改代码。 | tasks/T7.md | T6 |

VERIFY.md 里的条目优先于 TODO 任务（见 STANDING-ORDERS 第 1 节）。
