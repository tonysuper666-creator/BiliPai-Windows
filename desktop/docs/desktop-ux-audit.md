# BiliPai Windows 桌面使用逻辑审计（第二轮复核）

审计卡 TON-1244。目标是让 BiliPai 用起来像原生 Windows 客户端，而不是放大的安卓界面。

## 0. 本轮更正与边界

**真机归属更正。** 上一轮写的「真机是他朋友那台」是错的。5120×2160、165 Hz、150% 缩放、RTX 5080 这台是**李政自己的 winbox**（2026-11-13 确认）。本文所有涉及这台机器配置的结论都指它。

**与 TON-1014 的边界。** TON-1014 讲的是熟人机器配置各不相同、需要自动适配（不同分辨率、不同缩放、核显、低刷屏）。那是另一件事，本文不提也不合并。本文只审「在既定桌面环境里，操作是否符合 Windows 用户的预期」。

**本轮做了什么、没做什么。**

- 只读代码和已有材料，重写现状、期望、优先级。
- **没有新开实现子卡**，**没有派任何人**，只把上一轮已经开出去的五张子卡（S1–S5，对应 TON-1263–1267）与本文条目对齐并标出合并状态。
- **没有在 winbox 上启动客户端**：TON-1297 正在那台机器上跑跟版的 Windows 轮次，机器不抢。
- 因此**本轮没有新截图**。凡是必须看画面才能定论的，一律进「待核实」，不猜。
- 微软官方文档的三条引用沿用上一轮的核对结果（当时逐页打开过原文），本轮未重新抓取，文中据此标注。

## 范围与方法

- **基线**：`desktop/integration-263-claude-main` 的 `554a89b02`（PR #170 合并后，2026-11-11）。下文路径都相对仓库根。`K/` 是 `desktop/src/main/kotlin/com/bilipai/desktop/` 的缩写，`app/` 是 `app/src/main/java/com/android/purebilibili/` 的缩写。
- **编译模型（引用行号前必须知道）**：桌面端是「上游源码 + 切片」双轨编译，不看清哪一份在编译就会引错文件。
  - `feature/home/`、`feature/search/`、`feature/dynamic/`、`feature/comment/`、`data/` 直接从 `app/` 编译（`desktop/build.gradle.kts:319`，M4b）；`feature/plugin|profile|space|message|settings|login` 同样直接编译（`:334`，M4d），个别文件被 `desktop/upstream-core/m4d/later-steps.tsv` 挡回旧副本。
  - 其余页面仍由 `desktop/upstream-slices/*` 的哈希锁定原文、或由 `desktop/tools/extract-upstream-*.py` 从 `app/` 根源码抽取到 `build/generated/*`，经 Python 生成器注册进 `kotlin.srcDir`。**`feature/video/`（评论 `ReplyComponents.kt` 就在这里）仍走切片**，**`feature/bangumi/` 走抽取**（`desktop/build.gradle.kts:1404-1413`），两者都**不是** `app/` 直编；区别很重要，下面 C2、D1 分别按各自的轨道引用。
  - 上一轮有几条正是错在这里：引了 `app/` 的副本，实际编译的是切片；反过来也有。本文逐条按上面的名单核对过所属轨道。
- **页面**：首页推荐、视频详情和播放、番剧、搜索、动态、消息、个人中心、设置、登录。
- **检查面**：键盘、鼠标、窗口、文字、系统集成。
- **期望行为的依据**，按优先顺序：
  1. 本项目设计规范 v2（TON-1033，`desktop-design-spec-v2`，下称「规范」）与 `desktop/tools/ux/desktop-ux-contract.json`（下称「合约」）。这两份已经定下的照它们写，不另起炉灶。
  2. 微软 Windows 应用设计指南（链接见文末）。标「原话」的是英文原文的中文转述，意思不增不减；本轮沿用上一轮核对结果。
  3. B 站网页版、PC 客户端的公开行为。没有在线逐条核对的标「推断」。
- **优先级**：
  - P0：常用路径上违背桌面基本预期，一上手就觉得「这是手机界面」或操作落空。
  - P1：常用路径上明显不顺手，但有绕行办法。
  - P2：次要路径，或已有替代。
  - P3：打磨。
- **工作量**：S 不超过半天，M 1–2 天，L 2 天以上（按单个 agent 估算）。
- **归属**：已有卡的写卡号；已有子卡的写 S1–S5；P2/P3 里没有卡的只记本文档，不开卡。

## 结论摘要

共 45 个核对项（A5 拆成「评论 A5」「私信 A5′」两行，故表内 46 行）：**缺口 34 条，已达标 9 条，待核实 2 条**，另有 1 条是规范与微软指南的分歧（只记录）。「待核实」一节另列 10 个需要实机确认的问题。

| 级 | 缺口数 | 已有子卡 S1–S5 | 已有专卡 K/U/TON | 只记文档 |
|---|---|---|---|---|
| P0 | 2 | 2 | 0 | 0 |
| P1 | 16 | 8 | 8 | 0 |
| P2 | 8 | 0 | 3 | 5 |
| P3 | 8 | 0 | 1 | 7 |
| 合计 | 34 | 10 | 12 | 12 |

与上一轮的差异（45 条总数不变）：

