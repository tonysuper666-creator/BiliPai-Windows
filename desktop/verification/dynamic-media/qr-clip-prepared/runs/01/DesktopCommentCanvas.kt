package com.bilipai.desktop.ui
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
internal class DesktopCommentCanvas(bitmap: BufferedImage) {
    private val graphics = bitmap.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }
    private val width = bitmap.width
    private val height = bitmap.height
    private fun paint(value: DesktopCommentPaint) {
        graphics.color = Color(value.color, true)
        graphics.font = value.font
    }
    fun drawColor(value: Int) { graphics.color = Color(value, true); graphics.fillRect(0, 0, width, height) }
    fun drawRoundRect(rect: DesktopCommentRect, radiusX: Float, radiusY: Float, value: DesktopCommentPaint) {
        paint(value); graphics.fill(RoundRectangle2D.Float(rect.left, rect.top,
            rect.right-rect.left, rect.bottom-rect.top, radiusX*2, radiusY*2))
    }
    fun drawText(text: String, x: Float, y: Float, value: DesktopCommentPaint) { paint(value); graphics.drawString(text, x, y) }
    /** Only the printed footer URL is clipped to the existing qrLeft boundary.
     * QR/spec URL, original font, coordinates, geometry and other text stay intact.
     * Graphics state is isolated; no global drawText clipping policy is changed. */
    fun drawFooterTextBeforeQr(text: String, x: Float, y: Float, value: DesktopCommentPaint, qrLeft: Float) {
        paint(value)
        val clipped = graphics.create() as java.awt.Graphics2D
        try {
            clipped.clip(Rectangle2D.Float(x, 0f, (qrLeft - x).coerceAtLeast(0f), height.toFloat()))
            clipped.drawString(text, x, y)
        } finally { clipped.dispose() }
    }
    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, value: DesktopCommentPaint) {
        paint(value); graphics.fill(Rectangle2D.Float(left, top, right-left, bottom-top))
    }
    fun drawBitmap(bitmap: BufferedImage, x: Float, y: Float, @Suppress("UNUSED_PARAMETER") value: DesktopCommentPaint?) {
        graphics.drawImage(bitmap, AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble()), null)
    }
    fun close() = graphics.dispose()
}
