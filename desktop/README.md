# BiliPai Windows

从 BiliPai 源码维护的 Windows x64 客户端，使用 Compose Desktop 与 libmpv。便携 ZIP 内含运行时，运行 `BiliPai Windows.exe` 即可，无需 Android 模拟器或系统 Java。

Windows 分支现在按桌面使用重新组织界面，复用上游接口、模型与业务策略。当前源码基线为 `v0.2.5` / `79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40`，本轮候选版本为 `0.2.427.3`。窗口布局、鼠标键盘和可见设置由 Windows 分支维护，范围见 [WINDOWS.md](WINDOWS.md)。APK 是 Android 安装包；跟随更新依靠源码同步和 Windows 构建。

## 本轮 Windows 改造

| 区域 | 当前源码行为 |
| --- | --- |
| 主导航与启动 | 固定侧栏；保留页面状态和返回，直接进入首页，移除手机 Pager、卡片变形转场和开屏提示 |
| 普通视频 | 同一原生播放器承载桌面布局，保留原取流、账号权益、分集、画质、字幕、弹幕与互动业务 |
| 视频详情与评论 | 提供 UP 入口、收藏、下载、评论与楼中楼；复用原表情输入、排序、时间规则及历史续播建议 |
| 桌面设置 | 七组桌面设置，保留代理、WebDAV、CDN、播放加速和手动诊断；删除触屏手势、旋转、亮度与手机权限选项 |
| 默认画质 | 升级后首次启用最高可用画质，继续遵循视频与账号权限；后续手动选择保持 |
| 窗口输入与缩放 | 保留全屏/返回及播放器快捷键，支持 Ctrl＋加减与 Ctrl＋滚轮缩放，Ctrl＋0 回到 125% |
| 音频输出 | 默认共享，支持真实 WASAPI 设备列表及下一次播放应用独占配置，分别显示视频与独立音频播放器状态 |

推荐、搜索、空间、动态、消息、番剧、直播、下载、听视频和账号仍使用已有业务实现。新桌面普通视频布局没有重建另一套取流或播放器；各媒体路径与真实账号成功不能由源码接线推断。

## 当前验证事实

本轮普通主程序与测试源码编译通过，选定的 7 组 Windows 测试 **35 项通过**；随后独立执行的 HTTP 412/429 分类测试 **6 项通过**，两轮均无失败或跳过。前者覆盖音频输出、启动、导航、设置目录、缩放和播放默认值；后者覆盖错误分类、原重试规则、取消与页面所有权。这些针对性测试不能替代完整功能验收。

独立原生播放器使用静音的本地双声道素材，已实际通过 **WASAPI 共享 → 下一次播放独占 → 下一次播放恢复共享** 三个场景；每次都有原生 PCM 读回及播放时钟推进，音量保持 0、静音保持开启。该结果不证明真实主程序的双播放器切换、设备占用错误、Devialet 光纤听音或位完美输出。

真实 Main 的搜索 → 普通视频本地回放已通过：同一个原生播放器在普通窗口、全屏、退出全屏、缩小和恢复窗口、Ctrl 缩放期间保持同一来源、真实首帧和推进的播放时钟；播放器空格/方向键及评论编辑框的焦点区分也通过。修复了同步 Mini 回调误读异步界面投影、取消有效加载请求的问题；没有放宽请求、账号或旧源门禁。检查使用隔离访客数据、内存 API 回放及本地音视频，不能证明线上 B 站取流或真实账号播放成功。线上访客曾遇到 HTTP 412，请求限制现有明确提示。

便携包的原生、合并文件与更新检查由构建脚本分别记录，并绑定实际 ZIP。源码与主窗口回放通过不代表整个上游版本的全部功能或 Devialet 光纤听音通过。

此前 `0.2.427.2` 的编译、原生 smoke、更新和部署属于历史交付，不能转为本轮通过。真实扫码登录及账号内的播放、收藏、下载和评论需要用户验收；测试不读取用户账号，也不代替用户发送评论或关注。

`upstream-sources.json` 固定复用来源与摘要，历史 [PARITY.md](PARITY.md) 和 `verification` 保存此前移植记录。来源文件、接口或原手机页面的数量不能换算为当前 Windows 功能完成百分比。

便携包内的简短说明位于 `app/resources/Windows使用说明.txt`。构建与部署完成后，以实际 EXE 和本轮报告为准。

## 本地构建

需要 Windows x64、JDK 21、Python 3.12 和 PowerShell 7。使用便携 JDK 即可，不必修改系统 Java 配置；未指定 `-JavaHome` 或 `JAVA_HOME` 时，会递归搜索仓库相邻的 `toolchain` 目录。Windows `tar.exe` 或 7-Zip 用于解压播放器依赖。

在仓库根目录执行：

```powershell
pwsh -NoProfile -File desktop/tools/build.ps1 -JavaHome 'D:/toolchain/jdk-21'
```

