PrivacySection Windows 隔离交接

本目录是可审阅、已独立编译的草稿。未修改 main、共享 Gradle、manifest，也未创建 HWND 或发出账号/网络请求。`frozen-handoff.json` 记录权威 HEAD、各主文件基线、原 source/resource 身份和交付文件摘要；复制前验证摘要与精确 patch 基线。

接入顺序

1. 合并 `inventory.json` 四项源身份：SettingsSections.kt 与 SettingsManager.kt 已由主线持有，合并 features，不能复制重复身份。新增 SearchHintSettingsStore.kt 与 SearchScreen.kt 两项。合并 `resource-inventory.json` 一个原 ms_shield_24.xml；不能把它加入另一个全量 vector 表再重复生成本表。
2. 复制 `owned-files.json` 三个产品文件及一个 Python 定向测试。生成器输出五个 Kotlin 文件；没有 direct 模式重复输出，也不输出现有 SettingsCardGroup/SettingsAdaptiveDivider。新 generated 目录建议 `desktop/build/generated/settingsPrivacy`，加入 compileKotlin sources，任务输入包含这四项原源、一个 XML，以及 privacy/plugins/media/sync/settings-search 五个工具文件。命令：`python desktop/tools/extract-upstream-settings-privacy.py --repo <repo> --output <generated>`。`--inventory` 和 `--resource-inventory` 独立输出 JSON。无新增 runtime dependency。
3. 应用 `integration-drafts/01-category-divider-visibility.patch`，只把 category extractor 选出的原 private SettingsAdaptiveDivider 改为 internal，函数体完全保留。Home 与本 section 共用这一份 helper，不重复输出。
4. Root 创建 `DesktopPrivacySectionBindings`，传入既有全局 DesktopPluginContext（它的 store 就是 Root globalPluginStore）以及**既有 `community.searchPreferences`**。不要传 runtime.store 的同名 privacy namespace，不创建 DesktopSearchPreferences 第二实例，不镜像 privacy_mode_enabled 或 search_suggestions_enabled 到全局文件。
5. Privacy category/detail 调用以下 wrapper，四个回调分别传原权限管理、消息通知、黑名单、发评反诈历史路由。未实现的目的页应明确告知边界，不能以 URL 或空页面声称完成。wrapper 自身不导航、不开窗、不联网。
6. 应用 `02-original-search-hint-and-recommendation-semantics.patch` 与 `03-community-hint-state-hoisting.patch`，Root 同一 globalPluginContext 读原 Hint Flow，向 CommunityContentScreen 尾参传递实际值。两个 patched UI 源已独立编译，现有其它 caller 因尾参默认值保持 source compatible。

```kotlin
val privacyBindings = remember(globalPluginContext, community.searchPreferences) {
    DesktopPrivacySectionBindings(globalPluginContext, community.searchPreferences)
}
val defaultSearchHintEnabled by remember(globalPluginContext) {
    SearchHintSettingsStore.isEnabled(globalPluginContext)
}.collectAsState(initial = true)

DesktopPrivacySection(
    bindings = privacyBindings,
    onPermissionClick = { /* 原权限管理目标 */ },
    onMessageNotificationClick = { /* 原消息通知目标 */ },
    onBlockedListClick = { /* 原黑名单目标 */ },
    onCommentFraudHistoryClick = { /* 原发评反诈历史目标 */ },
)
CommunityContentScreen(/* 现有参数 */, defaultSearchHintEnabled = defaultSearchHintEnabled)
```

精确产品与存储边界

