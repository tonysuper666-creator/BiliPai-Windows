package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BangumiApi
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.Dispatchers
import com.bilipai.desktop.ui.ownedBangumiRequest
import kotlinx.serialization.json.Json

/** State-free original protocol bodies borrowing the same Root API/Hub/credentials. */
internal class DesktopOriginalBangumiPagesRequests(
    private val api: BangumiApi,
    private val hub: DesktopOriginalBangumiHubRepository,
    private val csrf: () -> String?,
    private val sessionCookie: () -> String?,
    private val owned: () -> Boolean,
    private val actionOwned: () -> Boolean,
) {
    suspend fun getTimeline(type: Int = 1, before: Int = 3, after: Int = 7) = ownedBangumiRequest(Dispatchers.IO, owned) { hub.getTimeline(type,before,after) }
    suspend fun getMyFollowBangumi(type: Int = 1, followStatus: Int? = null, page: Int = 1, pageSize: Int = 30, vmid: Long? = null) = ownedBangumiRequest(Dispatchers.IO, owned) { hub.getMyFollowBangumi(type,followStatus,page,pageSize,vmid) }
    suspend fun searchBangumi(keyword: String, seasonType: Int = BangumiType.ANIME.value, page: Int = 1, pageSize: Int = 20) = ownedBangumiRequest(Dispatchers.IO, owned) { hub.searchBangumi(keyword,seasonType,page,pageSize) }
    suspend fun unfollowBangumi(seasonId: Long, isCourse: Boolean = false) = ownedBangumiRequest(Dispatchers.IO, actionOwned) { hub.unfollowBangumi(seasonId,isCourse) }
    suspend fun updateBangumiFollowStatus(seasonId: Long,status: Int) = ownedBangumiRequest(Dispatchers.IO, actionOwned) { hub.updateBangumiFollowStatus(seasonId,status) }
suspend fun getBangumiIndex(
    seasonType: Int = 1,
    page: Int = 1,
    pageSize: Int = 20
): Result<BangumiIndexData> = ownedBangumiRequest(Dispatchers.IO, owned) {
    try {
        val requestFilter = buildBangumiIndexRequestFilter(
            filter = BangumiFilter(),
            seasonType = seasonType
        )
        val response = api.getBangumiIndex(
            seasonType = seasonType,
            st = seasonType,  //  [修复] st 必须与 seasonType 相同
            page = page,
            pageSize = pageSize,
            order = requestFilter.order,
            sort = requestFilter.sortDirection,
            area = requestFilter.area,
            isFinish = requestFilter.isFinish,
            year = requestFilter.year,
            releaseDate = requestFilter.releaseDate,
            styleId = requestFilter.styleId,
            producerId = requestFilter.producerId,
            seasonStatus = requestFilter.seasonStatus,
            seasonVersion = requestFilter.seasonVersion,
            spokenLanguageType = requestFilter.spokenLanguageType,
            copyright = requestFilter.copyright,
            seasonMonth = requestFilter.seasonMonth
        )
        if (response.code == 0 && response.data != null) {
            Result.success(response.data)
        } else {
            Result.failure(Exception("获取番剧列表失败: ${response.message}"))
        }
    } catch (e: Exception) {
        android.util.Log.e("BangumiRepo", "getBangumiIndex error: ${e.message}")
        Result.failure(e)
    }
}

suspend fun getBangumiIndexWithFilter(
    seasonType: Int = 1,
    page: Int = 1,
    pageSize: Int = 20,
    filter: BangumiFilter = BangumiFilter()
): Result<BangumiIndexData> = ownedBangumiRequest(Dispatchers.IO, owned) {
    try {
        val requestFilter = buildBangumiIndexRequestFilter(
            filter = filter,
            seasonType = seasonType
        )
        val response = api.getBangumiIndex(
            seasonType = seasonType,
            st = seasonType,
            page = page,
            pageSize = pageSize,
            order = requestFilter.order,
            sort = requestFilter.sortDirection,
            area = requestFilter.area,
            isFinish = requestFilter.isFinish,
            year = requestFilter.year,
            releaseDate = requestFilter.releaseDate,
            styleId = requestFilter.styleId,
            producerId = requestFilter.producerId,
            seasonStatus = requestFilter.seasonStatus,
            seasonVersion = requestFilter.seasonVersion,
            spokenLanguageType = requestFilter.spokenLanguageType,
            copyright = requestFilter.copyright,
            seasonMonth = requestFilter.seasonMonth
        )
        if (response.code == 0 && response.data != null) {
            Result.success(response.data)
        } else {
            Result.failure(Exception("获取番剧列表失败: ${response.message}"))
        }
    } catch (e: Exception) {
        android.util.Log.e("BangumiRepo", "getBangumiIndexWithFilter error: ${e.message}")
        Result.failure(e)
    }
}

suspend fun getSeasonDetail(seasonId: Long = 0, epId: Long = 0): Result<BangumiDetail> = ownedBangumiRequest(Dispatchers.IO, owned) {
    try {
        //  [修复] 使用 ResponseBody 自行解析，避免大型番剧导致 OOM
        // 优先使用 epId (因为历史记录中的 seasonId 可能是 AVID，而 epId 是准确的)，如果 epId 为 0 则使用 seasonId
        val responseBody = if (epId > 0) {
            api.getSeasonDetail(epId = epId)
        } else if (seasonId > 0) {
            api.getSeasonDetail(seasonId = seasonId)
        } else {
            return@ownedBangumiRequest Result.failure(Exception("参数错误: seasonId 和 epId 不能同时为空"))
        }

        val jsonString = responseBody.string()

        // 使用 kotlinx.serialization.json 手动解析
        val json = kotlinx.serialization.json.Json { 
            ignoreUnknownKeys = true 
            coerceInputValues = true
        }

        val response = json.decodeFromString<BangumiDetailResponse>(jsonString)

        if (response.code == 0 && response.result != null) {
            val rawDetail = response.result
            val resolvedDetail = if (rawDetail.seasonId > 0L && shouldLoadBangumiSections(rawDetail)) {
                runCatching { api.getSeasonSections(rawDetail.seasonId) }
                    .getOrNull()
                    ?.takeIf { it.code == 0 }
                    ?.result
                    ?.let { mergeBangumiDetailSections(rawDetail, it) }
                    ?: rawDetail
            } else {
                rawDetail
            }
            //  [调试] 打印追番状态和认证信息
            val userStatus = resolvedDetail.userStatus
            android.util.Log.w("BangumiRepo", """
                 getSeasonDetail 结果:
                - request seasonId: $seasonId, epId: $epId
                - result seasonId: ${resolvedDetail.seasonId}
                - title: ${resolvedDetail.title}
                - userStatus: $userStatus
                - follow: ${userStatus?.follow} (1=已追番, 0=未追番)
                - SESSDATA存在: ${sessionCookie()?.isNotEmpty() == true}
            """.trimIndent())
            Result.success(resolvedDetail)
        } else {
            // 如果 PGC 接口返回错误（例如 -404 啥都木有），尝试 PUGV 课堂/课程接口
            val pugvResult = getPugvSeasonDetail(seasonId = seasonId, epId = epId)
            if (pugvResult.isSuccess) {
                return@ownedBangumiRequest pugvResult
            }
            Result.failure(Exception("获取番剧详情失败: ${response.message}"))
        }
    } catch (e: OutOfMemoryError) {
        //  [修复] 捕获 OOM 错误，给出更友好的提示
        android.util.Log.e("BangumiRepo", " getSeasonDetail OOM: 番剧数据过大，内存不足", e)
        System.gc() // 尝试触发 GC 回收内存
        Result.failure(Exception("加载失败：番剧数据过大，请稍后重试"))
    } catch (e: Exception) {
        android.util.Log.e("BangumiRepo", "getSeasonDetail error: ${e.message}")
        val pugvResult = getPugvSeasonDetail(seasonId = seasonId, epId = epId)
        if (pugvResult.isSuccess) {
            return@ownedBangumiRequest pugvResult
        }
        Result.failure(e)
    }
}

suspend fun getPugvSeasonDetail(seasonId: Long = 0, epId: Long = 0): Result<BangumiDetail> = ownedBangumiRequest(Dispatchers.IO, owned) {
    try {
        val responseBody = if (epId > 0) {
            api.getPugvSeasonDetail(epId = epId)
        } else if (seasonId > 0) {
            api.getPugvSeasonDetail(seasonId = seasonId)
        } else {
            return@ownedBangumiRequest Result.failure(Exception("参数错误: seasonId 和 epId 不能同时为空"))
        }

        val jsonString = responseBody.string()
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        val response = json.decodeFromString<com.android.purebilibili.data.model.response.PugvSeasonResponse>(jsonString)
        if (response.code == 0 && response.data != null) {
            val detail = response.data.toBangumiDetail()
            android.util.Log.w("BangumiRepo", "getPugvSeasonDetail 成功: seasonId=${detail.seasonId}, title=${detail.title}, episodes=${detail.episodes?.size}")
            Result.success(detail)
        } else {
            Result.failure(Exception(response.message.ifBlank { "获取课程详情失败" }))
        }
    } catch (e: Exception) {
        android.util.Log.e("BangumiRepo", "getPugvSeasonDetail error: ${e.message}")
        Result.failure(e)
    }
}

suspend fun getBangumiMediaInfo(mediaId: Long): Result<BangumiMediaInfo> = ownedBangumiRequest(Dispatchers.IO, owned) {
    if (mediaId <= 0L) {
        return@ownedBangumiRequest Result.failure(IllegalArgumentException("mediaId 必须大于 0"))
    }
    runCatching {
        val response = api.getBangumiMediaInfo(mediaId)
        if (response.code != 0) {
            error("获取剧集基本信息失败: ${response.message}")
        }
        response.result?.media ?: error("剧集基本信息为空")
    }
}

suspend fun getSeasonSections(seasonId: Long): Result<BangumiSectionResult> = ownedBangumiRequest(Dispatchers.IO, owned) {
    if (seasonId <= 0L) {
        return@ownedBangumiRequest Result.failure(IllegalArgumentException("seasonId 必须大于 0"))
    }
    runCatching {
        val response = api.getSeasonSections(seasonId)
        if (response.code != 0) {
            error("获取剧集分集失败: ${response.message}")
        }
        response.result ?: error("剧集分集为空")
    }
}

suspend fun getSeasonDetailByMediaId(mediaId: Long): Result<BangumiDetail> {
    return getBangumiMediaInfo(mediaId).fold(
        onSuccess = { media ->
            if (media.seasonId <= 0L) {
                Result.failure(IllegalStateException("基本信息未返回 seasonId"))
            } else {
                getSeasonDetail(seasonId = media.seasonId)
            }
        },
        onFailure = { Result.failure(it) }
    )
}

suspend fun followBangumi(seasonId: Long, isCourse: Boolean = false): Result<Boolean> = ownedBangumiRequest(Dispatchers.IO, actionOwned) {
    try {
        val csrf = csrf() ?: return@ownedBangumiRequest Result.failure(Exception("未登录"))
        android.util.Log.w("BangumiRepo", "📌 追番/收藏请求: seasonId=$seasonId, isCourse=$isCourse, csrf=[Root credential bound]...")
        if (isCourse) {
            val pugvResponse = runCatching { api.addFavPugv(seasonId = seasonId, csrf = csrf) }.getOrNull()
            return@ownedBangumiRequest if (pugvResponse?.code == 0) {
                Result.success(true)
            } else {
                Result.failure(Exception(pugvResponse?.message?.ifBlank { "收藏课程失败" } ?: "收藏课程失败"))
            }
        }
        val response = api.followBangumi(seasonId = seasonId, csrf = csrf)
        android.util.Log.w("BangumiRepo", "📌 追番响应: code=${response.code}, message=${response.message}")
        if (response.code == 0) {
            Result.success(true)
        } else {
            // 如果常规追番失败，尝试课程收藏接口
            val pugvResponse = runCatching { api.addFavPugv(seasonId = seasonId, csrf = csrf) }.getOrNull()
            if (pugvResponse?.code == 0) {
                Result.success(true)
            } else {
                Result.failure(Exception("追番/收藏失败: ${response.message}"))
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("BangumiRepo", "followBangumi error: ${e.message}")
        Result.failure(e)
    }
}
}
