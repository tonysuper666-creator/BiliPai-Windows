# Windows 功能查漏审计（基线 desktop/v025-parity @ a969341e2）

审计代理：功能查漏（gap audit）。本报告只做缺项识别，不修改任何主任务负责文件。
方法：固定读取上游 jay3-yy/BiliPai 的 tag 源码，与 `desktop/upstream-sources.json`（1335 项采用来源）、`desktop/upstream-slices/*`（57 个切片）及 `desktop/src/main/kotlin`（Windows 自有实现）做路径级 + 功能级双重对比；功能级以 grep 验证 Windows 端是否已有等价实现，不以文件数量换算完成率。

> 主控核验（2026-10-12 UTC）：已通过官方 GitHub API 完整读取 4 页发布记录（339 个发布，含 80 个 prerelease），解析 v0.3.5 对应完整提交 `46eaf277e83512061785be9759d1f07aba3c4839`，并核对 a69 → 46e 的 24 个提交、153 个文件和 +9802/−4748 统计。GitHub 发布时间为 2026-10-12，发布说明标注 v0.3.5 (2026-10-13)，两者采用的日期口径不同。检测不等于移植完成。
>
> 本审计是 a969（Source61）快照的静态排期材料：未进入来源清单不证明 Windows 行为缺失，grep 也不证明行为等价。实现前须核对实际连接的原源码与 Windows 生产路径。Source62 的 PGC 音频下载随后已提交为 `2bc5c4879c71227ab7f9511bce9b4a4ee16807a1`，实际编译、10 项 JVM 回归和 9 项库存检查通过；以下“主任务未提交”属于原审计时点。桌面仍为唯一 .56，硬件、窗口与完整稳定版对齐尚未验收。

## 一、上游版本状态

- 上游最新发布 tag：**v0.3.5**（2026-10-13，versionCode 454，提交 `46eaf277e`），upstream/main 与该 tag 相同，无额外提交。
- 当前 Windows 切片覆盖至 v0.3.4-beta.2 / v0.3.4（整版 canonical 基线仍为 v0.2.5）。
- **v0.3.4 → v0.3.5 共 24 个提交、153 个文件（+9802/−4748），Windows 端零接入。**
- v0.3.4-beta.2 → v0.3.4 正式版 147 个生产文件中约 40 个未进入采用清单（部分功能已有 Windows 等价实现，见下文标注）。

## 二、v0.3.5 未接入缺项（优先级：功能 > 交互 > 视觉）

### A1. 核心功能缺项（建议优先）

| # | 缺项 | 上游文件 | Windows 现状 |
| --- | --- | --- | --- |
| 1 | 收藏夹封面滚动懒加载与失效缓存（修复"仅补前 6 个"，PR #903） | `core/store/FavoriteFolderCoverCache.kt`、`feature/list/FavoriteFolderCoverPrefetchPolicy.kt`、`FavoriteFolderCardList.kt` | 收藏夹业务已有，无封面懒加载/失效缓存策略，grep 无对应实现 |
| 2 | 竖屏短视频流接口解析、分页补量与推荐调度（减少分页重复与空结果中断） | `StoryModels.kt`、`StoryRepository.kt`、`StoryFeedPolicy.kt`、`StoryViewModel.kt`、`StoryScreen.kt` | Story 基础已接（`DesktopStoryTopic*`），v0.3.5 修复增强未同步 |
| 3 | 同页切集/相关视频导航保留目标 CID、封面与横屏意图（VideoNavigationCidPolicy，入口方向请求按路由会话处理） | `feature/video/ui/pager/PortraitPagerSwitchPolicy.kt`、`navigation/AdaptiveStateRestorationPolicy` | NextWatch 已接（`DesktopOriginalVideoNextWatchBinding`），此导航 CID 修复未同步 |
| 4 | 自动续播遵循"进入视频自动播放"设置（关闭时切分 P/合集/队列停留封面等待手动播放；同视频切分 P 手动起播状态重置） | `VideoPlaybackViewModel.kt`、`b79661a6c` | 自动续播已有，此行为开关未同步 |
| 5 | CDN 测速与线路面板（更多菜单新增，底部展开 + 背景模糊） | `fix(video)` 系列提交；复用既有 CDN 诊断 | Windows 有 CDN 传输诊断（`CdnTransferRuntime`、`playbackCdnDiagnostics`），无测速面板 UI 入口 |
| 6 | 动态链接卡统一渲染器（PR #904，消除进出详情横幅闪烁） | `DynamicLinkCard.kt`、`DynamicLinkCardPolicy.kt`、`DynamicOpusLinkCard.kt`、`DynamicAdditionalCardPolicy.kt` | 动态卡已有，无统一链接卡渲染策略，grep 无对应实现 |
| 7 | 竖屏 WiFi 预载队列 | `feature/video/ui/pager/PortraitWifiPreloadQueue.kt` | 无对应实现 |
| 8 | 搜索清空保持键盘焦点（#901）+ 搜索动效预算 | `SearchScreen.kt`、`SearchMotionBudgetPolicy.kt` | Windows 搜索自成交互，此修复未同步 |

