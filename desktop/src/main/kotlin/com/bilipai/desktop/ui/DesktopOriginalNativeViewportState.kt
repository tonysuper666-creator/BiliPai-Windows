package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import java.awt.Color
import java.awt.Component
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** Layout state of the sole existing Canvas. No native/entry/session authority is created. */
internal class DesktopOriginalNativeViewportState(private val isOwned: () -> Boolean) {
    private var pixels: Rect? = null
    var boundsInWindow by mutableStateOf<Rect?>(null); private set
    fun updateWindowBounds(rect: Rect) {
        require(rect.left.isFinite() && rect.top.isFinite() && rect.right.isFinite() && rect.bottom.isFinite())
        if (isOwned()) boundsInWindow = rect
    }
    private var visible = true
    private var container: JPanel? = null
    private var nativeAttached=true
    var attached by mutableStateOf(false); private set
    var heightPixels by mutableIntStateOf(0); private set
    fun update(rect: Rect, showing: Boolean) {
        require(rect.left.isFinite() && rect.top.isFinite() && rect.right.isFinite() && rect.bottom.isFinite())
        if (!isOwned()) return
        if(pixels==rect && visible==showing)return
        pixels = rect; visible = showing; attached = rect.width > 0 && rect.height > 0
        heightPixels = rect.height.roundToInt().coerceAtLeast(0)
        relayout()
    }
    fun reset() { boundsInWindow = null; pixels = null; visible = true; attached = false; heightPixels = 0; relayout() }
    fun setNativeAttached(value:Boolean) {if(nativeAttached==value)return;nativeAttached=value;relayout()}
    private fun relayout() = SwingUtilities.invokeLater {
        if (isOwned()) container?.let { it.doLayout(); it.revalidate(); it.repaint() }
    }
    fun createContainer(surface: Component): JPanel {
        container?.let { existing ->
            if (nativeAttached && surface.parent !== existing) existing.add(surface)
            return existing
        }
        return object : JPanel(null) {
            override fun doLayout() {
                if (!isOwned()) return
                if(!nativeAttached){if(surface.parent===this)remove(surface);return}
                if(surface.parent!==this)add(surface)
                val scale = graphicsConfiguration?.defaultTransform
                val sx = scale?.scaleX ?: 1.0
                val sy = scale?.scaleY ?: 1.0
                val next = logicalNativeViewport(pixels, width, height, sx, sy)
                if (surface.bounds != next) surface.bounds = next
                if (surface.isVisible != visible) surface.isVisible = visible
                surface.validate()
            }
        }.also { it.background = Color.BLACK; if(nativeAttached)it.add(surface); container = it }
    }
}

/** Compose bounds are device pixels; the same real AWT container supplies its current transform. */
internal fun logicalNativeViewport(pixels: Rect?, width: Int, height: Int, sx: Double, sy: Double): Rectangle {
    require(sx.isFinite() && sy.isFinite() && sx > 0 && sy > 0)
    if (pixels == null) return Rectangle(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
    return Rectangle((pixels.left / sx).roundToInt(), (pixels.top / sy).roundToInt(),
        (pixels.width / sx).roundToInt().coerceAtLeast(0), (pixels.height / sy).roundToInt().coerceAtLeast(0))
}