- **D3 私信气泡改为「达标」**：`app/.../feature/message/ChatScreen.kt:1028` 已经用 `SelectionContainer` 包住文本气泡，且 `feature/message/` 是 M4d 直接编译区，这份文件确实在编译。上一轮引的是切片副本，看漏了。（缺口 35 → 34，P1 17 → 16，达标 7 → 8。）
- **A5 拆开写**：私信输入框的 Ctrl+Enter 已经接上（`desktop/upstream-core/patches/.../ChatScreen.kt.patch:148` 注入 `rememberDesktopMessageSendShortcut`，实现见 `K/ui/DesktopMessageComposerShortcut.kt:23`、`:48`，有 `DesktopMessageSendShortcutTest` 与 `desktop/tools/tests/test_message_split_patches.py:114-126` 双重把关）。**未接上的是视频评论区**。上一轮把两者混成一条，既夸大了缺口也模糊了进度；本轮拆成 A5 与 A5′ 两行（达标再 +1 → 9）。
- **C6 保留 P1 但补了前提**：代码只证明首页常挂帧时钟，不证明「165 Hz 下空闲 CPU 偏高」。结论改为待实测，见 V6。

最影响「像不像桌面软件」的三件事：

1. **播放画面不认鼠标**（S1）：单击不暂停、双击不全屏（P0），滚轮不调音量（P1）。合约早定义了这三个开关（`K/appearance/DesktopWindowsUxModel.kt:94-96`），生产代码一处都没读。
2. **评论和动态正文不能直接选中复制**（P1，S2）：评论区一条都不能选；动态正文要长按弹窗；视频标题不能选。私信气泡已经可以（D3 达标）。
3. **键盘焦点走不进内容区**（P1，K3/K4/U4）：首页卡片用 `detectTapGestures` 打开（`app/.../feature/home/components/cards/VideoCard.kt:1449`），`focusable` 在 `:30` 导入了却从没用过，Tab 走不到，回车也打不开。

## 一、键盘

| 编号 | 页面 | 现状（证据） | 期望行为（依据） | 级 | 量 | 归属 |
|---|---|---|---|---|---|---|
| A1 | 首页、搜索、历史、收藏 | 卡片用 `detectTapGestures` 打开（`app/.../feature/home/components/cards/VideoCard.kt:1449`、`:2009` 处同理），`focusable` 在 `:30` 导入但全文件没有调用，Tab 走不到，回车打不开；只有 `semantics { contentDescription }`（`:2001`、`:2145`） | Tab 进网格，方向键在卡片间移动，Enter 打开，Shift+F10 弹菜单，焦点环可见（规范 §4 第 4 条、§8「焦点顺序」；微软「可操作的控件都应是 tab stop」「多列网格四个方向键都能导航」，原话） | P1 | M | K3 TON-1054（首页）、K4 TON-1055（其余列表）、U4 TON-1007 #6（全局焦点环） |
| A2 | 播放 | 播放器动作里 Esc 分层、视频信息、字幕浮层、逐帧、右键菜单、快捷键帮助全部直接返回 `false`（`K/ui/DesktopPlayerShortcutEffects.kt:143-144`）。Esc 本身的原生分层已经单独做过（`K/ui/DesktopWindowsVideoEscapeInput.kt:64-76`：先关浮层、再退全屏、再交给窗口后退，PR #147） | 合约里列出的播放器快捷键都要能用；Esc 先关最上层浮层，再退全屏，再返回（规范 §8） | P1 | M | K5 TON-1056（分层 Esc、输入隔离）、U2 TON-1006（控件） |
| A3 | 输入框（搜索、评论、私信） | 根键盘处理器里 Esc 分支用 `!searchFocused` 防止误返回（`K/DesktopShell.kt:1782`，播放键分支同样在 `:1788`、`:1790` 用它）。但 `searchFocused` 只有声明（`:759`）**从来没有被赋值**，四行之外无引用，这道防线是死的。文本框不消费 Esc 时，Esc 会被当成「返回上一页」 | 光标在输入框里时，Esc 只取消输入（关建议、失焦），不翻页（微软：「Esc 只影响临时 UI，不关闭、不后退应用界面」，原话）。是否真会误返回待 UI 测试复现 | P1 | S | **S3**（TON-1265） |
| A4 | 登录 | 「账号与登录」对话框（`K/ui/AdvancedLoginDialog.kt`）的账号、密码框都没有 `keyboardActions`（`:181`、`:182`），短信码（`:241`、`:258`）、区号搜索（`:224`）、Cookie 导入（`:274`）同样没有；输完只能鼠标点按钮（登录按钮在 `:184`） | 在账号框或密码框里按 Enter 就提交，等同点「登录」（网页登录表单通行做法，推断） | P1 | S | **S3**（TON-1265）；对话框外观仍归 K9 TON-1062 |
| A5 | 评论 | 视频评论输入用自绘 `JTextPane`（`K/ui/DesktopInlineEmotePane.kt`，Swing 层，无 `KeyStroke`/`InputMap`/`VK_ENTER`），编辑窗 `K/ui/DesktopWindowsCommentComposerWindow.kt:70` 只把 `send` 当按钮回调传进工具栏；`K/.../DesktopCommentEmoteTextField.kt` 也没有按键处理 | Ctrl+Enter 发送；输入法组合期间不发送，发送中不重复发（规范 §8「本规格新增的三个键」） | P1 | M | K5 TON-1056、K6 TON-1057、K7 TON-1058 |
| A5′ | 私信 | **已接上**：`desktop/upstream-core/patches/.../feature/message/ChatScreen.kt.patch:148` 在输入框上挂 `rememberDesktopMessageSendShortcut(text, isBusy, onSend)`；实现 `K/ui/DesktopMessageComposerShortcut.kt:23`、`:48`，单测 `DesktopMessageSendShortcutTest`，补丁完整性检查 `desktop/tools/tests/test_message_split_patches.py:114-126` | — | 达标 | — | 实机验收随 K7 TON-1379 |
| A6 | 播放页 | 播放页只放行四类壳层快捷键（`K/ui/DesktopShellShortcuts.kt:94-102` 的 `desktopShellPlayerPageKinds`，判定在 `:105-109`），所以合约里的 Ctrl+K / Ctrl+F 搜索和 Ctrl+1…9 切页在播放页不生效 | 播放页顶栏有 360 dp 搜索框（规范 §2.3），Ctrl+K/F 是全局快捷键（规范 §8），在播放页也应聚焦它 | P2 | S | K5 TON-1056（顶栏） |
| A7 | 搜索 | 搜索建议下拉的每一行只有 `appActionClickable`（`app/.../feature/search/SearchLandingUi.kt:279-330`），没有键盘选中态；`feature/search/` 全目录 grep 不到 `Key.DirectionDown`。搜索框本身有 `ImeAction.Search` + `onSearch`（`SearchScreen.kt:3215-3216`），所以回车能搜，但建议列表选不了 | ↑↓ 在建议间移动，Enter 搜所选项，Esc 关建议（Windows 自动建议框通行做法，推断）。现状待核实 V8 | P2 | S | K4 TON-1055 |
| A8 | 全局 | 鼠标侧键只处理「后退」（`K/ui/DesktopShellShortcutHost.kt:295-296`，只看 `isBackPressed`），没有前进 | 规范 §8：前进键只在有前进历史时生效。应用目前没有前进历史，所以只记 P3 | P3 | S | U4 TON-1007 #7（侧键） |
| A9 | 全局 | 微软指南写明「Esc 不后退应用界面」，规范 §8 则规定「Esc 先关最上层，再返回」。两者不一致 | 以规范为准（TON-1033 已定）。只记录分歧，不算缺口 | — | — | 不开卡 |