### A2. 交互与行为缺项

| # | 缺项 | 说明 |
| --- | --- | --- |
| 9 | 合集简介展开收起修复（长简介可展开、短简介隐藏无效入口；文字不抢占展开点击） | Windows 合集简介 .7 已接回，v0.3.5 修复未同步 |
| 10 | 稍后再看排序、搜索与筛选栏位置调整（筛选栏支持拖拽切换） | `WatchLaterScreen.kt`，Windows 有稍后再看（`DesktopWatchLaterEnvironment`），增量未同步 |
| 11 | MD3 首页卡片跟随刷新指示器实时位移 | `HomePullRefreshUiPolicy.kt`、`HomeScreen.kt` |
| 12 | 宽屏功能栏音质/竖屏/画质增强入口 + 更多菜单播放器设置/章节列表 | Windows 章节与播放设置已有，入口结构是 v0.3.5 新布局 |

### A3. 液态玻璃与视觉缺项（对应"液态玻璃 UI 优化"目标）

| # | 缺项 | 上游文件 |
| --- | --- | --- |
| 13 | 播放器原生液态玻璃 chrome：采集视频/封面/弹幕/字幕背景，横屏标题与操作 dock、中央播放、底部功能栏、玻璃进度条（仅拖动显示圆点），保留 HDR/画质增强不支持场景回退 | `PlayerChromeLayout.kt`、`PlayerControlGlass.kt`、`CenterPlaybackControl.kt`、`VideoPlayerProgressContent.kt`、`ChromeBackdropSource.kt` |
| 14 | 全局液态玻璃统一 chrome：底栏材质/高光绘制/按压回弹参数统一，圆形顶部按钮拖拽反馈，动态侧边栏返回按钮补齐 | `AppElasticPress.kt`、`AppPressHighlightMotion.kt`、`AppLiquidValueControls.kt`、`BiliPaiTopBarIconDecorator.kt`、`DynamicSidebar.kt` |
| 15 | MD3/Miuix 音量与亮度反馈新布局（顶部横向、弹性位移与模糊、无百分号） | `GestureLevelOverlay.kt`（v0.3.4 CHANGELOG） |
| 16 | PiliPlus 弹幕图标与发送入口（电视框+弹字+发送箭头）、弹幕颜色选项 | `piliplus_custom_icons.ttf`、`PiliPlusDanmakuSendIcon.kt`、`DanmakuColorOptions.kt`、`DanmakuSendDialog.kt` |
| 17 | 颜色选择组件（弹窗/数字/时长选择主题分流的一部分） | `AppColorPicker.kt` |
| 18 | AmbientFrame 氛围帧组件化（v0.3.5 新增 Controller/Policy/PlayerBinding/README） | Windows 已有 `captureAmbientFrame` 基础（`DesktopOriginalVideoSection*Platform.kt`），完整组件未接 |
| 19 | 音乐播放流动背景（PlayerFlowBackground/AppleCoverFlowBackground） | Windows 已有 Halcyon CPU 帧算法（`DesktopMusicRaster.kt`），v0.3.5 增量未同步 |
| 20 | BlurRenderEffectCache 模糊效果复用（#899，评论/动态覆盖图层） | Windows 弹窗玻璃走原 FloatingDock/Lens 路径，此缓存复用为 Android Compose 专属，可评估是否有等价优化点 |

## 三、v0.3.4 正式版（beta.2 → 正式版）未覆盖增量

以下为 v0.3.4-beta.2 之后、未进入采用清单且 Windows 端无等价实现的部分：

