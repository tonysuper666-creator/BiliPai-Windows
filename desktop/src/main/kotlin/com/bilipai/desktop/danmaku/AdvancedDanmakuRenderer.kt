package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.AdvancedDanmakuData
import com.android.purebilibili.danmaku.parser.BasPathPoint
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D

data class AdvancedDanmakuFrame(
    val data: AdvancedDanmakuData,
    val text: String,
    val x: Float,
    val y: Float,
    val alpha: Float,
    val pulseScale: Float,
)

/** Uses the upstream time/alpha/path/easing methods; AWT replaces only the Android drawing layer. */
class AdvancedDanmakuRenderer(comments: List<AdvancedDanmakuData>) {
    private val comments = comments.sortedBy { it.startTimeMs }
    private val longestDuration = comments.maxOfOrNull { it.durationMs } ?: 0L

    fun frame(timeMs: Long, settings: DanmakuSettings): List<AdvancedDanmakuFrame> {
        if (timeMs < 0 || !settings.enabled || !settings.allowSpecial) return emptyList()
        val start = lowerBound((timeMs - longestDuration).coerceAtLeast(0L))
        val result = mutableListOf<AdvancedDanmakuFrame>()
        var index = start
        while (index < comments.size && comments[index].startTimeMs <= timeMs) {
            val item = comments[index++]
            if (!item.isActive(timeMs) || !settings.allowsAdvanced(item)) continue
            val progress = item.easing.transform(item.getTranslationProgress(timeMs))
            val position = if (item.path.isNotEmpty()) item.getPathPointAt(progress) else BasPathPoint(
                item.startX + (item.endX - item.startX) * progress,
                item.startY + (item.endY - item.startY) * progress,
            )
            val elapsed = timeMs - item.startTimeMs
            val accumulating = item.maxCount > 1 && elapsed < item.accumulationDurationMs && item.accumulationDurationMs > 0
            val text = when {
                accumulating -> "${item.content} ×${(1 + (item.maxCount - 1) * elapsed.toFloat() / item.accumulationDurationMs).toInt()}"
                item.maxCount > 1 -> "${item.content} ×${item.maxCount}"
                else -> item.content
            }
            val phase = (timeMs % 300) / 300f
            val scale = if (!accumulating) 1f else if (phase < 0.5f) 1f + 0.6f * phase else 1.6f - 0.6f * phase
            result += AdvancedDanmakuFrame(item, text, position.x, position.y,
                (item.getAlphaAt(timeMs) * settings.opacity).coerceIn(0f, 1f), scale)
        }
        return result
    }

    fun paint(context: Graphics2D, timeMs: Long, width: Int, height: Int, settings: DanmakuSettings) {
        frame(timeMs, settings).forEach { item ->
            val drawing = context.create() as Graphics2D
            try {
                drawing.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, item.alpha)
                val size = (item.data.fontSize * settings.fontScale).toInt().coerceIn(8, 120)
                val font = Font("Microsoft YaHei UI", if (settings.fontWeight >= 5) Font.BOLD else Font.PLAIN, size)
                val shape = font.createGlyphVector(drawing.fontRenderContext, item.text)
                    .getOutline(0f, font.getLineMetrics(item.text, drawing.fontRenderContext).ascent)
                drawing.translate((item.x * width).toDouble(), (item.y * height).toDouble())
                drawing.rotate(Math.toRadians(item.data.rotateZ.toDouble()), shape.bounds2D.centerX, shape.bounds2D.centerY)
                // The upstream overlay applies Z rotation; its parsed rotateY field is retained.
                drawing.scale(item.pulseScale.toDouble(), item.pulseScale.toDouble())
                if (settings.strokeEnabled && !item.data.noStroke && settings.strokeWidth > 0f) {
                    drawing.color = Color.BLACK
                    drawing.stroke = BasicStroke(settings.strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    drawing.draw(shape)
                }
                drawing.color = Color(item.data.color and 0xffffff)
                drawing.fill(shape)
            } finally { drawing.dispose() }
        }
    }

    private fun lowerBound(timeMs: Long): Int {
        var lower = 0
        var upper = comments.size
        while (lower < upper) {
            val middle = (lower + upper) ushr 1
            if (comments[middle].startTimeMs < timeMs) lower = middle + 1 else upper = middle
        }
        return lower
    }
}