## 二、鼠标

| 编号 | 页面 | 现状（证据） | 期望行为（依据） | 级 | 量 | 归属 |
|---|---|---|---|---|---|---|
| B1 | 播放 | 在画面上按下鼠标只显示控制条并把焦点要回视口（`K/ui/DesktopWindowsVideoPhysicalLeaf.kt:178`、`:1152` 一带的 `reveal`）；mpv 原生窗口只绑了 `MBTN_LEFT → reveal`、`MOUSE_MOVE → motion`、`SPACE → toggle`、`ESC → escape`（`K/player/MpvNativeWindowInput.kt:17`）。单击画面不会暂停。项目自己的设置页注释也承认：「nothing honours the player flag yet」（`K/ui/DesktopWindowsUxSettings.kt:27`） | 单击画面切换播放/暂停（合约 `click_action`，`K/appearance/DesktopWindowsUxModel.kt:95` 默认 PAUSE；B 站网页播放器同样如此，推断）。离线播放器同样：左键松开只显示控制条（`K/ui/DesktopOfflinePointerInput.kt`） | P0 | M | **S1**（TON-1263） |
| B2 | 播放 | 双击画面不会全屏。`doubleClickAction = FULLSCREEN`（`K/appearance/DesktopWindowsUxModel.kt:96`）有定义，但 `grep -rn '\.wheelAction\|\.clickAction\|\.doubleClickAction' desktop/src/main` 排除该文件后**零命中**：全仓库没有读取者 | 双击画面切换全屏，容差 6 dp（合约 `double_click_action`、`pointer.double_click_tolerance_dp`）；双击后的播放状态和双击前一样，不被第一下单击顺带翻掉（主流桌面播放器做法，推断） | P0 | M | **S1**（TON-1263）；「即时、不黑屏」的画质要求仍归 U2 TON-1006 #12 |
| B3 | 播放 | 画面上滚轮不调音量。`wheelAction = VOLUME`（`:94`）同样没人读；处理 `PointerEventType.Scroll` / `MouseWheel` 的只有直播弹幕、刷新按钮、表情输入、对话框和显示缩放输入几处，播放器没有；mpv 默认绑定也已关掉（`K/player/MpvPlayer.kt:2003`，`input-default-bindings = no`），所以滚轮在画面上什么都不做 | 画面上每格滚轮音量 ±5；进度条上每格 ±5 s，按住 Shift ±10 s（规范 §8「滚轮」） | P1 | S | **S1**（TON-1263） |
| B4 | 首页卡片 | 右键菜单内容来自上游 `app/.../feature/home/components/cards/VideoCard.kt:2204-2322`，桌面原样编译：`K/ui/DesktopCardContextMenu.kt:65` 起的 `DesktopCardContextMenu` 只换外观、原样渲染传入项，`desktop/tools/ux_home_card.py` 只改写其中三项文案。实际菜单项：复制 BV 号（`:664`）、稍后再看（`:677`）、AI 总结（`:681`）、拉黑（`:686`）、无痕模式（`:690`），取消收藏只在收藏页出现（`:704`）。和规范五项对得上的只有稍后再看和不感兴趣；没有收藏、没有复制链接（复制的是 BV 号不是链接）、没有小窗播放 | 规范 §4 第 5 条定了五项：稍后再看、收藏、复制链接（Ctrl+C）、在小窗播放，分隔线下是不感兴趣 | P1 | S | K3 TON-1054 |
| B5 | 评论、动态、视频区 | 只有导航栏、首页卡片和直播弹幕有右键处理（`isSecondaryPressed` / `desktopCardSecondaryClick` 只出现在 `K/ui/DesktopWindowsNavigationRail.kt`、`K/ui/DesktopCardPointer.kt:31-58`、`K/ui/DesktopCardInteractionPolicy.kt`、`K/ui/DesktopLiveChatMessages.kt`）。评论、动态卡片、视频区没有右键菜单；卡片手形光标用的 `desktopCardHandCursor()` 在 `DesktopCardPointer.kt:60`，也没有配套焦点环 | 右键只作用于光标下的对象；Shift+F10 和菜单键打开同一个菜单（规范 §8「右键」；微软：上下文菜单「挂在单个元素上、右键唤出」，原话） | P1 | M | U4 TON-1007 #7 |
| B6 | 首页卡片 | 悬停只有高亮，没有视频预览；上游的预览只走长按（`VideoCard.kt:1447`、`:2007`，`hasPreviewAction = isLongPressEnabled && onLongClick != null`） | 悬停片刻后在封面上播放预览，或至少提供不用长按的入口（B 站网页首页悬停即预览，推断） | P2 | M | 预览本身只记文档；「每个长按都要有替代入口」归 U4 TON-1007 #9 |
| B7 | 播放 | 进度条悬停预览已实现：有雪碧图时显示缩略图加时间，没有时只显示时间（`K/ui/DesktopWindowsVideoSeekPreview.kt`，挂载在 `K/ui/DesktopWindowsVideoPhysicalLeaf.kt:1312` 的 `DesktopWindowsVideoSeekPreviewHost`） | — | 达标 | — | 尺寸是否符合规范 §5 第 4 条（232×130 加章节名）归 U2 TON-1006 #8 |
| B8 | 全局 | 全仓库没有 `isTertiaryPressed`，中键没有任何作用 | 应用没有多窗口和多标签，中键「在新窗口打开」没有落点，只记 P3 | P3 | M | 只记文档 |
| B9 | 全局 | 滚轮速度和惯性是否符合 Windows「每格三行」与平滑滚动，需要实机对比 | 待核实，参照 U5 TON-1001 的结论 | — | — | 待核实 V7 |

