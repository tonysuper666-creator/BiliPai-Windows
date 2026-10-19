# Roadmap 切片 #13 plr-gesture-tap-small-fixes：第 1 部分（全屏单击/双击）

分支 grokbot/slices-batch1，代码提交 62adbd2c6（基于 1ffb0e900）。

## 改动
- 新模块 desktop/tools/v046_fullscreen_tap_gestures.py，作为 extract-upstream-video-fullscreen-pager.py 处理 FullscreenPlayerOverlay.kt 的**最后一层**（排在所有已有层之后，紧接写盘前）。
  - 把主 onTap/onDoubleTap 代码块从 androidx 的 `detectTapGestures(` 换成编译集合里已有的原版 `detectPlayerTapGestures(`（生成的 DesktopOriginalPlayerTapGestures.kt，VideoPlayerSection 也在用），并插入 `import com.android.purebilibili.feature.video.ui.gesture.detectPlayerTapGestures`，与 v0.4.6 一致（d6bf8b16，文件 blob 80fc2e9a，第 98 行和第 742 行）。
  - 上游的两行有 sha256 钉住；两处编辑都按计数各 1 次，带偏移逆向证明（写入 v046-fullscreen-tap-gestures-proofs/FullscreenPlayerOverlay.kt.json）。要求代码块只有 onTap、onDoubleTap 两个参数，否则报错。比例菜单的 `detectTapGestures { showRatioMenu = false }` 保持不变（断言恰好 1 次）。重复应用或锚点变化都会失败。
- desktop/build.gradle.kts 只加了一行输入接线（Root 例外第④条）：`tasks.named("extractOriginalVideoFullscreenPager") { inputs.file("tools/v046_fullscreen_tap_gestures.py") }`。
- 新测试 desktop/tools/tests/test_v046_fullscreen_tap_gestures_source.py（3 项）。
- 没有编辑归档目录、upstream-sources.json、.github 或已有测试。
- 本切片第 2 部分（SettingsManager 整文件升到 v0.4.6）按路线图和 #35 一起做，本次没做。

## 证据
- Linux 编译：BUILD SUCCESSFUL。extractOriginalVideoFullscreenPager 重新执行，compileKotlin 0 错误。生成的 FullscreenPlayerOverlay.kt 里 detectPlayerTapGestures 出现 2 次（import 和调用各 1 次）。
- Python（带垫片）：
  - 新测试 3/3 OK。
  - 驱动同一生产者的 owner_follow 2/2、comment_selection_consumer_closure 5/5、v046_comment_list 26/26 都 OK。
  - test_extract_upstream_v031_repost_coin 有 1/4 失败，但在**干净的 3c4a93457 上同样失败**，失败点是 PortraitVideoPager.kt（v035 story 阶段 sha），与本层无关（把本层设成空操作后结果相同）。已写进 T11 报告的“额外发现”。
  - 验证集合：test_validate_source_cohort 25 OK（1 skip）、test_package_source_qualification 14 OK、test_sync_upstream 74 OK。
  - 符号闭包门 98 OK（2 项 Windows skip）。
- JVM：DesktopOriginalPlayerTapGesturesTest 8/8 通过（Linux）。
- 未验证：Windows 云端构建；真机上全屏单击/双击的手感（例如双击时间窗口、触控板）。不对 UI 行为下结论。
