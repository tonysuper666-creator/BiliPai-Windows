package com.bilipai.desktop.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.bilipai.desktop.plugins.DesktopAnimatedSkinImage
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.datatransfer.Clipboard
import java.awt.image.BufferedImage
import java.util.Base64
import java.util.concurrent.FutureTask
import javax.swing.Icon
import javax.swing.SwingUtilities
import javax.swing.text.IconView
import javax.swing.text.StyleConstants
import javax.swing.text.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopInlineEmoteHighDpiTest {
    private fun edt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else {
            val task = FutureTask { block() }
            SwingUtilities.invokeAndWait(task)
            task.get()
        }
    }
    private fun editor() = DesktopInlineEmotePane(Regex("\\[[^]]+]"), { true }) { Clipboard("private DPI test") }
    private val catalog = mapOf("[face]" to "private-in-memory-face")
    private fun icon(editor: DesktopInlineEmotePane) =
        StyleConstants.getIcon(editor.styledDocument.getCharacterElement(0).attributes)
    private fun nativeIconViews(editor: DesktopInlineEmotePane): List<IconView> {
        editor.setSize(320, 100)
        editor.doLayout()
        editor.modelToView2D(0) // BasicTextUI sizes the real root view before measurement.
        fun descendants(view: View): List<IconView> =
            (if (view is IconView) listOf(view) else emptyList()) +
                (0 until view.viewCount).flatMap { descendants(view.getView(it)) }
        return descendants(editor.ui.getRootView(editor))
    }
    private fun layout(editor: DesktopInlineEmotePane): IconView = nativeIconViews(editor).single()
    private fun paint(editor: DesktopInlineEmotePane, systemScale: Double): BufferedImage {
        editor.caret.isVisible = false
        return BufferedImage(ceil(320 * systemScale).toInt(), ceil(100 * systemScale).toInt(),
            BufferedImage.TYPE_INT_ARGB).also { image ->
            val graphics = image.createGraphics()
            try {
                graphics.scale(systemScale, systemScale)
                editor.paint(graphics) // JTextPane -> actual IconView -> production Icon.
            } finally { graphics.dispose() }
        }
    }
    private fun pixelBounds(image: BufferedImage, matches: (Int) -> Boolean): Rectangle {
        var left = image.width; var right = -1; var top = image.height; var bottom = -1
        for (y in 0 until image.height) for (x in 0 until image.width) if (matches(image.getRGB(x, y))) {
            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
        }
        assertTrue(right >= left && bottom >= top, "real document paint must contain icon pixels")
        return Rectangle(left, top, right - left + 1, bottom - top + 1)
    }
    private class Raster(width: Int, height: Int) : BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB) {
        var flushes = 0
        override fun flush() { flushes++; super.flush() }
    }
    private fun raster(metrics: DesktopInlineEmoteMetrics): Raster =
        Raster(metrics.rasterSize + metrics.rasterPadding * 2, metrics.rasterSize).apply {
            for (y in 0 until metrics.rasterSize) for (x in 0 until metrics.rasterSize)
                setRGB(x + metrics.rasterPadding, y, 0xffff00ff.toInt())
        }
    private class PaintProbe(private val delegate: DesktopInlineRasterEmoteIcon) : Icon, AutoCloseable {
        var paintCalls = 0
        override fun getIconWidth() = delegate.iconWidth
        override fun getIconHeight() = delegate.iconHeight
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            paintCalls++
            delegate.paintIcon(c, g, x, y)
        }
        override fun close() = delegate.close()
    }
    private class LeaseIcon : Icon, AutoCloseable {
        var closes = 0
        override fun getIconWidth() = 34
        override fun getIconHeight() = 28
        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) = Unit
        override fun close() { closes++ }
    }

    @Test fun realDocumentKeepsLogicalSpanAndPaintsStaticRasterAtOneSystemScale() = edt {
        val editor = editor()
        val document = editor.document; val caret = editor.caret
        val draft = TextFieldValue("[face] tail", TextRange(8, 11), TextRange(7, 9))
        var firstTextWidth: Double? = null
        val rasters = mutableListOf<Raster>()
        try {
            for (systemScale in listOf(1f, 1.5f)) {
                val metrics = desktopInlineEmoteMetrics(Density(systemScale * 1.25f, 1.2f),
                    Density(systemScale, 1.2f), 14.sp)
                assertEquals(21f, metrics.logicalFontSize, .001f) // App zoom and font preference remain.
                val revision = editor.updateImageSize(metrics.logicalSize, metrics.logicalPadding,
                    metrics.rasterSize, metrics.rasterPadding) {}
                editor.font = Font("Dialog", Font.PLAIN, 1).deriveFont(metrics.logicalFontSize)
                editor.bind(draft, catalog, true, false)
                assertTrue(nativeIconViews(editor).isEmpty(),
                    "pending replacement must show the original code through live text views, without a retired IconView")
                assertTrue(editor.modelToView2D(6).x > editor.modelToView2D(0).x)
                val raster = raster(metrics).also { rasters += it }
                val probe = PaintProbe(DesktopInlineRasterEmoteIcon(raster,
                    metrics.logicalSize, metrics.logicalPadding, metrics.rasterSize, metrics.rasterPadding))
                editor.imageReady("private-in-memory-face", probe, revision)
                assertSame(probe, icon(editor))
                val view = layout(editor)
                assertEquals(34f, view.getPreferredSpan(View.X_AXIS))
                assertEquals(28f, view.getPreferredSpan(View.Y_AXIS))
                assertEquals(34.0, editor.modelToView2D(6).x - editor.modelToView2D(0).x, .01)
                val textWidth = editor.modelToView2D(11).x - editor.modelToView2D(7).x
                firstTextWidth?.let { assertEquals(it, textWidth, .01) } ?: run { firstTextWidth = textWidth }
                val painted = paint(editor, systemScale.toDouble())
                try {
                    assertTrue(probe.paintCalls > 0,
                        "current emote raster must actually paint after the size revision at system scale $systemScale")
                    val pixels = pixelBounds(painted) { it == 0xffff00ff.toInt() }
                    val expected = (28 * systemScale).roundToInt()
                    assertTrue(abs(pixels.width - expected) <= 1 && abs(pixels.height - expected) <= 1,
                        "icon pixels $pixels must receive the supplied device transform only once")
                } finally { painted.flush() }
                assertSame(document, editor.document); assertSame(caret, editor.caret)
                assertEquals(draft, editor.rawValue())
            }
        } finally { editor.retire() }
        assertEquals(listOf(1, 1), rasters.map { it.flushes })
    }

    @Test fun realDocumentPaintsAnimatedRasterIntoItsLogicalIconView() = edt {
        val editor = editor()
        val encoded = Base64.getDecoder().decode("UklGRoQAAABXRUJQVlA4WAoAAAACAAAADwAADwAAQU5JTQYAAAD/////AABBTk1GKAAAAAAAAAAAAA8AAA8AAGQAAAJWUDhMDwAAAC8PwAMABxD1j/4HIqL/AQBBTk1GKAAAAAAAAAAAAA8AAA8AAGQAAABWUDhMDwAAAC8PwAMABxDR//4HIqL/AQA=")
        val animation = assertNotNull(DesktopAnimatedSkinImage.decodeOrNull(encoded))
        try {
            val metrics = desktopInlineEmoteMetrics(Density(1.5f * 1.25f), Density(1.5f), 14.sp)
            val revision = editor.updateImageSize(metrics.logicalSize, metrics.logicalPadding,
                metrics.rasterSize, metrics.rasterPadding) {}
            editor.bind(TextFieldValue("[face]"), catalog, true, false)
            val animated = DesktopInlineAnimatedEmoteIcon(animation, metrics.rasterSize,
                metrics.logicalPadding, metrics.logicalSize)
            editor.imageReady("private-in-memory-face", animated, revision)
            val view = layout(editor)
            assertEquals(34f, view.getPreferredSpan(View.X_AXIS))
            assertEquals(28f, view.getPreferredSpan(View.Y_AXIS))
            val painted = paint(editor, 1.5)
            try {
                val pixels = pixelBounds(painted) {
                    val red = it ushr 16 and 255; val blue = it and 255
                    (it ushr 24) > 0 && abs(red - blue) > 100
                }
                assertTrue(abs(pixels.width - 42) <= 1 && abs(pixels.height - 42) <= 1,
                    "real animated IconView must paint 28 logical units at 1.5x: $pixels")
            } finally { painted.flush() }
        } finally { editor.retire(); animation.close() }
    }

    @Test fun eitherRasterOrLogicalSizeChangeRetiresOldRevisionWithoutEditingDraft() = edt {
        val editor = editor()
        val document = editor.document; val caret = editor.caret
        val draft = TextFieldValue("[face] draft", TextRange(8, 11), TextRange(7, 10))
        val firstMetrics = desktopInlineEmoteMetrics(Density(1.25f), Density(1f), 14.sp)
        val nextMetrics = desktopInlineEmoteMetrics(Density(1.5f * 1.25f), Density(1.5f), 14.sp)
        val firstRaster = raster(firstMetrics)
        val current = LeaseIcon(); val lateOld = LeaseIcon(); val lateAfterLogicalChange = LeaseIcon()
        var cancellations = 0; var publications = 0
        editor.valueChanged = { publications++ }
        try {
            val first = editor.updateImageSize(firstMetrics.logicalSize, firstMetrics.logicalPadding,
                firstMetrics.rasterSize, firstMetrics.rasterPadding) { cancellations++ }
            editor.bind(draft, catalog, true, false)
            editor.imageReady("private-in-memory-face", DesktopInlineRasterEmoteIcon(firstRaster,
                firstMetrics.logicalSize, firstMetrics.logicalPadding, firstMetrics.rasterSize, firstMetrics.rasterPadding), first)
            // A system DPI transition changes decode resolution while app logical size is unchanged.
            assertEquals(firstMetrics.logicalSize, nextMetrics.logicalSize)
            assertEquals(firstMetrics.logicalPadding, nextMetrics.logicalPadding)
            val second = editor.updateImageSize(nextMetrics.logicalSize, nextMetrics.logicalPadding,
                nextMetrics.rasterSize, nextMetrics.rasterPadding) { cancellations++ }
            assertTrue(first !== second); assertEquals(1, firstRaster.flushes)
            editor.bind(draft, catalog, true, false)
            editor.imageReady("private-in-memory-face", lateOld, first)
            assertEquals(1, lateOld.closes); assertNull(icon(editor))
            editor.imageReady("private-in-memory-face", current, second)
            assertSame(current, icon(editor))
            assertSame(second, editor.updateImageSize(nextMetrics.logicalSize, nextMetrics.logicalPadding,
                nextMetrics.rasterSize, nextMetrics.rasterPadding) { cancellations++ })
            // Independent logical-span change must also invalidate even with the same raster dimensions.
            val third = editor.updateImageSize(35, 4, nextMetrics.rasterSize, nextMetrics.rasterPadding) { cancellations++ }
            assertTrue(second !== third); assertEquals(1, current.closes)
            editor.bind(draft, catalog, true, false)
            editor.imageReady("private-in-memory-face", lateAfterLogicalChange, second)
            assertEquals(1, lateAfterLogicalChange.closes); assertNull(icon(editor))
            assertSame(document, editor.document); assertSame(caret, editor.caret)
            assertEquals(draft, editor.rawValue()); assertEquals(0, publications)
            assertEquals(3, cancellations)
        } finally { editor.retire() }
        assertEquals(1, firstRaster.flushes)
        assertEquals(1, current.closes)
    }
}
