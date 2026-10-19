package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextDecoration
import com.android.purebilibili.feature.video.playback.policy.PlaybackPostLoadTask
import com.android.purebilibili.feature.video.playback.policy.PlaybackPostLoadTaskSpec
import com.android.purebilibili.feature.video.playback.policy.buildPlaybackPostLoadPlan
import com.android.purebilibili.feature.video.ui.feedback.VideoActionCountRequests
import com.android.purebilibili.feature.video.ui.feedback.VideoTriplePressState
import com.android.purebilibili.feature.video.ui.feedback.resolveVideoTripleHoldProgress
import com.android.purebilibili.feature.video.ui.feedback.shouldAnimateVideoActionCountChange
import com.android.purebilibili.feature.video.ui.section.buildVideoDescriptionAnnotatedString
import com.android.purebilibili.feature.video.ui.section.resolveVideoDetailOwnerStatsLabels
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Roadmap vid-info-section-repin: the v0.4.6 detail information, action count, triple-press and first-batch plan
 * declarations that the Windows producers now emit from whole upstream files. Pure functions and state holders only:
 * no composition, window, GPU or network is created. The ownerStats, triple-press and plan cases are the upstream
 * assertions (VideoInfoDisplayPolicyTest, VideoTriplePressStateTest, PlaybackPostLoadPlanPolicyTest). */
class DesktopVideoInfoRepinTest {
    @Test
    fun ownerStats_placesCountsBeforeLabels() {
        assertEquals(listOf("9粉丝", "2视频"), resolveVideoDetailOwnerStatsLabels(9, 2))
    }

    @Test
    fun ownerStats_preservesZeroAndHidesUnknownCounts() {
        assertEquals(listOf("0粉丝", "0视频"), resolveVideoDetailOwnerStatsLabels(0, 0))
        assertEquals(listOf("2视频"), resolveVideoDetailOwnerStatsLabels(null, 2))
        assertEquals(listOf("9粉丝"), resolveVideoDetailOwnerStatsLabels(9, -1))
        assertEquals(emptyList(), resolveVideoDetailOwnerStatsLabels(null, null))
        assertEquals(emptyList(), resolveVideoDetailOwnerStatsLabels(-1, -1))
    }

    @Test
    fun descriptionTopic_isASearchLinkWithoutUnderline() {
        val desc = "看看 #测试话题# 真好"
        val text = buildVideoDescriptionAnnotatedString(desc = desc, urlColor = Color.Red)
        assertEquals(desc, text.text)
        val link = text.getLinkAnnotations(0, text.length).single()
        val clickable = assertNotNull(link.item as? LinkAnnotation.Clickable)
        assertEquals("bilibili://search?keyword=" + URLEncoder.encode("测试话题", "UTF-8"), clickable.tag)
        assertEquals("#测试话题#", desc.substring(link.start, link.end))
        val style = text.spanStyles.single { it.start == link.start && it.end == link.end }
        assertEquals(TextDecoration.None, style.item.textDecoration)
        assertEquals(Color.Red, style.item.color)
    }

    @Test
    fun descriptionTopic_endsAtPunctuationInsteadOfRequiringAClosingMark() {
        val desc = "#只有开头的话题，后面是正文"
        val text = buildVideoDescriptionAnnotatedString(desc = desc, urlColor = Color.Blue)
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("#只有开头的话题", desc.substring(link.start, link.end))
        val clickable = assertNotNull(link.item as? LinkAnnotation.Clickable)
        assertEquals("bilibili://search?keyword=" + URLEncoder.encode("只有开头的话题", "UTF-8"), clickable.tag)
    }

    @Test
    fun descriptionUrl_keepsItsUnderline() {
        val desc = "详见 https://www.bilibili.com/video/BV1xx411c7mD 谢谢"
        val text = buildVideoDescriptionAnnotatedString(desc = desc, urlColor = Color.Blue)
        val link = text.getLinkAnnotations(0, text.length).first()
        val style = text.spanStyles.single { it.start == link.start && it.end == link.end }
        assertEquals(TextDecoration.Underline, style.item.textDecoration)
    }