该脚本校验并下载固定 SHA-256 的 libmpv 与 FFmpeg/FFprobe，执行 Windows 单元测试和 `createDistributable`，在实际 Kotlin 和资源生成后执行全部 Python 源码契约测试，再生成 `desktop/build/distributions/BiliPai-Windows-<Windows版本>-x64.zip` 及相邻的 `.zip.sha256`。默认生成便携包。需要 MSI 时加 `-Installer`，并准备 Compose 打包所需的 WiX 工具。

`python desktop/tools/run-tool-tests.py --stage policies` 只运行不依赖构建产物的同步与发布策略测试，适用于全新检出和定时检测。`--stage generated` 运行全部工具测试，包含实际生成文件和打包资源核验，须在正常 Gradle 生成之后运行。两个阶段均自动配置源码定位器的 Python 导入路径；完整发布证据必须包含 `pythonSourceContractTests=passed`，仅通过检测阶段不能发布。

发布前的完整检查：

```powershell
pwsh -NoProfile -File desktop/tools/build.ps1 -JavaHome 'D:/toolchain/jdk-21' -ReleaseGate
```

完整发布必须从干净、已提交的源码开始，并在检查结束时再次确认提交与工作区没有变化。发布报告绑定该 Windows 提交、版本和 ZIP 哈希；缺少源码绑定的旧报告不能为另一个提交提供发布证明。

用 `-NativeSmoke` 可以单独执行打包后的原生播放器检查并产生原生报告。Windows CI 对每次普通构建也执行此检查；完整发行仍须通过 `-ReleaseGate` 的所有门槛。

用 `-NativeMuxSmoke` 可以单独验证打包后的 FFmpeg/FFprobe，检查双轨、音频、渐进流及三种多段合并文件的编解码、时长和二进制摘要；六种输出必须全部通过，缺报告即失败。

用 `-UpdaterSmoke` 可以在 ZIP 和 SHA-256 文件生成后，单独执行隔离的更新集成检查。它通过 loopback HTTP 测试服务下载真实便携 ZIP，校验哈希与安装根目录，并验证实际 EXE 的启动、激活和失败回退；报告固定为 `desktop/build/reports/updater-smoke-*/updater-smoke.json`，绑定本次 Windows 版本和 ZIP SHA-256。测试使用独立的用户数据目录，不读取真实账号；新版本健康检查窗口可能发起隔离访客请求，因此这项检查并非完全离线。它不证明 GitHub 正式发行流程或线上 B 站 DASH 已通过。可选 `-PreviousUpdateTestPackage <旧版ZIP>` 用于额外检查真实旧版 EXE 的更新转发。Windows CI 默认执行此检查并单独上传报告与日志。独立 smoke 开关不会生成完整发布通过证据；`-SkipTests` 不会跳过所选 smoke。

发布门槛包括 Windows 单元测试、完整 Python 源码契约测试、访客模式推荐/搜索/视频详情/DASH 地址解析、**打包后的 EXE** 离线视频、音频、暂停、进度、速度和错误恢复检查，以及绑定本次 ZIP 的更新集成检查。`-ReleaseGate` 同时启用原生与更新 smoke，只有 ZIP、SHA-256 和全部检查成功后才写入 `desktop/build/release-gate.json`。网络请求被拒绝或任一检查失败会停止发布，不会跳过门槛。原生报告位于 `desktop/build/reports/release-gate-*`。CI 使用真实音视频解码与同步检查，并将声音输出送往空设备；本地保留声音设备检查。登录状态和新 Android 功能仍需要相应维护；这些自动检查不能证明完整 Android 功能已被移植。

## 跟随频繁的上游更新

仅查看更新，不修改源码、Git 引用或工作区：

```powershell
python desktop/tools/sync-upstream.py --check
```

准备独立候选版本：

```powershell
python desktop/tools/sync-upstream.py --sync --release-gate --java-home 'D:/toolchain/jdk-21'
```

更新器读取 GitHub `/releases`，包含 alpha/prerelease，解析 tag 对应的完整 commit SHA，核验复用文件是否仍存在以及内容哈希。它在另一个 Git worktree 和独立分支合并更新，保留已经审核的 Windows 工作流。原工作区必须先提交；冲突、移除源码、接口变化或测试失败时保留候选目录供排查，原工作区和已发布的便携包继续保留。脚本本身不会 push 或发布。

兼容预检覆盖复用源码、资源及版本配置；通过已固定的完整 Git tree 识别未变化文件，只下载变化项，二进制资源按原始字节校验。当前 Windows 构建仍严格绑定已审核的 v0.2.5 canonical 源码和适配配方。输入变化时，报告保留 `updateAvailable`，列出具体路径与摘要，并标记 `manualAdaptationRequired`；定时任务保存报告并跳过已知不能构建的候选，不会自动重写源码 pin 或宣称新功能已移植。完整输入保持兼容的候选先提交到隔离分支，再针对固定提交运行全部构建检查。

报告列出复用文件变化、需要复核的功能、认证接口变化、其余尚未移植的上游文件变化和当前 Windows 覆盖范围。对 `ApiClient.kt` 会比较实际复用的登录接口声明，以及 Cookie、授权和访客网络实现。普通未采用的 Android 接口或无关单例变化不会被当作登录风险；登录策略、登录模型、实际认证声明或敏感网络实现变化会转入人工审核。无法可靠识别敏感源码结构时停止自动发布，保留上一可用版。