## 三、窗口

| 编号 | 页面 | 现状（证据） | 期望行为（依据） | 级 | 量 | 归属 |
|---|---|---|---|---|---|---|
| C1 | 播放 | 进全屏时只在窗口处于 `Floating` 状态才记住原边界（`K/ui/DesktopWindowsFullscreenControl.kt:51`），退出全屏一律回 `Floating`（`:46`，`destination = if (enabled) Fullscreen else Floating`）；类里没有「全屏前是最大化」这个状态（`:27` 只有 `floatingBounds`）。最大化 → F11 → Esc 之后窗口变成普通窗口 | 退出全屏回到进入前状态：最大化回最大化，普通窗口回原位置原尺寸（Windows 播放器通行做法，推断） | P1 | S | **S1**（TON-1263，与双击全屏同一入口） |
| C2 | 番剧 | 番剧主页最外层 `Box` 套了 `Modifier.fillMaxSize().responsiveContentWidth()`，该 Modifier 默认上限 **800 dp**（`app/.../feature/bangumi/BangumiScreen.kt:348`，`:300`、`:318` 为局部；默认值在 `app/.../core/util/WindowSizeUtils.kt:570`）。这一份是编译源：番剧页由 `desktop/tools/extract-upstream-home-bangumi-page.py` 从根源码抽取到 `build/generated/home-bangumi-page` 再编译（`desktop/build.gradle.kts:1404-1413`），该 feature 的 transition 补丁目录里只有 `BangumiHubSkeletons.kt.patch`，不改这个上限。所以在 5120 宽的屏上，海报网格（`GridCells.Adaptive(112.dp)`，`BangumiHubContent.kt:342`、`:579`、`:1018`）被压在中间一条 800 dp 的窄带里。对照：个人空间已改为按窗口宽度算上限（`app/.../feature/space/SpaceScreen.kt:1326`，`maxWidth = adaptiveLayoutSpec.listContentMaxWidthDp.dp`）。注：TON-1266 已合入的 #111 标题写着「铺满内容区并跟随窗口列数」，但上面这条上限代码仍在，实际可见效果见 V10 | 番剧主页和首页一样铺满内容区，列数跟随窗口宽度（可复用 `K/ui/DesktopHomeFeedLayout.kt:80` 的 `desktopResolveHomeFeedGridColumns`） | P1 | M | **S4**（TON-1266） |
| C3 | 播放 | 详情栏宽度是 `minOf(360.dp, maxWidth * .43f)`（`K/ui/DesktopWindowsVideoPhysicalLeaf.kt:1206`，应用到 `:1417`），宽屏上偏窄；左上角是手机式 44 dp 返回箭头 | 面板宽先取窗口宽 22%，夹在 340–480 dp，横向富余时最多 560 dp（规范 §5「布局策略」）；顶栏放 36 dp 返回按钮，旁标「返回 Alt+←」（规范 §2.3、§5 第 1 条） | P1 | L | K5 TON-1056 |
| C4 | 全局 | 首次启动默认窗口 1360×900（`K/appearance/DesktopWindowsUxWindowBounds.kt:126` 注释「1360 by 900, position chosen by the platform」） | 默认 1600×1000，按显示器可用区收缩（规范 §2.1） | P2 | S | K1 TON-1052 |
| C5 | 全局 | 主窗口标题栏已跟随应用深浅色：根窗口挂载时装 `DesktopWindowsProfileChrome`（`K/ui/DesktopReadyOriginalRootMount.kt:225-228`），用 DWM 属性切换深色标题栏（`K/ui/DesktopProfileWindowChrome.kt:22`、`:38` 的 `DwmSetWindowAttribute(hwnd, 20, …)`） | — | 达标 | — | 实机效果由 K1 TON-1052 验收 |
| C6 | 全局 | 首页一直挂着帧时钟：`LaunchedEffect(resources) { resources.metrics.startFrameClock(this).join() }`（`K/ui/DesktopHomeWindowGlobals.kt:43`），实现是 `withFrameNanos {}` 死循环（`K/ui/DesktopHomeWindowPlatform.kt:70-77`）。代码只能证明「每帧都会醒」，**不能证明**空闲 CPU 实际多少 | 静止时不重绘，空闲 CPU 接近 0 | P1 | M | TON-1027；实测见 V6 |
| C7 | 小窗 | 小窗是 `isResizable = false` 的 `JFrame`（`K/player/PictureInPictureController.kt:268`），只能拖右下角拖柄缩放，拖不动窗口边缘；没有保存位置和大小的逻辑，每次打开回默认位置 | 边缘可拖动缩放，记住上次位置和大小（Windows 播放器小窗通行做法，推断） | P3 | M | 只记文档 |
| C8 | 对话框 | 仓库自述「跨不同 DPI 显示器移动独立对话框仍待处理」（`desktop/README.md:163`） | 对话框拖到另一块缩放比不同的屏幕后，按那块屏幕的缩放重排 | P2 | M | 只记文档；李政那台是单屏，优先级不高，多屏熟人机器归 TON-1014 |
| C9 | 全局 | 窗口尺寸位置记忆已实现：保存边界与最大化状态（`K/appearance/DesktopWindowsUxWindowBounds.kt:40` load、`:51` save），启动按现有显示器夹取；最小尺寸随缩放算（`K/Main.kt:269`，`desktopWindowsSafeMinimumSize`） | — | 达标 | — | — |

