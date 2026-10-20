package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.RelatedVideo
import com.android.purebilibili.data.model.response.Stat
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.feature.video.ui.pager.PORTRAIT_CONTROLS_AUTO_HIDE_DELAY_MS
import com.android.purebilibili.feature.video.ui.pager.PortraitVideoInteractionOverride
import com.android.purebilibili.feature.video.ui.pager.PortraitVideoInteractionUiState
import com.android.purebilibili.feature.video.ui.pager.resolvePortraitMediaId
import com.android.purebilibili.feature.video.ui.pager.resolvePortraitPlayUrlPreloadTargets
import com.android.purebilibili.feature.video.ui.pager.resolvePortraitPrefetchWindowTargets
import com.android.purebilibili.feature.video.ui.pager.resolvePortraitTripleActionOverride
import com.android.purebilibili.feature.video.ui.pager.resolvePortraitVideoInteractionUiState
import com.android.purebilibili.feature.video.ui.pager.shouldAutoHidePortraitControls
import com.android.purebilibili.feature.video.ui.pager.shouldShowPortraitCover
import com.android.purebilibili.feature.video.ui.pager.shouldSkipPortraitReloadForCurrentMedia
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure checks of the v0.4.6 portrait pager re-pin (roadmap slice plr-portrait-story-pager-repin). No window, no GPU,
 * no player and no network: only the policies and state helpers the carried and hunked files expose.
 *
 * Provenance: the first three cases (controlsHideAfterFourIdleSecondsOfPlayback, pausedInactiveAndAlreadyHidden...,
 * scrubbingHoldingAndOpenMenus...) restate the assertions of the upstream unit test
 * app/src/test/java/com/android/purebilibili/feature/video/ui/pager/PortraitControlsVisibilityPolicyTest.kt of the
 * v0.4.6 commit d6bf8b1655ae910999860bf13e65c8fcea5b345b (jay3-yy/BiliPai, GPL-3.0, the licence of this repository). That
 * file is not carried: it needs a test source root for the fullscreen pager producer, which has none (manifest notCarried).
 * Follow-up: carry it byte for byte through a producer test root and drop these three cases.
 */
class DesktopPortraitPagerRepinTest {
    @Test
    fun controlsHideAfterFourIdleSecondsOfPlayback() {
        assertEquals(4_000L, PORTRAIT_CONTROLS_AUTO_HIDE_DELAY_MS)
        assertTrue(shouldAutoHidePortraitControls(true, true, true, false, false))
    }

    @Test
    fun pausedInactiveAndAlreadyHiddenPagesNeverStartAnIdleCountdown() {
        assertFalse(shouldAutoHidePortraitControls(true, false, true, false, false))
        assertFalse(shouldAutoHidePortraitControls(false, true, true, false, false))
        assertFalse(shouldAutoHidePortraitControls(true, true, false, false, false))
    }

    @Test
    fun scrubbingHoldingAndOpenMenusSuspendAutoHideUntilTheInteractionEnds() {
        assertFalse(shouldAutoHidePortraitControls(true, true, true, true, false))
        assertFalse(shouldAutoHidePortraitControls(true, true, true, false, true))
        assertTrue(shouldAutoHidePortraitControls(true, true, true, false, false))
    }

    @Test
    fun anotherPartOfTheSameVideoReloadsEvenWhenTheMediaIdIsThePlainBvid() {
        // Multi-part videos share one bvid, so a legacy plain-bvid media id (no cid in it) is decided by the legacy
        // branch for a target that has a cid: it may only be reused when the playing cid is that same part.
        assertFalse(shouldSkipPortraitReloadForCurrentMedia("BV1", "BV1", "BV1", targetCid = 2L, currentPlayingCid = 1L))
        assertFalse(shouldSkipPortraitReloadForCurrentMedia("BV1", "BV1", "BV1", targetCid = 3L, currentPlayingCid = 5L))
        // (2, 2) is the case that tells v0.4.6 from v0.2.5: the old rule only reused a plain bvid for a cid-less target.
        assertTrue(shouldSkipPortraitReloadForCurrentMedia("BV1", "BV1", "BV1", targetCid = 2L, currentPlayingCid = 2L))
        // A cid-less target expects the plain bvid as its media id, so it matches on the expected-id path before the
        // legacy branch runs (here the playing cid does not matter).
        assertTrue(shouldSkipPortraitReloadForCurrentMedia("BV1", "BV1", "BV1", targetCid = 0L, currentPlayingCid = 0L))
        assertTrue(shouldSkipPortraitReloadForCurrentMedia("BV1", "BV1", "BV1", targetCid = 0L, currentPlayingCid = 5L))
    }

