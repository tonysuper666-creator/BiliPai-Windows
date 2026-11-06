# BiliPai Windows 桌面使用逻辑审计

审计卡 TON-1244。目标是让 BiliPai 用起来像原生 Windows 客户端，而不是放大的安卓界面。真机为 5120×2160、165 Hz、150% 缩放、RTX 5080。

## 范围与方法

- **基线**：落点分支 `desktop/integration-263-claude-main` 的 `572f6da99`（PR #86 合并后）。下文路径都相对仓库根。`K/` 是 `desktop/src/main/kotlin/com/bilipai/desktop/` 的缩写，`app/` 是 `app/src/main/java/com/android/purebilibili/` 的缩写。
- **页面**：首页推荐、视频详情和播放、番剧、搜索、动态、消息、个人中心、设置、登录。
- **检查面**：键盘、鼠标、窗口、文字、系统集成。
- **证据**：每条都给出代码行。本轮没有新截图：所有结论都能从代码直接读出；需要实机确认的条目列在「待核实」，交给对应子卡在 winvm 上做「改前 + 改后」各一轮。
- **期望行为的依据**，按优先顺序：
  1. 本项目的设计规范 v2（TON-1033，`desktop-design-spec-v2`，下称「规范」）和 `desktop/tools/ux/desktop-ux-contract.json`（下称「合约」）。这两份已经定下的就照它们写，不另起炉灶。
  2. 微软 Windows 应用设计指南（链接见文末，本轮已逐页打开核对原文）。文中标「原话」的，是英文原文的中文转述，意思不增不减。
  3. B 站网页版、PC 客户端的公开行为。没有在线逐条核对的，标「推断」。
- **优先级**：
  - P0：常用路径上违背桌面基本预期，用户一上手就会觉得「这是手机界面」或操作落空。
  - P1：常用路径上明显不顺手，但有绕行办法。
  - P2：次要路径，或已有替代。
  - P3：打磨。
- **工作量**：S 不超过半天，M 1–2 天，L 2 天以上（按单个 agent 估算）。
- **归属**：已有卡的写卡号；没有卡的 P0/P1 新开子卡 S1–S5（见「新开子卡」一节）；P2/P3 只记在本文档，不开卡。

## 结论摘要

表内共 45 条：缺口 35 条，已达标 7 条，待核实 2 条，另有 1 条是规范与微软指南的分歧（只记录）。「待核实」一节另列 8 个需要实机确认的问题。

| 优先级 | 缺口数 | 其中已有卡覆盖 | 新开子卡覆盖 | 只记文档 |
|---|---|---|---|---|
| P0 | 2 | 0 | 2（S1） | 0 |
| P1 | 17 | 8 | 9（S1–S5） | 0 |
| P2 | 8 | 3 | 0 | 5 |
| P3 | 8 | 1 | 0 | 7 |
| 合计 | 35 | 12 | 11 | 12 |

最影响「像不像桌面软件」的三件事：

1. **播放画面不认鼠标**（S1）：单击不暂停、双击不全屏（P0），滚轮不调音量（P1）。合约早已定义了这三个开关（`K/appearance/DesktopWindowsUxModel.kt:94-96`），但生产代码一处都没读。
2. **文字几乎都不能直接选中复制**（P1，S2）：评论区一条都不能选；动态正文要长按弹窗才能选；私信气泡不能选。视频标题也不能选，归 K5 重建信息区时一起做。
3. **键盘焦点走不进内容区**（P1，K3/U4 已覆盖）：首页卡片用 `detectTapGestures` 打开（`app/feature/home/components/cards/VideoCard.kt:1446-1449`），Tab 走不到，也不能回车打开。

## 一、键盘