## 四、文字

| 编号 | 页面 | 现状（证据） | 期望行为（依据） | 级 | 量 | 归属 |
|---|---|---|---|---|---|---|
| D1 | 视频评论、动态评论 | 能否拖选取决于 `shouldEnableRichCommentSelection(hasInteractiveAnnotations)`，而它的实现是 `!hasInteractiveAnnotations`（`desktop/upstream-slices/v046-comment-list/ReplyComponents.kt:374-377`）；调用处 `:2452-2463` 传进去的是 `hasTapHandler`（`:2458`，= 有没有传任何点击回调），不是「这条评论里有没有链接」。桌面端总会传 `urlClick` 和 `timeClick`（`K/ui/DesktopWindowsVideoCommentsSection.kt:155-156`），结果**所有评论**都不能直接拖选，只能长按弹窗再选 | 评论正文可直接拖选、Ctrl+C 复制；链接、@、时间戳照样能点（B 站网页版评论区行为：网页文字默认可选，推断） | P1 | M | **S2**（TON-1264） |
| D2 | 动态 | 动态正文只能长按后在弹窗里选（上游 `app/.../feature/dynamic/components/DynamicCard.kt:2418` 的 `onLongPress`；桌面端把底部弹层换成对话框 `K/ui/DesktopDynamicTextSelectionSheet.kt`） | 正文直接可选，不需要先长按 | P1 | M | **S2**（TON-1264）；长按的替代入口仍归 U4 TON-1007 #9 |
| D3 | 消息 | **已达标**：私信文本气泡已包在 `SelectionContainer` 里（`app/.../feature/message/ChatScreen.kt:1028`，`msg_type == 1` 分支包住 `EmoteText`）。`feature/message/` 属 M4d 直编区（`desktop/build.gradle.kts:334`），该文件不在 `later-steps.tsv` 中，编译的就是这一份 | — | 达标 | — | 实机验收随 K7 TON-1379 |
| D4 | 播放 | 视频标题是普通 `Text`（`K/ui/DesktopWindowsVideoPhysicalLeaf.kt:1464`，只读兜底视图 `:1568` 同样），不能选 | 标题可选可复制 | P1 | S | K5 TON-1056（规范 §5 第 5 条信息区由 K5 重建，标题在其中） |
| D5 | 播放 | 视频简介已包在 `SelectionContainer` 里（`K/ui/DesktopWindowsVideoDescriptionSection.kt:101`）；只有只读兜底视图的简介不能选 | — | 达标 | — | 兜底视图只记文档 |
| D6 | 评论、简介、私信 | 外部 http(s) 链接在应用内 Web 页打开（`K/DesktopShell.kt:2334`、`:2404`，`BiliPaiNavKey.Web(url)`） | 非 B 站链接默认用系统浏览器打开，或右键菜单提供「在浏览器中打开」（Windows 桌面应用通行做法，推断） | P2 | S | 只记文档 |
| D7 | 全局 | 选中文字后右键有没有「复制 / 全选」，取决于 Compose 1.12.1 `SelectionContainer` 的默认上下文菜单，代码里看不出 | 待核实；S2 验收时一并看 | — | — | 待核实 V3 |

