package com.bilipai.desktop.ui

import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDownloadListInteractionTest {
    @Test fun selectionSurvivesRefreshAndFollowsNewOrderForShift() {
        val state = DesktopDownloadListSelection()
        state.reconcile(listOf("a", "b", "c", "d"))
        state.click("b")
        state.click("d", ctrl = true)
        state.reconcile(listOf("d", "c", "b", "a"))
        assertEquals(setOf("b", "d"), state.selected)
        state.click("a", shift = true)
        assertEquals(setOf("d", "c", "b", "a"), state.selected)
        state.reconcile(listOf("a", "c"))
        assertEquals(setOf("a", "c"), state.selected)
    }
    @Test fun shiftKeepsAnchorAndKeyboardMovesFocusBeforeSpace() {
        val state = DesktopDownloadListSelection()
        state.reconcile(listOf("a", "b", "c", "d"))
        state.click("b")
        state.click("d", shift = true)
        state.click("c", shift = true)
        assertEquals(setOf("b", "c"), state.selected)
        state.move(1)
        assertEquals("d", state.focused)
        state.toggleFocused()
        assertEquals(setOf("b", "c", "d"), state.selected)
        state.move(-1)
        state.toggleFocused()
        assertEquals(setOf("b", "d"), state.selected)
    }
    @Test fun contextMenuTargetsPointerRowWithoutRetargetingExistingSelection() {
        val state = DesktopDownloadListSelection()
        state.reconcile(listOf("a", "b"))
        state.click("a")
        state.openContextMenu("b")
        assertEquals("b", state.contextTarget)
        assertEquals(setOf("a"), state.selected)
        state.reconcile(listOf("a"))
        assertEquals(null, state.contextTarget)
    }
    @Test fun cancellingDeleteNeverInvokesRemovalAndFileSurvives() {
        val file = Files.createTempFile("download-selection", ".fixture")
        try {
            val state = DesktopDownloadListSelection()
            state.reconcile(listOf("a"))
            state.requestDelete(setOf("a"))
            state.cancelDelete()
            state.confirmDelete(true) { _, _ -> Files.delete(file) }
            assertTrue(Files.exists(file))
            state.requestDelete(setOf("a"))
            var deleteFiles: Boolean? = null
            state.confirmDelete(false) { _, delete -> deleteFiles = delete }
            assertEquals(false, deleteFiles)
            assertTrue(Files.exists(file))
            assertTrue(state.pendingDelete.isEmpty())
        } finally { Files.deleteIfExists(file) }
    }
    @Test fun retiredOwnerCannotAdmitSelectionActions() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("download-list-retired-owner")
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val publication = com.bilipai.desktop.player.DesktopLocalPlaybackPublication({ true }, { action -> action(); true })
        val manager = com.bilipai.desktop.download.DesktopDownloadManager(
            okhttp3.OkHttpClient(), root.resolve("queue.json"),
            com.bilipai.desktop.download.DownloadMuxer { _, _, _ -> error("No muxing in ownership test") },
            publication = publication,
        )
        try {
            var owned = true
            var admissions = 0
            val bindings = DesktopOriginalDownloadListBindings(
                com.bilipai.desktop.plugins.DesktopPluginContext(com.bilipai.desktop.plugins.DesktopPluginStore(root.resolve("settings"))),
                manager, scope, { owned },
                { action -> admissions++; action(); true }, { true }, {},
            )
            bindings.pauseDownload("unknown")
            assertEquals(1, admissions)
            owned = false
            bindings.startDownload("unknown")
            bindings.pauseDownload("unknown")
            bindings.removeTask("unknown", true)
            assertEquals(1, admissions)
            assertTrue(manager.tasks.value.isEmpty())
        } finally {
            scope.cancel()
            manager.shutdownForApplicationExit()
        }
    }
}
