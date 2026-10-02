// 私信数据仓库
package com.android.purebilibili.data.repository

import com.android.purebilibili.core.network.MessageApi
import com.android.purebilibili.core.network.BilibiliApi
import com.bilipai.desktop.ui.DesktopMessagePageAdmission

import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * 私信相关数据仓库
 * 负责处理会话列表、私信消息的获取和发送
 */
data class MessageSessionControlInfo(
    val isLimit: Boolean? = null,
    val reportLimit: Boolean? = null,
    val isDnd: Boolean? = null,
    val pushMuted: Boolean? = null,
    val showPushSetting: Boolean = false,
    val followStatus: Int? = null,
    val isIntercept: Boolean? = null
)

internal class DesktopOriginalMessageRepository(private val api: MessageApi, private val bilibiliApi: BilibiliApi, private val owner: DesktopMessagePageAdmission, private val deviceId: () -> String) {
    
    // 设备ID缓存 (用于发送私信)

    
    /**
     * 获取或生成设备ID (UUID v4格式)
     */
    private fun getDeviceId(): String = deviceId()

    private fun requireCsrf(): Result<String> {
        val csrf = owner.csrf()
        return if (csrf.isNullOrEmpty()) {
            Result.failure(Exception("请先登录"))
        } else {
            owner.success(csrf)
        }
    }