## 五、系统集成

| 编号 | 页面 | 现状（证据） | 期望行为（依据） | 级 | 量 | 归属 |
|---|---|---|---|---|---|---|
| E1 | 直播、离线、小窗、听视频 | 防熄屏只接在点播和番剧。`DesktopWindowsVideoWakeLease.acquire`（`K/ui/DesktopWindowsVideoWakeLease.kt`，`PowerSetRequest` 同时持有 DISPLAY 与 SYSTEM，`:37-44`）只有 `K/ui/DesktopOriginalVideoWindowsWindowPort.kt:91-99` 的 `acquireKeepAwake` 一个调用方；后者只被点播和番剧四处用到（`K/ui/DesktopOriginalVideoRootAssembler.kt:208`、`K/ui/DesktopOriginalVideoSectionWindowsPlatform.kt:573`、`K/ui/DesktopOriginalBangumiPlayerRootOwner.kt:528`，以及点播全屏的转调 `K/ui/DesktopOriginalVideoRootWindowPlatforms.kt:71`）。直播、离线、听视频的界面代码都不引用它，全仓库也没有 `SetThreadExecutionState`。切小窗时还主动放掉（`K/ui/DesktopOriginalVideoRootPortraitRendering.kt:121`，`keepAwake = keepAwake && !pip`）。所以看直播、看离线、开小窗、只听声音时，屏幕照样按电源计划熄灭、系统照样睡眠 | 只要在播放（含直播、离线、小窗、纯音频）就持有请求，暂停或停止立即释放（微软：场景开始前立即 `PowerSetRequest`，结束后立即 `PowerClearRequest`，原话）。纯音频只需 SYSTEM，不需 DISPLAY | P1 | S | **S5**（TON-1267） |
| E2 | 全局 | 媒体键和系统媒体浮层已接好：`WindowsMediaSourceLease` 处理播放、暂停、停止、上下一个、快进快退 10 秒和拖动（`K/DesktopShell.kt:928`、`:982`） | — | 达标 | — | 真机验收在 TON-1047 |
| E3 | 全局 | 单实例已实现：第二次启动把激活路由转给已有实例（`K/Main.kt:96`，`DesktopSingleInstance.acquire`） | — | 达标 | — | — |
| E4 | 全局 | 主题默认跟随系统（`K/appearance/DesktopThemePrefs.kt:33`，`themeMode = AppThemeMode.FOLLOW_SYSTEM`） | — | 达标 | — | — |
| E5 | 全局 | 跟随系统主题靠每 250 ms 读一次注册表：`while (isActive) { dark = withContext(Dispatchers.IO) { readWindowsDarkTheme() }; delay(250) }`（`K/appearance/DesktopSystemTheme.kt:18-22`） | 改为监听 `WM_SETTINGCHANGE`，省掉空转 | P3 | S | 只记文档 |
| E6 | 消息 | 消息通知默认关闭（`app/.../feature/message/notification/MessageNotificationSettingsStore.kt:16`，`enabled = false`；`:19` `residentEnabled = false`） | 桌面端默认开启系统通知，或首次进消息页询问一次 | P2 | S | 只记文档（改默认值需要产品判断） |
| E7 | 全局 | 点关闭直接退出（`K/Main.kt:250`，`onCloseRequest = { closeApp() }`；`closeApp` 在 `:196`），下载进行中也不确认。下次启动时进行中的任务变「已暂停」（`K/download/DesktopDownloadManager.kt:484`），可续传（`:367-376`），不丢已下好的部分 | 有下载在跑时关闭前确认一次，或提供「关闭时最小化到托盘」 | P2 | S | 只记文档 |
| E8 | 全局 | 启动总回首页（`K/DesktopShell.kt:563`） | 可选「启动时回到上次的页面」 | P3 | S | 只记文档 |
| E9 | 全局 | 窗口标题固定「BiliPai Windows」（`K/Main.kt:253`），任务栏和 Alt+Tab 里看不出在看什么 | 播放时标题带上视频名 | P3 | S | 只记文档 |
| E10 | 全局 | 没有开机自启，也没有 `bilibili://` 协议注册（全仓库 grep 无结果） | 按需提供，默认关闭 | P3 | M | 只记文档 |
| E11 | 全局 | 托盘图标只在开启消息通知且有未读时创建（`K/ui/DesktopWindowsMessageNotifications.kt:26` 的 `tray`、「created solely after master is enabled」；`:37`、`:71`、`:79`、`:96`、`:122`），或有下载在跑时出现（`K/download/DesktopDownloadNotifications.kt:122`），平时没有常驻托盘 | 常驻托盘（打开主窗口、暂停全部下载、退出），与 E7 的「关闭时最小化到托盘」一起做 | P3 | S | 只记文档 |

