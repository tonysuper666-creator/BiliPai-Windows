TON-1344 动态关注栏偏好桥接

固定落点：`desktop/integration-263-claude-main` 的 `761af1ac927898804701a31a1103d1d110d8eb36`。本卡仅定向消费 U `6f0696eb1389728b5a090125d32c1aae470f72f1` 的 `DynamicSettingsStore` 新键、getter、setter 和 `DynamicUpListVisibilityPreference` 完整开关调用，不调整全局上游版本。

`desktop/upstream-slices/dynamic-up-list/manifest.json` 记录完整原文的 Git blob 和 SHA-256；原文逐字节保留。已有 tabs producer 调用 `dynamic_up_list.py` 生成桥接。getter/setter 完整原始函数体沿用；控件仅替换标题目录查询、语义图标入口和 Android 协程写回入口。`dynamic-up-list-proof.json` 给出实际输出 hash、选择文本和可逆替换，`--verify` 读取真实文件并反向还原控件，输出和 receipt 一起篡改也不能通过。

运行命令（在仓库根目录）：

```sh
python3 -B desktop/tools/tests/test_dynamic_up_list_source.py -v
python3 desktop/tools/extract-upstream-dynamic-tabs.py --repo .legacy-root --output <evidence>/tabs-cli
python3 desktop/tools/dynamic_up_list.py --repo .legacy-root --output <evidence>/tabs-cli --verify
git diff --check 761af1ac927898804701a31a1103d1d110d8eb36
<当前共享 heavy 路径> ./gradlew -p desktop :test --tests 'com.bilipai.desktop.ui.DesktopDynamicUpListVisibilityTest' --console=plain
```

`.legacy-root` 是现有 Gradle settings 自动物化的固定原文读点；不要把当前 merged app 当成旧 producer 的原文。Python 旧源码测试用已有 `legacy_gate_workspace` 临时读点，桌面区来自本分支，其他原文来自既有 legacy pin。

旧红/新绿：在固定 base 的独立工作树只复制本分支的 `DesktopDynamicUpListVisibilityTest.kt`，不复制实现。两树运行完全相同的 Gradle 命令，不加 `-x`，保留 sponsor、image loader、design source、app-core source finalizer。之后运行：

```sh
python3 desktop/verification/ton-1344/compare_results.py <baseline-repo> <head-repo> <evidence>
```

此脚本从真实 XML 提取所有任务的失败身份、原样保存 XML，再计算清除/新增/共同失败。命令总 exit 和配置/编译等 XML 外失败须同时查原始日志，不能将测试绿等同于完整构建绿。

行为测试使用实际 Root store、共享 StateFlow、账号 session/cache 与 ImageComposeScene 中的生产 Host/设置控件：默认开启、旧键不生效、点击写盘、运行时开关、888/1320 两种宽度、四个分类、隐藏回收侧栏空间、保留作者与数据、全局开关跨账号与冷重建。合成账号和临时目录，不请求真实服务、不启动桌面 GUI。

Root 交接：`root-inputs.patch` 提出四个文件的精确变更：现有 `extractUpstreamDynamicTabs` task 增加 helper 与原文目录两项 inputs；`compatibility-source-inputs.json` 增加目录、四个文件原始 hash 和两个部分消费的源文件条目；`sync-upstream.py` 更新该完整目录的受信 SHA-256；`.gitattributes` 精确登记一个已有 CRLF 文件、一个上游空白原文和补丁制品的空白规则。现有 `generated/dynamic-tabs` sourceSet/outputs 无需另注册。补丁仅在可丢弃的读点演练，实际分支未改上述 Root 文件或基线。Architect 需在落点复核并应用；若上述文件有并发更新，停在冲突处，重新合并目录并重算 SHA，不得直接沿用旧 hash。然后由监工续派 Claude Sage 或 QA-C 验收；本卡不自行合并或 done。

`DesktopSettingsShareWindowsCatalog.kt` 原本为整文件 CRLF；注释修订保留同一行尾。原文 `HomeFeedSettingsSection.kt:38` 有四个空格的空行；补丁制品包含合法的空白上下文行。未登记的本分支 `git diff --check <base>` 会如实报这三类空白，不应删改原文或切换行尾来掩盖。Root 补丁使用与仓库既有原文字节登记相同的按文件 attributes；目录数据及已有测试均不改。

兼容注意：TON-1336 持有 `test_v046_dynamic_category_merge_source.py`，其中旧断言要求 Host 写死 `true`，与本卡运行时偏好需求冲突。本卡保留原测试，交 owner 更新其验收契约。v078 只核对该键和目标片段的原文 hash，不强推整体重钉。