| 编号 | 页面 | 现状（证据） | 期望行为（依据） | 级 | 量 | 归属 |
|---|---|---|---|---|---|---|
| A1 | 首页、搜索、历史、收藏 | 卡片用 `detectTapGestures` 打开（`app/feature/home/components/cards/VideoCard.kt:1446-1449`），没有 `focusable`，Tab 走不到，回车打不开 | Tab 进网格，方向键在卡片间移动，Enter 打开，Shift+F10 弹菜单，焦点环可见（规范 §4 第 4 条、§8「焦点顺序」；微软「可操作的控件都应是 tab stop」「多列网格四个方向键都能导航」，原话） | P1 | M | K3 TON-1054（首页）、K4 TON-1055（其余列表）、U4 TON-1007 #6（全局焦点环） |
| A2 | 播放 | 播放器动作里 Esc 分层、视频信息、字幕浮层、逐帧、右键菜单、快捷键帮助全部直接返回 `false`（`K/ui/DesktopPlayerShortcutEffects.kt:143-144`） | 合约里列出的播放器快捷键都要能用；Esc 先关最上层浮层，再退全屏，再返回（规范 §8） | P1 | M | K5 TON-1056（分层 Esc、输入隔离）、U2 TON-1006（控件） |
| A3 | 输入框（搜索、评论、私信） | 根键盘处理器挂在冒泡阶段（`K/DesktopShell.kt:1871`），Esc 分支用 `!searchFocused` 防止误返回（`:1774`）。但 `searchFocused`（`:755`）从来没有被赋值，这道防线是死的。文本框不消费 Esc 时，Esc 会被当成「返回上一页」 | 光标在输入框里时，Esc 只取消输入（关建议、失焦），不翻页（微软：「Esc 只影响临时 UI，不关闭、不后退应用界面」，原话）。是否真会误返回，待 UI 测试复现 | P1 | S | **S3**（新） |
| A4 | 登录 | 「账号与登录」对话框（`K/ui/AdvancedLoginDialog.kt:63`）的账号、密码框都没有 `keyboardActions`（`:181-184`），输完按回车没反应，只能用鼠标点「登录」 | 在账号框或密码框里按 Enter 就提交，等同点「登录」（网页登录表单的通行做法，推断） | P1 | S | **S3**（新）；对话框外观仍归 K9 TON-1062 |
| A5 | 评论、动态、私信输入 | 评论输入用自绘 `JTextPane`（`K/ui/DesktopInlineEmotePane.kt:63-67`），没有 Enter/Ctrl+Enter 发送绑定 | Ctrl+Enter 发送；输入法组合期间不发送，发送中不重复发（规范 §8「本规格新增的三个键」） | P1 | M | K5 TON-1056、K6 TON-1057、K7 TON-1058 |
| A6 | 播放页 | 播放页只放行返回、全屏、帮助、切窗格四类壳层快捷键（`K/ui/DesktopShellShortcuts.kt:99-108`），所以合约里的 Ctrl+K / Ctrl+F 搜索（合约 `:848-849`）和 Ctrl+1…9 切页在播放页不生效 | 播放页顶栏有 360 dp 搜索框（规范 §2.3），Ctrl+K/F 是全局快捷键（规范 §8），在播放页也应聚焦它 | P2 | S | K5 TON-1056（顶栏） |
| A7 | 搜索 | 搜索相关代码里没有 `Key.DirectionDown` 处理（全仓库 grep 无结果），推断搜索建议列表不能用 ↑↓ 选择 | ↑↓ 在建议间移动，Enter 搜所选项，Esc 关建议（Windows 自动建议框的通行做法，推断）。现状待核实 | P2 | S | K4 TON-1055 |
| A8 | 全局 | 鼠标侧键只处理「后退」（`K/ui/DesktopShellShortcutHost.kt:290-296` 只看 `isBackPressed`），没有前进 | 规范 §8：前进键只在有前进历史时生效。应用目前没有前进历史，所以只记 P3 | P3 | S | U4 TON-1007 #7（侧键） |
| A9 | 全局 | 微软指南写明「Esc 不后退应用界面」，规范 §8 则规定「Esc 先关最上层，再返回」。两者不一致 | 以规范为准（TON-1033 已定）。这里只记录分歧，不算缺口 | — | — | 不开卡 |

## 二、鼠标

