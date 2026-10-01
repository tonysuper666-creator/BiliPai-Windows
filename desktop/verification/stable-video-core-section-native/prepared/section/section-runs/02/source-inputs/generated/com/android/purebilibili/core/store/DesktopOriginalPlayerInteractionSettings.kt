package com.android.purebilibili.core.store
import com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference
data class PlayerInteractionSettings(
    val gestureSensitivity: Float = 1.0f,
    val doubleTapLikeEnabled: Boolean = true,
    val doubleTapSeekEnabled: Boolean = false,
    val portraitSwipeToFullscreenEnabled: Boolean = true,
    val centerSwipeToFullscreenEnabled: Boolean = true,
    val slideVolumeBrightnessEnabled: Boolean = true,
    val setSystemBrightnessEnabled: Boolean = false,
    val pipNoDanmakuEnabled: Boolean = false,
    val seekForwardSeconds: Int = 10,
    val seekBackwardSeconds: Int = 10,
    val inlineSwipeSeekSeconds: Int = 30,
    val fullscreenSwipeSeekSeconds: Int = 15,
    val fullscreenSwipeSeekEnabled: Boolean = true,
    val fullscreenGestureReverse: Boolean = false,
    val hideVideoPageStatusBar: Boolean = false,
    /** 竖屏详情横屏视频上下黑边动态模糊，默认开启。 */
    val portraitLetterboxAmbientHaze: Boolean = true,
    val tabletCommentPanelWidthPreset: TabletCommentPanelWidthPreset =
        TabletCommentPanelWidthPreset.STANDARD,
    val autoEnterFullscreenEnabled: Boolean = false,
    val autoExitFullscreenEnabled: Boolean = true,
    /**
     * 自动退出全屏粒度。旧布尔 [autoExitFullscreenEnabled]=true 映射为 [ALL_PARTS]，
     * 避免连播下一P 时被 STATE_ENDED 踢回竖屏。
     */
    val autoExitFullscreenMode: AutoExitFullscreenMode = AutoExitFullscreenMode.ALL_PARTS,
    val fixedFullscreenAspectRatio: FullscreenAspectRatio = FullscreenAspectRatio.FIT,
    val subtitleAutoPreference: SubtitleAutoPreference = SubtitleAutoPreference.OFF,
    val longPressSpeed: Float = 2.0f,
    val longPressSpeedLockEnabled: Boolean = false,
    val longPressSpeedLockHintShown: Boolean = false,
    /**
     * 长按倍速浮层是否显示关闭（×）按钮。默认 false（始终隐藏），
     * 不在设置页暴露，避免第二指点 × 打断长按加速。
     */
    val longPressSpeedHintCloseEnabled: Boolean = false,
    val longPressSpeedHintHidden: Boolean = false,
    val longPressSpeedHintScale: Float = 1.0f,
    val longPressSpeedHintAlpha: Float = 0.5f,
    val subtitleVerticalOffsetFraction: Float = 0.0f,
    /** Vertical offset for portrait immersive / story subtitles (independent of landscape). */
    val subtitlePortraitVerticalOffsetFraction: Float = 0.0f,
    /** Prevent player gestures from accidentally moving subtitle overlays. */
    val subtitlePositionLocked: Boolean = true,
    val twoFingerVerticalSpeedEnabled: Boolean = false,
    val twoFingerHorizontalSpeedEnabled: Boolean = false,
    val hiResLongPressCompatHintShown: Boolean = false,
    val directPortraitStoryEntry: Boolean = false,
    val launchToPortraitFeedOnStartup: Boolean = false
)

enum class AutoExitFullscreenMode(val value: Int, val label: String, val subtitle: String) {
    OFF(0, "关闭", "播放结束不自动退出全屏"),
    CURRENT_PART(1, "当前分P结束", "每个分P/视频播完就退出全屏"),
    ALL_PARTS(2, "全部连播结束", "合集/分P/列表全部播完再退出全屏");

    companion object {
        fun fromValue(value: Int): AutoExitFullscreenMode =
            entries.find { it.value == value } ?: ALL_PARTS

        /** 兼容旧布尔：true→全部结束再退，false→关闭 */
        fun fromLegacyEnabled(enabled: Boolean): AutoExitFullscreenMode =
            if (enabled) ALL_PARTS else OFF
    }
}

internal fun resolveAutoExitFullscreenMode(
    modeValue: Int?,
    legacyEnabled: Boolean?,
): AutoExitFullscreenMode {
    if (modeValue != null) return AutoExitFullscreenMode.fromValue(modeValue)
    return AutoExitFullscreenMode.fromLegacyEnabled(legacyEnabled ?: true)
}

