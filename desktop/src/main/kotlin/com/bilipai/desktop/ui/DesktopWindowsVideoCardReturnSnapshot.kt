package com.bilipai.desktop.ui

import androidx.compose.ui.geometry.Rect
import com.android.purebilibili.core.ui.transition.VideoCardSourceLayout
import com.android.purebilibili.feature.video.screen.VideoDetailResidentCoverSource
import com.android.purebilibili.feature.video.screen.VideoDetailReturnSourceCardLayout
import com.android.purebilibili.feature.video.screen.resolveVideoDetailResidentCoverSource
import com.android.purebilibili.feature.video.screen.resolveVideoDetailReturnSourceCardLayout
import com.android.purebilibili.navigation3.VideoCardTransitionSession
import com.bilipai.desktop.player.DesktopNativeVideoCanvasLease

/** Immutable visual inputs captured BEFORE a genuine detail pop restores a parent session.
 *
 * The root and entry references describe the real caller; they grant no navigation or native
 * authority. The Root visual host first records a cover draw, then applies an actual
 * lease-checked empty HWND clip to expose it before committing the pop, and rechecks its
 * own lifetime on each frame. Compose z-index alone cannot draw above a native HWND.
 * This snapshot alone must never mark the clock Returning/Idle or report a displayed frame.
 *
 * No CardPositionManager/global layer is read here. A source GraphicsLayer may be disposed
 * with its entry, so the survivor instead retains the original frozen chrome and EXACT
 * original resident-cover request. Original geometry/rendering policies remain authoritative.
 */
internal class DesktopWindowsVideoCardReturnSnapshot private constructor(
    val rootIdentity: Any,
    val leavingEntryIdentity: Any,
    val transitionSession: VideoCardTransitionSession,
    val viewportInRoot: Rect,
    val sourceCardInRoot: Rect,
    val sourceCoverInRoot: Rect,
    val residentCover: VideoDetailResidentCoverSource,
    val sourceLayout: VideoDetailReturnSourceCardLayout,
    val nativeLease: DesktopNativeVideoCanvasLease,
) {
    companion object {
        /** Capture on the actual Window EDT before the Root return checkpoint/restoration.
         * The borrowed delayed request must independently revalidate the real Root/entry/
         * navigation revision at that checkpoint; these visual inputs confer no authority.
         * All rectangles must already be expressed in the SAME Compose root coordinate space.
         * Returning null leaves the real nonanimated return path available; no geometry,
         * placeholder card, native receipt or playback source is fabricated.
         */
        fun capture(
            rootIdentity: Any,
            leavingEntryIdentity: Any,
            session: VideoCardTransitionSession?,
            viewportInRoot: Rect,
            nativeLease: DesktopNativeVideoCanvasLease,
        ): DesktopWindowsVideoCardReturnSnapshot? {
            if (session == null || !session.hasUsableSourceGeometry || session.bvid.isBlank()) return null
            if (nativeLease.peer.retired || nativeLease.hostWindowHandle == 0L) return null
            val card = session.cardBounds?.takeIf(::usableRect) ?: return null
            val cover = session.coverBounds?.takeIf(::usableRect) ?: return null
            if (!usableRect(viewportInRoot) || !coverInsideCard(card, cover)) return null
            // A whole-card return may not invent title/UP/stat rows after a list refresh.
            val chrome = session.sourceChromeSnapshot ?: return null
            // The original now-playing bar needs its complete trusted bitmap (including
            // buttons). Do not replace it with the ordinary vector-card fallback. Until
            // that separate bitmap handoff exists, keep the actual nonanimated return.
            if (chrome.isNowPlayingBar) return null
            val resident = resolveVideoDetailResidentCoverSource(
                sourceChromeSnapshot = chrome,
                routeCoverUrl = session.coverIdentity,
                bvid = session.bvid,
            ) ?: return null
            val layout = resolveVideoDetailReturnSourceCardLayout(
                viewportWidthPx = viewportInRoot.width,
                sourceBounds = card,
                sourceCoverBounds = cover,
                sourceLayout = session.sourceLayout,
            )
            if (session.sourceLayout != VideoCardSourceLayout.COVER_ONLY && !layout.canRender) return null
            return DesktopWindowsVideoCardReturnSnapshot(rootIdentity, leavingEntryIdentity,
                session, viewportInRoot, card, cover, resident, layout, nativeLease)
        }

        private fun usableRect(rect: Rect): Boolean =
            listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() } &&
                rect.width.isFinite() && rect.height.isFinite() && rect.width > 1f && rect.height > 1f

        private fun coverInsideCard(card: Rect, cover: Rect): Boolean =
            cover.left >= card.left - 1f && cover.top >= card.top - 1f &&
                cover.right <= card.right + 1f && cover.bottom <= card.bottom + 1f
    }
}
