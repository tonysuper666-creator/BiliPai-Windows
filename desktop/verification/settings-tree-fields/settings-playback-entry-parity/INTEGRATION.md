原播放分类入口 / 共用详情 entry 交接

本独立目录以 c5f8a21d17a33183b03209ce0de3e450c70cfd8c 为主线基线；未编辑 main、共享 Gradle、manifest 或已冻结 Privacy 等目录，未开启 HWND/账号/网络请求。请先核对 frozen-handoff.json 与 owned-files.json 摘要。

复制与生成

- owned-files.json 映射两个工具文件：新 `desktop/tools/extract-upstream-settings-entries.py` 与 `desktop/tools/tests/test_extract_upstream_settings_entries.py`。没有新 parallel player preference schema、enum、38-state/53-action facade 或 Store。
- `inventory.json` 只有已注册原 SettingsSections.kt，mode=policy-extract、LF SHA c8178e7a048512348678555b529180f7a969a658eca4019d484aff8603581a1b；只合并 settings-playback-entry-parity feature，不新增重复 source record。**零新增源身份、资源或依赖**。
- 生成：`python desktop/tools/extract-upstream-settings-entries.py --repo <repo> --output <generated>`。建议 generated 目录 `desktop/build/generated/settingsEntries`，两个输出均加入同一 desktop compileKotlin module。任务输入为该原 SettingsSections.kt、entry tool、plugins/media/sync 四个工具文件。`--inventory` 输出唯一原身份 JSON。
- 唯一共享 helper owner 本 lane；其它 owner（包括 Backend storage）只引用这些 FQN，不复制声明或另建同名 file。现 category extractor 已有 SettingsCardGroup 与 private→internal 的 SettingsAdaptiveDivider，继续由 Root category 单份持有。

准确同包契约

```kotlin
package com.android.purebilibili.feature.settings

internal data class SettingsDetailEntry(
    val target: SettingsSearchTarget,
    val title: String,
    val value: String,
    val openFocus: SettingsSceneDetailFocus? = null,
    val onClick: () -> Unit,
)
@Composable
internal fun SettingsDetailGroup(title: String, content: @Composable ColumnScope.() -> Unit)
@Composable
internal fun SettingsDetailEntrySection(entries: List<SettingsDetailEntry>)
@Composable
internal fun SettingsPlaybackCategoryEntrySection(onPlaybackClick: () -> Unit)
```

全部 internal 与原一致，Root 同 Gradle module 可直接 import；不需要扩大可见性。`SettingsDetailEntries.kt` 完整原 data class/Group/EntrySection，唯一平台变化是 painterResource → rememberVectorPainter(DesktopSettingsVectors.vector(it))。EntrySection 仍按原 entries.size 计算 siblingTints、逐条原 visual、原 SettingsCardGroup/Divider；**先 submit/clear 原 SettingsSearchFocusController，再 entry.onClick**。

播放 root 分类（原 canonical 名为 PLAYBACK_QUALITY；源码没有 PLAYBACK_CATEGORY enum）使用 `SettingsPlaybackCategoryEntrySection`。该薄函数只替换原 `actions.onPlaybackClick` 为明确回调，保留两段原 SettingsDetailGroup/SettingsDetailEntrySection calls 与12dp spacer。原 SettingsRootCategoryEntranceSection/Modifier.entrance 不在本批闭包，不做空 facade、假动画或声称入口动画已移植。

| 组/行 | 原 title/value 来源 | visual target | 点击前的原 focus | Root 实际消费 |
|---|---|---|---|---|
| 播放与画质 / 播放设置 | settingsDestinationCopy(PLAYBACK) | PLAYBACK_QUALITY | PLAYBACK / PLAYBACK_DECODER | 现 PlaybackSettingsDialog |
| 互动与评论 / 互动与评论 | settingsDestinationCopy(INTERACTION_COMMENT) | INTERACTION_COMMENT | PLAYBACK / PLAYBACK_INTERACTION | 同一现 PlaybackSettingsDialog |

不要用通用 resolveSettingsSceneDetailFocus(PLAYBACK_QUALITY) 覆盖第一条：通用 policy 返回 PLAYBACK_NETWORK，但原 canonical root 分类入口明确传 PLAYBACK_DECODER。也不要误抽旧 deprecated CONTENT_PLAYBACK 分支，其“画质与播放”行确实走 NETWORK。本 batch 保留 canonical 两个实际调用。

Root 接缝示例（Root owns Shell，按现导航结构整合）

```kotlin
SettingsPlaybackCategoryEntrySection {
    val pending = SettingsSearchFocusController.request.value
    // 本回调发生在原 focus submit 之后；不要清掉它。
    settingsNavigator.openDetail(SettingsSearchTarget.PLAYBACK, pending?.focusId)
}
// 该 PLAYBACK detail 由 Root 现真实 dialog 承接；只传现数据，不构造默认 facade。
PlaybackSettingsDialog(
    preferences = actualPlayerPreferences,
    onPreferencesChange = actualPlayerPreferenceWriter,
    onDismiss = { settingsNavigator.pop() },
)
```

现 `ui/PlayerSettings.kt` 已有 PLAYBACK_DECODER 与 PLAYBACK_INTERACTION 的 desktopSettingsSearchFocusAnchor；由 Root 弹出真实 dialog 后消费 request，close/back 保持现导航生命周期。fixture 验证了回调即时读到准确 pending request；遵守本任务“不打开 HWND”的边界，**未声称真实 dialog 弹出或 native focus/window 成功**。

复用其它 owner 的判断

原通用 Entry/Group/EntrySection 完全无需 root category state/actions，因此可由其它 owner 传自己的真实 callbacks 和原 SettingsSceneDetailFocus。实际双风格 fixture 已验证另一 APPEARANCE owner focus，以及 openFocus=null 时原 clear 逻辑和旧 token 不清新 focus。声明只生成一份。

- 原 APPEARANCE_THEME 的 INTERFACE_THEME entry、HOME_RECOMMENDATION 的 HOME_FEED entry、NAVIGATION_INTERACTION 的导航/动效 entries，可按各 owner 真实后端与目的页复用这三声明，不默认模拟尚未移植的页面/动作。
- 原 Privacy/DataStorage/Plugin category 能复用 Group，但内部不全是 DetailEntrySection。Backend 已核 DataStorageSection 前两行是原直接 SettingClickableItem，palette count=7/paletteOffset=2，应保留它们原调用与 tint，不能改成这套默认 offset=0 的 entry renderer 来伪造视觉等价。
- 此批不生成其它完整 category content、SettingsRootCategoryState/Actions 或入口动画，也不把“共用 schema 可用”计为其它产品路径已完成。

验证

`compile-evidence.json` 保存实际 Kotlin2.4.0 + Compose2.4.0 的3源独立编译、逐源 SHA、不可变 product snapshot 与先前已冻结原 category/search/vectors helper classes SHA；没有共享 Gradle。5 Python extraction tests 完整比对原 data/group 与 renderer逆变换；逐 token 对照两 canonical constructor calls；确保零新增 asset/重复 helper、可重复输出以及上游新增 state 依赖 fail closed。

`proof/result.json`：M3/Miuix 各4次 ImageComposeScene pointer，共8次；原两入口文案/准确 decoder+interaction focus 在 callback 前提交、替换旧 focus，null entry 清除原请求，另一个 owner focus 实际通过，旧 token 不清后续 request。两张 PNG 已目视检查：原 group heading/文案/icons/tint/preference styles 保留。后两条测试 entry 清楚标注 fixture，未放入生成的产品入口。完整播放器 dialog/native入口尚由 Root 后续集成验证。
