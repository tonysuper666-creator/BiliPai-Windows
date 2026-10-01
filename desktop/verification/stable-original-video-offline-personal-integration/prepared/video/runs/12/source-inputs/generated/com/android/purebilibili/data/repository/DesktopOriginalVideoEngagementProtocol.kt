package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.core.refresh.WatchLaterRefreshBus
import com.bilipai.desktop.ui.DesktopOriginalVideoInteractionLog as Logger
import kotlinx.coroutines.*

/** Original action bodies on the already-owned Root API; no network/session/store is built. */
internal class DesktopOriginalVideoEngagementProtocol(
    private val api: BilibiliApi,
    private val readCsrf: () -> String?,
    private val readMid: () -> Long?,
    private val readSessData: () -> String?,
    private val readAccessToken: () -> String?,
    private val assertOwned: () -> Unit,
    private val confirmFollow: (FollowStateChange) -> Unit,
    private val folderProtocol: DesktopOriginalFavoriteFolderProtocol,
) {
    suspend fun getFavoriteFolders(aid:Long?=null):Result<List<FavFolder>> = folderProtocol.getFavoriteFolders(aid)
    suspend fun updateFavoriteFolders(aid:Long,addFolderIds:Set<Long>,removeFolderIds:Set<Long>):Result<Boolean> = folderProtocol.updateFavoriteFolders(aid,addFolderIds,removeFolderIds)
    data class TripleResult(
        val likeSuccess: Boolean,
        val coinSuccess: Boolean,
        val coinMessage: String?,
        val favoriteSuccess: Boolean
    )


    suspend fun followUser(mid: Long, follow: Boolean): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive(); assertOwned()
            try {
                val csrf = readCsrf() ?: ""
                Logger.d("ActionRepository", " followUser: mid=$mid, follow=$follow, csrf.length=${csrf.length}")
                if (csrf.isEmpty()) {
                    Logger.e("ActionRepository", " CSRF token is empty!")
                    return@withContext Result.failure(Exception("请先登录"))
                }
                
                val act = if (follow) 1 else 2
                Logger.d("ActionRepository", " Calling modifyRelation...")
                val response = api.modifyRelation(fid = mid, act = act, csrf = csrf)
                Logger.d("ActionRepository", " Response: code=${response.code}, message=${response.message}")
                
                currentCoroutineContext().ensureActive(); assertOwned()
                if (response.code == 0) {
                    confirmFollow(FollowStateChange(mid = mid, isFollowing = follow))
                    Result.success(follow)
                } else {
                    Result.failure(Exception(response.message.ifEmpty { "操作失败: ${response.code}" }))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                assertOwned()
                Logger.e("ActionRepository", "followUser failed", e)
                Result.failure(e)
            }
        }
    }

    suspend fun favoriteVideo(aid: Long, favorite: Boolean, folderId: Long? = null): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive(); assertOwned()
            try {
                val csrf = readCsrf() ?: ""
                if (csrf.isEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                
                // 如果没有指定收藏夹，需要先获取默认收藏夹
                val targetFolderId = folderId ?: getDefaultFolderId()
                if (targetFolderId == null) {
                    return@withContext Result.failure(Exception("无法获取收藏夹"))
                }

                val response = if (favorite) {
                    api.dealFavorite(rid = aid, addIds = targetFolderId.toString(), delIds = "", csrf = csrf)
                } else {
                    api.dealFavorite(rid = aid, addIds = "", delIds = targetFolderId.toString(), csrf = csrf)
                }
                
                currentCoroutineContext().ensureActive(); assertOwned()
                if (response.code == 0) {
                    Result.success(favorite)
                } else {
                    Result.failure(Exception(response.message.ifEmpty { "操作失败: ${response.code}" }))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                assertOwned()
                Logger.e("ActionRepository", "favoriteVideo failed", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun getDefaultFolderId(): Long? {
        return try {
            val mid = readMid() ?: return null
            currentCoroutineContext().ensureActive(); assertOwned()
            val response = api.getFavFolders(mid)
            currentCoroutineContext().ensureActive(); assertOwned()
            response.data?.list?.firstOrNull()?.id
        } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                assertOwned()
            Logger.e("ActionRepository", "getDefaultFolderId failed", e)
            null
        }
    }

    suspend fun likeVideo(aid: Long, like: Boolean): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive(); assertOwned()
            try {
                val csrf = readCsrf() ?: ""
                if (csrf.isEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                
                val likeAction = if (like) 1 else 2
                val response = api.likeVideo(aid = aid, like = likeAction, csrf = csrf)
                Logger.d("ActionRepository", " likeVideo: aid=$aid, like=$like, code=${response.code}")
                
                currentCoroutineContext().ensureActive(); assertOwned()
                if (response.code == 0) {
                    Result.success(like)
                } else {
                    Result.failure(Exception(response.message.ifEmpty { "点赞失败: ${response.code}" }))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                assertOwned()
                Logger.e("ActionRepository", "likeVideo failed", e)
                Result.failure(e)
            }
        }
    }

    suspend fun dislikeVideo(aid: Long, dislike: Boolean): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive(); assertOwned()
            try {
                if (readSessData().isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }

                val csrf = readCsrf() ?: ""
                if (csrf.isEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                val dislikeAction = if (dislike) 0 else 1
                val response = api.dislikeVideo(
                    aid = aid,
                    dislike = dislikeAction,
                    csrf = csrf,
                    accessKey = readAccessToken()
                )
                Logger.d("ActionRepository", " dislikeVideo: aid=$aid, dislike=$dislike, code=${response.code}")

                currentCoroutineContext().ensureActive(); assertOwned()
                when {
                    response.code == 0 || response.code == 65007 || response.code == 65005 ->
                        Result.success(dislike)
                    response.code == -101 -> Result.failure(
                        Exception("视频点踩需要 APP 鉴权，请先在登录页完成高画质（TV）鉴权登录")
                    )
                    else -> Result.failure(Exception(response.message.ifEmpty { "点踩失败: ${response.code}" }))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                assertOwned()
                Logger.e("ActionRepository", "dislikeVideo failed", e)
                Result.failure(e)
            }
        }
    }

    suspend fun coinVideo(aid: Long, count: Int, alsoLike: Boolean): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive(); assertOwned()
            try {
                val csrf = readCsrf() ?: ""
                if (csrf.isEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                
                val selectLike = if (alsoLike) 1 else 0
                val response = api.coinVideo(aid = aid, multiply = count, selectLike = selectLike, csrf = csrf)
                Logger.d("ActionRepository", " coinVideo: aid=$aid, count=$count, code=${response.code}")
                
                currentCoroutineContext().ensureActive(); assertOwned()
                when (response.code) {
                    0 -> Result.success(true)
                    34004 -> Result.failure(Exception("操作太频繁，请稍后重试"))
                    34005 -> Result.failure(Exception("已投满2个硬币"))
                    -104 -> Result.failure(Exception("硬币余额不足"))
                    else -> Result.failure(Exception(response.message.ifEmpty { "投币失败: ${response.code}" }))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                assertOwned()
                Logger.e("ActionRepository", "coinVideo failed", e)
                Result.failure(e)
            }
        }
    }

    suspend fun tripleAction(aid: Long): Result<TripleResult> {
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive(); assertOwned()
            val csrf = readCsrf() ?: ""
            if (csrf.isEmpty()) {
                return@withContext Result.failure(Exception("请先登录"))
            }
            
            // 1. 点赞
            val likeResult = likeVideo(aid, true)
            val likeSuccess = likeResult.isSuccess
            
            // 2. 投币 (2个，同时点赞)
            val coinResult = coinVideo(aid, 2, true)
            val coinSuccess = coinResult.isSuccess
            val coinMessage = coinResult.exceptionOrNull()?.message
            
            // 3. 收藏
            val favoriteResult = favoriteVideo(aid, true)
            val favoriteSuccess = favoriteResult.isSuccess
            
            Logger.d("ActionRepository", " tripleAction: like=$likeSuccess, coin=$coinSuccess, fav=$favoriteSuccess")
            
            Result.success(TripleResult(
                likeSuccess = likeSuccess,
                coinSuccess = coinSuccess,
                coinMessage = coinMessage,
                favoriteSuccess = favoriteSuccess
            ))
        }
    }

    suspend fun toggleWatchLater(aid: Long, add: Boolean): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive(); assertOwned()
            try {
                val csrf = readCsrf() ?: ""
                if (csrf.isEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                
                val response = if (add) {
                    api.addToWatchLater(aid = aid, csrf = csrf)
                } else {
                    api.deleteFromWatchLater(aid = aid, csrf = csrf)
                }
                
                Logger.d("ActionRepository", " toggleWatchLater: aid=$aid, add=$add, code=${response.code}")
                
                currentCoroutineContext().ensureActive(); assertOwned()
                when (response.code) {
                    0 -> {
                        WatchLaterRefreshBus.notifyChanged()
                        Result.success(add)
                    }
                    90001 -> Result.failure(Exception("稍后再看列表已满"))
                    90003 -> Result.failure(Exception("视频已被删除"))
                    else -> Result.failure(Exception(response.message.ifEmpty { "操作失败: ${response.code}" }))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                assertOwned()
                Logger.e("ActionRepository", "toggleWatchLater failed", e)
                Result.failure(e)
            }
        }
    }

    suspend fun checkLikeStatus(aid: Long): Boolean {
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive(); assertOwned()
            try {
                val response = api.hasLiked(aid)
                currentCoroutineContext().ensureActive(); assertOwned()
                if (response.code == 0) {
                    val isLiked = response.data == 1
                    Logger.d("ActionRepository", " checkLikeStatus: aid=$aid, isLiked=$isLiked")
                    isLiked
                } else {
                    false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                assertOwned()
                Logger.e("ActionRepository", "checkLikeStatus failed", e)
                false
            }
        }
    }

    suspend fun checkFavoriteStatus(aid: Long): Boolean {
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive(); assertOwned()
            try {
                val response = api.checkFavoured(aid)
                currentCoroutineContext().ensureActive(); assertOwned()
                if (response.code == 0) {
                    val isFavoured = response.data?.favoured ?: false
                    Logger.d("ActionRepository", " checkFavoriteStatus: aid=$aid, isFavoured=$isFavoured")
                    isFavoured
                } else {
                    false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                assertOwned()
                Logger.e("ActionRepository", "checkFavoriteStatus failed", e)
                false
            }
        }
    }

    suspend fun checkFollowStatus(mid: Long): Boolean {
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive(); assertOwned()
            try {
                val response = api.getRelation(mid)
                currentCoroutineContext().ensureActive(); assertOwned()
                if (response.code == 0) {
                    val isFollowing = response.data?.isFollowing ?: false
                    Logger.d("ActionRepository", " checkFollowStatus: mid=$mid, isFollowing=$isFollowing")
                    isFollowing
                } else {
                    false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                assertOwned()
                Logger.e("ActionRepository", "checkFollowStatus failed", e)
                false
            }
        }
    }

    suspend fun checkCoinStatus(aid: Long): Int {
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive(); assertOwned()
            try {
                val response = api.hasCoined(aid)
                currentCoroutineContext().ensureActive(); assertOwned()
                if (response.code == 0) {
                    val coinCount = response.data?.multiply ?: 0
                    Logger.d("ActionRepository", " checkCoinStatus: aid=$aid, coinCount=$coinCount")
                    coinCount
                } else {
                    0
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                assertOwned()
                Logger.e("ActionRepository", "checkCoinStatus failed", e)
                0
            }
        }
    }
}