    private suspend fun sendMessage(
        receiverId: Long,
        receiverType: Int,
        msgType: Int,
        content: String
    ): Result<SendMessageData> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val csrf = owner.csrf()
            if (csrf.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("请先登录"))
            }

            val senderUid = owner.mid
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
                devId = getDeviceId(),
                csrf = csrf,
                csrfToken = csrf
            )

            if (response.code == 0 && response.data != null) {
                owner.success(response.data)
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
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }
    
    /**
     * 获取未读私信数量
     */
    suspend fun getUnreadCount(): Result<MessageUnreadData> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val response = api.getUnreadCount()
            
            if (response.code == 0 && response.data != null) {
                owner.success(response.data)
            } else {
                val errorMsg = when (response.code) {
                    -101 -> "请先登录"
                    else -> response.message.ifEmpty { "获取未读数失败 (${response.code})" }
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }

    suspend fun getFeedUnread(): Result<MessageFeedUnreadData> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val response = api.getFeedUnread()
            if (response.code == 0 && response.data != null) {
                owner.success(response.data)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "获取消息中心未读数失败 (${response.code})" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }

    suspend fun getReplyFeed(cursor: Long? = null, cursorTime: Long? = null): Result<MessageFeedReplyData> =
        withContext(Dispatchers.IO) {
        owner.assertCurrent()
            try {
                val response = api.getReplyFeed(cursor = cursor, cursorTime = cursorTime)
                if (response.code == 0 && response.data != null) {
                    owner.success(response.data)
                } else {
                    Result.failure(Exception(response.message.ifEmpty { "获取回复消息失败 (${response.code})" }))
                }
            } catch (e: Exception) {
            if (e is CancellationException) throw e

                Result.failure(e)
            }
        }

    suspend fun getAtFeed(cursor: Long? = null, cursorTime: Long? = null): Result<MessageFeedAtData> =
        withContext(Dispatchers.IO) {
        owner.assertCurrent()
            try {
                val response = api.getAtFeed(cursor = cursor, cursorTime = cursorTime)
                if (response.code == 0 && response.data != null) {
                    owner.success(response.data)
                } else {
                    Result.failure(Exception(response.message.ifEmpty { "获取@我消息失败 (${response.code})" }))
                }
            } catch (e: Exception) {
            if (e is CancellationException) throw e

                Result.failure(e)
            }
        }

    suspend fun getLikeFeed(cursor: Long? = null, cursorTime: Long? = null): Result<MessageFeedLikeData> =
        withContext(Dispatchers.IO) {
        owner.assertCurrent()
            try {
                val response = api.getLikeFeed(cursor = cursor, cursorTime = cursorTime)
                if (response.code == 0 && response.data != null) {
                    owner.success(response.data)
                } else {
                    Result.failure(Exception(response.message.ifEmpty { "获取点赞消息失败 (${response.code})" }))
                }
            } catch (e: Exception) {
            if (e is CancellationException) throw e

                Result.failure(e)
            }
        }

    suspend fun getSystemNotices(cursor: Long? = null): Result<List<SystemNoticeItem>> =
        withContext(Dispatchers.IO) {
        owner.assertCurrent()
            try {
                val response = api.getSystemNotices(cursor = cursor)
                if (response.code == 0) {
                    owner.success(response.data ?: emptyList())
                } else {
                    Result.failure(Exception(response.message.ifEmpty { "获取系统通知失败 (${response.code})" }))
                }
            } catch (e: Exception) {
            if (e is CancellationException) throw e

                Result.failure(e)
            }
        }

    suspend fun updateSystemNoticeCursor(cursor: Long): Result<Unit> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val csrf = owner.csrf()
            if (csrf.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("请先登录"))
            }

            val response = api.updateSystemNoticeCursor(csrf = csrf, cursor = cursor)
            if (response.code == 0) {
                owner.success(Unit)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "更新系统通知已读失败" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }

    suspend fun deleteFeedItem(type: Int, id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val csrf = owner.csrf()
            if (csrf.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("请先登录"))
            }

            val response = api.deleteFeedItem(type = type, id = id, csrf = csrf, csrfToken = csrf)
            if (response.code == 0) {
                owner.success(Unit)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "删除消息失败" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }

    suspend fun setLikeFeedNotice(id: Long, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val csrf = owner.csrf()
            if (csrf.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("请先登录"))
            }

            val response = api.setFeedNotice(
                id = id,
                noticeState = if (enabled) 0 else 1,
                csrf = csrf,
                csrfToken = csrf
            )
            if (response.code == 0) {
                owner.success(Unit)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "更新点赞通知设置失败" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }
    
    /**
     * 获取会话列表
     * @param sessionType 会话类型: 1=用户与系统, 2=未关注人, 3=粉丝团, 4=所有, 9=关注的人与系统
     * @param size 返回数量
     */
    suspend fun getSessions(
        sessionType: Int = 4,
        size: Int = 50, //  keep size 50
        page: Int = 1,
        endTs: Long = 0
    ): Result<SessionListData> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {

            
            val response = api.getSessions(
                sessionType = sessionType,
                size = size,
                pn = page, // Revert to using dynamic page
                endTs = endTs,
                unfollowFold = 0
            )

            
            if (response.code == 0 && response.data != null) {
                owner.success(response.data)
            } else {
                val errorMsg = when (response.code) {
                    -101 -> "请先登录"
                    -400 -> "请求参数错误"
                    else -> response.message.ifEmpty { "获取会话列表失败 (${response.code})" }
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }
    
    /**
     * 获取私信消息记录
     * @param talkerId 聊天对象ID
     * @param sessionType 会话类型: 1=用户, 2=粉丝团
     * @param size 返回消息数量
     * @param endSeqno 结束序列号 (用于分页加载更早的消息)
     */
    suspend fun getMessages(
        talkerId: Long,
        sessionType: Int = 1,
        size: Int = 20,
        endSeqno: Long = 0
    ): Result<MessageHistoryData> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {

            
            val response = api.fetchSessionMsgs(
                talkerId = talkerId,
                sessionType = sessionType,
                size = size,
                endSeqno = endSeqno
            )

            
            if (response.code == 0 && response.data != null) {
                owner.success(response.data)
            } else {
                val errorMsg = when (response.code) {
                    -101 -> "请先登录"
                    -400 -> "请求参数错误"
                    else -> response.message.ifEmpty { "获取消息记录失败 (${response.code})" }
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }

    suspend fun getSessionControlInfo(
        talkerId: Long,
        sessionType: Int
    ): Result<MessageSessionControlInfo> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val detail = owner.runCatching {
                api.getSessionDetail(talkerId = talkerId, sessionType = sessionType)
                    .takeIf { it.code == 0 }
                    ?.data
            }.getOrNull()

            val ownUid = owner.mid
            val dnd = if (ownUid != null && ownUid > 0) {
                owner.runCatching {
                    api.getMsgDnd(
                        ownUid = ownUid,
                        uid = talkerId.takeIf { sessionType == 1 },
                        groupId = talkerId.takeIf { sessionType == 2 }
                    ).takeIf { it.code == 0 }?.data
                }.getOrNull()
            } else {
                null
            }
            val isDnd = when (sessionType) {
                2 -> dnd?.group_settings?.firstOrNull { it.id == talkerId }?.setting == 1
                else -> dnd?.uid_settings?.firstOrNull { it.id == talkerId }?.setting == 1
            }.takeIf { dnd != null } ?: detail?.is_dnd?.let { it == 1 }

            val limit = if (sessionType == 1) {
                owner.runCatching {
                    api.getSessionLimit(uid = talkerId)
                        .takeIf { it.code == 0 }
                        ?.data
                }.getOrNull()
            } else {
                null
            }
            val push = if (sessionType == 1) {
                owner.runCatching {
                    api.getSessionPushSetting(talkerUid = talkerId)
                        .takeIf { it.code == 0 }
                        ?.data
                }.getOrNull()
            } else {
                null
            }

            owner.success(
                MessageSessionControlInfo(
                    isLimit = limit?.let { it.is_limit == 1 },
                    reportLimit = limit?.let { it.report_limit == 1 },
                    isDnd = isDnd,
                    pushMuted = push?.let { it.push_setting == 1 },
                    showPushSetting = push?.show_push_setting == 1,
                    followStatus = push?.follow_status,
                    isIntercept = detail?.is_intercept?.let { it == 1 }
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }

    suspend fun setSessionDnd(
        talkerId: Long,
        sessionType: Int,
        enabled: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val csrf = requireCsrf().getOrElse { return@withContext Result.failure(it) }
            val response = api.setMsgDnd(
                ownUid = owner.mid,
                setting = if (enabled) 1 else 0,
                dndUid = talkerId.takeIf { sessionType == 1 },
                dndGroupId = talkerId.takeIf { sessionType == 2 },
                csrf = csrf,
                csrfToken = csrf
            )
            if (response.code == 0) {
                owner.success(Unit)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "更新免打扰失败 (${response.code})" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }

    suspend fun setSessionPushMuted(
        talkerId: Long,
        muted: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val csrf = requireCsrf().getOrElse { return@withContext Result.failure(it) }
            val response = api.setPushSetting(
                talkerUid = talkerId,
                setting = if (muted) 1 else 0,
                csrf = csrf,
                csrfToken = csrf
            )
            if (response.code == 0) {
                owner.success(Unit)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "更新推送设置失败 (${response.code})" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }

    suspend fun setSessionIntercept(
        talkerId: Long,
        intercepted: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val csrf = requireCsrf().getOrElse { return@withContext Result.failure(it) }
            val response = api.updateIntercept(
                talkerId = talkerId,
                status = if (intercepted) 1 else 0,
                csrf = csrf,
                csrfToken = csrf
            )
            if (response.code == 0) {
                owner.success(Unit)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "更新拦截状态失败 (${response.code})" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }

    suspend fun markDustbinRead(): Result<Unit> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val csrf = requireCsrf().getOrElse { return@withContext Result.failure(it) }
            val response = api.batchUpdateDustbinAck(csrf = csrf, csrfToken = csrf)
            if (response.code == 0) {
                owner.success(Unit)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "拦截会话已读失败 (${response.code})" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }

    suspend fun clearDustbinSessions(): Result<Unit> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val csrf = requireCsrf().getOrElse { return@withContext Result.failure(it) }
            val response = api.batchRemoveDustbin(csrf = csrf, csrfToken = csrf)
            if (response.code == 0) {
                owner.success(Unit)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "清空拦截会话失败 (${response.code})" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }
    
    /**
     * 发送文字私信
     * @param receiverId 接收者ID
     * @param content 消息内容
     * @param receiverType 接收者类型: 1=用户, 2=粉丝团
     */
    suspend fun sendTextMessage(
        receiverId: Long,
        content: String,
        receiverType: Int = 1
    ): Result<SendMessageData> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
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
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }

    suspend fun sendShareMessage(
        receiverId: Long,
        receiverType: Int,
        content: String,
    ): Result<SendMessageData> = sendMessage(
        receiverId = receiverId,
        receiverType = receiverType,
        msgType = 7,
        content = content,
    )

    suspend fun withdrawMessage(
        receiverId: Long,
        msgKey: Long,
        receiverType: Int = 1
    ): Result<SendMessageData> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {


            sendMessage(
                receiverId = receiverId,
                receiverType = receiverType,
                msgType = 5,
                content = MessageSendPayloadFactory.buildWithdrawContent(msgKey)
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }

    suspend fun uploadPrivateImage(
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Result<UploadCommentImageData> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val csrf = owner.csrf()
            if (csrf.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("请先登录"))
            }

            val mediaType = mimeType.toMediaType()
            val fileBody = bytes.toRequestBody(mediaType)
            val part = MultipartBody.Part.createFormData(
                "file_up",
                fileName.ifBlank { "message_image.jpg" },
                fileBody
            )
            val textMedia = "text/plain".toMediaType()
            val bizBody = "im".toRequestBody(textMedia)
            val csrfBody = csrf.toRequestBody(textMedia)

            val response = bilibiliApi.uploadPrivateMessageImage(
                fileUp = part,
                biz = bizBody,
                csrf = csrfBody
            )

            if (response.code == 0 && response.data != null) {
                owner.success(response.data)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "图片上传失败 (${response.code})" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }

    suspend fun sendImageMessage(
        receiverId: Long,
        imageUrl: String,
        width: Int,
        height: Int,
        imageType: String,
        size: Float,
        receiverType: Int = 1
    ): Result<SendMessageData> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            sendMessage(
                receiverId = receiverId,
                receiverType = receiverType,
                msgType = 2,
                content = MessageSendPayloadFactory.buildImageContent(
                    imageUrl = imageUrl,
                    width = width,
                    height = height,
                    imageType = imageType,
                    size = size
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }
    
    /**
     * 标记会话为已读
     * @param talkerId 聊天对象ID
     * @param sessionType 会话类型
     * @param ackSeqno 已读消息的序列号
     */
    suspend fun markAsRead(
        talkerId: Long,
        sessionType: Int,
        ackSeqno: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val csrf = owner.csrf()
            if (csrf.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("请先登录"))
            }
            
            val response = api.updateAck(
                talkerId = talkerId,
                sessionType = sessionType,
                ackSeqno = ackSeqno,
                csrf = csrf,
                csrfToken = csrf
            )
            
            if (response.code == 0) {
                owner.success(Unit)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "标记已读失败" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }
    
    /**
     * 置顶/取消置顶会话
     * @param talkerId 聊天对象ID
     * @param sessionType 会话类型
     * @param setTop true=置顶, false=取消置顶
     */
    suspend fun setSessionTop(
        talkerId: Long,
        sessionType: Int,
        setTop: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val csrf = owner.csrf()
            if (csrf.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("请先登录"))
            }
            
            val response = api.setTop(
                talkerId = talkerId,
                sessionType = sessionType,
                opType = if (setTop) 0 else 1,  // 0=置顶, 1=取消置顶
                csrf = csrf,
                csrfToken = csrf
            )
            
            if (response.code == 0) {
                owner.success(Unit)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "操作失败" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }
    
    /**
     * 移除会话
     * @param talkerId 聊天对象ID
     * @param sessionType 会话类型
     */
    suspend fun removeSession(
        talkerId: Long,
        sessionType: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        owner.assertCurrent()
        try {
            val csrf = owner.csrf()
            if (csrf.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("请先登录"))
            }
            
            val response = api.removeSession(
                talkerId = talkerId,
                sessionType = sessionType,
                csrf = csrf,
                csrfToken = csrf
            )
            
            if (response.code == 0) {
                owner.success(Unit)
            } else {
                Result.failure(Exception(response.message.ifEmpty { "移除会话失败" }))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Result.failure(e)
        }
    }
}
