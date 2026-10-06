package com.android.purebilibili.data.repository

import com.android.purebilibili.core.network.DynamicRepostContentItem
import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.store.TokenManager
import com.android.purebilibili.data.model.response.DynamicCreateFeedContent
import com.android.purebilibili.data.model.response.DynamicCreateFeedReq
import com.android.purebilibili.data.model.response.DynamicCreateFeedRequest
import com.android.purebilibili.data.model.response.DynamicVideoRepostResource
import com.android.purebilibili.data.model.response.DynamicVideoRepostSource
import com.android.purebilibili.data.model.response.resolveCreatedDynamicId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Publishes a native video dynamic through the web video-repost protocol (scene 5). */
object VideoDynamicShareRepository {
    suspend fun share(bvid: String, text: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val csrf = TokenManager.csrfCache.orEmpty()
            check(csrf.isNotBlank()) { "请先登录后分享到动态" }
            require(bvid.isNotBlank()) { "视频信息不完整" }
            require(text.length <= 2000) { "分享文字不能超过 2000 字" }
            val video = NetworkModule.api.getVideoInfo(bvid)
            check(video.code == 0) { video.message.ifBlank { "获取视频信息失败" } }
            val aid = video.data?.aid?.takeIf { it > 0L } ?: error("未获取到视频编号")
            val request = DynamicCreateFeedRequest(
                dyn_req = DynamicCreateFeedReq(
                    content = DynamicCreateFeedContent(
                        contents = listOf(DynamicRepostContentItem(
                            raw_text = text.trim().ifBlank { "转发视频" },
                            type = 1,
                            biz_id = "",
                        )),
                    ),
                    scene = 5,
                    upload_id = "0_${System.currentTimeMillis() / 1000}_${Random.nextInt(1000, 10000)}",
                ),
                web_repost_src = DynamicVideoRepostSource(DynamicVideoRepostResource(rid = aid, dyn_type = 8)),
            )
            val response = NetworkModule.dynamicApi.createFeedDynamic(csrf = csrf, body = request)
            check(response.code == 0) { response.message.ifBlank { "分享到动态失败" } }
            Result.success(resolveCreatedDynamicId(response.data).ifBlank { "ok" })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
}
