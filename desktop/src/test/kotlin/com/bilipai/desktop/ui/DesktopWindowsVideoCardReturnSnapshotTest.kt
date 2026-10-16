package com.bilipai.desktop.ui

import androidx.compose.ui.geometry.Rect
import com.android.purebilibili.core.ui.transition.VideoCardSourceChromeSnapshot
import com.android.purebilibili.core.ui.transition.VideoCardSourceLayout
import com.android.purebilibili.navigation3.*
import com.bilipai.desktop.player.*
import kotlin.test.*

/** Actual original session, resident-cover and geometry policies; no alternate return model.
 * Native lease/peer objects are descriptors only: no player, window, native API or media runs.
 */
class DesktopWindowsVideoCardReturnSnapshotTest {
    private val root = Any()
    private val entry = Any()
    private val viewport = Rect(80f, 30f, 1360f, 750f)
    private fun lease(peer: DesktopNativeVideoCanvasPeer = DesktopNativeVideoCanvasPeer(41L, 3L),
        host: Long = 43L) = DesktopNativeVideoCanvasLease(Any(),
        OwnedPlaybackSourceSnapshot(7L, PlaybackSource("file:///C:/nonexistent-return-fixture.mp4")), 9L, peer, host)
    private fun session(bvid: String = "BV-child", route: String = "video/BV-parent") =
        VideoCardTransitionSession.create(bvid, BiliPaiVideoSource(route, "$route:$bvid"),
            cardBounds = Rect(140f, 120f, 460f, 380f),
            coverBounds = Rect(140f, 120f, 460f, 300f), sourceCornerDp = 12,
            cardSourceDirection = BiliPaiNavCardSourceDirection.SOURCE_LEFT,
            coverIdentity = "https://example.invalid/route-cover.jpg", cardFullyVisible = true,
            isSingleColumnCard = false, sourceLayout = VideoCardSourceLayout.STACKED,
            sourceChromeSnapshot = VideoCardSourceChromeSnapshot("Frozen title", "Frozen UP",
                coverUrl = "https://example.invalid/list-cover@320w.jpg", coverCacheKey = "list-original-key",
                coverDecodeWidthPx = 320, coverDecodeHeightPx = 180))
    private fun capture(source: VideoCardTransitionSession? = session(),
        rect: Rect = viewport, native: DesktopNativeVideoCanvasLease = lease()) =
        DesktopWindowsVideoCardReturnSnapshot.capture(root, entry, source, rect, native)

    @Test fun `child visual input survives actual original related session restoration`() {
        val parent = session("BV-parent", "home")
        val child = session()
        val state = BiliPaiReturnSessionState().recordTransitionSession(parent)
            .recordTransitionSession(child, preserveCurrentSession = true)
        val native = lease()
        val frozen = assertNotNull(capture(state.transitionSession, native = native))
        val restored = state.restorePreviousVideoSourceAfterRelatedReturn()
        assertSame(parent, restored.transitionSession)
        assertSame(child, frozen.transitionSession)
        assertSame(native, frozen.nativeLease)
        assertSame(root, frozen.rootIdentity)
        assertSame(entry, frozen.leavingEntryIdentity)
        assertEquals(child.cardBounds, frozen.sourceCardInRoot)
        assertEquals(child.coverBounds, frozen.sourceCoverInRoot)
    }

    @Test fun `resident cover keeps the exact clicked request after actual session replacement`() {
        val state = BiliPaiReturnSessionState().recordTransitionSession(session())
        val frozen = assertNotNull(capture(state.transitionSession))
        val other = session("BV-other", "home").copy(sourceChromeSnapshot = VideoCardSourceChromeSnapshot(
            "Other title", "Other UP", coverUrl = "https://example.invalid/new.jpg", coverCacheKey = "other"))
        val replaced = state.recordTransitionSession(other, preserveCurrentSession = false)
        assertSame(other, replaced.transitionSession)
        assertEquals("https://example.invalid/list-cover@320w.jpg", frozen.residentCover.url)
        assertEquals("list-original-key", frozen.residentCover.cacheKey)
        assertEquals(320, frozen.residentCover.decodeWidthPx)
        assertEquals(180, frozen.residentCover.decodeHeightPx)
        assertEquals("Frozen title", frozen.transitionSession.sourceChromeSnapshot?.title)
    }

    @Test fun `real original stacked geometry uses the actual measured viewport width`() {
        val frozen = assertNotNull(capture())
        assertTrue(frozen.sourceLayout.canRender)
        assertEquals(.25f, frozen.sourceLayout.sourceScale)
        assertEquals(180f, frozen.sourceLayout.coverHeightPx)
        assertEquals(80f, frozen.sourceLayout.infoHeightPx)
        assertEquals(720f, frozen.sourceLayout.infoAnchorYInViewportPx)
    }

    @Test fun `absent or unrecorded source never fabricates a return card`() {
        assertNull(capture(null))
        assertNull(capture(session().copy(cardFullyVisible = false)))
        assertNull(capture(session().copy(cardBounds = null)))
        assertNull(capture(session().copy(sourceChromeSnapshot = null)))
    }

    @Test fun `invalid or nonfinite actual rectangles cannot enter the renderer`() {
        for (bad in listOf(Rect.Zero, Rect(0f, 0f, 1f, 10f),
            Rect(Float.NaN, 0f, 100f, 100f), Rect(0f, 0f, Float.POSITIVE_INFINITY, 100f))) {
            assertNull(capture(rect = bad))
            assertNull(capture(session().copy(cardBounds = bad)))
            assertNull(capture(session().copy(coverBounds = bad)))
        }
        assertNull(capture(session().copy(coverBounds = Rect(0f, 0f, 900f, 900f))))
    }

    @Test fun `missing resident image keeps the nonanimated path instead of a placeholder`() {
        val source = session().copy(coverIdentity = null,
            sourceChromeSnapshot = VideoCardSourceChromeSnapshot("Title", "UP"))
        assertNull(capture(source))
    }

    @Test fun `retired or unattached native peer is rejected without invoking native APIs`() {
        val peer = DesktopNativeVideoCanvasPeer(41L, 3L)
        peer.retire()
        assertNull(capture(native = lease(peer)))
        assertNull(capture(native = lease(host = 0L)))
    }

    @Test fun `original cover-only cards do not invent an information band`() {
        val source = session().copy(cardBounds = session().coverBounds,
            sourceLayout = VideoCardSourceLayout.COVER_ONLY)
        val frozen = assertNotNull(capture(source))
        assertEquals(frozen.sourceCardInRoot, frozen.sourceCoverInRoot)
        assertFalse(frozen.sourceLayout.canRender)
        assertEquals(VideoCardSourceLayout.COVER_ONLY, frozen.sourceLayout.layout)
    }

    @Test fun `a whole audio bar without a trusted complete bitmap uses the actual fallback`() {
        val source = session()
        assertNull(capture(source.copy(sourceChromeSnapshot =
            source.sourceChromeSnapshot!!.copy(isNowPlayingBar = true))))
    }

    @Test fun `invalid original information layout cannot become an incomplete whole card`() {
        val source = session()
        assertNull(capture(source.copy(coverBounds = source.cardBounds)))
    }
}