enum class FullscreenMode(val value: Int, val label: String, val description: String) {
    AUTO(0, "自动", "按视频方向自动切换全屏方向"),
    NONE(1, "不改方向", "保持当前方向，仅切换全屏 UI"),
    VERTICAL(2, "竖屏", "进入全屏时保持竖屏"),
    HORIZONTAL(3, "横屏", "进入全屏时切换到横屏");

    companion object {
        fun fromValue(value: Int): FullscreenMode {
            return when (value) {
                // 兼容历史配置：已下线的模式统一回收为 AUTO，避免老用户进入无感知分支。
                4, 5 -> AUTO
                else -> entries.find { it.value == value } ?: AUTO
            }
        }
    }
}

enum class FullscreenAspectRatio(val value: Int, val label: String, val description: String) {
    FIT(0, "适应", "完整显示画面，尽量不裁切"),
    FILL(1, "填充", "填满屏幕，可能裁切边缘"),
    RATIO_16_9(2, "16:9", "优先按 16:9 展示画面"),
    RATIO_4_3(3, "4:3", "优先按 4:3 展示画面"),
    STRETCH(4, "拉伸", "铺满屏幕，可能导致画面变形");

    companion object {
        fun fromValue(value: Int): FullscreenAspectRatio {
            return entries.find { it.value == value } ?: FIT
        }
    }
}

enum class PortraitPlayerCollapseMode(val value: Int, val label: String, val description: String) {
    OFF(0, "关闭", "不自动缩小播放器"),
    INTRO_ONLY(1, "竖屏", "竖屏视频评论区或简介上滑时缩小播放器"),
    COMMENT_ONLY(2, "横屏", "仅横屏视频详情页滚动时缩小播放器"),
    BOTH(3, "全部", "横竖屏视频都使用播放器缩小策略"),
    PAUSED_ONLY(4, "暂停时", "横竖屏视频暂停后，下滑评论或简介可缩小播放器");

    val enablesPortraitVideo: Boolean
        get() = this == INTRO_ONLY || this == BOTH || this == PAUSED_ONLY

    val enablesLandscapeVideo: Boolean
        get() = this == COMMENT_ONLY || this == BOTH || this == PAUSED_ONLY

    fun enablesVideoOrientation(isVerticalVideo: Boolean): Boolean {
        return if (isVerticalVideo) enablesPortraitVideo else enablesLandscapeVideo
    }

    val enablesIntro: Boolean
        get() = this != OFF

    val enablesComment: Boolean
        get() = this != OFF

    companion object {
        fun fromValue(value: Int): PortraitPlayerCollapseMode {
            return entries.find { it.value == value } ?: OFF
        }

        fun fromLegacySwipeHide(enabled: Boolean): PortraitPlayerCollapseMode {
            return if (enabled) INTRO_ONLY else OFF
        }
    }
}

enum class TabletCommentPanelWidthPreset(
    val value: Int,
    val label: String
) {
    COMPACT(0, "窄"),
    STANDARD(1, "标准"),
    WIDE(2, "宽"),
    ULTRA_WIDE(3, "超宽");

    companion object {
        fun fromValue(value: Int): TabletCommentPanelWidthPreset =
            entries.find { it.value == value } ?: STANDARD
    }
}

internal const val LONG_PRESS_SPEED_HINT_DEFAULT_SCALE = 1.0f

internal const val LONG_PRESS_SPEED_HINT_SCALE_MIN = 0.8f

internal const val LONG_PRESS_SPEED_HINT_SCALE_MAX = 1.5f

internal const val LONG_PRESS_SPEED_HINT_DEFAULT_ALPHA = 0.5f

internal const val LONG_PRESS_SPEED_HINT_ALPHA_MIN = 0.3f

internal const val LONG_PRESS_SPEED_HINT_ALPHA_MAX = 1.0f

internal fun normalizeLongPressSpeedHintScale(value: Float): Float =
    if (!value.isFinite()) LONG_PRESS_SPEED_HINT_DEFAULT_SCALE
    else value.coerceIn(LONG_PRESS_SPEED_HINT_SCALE_MIN, LONG_PRESS_SPEED_HINT_SCALE_MAX)

/** 长按倍速提示背景/内容透明度（0.3–1.0，默认 0.5）。 */

internal fun normalizeLongPressSpeedHintAlpha(value: Float): Float =
    if (!value.isFinite()) LONG_PRESS_SPEED_HINT_DEFAULT_ALPHA
    else value.coerceIn(LONG_PRESS_SPEED_HINT_ALPHA_MIN, LONG_PRESS_SPEED_HINT_ALPHA_MAX)

internal fun resolveDefaultPlayerDiagnosticLoggingEnabled(isDebugBuild: Boolean): Boolean {
    return !isDebugBuild
}

