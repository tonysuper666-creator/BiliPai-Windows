package com.android.purebilibili.feature.home

import com.android.purebilibili.data.model.response.PopularSeriesOneData
import com.android.purebilibili.data.model.response.PopularSeriesPeriod

/** Platform port to the existing shared DiscoveryRepository; no client or cache. */
interface DesktopWeeklySeriesRequests {
    suspend fun getWeeklyPeriods(): Result<List<PopularSeriesPeriod>>
    suspend fun getWeeklyPeriod(number: Int): Result<PopularSeriesOneData>
}

/** Original SavedStateHandle's one route value, retained by Root BrowseMemory. */
class DesktopWeeklySeriesSavedState {
    @Volatile private var weeklyNumber: Int? = null
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? {
        check(key == "weeklyNumber")
        return weeklyNumber as T?
    }
    operator fun set(key: String, number: Int) {
        check(key == "weeklyNumber")
        weeklyNumber = number
    }
}
