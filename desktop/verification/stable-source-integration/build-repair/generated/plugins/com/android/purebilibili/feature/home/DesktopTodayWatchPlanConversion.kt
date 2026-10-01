// GENERATED from app/src/main/java/com/android/purebilibili/feature/home/HomeViewModel.kt; do not edit.
// LF-normalized SHA-256: 63ffd0eadd3080e2b304612c9c1e672f5f2370ca9c9db4f8040a52e85cbc15aa
package com.android.purebilibili.feature.home

import com.android.purebilibili.core.plugin.*

import kotlinx.collections.immutable.*

private fun RecommendationMode.toTodayWatchMode(): TodayWatchMode {
    return when (this) {
        RecommendationMode.RELAX -> TodayWatchMode.RELAX
        RecommendationMode.LEARN -> TodayWatchMode.LEARN
    }
}

internal fun RecommendationResult.toTodayWatchPlan(): TodayWatchPlan {
    val creatorGroup = groups.firstOrNull { it.id == "preferred_creators" }
    return TodayWatchPlan(
        mode = mode.toTodayWatchMode(),
        upRanks = creatorGroup.toTodayUpRanks().toImmutableList(),
        videoQueue = items.map { it.video }.toImmutableList(),
        explanationByBvid = items.associate { it.video.bvid to it.explanation }.toImmutableMap(),
        scoreByBvid = items.associate { it.video.bvid to it.score }.toImmutableMap(),
        confidenceByBvid = items.associate { it.video.bvid to it.confidence }.toImmutableMap(),
        historySampleCount = historySampleCount,
        nightSignalUsed = sceneSignals.eyeCareNightActive,
        generatedAt = generatedAt
    )
}

private fun RecommendationGroup?.toTodayUpRanks(): List<TodayUpRank> {
    return this?.items.orEmpty().mapNotNull { item ->
        val mid = item.id.toLongOrNull() ?: return@mapNotNull null
        TodayUpRank(
            mid = mid,
            name = item.title,
            score = item.score ?: 0.0,
            watchCount = item.watchCount ?: 1
        )
    }
}
