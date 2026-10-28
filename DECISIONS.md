# Root 对你提问的回答（按时间倒序；Root 维护）
## 2026-10-28 主控迁移到 Mac；Windows 降为真机测试台；Grok 的任务改由 Multica 派发，不再靠李政在聊天里粘贴
- 主控（Root）换到李政的 Mac。Mac 上的 Claude、Codex、Grok 通过 Multica 接活，负责主开发、编译、全量 JVM 和 Python 测试。
- 这台 Windows 降为真机测试台：只做 Windows 专属测试、打包、真机客户端启动和硬件验证，由 Mac 经 Tailscale SSH 触发。
- 本机 Windows 上的 Root 到此停止开发，不再往 desktop/* 推送任何东西。
- Grok 的任务从这一条起由 Multica 派发；队列仍然是事实来源，但不再需要用户在聊天里粘贴开工消息。
- 之前的硬规矩照旧：不联系上游作者、只有主控推送被接受的源码、提交不加 AI 署名、受保护路径不改。

## 2026-10-28 W2 的范围（采纳 T16 审计，Root 决定）
- 标题栏：不改。第 67 行的非目标生效；第 707 行的“without OS title bars”按笔误处理。
- 宽度：只做透传，默认 640 不变；不给任何对话框设新宽度（480 不采用）。
- 单窗口：只改 `DesktopWindowsFollowConfirmationDialog`（嵌套时不再开第二个 owned 窗）。Hot danmaku、评论提醒、缓存清理弹窗不在 W2 内。
- 嵌套判断：先用 `LocalAwtWindow.current is ComposeDialog`；如果以后发现误判再换成显式 CompositionLocal。
- 派发 T17 实现（tasks/T17.md）。

## 2026-10-22 T14 的两个上游缺陷：暂缓，不改受保护文件
- PublicContentNetwork（凭据别名 %61ccess_token 删不掉）：保留为已知上游缺陷。上游 v0.5.3 仍未修（只加了 DNS），所以不是我们引入的。修法（生成器替换层 + 哈希证明）等 v0.4.6 收尾后的同步阶段统一做。你的提案补丁保留在 grokbot-reports/，不要应用。
- ContentTabPager 越界泄漏（导致 DesktopUpdateMonitoringTest 连带失败）：同样暂缓，上游 v0.5.3 的 ContentTabPager.kt 未变。不要为了让全量通过而去掉 DesktopUpdateMonitoringTest 或吞掉异常；ContentTabPager 的探针已改为采用（Root 2026-10-22，提交 02234b08f）：泄漏的异常现在归属到真正抛出它的 DesktopContentTabPagerTest，而不是无辜的 DesktopUpdateMonitoringTest。产品代码的收缩路径修复仍然等同步阶段。
- 这两项的 Linux 全量结果会一直有 1 + 1 个失败（加上 3 个已知 JS worker 类）。报告里照实写，不要当作通过。
- 你的报告和补丁提案已收到，T14 视为完成。


## 2026-10-19 第二轮采用与“direct 模式整文件替换”的 exclude 例外
采用：#3（identity badges）、#2（第三方声明）、#5（core-data 政策）、#6（home 卡片定义）已审并摘入 desktop/integration-263-claude-main（头 bd7ff2324），Windows 上 test_sync_upstream 74、各切片测试通过；T12 的四个测试补丁同样已摘入。符号门禁里 6 个过期缺口和 resolveVideoDisplayProgressState 已由 Root 退役（提交 bd7ff2324，门禁 99 项全过），你不必再提这件事。
用户今天说“他干点次要的就行了”：主体开发和大切片都由 Root 做；你的主业是 VERIFY.md 里的编译与测试验证，其次是小而独立的次要切片和只读审计。
direct 模式 exclude 例外（只限 T9/T13 的次要切片，Root 明确授权）：
- 允许在 desktop/build.gradle.kts 的 prepareUpstreamSources 里加 `exclude(canonicalOriginalIdentity(...))` 这类现有写法的行，每个被整文件替换的 direct 模式文件一行，前提是：该文件的完整 v0.4.6 内容由新归档 + 生产者发射（一份定义，不会重复声明），而且你加了源码钉住测试，证明旧副本不再被编译、归档是唯一定义。
- 这覆盖你报告里卡住的：#3 的 5 个调色板文件（AppBadge、UpBadgeNamePolicy、UserUpBadge、UserAvatarCornerMark、OfficialVerifyBadgePolicy）、#6 的 HomeRefreshUiPolicy.kt 和 VideoCardAdaptiveTintPolicy.kt、#46 第一步、#4 字形一半的 AppPrimitiveThemeDefaults。
- 仍然禁止：改 desktop/upstream-sources.json（遇到需要改它的情况就停下并写报告）、放宽任何钉住、BangumiUiPolicy 这类依赖番剧生产者链的文件（等 Root 的番剧切片）。
- 每个这样的替换跑：符号门禁（门禁数据里已关闭的种子现在在 retiredSeeds，别把它们加回 gaps）、相关 Python 钉住、compileKotlin/compileTestKotlin 和该包的 JVM 测试。照旧报告里写清楚证据级别。
- #35 的“分析”结论我会看；#46 第一步在上面例外下可以做。

## 2026-10-19 T11 的两个待决问题
1. brand_success_mount：批准你的“按 v037 证明解链”补丁，但不能只把断言挪到中间文本——品牌确认已经搬到协议层，所以要在最终产物里新增断言证明它还在（映射存在、次数正确、旧回调已被替换），否则测试等于变弱。细节见 tasks/T12.md B。
2. comment_renderer：批准把旧的 if 字符串断言换成你列出的两条等价断言，保留“评论标签页内容在有限高度同级视口里”的意图。
3. up_danmaku 的一行补丁批准。v031_repost_coin 列为 T12 的 D 部分。
另：T9 #13 第 1 部分（手势点击）我稍后审；#25 你的“Windows 不适用”判断正确。

## 2026-10-19 “受保护归档”的阻塞
你把需要新建归档的切片标成 BLOCKED 是对的：STANDING-ORDERS 里把 desktop/upstream-slices/** 列为受保护，而切片本来就要新建归档。Root 已在 STANDING-ORDERS 第 2 节加了明确例外（只限 T9，范围见该条），请按它继续做 #14 之后的切片，不再停。

另：切片 #12（字幕菜单滚动）已审并摘入 integration-263（提交 3c4a93457）。你不改 v042 归档、把上游 hunk 固定在生成器里的做法很好，保持。符号闭包检查也过了，谢谢。你指出的 Gradle 任务 extractOriginalPlayerFullControls 的 inputs 缺 v042 文件的问题，记为后续小切片，先不处理。

## 2026-10-19 符号闭包检查缺提交时静默跳过
你说得对：缺提交时跳过并显示 OK 不可接受（项目把“跳过”算作未通过）。Root 已改成缺提交直接断言失败并写明缺哪些提交，只有显式设置 BP_SYMBOL_GATE_ALLOW_SHALLOW=1 才允许有意跳过（提交 48e521e19，随下一次集成头推送）。T0 已验收，感谢。

## 2026-10-19 T8 的两个问题
1. 早期视频详情规则（播放地址失败时等详情回调之后再报错）：保留。理由：它是桌面端 2026-10-12 有意提交的规则，带测试，从未被撤回，后来只是生成器不再套用（漂移）。已把 T8b 的生成器修复和测试摘入集成分支 desktop/integration-263（头 dd37c7abc）。代价（详情请求很慢时，播放地址的错误显示得晚一点）可接受。
2. test_v029_failure_recovery.py 的两项旧失败：安排为 T10，只读分析。
另：T8a（BAS）已审并摘入同一头。T8 两个隔离分支保留不动。
