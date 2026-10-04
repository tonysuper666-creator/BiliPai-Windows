package com.bilipai.desktop.ui

import com.android.purebilibili.danmaku.parser.bas.BasTarget
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import java.net.URI
import javax.swing.SwingUtilities
import kotlin.test.*

class DesktopWindowsBasActionsTest {
    private class Harness {
        val expected = DesktopOriginalVideoAcceptedPublication(PlaybackRequest.create("BVfixture", aid = 1, cid = 2),
            OwnedPlaybackSourceSnapshot(3, PlaybackSource("https://example.invalid/video")))
        var source: DesktopOriginalVideoAcceptedPublication? = expected
        var foreground = true
        var account = true
        var inAdmission = false
        var beforeAdmission: () -> Unit = {}
        var afterPause: () -> Unit = {}
        val seeks = mutableListOf<Long>()
        val urls = mutableListOf<URI>()
        var pauses = 0
        val actions = DesktopWindowsBasActions(expected, { source }, { foreground && account },
            admit = { action ->
                beforeAdmission()
                if (account && source === expected) {
                    inAdmission = true
                    try { action(); true } finally { inAdmission = false }
                } else false
            }, originalSeek = { assertTrue(inAdmission); seeks += it },
            originalPause = { assertTrue(inAdmission); pauses++; afterPause() },
            openExternal = { assertFalse(inAdmission); urls += it; true })
        fun replacement() = DesktopOriginalVideoAcceptedPublication(expected.request, expected.nativeSource)
    }
    private fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    @Test fun seekUsesCapturedOriginalCallbackAndExactMilliseconds() = onUi {
        val h = Harness()
        assertTrue(h.actions.dispatch(BasTarget.Seek(12_345)))
        assertEquals(listOf(12_345L), h.seeks)
        assertEquals(0, h.pauses); assertTrue(h.urls.isEmpty())
    }

    @Test fun sameValueSuccessorCannotConsumeOldButton() = onUi {
        val h = Harness(); h.source = h.replacement()
        assertFalse(h.actions.dispatch(BasTarget.Seek(1)))
        assertTrue(h.seeks.isEmpty())
    }

    @Test fun accountRouteOrSourceRetiringBeforeAdmissionRejectsAction() = onUi {
        listOf<(Harness) -> Unit>({ it.account = false }, { it.foreground = false }, { it.source = it.replacement() })
            .forEach { retire ->
                val h = Harness(); h.beforeAdmission = { retire(h) }
                assertFalse(h.actions.dispatch(BasTarget.Video(1, null, 3, 12_345)))
                assertEquals(0, h.pauses); assertTrue(h.urls.isEmpty())
            }
    }

    @Test fun linkPausesSameOwnerAndOpensOutsideAdmissionWithOriginalPartAndTime() = onUi {
        val h = Harness()
        assertTrue(h.actions.dispatch(BasTarget.Video(1, "BV1xx411c7mD", 3, 12_345)))
        assertEquals(1, h.pauses)
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD?p=3&t=12.345", h.urls.single().toASCIIString())
        assertTrue(h.seeks.isEmpty())
    }

    @Test fun sourceRetiringAfterPauseCannotOpenExternalLink() = onUi {
        val h = Harness(); h.afterPause = { h.source = h.replacement() }
        assertFalse(h.actions.dispatch(BasTarget.Bangumi(1, 2, 0)))
        assertEquals(1, h.pauses); assertTrue(h.urls.isEmpty())
    }

    @Test fun externalTargetsKeepOriginalFallbackAndEpisodePreference() {
        assertEquals("https://www.bilibili.com/video/av42?p=2&t=1.2",
            desktopBasExternalTarget(BasTarget.Video(42, null, 2, 1_200)).toString())
        assertEquals("https://www.bilibili.com/bangumi/play/ep42?t=1",
            desktopBasExternalTarget(BasTarget.Bangumi(7, 42, 1_000)).toString())
        assertEquals("https://www.bilibili.com/bangumi/play/ss7?t=0.001",
            desktopBasExternalTarget(BasTarget.Bangumi(7, null, 1)).toString())
    }

    @Test fun authoredTargetIsAnEncodedSegmentOnFixedHttpsOrigin() {
        val uri = assertNotNull(desktopBasExternalTarget(BasTarget.Video(null, "x/../?p=9#@evil.invalid", 2, 0)))
        assertEquals("www.bilibili.com", uri.host); assertEquals("https", uri.scheme)
        assertEquals("/video/x%2F..%2F%3Fp%3D9%23%40evil.invalid", uri.rawPath)
        assertEquals("p=2&t=0", uri.rawQuery); assertNull(uri.fragment); assertNull(uri.userInfo)
    }

    @Test fun invalidTargetNeverPausesOrInvokesBrowser() = onUi {
        val h = Harness()
        listOf(BasTarget.Seek(-1), BasTarget.Video(null, null, 1, 0), BasTarget.Video(1, null, 0, 0),
            BasTarget.Video(1, "\n", 1, 0), BasTarget.Bangumi(null, null, 0), BasTarget.Bangumi(1, null, -1))
            .forEach { assertFalse(h.actions.dispatch(it)) }
        assertEquals(0, h.pauses); assertTrue(h.urls.isEmpty()); assertTrue(h.seeks.isEmpty())
    }
}
