# Roadmap 切片 #14 rss-referer-opml-groups

分支 grokbot/slices-batch1（先合入 integration-263@578444aeb，提交 86370a61b）。代码由 Grok Build 编写，我审查范围并运行验证。只改了 desktop/src/** 和测试，**没有碰受保护文件**，没有新增归档或 Gradle 改动。

## 第 1 步：Referer/Origin 主机门控（`10430cc3b`）
- 新文件 `desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopBilibiliRequestHeaderPolicy.kt`：`usesDefaultBilibiliHeaders(host)`，逻辑与上游 v0.4.6 `core-data/.../BilibiliRequestHeaderPolicy.kt`（blob 7a5e0693…）一致。按桌面端自有代码移植，没有建归档，因为归档在受保护路径下。
- `DesktopRepository`：`resolveReferer` 先保留显式 Referer，再做主机门控（非 B 站主机不加默认 Referer），其余逻辑不变；默认 Origin 也只发给 B 站主机。修正前，桌面端对所有主机（包括 RSS 外站图片）都发 `Referer/Origin: https://www.bilibili.com`。
- 与上游仍有的差异（保留原行为，未改）：调用方带了显式 Referer 时，桌面端在 B 站主机上仍会补 Origin，上游不补。
- Cookie：核查过，账号 Cookie 已由 `DesktopSessionStore.isBilibiliHost` 限定在 B 站域，不会发往外站。
- 测试：新类 `DesktopBilibiliRequestHeaderPolicyTest`（5 项）：上游两组正反例、resolveReferer 外站/显式/B 站各分支、拦截器级别验证外站无 Referer/Origin、B 站 CDN/API/WBI 行为不变。

## 第 2 步：分组编辑与 OPML 文件导出（本提交）
- `DesktopSubscriptionRepository.setGroup(id, group)`：通过已生成的 v0.4.1 `SubscriptionFeedStore.setGroup`（去空格，未变化不写盘）；内置源 `builtin:*` 直接拒绝。新增 `writeSubscriptionOpml(path, text)`（UTF-8）。
- `DesktopSubscriptionPageBindings.setGroup`：走 owned{}。
- `PluginProviderScreens`：自建源旁边加“分组”按钮和对话框，标题旁显示分组；新增“导出 OPML 文件”，使用 AWT `FileDialog` 保存（仓库里没有可复用的保存选择器）。默认文件名 `bilipai-subscriptions.opml`，带 owner 失效检查和取消时关闭对话框；仍保留“复制 OPML”。
- 测试：新类 `DesktopSubscriptionGroupTest`（5 项）：去空格并持久化、未变化不重写（字节一致）、内置源拒绝、导出 OPML 把分组嵌套为文件夹并能被解析回来、文件写入 UTF-8 字节一致。

## 验证
| 项 | 结果 |
|---|---|
| compileKotlin compileJava compileTestKotlin compileTestJava（两步分别跑） | BUILD SUCCESSFUL，0 错误 |
| 第 1 步 JVM：com.bilipai.desktop.data.* + DesktopSubscription* + 两个 Coil 类 | 50 类，405 测试，1 失败：DesktopDynamicCacheTest 的 NOSHARE_DELETE（Windows 共享模式夹具，T5 已归为仅限 Windows 的类别 a） |
| 第 2 步 JVM：com.bilipai.desktop.plugins.* + DesktopSubscriptionReadingProgressRegressionTest | 19 类，112 测试，5 失败，全部来自 DesktopPreferenceReplacementRetryWindowsTest 的 “Opt-in Windows native file test”（设计上只在 Windows 运行） |
| 新测试 | DesktopBilibiliRequestHeaderPolicyTest 5/5，DesktopSubscriptionGroupTest 5/5 |
| 符号闭包检查（第 1 步之后） | pass，unexpected 0，stale 0 |
| Python | 未改生成器、归档或登记，不涉及 |

## 交给 Root
新增 JVM 测试类 DesktopBilibiliRequestHeaderPolicyTest（5 项）、DesktopSubscriptionGroupTest（5 项），需要加进 Windows 验证计划。不涉及 inventory 或 windowsRevision。

## 证据分类
Linux 编译：已验证。JVM：已验证（上表）。Python：不涉及。未验证：Windows 上 FileDialog 保存的实际交互、分组对话框外观、真实 RSS 外站图片在去掉 Referer 后是否正常加载（如需判断防盗链行为，只能在线验证）。
