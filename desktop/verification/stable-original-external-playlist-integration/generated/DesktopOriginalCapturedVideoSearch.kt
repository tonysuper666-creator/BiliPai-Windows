package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.data.repository.SearchRepository.SearchPageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
internal class DesktopOriginalCapturedVideoSearch(
    private val api: SearchApi,
    private val navApi: BilibiliApi,
    private val checkCurrent: () -> Unit,
) {
    suspend fun search(
        keyword: String,
        order: SearchOrder = SearchOrder.TOTALRANK,
        duration: SearchDuration = SearchDuration.ALL,
        tids: Int = 0,
        page: Int = 1,
        pubBegin: Long? = null,
        pubEnd: Long? = null
    ): Result<Pair<List<VideoItem>, SearchPageInfo>> = withContext(Dispatchers.IO) {
        try {
            checkCurrent()
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

            com.android.purebilibili.core.util.Logger.d(
                "SearchRepo",
                " search(video): keyword=$keyword, order=${order.value}, duration=${duration.value}, tids=$tids, pubBegin=$pubBegin, pubEnd=$pubEnd, page=$page"
            )

            val signedParams = signWithWbi(params)

            checkCurrent()
            val response = api.search(signedParams)
            checkCurrent()
            if (response.code != 0) {
                return@withContext Result.failure(createSearchError(response.code, response.message))
            }

            val videoList = response.data?.result
                ?.map { it.toVideoItem() }
                ?: emptyList()
            val pageInfo = resolveVideoSearchPageInfo(
                requestedPage = page,
                responsePage = response.data?.page ?: page,
                totalPages = response.data?.numPages ?: 0,
                totalResults = response.data?.numResults ?: videoList.size,
                pageSize = response.data?.pagesize ?: 20,
                resultCount = videoList.size
            )

            com.android.purebilibili.core.util.Logger.d(
                "SearchRepo",
                " search(video) result: size=${videoList.size}, page=${pageInfo.currentPage}/${pageInfo.totalPages}, hasMore=${pageInfo.hasMore}"
            )

            Result.success(Pair(videoList, pageInfo))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            checkCurrent()
            com.android.purebilibili.core.util.Logger.e("SearchRepo", "search(video) failed", e)
            Result.failure(e)
        }
    }

    private suspend fun signWithWbi(params: Map<String, String>): Map<String, String> {
        return try {
            checkCurrent()
            val navResp = navApi.getNavInfo()
            checkCurrent()
            val wbiImg = navResp.data?.wbi_img
            val imgKey = wbiImg?.img_url?.substringAfterLast("/")?.substringBefore(".") ?: ""
            val subKey = wbiImg?.sub_url?.substringAfterLast("/")?.substringBefore(".") ?: ""
            if (imgKey.isNotEmpty() && subKey.isNotEmpty()) {
                WbiUtils.sign(params, imgKey, subKey)
            } else {
                com.android.purebilibili.core.util.Logger.w(
                    "SearchRepo",
                    "signWithWbi: missing img/sub key, use unsigned params"
                )
                params
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            checkCurrent()
            com.android.purebilibili.core.util.Logger.e(
                "SearchRepo",
                "signWithWbi: failed to load nav/wbi keys, use unsigned params",
                e
            )
            params
        }
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
}
