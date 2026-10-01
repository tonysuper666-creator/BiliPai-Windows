package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.DesktopFeedSettings
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.ui.DesktopHomeProtocolEnvironment
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

internal class DesktopOriginalHomeLiveProtocol(private val api:BilibiliApi) {
    suspend fun getLiveRooms(page: Int = 1): Result<List<LiveRoom>> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getLiveList(page = page)
            // 使用 getAllRooms() 兼容新旧 API 格式
            val list = resp.data?.getAllRooms() ?: emptyList()
            list.firstOrNull()?.let {
                com.android.purebilibili.core.util.Logger.d("LiveRepo", "🟢 Popular Live: roomid=${it.roomid}, title=${it.title}, online=${it.online}")
            }
            com.android.purebilibili.core.util.Logger.d("LiveRepo", "🔴 getLiveRooms page=$page, count=${list.size}")
            Result.success(list)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {
            com.android.purebilibili.core.util.Logger.e("LiveRepo", " getLiveRooms failed", e)
            // Windows boundary does not print raw URL/response exceptions.
            Result.failure(e)
        }
    }

    suspend fun getFollowedLive(page: Int = 1): Result<List<LiveRoom>> = getFollowedLivePage(page).map { it.items }

    suspend fun getFollowedLivePage(
        page: Int = 1,
        pageSize: Int = 50
    ): Result<LivePagedResult<LiveRoom>> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getFollowedLive(page = page, pageSize = pageSize)
            val followedRooms = resp.data?.list
                ?.filter { it.liveStatus == 1 }
                ?: emptyList()

            val liveRooms = followedRooms.map { it.toLiveRoom() }
            val pageInfo = resp.data?.pageinfo
            val hasMore = when {
                pageInfo != null && pageInfo.total_page > 0 -> page < pageInfo.total_page
                followedRooms.size >= pageSize -> true
                else -> false
            }

            Result.success(
                LivePagedResult(
                    items = liveRooms.distinctBy { it.roomid },
                    hasMore = hasMore,
                    nextPage = page + 1,
                    totalCount = resp.data?.livingNum ?: liveRooms.size
                )
            )
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {
            // Windows boundary does not print raw URL/response exceptions.
            Result.failure(e)
        }
    }
}
