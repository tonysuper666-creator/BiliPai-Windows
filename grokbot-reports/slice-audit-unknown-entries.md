# T13 #10 audit-unknown-entries — 只读审计（2026-10-19）

只读审计，无代码/构建/测试改动。数据来源：desktop/integration-263@c985a0386 的干净全量构建产物（2026-10-19 VERIFY 时由 Gradle 重跑全部生产者）+ desktop/src/main；上游提交均在本地对象库中读取（未联网、未接触上游仓库）。机器可读结果：`grokbot-reports/audit-unknown-20261018.json`（roadmap firstStep 要求的文件名）。

| 条目 | 建议状态 | 证据 |
|---|---|---|
| v027-dissolve-delete-layout-fixes | 缺失 | Windows WatchLaterScreen 仍是 ad85f33c 之前的形态：AnimatedVideoListItem 无 useLookaheadBounds 参数；completeVideoDissolve 无重复/延迟完成防护（无 'Ignore duplicate/deferred completion'、无 rollbackState）；未使用 resolveCommonListRenderKeys。消散效果为桌面自有 com.bilipai.desktop.ui.jiggleOnDissolve，离屏回调延后（上游用 Android Handler）需桌面等价物。 |
| v034-upstream-tests-added | 部分 | 9 个提交涉及 61 个上游测试文件（6 新增/54 修改/1 删除）。按类名在 Windows 树中找到 3 个（BilibiliLinkNavigationPolicyTest、FloatingBottomBarGeometryTest、VideoPlayerCoverPolicyTest，未逐条核对版本内容）；7 个被测对象已在 Windows 编译集中但测试未携带（DynamicLayoutPolicyTest、AppSegmentedControlPolicyTest、TabVisibilityPolicyTest、PlaybackSettingsSelectionPolicyTest、SettingsEntryVisualPolicyTest、VerticalPriorityPagerGestureTest、NextWatchSuggestionTest）；其余 51 个被测对象未携带（启发式：按文件名匹配），测试随对象切片走。清单见 grokbot-reports/audit-unknown-20261018.json。 |
| v026-home-overlay-pill-press-shift | 缺失 | AppPressShiftContent、HomeOverlayPillButton、resolveOldContentDividerContainerColor 三个定义在编译集中均不存在；它们已登记在 desktop/tools/symbol-closure-expected-gaps.json（首页卡片级联缺口），v033-home-undo/v043-home-native-cards/v029-home-load/v042-search-dependencies 等已有归档引用它们。由 T13 #6 fnd-home-card-cascade-definers 覆盖。 |
| v026-search-entry-centered-transition | 缺失 | Windows AppSearchEntry（preferences 生产者，AdaptivePreferenceComponents.kt:1676）没有 d8cd4bd9 新增的 centeredIdleContent 参数及居中布局；编译集中无任何调用方传该参数。 |
| v026-dynamic-top-tabs-scroll | 缺失 | Windows 动态页不使用上游 DynamicTopBar，而是自有 DesktopDynamicTabsHost → AppNativeTabRow(scrollable 默认 false, Modifier.weight(1f))；b216920a 的固定标签宽度 + 视口滚动 + 选中项 animateScrollTo 未携带。窄窗口下才有实际影响，适用性请 Root 判定。 |
| v027-home-feed-row-alignment | 缺失 | Windows HomeCategoryPage（home-page 生产者，LF sha cfc3ff16…）仍含 f7123aa2 删除的注释 'Truncated cards advance as complete rows'，无 rowHeightPx / 行内最高卡片定高 / bf9a9f7f 的按宽度失效 layout{}。VideoCard（home-full-card）为另一版本形态（nameLeadingContent，reserveTrailingSlot=false），该半部分未判定。 |
| v034b2-third-party-license-notices | 已完成 | 已在 grokbot/slices-batch1（#2，79ed052e4）交付：desktop/src/main/resources/licenses/upstream-carried-assets/ 含 AndroidLiquidGlass.LICENSE（a0fabed3）与 piliplus-LICENSE.txt（b2091b1d）及来源记录，测试 test_third_party_notices_source.py。integration-263 尚未合入；上游这两个提交没有应用内许可证界面消费者。 |
| v034b2-card-return-source-session | 缺失 | Windows 实际调用的 resolveBiliPaiVideoSource（DesktopHomeReturnNavigationOwner.kt:101 → upstream 副本 BiliPaiVideoSourcePolicy.kt）仍是 b2091b1d 之前的逻辑（'keep list origin … multi-hop returns'），嵌套链接仍借用列表来源；无 sourceCardOwnerRetained。preserveCurrentSession 来自 2026-09-21 的 557313712，不是本修复。 |
| v040-history-in-place-progress | 缺失 | 与 v039-history-in-place-progress（missing）重复。Windows HistoryViewModel（original-favorites 生产者）observeHistoryRefresh 收到 historyChanges 仍整表 loadData(showLoading=false)，会重排；无 updateCachedHistoryProgress / enterBrowsingSession。 |

## 汇总

- 已完成 1（v034b2-third-party-license-notices，在 grokbot/slices-batch1，待 Root 合入）；部分 1（v034-upstream-tests-added）；缺失 7。
- 没有条目判为不适用；v026-dynamic-top-tabs-scroll 适用性低（宽窗口不触发），请 Root 判定。
- v040-history-in-place-progress 与 v039 条目重复，建议合并为一条。
- v026-home-overlay-pill-press-shift 的三个定义正是 T13 #6 的目标；#6 落地后该条可改判。
- roadmap.json 的 windowsStatus 未改动（由 Root 重新冻结）。

## 证据分级

- Linux 编译：无（本任务不涉及编译；所读产物来自今天 c985a0386 VERIFY 的 Linux 全量构建）。
- Python：无新跑（产物由该次 Gradle 构建中的 Python 生产者生成）。
- JVM：无。
- 未验证：全部结论都是静态源码比对；上游测试携带情况为按类名/文件名的启发式匹配，未逐条比对内容；无任何 UI/真机/Windows 行为结论。
