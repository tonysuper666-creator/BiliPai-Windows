package com.bilipai.desktop.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.bilipai.desktop.settings.desktopSettingsSearchFocusAnchor
import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences as Preferences
import com.android.purebilibili.core.store.DesktopOriginalReplySettings
import com.android.purebilibili.core.store.DesktopOriginalTabletAudioSettings
import com.android.purebilibili.core.store.PlaybackCompletionBehavior
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettings
import com.android.purebilibili.feature.settings.resolveDefaultAudioQualityOptions
import com.android.purebilibili.feature.settings.resolveDefaultPlaybackQualityOptions
import com.android.purebilibili.feature.video.controller.QualityManager
import com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference
import com.bilipai.desktop.settings.DesktopOriginalPlaybackSettingsBindings

/** The default-quality chips Windows offers. Settings import accepts more ids (6, 74, 116, 125, 126, 127). */
internal val desktopWindowsDefaultQualityChoices =
    listOf(16 to "360P", 32 to "480P", 64 to "720P", 80 to "1080P", 112 to "1080P+", 120 to "4K")

/** Read-only caption for a stored default quality that no chip represents, else null. Labels come from the
 * original settings table first, then the original play-url QualityManager table; no table is invented here. */
internal fun desktopWindowsOffListQualityCaption(quality: Int?): String? {
    if (quality == null || desktopWindowsDefaultQualityChoices.any { it.first == quality }) return null
    val label = resolveDefaultPlaybackQualityOptions().firstOrNull { it.value == quality }?.label
        ?: QualityManager().getQualityLabel(quality)
    return "当前保存的默认画质为 ${label}（${quality}），不在上方选项中；选择上方画质后将统一替换。"
}

/** Windows presentation over the existing canonical recipes, page permit and mirror bridge.
 * No Android gestures, system permissions, sensors, portrait modes or copied preference store.
 */
