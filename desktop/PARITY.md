# Windows 源码对齐与验收清单

目标是对齐固定上游 `v0.2.3-alpha.9` / `fcf84853b287662e8a9129ea0d38576c36522a34` 的实际能力。上游 APK 不参与 Windows 运行。用户已确认上一测试版普通视频能播放；该确认不代表其他模块完成。

状态区分：**实现并单测**表示代码及相应离线测试通过；**整合中**表示有实际代码但尚需接入或验证；**待移植**表示仍有工作。接口与模型复用、编译通过、目录入口存在都不能单独证明功能对齐。账号写操作需要通过界面由用户主动执行，开发测试使用隔离数据与模拟返回，不操作用户账号。

来源以 `upstream-sources.json` 的 LF SHA-256 为准。构建从原始文件复制纯 Kotlin 源，或生成选定原算法；Windows 负责网络会话、文件路径、libmpv、FFmpeg 和桌面窗口等平台部分。`tools/source-parity-report.py` 会逐一核验来源摘要，并检查全部生成 Retrofit 接口是否与上游逐字一致，还会列出每个上游 feature 目录尚未采用的 Policy。此报告是来源审计，不是完成百分比。

| 范围 | 原源码锚点 | 当前 Windows 状态 | 尚需对齐与验证 |
| --- | --- | --- | --- |
| 网络与数据模型 | `core/network/ApiClient.kt`, `data/model/response/*` | 全部 Retrofit 接口和响应模型纳入构建，实际使用原 WBI/播放请求策略 | 请求到行为的完整对应；客户端会话、重试与平台能力验收 |
| 推荐、热门、分区与列表 | `feature/home`, `feature/list` | 推荐/热门可浏览；刷新分页 | 分区/排行筛选、预览、单列/瀑布流设置、来源页恢复 |
| 搜索 | `feature/search`, 原 `SearchType` | 九类搜索、热搜/建议和结果路由已实现 | 本地搜索历史、筛选完整度与真人账号结果验收 |
| 动态 | `feature/dynamic`, `DynamicRepository` | 真实动态流/详情、图片、评论、转发 | 发布/图片上传已接，写操作单测通过；投票、图片保存、完整卡片交互与回归 |
| 私信与通知 | `feature/message`, `MessageSendPayloadFactory.kt` | 回复/@/赞/系统/会话历史；发送/图片/撤回/已读已接并单测 | 富内容跳转、账号验证与未知消息类型展示 |
| UP 空间与合集 | `feature/space`, `SpaceApi` | 投稿/动态/资料/关注；合集原接口已实现 | 合集/系列入口已接；收藏子页、搜索筛选、粉丝列表原能力核对 |
| 历史、收藏、稍后再看、点赞 | `feature/list`, `feature/watchlater` | 云端原业务类型列表、收藏夹管理；本地续播迁移测试通过 | PGC/直播/文章/课程与合集路由已接；删除/批量/播放队列与账号验收 |
| 普通视频取流 | `VideoLoadPolicy.kt`, `VideoResponse.kt` | 真实 DASH/单段与多段 MP4；原画质/音轨选择，保留完整详情与服务器画质 | CDN/编码失败切换、UGC 合集与高级媒体验收 |
| 播放控制 | `PlaybackSpeedPolicy.kt`, `PlayerKeyboardPolicy.kt` | 原生暂停/进度/0.1–8倍/音量/静音/音轨/主副字幕/截图/分P/循环；新原生测试已确认字幕/浮窗/系统媒体同步；多段连续播放/跨段跳转实际通过 | 在线字幕选择已接；默认自动选择、进度预览、手势、全屏影院/锁定与所有快捷键 |
| 弹幕 | `DanmakuProto.kt`, 原分段/过滤/高级模型与 parser | 标准 protobuf/XML、高级 JSON 弹幕、原窗口/过滤、离线分段，针对性测试通过 | 指令互动、避挡、发送/点赞及直播弹幕验收 |
| 后台/PiP/系统媒体控制 | 原 `MusicPlaybackContract`, PiP/player policies | 页面外迷你画面；听视频独立常驻原生会话 | Windows SMTC 与独立浮窗还原实际通过；媒体页浏览保活与完整队列控制 |
| 听视频 | `feature/audio/lyrics`, `library`, 原 `PlaylistManager` 纯策略 | 真实音轨、队列/随机历史、持久收藏/最近、歌词与双字幕、睡眠定时已整合 | 队列/恢复/账号释放的 4 项测试通过；完整原生音频流程、外部歌单、沉浸/黑胶视觉 |
| 视频笔记/AI | `feature/video/note`, `VideoNoteContentCodec.kt`, `AiSummaryResponse.kt` | 私有/公开读取、AI/章节；原富文本/时间戳写入已接并单测 | 编辑/删除/公开分享和时间戳跳转已接；真人账号验收 |
| 番剧/影视/课程 | `BangumiRepository.kt`, `BangumiIndexFilterPolicy`, PGC/PUGV APIs | 原 PGC 校验/fallback/季与分集/index；实际播放屏幕 | 原追番/状态、时间表/筛选、课程、权益与指定分集续播已接；多段原生播放与真人验收 |
| 直播 | `feature/live`, `LiveApi` | 真实分区/搜索/关注/HLS与品质选择 | 原 WebSocket/Brotli 协议、鉴权、实时弹幕/SC期限/删除/发送已接并测试；线上连接生命周期 |
| 下载与离线 | 原 `ResumableAssetDownloader`, Download/Offline policies | 原续传/分片/队列/任务/资产、离线弹幕与续播；实际 Kotlin FFmpeg 双轨/音频/单轨合并解码通过 | 整包真实 FFmpeg 的六种合并输出通过；批量选择、存储管理与真实下载验收 |
| 内置插件与增强 | `feature/plugin` 实际注册的原十二个插件/策略 | 待移植 | 空降、广告、CDN、Anime4K、护眼、今日推荐、初见、弹幕增强等 |
| JSON/包插件、皮肤 | `plugin-sdk`, `feature/plugin/js` | 待移植 | 原解析/预览/安装/启停/授权与包验证；原上游预览边界保持一致 |
| DLNA/Google Cast | 原 `feature/plugin/dlna`, `googlecast` | 待移植 | Windows 发现与控制适配、真实接收设备验收 |
| 登录与多账号 | `feature/login`, `TokenManager` | Web/TV 扫码、密码/短信/真实浏览器验证、多账号与 DPAPI 已接并单测 | 实际账号登录/验证码与切换验收；账号缓存 owner 回归已通过 |
| WebDAV与数据备份 | `feature/settings/webdav`, 原 Backup policies | 待移植 | Windows 文件/会话备份恢复适配、失败提示与实际服务验收 |
| 外观、本地化、大屏 | `design-system`, `settings-core`, `feature/settings` | 桌面双栏、浅/深色 | 系统主题、原当前两套运行时样式/壁纸/取色/玻璃/皮肤、简繁英、导航与键盘可达性 |
| 诊断与隐私设置 | 原诊断/遥测与设置 | Windows 本地诊断已有基础 | 用户开关、日志脱敏、诊断导出与上游设置语义核对 |
| 跟随更新 | `desktop/tools` 与 Windows updater | 既有更新监测保留，自动发布关闭 | 按用户要求先完成上述对齐，再扩大完整功能验证和更新机制 |

当前验证记录：138 份上游来源摘要审计通过，全部 303 个 Retrofit 方法声明逐字一致，45/45 响应文件采用。针对性 Kotlin 回归共 113 个不同测试通过（54 + 58，其中原 3 个多段测试扩为 4 个并单独通过）；Python 脚本 59 项通过。实际打包的固定 FFmpeg/FFprobe 六种合并输出均已完整解码。Windows 打包原生播放器整轮通过：实际画面和声音设备、SMTC 元数据/状态/进度读回、主副字幕、表面重建、独立浮窗/还原、静音/只音频、循环/复播、多段持续播放/跨段跳转/整段 EOF、故障恢复、续播与源所有权。访客网络检查在视频详情阶段返回 HTTP 412，未通过完整线上网络门槛；没有把它标记为发布通过，也没有测试用户账号写操作。完整功能对齐仍未完成。
