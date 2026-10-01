package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.DesktopTodayWatchState
import kotlinx.coroutines.flow.StateFlow

/** Projection/entry points into the ONE retained original Home VM. No planner data is stored here. */
internal interface DesktopOriginalTodayWatchOwner {
    val capturedEpoch: Long
    val todayWatchState: StateFlow<DesktopTodayWatchState>
    fun isCurrentOwner(): Boolean
    suspend fun reloadTodayWatch(forceHistory: Boolean)
    suspend fun consumeTodayWatchBvid(bvid: String): Boolean
    suspend fun closeAndJoin()
}
