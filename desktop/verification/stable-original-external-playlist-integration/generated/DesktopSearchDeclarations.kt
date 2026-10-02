// GENERATED verbatim from app/src/main/java/com/android/purebilibili/data/repository/SearchRepository.kt

package com.android.purebilibili.data.repository

import com.android.purebilibili.data.model.response.HotItem

data class SearchTrendingBundle(
    val pinnedItems: List<HotItem> = emptyList(),
    val items: List<HotItem> = emptyList()
) {
    val allItems: List<HotItem>
        get() = pinnedItems + items
}

enum class SearchOrder(val value: String, val displayName: String) {
    TOTALRANK("totalrank", "综合排序"),
    PUBDATE("pubdate", "最新发布"),
    CLICK("click", "播放最多"),
    DM("dm", "弹幕最多"),
    STOW("stow", "收藏最多"),
    SCORES("scores", "评论最多"),
    ATTENTION("attention", "喜欢最多")
}

enum class SearchArticleCategory(val value: Int, val displayName: String) {
    ALL(0, "全部分区"),
    GAME(1, "游戏"),
    ANIMATION(2, "动画"),
    LIFE(3, "生活"),
    LIGHT_NOVEL(16, "轻小说"),
    TECHNOLOGY(17, "科技"),
    MOVIE(28, "影视"),
    INTEREST(29, "兴趣")
}

enum class SearchPhotoCategory(val value: Int, val displayName: String) {
    ALL(0, "全部分区"),
    ILLUSTRATION(1, "画友"),
    PHOTOGRAPHY(2, "摄影")
}

enum class SearchDuration(val value: Int, val displayName: String) {
    ALL(0, "全部时长"),
    UNDER_10MIN(1, "10分钟以下"),
    TEN_TO_30MIN(2, "10-30分钟"),
    THIRTY_TO_60MIN(3, "30-60分钟"),
    OVER_60MIN(4, "60分钟以上")
}

enum class SearchUpOrder(val value: String, val displayName: String) {
    DEFAULT("0", "默认排序"),
    FANS("fans", "粉丝数"),
    LEVEL("level", "用户等级")
}

enum class SearchOrderSort(val value: Int, val displayName: String) {
    DESC(0, "从高到低"),
    ASC(1, "从低到高")
}

enum class SearchUserType(val value: Int, val displayName: String) {
    ALL(0, "全部类型"),
    UP(1, "仅UP主"),
    NORMAL(2, "普通用户"),
    VERIFIED(3, "认证用户")
}

enum class SearchLiveOrder(val value: String, val displayName: String) {
    ONLINE("online", "人气直播"),
    LIVE_TIME("live_time", "最新开播")
}

object SearchRepository {
    data class SearchPageInfo(
        val currentPage: Int,
        val totalPages: Int,
        val totalResults: Int,
        val hasMore: Boolean
    )
    private fun searchTypeParams(
        keyword: String,
        searchType: String,
        page: Int,
        extra: Map<String, String> = emptyMap()
    ): MutableMap<String, String> {
        return mutableMapOf(
            "keyword" to keyword,
            "search_type" to searchType,
            "page" to page.toString(),
            "page_size" to "20",
            "platform" to "pc",
            "web_location" to "1430654"
        ).apply { putAll(extra) }
    }
    internal fun desktopSearchTypeParams(keyword: String, searchType: String, page: Int, extra: Map<String, String> = emptyMap()): Map<String, String> = searchTypeParams(keyword, searchType, page, extra)
}

internal fun desktopSearchTypeParams(keyword: String, searchType: String, page: Int, extra: Map<String, String> = emptyMap()): Map<String, String> = SearchRepository.desktopSearchTypeParams(keyword, searchType, page, extra)

internal fun desktopVideoSearchParams(keyword: String, order: SearchOrder, duration: SearchDuration, tids: Int, page: Int, pubBegin: Long?, pubEnd: Long?): Map<String, String> {
    val params = mutableMapOf(
        "keyword" to keyword,
        "search_type" to "video",
        "order" to order.value,
        "duration" to duration.value.toString(),
        "page" to page.toString(),
        "page_size" to "20",
        "platform" to "pc",
        "web_location" to "1430654"
    )
    if (tids != 0) {
        params["tids"] = tids.toString()
    }
    if (pubBegin != null) {
        params["pubtime_begin_s"] = pubBegin.toString()
    }
    if (pubEnd != null) {
        params["pubtime_end_s"] = pubEnd.toString()
    }
    return params
}

internal fun desktopSearchFallbackKeywords(): List<String> {
    val fallbackKeywords = listOf("黑神话悟空", "原神", "初音未来", "JOJO", "罗翔说刑法", "何同学", "毕业季", "猫咪", "我的世界", "战鹰")
    return fallbackKeywords
}