| 编号 | 页面 | 现状（证据） | 期望行为（依据） | 级 | 量 | 归属 |
|---|---|---|---|---|---|---|
| B1 | 播放 | 在画面上按下鼠标只会显示控制条，然后把焦点要回视口（`K/ui/DesktopWindowsVideoPhysicalLeaf.kt:306-313`）；mpv 原生窗口只绑了 `MBTN_LEFT → reveal`、`MOUSE_MOVE → motion`、`SPACE → toggle`（`K/player/MpvNativeWindowInput.kt:17`）。单击画面不会暂停 | 单击画面切换播放/暂停（合约 `click_action`，`desktop/tools/ux/desktop-ux-contract.json:104`，默认 PAUSE，见 `K/appearance/DesktopWindowsUxModel.kt:95`；B 站网页播放器同样如此，推断）。离线播放器也一样：左键松开只显示控制条（`K/ui/DesktopOfflinePointerInput.kt:190-194`） | P0 | M | **S1**（新） |
| B2 | 播放 | 双击画面不会全屏。`doubleClickAction = FULLSCREEN`（`K/appearance/DesktopWindowsUxModel.kt:96`）有定义，但除了这个类本身（`:66-68`、`:94-96`），全仓库没有任何地方读它 | 双击画面切换全屏，容差 6 dp（合约 `double_click_action` `:111`、`pointer.double_click_tolerance_dp` `:809`）；双击后的播放状态和双击前一样，不被第一下单击顺带翻掉（主流桌面播放器的做法，推断） | P0 | M | **S1**（新）；「即时、不黑屏」的画质要求仍归 U2 TON-1006 #12 |
| B3 | 播放 | 画面上滚轮不调音量。`wheelAction = VOLUME`（`:94`）同样没人读。全仓库处理滚轮事件（`PointerEventType.Scroll` / `MouseWheel`）的只有直播弹幕、刷新按钮、表情输入、对话框和缩放输入几处，播放器没有；mpv 自带的默认绑定也已关掉（`K/player/MpvPlayer.kt:2000`，`input-default-bindings = no`），所以滚轮在画面上什么都不做 | 画面上每格滚轮音量 ±5；进度条上每格 ±5 s，按住 Shift ±10 s（规范 §8「滚轮」） | P1 | S | **S1**（新） |
| B4 | 首页卡片 | 右键菜单只有「稍后再看 / 取消收藏 / 不感兴趣」三项（`desktop/tools/ux_home_card.py:38-40`） | 规范 §4 第 5 条定了五项：稍后再看、收藏、复制链接（Ctrl+C）、在小窗播放，分隔线下是不感兴趣 | P1 | S | K3 TON-1054 |
| B5 | 评论、动态、视频区 | 只有导航栏、首页卡片和直播弹幕有右键处理（`isSecondaryPressed` / `desktopCardSecondaryClick` 只出现在 `K/ui/DesktopWindowsNavigationRail.kt`、`K/ui/DesktopCardPointer.kt`、`K/ui/DesktopCardInteractionPolicy.kt`、`K/ui/DesktopLiveChatMessages.kt`）。评论、动态卡片和视频区没有右键菜单 | 右键只作用于光标下的对象；Shift+F10 和菜单键打开同一个菜单（规范 §8「右键」；微软：上下文菜单「挂在单个元素上、右键唤出」，原话） | P1 | M | U4 TON-1007 #7 |
| B6 | 首页卡片 | 悬停只有高亮效果，没有视频预览；上游的预览只走长按（`app/feature/home/components/cards/VideoCard.kt:1447`，`hasPreviewAction = isLongPressEnabled && onLongClick != null`） | 悬停片刻后在封面上播放预览，或者至少提供不用长按的入口（B 站网页版首页悬停会预览，推断） | P2 | M | 预览本身只记文档；「每个长按都要有替代入口」归 U4 TON-1007 #9 |
| B7 | 播放 | 进度条悬停预览已实现：有雪碧图时显示缩略图加时间，没有时只显示时间（`K/ui/DesktopWindowsVideoSeekPreview.kt:27-58`，挂在 `K/ui/DesktopWindowsVideoPhysicalLeaf.kt:1269`） | — | 达标 | — | 尺寸是否符合规范 §5 第 4 条（232×130 加章节名）归 U2 TON-1006 #8 |
| B8 | 全局 | 全仓库没有 `isTertiaryPressed`，中键没有任何作用 | 应用没有多窗口和多标签，中键「在新窗口打开」没有落点，只记 P3 | P3 | M | 只记文档 |
| B9 | 全局 | 滚轮速度和惯性是否符合 Windows「每格三行」与平滑滚动，需要实机对比 | 待核实，参照 U5 TON-1001 的结论 | — | — | 待核实 |

