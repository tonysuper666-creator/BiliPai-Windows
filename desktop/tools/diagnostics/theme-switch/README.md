## TON-1210 主题切换时间线

只用于诊断「深浅色切换后约 0.75–1.55 s 才重绘」到底卡在哪一段。应用内打点默认关闭；离线探针只读文件，不启动客户端、不连 VM、不跑 Gradle。

### 打开应用内打点

启动参数加 `-Dbilipai.trace.themeSwitch=<文件路径>`。不加这个属性时不建队列、不起线程、不写文件；每个调用点先判断 `DesktopThemeSwitchTrace.enabled`，为假时不读时钟、不构造字段，开销只剩一次 volatile 读。新增调用点也要照这个写法。属性值不是合法路径（例如含 NUL）时打一行 stderr 警告、保持关闭，不会让类初始化失败。

打开后，文件前两行是表头：

```
# ton1210-trace v1 pid=<pid> os=<os.name>
# clock_sync jvm_nano_before=<ns> qpc_ticks=<QPC> jvm_nano_after=<ns> qpc_freq=<Hz>
```

`clock_sync` 用两次 `System.nanoTime` 夹住一次 `QueryPerformanceCounter`，PresentMon 和像素采样器都靠它对齐；非 Windows 或 JNA 失败时没有这一行，探针遇到 QPC 输入会退出 4。之后每行是 `<System.nanoTime> <事件> thread=<线程名> k=v ...`，由守护线程 `bilipai-theme-trace` 写盘，不在 EDT 上做 IO。

队列上限 4096 行，生命周期是这样的：

- **队列满**：新打点直接丢弃并计数；写线程空闲时写一行 `# dropped total=<累计>`，所以进程后来被杀也看得到丢过。
- **写入失败**（目录、无权限、磁盘满）：立即停用打点并清空队列，不会无限积压；stderr 打一行警告。之后 `enabled` 一直为假。
- **正常退出**：shutdown hook 先拒收新打点，等写线程排空，最后追加 `# end dropped=<n>`。没有这一行，就说明进程被杀、写入失败，或尾部可能缺打点。

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
python3 -I desktop/tools/tests/test_theme_timeline_probe.py -v   # 在仓库根运行：合成正例与反例
```

回归测试放在 `desktop/tools/tests/`，`desktop/tools/run-tool-tests.py --stage generated` 会自动发现它。

探针怎么读 trace：

- **按时间戳排序，不按文件行**：线程可能在读完 `nanoTime` 之后、入队之前被抢占，所以文件里的先后不代表因果。
- **一次切换**：从它的 `pref.request` 起，到下一次 `pref.request` 前为止。
- **DWM**：窗口里每次 `dwm.set_end` 都会列在 `dwm_sets` 里；只有 `light=` 与本次目标一致的那次才算本次切换的阶段，并参与「DWM 设置早于新 palette 构建」（退出 2）的检查。上一次切换留下的反向设置只列出，不当成本次阶段。
- **字段值**：`dur_ns`、`epoch`、`bytes`、`hr` 等必须是十进制整数，`dark`、`light`、`atomic` 必须是 `true`/`false`；`# dropped`、`# end` 的计数也一样。不合法按 1 退出，不输出半截报告。

| 退出码 | 含义 |
|---|---|
| 0 | 时间线完整且因果顺序成立 |
| 1 | 用法错误或输入无法解析，包括字段值不合法（不给半截时间线） |
| 2 | 因果乱序：例如 `force` 早于写入、像素变化早于应用首帧、本次目标的 DWM 设置早于新 palette |
| 3 | 缺必需阶段：例如 apply 后没有根绘制（缺首帧）；应用丢过打点（`# dropped`）；`--require-present` 时本次切换窗口里没有 present |
| 4 | 两个时钟域没有同步点：trace 缺 `clock_sync`，或采样器不是 QPC 时钟 |

同时出现多种问题时按 1 > 4 > 2 > 3 取最严重的。缺 `# end` 只是提示，不改变退出码，但报告里的结论要注明尾部可能不全。