## 六、逐页速查

| 页面 | 缺口编号 | 已达标 |
|---|---|---|
| 首页推荐 | A1、B4、B6、C6 | 右键菜单外观和手形光标（`desktop/tools/ux_home_card.py`、`K/ui/DesktopCardPointer.kt:60`）、窗口记忆 C9、标题栏跟随主题 C5 |
| 视频详情和播放 | A2、A5、A6、B1、B2、B3、B5、C1、C3、D1、D4、D6、E1（纯音频、小窗） | 进度条预览 B7、简介可选 D5、媒体键 E2 |
| 番剧 | C2、E1（离线番剧） | 番剧播放已持有防熄屏 |
| 搜索 | A1、A3、A7 | 搜索框回车有 `ImeAction.Search`（`SearchScreen.kt:3215-3216`） |
| 动态 | A5、B5、D1、D2 | — |
| 消息 | A3、E6 | 私信气泡可选 D3、私信 Ctrl+Enter 发送 A5′ |
| 个人中心 | — | 个人空间列表宽度按窗口自适应（C2 的对照）；布局改造在 K8b TON-1060 |
| 设置 | — | 左侧导航加限宽内容区，带设置搜索（`K/settings/DesktopSettingsTree.kt`，在 `K/DesktopShell.kt:2709` 挂载）。去掉手机专属设置项在 U4 TON-1007 #9 |
| 登录 | A4 | — |

崩溃类问题不在本审计范围内，各有专卡：视频/设置崩溃 TON-1077，消息崩溃 TON-1201。

## 七、待核实

| 编号 | 内容 | 怎么核实 | 交给 |
|---|---|---|---|
| V1 | 单击画面后焦点在 mpv 原生窗口还是 Compose 视口：←→↑↓、F、M 还能不能用（`PhysicalLeaf.kt:1152` 一带按下时会把焦点要回视口，实机没测过） | winbox：点画面后依次按键看是否都生效 | S1 |
| V2 | 输入框里按 Esc 是否真的触发返回（A3） | 先写 UI 测试：文本框聚焦时发 Esc，断言 `requestBack` 未被调用 | S3 |
| V3 | `SelectionContainer` 的默认右键菜单（D7） | S2 验收时在 winbox 右键已选中的文字 | S2 |
| V4 | 进程是否 Per-Monitor V2 DPI 感知 | winbox 任务管理器「详细信息」看「DPI 感知」列 | 只记文档 |
| V5 | 链接悬停是否变手形光标 | winbox 截图 | S2 |
| V6 | **首页在 165 Hz 下空闲 CPU 到底多少**（C6）：常挂帧时钟不等于高 CPU，Compose 的 `withFrameNanos` 空帧开销可能很小。要用 perfmon / 任务管理器看静止 1 分钟的平均占用 | winbox：首页静止 60 s，记录进程 CPU 均值与 GPU 占用；与暂停在设置页时的基线对比 | TON-1027 |
| V7 | 滚轮速度和惯性（B9） | 与 U5 TON-1001 的实测结论对照 | U5 |
| V8 | 搜索建议 ↑↓（A7） | winbox 实测：聚焦搜索框按 ↓ 看建议是否被选中 | K4 |
| V9 | 全屏时任务栏是否完全隐藏 | winbox 截图 | S1 |
| V10 | 番剧主页实际可见宽度（C2）：代码上限仍在，但 #111 的标题说已铺满，两者对不上，必须看一眼画面 | winbox 打开番剧主页截图，量左右留白 | S4 |

## 八、已有子卡与合并状态（本轮不再新开）

五张子卡上一轮已开，本轮只对齐范围、核对是否仍然成立。截至 `554a89b02`，**五个 PR 全部仍是 OPEN**，本文所有对应缺口都还能在基线代码里复现。

| 子卡 | 级 | 卡号 | PR | 覆盖条目 | 状态 |
|---|---|---|---|---|---|
| S1 播放画面的鼠标操作和全屏往返 | P0 | TON-1263 | #106 | B1、B2、B3、C1；V1、V9 | OPEN |
| S2 文字能直接选中复制，链接照样能点 | P1 | TON-1264 | #108 | D1、D2；V3、V5 | OPEN |
| S3 输入框里的 Esc 和回车 | P1 | TON-1265 | #109 | A3、A4；V2 | OPEN |
| S4 番剧主页铺满内容宽 | P1 | TON-1266 | #107（OPEN）；#111 已合入，见 V10 | C2；V10 | OPEN |
| S5 看直播、离线视频、小窗和只听声音时不熄屏 | P1 | TON-1267 | #105 | E1 | OPEN |

上一轮汇总卡片（本审计文档本身）由 PR #103 合入，即本文的上一版。

**为什么能各自独立验证**：五张卡改的是不同文件。

