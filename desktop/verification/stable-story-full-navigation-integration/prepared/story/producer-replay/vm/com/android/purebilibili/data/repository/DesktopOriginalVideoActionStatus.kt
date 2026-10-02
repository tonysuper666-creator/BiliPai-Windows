// ORIGINAL app/src/main/java/com/android/purebilibili/data/repository/ActionRepository.kt
// LF-normalized SHA-256: d6a59bbf30b9c2058f352e299399713f9af13fd44262eeae6473e1447769087f
package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.WatchLaterItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun isWatchLaterAid(items: List<WatchLaterItem>, aid: Long): Boolean {
    return aid > 0L && items.any { it.aid == aid }
}

internal class DesktopOriginalVideoActionStatus(
    private val api: BilibiliApi,
    private val primarySessData: () -> String?,
) {
    suspend fun checkFavoriteStatus(aid: Long): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val response = api.checkFavoured(aid)
                if (response.code == 0) {
                    val isFavoured = response.data?.favoured ?: false
                    com.android.purebilibili.core.util.Logger.d("ActionRepository", " checkFavoriteStatus: aid=$aid, isFavoured=$isFavoured")
                    isFavoured
                } else {
                    false
                }
            } catch (e: Exception) {
                android.util.Log.e("ActionRepository", "checkFavoriteStatus failed", e)
                false
            }
        }
    }

    suspend fun checkLikeStatus(aid: Long): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val response = api.hasLiked(aid)
                if (response.code == 0) {
                    val isLiked = response.data == 1
                    com.android.purebilibili.core.util.Logger.d("ActionRepository", " checkLikeStatus: aid=$aid, isLiked=$isLiked")
                    isLiked
                } else {
                    false
                }
            } catch (e: Exception) {
                android.util.Log.e("ActionRepository", "checkLikeStatus failed", e)
                false
            }
        }
    }

    suspend fun checkDislikeStatus(aid: Long): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val response = api.getVideoRelation(aid = aid)
                if (response.code == 0) {
                    response.data?.dislike ?: false
                } else {
                    false
                }
            } catch (e: Exception) {
                android.util.Log.e("ActionRepository", "checkDislikeStatus failed", e)
                false
            }
        }
    }

    suspend fun checkCoinStatus(aid: Long): Int {
        return withContext(Dispatchers.IO) {
            try {
                val response = api.hasCoined(aid)
                if (response.code == 0) {
                    val coinCount = response.data?.multiply ?: 0
                    com.android.purebilibili.core.util.Logger.d("ActionRepository", " checkCoinStatus: aid=$aid, coinCount=$coinCount")
                    coinCount
                } else {
                    0
                }
            } catch (e: Exception) {
                android.util.Log.e("ActionRepository", "checkCoinStatus failed", e)
                0
            }
        }
    }

    suspend fun checkWatchLaterStatus(aid: Long): Boolean = withContext(Dispatchers.IO) {
        if (aid <= 0L || primarySessData().isNullOrEmpty()) return@withContext false
        runCatching {
            val response = api.getWatchLaterList()
            response.code == 0 && isWatchLaterAid(response.data?.list.orEmpty(), aid)
        }.getOrDefault(false)
    }
}
