package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.AppSignUtils
import com.android.purebilibili.data.model.response.FollowingUser
import com.android.purebilibili.data.model.response.RecommendationFeedbackMetadata
import com.android.purebilibili.data.model.response.RecommendationFeedbackReason
import com.android.purebilibili.data.model.response.RecommendationFeedbackType
import com.android.purebilibili.data.model.response.WatchLaterItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
class DesktopOriginalFavoriteActions(private val environment: com.android.purebilibili.feature.list.DesktopFavoriteEnvironment) {
    private val api=environment.api
    suspend fun createFavFolder(title: String, intro: String = "", isPrivate: Boolean = false): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                if (title.isBlank()) return@withContext Result.failure(Exception("标题不能为空"))
                val privacy = if (isPrivate) 1 else 0
                val csrf = environment.csrf() ?: return@withContext Result.failure(Exception("未登录"))
                
                val response = environment.api.createFavFolder(
                    title = title,
                    intro = intro,
                    privacy = privacy,
                    csrf = csrf
                )
                
                if (response.code == 0) {
                    Result.success(true)
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    suspend fun favoriteVideo(aid: Long, favorite: Boolean, folderId: Long? = null): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val csrf = environment.csrf() ?: ""
                if (csrf.isEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                
                // 如果没有指定收藏夹，需要先获取默认收藏夹
                val targetFolderId = folderId ?: getDefaultFolderId()
                if (targetFolderId == null) {
                    return@withContext Result.failure(Exception("无法获取收藏夹"))
                }

                val response = if (favorite) {
                    environment.api.dealFavorite(rid = aid, addIds = targetFolderId.toString(), delIds = "", csrf = csrf)
                } else {
                    environment.api.dealFavorite(rid = aid, addIds = "", delIds = targetFolderId.toString(), csrf = csrf)
                }
                
                if (response.code == 0) {
                    Result.success(favorite)
                } else {
                    Result.failure(Exception(response.message.ifEmpty { "操作失败: ${response.code}" }))
                }
            } catch (e: Exception) {

                Result.failure(e)
            }
        }
    }
    private suspend fun getDefaultFolderId(): Long? {
        return try {
            val mid = environment.currentMid() ?: return null
            val response = environment.api.getFavFolders(mid)
            response.data?.list?.firstOrNull()?.id
        } catch (e: Exception) {

            null
        }
    }
    suspend fun toggleWatchLater(aid: Long, add: Boolean): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val csrf = environment.csrf() ?: ""
                if (csrf.isEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                
                val response = if (add) {
                    environment.api.addToWatchLater(aid = aid, csrf = csrf)
                } else {
                    environment.api.deleteFromWatchLater(aid = aid, csrf = csrf)
                }
                

                
                when (response.code) {
                    0 -> {
                        environment.watchLaterChanged()
                        Result.success(add)
                    }
                    90001 -> Result.failure(Exception("稍后再看列表已满"))
                    90003 -> Result.failure(Exception("视频已被删除"))
                    else -> Result.failure(Exception(response.message.ifEmpty { "操作失败: ${response.code}" }))
                }
            } catch (e: Exception) {

                Result.failure(e)
            }
        }
    }
}
