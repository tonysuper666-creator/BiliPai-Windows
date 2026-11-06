## TON-1210 主题切换时间线

只用于诊断「深浅色切换后约 0.75–1.55 s 才重绘」到底卡在哪一段。应用内打点默认关闭；离线探针只读文件，不启动客户端、不连 VM、不跑 Gradle。

### 打开应用内打点

启动参数加 `-Dbilipai.trace.themeSwitch=<文件路径>`。不加这个属性时，所有打点都直接返回，不建线程、不写文件。

打开后，文件前两行是表头：

```
# ton1210-trace v1 pid=<pid> os=<os.name>
# clock_sync jvm_nano_before=<ns> qpc_ticks=<QPC> jvm_nano_after=<ns> qpc_freq=<Hz>
```

`clock_sync` 用两次 `System.nanoTime` 夹住一次 `QueryPerformanceCounter`，PresentMon 和像素采样器都靠它对齐；非 Windows 或 JNA 失败时没有这一行，探针遇到 QPC 输入会退出 4。之后每行是 `<System.nanoTime> <事件> thread=<线程名> k=v ...`，由守护线程 `bilipai-theme-trace` 写盘，不在 EDT 上做 IO。

### 事件

| 事件 | 位置 | 含义 |
|---|---|---|
| `input.press` / `input.release` | `Main.kt` 装的 AWTEventListener | 原始鼠标按下/抬起，早于 Compose 分发；`onClick` 在抬起时触发 |
| `pref.request` | `DesktopThemePrefs.setThemeMode` / `setThemeModeOwned` 入口 | 一次切换的起点；`entry=settings` 或 `profile`，`mode=DARK/LIGHT/FOLLOW_SYSTEM` |
| `pref.io_start` | 同上，进入 `Dispatchers.IO` 后 | 协程切线程完成 |
| `pref.admission_wait` / `pref.admission_acquired` | `setThemeModeOwned` | 只在个人页入口出现：等 gate 锁 |
| `store.lock_request` / `store.lock_acquired` | `DesktopPluginStore.updateThemeFromSnapshot` | `synchronized(backing)` 前后 |
| `store.written` | 同上 | 临时文件写完（`bytes=`） |
| `store.forced` | 同上 | `force(true)` 返回 |
| `store.moved` | 同上 | 原子替换完成（`atomic=false` 表示退回普通替换） |
| `store.published` | 同上 | 新 snapshot 已发布给 Flow，随后才释放锁 |
| `prefs.decode` | `DesktopThemePrefs.initialSettings` | 每次解码（`dur_ns=`）；在 EDT 上的次数和耗时单独统计 |
| `prefs.settings_emit` | `DesktopThemePrefs.settings` 的 `map` | 收集方拿到新设置 |
| `theme.palette_built` | `DesktopAppearanceTheme` 的 palette `remember` | 新 palette 构建完成（`dur_ns=` 为构建自身耗时） |
| `theme.applied` | 同文件，`SideEffect` | 组合已 apply，`palette.dark` 变化时只记一次 |
| `frame.draw` | `DesktopShell` 根 `BoxWithConstraints` 的 `drawWithContent` | apply 后第一次根节点内容录制完成。**不是** GPU 提交，更不是屏幕显示 |
| `frame.next` | 同 `theme.applied`，`withFrameNanos` | apply 后下一帧时钟（对照用） |
| `dwm.set_start` / `dwm.set_end` | `DesktopWindowsProfileChrome.apply` | `DwmSetWindowAttribute(hwnd, 20, …)` 前后，`light=`、`hr=` |
| `awt.window_background` | `DesktopHomeWindowsClientPolicy.applyHomeSystemBars` | AWT 窗口底色改完（`rgb=`） |

`updateThemeFromSnapshot` 还被 `dark_theme_style` 写入调用；这类写入有 `store.*` 但没有 `pref.request`，探针不把它们算成一次切换。

### 离线探针

```
python3 -I theme_timeline_probe.py --trace <trace> [--samples <采样.csv> ...] [--presentmon <PresentMon.csv>] [--require-present] [--json <报告.json>]
python3 -I theme_timeline_probe.py --samples <采样.csv>      # 只有外部采样时，按鼠标时间算客户区/attr20 变化区间
python3 -I test_theme_timeline_probe.py -v                   # 合成正例与反例
```

| 退出码 | 含义 |
|---|---|
| 0 | 时间线完整且因果顺序成立 |
| 1 | 用法错误或输入无法解析（不给半截时间线） |
| 2 | 因果乱序：例如 `force` 早于写入、像素变化早于应用首帧 |
| 3 | 缺必需阶段：例如 apply 后没有根绘制（缺首帧）；`--require-present` 时首帧后没有 present |
| 4 | 两个时钟域没有同步点：trace 缺 `clock_sync`，或采样器不是 QPC 时钟 |

同时出现多种问题时按 1 > 4 > 2 > 3 取最严重的。

### 外部输入的要求

- **像素/DWM 采样器**：表头列 `t_s,dwm_attr20,client_r,client_g,client_b`（`caption_*` 可选），注释行写元数据。要合并进应用时间线，必须加 `# clock=qpc qpc_freq=<Hz> qpc_base_ticks=<t_s=0 时的 QPC>`；鼠标时间写 `# mouse_up_t_s=<s>`（只有 `mouse_down_t_s` 时会多算按住时长，探针会提示）。像素变化一律报区间（上一采样, 变化采样]，读的是 DWM 合成后的桌面，不是面板扫描输出。
- **PresentMon**：用 2.x 的默认指标加 `--qpc_time`（不要 `--v1_metrics`），需要列 `ProcessID`、`CPUStartQPC`、`MsInPresentAPI`，可选 `MsUntilDisplayed`。`CPUStartQPC + MsInPresentAPI` 是 present 提交返回；`MsUntilDisplayed` 才是显示。

### 边界

- 应用内打点未经编译验证，需要 QA 编译审查后才能上 VM。
- `frame.draw`、present 提交、屏幕显示是三条不同的边界，报告里不得混用。
- 合成 fixture 只证明探针的判定逻辑，里面的毫秒数不是实机耗时。
