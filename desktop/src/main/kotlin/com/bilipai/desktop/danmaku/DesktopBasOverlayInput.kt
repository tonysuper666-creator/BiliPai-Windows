package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.bas.BasTarget
import java.awt.Canvas
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

internal data class DesktopBasInputGeometry(
    val screenX: Int, val screenY: Int, val width: Int, val height: Int,
    val scaleX: Double, val scaleY: Double,
) {
    fun physical(screenX: Int, screenY: Int): Pair<Float, Float>? {
        if (width <= 0 || height <= 0 || !scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0 || scaleY <= 0) return null
        val x = screenX.toLong() - this.screenX
        val y = screenY.toLong() - this.screenY
        if (x !in 0L until width.toLong() || y !in 0L until height.toLong()) return null
        return (x * scaleX).toFloat() to (y * scaleY).toFloat()
    }
}

/** Publication identity is supplied by the one Overlay, never reconstructed from BV/CID. */
internal class DesktopBasInputFrame(
    val owner: Any, val installation: Any, val geometry: DesktopBasInputGeometry,
    val isCurrent: () -> Boolean, val activate: (BasTarget) -> Boolean,
)

/** Input for the actual MPV Canvas. The transparent overlay keeps mouse-through behavior.
 * Animation may advance between DOWN/UP; source, layout and target identity may not. */
internal class DesktopBasOverlayInput(
    private val renderer: DesktopBasRenderer,
    private val frame: () -> DesktopBasInputFrame?,
) : AutoCloseable {
    private var canvas: Canvas? = null
    private var pressed: DesktopBasInputFrame? = null
    private val listener = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) {
            if (event.button == MouseEvent.BUTTON1 && pressAt(event.xOnScreen, event.yOnScreen)) event.consume()
        }
        override fun mouseDragged(event: MouseEvent) { moveAt(event.xOnScreen, event.yOnScreen) }
        override fun mouseReleased(event: MouseEvent) {
            if (event.button == MouseEvent.BUTTON1 && releaseAt(event.xOnScreen, event.yOnScreen)) event.consume()
        }
        override fun mouseExited(event: MouseEvent) { cancel() }
    }

    fun attach(actual: Canvas?) {
        if (canvas === actual) return
        cancel()
        canvas?.removeMouseListener(listener); canvas?.removeMouseMotionListener(listener)
        canvas = actual
        actual?.addMouseListener(listener); actual?.addMouseMotionListener(listener)
    }

    fun pressAt(screenX: Int, screenY: Int): Boolean {
        cancel()
        val current = frame()?.takeIf { it.isCurrent() } ?: return false
        val point = current.geometry.physical(screenX, screenY) ?: return false
        if (renderer.pressReceipt(point.first, point.second) == null) return false
        pressed = current
        return true
    }

    private fun currentPress(): DesktopBasInputFrame? {
        val down = pressed ?: return null
        val current = frame() ?: return null
        return current.takeIf { down.owner === it.owner && down.installation === it.installation &&
            down.geometry == it.geometry && down.isCurrent() && it.isCurrent() }
    }

    fun moveAt(screenX: Int, screenY: Int) {
        val current = currentPress() ?: run { cancel(); return }
        val point = current.geometry.physical(screenX, screenY) ?: run { cancel(); return }
        renderer.move(point.first, point.second)
    }

    fun releaseAt(screenX: Int, screenY: Int): Boolean {
        val current = currentPress() ?: run { cancel(); return false }
        val point = current.geometry.physical(screenX, screenY) ?: run { cancel(); return false }
        pressed = null
        val hit = renderer.releaseReceipt(point.first, point.second) ?: return false
        // Root's real action port performs final source/account/entry admission after this callback.
        return current.isCurrent() && current.activate(hit.target)
    }

    fun cancel() { pressed = null; renderer.cancelPress() }
    override fun close() { attach(null) }
}
