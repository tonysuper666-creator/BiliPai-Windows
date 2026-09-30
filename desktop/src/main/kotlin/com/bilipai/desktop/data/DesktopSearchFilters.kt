package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.SearchType
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.search.*

data class DesktopSearchFilters(
    val videoOrder: SearchOrder = SearchOrder.TOTALRANK,
    val durations: Set<SearchDuration> = emptySet(), val videoTid: Int = 0,
    val pubType: SearchVideoPubTimeType = SearchVideoPubTimeType.ALL,
    val pubBegin: Long? = null, val pubEnd: Long? = null,
    val upOrder: SearchUpOrder = SearchUpOrder.DEFAULT,
    val upSort: SearchOrderSort = SearchOrderSort.DESC,
    val userType: SearchUserType = SearchUserType.ALL,
    val liveOrder: SearchLiveOrder = SearchLiveOrder.ONLINE,
    val articleOrder: SearchOrder = SearchOrder.TOTALRANK,
    val articleCategory: SearchArticleCategory = SearchArticleCategory.ALL,
    val photoOrder: SearchOrder = SearchOrder.TOTALRANK,
    val photoCategory: SearchPhotoCategory = SearchPhotoCategory.ALL
) {
    fun withPubType(type: SearchVideoPubTimeType, nowMillis: Long = System.currentTimeMillis()): DesktopSearchFilters {
        val range = resolveSearchPubTimeRange(type, nowMillis, pubBegin, pubEnd)
        return copy(pubType = type, pubBegin = range.beginEpochSeconds, pubEnd = range.endEpochSeconds)
    }
    fun withCustomRange(begin: Long, end: Long): DesktopSearchFilters = copy(pubType = SearchVideoPubTimeType.CUSTOM,
        pubBegin = minOf(begin, end), pubEnd = maxOf(begin, end))

    fun parameters(type: SearchType): Map<String, String> = when (type) {
        SearchType.UP -> mapOf("order" to upOrder.value, "order_sort" to upSort.value.toString(), "user_type" to userType.value.toString())
        SearchType.LIVE -> mapOf("order" to liveOrder.value)
        SearchType.ARTICLE -> mapOf("order" to articleOrder.value, "category_id" to articleCategory.value.toString())
        SearchType.PHOTO -> mapOf("order" to photoOrder.value, "category_id" to photoCategory.value.toString())
        else -> emptyMap()
    }
    internal fun requestKey(type: SearchType): Any = if (type == SearchType.VIDEO) listOf(videoOrder, durations, videoTid, pubType, pubBegin, pubEnd) else parameters(type)
}
