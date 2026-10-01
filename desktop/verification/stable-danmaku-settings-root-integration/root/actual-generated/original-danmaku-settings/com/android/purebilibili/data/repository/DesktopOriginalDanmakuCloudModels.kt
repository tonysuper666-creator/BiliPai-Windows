package com.android.purebilibili.data.repository
import com.android.purebilibili.core.store.normalizeDanmakuDisplayArea

data class DanmakuCloudFilterRule(
    val id: Long,
    val type: Int,
    val filter: String
)

data class DanmakuCloudFilterRules(
    val rules: List<DanmakuCloudFilterRule>,
    val toast: String? = null
)

internal data class DanmakuCloudSyncSettings(
    val enabled: Boolean,
    val allowScroll: Boolean,
    val allowTop: Boolean,
    val allowBottom: Boolean,
    val allowColorful: Boolean,
    val allowSpecial: Boolean,
    val opacity: Float,
    val displayAreaRatio: Float,
    val speed: Float,
    val fontScale: Float
)

internal data class DanmakuCloudConfigPayload(
    val dmSwitch: String,
    val blockScroll: String,
    val blockTop: String,
    val blockBottom: String,
    val blockColor: String,
    val blockSpecial: String,
    val opacity: Float,
    val dmArea: Int,
    val speedPlus: Float,
    val fontSize: Float
)

private fun Boolean.toCloudFlag(): String = if (this) "true" else "false"

internal const val DANMAKU_CLOUD_FONT_SIZE_MIN_EXCLUSIVE = 0.5f

internal const val DANMAKU_CLOUD_FONT_SIZE_MIN = 0.51f

internal const val DANMAKU_CLOUD_FONT_SIZE_MAX = 1.6f

internal fun mapDanmakuDisplayAreaRatioToCloudValue(displayAreaRatio: Float): Int {
    if (displayAreaRatio <= 0f) return 0
    return (normalizeDanmakuDisplayArea(displayAreaRatio) * 100f).toInt()
}

internal fun mapDanmakuFontScaleToCloudFontSize(fontScale: Float): Float {
    val clamped = fontScale.coerceIn(0.3f, DANMAKU_CLOUD_FONT_SIZE_MAX)
    return if (clamped <= DANMAKU_CLOUD_FONT_SIZE_MIN_EXCLUSIVE) {
        DANMAKU_CLOUD_FONT_SIZE_MIN
    } else {
        clamped
    }
}

internal fun buildDanmakuCloudConfigPayload(settings: DanmakuCloudSyncSettings): DanmakuCloudConfigPayload {
    return DanmakuCloudConfigPayload(
        dmSwitch = settings.enabled.toCloudFlag(),
        // B站 blockxxx 字段语义：true=不屏蔽，false=屏蔽；与本地 allow 语义一致
        blockScroll = settings.allowScroll.toCloudFlag(),
        blockTop = settings.allowTop.toCloudFlag(),
        blockBottom = settings.allowBottom.toCloudFlag(),
        blockColor = settings.allowColorful.toCloudFlag(),
        blockSpecial = settings.allowSpecial.toCloudFlag(),
        opacity = settings.opacity.coerceIn(0f, 1f),
        dmArea = mapDanmakuDisplayAreaRatioToCloudValue(settings.displayAreaRatio),
        speedPlus = settings.speed.coerceIn(0.4f, 1.6f),
        fontSize = mapDanmakuFontScaleToCloudFontSize(settings.fontScale)
    )
}

internal fun isDanmakuCloudSyncSuccessful(code: Int): Boolean = code == 0 || code == 23004
