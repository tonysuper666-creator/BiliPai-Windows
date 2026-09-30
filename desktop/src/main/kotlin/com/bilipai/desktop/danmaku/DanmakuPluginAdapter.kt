package com.bilipai.desktop.danmaku

import com.android.purebilibili.core.plugin.DanmakuItem
import com.android.purebilibili.core.plugin.DanmakuStyle
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CancellationException
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D

typealias DanmakuPluginProcessor = (DanmakuItem) -> Pair<DanmakuItem, DanmakuStyle?>?

internal data class StyledDesktopDanmaku(val comment: DanmakuComment, val style: DanmakuStyle?)

/** Maps only platform representation; original PluginManager owns filter order and style merging. */
internal fun applyDesktopDanmakuPlugin(comment: DanmakuComment, processor: DanmakuPluginProcessor?): StyledDesktopDanmaku? {
    if (processor == null) return StyledDesktopDanmaku(comment, null)
    val original = DanmakuItem(comment.serverId.takeIf { it != 0L } ?: comment.id.toLong(), comment.text,
        (comment.timeSeconds * 1_000).toLong(), comment.mode, comment.color and 0xffffff, comment.userHash)
    val transformed = try { processor(original) } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        original to null
    } ?: return null
    val item = transformed.first
    val text = item.content.replace("\u0000", "").take(2_000)
    if (text.isBlank()) return null
    val color = transformed.second?.textColor?.let { runCatching { it.toArgb() }.getOrNull() } ?: item.color
    val style = transformed.second?.let { it.copy(scale = it.scale.takeIf(Float::isFinite)?.coerceIn(0.3f, 4f) ?: 1f) }
    return StyledDesktopDanmaku(comment.copy(text = text, timeSeconds = item.timeMs.coerceAtLeast(0) / 1_000.0,
        mode = item.type.takeIf { it in 1..6 } ?: 1, color = color and 0xffffff), style)
}

internal fun pluginAwtColor(value: androidx.compose.ui.graphics.Color?): Color? =
    value?.let { runCatching { Color(it.toArgb(), true) }.getOrNull() }

/** AWT paint adapter for values produced by the original EyeProtectionPolicy. */
internal data class DesktopEyeTint(val dimAlpha: Float = 0f, val warmAlpha: Float = 0f, val warmArgb: Int = 0xffffc07a.toInt()) {
    val visible: Boolean get() = dimAlpha > 0 || warmAlpha > 0
    fun paint(context: Graphics2D, width: Int, height: Int) {
        if (dimAlpha > 0) {
            context.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, dimAlpha)
            context.color = Color.BLACK; context.fillRect(0, 0, width, height)
        }
        if (warmAlpha > 0) {
            context.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, warmAlpha)
            context.color = Color(warmArgb, true); context.fillRect(0, 0, width, height)
        }
        context.composite = AlphaComposite.SrcOver
    }
}
