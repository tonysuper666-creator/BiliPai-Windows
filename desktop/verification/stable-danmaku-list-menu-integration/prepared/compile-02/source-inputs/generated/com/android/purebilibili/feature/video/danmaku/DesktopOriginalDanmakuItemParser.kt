package com.android.purebilibili.feature.video.danmaku
import com.android.purebilibili.danmaku.engine.*

internal fun resolveDanmakuClickUserHash(rawUserHash: String): String = rawUserHash.trim()

internal fun resolveDanmakuClickIsSelf(userHash: String, currentMid: Long): Boolean {
    if (currentMid <= 0L) return false
    return userHash.toLongOrNull() == currentMid
}

private const val BILIBILI_STANDARD_DANMAKU_FONT_SIZE = 25f

internal fun resolveBilibiliDanmakuFontScale(fontSize: Float): Float {
    if (!fontSize.isFinite() || fontSize <= 0f) return 1f
    return (fontSize / BILIBILI_STANDARD_DANMAKU_FONT_SIZE).coerceIn(0.48f, 2.56f)
}

internal object DesktopOriginalDanmakuItemParser {
    fun createTextDataFromProto(elem: DanmakuProto.DanmakuElem): DanmakuItem? {
        if (elem.content.isEmpty()) return null
        
        // Mode 8/9 代码弹幕目前暂不支持
        if (elem.mode >= 8) return null
        
        val layerType = mapLayerType(elem.mode)
        val colorWithAlpha = elem.color or 0xFF000000.toInt()
        
        // [API 完整利用] 使用 WeightedTextData 携带 weight 和 pool 信息
        return WeightedTextData().apply {
            this.danmakuId = elem.id
            this.userHash = elem.midHash
            this.text = formatDanmakuTextWithCount(
                content = elem.content,
                duplicateCount = elem.count
            )
            this.showAtTime = elem.progress.toLong()
            this.layerType = layerType
            this.textColor = colorWithAlpha
            this.textSizeScale = resolveBilibiliDanmakuFontScale(elem.fontsize.toFloat())
            
            // 填充 Bilibili 特有属性
            this.weight = elem.weight
            this.pool = elem.pool
            this.attr = elem.attr
            this.likeCount = elem.like
            this.isVipGradualColor = elem.colorful == DanmakuProto.DmColorfulTypeVipGradualColor
            this.duplicateCount = elem.count
            this.isSelf = elem.isSelf
        }
    }

    private fun formatDanmakuTextWithCount(
        content: String,
        duplicateCount: Int
    ): String {
        return if (duplicateCount > 1) {
            "$content x$duplicateCount"
        } else {
            content
        }
    }

    fun createTextData(pAttr: String, content: String): DanmakuItem? {
        try {
            val parts = pAttr.split(",")
            if (parts.size < 4) return null
            
            val biliType = parts[1].toIntOrNull() ?: 1
            
            // 过滤 Mode 7/8/9
            if (biliType >= 7) return null
            
            val timeSeconds = parts[0].toFloatOrNull() ?: 0f
            val timeMs = (timeSeconds * 1000).toLong()  // 转换为毫秒
            val fontSize = parts[2].toFloatOrNull() ?: 25f
            val colorInt = parts[3].toLongOrNull() ?: 0xFFFFFF
            val pool = parts.getOrNull(5)?.toIntOrNull() ?: 0
            val userHash = parts.getOrNull(6).orEmpty()
            val danmakuId = parts.getOrNull(7)?.toLongOrNull() ?: 0L
            
            val layerType = mapLayerType(biliType)
            
            return WeightedTextData().apply {
                this.danmakuId = danmakuId
                this.userHash = userHash
                this.pool = pool
                this.text = content
                this.showAtTime = timeMs
                this.layerType = layerType
                this.textColor = (colorInt.toInt() or 0xFF000000.toInt())
                this.textSizeScale = resolveBilibiliDanmakuFontScale(fontSize)
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun mapLayerType(biliType: Int): Int = when (biliType) {
        1, 2, 3 -> DANMAKU_LAYER_SCROLL
        4 -> DANMAKU_LAYER_BOTTOM
        5 -> DANMAKU_LAYER_TOP
        6 -> DANMAKU_LAYER_REVERSE
        else -> DANMAKU_LAYER_SCROLL
    }
}
