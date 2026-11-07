package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.player.DesktopNativePresentationTransfer
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PictureInPictureController
import com.bilipai.desktop.player.PlaybackSource
import java.awt.Window
import javax.swing.SwingUtilities
import kotlin.test.*

class DesktopPictureInPictureReturnTest {
    private val detail = BiliPaiNavKey.VideoDetail("BV1fixture", cid = 1L, openId = 7L)
    private var page: BiliPaiNavKey? = detail
    private var pauses = 0
    private val pip = DesktopPictureInPictureReturn({ page }, { pauses++ })

    @Test fun `closing after navigating away from the original page pauses once`() {
        pip.openAccepted()
        page = BiliPaiNavKey.Home
        pip.userReturn()
        assertEquals(1, pauses)
    }

    @Test fun `closing back on the original page keeps the visible player playing`() {
        pip.openAccepted()
        page = BiliPaiNavKey.Home
        page = detail // Back stack restores the same page entry.
        pip.userReturn()
        assertEquals(0, pauses)
    }

    @Test fun `a re-entered copy of the same video or a hidden host is not the original page`() {
        pip.openAccepted()
        page = detail.copy(openId = 8L)
        pip.userReturn()
        assertEquals(1, pauses)
        pip.sessionEnded()
        page = detail
        pip.openAccepted()
        page = null // The host window cannot show the returned surface at all.
        pip.userReturn()
        assertEquals(2, pauses)
    }

    @Test fun `repeated close pauses once and a stale close after teardown does nothing`() {
        pip.openAccepted()
        page = BiliPaiNavKey.Home
        pip.userReturn()
        pip.userReturn() // Second X/Esc while the return handoff is still running.
        assertEquals(1, pauses)
        pip.sessionEnded()
        pip.userReturn()
        assertEquals(1, pauses)
        pip.openAccepted() // A new session opened from Home then closed on Home keeps playing.
        pip.userReturn()
        assertEquals(1, pauses)
    }

    @Test fun `switching page during the open handoff pins the requesting page and the cancelled session ends`() {
        MpvPlayer().use { player ->
            var ended = 0
            val controller = PictureInPictureController(player, onRestore = {}, onOpenAccepted = pip::openAccepted,
                onUserReturn = pip::userReturn, onSessionEnded = { ended++; pip.sessionEnded() })
            controller.openTransfer = { fixtureTransfer() }
            SwingUtilities.invokeAndWait {
                controller.open(headlessOwner())
                page = BiliPaiNavKey.Home // Navigated before the handoff marks the session active.
                assertFalse(controller.active.value)
                controller.restore()
                controller.restore()
            }
            assertEquals(1, pauses)
            awaitOnEdt { ended == 1 } // The unattached fixture source cancels the handoff.
            SwingUtilities.invokeAndWait { controller.restore() } // A queued close after cancellation.
            assertEquals(1, pauses)
            SwingUtilities.invokeAndWait { controller.open(headlessOwner()); controller.restore() } // Opened and closed on Home.
            assertEquals(1, pauses)
            awaitOnEdt { ended == 2 }
        }
    }

    @Test fun `a rejected open request pins no page`() {
        MpvPlayer().use { player ->
            val controller = PictureInPictureController(player, onRestore = {}, onOpenAccepted = pip::openAccepted,
                onUserReturn = pip::userReturn, onSessionEnded = pip::sessionEnded)
            SwingUtilities.invokeAndWait {
                controller.open(headlessOwner()) // No source is loaded yet.
                page = BiliPaiNavKey.Home
                controller.restore()
            }
            pip.userReturn() // Even a direct hook call finds no accepted session.
            assertEquals(0, pauses)
            assertEquals("视频尚未就绪，暂时不能打开浮窗", controller.error.value)
        }
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

    private fun fixtureTransfer() = DesktopNativePresentationTransfer(
        OwnedPlaybackSourceSnapshot(7, PlaybackSource("file:///C:/private-fixture.mp4")), 11, 100) { true }

    /** Tests run headless; open() only keeps the owner for mounting, which this fixture never reaches. */
    private fun headlessOwner(): Window {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, Window::class.java) as Window
    }

    private fun awaitOnEdt(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            var met = false
            SwingUtilities.invokeAndWait { met = condition() }
            if (met) return
            assertTrue(System.nanoTime() < deadline, "floating session did not end")
            Thread.sleep(25)
        }
    }
}