- 只有 S1 会碰 `K/ui/DesktopWindowsVideoPhysicalLeaf.kt`（K5 独占写入的文件），且只碰指针输入与全屏往返那两小段。**S1 必须在 K5 开工前合入**；K5 开工时 S1 还没合入的，剩余改动并入 K5。
- S2、S4 要改的评论、动态、番剧代码来自上游。`desktop/upstream-slices/` 下的原文按哈希锁定（例如 `desktop/tools/v046_comment_list.py:10` 的 `ARCHIVE`），不能直接编辑；改动走该页面现有机制（生成脚本替换或 M0 补丁），每个改动点配布局测试或 golden。**注意 `feature/comment/` 已迁入 M4b 直编区，但 `ReplyComponents.kt` 实际在 `feature/video/` 下，仍在切片轨**，动手前先确认目标文件当时归哪套机制管，改法要在 M4a 合入后仍然有效。
- S3 碰的 `AdvancedLoginDialog.kt` 与 `DesktopShell.kt` 都是热点文件（后者已被 #147、#149 改过），S3 只改 Esc 防线和登录提交的几行，变基成本低但要勤拉。

## 九、抽检清单（交给异源复核，10 条）

每条都可以只读代码核对，不需要启动客户端。行号对应基线 `554a89b02`。

1. **B2**：`grep -rn '\.doubleClickAction' desktop/src/main --include=*.kt` 只命中 `DesktopWindowsUxModel.kt` 自身，无生产读取者。
2. **C1**：`K/ui/DesktopWindowsFullscreenControl.kt:46` 退出全屏目标固定 `Floating`；`:51` 只在 `Floating` 时记 `floatingBounds`，没有「全屏前是最大化」的状态。
3. **A3**：`K/DesktopShell.kt:759` 的 `searchFocused` 只有声明，全文仅出现在 `:1782`、`:1788`、`:1790` 的读取处，无任何赋值。
4. **D1**：`desktop/upstream-slices/v046-comment-list/ReplyComponents.kt:377` 是 `!hasInteractiveAnnotations`，`:2458` 把 `hasTapHandler` 当参数，`K/ui/DesktopWindowsVideoCommentsSection.kt:155-156` 无条件传 `urlClick`/`timeClick`。
5. **C2**：`app/.../feature/bangumi/BangumiScreen.kt:348` 是 `Modifier.fillMaxSize().responsiveContentWidth()`，`WindowSizeUtils.kt:570` 的默认 `maxWidth = 800.dp`；番剧页编译源由 `desktop/build.gradle.kts:1404-1413` 的 `extract-upstream-home-bangumi-page.py` 生成。
6. **E1**：`DesktopWindowsVideoWakeLease.acquire` 只被 `DesktopOriginalVideoWindowsWindowPort.kt:91-99` 调用，后者只被四处用到（含 `DesktopOriginalVideoRootWindowPlatforms.kt:71`）；`grep -rln -i keepAwake desktop/src/main` 无直播、离线、听视频文件；`DesktopOriginalVideoRootPortraitRendering.kt:121` 在小窗传 `keepAwake && !pip`。
7. **A4**：`K/ui/AdvancedLoginDialog.kt:181`、`:182` 两个输入框没有 `keyboardActions`（`:224`、`:241`、`:258`、`:274` 同样没有）。
8. **B4**：菜单在 `VideoCard.kt:2204-2322`，项为复制 BV 号 `:664`、稍后再看 `:677`、AI 总结 `:681`、拉黑 `:686`、无痕模式 `:690`、取消收藏 `:704`（仅收藏页）；`desktop/tools/ux_home_card.py` 只改文案；规范五项里的收藏、复制链接、小窗播放都不存在。
9. **D3 反证**：`app/.../feature/message/ChatScreen.kt:1028` 确实有 `SelectionContainer`，且 `feature/message/` 在 `desktop/build.gradle.kts:334` 的 M4d 直编名单里。上一轮把它当缺口是错的。
10. **E2 / E3 反证**：`K/DesktopShell.kt:928`、`:982` 媒体键确实接好；`K/Main.kt:96` 单实例确实实现。

## 参考

- 微软：[Keyboard interactions](https://learn.microsoft.com/en-us/windows/apps/develop/input/keyboard-interactions)，含 Tab 顺序、方向键内导航、Enter/Space、「Esc 只影响临时 UI」、焦点框、Ctrl+F 查找。上一轮已打开核对原文，本轮沿用。
- 微软：[Menus and context menus](https://learn.microsoft.com/en-us/windows/apps/develop/ui/controls/menus-and-context-menus)，上下文菜单挂在单个元素上、右键唤出。上一轮已打开核对原文，本轮沿用。
- 微软：[PowerSetRequest](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-powersetrequest)，DISPLAY 必须和 SYSTEM 一起持有，场景结束立即清除。上一轮已打开核对原文，本轮沿用。
- B 站：官方帮助中心「播放器使用指南」（[入口](https://www.bilibili.com/blackboard/help.html)）。页面靠脚本渲染，取不到正文，因此文中涉及 B 站的期望行为都标「推断」。
- 本项目：设计规范 v2（TON-1033）、合约 `desktop/tools/ux/desktop-ux-contract.json`。
