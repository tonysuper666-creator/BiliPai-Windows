// GENERATED from app/src/main/java/com/android/purebilibili/feature/home/HomeUiState.kt; do not edit.
// LF-normalized SHA-256: 3dd55fff4015adb388f9a403e6ae585a01b4a81f9aff3502a3af97f069187939
package com.android.purebilibili.feature.home

import androidx.compose.runtime.Immutable

import com.android.purebilibili.data.model.response.VideoItem

import kotlinx.collections.immutable.*

enum class TodayWatchMode(val label: String) {
    RELAX("今晚轻松看"),
    LEARN("深度学习看")
}

@Immutable
data class TodayUpRank(
    val mid: Long,
    val name: String,
    val score: Double,
    val watchCount: Int
)

@Immutable
data class TodayWatchPlan(
    val mode: TodayWatchMode = TodayWatchMode.RELAX,
    val upRanks: ImmutableList<TodayUpRank> = persistentListOf(),
    val videoQueue: ImmutableList<VideoItem> = persistentListOf(),
    val explanationByBvid: ImmutableMap<String, String> = persistentMapOf(),
    val scoreByBvid: ImmutableMap<String, Double> = persistentMapOf(),
    val confidenceByBvid: ImmutableMap<String, Float> = persistentMapOf(),
    val historySampleCount: Int = 0,
    val nightSignalUsed: Boolean = false,
    val generatedAt: Long = 0L
)
