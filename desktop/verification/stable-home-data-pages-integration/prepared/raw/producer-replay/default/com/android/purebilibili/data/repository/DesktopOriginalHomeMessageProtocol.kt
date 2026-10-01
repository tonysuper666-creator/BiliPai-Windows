package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.DesktopFeedSettings
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.ui.DesktopHomeProtocolEnvironment
import kotlinx.coroutines.*

internal class DesktopOriginalHomeMessageProtocol(private val api:MessageApi) {
    suspend fun getUnreadCount(): Result<MessageUnreadData> = withContext(Dispatchers.IO) {
        try {
            val response = api.getUnreadCount()

            if (response.code == 0 && response.data != null) {
                Result.success(response.data)
            } else {
                val errorMsg = when (response.code) {
                    -101 -> "请先登录"
                    else -> response.message.ifEmpty { "获取未读数失败 (${response.code})" }
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {
            android.util.Log.e("MessageRepo", "getUnreadCount exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getFeedUnread(): Result<MessageFeedUnreadData> = withContext(Dispatchers.IO) {
        try {
            val response = api.getFeedUnread()
            if (response.code == 0 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "获取消息中心未读数失败 (${response.code})" }))
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {
            android.util.Log.e("MessageRepo", "getFeedUnread exception: ${e.message}", e)
            Result.failure(e)
        }
    }
}
