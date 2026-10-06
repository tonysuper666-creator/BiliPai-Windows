package com.bilipai.desktop.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.datatransfer.Clipboard
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.util.concurrent.FutureTask
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopInlineEmoteScaleTest {
    private fun edt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else {
            val task = FutureTask { block() }
            SwingUtilities.invokeAndWait(task)
            task.get()
        }
    }
    private fun editor(owned: () -> Boolean = { true }) =
        DesktopInlineEmotePane(Regex("\\[[^]]+]"), owned) { Clipboard("private emote test") }
    private fun icon(editor: DesktopInlineEmotePane): Icon? =
        StyleConstants.getIcon(editor.styledDocument.getCharacterElement(0).attributes)
    private class Raster(width: Int, height: Int) : BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB) {
        var flushes = 0
        override fun flush() { flushes++; super.flush() }
    }
    private class LeaseIcon(private val side: Int) : Icon, AutoCloseable {
        var closes = 0
        override fun getIconWidth() = side
        override fun getIconHeight() = side
        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) = Unit
        override fun close() { closes++ }
    }

    @Test fun sameUrlGetsNewSizeWithoutReplacingEditorDocumentCaretOrDraft() = edt {
        val editor = editor()
        val scroll = DesktopCommentEmoteScrollPane(editor)
        val document = editor.document
        val caret = editor.caret
        val draft = TextFieldValue("[face] private draft", TextRange(11, 16), TextRange(7, 10))
        val catalog = mapOf("[face]" to "private-emote")
        val requested = mutableListOf<String>()
        var cancellations = 0
        var publications = 0
        editor.requestImage = { requested += it }
        editor.valueChanged = { publications++ }
        val firstRaster = Raster(34, 28)
        val secondRaster = Raster(52, 44)
        try {
            val first = editor.updateImageSize(28, 3) { cancellations++ }
            editor.bind(draft, catalog, true, false)
            val firstIcon = ImageIcon(firstRaster)
            editor.imageReady("private-emote", firstIcon, first)
            assertSame(firstIcon, icon(editor))
            assertSame(first, editor.updateImageSize(28, 3) { cancellations++ })
            assertEquals(0, firstRaster.flushes)
            val second = editor.updateImageSize(44, 4) { cancellations++ }
            assertEquals(1, firstRaster.flushes)
            editor.bind(draft, catalog, true, false)
            assertNull(icon(editor))
            val secondIcon = ImageIcon(secondRaster)
            editor.imageReady("private-emote", secondIcon, second)
            assertEquals(listOf("private-emote", "private-emote"), requested)
            assertEquals(2, cancellations) // Initial size plus resize; same size is a no-op.
            assertSame(secondIcon, icon(editor))
            assertEquals(52, icon(editor)?.iconWidth)
            assertEquals(44, icon(editor)?.iconHeight)
            assertSame(editor, scroll.viewport.view)
            assertSame(document, editor.document)
            assertSame(caret, editor.caret)
            assertEquals(draft, editor.rawValue())
            assertEquals(0, publications)
        } finally { editor.retire() }
        assertEquals(1, firstRaster.flushes)
        assertEquals(1, secondRaster.flushes)
    }

    @Test fun lateOldRevisionIsReleasedEvenAfterReturningToTheSameDimensions() = edt {
        val editor = editor()
        val draft = TextFieldValue("[face] draft", TextRange(8, 11))
        val catalog = mapOf("[face]" to "private-emote")
        val request = Job()
        val late = LeaseIcon(28)
        val middle = LeaseIcon(44)
        val current = LeaseIcon(28)
        try {
            val first = editor.updateImageSize(28, 3) {}
            editor.bind(draft, catalog, true, false)
            val second = editor.updateImageSize(44, 4) { request.cancel() }
            assertTrue(request.isCancelled)
            editor.bind(draft, catalog, true, false)
            editor.imageReady("private-emote", middle, second)
            val third = editor.updateImageSize(28, 3) {}
            editor.bind(draft, catalog, true, false)
            assertTrue(first !== third)
            assertEquals(1, middle.closes)
            editor.imageReady("private-emote", late, first)
            assertEquals(1, late.closes)
            assertNull(icon(editor))
            editor.imageReady("private-emote", current, third)
            assertSame(current, icon(editor))
            assertEquals(draft, editor.rawValue())
        } finally { editor.retire() }
        assertEquals(1, current.closes)
    }

    @Test fun currentRevisionStillRejectsRetiredOwnerAndDisposedEditor() = edt {
        var owned = true
        val editor = editor { owned }
        val revision = editor.updateImageSize(28, 3) {}
        val kept = LeaseIcon(28)
        val wrongOwner = LeaseIcon(28)
        val afterDispose = LeaseIcon(28)
        try {
            editor.bind(TextFieldValue("[face]"), mapOf("[face]" to "private-emote"), true, false)
            editor.imageReady("private-emote", kept, revision)
            owned = false
            editor.imageReady("private-emote", wrongOwner, revision)
            assertEquals(1, wrongOwner.closes)
            assertSame(kept, icon(editor))
        } finally { editor.retire() }
        assertEquals(1, kept.closes)
        editor.imageReady("private-emote", afterDispose, revision)
        assertEquals(1, afterDispose.closes)
    }

    @Test fun consumedWheelLeavesMiddleViewportUnchangedWhileOrdinaryWheelScrolls() = edt {
        val editor = editor()
        val scroll = DesktopCommentEmoteScrollPane(editor)
        try {
            editor.font = Font("Dialog", Font.PLAIN, 14)
            editor.bind(TextFieldValue((1..90).joinToString("\n") { "line $it" }), emptyMap(), true, false)
            scroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
            scroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            scroll.setSize(240, 160)
            scroll.doLayout()
            editor.setSize(scroll.viewport.extentSize.width, 1)
            scroll.viewport.viewSize = Dimension(scroll.viewport.extentSize.width, editor.preferredSize.height)
            scroll.doLayout()
            val bar = scroll.verticalScrollBar
            bar.value = (bar.maximum - bar.visibleAmount) / 2
            assertTrue(bar.value > bar.minimum && bar.value + bar.visibleAmount < bar.maximum)
            val before = scroll.viewport.viewPosition.y
            fun wheel() = MouseWheelEvent(scroll, MouseWheelEvent.MOUSE_WHEEL, 0L, 0,
                40, 40, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1)
            val consumed = wheel().apply { consume() }
            scroll.dispatchEvent(consumed)
            assertTrue(consumed.isConsumed)
            assertEquals(before, scroll.viewport.viewPosition.y)
            val ordinary = wheel()
            assertFalse(ordinary.isConsumed)
            scroll.dispatchEvent(ordinary)
            assertTrue(scroll.viewport.viewPosition.y > before)
        } finally { editor.retire() }
    }
}
