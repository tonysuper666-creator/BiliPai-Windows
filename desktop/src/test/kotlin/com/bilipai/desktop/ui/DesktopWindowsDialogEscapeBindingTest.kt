package com.bilipai.desktop.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.FutureTask
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopWindowsDialogEscapeBindingTest {
    private val escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, false)
    private fun edt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else {
            val task = FutureTask { block() }
            SwingUtilities.invokeAndWait(task)
            task.get()
        }
    }
    private fun mapped(root: JRootPane): Action = requireNotNull(root.actionMap.get(
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(escape)))

    @Test fun nativeEditorEscapeUsesLocalAncestorActionWithoutChangingDraftOrSelection() = edt {
        val editor = DesktopInlineEmotePane(Regex("\\[[^]]+]"), { true })
        val root = JRootPane().apply { contentPane.add(JScrollPane(editor)) }
        val draft = TextFieldValue("private draft [emote]", TextRange(3, 8))
        editor.bind(draft, emptyMap(), enabled = true, readOnly = false)
        var closes = 0
        val binding = DesktopWindowsDialogEscapeBinding(root, { true }, { closes++ })
        try {
            // The exact native editor has no focused Escape action. Swing then
            // walks to this root's ancestor map, without a Compose key callback.
            assertNull(editor.getInputMap(JComponent.WHEN_FOCUSED).get(escape))
            assertTrue(SwingUtilities.isDescendingFrom(editor, root))
            val event = KeyEvent(editor, KeyEvent.KEY_PRESSED, 1L, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)
            assertTrue(SwingUtilities.notifyAction(mapped(root), escape, event, editor, 0))
            assertEquals(1, closes)
            assertEquals(draft, editor.rawValue())
            assertNull(root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, true)))
            assertNull(root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, InputEvent.CTRL_DOWN_MASK, false)))
        } finally { binding.close(); editor.retire() }
    }

    @Test fun disabledUnfocusedAndRetiredOwnershipDoNotDismissAnyWindow() = edt {
        val root = JRootPane()
        var enabled = false; var sameWindowFocused = true; var current = true
        var closes = 0
        val binding = DesktopWindowsDialogEscapeBinding(root,
            { enabled && sameWindowFocused && current }, { closes++ })
        val action = mapped(root)
        fun press() = action.actionPerformed(ActionEvent(root, ActionEvent.ACTION_PERFORMED, "Escape"))
        try {
            press(); assertEquals(0, closes)
            enabled = true; sameWindowFocused = false // chooser or another dialog owns focus
            press(); assertEquals(0, closes)
            sameWindowFocused = true; current = false
            press(); assertEquals(0, closes)
            current = true
            press(); assertEquals(1, closes)
        } finally { binding.close() }
        press(); assertEquals(1, closes) // retained old Action cannot dispatch after disposal
    }

    @Test fun staleActionCannotDismissReplacementDialogAndCloseIsIdempotent() = edt {
        val root = JRootPane()
        var old = 0; var replacement = 0
        val first = DesktopWindowsDialogEscapeBinding(root, { true }, { old++ })
        val oldAction = mapped(root)
        oldAction.actionPerformed(ActionEvent(root, 0, "Escape"))
        first.close(); first.close()
        val next = DesktopWindowsDialogEscapeBinding(root, { true }, { replacement++ })
        try {
            oldAction.actionPerformed(ActionEvent(root, 0, "Escape"))
            assertEquals(1, old); assertEquals(0, replacement)
            mapped(root).actionPerformed(ActionEvent(root, 0, "Escape"))
            assertEquals(1, replacement)
        } finally { next.close() }
    }

    @Test fun disposalRestoresOnlyTheStillOwnedEscapeSlot() = edt {
        val root = JRootPane()
        val input = root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val oldKey = Any()
        val oldAction = object : AbstractAction() { override fun actionPerformed(e: ActionEvent) = Unit }
        input.put(escape, oldKey); root.actionMap.put(oldKey, oldAction)
        val first = DesktopWindowsDialogEscapeBinding(root, { true }, {})
        first.close()
        assertSame(oldKey, input.get(escape)); assertSame(oldAction, mapped(root))
        val next = DesktopWindowsDialogEscapeBinding(root, { true }, {})
        val replacementKey = Any()
        input.put(escape, replacementKey)
        next.close()
        assertSame(replacementKey, input.get(escape))
        assertSame(oldAction, root.actionMap.get(oldKey))
    }

    @Test fun inheritedEscapeMappingIsNotCopiedIntoLocalMapOnDispose() = edt {
        val root = JRootPane()
        val input = root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        input.remove(escape)
        val inherited = javax.swing.InputMap().apply { put(escape, "inherited") }
        input.parent = inherited
        val binding = DesktopWindowsDialogEscapeBinding(root, { true }, {})
        binding.close()
        assertFalse(input.keys()?.contains(escape) == true)
        assertEquals("inherited", input.get(escape))
    }
}
