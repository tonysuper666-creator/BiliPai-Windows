package com.bilipai.desktop.ui
import com.android.purebilibili.core.network.BangumiApi
import com.android.purebilibili.data.model.response.MyFollowBangumiData
import kotlinx.coroutines.*
internal class DesktopOriginalSpaceBangumiRequests(private val api:BangumiApi,private val environment:DesktopOriginalSpaceEnvironment) {
    suspend fun getMyFollowBangumi(
        type: Int = 1,  // 1=追番 2=追剧
        followStatus: Int? = null,
        page: Int = 1,
        pageSize: Int = 30,
        vmid: Long? = null
    ): Result<MyFollowBangumiData> = withContext(Dispatchers.IO) {
        try {
            val mid = vmid?.takeIf { it > 0L } ?: environment.accountMid ?: return@withContext Result.failure(Exception("未登录"))
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
        } catch (cancelled:CancellationException) { throw cancelled
        } catch (e: Exception) {
            Unit
            Result.failure(e)
        }
    }
}
