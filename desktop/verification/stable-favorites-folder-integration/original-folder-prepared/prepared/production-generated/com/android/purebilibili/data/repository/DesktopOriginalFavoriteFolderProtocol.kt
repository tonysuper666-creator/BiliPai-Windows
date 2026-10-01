package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Uses Root's existing owned API. No Retrofit, client, cookies, identity or data store. */
class DesktopOriginalFavoriteFolderProtocol(
    private val api:BilibiliApi,
    private val readMid:()->Long?,
    private val readCsrf:()->String?,
    private val assertOwned:()->Unit,
) {
    suspend fun getFavoriteFolders(aid: Long? = null): Result<List<com.android.purebilibili.data.model.response.FavFolder>> {
        assertOwned()
        return withContext(Dispatchers.IO) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            assertOwned()
            try {
                val mid = readMid() ?: return@withContext Result.failure(Exception("请先登录"))
                val response = api.getFavFolders(
                    mid = mid,
                    type = aid?.let { 2 },
                    rid = aid
                )
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                assertOwned()
                if (response.code == 0) {
                    Result.success(response.data?.list ?: emptyList())
                } else {
                    Result.failure(Exception(response.message))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                assertOwned()
                Result.failure(e)
            }
        }
    }

    suspend fun updateFavoriteFolders(
        aid: Long,
        addFolderIds: Set<Long>,
        removeFolderIds: Set<Long>
    ): Result<Boolean> {
        assertOwned()
        return withContext(Dispatchers.IO) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            assertOwned()
            try {
                val csrf = readCsrf() ?: ""
                if (csrf.isEmpty()) {
                    return@withContext Result.failure(Exception("请先登录"))
                }
                if (addFolderIds.isEmpty() && removeFolderIds.isEmpty()) {
                    return@withContext Result.success(true)
                }

                val addIds = addFolderIds.sorted().joinToString(",")
                val delIds = removeFolderIds.sorted().joinToString(",")
                val response = api.dealFavorite(rid = aid, addIds = addIds, delIds = delIds, csrf = csrf)

                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                assertOwned()
                if (response.code == 0) {
                    Result.success(true)
                } else {
                    Result.failure(Exception(response.message.ifEmpty { "操作失败: ${response.code}" }))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                assertOwned()
                Result.failure(e)
            }
        }
    }
}
