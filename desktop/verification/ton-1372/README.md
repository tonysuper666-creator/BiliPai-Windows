TON-1372 局部修复，基于候选 4 `20fd5959affb51b72bcb67bb75cc0bcb9d64af03`。

TON-1343 把选集适配插入已有历史 producer，导致 default-quality 无法还原固定 stage，
v046 测试移除 speed stage 后也无法满足选集输入 pin。本补丁把该适配移到现有
`original-bangumi-player` transition 层，恢复 producer 的历史字节。没有调整 pin、
测试、gate 基线、构建登记或 Root。现有构建自动发现这两份补丁。

下载本卡证据包并解压后，在仓库根目录执行：

```sh
python3 -B desktop/verification/ton-1372/compare_generated.py --evidence /path/to/evidence
python3 -B desktop/verification/ton-1372/verify_transition.py --repo . --evidence /path/to/evidence
git diff --check 20fd5959affb51b72bcb67bb75cc0bcb9d64af03
```

`verify_transition.py` 需要本机已有 Kotlin 2.0.21 compiler 缓存。它编译并执行构建脚本中
`applyUnifiedPatch` 函数的原字节，对真实 producer CLI 改前/改后输出执行完整补丁、
逆变换和重放，并验证坏锚点与缺输入被拒绝。resume 正文与改前逐字节一致；VM 保留
原始空白行并修复旧替换拼接留下的行尾空格，每个非空行及其缩进均与改前一致，完整
格式 diff 附在证据中。逆变换、重放分别要求完整字节恒等。没有执行整个 Gradle task，
Gradle 添加的 provenance 包装未在这里运行。

`compare_generated.py` 只读取两次完整原始日志。按 task|class|name 比较方法、class setup
及失败子用例；方法数量须与 unittest 自身的 Ran 汇总一致。它检查新增 failure/error/skip、
缺失方法、discovery 数量和子用例变化，不改变原 runner 的退出码。

未解决的 hub 原断言扫描 `desktop/src`，统计到生产构造调用 1 处及 PR141 测试夹具 1 处。
原卡禁止修改测试，本补丁保留该失败，不通过更名、格式绕过或删除夹具消红。

本补丁没有完成整份 v0.7.8 VM/hub 移植。现有 legacy→v034→v046 生产链仍保留，
证据包另附真实 v0.7.8 VM/hub 及相对 v046 的完整 diff。v0.7.8 的收藏/稍后看状态、
三连、videoshot、hub pager 等增量不能从本次字节恒等证明推导为已移植。
全门禁、JVM 应用编译、Windows/GUI、安装包均须按证据中的实际边界验收。
