package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.ui.components.ReplyCommentImageSpec
import com.android.purebilibili.feature.video.ui.components.renderDesktopReplyCommentImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.coroutines.coroutineContext

/** Thin Android Paint/Canvas operations used by the selected original comment
 * screenshot body. Geometry, line limit, colors, QR data and layout stay there;
 * Windows font rasterization is a platform difference. */
internal class DesktopCommentPaint(@Suppress("UNUSED_PARAMETER") flags: Int) {
    companion object { const val ANTI_ALIAS_FLAG = 1 }
    var color: Int = Color.BLACK.rgb
    var textSize: Float = 16f
    var typeface: Int = Font.PLAIN
    val font: Font get() = Font("Dialog", typeface, 1).deriveFont(textSize)
    fun breakText(text: String, @Suppress("UNUSED_PARAMETER") measureForwards: Boolean,
        maxWidth: Float, @Suppress("UNUSED_PARAMETER") measuredWidth: FloatArray?): Int {
        val frc = FontRenderContext(AffineTransform(), true, true)
        var count = 0
        while (count < text.length) {
            val next = count + Character.charCount(Character.codePointAt(text, count))
            if (font.getStringBounds(text, 0, next, frc).width > maxWidth) break
            count = next
        }
        return count
    }
}
internal object DesktopCommentColors {
    val WHITE = Color.WHITE.rgb
    val BLACK = Color.BLACK.rgb
    fun rgb(red: Int, green: Int, blue: Int) = Color(red, green, blue).rgb
}
internal data class DesktopCommentRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
internal fun createDesktopCommentBitmap(width: Int, height: Int, type: Int) = BufferedImage(width, height, type)
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
    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, value: DesktopCommentPaint) {
        paint(value); graphics.fill(Rectangle2D.Float(left, top, right-left, bottom-top))
    }
    fun drawBitmap(bitmap: BufferedImage, x: Float, y: Float, @Suppress("UNUSED_PARAMETER") value: DesktopCommentPaint?) {
        graphics.drawImage(bitmap, AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble()), null)
    }
    fun close() = graphics.dispose()
}

/** Only an explicitly chosen target is accepted. Root supplies its captured
 * session/page commit gate; isolated render callers can use the ownership check. */
internal suspend fun writeDesktopReplyCommentImage(
    spec: ReplyCommentImageSpec, target: Path, stillOwned: () -> Boolean,
    replaceExisting: Boolean = true,
    withOwnedCommit: ((() -> Unit) -> Boolean)? = null,
): Boolean = withContext(Dispatchers.IO) {
    fun owned() { coroutineContext.ensureActive(); if (!stillOwned()) throw CancellationException("Reply export owner retired") }
    owned()
    require(target.fileName.toString().endsWith(".png", ignoreCase = true))
    val parent = target.toAbsolutePath().normalize().parent
    require(Files.isDirectory(parent))
    val temporary = Files.createTempFile(parent, ".bilipai-comment-", ".png")
    try {
        val bitmap = renderDesktopReplyCommentImage(spec)
        owned()
        check(ImageIO.write(bitmap, "png", temporary.toFile()))
        owned()
        val commit = {
            owned()
            if (!replaceExisting) Files.move(temporary, target)
            else try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                owned(); Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            Unit
        }
        if(withOwnedCommit==null)commit()
        else if(!withOwnedCommit(commit))throw CancellationException("Reply export commit owner retired")
        true
    } finally { Files.deleteIfExists(temporary) }
}
