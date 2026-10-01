package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.core.util.IdUtils
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
class DesktopOriginalFavoritePgc(private val environment: com.android.purebilibili.feature.list.DesktopFavoriteEnvironment) {
private val api=environment.bangumiApi
    suspend fun getMyFollowBangumi(
        type: Int = 1,  // 1=追番 2=追剧
        followStatus: Int? = null,
        page: Int = 1,
        pageSize: Int = 30,
        vmid: Long? = null
    ): Result<MyFollowBangumiData> = withContext(Dispatchers.IO) {
        try {
            val mid = vmid?.takeIf { it > 0L } ?: environment.currentMid() ?: return@withContext Result.failure(Exception("未登录"))
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
            android.util.Log.e("BangumiRepo", "getMyFollowBangumi error: ${e.message}")
            Result.failure(e)
        }
    }
    suspend fun unfollowBangumi(seasonId: Long, isCourse: Boolean = false): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val csrf = environment.csrf() ?: return@withContext Result.failure(Exception("未登录"))
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
            android.util.Log.e("BangumiRepo", "unfollowBangumi error: ${e.message}")
            Result.failure(e)
        }
    }
    suspend fun updateBangumiFollowStatus(
        seasonId: Long,
        status: Int
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val csrf = environment.csrf() ?: return@withContext Result.failure(Exception("未登录"))
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
            android.util.Log.e("BangumiRepo", "updateBangumiFollowStatus error: ${e.message}")
            Result.failure(e)
        }
    }
}