## 三、窗口

| 编号 | 页面 | 现状（证据） | 期望行为（依据） | 级 | 量 | 归属 |
|---|---|---|---|---|---|---|
| C1 | 播放 | 进全屏时只在窗口处于 `Floating` 状态才记住原边界；退出全屏一律回到 `Floating`（`K/ui/DesktopWindowsFullscreenControl.kt:43-56`，`destination = if (enabled) Fullscreen else Floating`），文件里没有记录全屏前状态的变量。最大化 → F11 → Esc 之后，窗口变成普通窗口 | 退出全屏回到进入前的状态：最大化就回最大化，普通窗口就回原位置和原尺寸（Windows 播放器的通行做法，推断） | P1 | S | **S1**（新，和双击全屏同一入口） |
| C2 | 番剧 | 番剧主页整体套了 `responsiveContentWidth()`（`desktop/upstream-slices/v046-bangumi-hub-pages/BangumiScreen.kt:328`），默认上限 800 dp（`app/core/util/WindowSizeUtils.kt:566-574`）。在 5120 宽的屏幕上，内容挤在中间一条窄带里。对照：个人空间已经改为按窗口宽度算上限（`desktop/upstream-core/patches/app/src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt.patch:633-635`，`maxWidth = adaptiveLayoutSpec.listContentMaxWidthDp`） | 番剧主页和首页一样铺满内容区，列数跟随窗口宽度（可复用 `K/ui/DesktopHomeFeedLayout.kt:80` 的 `desktopResolveHomeFeedGridColumns`） | P1 | M | **S4**（新） |
| C3 | 播放 | 详情栏宽度是 `minOf(360.dp, maxWidth * .43f)`（`K/ui/DesktopWindowsVideoPhysicalLeaf.kt:1163`），宽屏上偏窄；左上角是手机式的 44 dp 返回箭头（`:1169-1171`） | 面板宽度先取窗口宽的 22%，夹在 340–480 dp，横向有富余时最多到 560 dp（规范 §5「布局策略」）；顶栏放 36 dp 返回按钮，旁边一行「返回 Alt+←」（规范 §2.3、§5 第 1 条） | P1 | L | K5 TON-1056 |
| C4 | 全局 | 首次启动的默认窗口是 1360×900（`K/appearance/DesktopWindowsUxWindowBounds.kt:126`） | 默认 1600×1000，按显示器可用区收缩（规范 §2.1） | P2 | S | K1 TON-1052 |
| C5 | 全局 | 主窗口标题栏已跟随应用深浅色：根窗口挂载时装上 `DesktopWindowsProfileChrome`（`K/ui/DesktopReadyOriginalRootMount.kt:225-228`），用 DWM 属性 20 切换深色标题栏（`K/ui/DesktopProfileWindowChrome.kt:30-37`） | — | 达标 | — | 实机效果由 K1 TON-1052 验收 |
| C6 | 全局 | 首页在 165 Hz 下空闲 CPU 偏高：首页一直挂着帧时钟（`K/ui/DesktopHomeWindowGlobals.kt:43`、`K/ui/DesktopHomeWindowPlatform.kt:69-76`） | 静止时不重绘，空闲 CPU 接近 0 | P1 | M | TON-1027 |
| C7 | 小窗 | 小窗是 `isResizable = false` 的 `JFrame`（`K/player/PictureInPictureController.kt:258`），只能用右下角的拖柄缩放（`:325-337`），拖不动窗口边缘；文件里没有保存位置和大小的逻辑，每次打开都回到默认位置 | 边缘可拖动缩放，记住上次的位置和大小（Windows 播放器小窗的通行做法，推断） | P3 | M | 只记文档 |
| C8 | 对话框 | 仓库 README 自述「跨不同 DPI 显示器移动独立对话框仍待处理」（`desktop/README.md:163`） | 对话框拖到另一块缩放比例不同的屏幕后，按那块屏幕的缩放重排 | P2 | M | 只记文档；真机是单屏，优先级不高 |
| C9 | 全局 | 窗口尺寸和位置记忆已实现：保存边界和最大化状态，启动时按现有显示器夹取（`K/appearance/DesktopWindowsUxWindowBounds.kt:124-140`）；最小尺寸随缩放计算（`K/Main.kt:233`） | — | 达标 | — | — |

