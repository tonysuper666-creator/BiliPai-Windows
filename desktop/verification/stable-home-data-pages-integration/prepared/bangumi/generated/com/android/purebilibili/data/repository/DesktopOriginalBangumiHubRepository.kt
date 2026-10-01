package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
internal class DesktopOriginalBangumiHubRepository(private val api:BangumiApi, private val ownedNavApi:BilibiliApi, private val ownedSearchApi:SearchApi, private val csrf:()->String?,private val mid:()->Long?) {
    suspend fun getTimeline(
        type: Int = 1,
        before: Int = 3,
        after: Int = 7,
    ): Result<List<TimelineDay>> = withContext(Dispatchers.IO) {
        try {
            val response = api.getTimeline(
                types = type,
                before = before.coerceIn(0, 7),
                after = after.coerceIn(0, 7),
            )
            if (response.code == 0 && response.result != null) {
                Result.success(response.result)
            } else {
                Result.failure(Exception("获取时间表失败: ${response.message}"))
            }
        } catch (e: Exception) {
            // Existing optional protocol failure is returned through Result; no credential-bearing platform log.
            Result.failure(e)
        }
    }

    suspend fun unfollowBangumi(seasonId: Long, isCourse: Boolean = false): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val csrf = csrf() ?: return@withContext Result.failure(Exception("未登录"))
            if (isCourse) {
                val pugvResponse = runCatching { api.delFavPugv(seasonId = seasonId, csrf = csrf) }.getOrNull()
                return@withContext if (pugvResponse?.code == 0) {
                    Result.success(true)
                } else {
                    Result.failure(Exception(pugvResponse?.message?.ifBlank { "取消收藏失败" } ?: "取消收藏失败"))
                }
            }
            val response = api.unfollowBangumi(seasonId = seasonId, csrf = csrf)
            if (response.code == 0) {
                Result.success(true)
            } else {
                // 如果常规取消追番失败，尝试课程取消收藏接口
                val pugvResponse = runCatching { api.delFavPugv(seasonId = seasonId, csrf = csrf) }.getOrNull()
                if (pugvResponse?.code == 0) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("取消追番/收藏失败: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            // Existing optional protocol failure is returned through Result; no credential-bearing platform log.
            Result.failure(e)
        }
    }

    suspend fun updateBangumiFollowStatus(
        seasonId: Long,
        status: Int
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val csrf = csrf() ?: return@withContext Result.failure(Exception("未登录"))
            val response = api.updateBangumiFollowStatus(
                seasonId = seasonId,
                status = status,
                csrf = csrf
            )
            if (response.code == 0) {
                Result.success(true)
            } else {
                Result.failure(Exception("更新追番状态失败: ${response.message}"))
            }
        } catch (e: Exception) {
            // Existing optional protocol failure is returned through Result; no credential-bearing platform log.
            Result.failure(e)
        }
    }

    suspend fun updateBangumiFollowStatuses(
        seasonIds: Collection<Long>,
        status: Int,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val normalizedIds = seasonIds.asSequence().filter { it > 0L }.distinct().toList()
        if (normalizedIds.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("请选择要更新的番剧"))
        }
        try {
            val csrf = csrf() ?: return@withContext Result.failure(Exception("未登录"))
            val response = api.updateBangumiFollowStatusBatch(
                seasonIds = normalizedIds.joinToString(","),
                status = status,
                csrf = csrf,
            )
            if (response.code == 0) {
                Result.success(true)
            } else {
                Result.failure(Exception("批量更新追番状态失败: ${response.message}"))
            }
        } catch (e: Exception) {
            // Existing optional protocol failure is returned through Result; no credential-bearing platform log.
            Result.failure(e)
        }
    }

    suspend fun getBangumiIndexConditions(
        seasonType: Int? = null,
        indexType: Int? = null,
        type: Int = 0,
    ): Result<BangumiIndexConditionData> = withContext(Dispatchers.IO) {
        try {
            val response = api.getBangumiIndexCondition(
                seasonType = seasonType,
                type = type,
                indexType = indexType,
            )
            if (response.code == 0 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception("获取番剧索引条件失败: ${response.message}"))
            }
        } catch (e: Exception) {
            // Existing optional protocol failure is returned through Result; no credential-bearing platform log.
            Result.failure(e)
        }
    }

    suspend fun getBangumiIndexPage(
        seasonType: Int? = null,
        indexType: Int? = null,
        type: Int = 0,
        page: Int = 1,
        pageSize: Int = 21,
        params: Map<String, String> = emptyMap(),
    ): Result<BangumiIndexData> = withContext(Dispatchers.IO) {
        try {
            val query = params.toMutableMap().apply {
                put("type", type.toString())
                put("page", page.coerceAtLeast(1).toString())
                put("pagesize", pageSize.coerceAtLeast(1).toString())
                seasonType?.let {
                    put("season_type", it.toString())
                    putIfAbsent("st", it.toString())
                }
                indexType?.let { put("index_type", it.toString()) }
            }
            val response = api.getBangumiIndexResult(query)
            if (response.code == 0 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception("获取番剧索引失败: ${response.message}"))
            }
        } catch (e: Exception) {
            // Existing optional protocol failure is returned through Result; no credential-bearing platform log.
            Result.failure(e)
        }
    }

    suspend fun searchBangumi(
        keyword: String,
        seasonType: Int = BangumiType.ANIME.value,
        page: Int = 1,
        pageSize: Int = 20
    ): Result<BangumiSearchData> = withContext(Dispatchers.IO) {
        try {
            val navApi = ownedNavApi
            val searchApi = ownedSearchApi
            val searchType = resolveBangumiSearchTypeForSeasonType(seasonType)
            
            // 获取 WBI 密钥
            val navResp = navApi.getNavInfo()
            val wbiImg = navResp.data?.wbi_img
            val imgKey = wbiImg?.img_url?.substringAfterLast("/")?.substringBefore(".") ?: ""
            val subKey = wbiImg?.sub_url?.substringAfterLast("/")?.substringBefore(".") ?: ""
            
            val params = mutableMapOf(
                "keyword" to keyword,
                "search_type" to searchType.value,
                "page" to page.toString(),
                "pagesize" to pageSize.toString()
            )
            
            // WBI 签名
            val signedParams = if (imgKey.isNotEmpty()) WbiUtils.sign(params, imgKey, subKey) else params
            val response = if (searchType == SearchType.MEDIA_FT) {
                searchApi.searchMediaFt(signedParams)
            } else {
                searchApi.searchBangumi(signedParams)
            }
            
            if (response.code == 0 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception("搜索番剧失败: ${response.message}"))
            }
        } catch (e: Exception) {
            // Existing optional protocol failure is returned through Result; no credential-bearing platform log.
            Result.failure(e)
        }
    }

    suspend fun getMyFollowBangumi(
        type: Int = 1,  // 1=追番 2=追剧
        followStatus: Int? = null,
        page: Int = 1,
        pageSize: Int = 30,
        vmid: Long? = null
    ): Result<MyFollowBangumiData> = withContext(Dispatchers.IO) {
        try {
            val mid = vmid?.takeIf { it > 0L } ?: mid() ?: return@withContext Result.failure(Exception("未登录"))
            val response = api.getMyFollowBangumi(
                vmid = mid,
                type = type,
                followStatus = followStatus,
                pn = page,
                ps = pageSize
            )
            if (response.code == 0 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception("获取追番列表失败: ${response.message}"))
            }
        } catch (e: Exception) {
            // Existing optional protocol failure is returned through Result; no credential-bearing platform log.
            Result.failure(e)
        }
    }
}