    @Test
    fun aCidAwareMediaIdMatchesOnlyItsOwnPart() {
        assertEquals("BV1#2", resolvePortraitMediaId("BV1", 2L))
        assertEquals("BV1", resolvePortraitMediaId(" BV1 ", 0L))
        assertTrue(shouldSkipPortraitReloadForCurrentMedia("BV1", "BV1", "BV1#2", targetCid = 2L, currentPlayingCid = 1L))
        assertFalse(shouldSkipPortraitReloadForCurrentMedia("BV1", "BV1", "BV1#3", targetCid = 2L, currentPlayingCid = 3L))
        assertFalse(shouldSkipPortraitReloadForCurrentMedia("BV1", "BV2", "BV1#2", targetCid = 2L, currentPlayingCid = 2L))
        assertFalse(shouldSkipPortraitReloadForCurrentMedia("BV1", "BV1", " ", targetCid = 2L, currentPlayingCid = 2L))
    }

    @Test
    fun theCurrentPageKeepsTheWindowsCoverRule() {
        // The Windows pager still paints the video through the native window: v0.4.6 shows the static cover until the
        // first frame, this port keeps the v0.2.5 rule (one counted hunk in v046_portrait_pager.COVER_HUNK).
        assertFalse(shouldShowPortraitCover(isLoading = true, isCurrentPage = true,
            isPlayerReadyForThisVideo = false, hasRenderedFirstFrame = false))
        assertFalse(shouldShowPortraitCover(isLoading = false, isCurrentPage = true,
            isPlayerReadyForThisVideo = true, hasRenderedFirstFrame = true))
        assertTrue(shouldShowPortraitCover(isLoading = false, isCurrentPage = false,
            isPlayerReadyForThisVideo = true, hasRenderedFirstFrame = true))
    }

    @Test
    fun preloadTargetsKeepTheDifferentPartsOfOneVideoApart() {
        val targets = resolvePortraitPlayUrlPreloadTargets(
            committedPage = 0,
            pageItems = listOf(
                RelatedVideo(bvid = "BV1", cid = 1L),
                RelatedVideo(bvid = "BV2", cid = 1L),
                RelatedVideo(bvid = "BV2", cid = 2L),
                RelatedVideo(bvid = "BV2", cid = 2L),
            ),
            preloadCount = 3,
        )
        assertEquals(listOf("BV2" to 1L, "BV2" to 2L), targets.map { it.bvid to it.cid })
    }

    @Test
    fun prefetchWindowWarmsTheSwipeCandidateTheAnchorAndTheForwardPagesOnce() {
        val pages = listOf(
            RelatedVideo(bvid = "BV0", cid = 10L),
            RelatedVideo(bvid = "BV1", cid = 11L),
            RelatedVideo(bvid = "BV2", cid = 12L),
            RelatedVideo(bvid = "BV3", cid = 13L),
        )
        val targets = resolvePortraitPrefetchWindowTargets(
            anchorPage = 1, pageItems = pages, preloadCount = 2, swipeTargetPage = 0, warmAnchor = true,
        )
        assertEquals(listOf("BV0", "BV1", "BV2", "BV3"), targets.map { it.bvid })
        assertTrue(resolvePortraitPrefetchWindowTargets(anchorPage = 9, pageItems = pages, preloadCount = 2).isEmpty())
    }

