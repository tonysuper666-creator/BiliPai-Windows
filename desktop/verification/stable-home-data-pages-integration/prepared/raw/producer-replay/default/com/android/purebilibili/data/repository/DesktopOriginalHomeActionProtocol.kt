package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.DesktopFeedSettings
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.ui.DesktopHomeProtocolEnvironment
import kotlinx.coroutines.*

import com.android.purebilibili.core.refresh.WatchLaterRefreshBus

internal class DesktopOriginalHomeActionProtocol(private val environment:DesktopHomeProtocolEnvironment) {
    private val api get()=environment.api
    suspend fun submitRecommendationFeedback(
        metadata: RecommendationFeedbackMetadata,
        reason: RecommendationFeedbackReason
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val accessToken = environment.accessToken()
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(Exception("缺少移动端登录凭证"))
        val request = buildRecommendationFeedbackRequest(metadata, reason)
            ?: return@withContext Result.failure(Exception("当前推荐不支持服务器同步"))
        runCatching {
            val params = buildRecommendationFeedbackParams(
                request = request,
                accessToken = accessToken,
                timestamp = AppSignUtils.getTimestamp()
            )
            val response = api.submitMobileFeedDislike(AppSignUtils.signForTvLogin(params))
            if (response.code != 0) {
                throw Exception(response.message.ifBlank { "反馈失败: ${response.code}" })
            }
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
                    api.addToWatchLater(aid = aid, csrf = csrf)
                } else {
                    api.deleteFromWatchLater(aid = aid, csrf = csrf)
                }

                com.android.purebilibili.core.util.Logger.d("ActionRepository", " toggleWatchLater: aid=$aid, add=$add, code=${response.code}")

                when (response.code) {
                    0 -> {
                        WatchLaterRefreshBus.notifyChanged()
                        Result.success(add)
                    }
                    90001 -> Result.failure(Exception("稍后再看列表已满"))
                    90003 -> Result.failure(Exception("视频已被删除"))
                    else -> Result.failure(Exception(response.message.ifEmpty { "操作失败: ${response.code}" }))
                }
            } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {
                android.util.Log.e("ActionRepository", "toggleWatchLater failed", e)
                Result.failure(e)
            }
        }
    }
}
