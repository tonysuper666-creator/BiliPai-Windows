package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DanmakuSettings as OriginalDanmakuSettings
import com.android.purebilibili.core.store.DanmakuSettingsScope
import com.bilipai.desktop.danmaku.DanmakuSettings
import com.bilipai.desktop.settings.DesktopOriginalDanmakuPreferences
import kotlinx.serialization.json.*

/** Explicit Root presentation signal. It cannot be inferred from video aspect ratio. */
internal enum class DesktopDanmakuPresentation {
    INLINE,
    FULLSCREEN_LANDSCAPE,
    FULLSCREEN_PORTRAIT,
}
/** Required Root getter observes actual Main placement and supported physical-monitor presentation. */
internal fun interface DesktopDanmakuPresentationBinding {
    fun currentPresentation():DesktopDanmakuPresentation
}

internal fun DesktopDanmakuPresentation.originalScope():DanmakuSettingsScope =
    com.android.purebilibili.feature.video.ui.section.resolveVideoPlayerDanmakuSettingsScope(
        isFullscreen=this!=DesktopDanmakuPresentation.INLINE,
        isPortraitFullscreen=this==DesktopDanmakuPresentation.FULLSCREEN_PORTRAIT,
    )

/** Ephemeral projection, not a second settings authority. Original parsed full rules take precedence. */
internal fun projectOriginalDanmakuRendererSettings(
    windows:DanmakuSettings,
    original:OriginalDanmakuSettings,
):DanmakuSettings = windows.copy(
    enabled=original.enabled,opacity=original.opacity,fontScale=original.fontScale,
    fontWeight=original.fontWeight,speedFactor=original.speed,
    displayAreaRatio=original.displayArea,scrollDurationSeconds=original.scrollDurationSeconds,
    staticDurationSeconds=original.staticDurationSeconds,lineHeight=original.lineHeight,
    scrollFixedVelocity=original.scrollFixedVelocity,staticDanmakuToScroll=original.staticDanmakuToScroll,
    massiveMode=original.massiveMode,weightFilterLevel=original.weightFilterLevel,
    smartOcclusionEnabled=original.smartOcclusion,
    strokeEnabled=original.strokeWidth>0f,strokeWidth=original.strokeWidth,
    allowScroll=original.allowScroll,allowTop=original.allowTop,allowBottom=original.allowBottom,
    allowColorful=original.allowColorful,allowSpecial=original.allowSpecial,
    mergeDuplicates=original.mergeDuplicates,duplicateMergeWindowMs=original.duplicateMergeWindowMs,
    duplicateMergeCountThreshold=original.duplicateMergeCountThreshold,
    hideInteractiveCommands=original.hideInteractiveCommands,
    blockedKeywords=emptyList(),blockedRules=original.blockRules,
).normalized()

/** Only missing ORIGINAL legacy keys are imported, preserving scoped/legacy authority and original version key. */
internal suspend fun DesktopOriginalDanmakuPreferences.importLegacyWindowsDanmakuIfAbsent(windows:DanmakuSettings) {
    val rules=(windows.blockedKeywords+windows.blockedRules).distinct().joinToString("\n")
    migrateMissingOriginalLegacyValues(mapOf(
        "danmaku_enabled" to JsonPrimitive(windows.enabled),
        "danmaku_opacity" to JsonPrimitive(windows.opacity),
        "danmaku_font_scale" to JsonPrimitive(windows.fontScale),
        "danmaku_speed" to JsonPrimitive(windows.speedFactor),
        "danmaku_area" to JsonPrimitive(windows.displayAreaRatio),
        "danmaku_font_weight" to JsonPrimitive(windows.fontWeight),
        "danmaku_stroke_width" to JsonPrimitive(if(windows.strokeEnabled)windows.strokeWidth else 0f),
        "danmaku_line_height" to JsonPrimitive(windows.lineHeight),
        "danmaku_scroll_duration_seconds" to JsonPrimitive(windows.scrollDurationSeconds),
        "danmaku_static_duration_seconds" to JsonPrimitive(windows.staticDurationSeconds),
        "danmaku_allow_scroll" to JsonPrimitive(windows.allowScroll),
        "danmaku_allow_top" to JsonPrimitive(windows.allowTop),
        "danmaku_allow_bottom" to JsonPrimitive(windows.allowBottom),
        "danmaku_allow_colorful" to JsonPrimitive(windows.allowColorful),
        "danmaku_allow_special" to JsonPrimitive(windows.allowSpecial),
        "danmaku_merge_duplicates" to JsonPrimitive(windows.mergeDuplicates),
        "danmaku_duplicate_merge_window_ms" to JsonPrimitive(windows.duplicateMergeWindowMs),
        "danmaku_duplicate_merge_count_threshold" to JsonPrimitive(windows.duplicateMergeCountThreshold),
        "danmaku_block_attention_commands" to JsonPrimitive(windows.hideInteractiveCommands),
        "danmaku_block_rules" to JsonPrimitive(rules),
        "danmaku_defaults_version" to JsonPrimitive(5),
    ))
}
