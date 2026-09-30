实际设置树集成 fixture（已使用 Root 本轮冻结产品快照验证）

此 lane 不产出产品 source/extractor/依赖，不修改 main 或先前冻结 cohort。`SettingsTreeFixture.kt` 只使用实际 main DesktopSettingsTree、Navigator、SearchController、SettingsSearchRepository、DesktopPrivacySectionBindings、Community.searchPreferences、DiscoveryRepository/Preferences 和共享 PluginStore；没有另建 enum/schema/控制器或替代 Tree renderer。

本轮实际调用：

`python desktop/.local/settings-tree-integration-proof/compile-run.py --dependencies desktop/.local/settings-tree-integration-proof/root-dependencies.json`

该 JSON 是有序 `[{"path":"<absolute immutable jar>","sha256Bytes":"<exact bytes hash>"}, ...]`：首项必须是本轮实际 product Kotlin classes snapshot（包括 Tree/Entries/Privacy/Home/Storage/Navigator）；后续 product Java/resources snapshots 和当前完整 runtime dependency jars。脚本不会读取变化中的 build/classes、调用 Gradle、复用旧 Tree snapshot 或修改依赖，只接受文件并严格验证 SHA，编译前检查产品 snapshot 的真实 Tree/Navigator/Controller classes。

fixture 子进程 LOCALAPPDATA 单独指向本目录 proof 下 freshly created owned temp folder；源 main 通过原 DesktopLibrary.directoryForAccount(null) 获得该隔离 root，并检查它仍位于指定目录内。DesktopSessionStore 显式 temp path/persistent=false，初始账号为空；不请求任何 Repository API。原进程环境、用户 settings/account 文件完全不访问。

已验证的真实产品流程

- 两种原 ui styles：实际 Root row 文案/摘要按原 resolveSettingsRootCategoryOrder 的可见位置顺序；Root→播放分类→真实 entry→Detail route→Back→Category→Root。
- 实际 Root Search 按钮与原 AppSearchField editor（Semantic SetText 是原控件编辑动作，不直接 setQuery）；点击真实 decoder 搜索 result 后，页面真正切入 fixture-labelled detail port，搜索 EditableText 已不在 scene。供应 Root history scope 排队 dispatcher 此前不允许运行，页面离开后才放行实际 repository 写盘；验证记录仍完成。
- 点击 Back 恢复原 Search entry token、Controller 查询以及重新挂载原文本控件中的值。检查更新搜索结果与 Root system category 都只进入 category；显式 action counter 保持0，没有自动执行该动作。
- Home category 使用实际同一个 Discovery repo 的已持久化 refreshCount 30，并显示原真实摘要。Storage→WebDAV Detail 与给定 dismiss 回调返回原 category；这里只验证 routes，不打开 popup/modal。
- Privacy 原控件 pointer 写现社区唯一 authoritative privacy/suggestions backing；同实例 Root settings history guard 和实际 Community search history 都抑制无痕写入、保留已有记录。独立 Hint key写 supplied Root global store；两个 backing 的实际 JSON 检查 global 没有第二 privacy key。复位隐私和推荐词后返回Root。

明确边界

appearance/plugins/playback/backup/system 的 detail-content ports 有明确 fixture 文案。未运行原生 PlaybackSettingsDialog/BackupSettingsDialog、真正 update action、账号请求、native窗口、Root Shell restore/restart 或通知权限。System action 只是 supplied port 的显式计数，用来证明 Tree 的导航不会代替用户点击执行该 port；不能将其称为真实更新服务验证。不要点击 donate/comment-fraud boundary popup，以遵守本任务 HWND 边界。

本轮实际 PASS：1 个独立 fixture 源使用当前 product Kotlin/Java/resources 三份冻结 jars 编译；MATERIAL3 与 MIUIX 各 25 个真实 pointer、2 个原 editor SetText，共 50 pointer / 4 输入。10 张 PNG、6 份实际 disk JSON snapshots 和 result.json 已保存，Root / 查询保留 / 详情 fixture / 隐私截图已查看。Search surface 已离开后才释放 history 写入，直接从实际 plugin-settings.json 的原 settings_search_history key 读出新查询与既有历史。权威 privacy/suggestions backing 仍是实际 community.searchPreferences；原 Root global backing 没有第二 privacy key。未发现产品问题或需合入 main 的修复。

compile-evidence.json 保存全部 234 个有序 jars 的精确 SHA，以及 16 个读取的实际 product source 的 LF SHA；product-snapshot-manifest.json 保存 Root 所提供的 raw source 和三份 jar 身份。依赖与 product source 在运行前后均重新核对，未发生变更。临时账户目录只在 child LOCALAPPDATA 生效，persistent=false 不创建 session 文件。此 cohort 不新增 original source/resource/dependency/Gradle task，无需合 main source，Root 可直接引用 proof 与冻结 manifest 记录验证结果。
