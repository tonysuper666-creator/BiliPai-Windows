# Roadmap 切片 #35 vid-next-watch-and-toggle（含 #13 第 2 部分）：BLOCKED，需要 Root 定方案

基础：grokbot/slices-batch1 @ 30f492bf7。只做了只读分析，没有改代码。

## 现状
- 读取侧已经存在：生成的 DesktopOriginalNextWatchHintSettings.kt（player-full-controls 生产者）带有原版的 key `next_watch_hint_enabled` 和 getter（默认 true），NextWatchOverlay 会读它。
- **没有写入侧，也没有界面**：Windows 的播放设置页是 whole-playback-settings 生产者输出的“钉住的整页原版” PlaybackSettingsScreen（v0.2.5 钉，没有这个开关）；偏好来自从钉住的 SettingsManager 里选出的声明（DesktopOriginalPlaybackSettingsPreferences.kt），里面没有 setNextWatchHintEnabled。分享目录 DesktopSettingsShareWindowsCatalog.kt:126 的 playback booleans 里也没有这个 key。
- 上游 v0.4.6：SettingsManager.kt:2152 有 setNextWatchHintEnabled，:8501 把它登记为可分享；PlaybackSettingsScreen.kt:1326、1460-1470 有 SettingsItemAnchor("playback.next_watch_hint_enabled") 开关。

## 为什么卡住
T9 要求 #13 和 #35 “同一文件一次整文件升到 v0.4.6，不要各改一个 hunk”。实测这个整文件迁移的规模：
- SettingsManager.kt 是 policy-extract，有 48 个 features；desktop/tools 下有 **47 个生产者**按规范路径读它（另有 6 个测试），还有 **6 个已有归档**各自携带不同版本的 SettingsManager.kt。
- 规范 v0.2.5 副本不能改（v025-canonical-sources.json 和上游副本根目录都禁止），所以“整文件换到 v0.4.6”只能让 47 个生产者全部改为从新归档读，并重算每条链的前驱哈希和 proof。上游 v0.2.5 → v0.4.6 的差异是 +416/-60 行，文件 8853 行，inverse_proof 是平方复杂度。
- 这远超 90 分钟，而且是全局性的改动，会和 Root 自己负责的 #17、#11、#7、#8 大面积冲突。所以按“越界即停”处理。

## 给 Root 的选项（需要选一个）
1. **窄方案（推荐先做）**：新归档 v046-next-watch-toggle，只携带 v0.4.6 的 SettingsManager.kt 和 PlaybackSettingsScreen.kt 作为来源，不整文件替换。
   - 在 whole-playback-settings 生产者末尾加一层：从上游**整段选出** setNextWatchHintEnabled 声明（选声明，不是 hunk），加进 DesktopOriginalPlaybackSettingsPreferences；把上游这个开关的完整 SettingsItemAnchor 块插到 auto_play_enabled 之前。
   - 分享目录（desktop/src）加 `next_watch_hint_enabled`。
   - 这违反了 T9 的“不要各改一个 hunk”，所以需要 Root 明确同意。
2. **全量方案**：由 Root 规划 SettingsManager 统一到 v0.4.6（建议作为独立的基础切片，由 Root 来做或拆成多批）。
3. 只做分享目录这一侧：没有界面的话没有意义，不推荐。

#13 第 2 部分（滑动 seek 默认值、GestureLevelOverlay）同样依赖这个决定；另外路线图本身也说，只有当 Windows 有鼠标拖动 seek 时才值得暴露。