## 四、文字

| 编号 | 页面 | 现状（证据） | 期望行为（依据） | 级 | 量 | 归属 |
|---|---|---|---|---|---|---|
| D1 | 视频评论、动态评论 | 评论文本是否允许拖选，取决于 `shouldEnableRichCommentSelection(hasInteractiveAnnotations = hasTapHandler)`（`desktop/upstream-slices/v046-comment-list/ReplyComponents.kt:374-377`、`:2452-2463`）。这里的 `hasTapHandler` 看的是「有没有传点击回调」，不是「这条评论里有没有链接」。桌面端总会传 `urlClick` 和 `timeClick`（`K/ui/DesktopWindowsVideoCommentsSection.kt:156-157`），结果**所有评论**都不能直接拖选，只能长按弹窗再选 | 评论正文可以直接拖选、Ctrl+C 复制；链接、@、时间戳照样能点（B 站网页版评论区的行为：网页文字默认可选，推断） | P1 | M | **S2**（新） |
| D2 | 动态 | 动态正文只能长按后在弹出的「选择文本」对话框里选（上游 `app/feature/dynamic/components/DynamicCard.kt:2410-2443`；桌面端把底部弹层换成对话框：`K/ui/DesktopDynamicTextSelectionSheet.kt:9`） | 正文直接可选，不需要先长按 | P1 | M | **S2**（新）；长按的替代入口仍归 U4 TON-1007 #9 |
| D3 | 消息 | 私信气泡用 `EmoteText` 渲染（`desktop/upstream-slices/v029-chat-timeline/ChatScreen.kt:936-938`），外面没有 `SelectionContainer`，不能选 | 气泡文字可选可复制 | P1 | S | **S2**（新） |
| D4 | 播放 | 视频标题是普通 `Text`（`K/ui/DesktopWindowsVideoPhysicalLeaf.kt:1421`），不能选 | 标题可选可复制 | P1 | S | K5 TON-1056（规范 §5 第 5 条信息区由 K5 重建，标题在其中；本卡已在 K5 卡上留话） |
| D5 | 播放 | 视频简介已经包在 `SelectionContainer` 里（`K/ui/DesktopWindowsVideoDescriptionSection.kt:101`）；只有只读兜底视图的简介（`K/ui/DesktopWindowsVideoPhysicalLeaf.kt:1528`）不能选 | — | 达标 | — | 兜底视图只记文档 |
| D6 | 评论、简介、私信 | 外部 http(s) 链接在应用内的 Web 页面打开（`K/DesktopShell.kt:2389-2396`，`BiliPaiNavKey.Web(url)`） | 非 B 站链接默认用系统浏览器打开，或者在右键菜单里提供「在浏览器中打开」（Windows 桌面应用的通行做法，推断） | P2 | S | 只记文档 |
| D7 | 全局 | 选中文字后右键是否有「复制 / 全选」菜单，取决于 Compose 1.12.1 `SelectionContainer` 的默认上下文菜单 | 待核实；S2 验收时一并看 | — | — | 待核实 |

