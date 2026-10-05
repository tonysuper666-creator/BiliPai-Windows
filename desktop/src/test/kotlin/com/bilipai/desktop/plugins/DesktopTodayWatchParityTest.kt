package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.home.*
import com.android.purebilibili.feature.plugin.TodayWatchPlugin
import kotlin.test.*

class DesktopTodayWatchParityTest {
    @Test fun `original recommendation planner excludes completed consumed and disliked videos`() {
        val completed = video("completed", 1).copy(progress = 100)
        val consumed = video("consumed", 2)
        val disliked = video("disliked", 3)
        val blocked = video("blocked", 4)
        val keyword = video("keyword", 5).copy(title = "不喜欢的测试")
        val usable = video("usable", 6)
        val plugin = TodayWatchPlugin { error("Pure recommendation must not read a platform context") }
        val result = plugin.buildRecommendations(RecommendationRequest(listOf(completed, consumed, disliked, blocked, keyword, usable),
            listOf(completed), feedbackSignals = RecommendationFeedbackSignals(setOf("consumed"), setOf("disliked"), setOf(4), setOf("不喜欢")),
            mode = RecommendationMode.LEARN, queueLimit = 20, groupLimit = 5))
        assertEquals(listOf(usable), result.items.map { it.video })
        assertEquals(1, result.historySampleCount)
        assertTrue(result.items.single().explanation.isNotBlank())
        assertTrue(result.items.single().confidence in 0f..1f)
    }

    @Test fun `original queue consumption removes scores and signals refill once`() {
        val plugin = TodayWatchPlugin { error("Pure recommendation must not read a platform context") }
        val videos = listOf(video("one", 1), video("two", 2))
        val plan = plugin.buildRecommendations(RecommendationRequest(videos, emptyList(), mode = RecommendationMode.RELAX,
            queueLimit = 20, groupLimit = 5)).toTodayWatchPlan()
        val consumed = consumeVideoFromTodayWatchPlan(plan, "one", 6)
        assertTrue(consumed.consumedApplied)
        assertTrue(consumed.shouldRefill)
        assertEquals(listOf("two"), consumed.updatedPlan.videoQueue.map { it.bvid })
        assertFalse("one" in consumed.updatedPlan.scoreByBvid)
        assertFalse("one" in consumed.updatedPlan.confidenceByBvid)
        assertFalse("one" in consumed.updatedPlan.explanationByBvid)
        assertFalse(consumeVideoFromTodayWatchPlan(consumed.updatedPlan, "one", 6).consumedApplied)
    }

    private fun video(bvid: String, mid: Long) = VideoItem(bvid = bvid, cid = 10, title = "学习视频$bvid", duration = 100,
        owner = Owner(mid = mid, name = "作者$mid"), stat = Stat(view = 10_000), pubdate = 1_000)
}