| # | 缺项 | 上游文件 | Windows 现状 |
| --- | --- | --- | --- |
| 21 | 弱网调度：视频详情加载优化 + 网络压力观察 + 加载时间线 + 播放错误通知（PR #895/937d99b7b） | `VideoNetworkPressureObserver.kt`、`VideoLoadTimeline.kt`、`VideoDetailPlaybackErrorNotice.kt`、`VideoDetailUiState.kt` | 详情加载已有，弱网压力观察无对应实现 |
| 22 | 动态评论预览开关（PR #894，DynamicCommentPreviewPolicy + DynamicCommentSheet） | `DynamicCommentPreviewPolicy.kt`、`DynamicCommentSheet.kt` | 动态评论无预览开关（视频评论图片预览已有，二者不同） |
| 23 | 直播组件批量重构：封面保存、竖屏展示策略、贡献榜、发弹幕面板、播放器控制组件、聊天面板 tab 恢复 | `LiveCoverSaveDialog.kt`、`LivePortraitPresentationPolicy.kt`、`LiveContributionRankSheet.kt`、`LiveSendDanmakuSheet.kt`、`LivePlayerControls.kt`、`LivePortraitChrome.kt`、`LiveLandscapePlayerControls.kt`、`LiveRoomLayoutPolicy.kt`、d325aae1a | 直播业务已接（v030-live-* 切片：弹幕发送/SC/删除已有），这批 UI 组件与竖屏布局重构未同步 |
| 24 | 表情面板 Sheet（评论编辑器新表情面板） | `EmotePanelSheet.kt` | 评论编辑器已有 v0.2.9 原表情输入，v0.3.4 新面板未接 |
| 25 | 取流缓存策略与目录请求缓存增量 | `PlayUrlCachePolicy.kt`、`OnDemandRequestCache.kt`、`VideoCatalogRepository.kt`、`VideoRequestAccountScope.kt` | `DesktopPlaybackCache`/`DesktopOriginalVideoRequestCaches`/`DesktopCatalogInvocation` 已有，增量未逐项核对 |
| 26 | 播放器手势处理重构 | `PlayerGestureHandler.kt` | Windows 无对应（鼠标交互，评估是否适用） |
| 27 | 动态设置存储与延迟启动策略 | `DynamicSettingsStore.kt`、`DynamicDeferredStartupPolicy.kt`、`DynamicTopBar.kt` | 动态页激活信号已接（PARITY 记录），这批策略文件未同步 |
| 28 | 番剧播放器功能组件变化 | `BangumiPlayerFeatures.kt` | 番剧播放已接（v034-bangumi-quality 等），此组件增量未核对 |
| 29 | NextWatch 拆分重构（NextWatchSuggestion/NextWatchOverlay 独立文件） | `feature/video/playback/next/*`、`feature/video/ui/overlay/NextWatchOverlay.kt` | NextWatch 已接 .57/.60，此重构版本未同步 |
| 30 | 设置目录重组（设置分类合并重复入口、玻璃高级设置/播放诊断/应用信息位置调整） | `SettingsAdditionalDirectory.kt`、`PlaybackSettingDirectory.kt`、`HomeFeedSettingsSection.kt` | 与历史待办"设置树 38 state/53 actions 未接完"重叠 |

v0.3.4 beta.1 中的蓝雪女仆显示开关（`DesktopMaidMotionRuntime` 已有基础）、列表可拖动滚动条（侧栏已有）属增量调整，优先级低。

## 四、历史明确待办（PARITY/README 记录，按主任务目标归类）

**NVIDIA 增强目标：**
- 运动 DLSS 尚未接入（.39/.44 记录；RTX VSR 与 RTX Video HDR 已接并有本机管线检查）。
- RTX 5080 实机画面、HDR 呈现、光纤 WASAPI 独占听音需实机验收（验收类，非开发缺项）。

**WASAPI 独占音频目标：**
- 独占/共享切换已有隔离原生验证（三场景通过）；真实主程序双播放器切换、设备占用错误、位完美输出待实机验收（验收类）。

**自动更新闭环目标：**
- 自动发布默认关闭，门槛 = 完整功能对齐 + 首次发布验证；上游检查每 10 小时、客户端每 6 小时、更新准备 READY/CLAIMED 持久化（.61）已就位。剩余：完成功能对齐后打开 `BILIPAI_WINDOWS_AUTO_PUBLISH` 并通过首次发布验证（主任务职责）。
- 桌面部署版本 .56 落后源码 .61（部署由主任务统一处理）。

**其他长期待办：**
- 完整 ElegantVideoCard 未移植；设置树完整 38 state/53 actions 消费者未接完。
- 本地离线文件投屏（DLNA）未实现；Android 备份格式迁移未实现；皮肤/外部包资产未纳入 WebDAV 备份。
- 直播 Media3 behind-live-window 事件无 MPV 映射。
- 弹幕指令互动、避挡、GIF/APNG 多帧；音乐 gRPC 多 BGM、外部歌单、沉浸/黑胶视觉。
- 插件 CDN prefetch 暂未开放；GraalJS 许可清单 `complete:false`。

## 五、职责边界确认

- 主任务正在进行（未提交，revision 62）：v0.3.4 PGC（番剧/课程）音频下载——本审计未触碰 `DesktopOriginalBangumiDownloadCapture.kt`、`v034_pgc_audio_download.py`、`v034-pgc-initial-detail/`、`upstream-sources.json`、`sync-upstream.py`、`compatibility-source-inputs.json`、`extract-upstream-*.py`、`build.gradle.kts`、PARITY.md、README.md。
- 公共接口、全局构建配置、版本号、CI、更新记录、桌面部署归主任务。
- 建议后续切片顺序（供主任务排期参考）：先 A1 核心功能（收藏夹封面缓存、Story 分页补量、导航 CID 策略、自动续播开关、动态链接卡），再 A3 液态玻璃 chrome（与主任务 UI 优化目标合并做），直播组件重构与弱网调度随后。

## 六、未验证事项

- 本审计为源码级对比 + grep 验证，未编译、未运行任何测试、未启动客户端窗口、未操作真实账号。
- 路径级"未在清单"不完全等于"功能未实现"：Windows 端部分功能以 `Desktop*` 自有文件实现（已逐项 grep 验证并标注现状），但行为级等价性（例如 Story 分页补量逻辑是否已在 Windows 实现中修复）需主任务按切片逐一核对。
- v0.3.5 中 Android 专属组件（手势、键盘、预测返回、Haze DSL、前台服务）不适用 Windows，已从缺项中排除或降级。