## 五、系统集成

| 编号 | 页面 | 现状（证据） | 期望行为（依据） | 级 | 量 | 归属 |
|---|---|---|---|---|---|---|
| E1 | 直播、离线、小窗、听视频 | 防熄屏只接在点播和番剧上。`DesktopWindowsVideoWakeLease.acquire`（`K/ui/DesktopWindowsVideoWakeLease.kt:21`，用 `PowerSetRequest` 持有 DISPLAY 和 SYSTEM 两种请求）只有 `K/ui/DesktopOriginalVideoWindowsWindowPort.kt:91-94` 的 `acquireKeepAwake` 一个调用方；后者只被点播和番剧的三处用到（`K/ui/DesktopOriginalVideoSectionWindowsPlatform.kt:575`、`K/ui/DesktopOriginalBangumiPlayerRootOwner.kt:528`、`K/ui/DesktopOriginalVideoRootAssembler.kt:208`），直播、离线、听视频的界面代码都不引用它；全仓库也没有 `SetThreadExecutionState`。另外，切到小窗时会主动放掉请求（`K/ui/DesktopOriginalVideoRootPortraitRendering.kt:121`，`keepAwake = keepAwake && !pip`）。所以看直播、看离线视频、开小窗、只听声音时，屏幕和系统照样会按电源计划熄屏、睡眠 | 只要在播放（含直播、离线、小窗、纯音频）就持有请求，暂停或停止就释放（微软：在需要的场景开始前立即 `PowerSetRequest`，结束后立即 `PowerClearRequest`，原话）。纯音频只需要 SYSTEM，不需要 DISPLAY | P1 | S | **S5**（新） |
| E2 | 全局 | 媒体键和系统媒体浮层已接好：`WindowsMediaSourceLease` 处理播放、暂停、停止、上下一个、快进快退 10 秒和拖动（`K/DesktopShell.kt:978-1011`） | — | 达标 | — | 真机验收在 TON-1047 |
| E3 | 全局 | 单实例已实现：第二次启动把激活路由转给已有实例（`K/Main.kt:93-96`，`DesktopSingleInstance.acquire`） | — | 达标 | — | — |
| E4 | 全局 | 主题默认跟随系统（`K/appearance/DesktopThemePrefs.kt:32`） | — | 达标 | — | — |
| E5 | 全局 | 跟随系统主题靠每 250 ms 读一次注册表（`K/appearance/DesktopSystemTheme.kt:18-22`） | 改为监听 `WM_SETTINGCHANGE`，省掉空转 | P3 | S | 只记文档 |
| E6 | 消息 | 消息通知默认关闭（`app/feature/message/notification/MessageNotificationSettingsStore.kt:17`，`enabled = false`） | 桌面端默认开启系统通知，或首次进入消息页时询问一次 | P2 | S | 只记文档（是否改默认值需要产品判断） |
| E7 | 全局 | 点关闭直接退出（`K/Main.kt:214`），下载进行中也不确认。下次启动时，进行中的任务变成「已暂停」（`K/download/DesktopDownloadManager.kt:484`），可以续传（`:367-376`），不会丢已下好的部分 | 有下载在跑时，关闭前确认一次，或者提供「关闭时最小化到托盘」选项 | P2 | S | 只记文档 |
| E8 | 全局 | 启动总是回到首页（`K/DesktopShell.kt:563`） | 可选「启动时回到上次的页面」 | P3 | S | 只记文档 |
| E9 | 全局 | 窗口标题固定为「BiliPai Windows」（`K/Main.kt:217`），任务栏和 Alt+Tab 里看不出正在看什么 | 播放时标题带上视频名 | P3 | S | 只记文档 |
| E10 | 全局 | 没有开机自启和 `bilibili://` 协议注册（全仓库 grep 无结果） | 按需提供，默认关闭 | P3 | M | 只记文档 |
| E11 | 全局 | 托盘图标只在开启消息通知并有未读（`K/ui/DesktopWindowsMessageNotifications.kt:26`、`:70`），或有下载正在进行时出现（`K/download/DesktopDownloadNotifications.kt:184-195`），平时没有常驻托盘 | 提供常驻托盘（打开主窗口、暂停全部下载、退出），和 E7 的「关闭时最小化到托盘」一起做 | P3 | S | 只记文档 |