### 外部输入的要求

- **像素/DWM 采样器**：表头列 `t_s,dwm_attr20,client_r,client_g,client_b`（`caption_*` 可选），注释行写元数据。要合并进应用时间线，必须加 `# clock=qpc qpc_freq=<Stopwatch.Frequency> qpc_base_ticks=<t_s=0 时的 GetTimestamp>`；鼠标时间写 `# mouse_up_t_s=<s>`（只有 `mouse_down_t_s` 时会多算按住时长，探针会提示）。每个鼠标锚点一份样本，记录真实采样间隔。像素变化一律报区间（上一采样, 变化采样]，读的是 DWM 合成后的桌面，不是面板扫描输出。仓库里还没有可用的采样器，用之前要先实现并核对这些元数据。
- **PresentMon**：2.x 的列名并不统一，先看已装版本和 `--help`。本探针按 v2.3.1 的默认指标加 `--qpc_time`（不要 `--v1_metrics`），需要列 `ProcessID`、`CPUStartQPC`、`MsCPUBusy`、`MsInPresentAPI`，可选 `MsUntilDisplayed`；v2.3.0 的列叫 `CPUBusy`，缺列按 1 退出，不硬套单位。2.x 的 `CPUStartQPC` 是 CPU 开始做这一帧的时刻，不是 `Present()` 调用：调用 = `CPUStartQPC + MsCPUBusy`，提交返回 = 调用 + `MsInPresentAPI`，显示 = 调用 + `MsUntilDisplayed`。每次切换取首帧之后、下一次 `pref.request` 之前的第一个同 pid `Present()` 调用，同一行只分给一次切换；窗口里没有就报「未观测」，不向后一次切换借。它是最早可能带上这帧的提交，不是逐帧对上的证明。采集用本卡唯一的 session，不抢别人的 ETW session（不加 `--stop_existing_session`）：

  ```
  PresentMon --process_id <pid> --qpc_time --session_name "TON1210-<label>-on" --output_file <csv> --timed 60 --terminate_after_timed
  ```

### 上 VM 前要核对的契约

以 VM 上 `C:\BuildVM\tools` 的现行脚本为准；与下面不一致时先改步骤，不改共享脚本。

- 构建走 accept 入口，参数写全：`@{ Sha=<40 位>; Label=<本卡唯一>; BuildOnly=$true; SkipTests=$false; KeepWorktree=$true }`。现行 accept 的 `SkipTests` 默认为真，省略就会跳过测试。看本 label 自己的 accept/build JSON 和最终退出码，不看共享的 `last-build.json`。
- 产物在 `C:\BuildVM\accept\<label>\desktop\build\compose\binaries\main\app\BiliPai Windows\BiliPai Windows.exe`。trace 属性写进本卡副本 `app\*.cfg` 的 `[JavaOptions]`（`java-options=-Dbilipai.trace.themeSwitch=…`），不要靠 `_JAVA_OPTIONS`/`JAVA_TOOL_OPTIONS`：`accept-launch.ps1` 会重置它们。off 不加属性，off/on 共用同一份产物。
- 启动用本卡唯一名称的交互计划任务，在任务脚本里把 `LOCALAPPDATA`、`APPDATA`、`TEMP`/`TMP`、`user.home` 指向本卡的空目录，`SKIKO_RENDER_API=OPENGL`；不复制登录 profile，不碰其他卡或账号目录。
- 点击前核对 trace 首行 pid 与实际进程一致、第二行有 `clock_sync`；正常关闭后核对文件末尾有 `# end`。缺任何一项先停，不把数据补算成结论。

### 边界

- 应用内打点在 Mac 上编译通过，但还没在 Windows 上运行过；打点的生命周期（关闭、写入失败、正常退出）只用仓库外的临时 harness 验证过。
- `frame.draw`、present 提交、屏幕显示是三条不同的边界，报告里不得混用。
- 合成 fixture 只证明探针的判定逻辑，里面的毫秒数不是实机耗时。
