# BiliPai TV

当前仓库中的独立 Android TV 应用，包名 `com.android.bilipai.tv`，最低 Android 8.0。使用 Compose for TV 和 Media3，依赖 `:core-data`、`:core-player`，不依赖手机 `:app` 或手机 `:design-system`。

已接入推荐、搜索、扫码登录、详情、历史、收藏夹、稍后再看、基础设置，以及暂停、进度调整、画质、倍速、分 P 和续播。遥控器按键与手机手势分别维护。

当前是初期实现；弹幕、番剧、直播等后续能力尚未完成。完整范围、验证状态和下一项见 [TV 实施记录](../docs/TV_ADAPTATION.md)。

现有色板已迁移到 `:design-tokens`，手机设计系统与 TV 共用颜色定义。TV 仍使用独立的焦点、侧栏、播放控件和弹窗。当前界面是观看原型，视觉规范尚未全面适配。

## 启动到 TV 虚拟机

在仓库根目录执行：

```sh
./scripts/launch_tv.sh
```

默认启动或复用 `Television_4K`，安装已有 `app-tv/build/outputs/apk/debug/app-tv-debug.apk` 并打开 TV 应用。**默认不编译**。指定其他 TV 虚拟机用 `--avd 名称`，指定已连接 TV 用 `--device 序列号`；脚本不会将 TV 包安装到普通手机。

只有明确希望编译最新源码时执行 `./scripts/launch_tv.sh --build`。也可在 Android Studio 选择 `app-tv` 和 TV 虚拟机启动。

点选虚拟机窗口后用方向键移动、Enter 确认、虚拟机工具栏 Back 返回。也可用扩展控制中的遥控器/D-pad。播放控件隐藏时确认显示控件，左右预览进度；返回依次取消调整、隐藏控件、回到详情。

如果复用的是 Android Studio 内嵌虚拟机，在 `Running Devices` 打开 `Television (4K) API 36.0` 面板即可看到应用。搜索输入后可使用键盘 Enter 提交，也可关闭输入法、按下方向键移到搜索按钮，再确认。

## 遥控器测试

```sh
python3 scripts/tv_smoke.py --device emulator-5554
```

脚本不调用 Gradle。使用已有测试 APK 运行隔离的焦点和搜索操作测试，然后通过 ADB 方向键验证真实接口的推荐、详情、播放、分层返回、设置弹窗、系统输入法搜索和账号页取消。测试结束停留在推荐页，生成 XML 证据和 `report.json` 到 `app-tv/build/outputs/tv-qa/`。

只运行接口链路可加 `--skip-instrumentation`。真实接口超时、无视频或播放未推进会记为失败；脚本不扫码确认、不退出账号、不修改收藏。登录成功、过期、画质、多 P、续播以及番剧直播仍需单独验收。

仓库静态检查：`python3 scripts/check_tv_architecture.py`。最新验收结果见 [实施记录](../docs/TV_ADAPTATION.md)。
