package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.MessageApi
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
internal class DesktopOriginalVideoShareMessages(
 private val api:MessageApi,
 private val csrf:()->String?,
 private val mid:()->Long?,
 private val deviceId:()->String,
) {
suspend fun sendTextMessage(
    receiverId: Long,
    content: String,
    receiverType: Int = 1
): Result<SendMessageData> = withContext(Dispatchers.IO) {
    try {

        sendMessage(
            receiverId = receiverId,
            receiverType = receiverType,
            msgType = 1,
            content = MessageSendPayloadFactory.buildTextContent(content)
        ).also { result ->
            result.onSuccess { data ->
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private suspend fun sendMessage(
    receiverId: Long,
    receiverType: Int,
    msgType: Int,
    content: String
): Result<SendMessageData> = withContext(Dispatchers.IO) {
    try {
        val csrf = csrf()
        if (csrf.isNullOrEmpty()) {
            return@withContext Result.failure(Exception("请先登录"))
        }

        val senderUid = mid()
        if (senderUid == null || senderUid <= 0) {
            return@withContext Result.failure(Exception("无法获取用户信息，请重新登录"))
        }

        val response = api.sendMsg(
            senderUid = senderUid,
            receiverId = receiverId,
            receiverType = receiverType,
            msgType = msgType,
            content = content,
            timestamp = System.currentTimeMillis() / 1000,
            devId = deviceId(),
            csrf = csrf,
            csrfToken = csrf
        )

        if (response.code == 0 && response.data != null) {
            Result.success(response.data)
        } else {
            val errorMsg = when (response.code) {
                -101 -> "请先登录"
                -400 -> "请求参数错误"
                21007 -> "消息过长，无法发送"
                21015 -> "需绑定手机号才能发送消息"
                21020 -> "发送频率过快，请稍后再试"
                21026 -> "不能给自己发消息"
                21046 -> "发送过于频繁，请24小时后再试"
                21047 -> "对方未关注你，最多发送1条消息"
                25003 -> "对方隐私设置限制，无法发送"
                25005 -> "已拉黑对方，请先解除拉黑"
                else -> response.message.ifEmpty { "发送失败 (${response.code})" }
            }
            Result.failure(Exception(errorMsg))
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Result.failure(e)
    }
}
}