    @Test
    fun tripleHoldProgress_usesElapsedTimeAndRequiresTheFullHold() {
        assertEquals(0f, resolveVideoTripleHoldProgress(-1L))
        assertEquals(0.5f, resolveVideoTripleHoldProgress(450_000_000L))
        assertTrue(resolveVideoTripleHoldProgress(899_000_000L) < 1f)
        assertEquals(1f, resolveVideoTripleHoldProgress(900_000_000L))
        assertEquals(1f, resolveVideoTripleHoldProgress(1_800_000_000L))
    }

    @Test
    fun triplePress_releasedOrIncompleteHoldCannotSubmit() {
        val state = VideoTriplePressState()
        state.start()
        state.updateProgress(0.6f)
        assertFalse(state.complete())
        state.release()
        state.updateProgress(1f)
        assertFalse(state.complete())
    }

    @Test
    fun triplePress_completedHoldSubmitsOnlyOnceAndNextHoldStartsFresh() {
        val state = VideoTriplePressState()
        state.start()
        state.updateProgress(1f)
        assertTrue(state.complete())
        assertTrue(state.completed)
        assertFalse(state.complete())
        state.start()
        assertEquals(0f, state.progress)
        assertFalse(state.completed)
        assertFalse(state.complete())
    }

    @Test
    fun triplePress_restartingDuringRetractionDoesNotInheritOldProgress() {
        val state = VideoTriplePressState()
        state.start()
        state.updateProgress(0.8f)
        state.release()
        state.start()
        assertEquals(0f, state.progress)
        assertTrue(state.isPressing)
    }

    @Test
    fun countMotion_armsOnlyAfterAnExplicitLocalActionAndNeverForHydration() {
        // An explicit like request animates a changed digit count once.
        assertTrue(shouldAnimateVideoActionCountChange("1", "2", request = 1, consumedRequest = 0, reducedMotion = false))
        // Hydration (no request yet), an already consumed request, reduced motion, an unchanged or digitless text stay still.
        assertFalse(shouldAnimateVideoActionCountChange("1", "2", request = 0, consumedRequest = 0, reducedMotion = false))
        assertFalse(shouldAnimateVideoActionCountChange("1", "2", request = 1, consumedRequest = 1, reducedMotion = false))
        assertFalse(shouldAnimateVideoActionCountChange("1", "2", request = 1, consumedRequest = 0, reducedMotion = true))
        assertFalse(shouldAnimateVideoActionCountChange("2", "2", request = 1, consumedRequest = 0, reducedMotion = false))
        assertFalse(shouldAnimateVideoActionCountChange("点赞", "投币", request = 1, consumedRequest = 0, reducedMotion = false))
    }

    @Test
    fun countRequests_tripleArmsLikeCoinAndFavoriteTogether() {
        val requests = VideoActionCountRequests()
        assertEquals(Triple(0, 0, 0), Triple(requests.like, requests.coin, requests.favorite))
        requests.requestCoin()
        assertEquals(Triple(0, 1, 0), Triple(requests.like, requests.coin, requests.favorite))
        requests.requestTriple()
        assertEquals(Triple(1, 2, 1), Triple(requests.like, requests.coin, requests.favorite))
    }

    @Test
    fun firstBatch_requestsTagsAndInteractionStateImmediately() {
        val guest = buildPlaybackPostLoadPlan(isLoggedIn = false, shouldShowOnlineCount = false)
        val member = buildPlaybackPostLoadPlan(isLoggedIn = true, shouldShowOnlineCount = false)
        assertEquals(150L, guest.firstDelayOf(PlaybackPostLoadTask.PLAYER_INFO))
        assertEquals(0L, guest.firstDelayOf(PlaybackPostLoadTask.VIDEO_TAGS))
        assertTrue(guest.firstDelayOf(PlaybackPostLoadTask.OWNER_STATS) > guest.firstDelayOf(PlaybackPostLoadTask.PLAYER_INFO))
        assertEquals(0L, member.firstDelayOf(PlaybackPostLoadTask.REFRESH_DEFERRED_SIGNALS))
        assertTrue(guest.none { it.task == PlaybackPostLoadTask.REFRESH_DEFERRED_SIGNALS })
        assertTrue(member.any { it.task == PlaybackPostLoadTask.LOAD_FOLLOWING_MIDS })
    }

    private fun List<PlaybackPostLoadTaskSpec>.firstDelayOf(task: PlaybackPostLoadTask): Long =
        first { spec -> spec.task == task }.delayMs
}
