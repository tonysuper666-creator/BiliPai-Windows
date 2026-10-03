# Miuix 导航升级评审

评审日期：2026-10-02。

## 对话核实

- Navigation 3 1.2.0 于 2026-09-23 发布为稳定版，包含 Result API 和 Deep Link API。这条说法正确，见 [AndroidX release notes](https://developer.android.com/jetpack/androidx/releases/navigation3#1.2.0)。
- Miuix 0.9.4 用独立的 `miuix-nav` 替代 `miuix-navigation3-ui`，不依赖 `androidx.navigation3`。这条说法正确，迁移不能只改 artifact 名称，见 [Miuix 0.9.4 release](https://github.com/compose-miuix-ui/miuix/releases/tag/v0.9.4)。
- 相似的 NavKey、NavDisplay 和 back stack API 不代表两个运行时兼容。Google 的 Scene、Result 和 Deep Link API 不会自动成为 Miuix Nav 的功能。
- “继续用 Miuix，无需仅为 Deep Link 整体迁移”的建议适合本项目。至于两者长期能力差距和维护前景，属于判断，不能作为已经发生的事实。
- 对话对 BiliPai 链接处理的推测需要以代码为准：项目已有原生目标解析和 `b23.tv` 异步展开，不是尚待补齐的空白功能。

## 当前版本与决定

`gradle/libs.versions.toml` 固定 `0.9.4-5c91d5e5-SNAPSHOT`，本地
`:miuix-navigation` 保存对应发布源码及视频返回补丁。

核对 [v0.9.4 到 5c91d5e5 的提交](https://github.com/compose-miuix-ui/miuix/compare/v0.9.4...5c91d5e5)，
当前版本在正式版之后已有九个提交，包含导航切换清除焦点的修复。
截至评审时，[之后的 main 提交](https://github.com/compose-miuix-ui/miuix/compare/5c91d5e5...106af300)
仅涉及示例与 README，没有修改 miuix-nav。

因此保持固定版本和本地导航源码，不降回 0.9.4，不为版本号变化导入第二套运行时。
后续若上游导航有实际修复，需对照当前基线合并，同时保留按 session 冻结的返回策略、
视频卡片的取消/完成规则及防止旧 session 修改新手势的补丁。

## 本次收敛

- 移除没有生产代码引用的 `lifecycle-viewmodel-navigation3` 依赖，避免残留 AndroidX Nav3 运行时依赖链。
- 移除未使用的远程 `miuix-navigation` catalog 别名。唯一实际导航依赖继续为 `project(":miuix-navigation")`。
- 更新两个旧结构测试：从要求 Nav3 alpha07 和 entry decorators，改为检查本地 Miuix 接入及无旧 Nav3 依赖。
- 保留历史 `navigation3` 包名及函数名，避免无行为收益的大范围重命名。

## 已有深链与状态接入

- `MainActivity.resolveIntentLinkAndNavigate` 处理外部入口，并等待短链异步展开后再决定原生目标或网页兜底。
- `BilibiliNavigationTargetParser` 和 `BilibiliLinkNavigationPolicy` 负责链接到业务目标的解析与分流。
- `AppNavigation` 将业务目标交给现有路由和返回栈操作；`rememberNavBackStack<BiliPaiNavKey>` 使用可序列化的完整路由层级。
- `BiliPaiNavDisplayHost` 使用 Miuix NavDisplay；本地运行时负责 entry 生命周期、保存状态及 ViewModel store。应用补充 Application creation extras。

当前没有需要靠 Nav3 1.2 API 才能解决的已确认功能缺口，因此不新增 matcher、Result 总线或 Scene 架构。
如果以后需要导航结果或多窗格，应先定义具体业务行为，再决定应用层实现还是切换运行时。

## 验证边界

按用户要求未执行编译、Gradle 测试或安装。只做源码引用、版本提交比较及 diff 检查。
依赖移除后的完整依赖图和设备行为尚未验证；更新的 Kotlin 测试也未运行。
现有其他结构测试仍有针对历史 Nav3 实现的断言，本次没有全面迁移测试套件，不能据此声称全套测试通过。
