# Windows 源码对齐与验收清单

目标是对齐固定上游 `v0.2.3-alpha.9` / `fcf84853b287662e8a9129ea0d38576c36522a34` 的实际能力。上游 APK 不参与 Windows 运行。用户已确认上一测试版普通视频能播放；该确认不代表其他模块完成。

状态区分：**实现并单测**表示代码及相应离线测试通过；**整合中**表示有实际代码但尚需接入或验证；**待移植**表示仍有工作。接口与模型复用、编译通过、目录入口存在都不能单独证明功能对齐。账号写操作需要通过界面由用户主动执行，开发测试使用隔离数据与模拟返回，不操作用户账号。

来源以 `upstream-sources.json` 的 LF SHA-256 为准。构建从原始文件复制纯 Kotlin 源，或生成选定原算法；Windows 负责网络会话、文件路径、libmpv、FFmpeg 和桌面窗口等平台部分。`tools/source-parity-report.py` 会逐一核验来源摘要，并检查全部生成 Retrofit 接口是否与上游逐字一致，还会列出每个上游 feature 目录尚未采用的 Policy。此报告是来源审计，不是完成百分比。

| 范围 | 原源码锚点 | 当前 Windows 状态 | 尚需对齐与验证 |
| --- | --- | --- | --- |
| 网络与数据模型 | `core/network/ApiClient.kt`, `data/model/response/*` | 全部 Retrofit 接口和响应模型纳入构建，实际使用原 WBI/播放请求策略 | 请求到行为的完整对应；客户端会话、重试与平台能力验收 |
| 推荐、热门、分区与列表 | `feature/home`, `feature/list` | 原推荐来源/分页、分区/排行/必看/每周模式与筛选已接；实际列表状态保留，原负反馈与插件过滤已整合并离线测试 | 单列/瀑布流全部设置、刷新总线、真人账号推荐与服务端负反馈验收 |
| 搜索 | `feature/search`, 原 `SearchType` | 九类搜索、热搜/建议、原历史/隐私/筛选策略和结果路由已实现并离线测试 | 真人账号结果与完整交互验收 |
| 动态 | `feature/dynamic`, `DynamicRepository` | 真实动态流/详情、图片、评论、转发 | 发布/图片上传已接，写操作单测通过；投票、图片保存、完整卡片交互与回归 |
| 私信与通知 | `feature/message`, `MessageSendPayloadFactory.kt` | 回复/@/赞/系统/会话历史；发送/图片/撤回/已读已接并单测 | 富内容跳转、账号验证与未知消息类型展示 |
| UP 空间与合集 | `feature/space`, `SpaceApi` | 投稿/动态/资料/关注；合集原接口已实现 | 合集/系列入口已接；收藏子页、搜索筛选、粉丝列表原能力核对 |
| 历史、收藏、稍后再看、点赞 | `feature/list`, `feature/watchlater` | 云端原业务类型列表、收藏夹管理；本地续播迁移测试通过 | PGC/直播/文章/课程与合集路由已接；删除/批量/播放队列与账号验收 |
| 普通视频取流 | `VideoLoadPolicy.kt`, `VideoResponse.kt` | 真实 DASH/单段与多段 MP4；原画质/音轨选择、UGC 合集/播放队列；原错误恢复预算与 CDN/编码失败切换已整合并离线测试 | 实际网络恢复、CDN 启动/卡顿 watchdog、PremiumAudio 与高级媒体验收 |
| 播放控制 | `PlaybackSpeedPolicy.kt`, `PlayerKeyboardPolicy.kt` | 原生暂停/进度/0.1–8倍/音量/静音/音轨/主副字幕/截图/分P/循环；新原生测试已确认字幕/浮窗/系统媒体同步；多段连续播放/跨段跳转实际通过 | 在线字幕选择已接；默认自动选择、进度预览、手势、全屏影院/锁定与所有快捷键 |
| 弹幕 | `DanmakuProto.kt`, 原分段/过滤/高级模型与 parser | 标准 protobuf/XML、高级 JSON 弹幕、原窗口/过滤、离线分段；真实原插件对普通/直播/SC 字体、颜色、时机和隐藏的渲染适配已接并测试 | 实际桌面叠层/护眼像素、指令互动、避挡、GIF/APNG 多帧、BAS 与发送/点赞及直播线上验收 |
| 后台/PiP/系统媒体控制 | 原 `MusicPlaybackContract`, PiP/player policies | 页面外迷你画面；听视频独立常驻原生会话 | Windows SMTC 与独立浮窗还原实际通过；媒体页浏览保活与完整队列控制 |
| 听视频 | `feature/audio/lyrics`, `library`, 原 `PlaylistManager` 纯策略 | 真实音轨、队列/随机历史、持久收藏/最近、歌词与双字幕、睡眠定时已整合 | 队列/恢复/账号释放的 4 项测试通过；完整原生音频流程、外部歌单、沉浸/黑胶视觉 |
| 视频笔记/AI | `feature/video/note`, `VideoNoteContentCodec.kt`, `AiSummaryResponse.kt` | 私有/公开读取、AI/章节；原富文本/时间戳写入已接并单测 | 编辑/删除/公开分享和时间戳跳转已接；真人账号验收 |
| 番剧/影视/课程 | `BangumiRepository.kt`, `BangumiIndexFilterPolicy`, PGC/PUGV APIs | 原 PGC 校验/fallback/季与分集/index；实际播放屏幕 | 原追番/状态、时间表/筛选、课程、权益与指定分集续播已接；多段原生播放与真人验收 |
| 直播 | `feature/live`, `LiveApi` | 真实分区/搜索/关注/HLS与品质选择 | 原 WebSocket/Brotli 协议、鉴权、实时弹幕/SC期限/删除/发送已接并测试；线上连接生命周期 |
| 下载与离线 | 原 `ResumableAssetDownloader`, Download/Offline policies | 原续传/分片/队列/任务/资产、离线弹幕与续播；实际 Kotlin FFmpeg 双轨/音频/单轨合并解码通过 | 整包真实 FFmpeg 的六种合并输出通过；批量选择、存储管理与真实下载验收 |
| 内置插件与增强 | `feature/plugin` 实际注册的原十二个插件/策略 | 十个实际 provider 已接：空降、弹幕增强、护眼、过滤、初见、广告、今日推荐、订阅、CDN、DLNA；原 SDK/配置/管理生命周期与成功 seek 回调已整合并离线测试 | Anime4K 的实际 native shader、Google Cast、今日推荐补充缓存、完整界面与线上插件验收；CDN prefetch 暂未开放 |
| JSON/包插件、皮肤 | `plugin-sdk`, `feature/plugin/js` | 原 JSON 插件解析/规则执行/启停/授权已接并测试；原包/皮肤解析与 Windows 宿主整合中 | 原安装/预览/包验证与安全存储、实际 JS VM、动画/视频皮肤与桌面视觉；原 Kotlin 包预览边界保持一致 |
| DLNA/Google Cast | 原 `feature/plugin/dlna`, `googlecast` | DLNA 原发现/控制/媒体策略与 Windows UDP/SOAP/HTTP 代理、实际对话框已接；Range/HEAD/同连接、四轮发现等 11 项离线测试通过 | 真实接收设备；Google Cast V2 尚未实现，不能将 DLNA 通过当作 Google Cast 完成 |
| 登录与多账号 | `feature/login`, `TokenManager` | Web/TV 扫码、密码/短信/真实浏览器验证、多账号与 DPAPI 已接并单测 | 实际账号登录/验证码与切换验收；账号缓存 owner 回归已通过 |
| WebDAV与数据备份 | `feature/settings/webdav`, 原 Backup policies | 原九个 WebDAV HTTP 方法和调度策略复用；Windows 受限 ZIP/摘要/回滚、旧写入器停止、DPAPI、本地/跨进程锁与设置对话框已接；真实 loopback DAV、恢复和 DST 周期离线测试通过 | 实际 WebDAV 服务与打包的 Windows 调度验收；Android 备份格式迁移未实现；皮肤/外部包资产未纳入 |
| 外观、本地化、大屏 | `design-system`, `settings-core`, `feature/settings` | 桌面双栏、浅/深色 | 系统主题、原当前两套运行时样式/壁纸/取色/玻璃/皮肤、简繁英、导航与键盘可达性 |
| 诊断与隐私设置 | 原诊断/遥测与设置 | Windows 本地诊断已有基础 | 用户开关、日志脱敏、诊断导出与上游设置语义核对 |
| 跟随更新 | `desktop/tools` 与 Windows updater | 既有更新监测保留，自动发布关闭 | 按用户要求先完成上述对齐，再扩大完整功能验证和更新机制 |