## 六、逐页速查

| 页面 | 缺口编号 | 已达标 |
|---|---|---|
| 首页推荐 | A1、B4、B6、C6 | 卡片手形光标和右键菜单（`desktop/tools/ux_home_card.py`）、窗口记忆 C9、标题栏跟随主题 C5 |
| 视频详情和播放 | A2、A5、A6、B1、B2、B3、B5、C1、C3、D1、D4、D6、E1（纯音频、小窗） | 进度条预览 B7、简介可选 D5、媒体键 E2 |
| 番剧 | C2、E1（离线番剧） | 番剧播放持有防熄屏 |
| 搜索 | A1、A3、A7 | — |
| 动态 | A5、B5、D1、D2 | — |
| 消息 | A3、A5、D3、E6 | — |
| 个人中心 | — | 个人空间列表宽度按窗口自适应（C2 的对照）；布局改造在 K8b TON-1060 |
| 设置 | — | 左侧导航加限宽内容区，带设置搜索（`K/settings/DesktopSettingsTree.kt:60`、`:83`）。去掉手机专属设置项的工作在 U4 TON-1007 #9 |
| 登录 | A4 | — |

崩溃类问题不在本审计范围内，各有专卡：视频/设置崩溃 TON-1077，消息崩溃 TON-1201。

## 七、待核实

| 编号 | 内容 | 怎么核实 | 交给 |
|---|---|---|---|
| V1 | 单击画面后焦点在 mpv 原生窗口还是 Compose 视口：←→↑↓、F、M 还能不能用。`PhysicalLeaf.kt:310-311` 在按下时会把焦点要回视口，但实机没测过 | winvm：点画面后依次按键，看是否都生效 | S1 |
| V2 | 输入框里按 Esc 是否真的会触发返回（A3） | 先写 UI 测试：文本框聚焦时发 Esc，断言 `requestBack` 没被调用 | S3 |
| V3 | `SelectionContainer` 的默认右键菜单（D7） | S2 验收时在 winvm 里右键已选中的文字 | S2 |
| V4 | 进程是否按显示器感知 DPI（Per-Monitor V2） | winvm 里用任务管理器「详细信息」的「DPI 感知」列看 | 只记文档 |
| V5 | 链接悬停是否变成手形光标 | winvm 截图 | S2 |
| V6 | 全屏时任务栏是否完全隐藏 | winvm 截图 | S1 |
| V7 | 滚轮速度和惯性（B9） | 和 U5 TON-1001 的实测结论对照 | U5 |
| V8 | 搜索建议 ↑↓（A7） | winvm 里实测 | K4 |

## 八、新开子卡

每张卡的边界、写入范围和 winvm 验收画面都写在卡里。这里只列摘要。

| 子卡 | 级 | 链 | 内容 | 覆盖条目 |
|---|---|---|---|---|
| S1 播放画面的鼠标操作和全屏往返 | P0 | engineering | 单击暂停、双击全屏、滚轮调音量和进度；退出全屏回到最大化 | B1、B2、B3、C1；V1、V6 |
| S2 文字能直接选中复制，链接照样能点 | P1 | frontend | 评论、动态正文、私信气泡 | D1–D3；V3、V5 |
| S3 输入框里的 Esc 和回车 | P1 | engineering | 输入框里 Esc 不翻页；登录框回车提交 | A3、A4；V2 |
| S4 番剧主页铺满内容宽 | P1 | frontend | 去掉 800 dp 上限，列数跟随窗口宽度 | C2 |
| S5 看直播、离线视频、小窗和只听声音时不熄屏 | P1 | engineering | 把防熄屏扩展到直播、离线、小窗、纯音频 | E1 |

