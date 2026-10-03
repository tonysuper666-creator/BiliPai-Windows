package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.AdvancedDanmakuData
import com.android.purebilibili.feature.video.danmaku.DanmakuTypeFilterSettings
import com.android.purebilibili.feature.video.danmaku.shouldBlockDanmakuByRules
import com.android.purebilibili.feature.video.danmaku.shouldDisplayAdvancedDanmaku
import com.android.purebilibili.feature.video.danmaku.shouldDisplayStandardDanmaku
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuRenderLayerType
import com.android.purebilibili.feature.video.danmaku.mapLayerTypeToDanmakuType
import kotlinx.serialization.Serializable

/** Renderer settings use the same ranges and slower-is-larger speed factor as upstream. */
@Serializable
data class DanmakuSettings(
    val enabled: Boolean = true,
    val opacity: Float = 0.85f,
    val fontScale: Float = 1f,
    val fontWeight: Int = 5,
    val speedFactor: Float = 1f,
    val displayAreaRatio: Float = 0.5f,
    val scrollDurationSeconds: Float = 7f,
    val staticDurationSeconds: Float = 4f,
    val scrollFixedVelocity: Boolean = false,
    val staticDanmakuToScroll: Boolean = false,
    val massiveMode: Boolean = false,
    val weightFilterLevel: Int = 0,
    @kotlinx.serialization.Transient
    val smartOcclusionEnabled: Boolean = false,
    val lineHeight: Float = 1.6f,
    val strokeEnabled: Boolean = true,
    val strokeWidth: Float = 1.5f,
    val allowScroll: Boolean = true,
    val allowTop: Boolean = true,
    val allowBottom: Boolean = true,
    val allowColorful: Boolean = true,
    val blockedKeywords: List<String> = emptyList(),
    val mergeDuplicates: Boolean = true,
    val duplicateMergeWindowMs: Int = 500,
    val duplicateMergeCountThreshold: Int = 2,
    val allowSpecial: Boolean = true,
    /** Same false default as upstream's interactive command preference. */
    val hideInteractiveCommands: Boolean = false,
    /** Upstream supports keywords, regex:/re:/slash rules, and uid:/user:/hash: rules. */
    val blockedRules: List<String> = emptyList(),
) {
    @kotlinx.serialization.Transient
    private val typeFilters = DanmakuTypeFilterSettings(allowScroll, allowTop, allowBottom, allowColorful, allowSpecial)
    @kotlinx.serialization.Transient
    private val rules = (blockedKeywords + blockedRules).distinct()
    fun normalized(): DanmakuSettings = copy(
        opacity = finite(opacity, 0.85f, 0.1f, 1f),
        fontScale = finite(fontScale, 1f, 0.3f, 2f),
        fontWeight = fontWeight.coerceIn(1, 9),
        weightFilterLevel = weightFilterLevel.coerceIn(0, 10),
        speedFactor = finite(speedFactor, 1f, 0.5f, 3f),
        displayAreaRatio = com.android.purebilibili.core.store.normalizeDanmakuDisplayArea(finite(displayAreaRatio, 0.5f, 0.25f, 1f)),
        scrollDurationSeconds = finite(scrollDurationSeconds, 7f, 1f, 50f),
        staticDurationSeconds = finite(staticDurationSeconds, 4f, 1f, 50f),
        lineHeight = finite(lineHeight, 1.6f, 1f, 3f),
        strokeWidth = finite(strokeWidth, 1.5f, 0f, 5f),
        blockedKeywords = blockedKeywords.map { it.trim().take(200) }.filter(String::isNotEmpty).distinct().take(300),
        duplicateMergeWindowMs = duplicateMergeWindowMs.coerceIn(100, 3000),
        duplicateMergeCountThreshold = duplicateMergeCountThreshold.coerceIn(2, 10),
        blockedRules = com.android.purebilibili.feature.video.danmaku.parseDanmakuBlockRules(blockedRules.joinToString("\n")),
    )

    fun allows(comment: DanmakuComment): Boolean {
        return enabled && comment.mode in 1..6 &&
            shouldDisplayStandardDanmaku(mapLayerTypeToDanmakuType(resolveDanmakuRenderLayerType(comment.mode, staticDanmakuToScroll)), comment.color, typeFilters, comment.isVipGradualColor) &&
            !(weightFilterLevel > 0 && comment.originalElement?.isSelf != true && comment.weight < weightFilterLevel) &&
            !shouldBlockDanmakuByRules(comment.text, rules, comment.userHash)
    }

    internal fun allowsLive(comment:DanmakuComment):Boolean = enabled &&
        com.android.purebilibili.feature.video.danmaku.desktopOriginalLiveDanmakuAllows(
            com.android.purebilibili.feature.live.LiveDanmakuItem(text=comment.text,mode=comment.mode,color=comment.color,uid=comment.userHash.toLongOrNull() ?: 0L),this)

    fun allowsAdvanced(comment: AdvancedDanmakuData): Boolean = enabled &&
        shouldDisplayAdvancedDanmaku(comment.color, typeFilters) && !shouldBlockDanmakuByRules(comment.content, rules)

    private fun finite(value: Float, fallback: Float, min: Float, max: Float) =
        if (value.isFinite()) value.coerceIn(min, max) else fallback
}
