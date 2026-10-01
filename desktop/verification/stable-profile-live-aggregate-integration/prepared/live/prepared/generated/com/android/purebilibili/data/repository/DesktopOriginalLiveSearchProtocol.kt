package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.SearchRepository.SearchPageInfo
import kotlinx.coroutines.*
internal class DesktopOriginalLiveSearchProtocol(private val api:SearchApi, private val navApi:BilibiliApi) {
    suspend fun signSearch(params:Map<String,String>):Map<String,String> = signWithWbi(params)
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

    private fun createPageInfo(
        requestedPage: Int,
        responsePage: Int,
        totalPages: Int,
        totalResults: Int,
        fallbackResultCount: Int
    ): SearchPageInfo {
        val resolvedPage = resolveSearchLoadedPage(
            requestedPage = requestedPage,
            responsePage = responsePage
        )
        val resolvedTotalPages = totalPages.takeIf { it > 0 } ?: 1
        return SearchPageInfo(
            currentPage = resolvedPage,
            totalPages = resolvedTotalPages,
            totalResults = totalResults.takeIf { it > 0 } ?: fallbackResultCount,
            hasMore = resolvedPage < resolvedTotalPages
        )
    }

    private fun createSearchError(code: Int, message: String): Exception {
        val readable = when (code) {
            -412 -> "搜索请求被拦截，请稍后重试"
            -400 -> "搜索参数错误"
            -404 -> "搜索接口不存在"
            -1200 -> "搜索类型不存在或参数被降级过滤"
            else -> message.ifBlank { "搜索失败 ($code)" }
        }
        return Exception(readable)
    }

    suspend fun searchLive(
        keyword: String,
        page: Int = 1,
        order: SearchLiveOrder = SearchLiveOrder.ONLINE
    ): Result<Pair<List<LiveRoomSearchItem>, SearchPageInfo>> = withContext(Dispatchers.IO) {
        try {
            val params = searchTypeParams(
                keyword = keyword,
                searchType = "live_room",
                page = page,
                extra = mapOf("order" to order.value)
            )

            val signedParams = signWithWbi(params)

            val response = api.searchLive(signedParams)
            if (response.code != 0) {
                return@withContext Result.failure(createSearchError(response.code, response.message))
            }

            val liveList = response.data?.result?.map { it.cleanupFields() } ?: emptyList()

            val pageInfo = createPageInfo(
                requestedPage = page,
                responsePage = response.data?.page ?: page,
                totalPages = response.data?.numPages ?: 1,
                totalResults = response.data?.numResults ?: liveList.size,
                fallbackResultCount = liveList.size
            )

            com.android.purebilibili.core.util.Logger.d("SearchRepo", "🔍 Live search result: ${liveList.size} rooms, page ${pageInfo.currentPage}/${pageInfo.totalPages}")

            Result.success(Pair(liveList, pageInfo))
        } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private suspend fun signWithWbi(params: Map<String, String>): Map<String, String> {
        return try {
            val navResp = navApi.getNavInfo()
            val wbiImg = navResp.data?.wbi_img
            val imgKey = wbiImg?.img_url?.substringAfterLast("/")?.substringBefore(".") ?: ""
            val subKey = wbiImg?.sub_url?.substringAfterLast("/")?.substringBefore(".") ?: ""
            if (imgKey.isNotEmpty() && subKey.isNotEmpty()) {
                WbiUtils.sign(params, imgKey, subKey)
            } else {
                android.util.Log.w(
                    "SearchRepo",
                    "signWithWbi: missing img/sub key, use unsigned params"
                )
                params
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.android.purebilibili.core.util.Logger.e(
                "SearchRepo",
                "signWithWbi: failed to load nav/wbi keys, use unsigned params",
                e
            )
            params
        }
    }
}