| 原字段/入口 | Windows 草稿实际行为 | 唯一存储/边界 |
|---|---|---|
| 搜索框默认词 | 原完整 SearchHintSettingsStore 读写，默认 true；关闭时固定原 placeholder，空查询不会偷偷提交默认词 | Root globalPluginStore 的 settings/search_default_hint_enabled；独立于搜索推荐词 |
| 搜索推荐词 | 原 section 控件实际调用现有 setter；consumer patch 按原 SearchViewModel 语义切换搜索发现个性化并刷新 | community.searchPreferences 的 search/plugin-settings.json，settings/search_suggestions_enabled，默认 true |
| 不记录历史 | 原 section 控件实际写现有全局隐私；已有普通/Story/搜索等消费路径不更换 reader，已有历史可读且不删除 | 同 community.searchPreferences 的 privacy_mode/enabled；默认 false，不创建第二 key 或 backing |
| 进入隐私内容时验证 | 读取原 SettingsManager 的实际保存值，原开关禁用；明确“Windows 身份验证尚未接入；当前不会验证或保护隐私内容” | Root globalPluginStore 的 settings/privacy_content_authentication_enabled，原默认 false；不伪调用 onEnable 或写死 false，未实现 Windows Hello/session unlock/gate |
| 权限管理 | 原标题、图标、回调 | Android INTERNET/ACCESS_NETWORK_STATE/POST_NOTIFICATIONS/FOREGROUND_SERVICE/本地网络/存储等不等于 Windows 能力状态；这批仅完整保留入口，不虚报“已授权” |
| 消息通知 | 原标题、摘要、图标、回调 | Android permission launcher/channel/Service/WorkManager/boot receiver 未由本 section 替代；Windows scheduler/toast 与权限需单独真实实现 |
| 黑名单管理 | 原标题、摘要、图标、回调 | 主线已有部分本地屏蔽与 relation 写入；原完整管理页还有导入/导出、同步、资料刷新，单一发现屏蔽列表不能冒称此整页已对齐 |
| 发评反诈历史 | 原盾牌 XML、标题、摘要、回调 | 原 CommentFraudRepository/Room records/recheck/delete/import/export 未在本批移植；不能改成外部网页 |

认证的原保护目标来自 PrivacyAuthenticationPolicy：search/search_trending/history/favorite/watch_later/download_list/offline_video/inbox/四类消息/chat、favorite/favorite_season 季详情。只保存 bool 不能声称这些目标已有验证。

原搜索推荐词开关与输入联想不是同一语义：SearchViewModel.init 收集 getSearchSuggestionsEnabled 并传给 refreshDiscoverInternal 的 enablePersonalizedRecommend；loadSuggestions 仅检查输入、300ms debounce 与过期查询。当前 Windows main 错把该开关用于输入联想。consumer patch 将开关用于发现个性化，恢复原输入联想行为，并保留 Windows 已有 privacy 对个性化的额外门控。Root 自己的长期历史写入 scope 接缝保持原样。

源保持及平台改动

- 原 PrivacySection 的控件顺序、文案（除认证可用性边界）、icons/tints/group/divider、三个参数与七个回调完整保留。只将 LocalContext→提供的 bindings、collectAsStateWithLifecycle→collectAsState、Android painter→原 XML vector、Hint setter→有错误通道的同一原 setter，以及认证 row 的 enabled=false/明确提示做平台适配。
- 原 SearchHintSettingsStore 全对象仅修改 Context/imports/settingsDataStore 绑定。原 authentication key/getter 以及两个 SearchScreen query/placeholder 函数体逐字提取；Python tests 做逆变换完整比对和漂移 fail-closed 检查。
- 新 Bool DataStore 仅更新本次键，复用 Root 现共享 backing 原子锁/写盘/Flow/freeze；不缓存或覆盖整份 settings 文件。read/save error 不会伪更新开关，取消继续传播。
- Root restore/shutdown 必须继续 freeze globalPluginStore 和 community.searchPreferences；本 wrapper 没有自己的后台 Scope，也不持有独立 backing。Hint 编辑接收到 freeze 后返回 false并显示固定错误；历史读取仍可用。

验证

`compile-evidence.json`：实际 Kotlin2.4.0/Compose compiler 隔离编译，输入为原控件/策略、不可变 product/runtime classpath 与两个只读现主存储源码；`consumer-compile-evidence.json`：两个完整 search/community consumer review 源通过同一独立编译。

`proof/result.json`：7 个真实 temp-disk/policy 场景；M3/Miuix 各11个实际 ImageComposeScene pointer事件，共22次；三个真实开关写盘、四个原导航目标、已保存 true 的禁用认证、不成功文件替换后的原开关状态/错误提示/同实例 pointer retry。`proof/*-shared-settings.json` 与 `*-authoritative-search.json` 是实际 fixture 文件快照，不是预制模拟响应。四张 PNG 已目视检查；认证项明确禁用，失败信息实际出现在原控件下。没有 native popup、账号请求或网络页面的 runtime 证明。

Python extraction tests 5 个：整个原 Hint body、整段原 PrivacySection 逆变换、原 auth/query 算法、原 shield path/闭包、上游认证文案变化 fail closed。运行 `python desktop/tools/tests/test_extract_upstream_settings_privacy.py` 或本目录同名测试。Root 合入后再做共享 targeted compile/check，不能把本次独立 fixture 作为整产品 native 验收。
