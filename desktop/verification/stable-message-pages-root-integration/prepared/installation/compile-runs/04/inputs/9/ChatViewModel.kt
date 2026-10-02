// 聊天详情 ViewModel
package com.android.purebilibili.feature.message
import com.bilipai.desktop.ui.*

import com.android.purebilibili.data.model.response.EmoteInfo
import com.android.purebilibili.data.model.response.PrivateMessageItem
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.repository.MessageSessionControlInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视频预览信息 (用于链接预览)
 */
data class VideoPreviewInfo(
    val bvid: String,
    val title: String,
    val cover: String,
    val ownerName: String,
    val viewCount: Long,
    val danmakuCount: Long,
    val duration: Long = 0
)

data class ChatUiState(
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val isUploadingImage: Boolean = false,
    val isLoadingMore: Boolean = false,
    val withdrawingMessageKey: Long? = null,
    val messages: List<PrivateMessageItem> = emptyList(),
    val emoteInfos: List<EmoteInfo> = emptyList(),
    val hasMore: Boolean = false,
    val minSeqno: Long = 0,
    val error: String? = null,
    val sendError: String? = null,
    val sessionControlInfo: MessageSessionControlInfo = MessageSessionControlInfo(),
    val isSessionControlLoading: Boolean = false,
    val isSessionControlUpdating: Boolean = false,
    val videoPreviews: Map<String, VideoPreviewInfo> = emptyMap()  // bvid -> VideoPreviewInfo
)

