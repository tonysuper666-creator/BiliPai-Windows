# VERIFY desktop/integration-263@dd37c7abc（只汇报，不改代码）

- 对象：`dd37c7abc Reapply the early video detail boundary to the v037 playback use case`（= origin/desktop/integration-263 当前头）。相对 89a0aee85 多 3 个提交：4ed723cac、a805a9a2e、dd37c7abc。
- 摘入核对：三者与我在 T8 分支上的 44155b273、a6fb2344f、456c38d69 的 `git patch-id --stable` 完全相同（3c92b3ba11c6 / e44592ea765e / d99545706817），即逐字摘入，无合并改动。
- 命令行：grokbot/linux-bootstrap 的 bootstrap/gradle.sh、test.sh（单 worker、-Xmx6g -Xss16m、三个 Windows `-x`，测试加 `-x prepareJsWorkerResources`；**未排除** prepareVeyraRuntimeVerifier）。
- 时间：2026-10-19 02:29–02:47 UTC。

## 结果

| 项 | 结果 | 耗时 |
|---|---|---|
| 编译：`rm -rf desktop/build` + make-stubs + compileKotlin compileJava compileTestKotlin compileTestJava `--no-build-cache` | BUILD SUCCESSFUL，0 个 `e:`；警告 main 457 / test 98（与 89a0aee85 相同，T8 未新增警告） | 6m26s |
| com.bilipai.desktop.danmaku.* 全包 | 26 类，196 测试，**0 失败** | 合计约 6 分钟 |
| com.bilipai.desktop.ui.* 全包（含 DesktopWindowsEarlyVideoDetailTest） | 188 类，1347 测试，3 失败，均为已知 Linux 环境问题：DesktopOriginalPortraitMetadataPreparationTest、DesktopOriginalSponsorPresentationAssemblyTest、DesktopOriginalVideoCdnSelectionAssemblyTest 的 initializationError “JS worker resource path is invalid”（全零 JS worker 桩所致，与 T5/T6d/T8b 基线相同）。DesktopWindowsEarlyVideoDetailTest 4/4 通过 | 同一次运行 |
| nvidia 14 + 评论 23（顺带，供 T7） | 37 类，566 测试，0 失败 | 同一次运行 |
| test_early_video_detail_boundary.py | Ran 3，OK | 1 s |
| test_symbol_closure.py | Ran 98，OK，skipped=2（ScratchPathTests 的两个 Windows 长路径规则）；CurrentIntegrationTree 8 项实际运行并通过（v0.4.6 对象 d6bf8b165 已在本地对象库） | 3m48s |

结论：合并后无冲突、无新增失败。与 89a0aee85 相比唯一的新增内容（T8a、T8b）对应测试全部通过。

## 证据分类

| 类别 | 内容 |
|---|---|
| Linux 编译 | 已验证（无缓存） |
| Python | 已验证：early-detail 3/3，符号闭包 98（2 个 Windows 跳过） |
| JVM（Linux） | 已验证：danmaku 196/196；ui 1344/1347（3 个 JS worker 环境失败）；37 类 566/566 |
| 未验证 | Windows 云验证、真机、UI 外观、BAS 实际渲染、NVIDIA、音频、安装器 |

没有修改任何代码。
