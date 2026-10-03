package com.bilipai.desktop.data

import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** SearchRepository policies using the same desktop account and original Retrofit API. */
class DesktopSearchRepository(private val repository: DesktopRepository) {
    private val web = Retrofit.Builder().baseUrl("https://api.bilibili.com/")
        .client(repository.httpClient)
        .addConverterFactory(Json { ignoreUnknownKeys = true; coerceInputValues = true }.asConverterFactory("application/json".toMediaType()))
        .build()
    private val api = web.create(SearchApi::class.java)

    suspend fun defaultHint(): String = read {
        val current = optional { api.getDefaultSearch(repository.signWebParams(emptyMap())) }
        if (current?.code == 0 && !current.data?.showName.isNullOrBlank()) return@read current.data!!.showName.trim()
        val legacy = api.getDefaultSearchLegacy()
        requireSuccess(legacy.code, legacy.message)
        legacy.data?.showName?.trim()?.takeIf { it.isNotBlank() } ?: throw BiliApiException(-1, "默认搜索词为空")
    }

    suspend fun trending(limit: Int = 30): SearchTrendingBundle = read {
        require(limit in 1..50)
        val current = optional { api.getHotSearch(repository.signWebParams(mapOf("limit" to limit.toString()))) }
        val items = current?.takeIf { it.code == 0 }?.data?.trending?.list
            ?.filter { it.keyword.isNotBlank() || it.show_name.isNotBlank() }.orEmpty()
        if (items.isNotEmpty()) return@read SearchTrendingBundle(items = items.take(limit))
        val legacy = api.getTrendingList(limit)
        requireSuccess(legacy.code, legacy.message)
        SearchTrendingBundle(legacy.topList ?: legacy.data?.topList.orEmpty(), legacy.list ?: legacy.data?.list.orEmpty())
    }

    suspend fun discover(personalized: Boolean = true): List<HotItem> = read {
        if (!personalized) return@read trending(12).allItems.take(12)
        val expectedEpoch = repository.sessionEpoch
        val identity = repository.ownedHomeAccessTokenIdentity(expectedEpoch) { repository.sessionEpoch == expectedEpoch }
        val response = api.getSearchRecommend(buildSearchRecommendParams(identity.first, identity.second,
            com.android.purebilibili.core.network.AppSignUtils.getTimestamp()))
        requireSuccess(response.code, response.message)
        response.data?.list.orEmpty().filter { it.keyword.isNotBlank() || it.show_name.isNotBlank() }
    }

    suspend fun videos(keyword: String, page: Int = 1, order: SearchOrder = SearchOrder.TOTALRANK,
        durations: Set<SearchDuration> = emptySet(), tids: Int = 0, pubBegin: Long? = null, pubEnd: Long? = null): TypedSearchPage = read {
        require(keyword.isNotBlank() && page > 0 && tids >= 0)
        val results = mutableListOf<SearchTypeData>()
        var firstFailure: Exception? = null
        for (duration in resolveSearchDurationRequests(durations)) {
            try {
                val response = api.search(repository.signWebParams(desktopVideoSearchParams(keyword.trim(), order, duration, tids, page, pubBegin, pubEnd)))
                requireSuccess(response.code, response.message)
                results += response.data ?: throw BiliApiException(-1, "视频搜索数据为空")
            } catch (error: Exception) { if (error is CancellationException) throw error; if (firstFailure == null) firstFailure = error }
        }
        if (results.isEmpty()) throw firstFailure ?: BiliApiException(-1, "视频搜索失败")
        val combined = mergeDesktopSearchVideoPages(results, page)
        TypedSearchPage(SearchType.VIDEO, CommunitySearchResult.Videos(combined.first), combined.second)
    }

    private suspend fun <T> read(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val epoch = repository.sessionEpoch
        repository.ensureSession()
        val result = block()
        if (repository.sessionEpoch != epoch) throw BiliApiException(-101, "账号已切换，请重新加载")
        result
    }
}

private suspend fun <T> optional(block: suspend () -> T): T? = try { block() }
catch (error: Exception) { if (error is CancellationException) throw error; null }

private fun requireSuccess(code: Int, message: String) {
    if (code != 0) throw BiliApiException(code, if (code in setOf(-101, -111)) "登录凭证已失效，请重新登录" else message.ifBlank { "搜索请求失败 ($code)" })
}

internal fun mergeDesktopSearchVideoPages(pages: List<SearchTypeData>, requestedPage: Int): Pair<SearchTypeData, Int?> {
    require(pages.isNotEmpty())
    val items = mergeSearchPageResults(emptyList(), pages.flatMap { it.result.orEmpty() }) { raw ->
        val item = raw.toVideoItem()
        when {
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            item.aid > 0 -> "aid:${item.aid}"
            item.id > 0 -> "id:${item.id}"
            else -> "title:${item.title}:${item.owner.mid}"
        }
    }
    val infos = pages.map { raw -> resolveVideoSearchPageInfo(requestedPage, raw.page, raw.numPages, raw.numResults, raw.pagesize, raw.result.orEmpty().size) }
    val info = mergeSearchDurationResultPages(pages.zip(infos).map { (raw, pageInfo) -> raw.result.orEmpty().map { it.toVideoItem() } to pageInfo }).second
    val combined = pages.first().copy(page = info.currentPage, result = items, numPages = info.totalPages, numResults = info.totalResults)
    return combined to (info.currentPage + 1).takeIf { info.currentPage < Int.MAX_VALUE && info.hasMore }
}