    @Test
    fun aSwipeCandidateThatIsAlsoAForwardPageIsWarmedOnlyOnce() {
        val pages = listOf(
            RelatedVideo(bvid = "BV0", cid = 10L),
            RelatedVideo(bvid = "BV1", cid = 11L),
            RelatedVideo(bvid = "BV2", cid = 12L),
            RelatedVideo(bvid = "BV3", cid = 13L),
        )
        // The swipe candidate (page 2) is also the first forward page of the anchor (page 1): it is listed first and not again.
        val targets = resolvePortraitPrefetchWindowTargets(
            anchorPage = 1, pageItems = pages, preloadCount = 2, swipeTargetPage = 2, warmAnchor = true,
        )
        assertEquals(listOf("BV2", "BV1", "BV3"), targets.map { it.bvid })
        // Two pages of one video are told apart by their cid, the very same part is merged.
        val parts = listOf(
            RelatedVideo(bvid = "BV5", cid = 1L),
            RelatedVideo(bvid = "BV5", cid = 2L),
            RelatedVideo(bvid = "BV5", cid = 2L),
        )
        val partTargets = resolvePortraitPrefetchWindowTargets(
            anchorPage = 0, pageItems = parts, preloadCount = 2, swipeTargetPage = 2, warmAnchor = true,
        )
        assertEquals(listOf(2L, 1L), partTargets.map { it.cid })
    }

    @Test
    fun anUnmatchedVideoKeepsItsLikeStateUnknownUntilALocalOverrideExists() {
        val unmatched = resolvePortraitVideoInteractionUiState("BV_NEXT", Stat(like = 8, favorite = 3), null)
        assertFalse(unmatched.likeStatusKnown)
        assertFalse(unmatched.isLiked)
        assertEquals(8, unmatched.likeCount)
        val liked = resolvePortraitVideoInteractionUiState(
            "BV_NEXT", Stat(like = 8, favorite = 3), null, PortraitVideoInteractionOverride(isLiked = true, likeCount = 9),
        )
        assertTrue(liked.likeStatusKnown)
        assertTrue(liked.isLiked)
        assertEquals(9, liked.likeCount)
        val onlyFavorite = resolvePortraitVideoInteractionUiState(
            "BV_NEXT", Stat(like = 8, favorite = 3), null, PortraitVideoInteractionOverride(isFavorited = true, favoriteCount = 4),
        )
        assertFalse(onlyFavorite.likeStatusKnown)
        assertTrue(onlyFavorite.isFavorited)
    }

    @Test
    fun aMatchingSharedStateForwardsItsKnownLikeFlagInBothDirections() {
        val fallback = Stat(like = 8, favorite = 3)
        for (known in listOf(false, true)) {
            val shared = VideoPlaybackUiState.Success(
                info = ViewInfo(bvid = "BV1", stat = Stat(like = 20, favorite = 6)),
                playUrl = "",
                isLiked = true,
                likeStatusKnown = known,
            )
            val matched = resolvePortraitVideoInteractionUiState("BV1", fallback, shared)
            assertEquals(known, matched.likeStatusKnown)
            assertTrue(matched.isLiked)
            assertEquals(20, matched.likeCount)
            // The shared state of another video never leaks into this one: its flags stay unknown and unliked.
            val other = resolvePortraitVideoInteractionUiState("BV2", fallback, shared)
            assertFalse(other.likeStatusKnown)
            assertFalse(other.isLiked)
            assertEquals(8, other.likeCount)
        }
    }

    @Test
    fun aTripleActionNeverAssertsALikeOverAnUnknownState() {
        val unknown = PortraitVideoInteractionUiState(
            isLiked = false, likeStatusKnown = false, isFavorited = false, likeCount = 8, favoriteCount = 3,
        )
        val favoriteOnly = resolvePortraitTripleActionOverride(unknown, likeSuccess = false, favoriteSuccess = true)
        assertNull(favoriteOnly.isLiked)
        assertEquals(true, favoriteOnly.isFavorited)
        assertEquals(4, favoriteOnly.favoriteCount)
        val liked = resolvePortraitTripleActionOverride(unknown, likeSuccess = true, favoriteSuccess = true)
        assertEquals(true, liked.isLiked)
        assertEquals(9, liked.likeCount)
        val known = resolvePortraitTripleActionOverride(unknown.copy(likeStatusKnown = true), likeSuccess = false, favoriteSuccess = false)
        assertEquals(false, known.isLiked)
    }
}
