完整原 VideoContentSection 正文安装合同（source-only）

目标为固定 stable 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589。原 VideoContentSection 2209 行完整正文、原 RelatedVideoItem 663 行完整推荐与长按行为、AI Summary、Supplement、完整 VideoNoteSection 富文本编辑器保留。原 VideoCommentTab 整函数由现有唯一 video-comment producer 提供；原 BGM 已拥有的推荐封面常量由现有 renderer 提供，不再次生成。

唯一生产工具 `prepared/desktop/tools/extract-upstream-video-content-full.py` 默认只输出 6 个 selected 文件；14 个 DIRECT 原全文由唯一 upstream sync 复制一次。standalone 输出 20 个文件仅用于本 packet 窄编和逐字证明。安装前合并 `content-registry-delta.json` 的 feature union，保留 existing mode，不能整替换 registry。2 个 manual 源按 recipe 原字节复制；没有 Controller、Operations、Listen 或 Shell 整文件。

完整原正文参数组 `VideoContentData/Engagement/Comment/Note/Presentation/PrimaryActions/CommentActions/NoteActions/UiActions` 保留原 fields。Root 必须提供真实 raw ViewInfo、reply/emote/comment state、原 note state 与动作；不得以默认对象或空回调冒充原消费者。原 UI 跨章节、合集、相关推荐导航改为 `(String, Long, String?)` 传 BVID、CID、原 cover。`buildDesktopOriginalVideoNavigationOptions` 保留原 CID<=0 不写入、base merge 与 cover.trim() 规则；不生产另一个导航 schema。

`LocalDesktopOriginalVideoContentBindings` 使用同一详情 entry 的 `isCurrent/commitIfCurrent` 和同全局 `DesktopOriginalPlayerSettingsContext/HomeSettingsPort`。推荐 feedback 使用现有对应账号的 retained Home/Discovery `DesktopPluginContext`，不再创建 context/store/cache。原暂不喜欢的反馈算法与记录 schema 保留，最终写入捕获 caller Job 并经同 Root Store→entry gate。Root feedback 只赋消息，share callback 只给唯一原生 actor 入队，锁内不等待 pane、native、网络。

`watchLater(aid, add)` 传原动作语义：add=true 意味加入。若接 Stage1 的 `VideoEngagementActions.toggleWatchLater(aid, currentlyIn, bvid)`，必须传 `currentlyIn=!add`。各异步 onVideoHidden/UI callback 经过 caller Job+owned gate；block creator busy 清理由 request identity 判等，单次失败或取消可恢复，旧 finally 不清新 request。

`desktopOriginalVideoRelatedBlockedPort` 只包已存在 DesktopBlockedUpRepository/store/owned API/CSRF/bootstrap。传详情自己捕获的 epoch 和 entry owner，不能复用 Home lifetime 请求 facade。local block 的最终 upsert 在 Store→entry gate；Bilibili sync 使用原 full repository 方法，await 后再检查 caller active 和 owned。不增加网络 client、DTO、Store。

4 个原 getter/key/default 保留：`video_ai_summary_entry_enabled=true`、`video_note_enabled=true`、`video_detail_chrome_scroll_hide_enabled=false`、`show_video_detail_comment_count=true`。setter/UI 仍现有唯一 global settings producer。Related 原 card style 从真实 HomeSettings Flow 收集，无假初始默认。

Root 安装原富文本依赖 `com.mohamedrejeb.richeditor:richeditor-compose:1.0.0-rc14`，选择 published desktop JVM variant。prospective 窄编只追加官方 desktop JAR（SHA c4d49d810294afa86d4335b058e7ee9cc4ddb875ec08355354bc77f8c917c8bb），没有改 actual47 97CP，也没有富文本 runtime 验收。Root 应由同 Gradle 图解析 ksoup-html-jvm/ksoup-entities-jvm 0.6.0 与 markdown-jvm 0.7.3 及 published Compose/Kotlin 依赖并重新冻结 provenance/ordered CP。原 `rememberRichTextState`、`BasicRichTextEditor`、Material3 `RichTextEditor`、Markdown、历史、SpanStyle 操作都保留，未替换为普通 TextField。

窄编 content-runs/06：22 源通过，102 类。against actual47 + frozen Stage1/Stage2 的 class FQN 和同包+JVM名+参数 descriptor 都零交集；非法 NON_LOCAL_RETURN 名称为零。生产审计 03：默认 6 输出、DIRECT14 跳过、standalone20 等字；16 个原完整文件逐字或反向还原逐字。01/02 真实缺富文本依赖失败保留。原 PlayerSection/StateHolder 与完整 ordinary/landscape/portrait page 仍下一片，不把此正文编译称整页 runtime 对齐。