Windows 独立版本为 `0.2.<上游versionCode>.<windowsRevision>`。上游代码递增时 revision 从 1 开始；同一 versionCode 的下一次上游发布递增 Windows revision。同一上游版本的 Windows 修复也必须递增 revision，确保每个 Windows 标签只对应一个源码提交。Windows 标签使用 `Windows-v<Windows版本>`，避免与 Android 标签混淆。已有标签和有效发行附件不会被新构建覆盖。

## GitHub 自动构建和发布

Windows 分支的原 Android 工作流完整保存在 `.github/upstream-workflows`，不参与 GitHub Actions 执行。活动工作流仅保留 `windows-desktop.yml` 和 `windows-upstream-sync.yml`。

在自己的 GitHub fork 上完成首次发布验证后，设置以下仓库 Actions variables：

| 变量 | 值 | 作用 |
| --- | --- | --- |
| `BILIPAI_WINDOWS_AUTO_SYNC` | `true` | 每两小时检查上游发布，包括 alpha 版本 |
| `BILIPAI_WINDOWS_AUTO_PUBLISH` | `true` | 满足发布检查且没有认证风险时，自动更新 Windows 分支并发布 |

未设置变量时，定时同步或自动发布不会启用。个人 Windows 仓库必须允许 Actions 创建 PR，并启用定时工作流，默认分支应为 `desktop/windows`。每两小时的源码与发布完整性检测使用 Ubuntu runner；没有新源码且当前 Windows 发行版完整时，不启动 Windows 构建机。GitHub 定时任务可能排队延迟，因此检查间隔不是严格实时保证。

更新工作流串行执行，以 tag 和 commit SHA 去重。低风险候选只有在单元测试、访客网络检查和原生播放检查全部通过后，才通过 fast-forward 更新 fork 默认分支并启动发布工作流。高风险但能构建的候选创建 draft PR，列出风险和功能覆盖范围。冲突、构建或网络检查失败会停止发布；上一个成功 Windows 发行版仍可使用。

源码同步与 Windows 发布完成分别记录。即使源码显示 `upToDate`，工作流仍会验证固定 Windows 版本的标签、Release、ZIP、SHA-256 和源码证据附件。源码已经推送但发布派发失败、Release 缺少附件或仍是 draft 时，下一次检测会从固定的源码 SHA 重试发布。派发步骤具有独立的 `actions: write` 权限。

`Windows desktop build` 可手动触发。`release=true` 执行完整检查并创建 Windows prerelease；`source_sha` 指定确切的构建来源，`expected_head` 再次校验来源。标签会独立核验确切提交，即使标签存在但 Release 尚未创建也不会复用错误提交。发布先创建 draft，上传并校验 ZIP、SHA-256 和 `BiliPai-Windows-<版本>-source.json`，全部附件完整后才公开。重试会保留已有有效 ZIP，只补齐缺失附件。若远端 ZIP 与本次检查的字节不同，必须已有对应源码、哈希及全部门槛证据才可复用；缺少证据时停止，需验证远端确切 ZIP 或增加 revision。附件内容冲突同样停止并要求增加 revision。发布不生成 Android APK，也不启用原 Android 发布流程。

## 版本检查和便携更新

程序内的版本信息记录 Windows 版本、上游 tag/SHA 和配置的 Windows release 仓库。更新源是该 Windows fork 的 Releases，包含 prerelease。程序每六小时检查一次，设置页也可以立即检查；自动安装可在设置中开关。下载时可以继续播放，ZIP 通过 SHA-256 校验后解压到独立目录，等视频页面退出且没有打开视频的任务时才切换。

新版本必须完成真正活跃的原首页内容绘制、经过下一帧并再次确认页面所有权，成功初始化 libmpv，并持续存活 1.5 秒，旧版本才登记更新成功并退出。加载页或 Root 初始化失败不会发出健康确认。启动失败会终止本次失败进程并保留当前版本；从原便携目录再次启动时，会尝试已登记的新版本，失败则回到上一可用安装。清理仅处理本程序标记的更新目录，并保留当前、上一、准备中及可观测运行的安装。登录数据及更新目录位于 `%LOCALAPPDATA%/BiliPai`，历史、收藏和界面设置位于 `%LOCALAPPDATA%/BiliPaiWindows`。

自动发布关注的是**已移植的 Windows 功能能够继续运行**。上游新增的 Android 界面、弹幕、直播、插件或其他功能，需要继续实现对应 Windows 代码后，才会加入覆盖清单。

## 依赖与许可

原 BiliPai 许可见仓库 `LICENSE`。Windows 播放器依赖来源、固定下载链接、档案和 DLL 校验值记录在 `desktop/native/windows-x64/provenance.json`。`desktop/third-party/libmpv` 保存 mpv、FFmpeg 的原许可文本及固定源码、构建配方引用，构建时核验摘要并一并打包。固定播放器档案及源码档案保存在 Windows 仓库的 `runtime-mpv-20260903` 依赖发行中，供上游清理旧二进制后仍可构建。