为什么能拆成独立子卡：五张卡改的是不同文件，各自能单独验证。

- 只有 S1 会碰 `DesktopWindowsVideoPhysicalLeaf.kt`（K5 独占写入的文件），而且只碰指针输入那一小段。K5 还在 backlog，S1 必须在 K5 开工前合入；K5 开工时 S1 还没合入的，剩余改动并入 K5。这个文件同时被 K1 的 PR #28 改动（错误态，不在指针段），S1 在 #28 合入后变基即可。
- S2、S4 要改的评论、动态、私信、番剧代码都来自上游。`desktop/upstream-slices/` 下的原文按哈希锁定（例如 `desktop/tools/v046_comment_list.py:23`、`:34`），不能直接编辑。改动走该页面现有的机制：生成脚本里的替换，或 M0 构建时补丁。每个改动点都要配布局测试或 golden。M4a TON-1002 正在把视频和番剧改成直接编译上游，动手前先确认目标文件当时归哪套机制管，改法要在 M4a 合入后仍然有效。
- S3 碰的 `AdvancedLoginDialog.kt` 也在 TON-1066 的草稿 PR #34 里，但 #34 不涉及键盘提交；`DesktopShell.kt` 也在 PR #8、#18 里，S3 只改 Esc 防线的几行。

## 九、抽检清单（交给异源复核，10 条）

每条都可以只读代码核对，不需要启动客户端：

1. B2：`doubleClickAction` 在生产代码里没有读取者（`grep -rn doubleClickAction desktop/src/main` 只命中 `DesktopWindowsUxModel.kt`）。
2. C1：`DesktopWindowsFullscreenControl.kt:46` 退出全屏的目标固定是 `Floating`。
3. A3：`DesktopShell.kt:755` 的 `searchFocused` 只有声明，没有赋值。
4. D1：`ReplyComponents.kt:2452-2463` 按「有没有传回调」决定能否选中，`DesktopWindowsVideoCommentsSection.kt:156-157` 总会传回调。
5. C2：`BangumiScreen.kt:328` 用 `responsiveContentWidth()`，默认 800 dp。
6. E1：`DesktopWindowsVideoWakeLease.acquire` 只被 `DesktopOriginalVideoWindowsWindowPort.kt:91-94` 调用，后者只被点播和番剧三处用到；`grep -rln -i keepAwake desktop/src/main` 里没有直播、离线、听视频的文件；`DesktopOriginalVideoRootPortraitRendering.kt:121` 在小窗时传 `keepAwake && !pip`。
7. A4：`AdvancedLoginDialog.kt:181-184` 的两个输入框都没有 `keyboardActions`。
8. B4：`ux_home_card.py:38-40` 菜单只有三项。
9. E2（反证）：`DesktopShell.kt:978-1011` 媒体键确实已经接好。
10. E3（反证）：`Main.kt:93` 单实例确实已经实现。

## 参考

- 微软：[Keyboard interactions](https://learn.microsoft.com/en-us/windows/apps/develop/input/keyboard-interactions)，包括 Tab 顺序、方向键内导航、Enter/Space、「Esc 只影响临时 UI」、焦点框、Ctrl+F 查找。本轮已打开核对原文。
- 微软：[Menus and context menus](https://learn.microsoft.com/en-us/windows/apps/develop/ui/controls/menus-and-context-menus)，上下文菜单挂在单个元素上、右键唤出，文字和图片的复制类命令放上下文菜单。本轮已打开核对原文。
- 微软：[PowerSetRequest](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-powersetrequest)，DISPLAY 必须和 SYSTEM 一起持有，场景结束立即清除。本轮已打开核对原文。
- B 站：官方帮助中心「播放器使用指南」（[入口](https://www.bilibili.com/blackboard/help.html)）。页面靠脚本渲染，本轮没能取到正文，所以文中涉及 B 站的期望行为都标了「推断」。
- 本项目：设计规范 v2（TON-1033）、合约 `desktop/tools/ux/desktop-ux-contract.json`。
