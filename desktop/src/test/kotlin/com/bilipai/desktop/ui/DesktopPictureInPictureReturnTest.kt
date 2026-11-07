package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PictureInPictureController
import javax.swing.SwingUtilities
import kotlin.test.*

class DesktopPictureInPictureReturnTest {
    private val detail = BiliPaiNavKey.VideoDetail("BV1fixture", cid = 1L, openId = 7L)
    private var pauses = 0
    private val pip = DesktopPictureInPictureReturn()
    private fun close(page: BiliPaiNavKey?) = pip.userReturn(page) { pauses++ }

    @Test fun `closing after navigating away from the original page pauses once`() {
        pip.observe(true, detail)
        pip.observe(true, BiliPaiNavKey.Home)
        close(BiliPaiNavKey.Home)
        assertEquals(1, pauses)
    }

    @Test fun `closing back on the original page keeps the visible player playing`() {
        pip.observe(true, detail)
        pip.observe(true, BiliPaiNavKey.Home)
        pip.observe(true, detail) // Back stack restores the same page entry.
        close(detail)
        assertEquals(0, pauses)
    }

    @Test fun `a re-entered copy of the same video or a hidden host is not the original page`() {
        pip.observe(true, detail)
        close(detail.copy(openId = 8L))
        assertEquals(1, pauses)
        pip.observe(false, null)
        pip.observe(true, detail)
        close(null) // The host window cannot show the returned surface at all.
        assertEquals(2, pauses)
    }

    @Test fun `repeated close pauses once and a stale close after teardown does nothing`() {
        pip.observe(true, detail)
        close(BiliPaiNavKey.Home)
        close(BiliPaiNavKey.Home) // Second X/Esc while the return handoff is still running.
        assertEquals(1, pauses)
        pip.observe(false, BiliPaiNavKey.Home)
        close(BiliPaiNavKey.Home)
        assertEquals(1, pauses)
        pip.observe(true, BiliPaiNavKey.Home) // A new session opened from Home then closed on Home keeps playing.
        close(BiliPaiNavKey.Home)
        assertEquals(1, pauses)
    }

    @Test fun `ended or disposed floating sessions never pause through the user return hook`() {
        MpvPlayer().use { player ->
            var hooks = 0
            var restores = 0
            val controller = PictureInPictureController(player, onRestore = { restores++ }, onUserReturn = { hooks++ })
            // The floating window already tore down (EOF close or disposal): a queued X/Esc finds no session.
            SwingUtilities.invokeAndWait { controller.restore(); controller.restore(); controller.close() }
            player.close() // Destruction racing a queued X/Esc.
            SwingUtilities.invokeAndWait { controller.restore() }
            assertEquals(0, hooks)
            assertEquals(0, restores)
            assertFalse(controller.active.value)
        }
    }
}