@Composable
internal fun DesktopWindowsPlaybackSettings(bindings: DesktopOriginalPlaybackSettingsBindings, onBack: () -> Unit) {
    val context = bindings.context
    val compositionScope = rememberCoroutineScope { bindings.writeContext }
    val writer = remember(bindings, compositionScope) { bindings.bindWriteScope(compositionScope) }
    val state by bindings.state.collectAsState()
    val speed = desktopWindowsSettingsValue(remember(context) { Preferences.getDefaultPlaybackSpeed(context) })
    val speeds = desktopWindowsSettingsValue(remember(context) { Preferences.getPlaybackSpeedOptions(context) })
    val rememberSpeed = desktopWindowsSettingsValue(remember(context) { Preferences.getRememberLastPlaybackSpeed(context) })
    val codec = desktopWindowsSettingsValue(remember(context) { Preferences.getVideoCodec(context) })
    val fallbackCodec = desktopWindowsSettingsValue(remember(context) { Preferences.getVideoSecondCodec(context) })
    val wifiQuality = desktopWindowsSettingsValue(remember(context) { Preferences.getWifiQuality(context) })
    val otherNetworkQuality = desktopWindowsSettingsValue(remember(context) { Preferences.getMobileQuality(context) })
    val quality = wifiQuality.takeIf { it == otherNetworkQuality }
    val qualityReady = wifiQuality != null && otherNetworkQuality != null
    val highest = desktopWindowsSettingsValue(remember(context) { Preferences.getAutoHighestQuality(context) })
    val audioQuality = desktopWindowsSettingsValue(remember(context) { PlayerSettings.getDefaultAudioQuality(context) })
    val subtitle = desktopWindowsSettingsValue(remember(context) { Preferences.getSubtitleAutoPreference(context) })
    val playOnOpen = desktopWindowsSettingsValue(remember(context) { Preferences.getClickToPlay(context) })
    val backgroundPlayback = desktopWindowsSettingsValue(remember(context) { Preferences.getBackgroundPlaybackEnabled(context) })
    val resumePrompt = desktopWindowsSettingsValue(remember(context) { Preferences.getResumePlaybackPromptEnabled(context) })
    val completion = desktopWindowsSettingsValue(remember(context) { Preferences.getPlaybackCompletionBehavior(context) })
    val autoPlayNext = desktopWindowsSettingsValue(remember(context) { Preferences.getAutoPlay(context) })
    val playlistAutoContinue = desktopWindowsSettingsValue(remember(context) { Preferences.getExternalPlaylistAutoContinue(context) })
    val commentSort = desktopWindowsSettingsValue(remember(context) { DesktopOriginalReplySettings.getCommentDefaultSortMode(context.pluginContext) })
    val detailedCommentTime = desktopWindowsSettingsValue(remember(context) { Preferences.getDetailedCommentTimeEnabled(context) })
    val commentDecorations = desktopWindowsSettingsValue(remember(context) { Preferences.getCommentMemberDecorationsEnabled(context) })
    val commentAvatarPush = desktopWindowsSettingsValue(remember(context) { Preferences.getCommentAvatarPushEnabled(context) })
    val codecChoices = listOf("avc1" to "AVC / H.264", "hev1" to "HEVC / H.265", "av01" to "AV1")
    DesktopWindowsSettingsPane("播放与音频", onBack) {
        DesktopWindowsSettingsGroup("解码与画质", modifier = Modifier.desktopSettingsSearchFocusAnchor(
            SettingsSearchTarget.PLAYBACK, "windows_playback_quality")) {
            DesktopWindowsSettingsSwitch("硬件解码", state.hwDecode,
                description = "使用显卡解码；更改后由播放器按原有恢复策略应用。",
                onChange = bindings::toggleHwDecode)
            DesktopWindowsSettingsChoice("首选视频编码", codec, codecChoices) { value ->
                writer.launch { Preferences.setVideoCodec(context, value) }
            }
            DesktopWindowsSettingsChoice("无法播放时的备用编码", fallbackCodec, codecChoices) { value ->
                writer.launch { Preferences.setVideoSecondCodec(context, value) }
            }
            DesktopWindowsSettingsSwitch("优先最高可用画质", highest) { enabled ->
                writer.launch { Preferences.setAutoHighestQuality(context, enabled) }
            }
            if (highest == false) {
            DesktopWindowsSettingsChoice("默认画质", quality, desktopWindowsDefaultQualityChoices,
                enabled = highest == false && qualityReady, allowUnselected = qualityReady, presentation = DesktopWindowsSettingsChoicePresentation.DROPDOWN) { value ->
                // The one original operation journal publishes both network keys and their
                // original quality_settings mirrors together. Ethernet is not Wi-Fi.
                writer.launch {
                    Preferences.setWifiQuality(context, value)
                    Preferences.setMobileQuality(context, value)
                }
            }
            if (qualityReady && quality == null) Text("旧网络画质偏好不同；选择后统一应用到 Windows 网络。",
                style = MaterialTheme.typography.bodySmall)
            desktopWindowsOffListQualityCaption(quality)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text("实际画质与编码取决于视频和账号权限。", style = MaterialTheme.typography.bodySmall)
        }
        DesktopWindowsSettingsGroup("倍速与字幕", modifier = Modifier.desktopSettingsSearchFocusAnchor(
            SettingsSearchTarget.PLAYBACK, "windows_playback_speed_subtitle")) {
            DesktopWindowsSettingsSwitch("记住上次播放倍速", rememberSpeed) { enabled ->
                writer.launch { Preferences.setRememberLastPlaybackSpeed(context, enabled) }
            }
            DesktopWindowsSettingsChoice("默认倍速", speed,
                speeds.orEmpty().map { it to "${playbackSpeedLabel(it.toDouble())}×" }) { value ->
                writer.launch { Preferences.setDefaultPlaybackSpeed(context, value) }
            }
            DesktopWindowsSettingsChoice("自动启用字幕", subtitle, listOf(
                SubtitleAutoPreference.OFF to "关闭", SubtitleAutoPreference.ON to "开启",
                SubtitleAutoPreference.WITHOUT_AI to "排除 AI 字幕", SubtitleAutoPreference.AUTO to "自动")) { value ->
                writer.launch { Preferences.setSubtitleAutoPreference(context, value) }
            }
        }
        DesktopWindowsSettingsGroup("播放行为", modifier = Modifier.desktopSettingsSearchFocusAnchor(
            SettingsSearchTarget.PLAYBACK, "windows_playback_behavior")) {
            DesktopWindowsSettingsSwitch("进入视频自动播放", playOnOpen,
                description = "关闭后，进入视频详情页需手动开始播放。") { enabled ->
                writer.launch { Preferences.setClickToPlay(context, enabled) }
            }
            DesktopWindowsSettingsSwitch("自动跳过片头片尾", state.autoSkipOpEd,
                description = "番剧提供跳过区间时，自动跳过片头和片尾。",
                onChange = bindings::toggleAutoSkipOpEd)
            DesktopWindowsSettingsSwitch("后台播放", backgroundPlayback,
                description = "最小化窗口后继续播放；听视频和小窗遵循各自的播放模式。") { enabled ->
                writer.launch { Preferences.setBackgroundPlaybackEnabled(context, enabled) }
            }
            DesktopWindowsSettingsSwitch("历史续播提示", resumePrompt,
                description = "发现历史播放进度时提示继续播放。") { enabled ->
                writer.launch { Preferences.setResumePlaybackPromptEnabled(context, enabled) }
            }
            // The two real gates of 自动连播 (PlaybackCompletionPolicy); both default on and are read from the auto_play_cache mirror.
            DesktopWindowsSettingsSwitch("自动播放下一个", autoPlayNext,
                description = "分P/合集自动播放下一集，普通单视频播完暂停；“视频播完后”为“自动连播”时生效。") { enabled ->
                writer.launch { Preferences.setAutoPlay(context, enabled) }
            }
            DesktopWindowsSettingsSwitch("列表/收藏夹连续播放", playlistAutoContinue,
                description = "控制收藏夹、稍后再看、合集等列表播放完后是否继续下一条；“视频播完后”为“自动连播”时生效。") { enabled ->
                writer.launch { Preferences.setExternalPlaylistAutoContinue(context, enabled) }
            }
            DesktopWindowsSettingsChoice("视频播完后", completion,
                PlaybackCompletionBehavior.entries.map { it to it.label }) { value ->
                writer.launch { Preferences.setPlaybackCompletionBehavior(context, value) }
            }
        }
        DesktopWindowsSettingsGroup("评论", modifier = Modifier.desktopSettingsSearchFocusAnchor(
            SettingsSearchTarget.PLAYBACK, "windows_playback_comments")) {
            DesktopWindowsSettingsChoice("默认评论排序", commentSort,
                listOf(3 to "按热度", 2 to "按时间")) { value ->
                // The existing whole original setter joins this page's canonical/mirror journal.
                // Its class name is historical; this method has no tablet-only dependency.
                writer.launch { DesktopOriginalTabletAudioSettings.setCommentDefaultSortMode(context, value) }
            }
            DesktopWindowsSettingsSwitch("详细评论时间", detailedCommentTime,
                description = "显示完整时间；关闭后沿用原有相对时间规则。") { enabled ->
                writer.launch { Preferences.setDetailedCommentTimeEnabled(context, enabled) }
            }
            DesktopWindowsSettingsSwitch("评论头像跟随", commentAvatarPush,
                description = "滚动长评论时，头像跟随评论内容显示。") { enabled ->
                writer.launch { Preferences.setCommentAvatarPushEnabled(context, enabled) }
            }
            DesktopWindowsSettingsSwitch("评论区个性装扮", commentDecorations,
                description = "显示粉丝牌、铭牌和装扮卡片。") { enabled ->
                writer.launch { Preferences.setCommentMemberDecorationsEnabled(context, enabled) }
            }
        }
        DesktopWindowsSettingsGroup("音频", modifier = Modifier.desktopSettingsSearchFocusAnchor(
            SettingsSearchTarget.PLAYBACK, "windows_audio_output")) {
            DesktopWindowsSettingsChoice("默认音质", audioQuality,
                resolveDefaultAudioQualityOptions().map { it.value to it.label }) { value ->
                writer.launch { PlayerSettings.setDefaultAudioQuality(context, value) }
            }
            DesktopWindowsAudioOutputSettings(context)
        }
        DesktopWindowsSettingsGroup("视频增强", modifier = Modifier.desktopSettingsSearchFocusAnchor(
            SettingsSearchTarget.PLAYBACK, "windows_video_enhancement")) {
            DesktopWindowsVideoEnhancementSettingsContent()
        }
        DesktopWindowsSettingsGroup("桌面操作") {
            Text("F11：窗口全屏　　Esc：退出全屏或返回")
            Text("音量、静音和弹幕开关可直接在播放器中调整。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