Windows revision 7 已归档验证：238 份上游 Kotlin 来源和 11 个原资源摘要审计通过；全部 303 个 Retrofit 方法声明逐字一致，45/45 响应文件采用。全量 Kotlin 共 291 项、43 个 suite：290 通过、0 失败、1 因 Windows 主机不能创建符号链接而跳过；Python 脚本 70 项通过。新打包 EXE 的真实原生整轮验证通过，包括画面/声音、SMTC、主副字幕、浮窗/还原、多段/循环与所有权，并新增实际 seek 完成事件、软件解码恢复保留位置/字幕，以及快速替换和同源恢复隔离。完整可复查记录在 `verification/milestone-0.2.406.7.json`。DLNA 仍仅协议 fixture 通过，WebDAV 仍仅 loopback 服务通过，尚未声称真实接收设备/远端服务/Windows 定时任务验收。

上一已归档里程碑 revision 6：138 个原源、113 个不同 Kotlin 回归和 59 项 Python 脚本测试通过；固定 FFmpeg/FFprobe 的六种合并输出均已实际完整解码。真实打包原生播放器已通过画面和声音设备、SMTC 元数据/状态/进度读回、主副字幕、表面重建、独立浮窗/还原、静音/只音频、循环/复播、多段持续播放/跨段跳转/整段 EOF、故障恢复、续播与源所有权。

访客网络检查在视频详情阶段返回 HTTP 412，未通过完整线上网络门槛；没有把它标记为发布通过，也没有测试用户账号写操作。桌面仍为用户确认普通视频能播放的 revision 5，未将开发中的新增功能当作桌面已交付。完整功能对齐仍未完成。