internal class ChatViewModel(
    private val talkerId: Long,
    private val sessionType: Int,
    private val owner: DesktopMessagePageAdmission
) {
    
    private val _uiState = owner.stateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    // 视频预览缓存
    private val videoPreviewCache = mutableMapOf<String, VideoPreviewInfo>()
    
    // 正在加载的 bvid 集合 (防止重复请求)
    private val loadingBvids = mutableSetOf<String>()
    
    // 当前用户 mid
    val currentUserMid: Long
        get() = owner.mid
    
    // BV号匹配正则
    private val bvPattern = Regex("BV[a-zA-Z0-9]{10}")
    private val avPattern = Regex("av(\\d+)", RegexOption.IGNORE_CASE)
    
    init {
        loadMessages()
        loadSessionControlInfo()
    }
    
    /**
     * 加载消息
     */
    fun loadMessages() {
        owner.launchRead("chat-list") {
            _uiState.value = _uiState.value.copy(isLoading = true, isLoadingMore = false, error = null)
            
            owner.requests.getMessages(
                talkerId = talkerId,
                sessionType = sessionType,
                size = 30
            ).fold(
                onSuccess = { data ->
                    val messages = data.messages?.reversed() ?: emptyList()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        messages = messages,
                        emoteInfos = data.e_infos ?: emptyList(),
                        hasMore = data.has_more == 1,
                        minSeqno = data.min_seqno,
                        videoPreviews = videoPreviewCache.toMap()
                    )
                    
                    // 标记为已读
                    data.max_seqno.takeIf { it > 0 }?.let { seqno ->
                        markAsRead(seqno)
                    }
                    
                    // 扫描并预加载视频信息
                    scanAndLoadVideoPreviews(messages)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "加载失败"
                    )
                }
            )
        }
    }
    
    /**
     * 扫描消息中的视频链接并预加载
     */
    private fun scanAndLoadVideoPreviews(messages: List<PrivateMessageItem>) {
        val bvids = mutableSetOf<String>()
        
        messages.forEach { msg ->
            if (msg.msg_type == 1) {
                val content = parseTextContent(msg.content)
                // 查找 BV 号
                bvPattern.findAll(content).forEach { match ->
                    bvids.add(match.value)
                }
            }
        }
        
        // 加载未缓存的视频信息
        bvids.forEach { bvid ->
            loadVideoPreview(bvid)
        }
    }
    
    /**
     * 加载单个视频预览信息
     */
    fun loadVideoPreview(bvid: String) {
        // 已缓存或正在加载则跳过
        if (videoPreviewCache.containsKey(bvid) || loadingBvids.contains(bvid)) {
            return
        }
        
        loadingBvids.add(bvid)
        
        owner.launch {
            try {
                val result = owner.getVideoDetails(bvid)
                result.onSuccess { viewInfo ->
                    val preview = VideoPreviewInfo(
                        bvid = viewInfo.bvid,
                        title = viewInfo.title,
                        cover = viewInfo.pic,
                        ownerName = viewInfo.owner.name,
                        viewCount = viewInfo.stat.view.toLong(),
                        danmakuCount = viewInfo.stat.danmaku.toLong(),
                        duration = viewInfo.pages.firstOrNull()?.duration ?: 0
                    )
                    videoPreviewCache[bvid] = preview
                    
                    // 更新 UI
                    _uiState.value = _uiState.value.copy(
                        videoPreviews = videoPreviewCache.toMap()
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e

            } finally {
                loadingBvids.remove(bvid)
            }
        }
    }
    
    /**
     * 解析文本消息内容
     */
    /**
     * 解析文本消息内容
     * 增强版: 处理非JSON内容，以及提取 "content" 字段
     */
    private fun parseTextContent(content: String): String {
        if (content.isBlank()) return ""
        
        // 如果不是 JSON 格式 (不以 { 开头)，直接返回
        if (!content.trim().startsWith("{")) {
            return content
        }

        return try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(content)
            
            // 尝试提取 "content" 字段
            if (element is kotlinx.serialization.json.JsonObject) {
                 element["content"]?.let { 
                    if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.toString()
                 } ?: content // 如果没有 content 字段，返回原字符串
            } else {
                content
            }
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            // 解析失败，说明不是合法JSON，直接返回原文
            content
        }
    }
    
    /**
     * 加载更多历史消息
     */
    fun loadMoreMessages() {
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return
        
        owner.launchRead("chat-more", dependsOn = "chat-list") {
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            
            owner.requests.getMessages(
                talkerId = talkerId,
                sessionType = sessionType,
                size = 30,
                endSeqno = _uiState.value.minSeqno
            ).fold(
                onSuccess = { data ->
                    val newMessages = data.messages?.reversed() ?: emptyList()
                    _uiState.value = _uiState.value.copy(
                        isLoadingMore = false,
                        messages = newMessages + _uiState.value.messages,
                        hasMore = data.has_more == 1,
                        minSeqno = data.min_seqno
                    )
                    
                    // 扫描新消息中的视频链接
                    scanAndLoadVideoPreviews(newMessages)
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(isLoadingMore = false)
                }
            )
        }
    }
    
    /**
     * 发送文字消息
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return
        
        owner.launchMutation("chat-send") {
            _uiState.value = _uiState.value.copy(isSending = true, sendError = null)
            
            owner.requests.sendTextMessage(
                receiverId = talkerId,
                content = content.trim(),
                receiverType = sessionType
            ).fold(
                onSuccess = { data ->
                    _uiState.value = _uiState.value.copy(isSending = false)
                    // 刷新消息列表
                    loadMessages()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        sendError = e.message ?: "发送失败"
                    )
                }
            )
        }
    }

    fun sendImageMessage(image: DesktopMessageLocalImage) {
        if (_uiState.value.isUploadingImage || _uiState.value.isSending) return

        owner.launchMutation("chat-send") {
            _uiState.value = _uiState.value.copy(isUploadingImage = true, sendError = null)

            withContext(Dispatchers.IO) {
                owner.runCatching {
                    val bytes = image.readBytes()

                    if (bytes.isEmpty()) {
                        error("图片内容为空")
                    }
                    if (bytes.size > 15 * 1024 * 1024) {
                        error("图片过大（单张最大 15MB）")
                    }

                    val mimeType = image.mimeType
                    val fileName = image.fileName

                    val uploadData = owner.requests.uploadPrivateImage(
                        fileName = fileName,
                        mimeType = mimeType,
                        bytes = bytes
                    ).getOrElse { throw it }

                    owner.requests.sendImageMessage(
                        receiverId = talkerId,
                        imageUrl = uploadData.imageUrl,
                        width = uploadData.imageWidth,
                        height = uploadData.imageHeight,
                        imageType = mimeType.substringAfter('/', "jpg"),
                        size = uploadData.imgSize,
                        receiverType = sessionType
                    ).getOrElse { throw it }
                }
            }.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isUploadingImage = false)
                    loadMessages()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isUploadingImage = false,
                        sendError = error.message ?: "图片发送失败"
                    )
                }
            )
        }
    }

    fun loadSessionControlInfo() {
        owner.launchRead("chat-control") {
            _uiState.value = _uiState.value.copy(isSessionControlLoading = true)
            owner.requests.getSessionControlInfo(talkerId, sessionType)
                .onSuccess { info ->
                    _uiState.value = _uiState.value.copy(
                        sessionControlInfo = info,
                        isSessionControlLoading = false
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isSessionControlLoading = false)
                }
        }
    }

    fun toggleDnd() {
        val current = _uiState.value.sessionControlInfo.isDnd == true
        updateSessionControl {
            owner.requests.setSessionDnd(talkerId, sessionType, !current)
        }
    }

    fun togglePushMuted() {
        if (sessionType != 1) return
        val current = _uiState.value.sessionControlInfo.pushMuted == true
        updateSessionControl {
            owner.requests.setSessionPushMuted(talkerId, !current)
        }
    }

    fun toggleIntercept() {
        if (sessionType != 1) return
        val current = _uiState.value.sessionControlInfo.isIntercept == true
        updateSessionControl {
            owner.requests.setSessionIntercept(talkerId, !current)
        }
    }

    private fun updateSessionControl(action: suspend () -> Result<Unit>) {
        if (_uiState.value.isSessionControlUpdating) return
        owner.launchMutation("updateSessionControl") {
            _uiState.value = _uiState.value.copy(isSessionControlUpdating = true, sendError = null)
            action().fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isSessionControlUpdating = false)
                    loadSessionControlInfo()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isSessionControlUpdating = false,
                        sendError = error.message ?: "更新会话设置失败"
                    )
                }
            )
        }
    }

    fun withdrawMessage(message: PrivateMessageItem) {
        if (message.msg_key <= 0L || message.msg_status == 1) return

        owner.launchMutation("withdrawMessage") {
            _uiState.value = _uiState.value.copy(
                withdrawingMessageKey = message.msg_key,
                sendError = null
            )

            owner.requests.withdrawMessage(
                receiverId = talkerId,
                msgKey = message.msg_key,
                receiverType = sessionType
            ).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        withdrawingMessageKey = null,
                        messages = ChatMessageMutationPolicy.markWithdrawn(
                            messages = _uiState.value.messages,
                            msgKey = message.msg_key
                        )
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        withdrawingMessageKey = null,
                        sendError = e.message ?: "撤回失败"
                    )
                }
            )
        }
    }
    
    /**
     * 标记为已读
     */
    private fun markAsRead(seqno: Long) {
        owner.launchMutation("markAsRead") {
            owner.requests.markAsRead(talkerId, sessionType, seqno)
        }
    }
    
    /**
     * 清除发送错误
     */
    fun clearSendError() {
        _uiState.value = _uiState.value.copy(sendError = null)
    }


    
    /**
     * 工厂类
     */
}
