package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
internal class DesktopOriginalVideoCreatorCard(private val api:BilibiliApi,private val assertOwned:()->Unit) {
    private val creatorCardStatsCache = ConcurrentHashMap<Long, CreatorCardStats>()
    suspend fun getCreatorCardStats(mid: Long): Result<CreatorCardStats> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive(); assertOwned()
        if (mid <= 0L) return@withContext Result.failure(IllegalArgumentException("Invalid mid"))
        creatorCardStatsCache[mid]?.let { return@withContext Result.success(it) }
        try {
            val response = api.getUserCard(mid = mid, photo = false)
            val data = response.data
            currentCoroutineContext().ensureActive(); assertOwned()
            if (response.code == 0 && data != null) {
                val stats = CreatorCardStats(
                    followerCount = data.follower.coerceAtLeast(0),
                    videoCount = data.archive_count.coerceAtLeast(0),
                    vipStatus = data.card?.vip?.status ?: 0,
                    vipType = data.card?.vip?.type ?: 0,
                    officialType = data.card?.Official?.type ?: -1,
                    pendantImage = data.card?.pendant?.image.orEmpty(),
                )
                creatorCardStatsCache[mid] = stats
                Result.success(stats)
            } else {
                Result.failure(Exception(response.message.ifBlank { "UP主信息加载失败(${response.code})" }))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            assertOwned()
            Result.failure(e)
        }
    }
}
