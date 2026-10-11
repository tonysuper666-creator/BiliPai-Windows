package com.bilipai.desktop.data

import com.android.purebilibili.core.network.socket.DanmakuProtocol
import com.android.purebilibili.core.network.socket.LiveDanmakuClient
import com.android.purebilibili.data.repository.LiveDanmakuPermission
import com.android.purebilibili.data.repository.LiveDanmakuSendRequest
import com.android.purebilibili.data.repository.LiveVoteSnapshot
import com.android.purebilibili.data.repository.LiveShieldInfo
import com.android.purebilibili.data.repository.LiveDanmakuReportRequest
import com.android.purebilibili.data.repository.LiveSuperChatReportRequest
import com.android.purebilibili.data.repository.LiveReportReason
import com.android.purebilibili.data.repository.DefaultLiveReportReasons
import com.android.purebilibili.data.repository.applyOriginalDesktopLiveVoteUpdate
import com.android.purebilibili.feature.live.LiveDanmakuItem
import com.android.purebilibili.feature.live.LiveDanmakuFilterPolicy
import com.android.purebilibili.feature.live.LiveRealtimeAction
import com.android.purebilibili.feature.live.resolveLiveRealtimeAction
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

data class LiveChatState(
    val messages: List<LiveDanmakuItem> = emptyList(),
    val connected: Boolean = false,
    val status: String = "正在连接直播弹幕",
    val error: String? = null,
    val permission: LiveDanmakuPermission? = null,
    val sending: Boolean = false,
    val moderating: Boolean = false,
)

/** One playback owns one original socket client; closing never leaves a reconnect job. */
class DesktopLiveSession(
    private val repository: DesktopRepository,
    private val media: DesktopMediaRepository,
    val roomId: Long,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val primaryEpoch = repository.sessionEpoch
    private val lifetimeLock = Any()
    private val mutableState = MutableStateFlow(LiveChatState())
    val state: StateFlow<LiveChatState> = mutableState.asStateFlow()
    private val mutableVoteSnapshot = MutableStateFlow(LiveVoteSnapshot())
    val voteSnapshot: StateFlow<LiveVoteSnapshot> = mutableVoteSnapshot.asStateFlow()
    private val mutableShieldInfo = MutableStateFlow(LiveShieldInfo())
    val shieldInfo: StateFlow<LiveShieldInfo> = mutableShieldInfo.asStateFlow()
    // Read/write only inside existing publish/admission + lifetimeLock.
    private var shieldRequestGeneration = 0L
    private var danmakuFilter = LiveDanmakuFilterPolicy()
    private val moderationGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mutableActions = MutableSharedFlow<LiveRealtimeAction>(extraBufferCapacity = 200, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val actions: SharedFlow<LiveRealtimeAction> = mutableActions.asSharedFlow()
    private val startGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val sendGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var client: LiveDanmakuClient? = null
    @Volatile private var lastSentText = ""
    @Volatile private var lastSentAtMs = 0L

    init {
        require(roomId > 0)
        // Primary credentials/MID retirement only. SPI, profile/VIP and ordinary
        // Cookie refresh do not change this existing primary generation flow.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.sessionEpochFlow.collect { if (it != primaryEpoch) close() }
        }
    }

    private fun owned(): Boolean = !closed.get() && scope.isActive && repository.sessionEpoch == primaryEpoch

    private fun publish(action: () -> Unit): Boolean {
        if (!owned()) return false
        var entered = false
        return try {
            repository.withPrimaryPlaybackAdmission(primaryEpoch, ::owned) {
                synchronized(lifetimeLock) {
                    entered = true
                    if (!owned()) false else { action(); true }
                }
            }
        } catch (retired: BiliApiException) {
            if (!entered && retired.apiCode == -101 && !owned()) false else throw retired
        }
    }

    fun start() {
        if (!owned()) { close(); return }
        if (!startGuard.compareAndSet(false, true)) return
        scope.launch {
            try {
                // This child is independent of history/config/socket startup. Hydration
                // can only replace the exact initial object, never a newer realtime vote.
                val initialVoteSnapshot = mutableVoteSnapshot.value
                launch voteSnapshotLoad@ {
                    try {
                        val snapshot = media.liveVoteSnapshot(roomId, primaryEpoch, ::owned).getOrNull()
                            ?: return@voteSnapshotLoad
                        publish {
                            if (mutableVoteSnapshot.value === initialVoteSnapshot) mutableVoteSnapshot.value = snapshot
                        }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { /* Original vote snapshot is optional. */ }
                }
                // Original startup refresh precedes history/socket; failure stays optional.
                try { refreshShieldInfo()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { /* Optional original server shield hydration. */ }
                // History/config remain optional: live socket authentication uses
                // its separately signed original API and the same current login.
                try {
                    val permission = media.liveDanmakuPermission(roomId, primaryEpoch, ::owned)
                    if (!publish { mutableState.update { it.copy(permission = permission) } }) return@launch
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) { if (!publish { mutableState.update { it.copy(error = error.message) } }) return@launch }
                try {
                    val history = media.liveDanmakuHistory(roomId, primaryEpoch, ::owned).map { LiveDanmakuItem(text = it.text, uid = it.uid,
                        uname = it.uname, emoticonUrl = it.emoticonUrl, replyToName = it.replyToName, dmType = it.dmType,
                        idStr = it.idStr, reportTs = it.reportTs, reportSign = it.reportSign) }
                    if (!publish { mutableState.update { it.copy(messages = history.filterNot { item ->
                        danmakuFilter.blocks(item.uid, item.text)
                    }.takeLast(200)) } }) return@launch
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { /* A failed history fetch cannot hide realtime messages. */ }
                if (!owned()) return@launch
                val socket = media.liveDanmakuClient(scope, roomId, primaryEpoch, ::owned)
                if (!publish { client = socket }) { socket.disconnect(); return@launch }
                launch {
                    var observedConnection = false
                    while (isActive && owned()) {
                        val connected = socket.isConnected
                        observedConnection = observedConnection || connected
                        if (!publish { mutableState.update { it.copy(connected = connected,
                            status = if (connected) "直播弹幕已连接" else if (observedConnection) "弹幕连接已断开" else "正在连接直播弹幕") } }) return@launch
                        delay(500)
                    }
                }
                socket.messageFlow.collect { packet ->
                    if (packet.operation == DanmakuProtocol.OP_MESSAGE) {
                        try {
                            val body = Json.parseToJsonElement(String(packet.body, Charsets.UTF_8)).jsonObject
                            val action = resolveLiveRealtimeAction(body, repository.account.value?.mid ?: 0L)
                            publish { apply(action) }
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) { /* Malformed individual messages are ignored as upstream does. */ }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                publish { mutableState.update { it.copy(connected = false, status = "直播弹幕连接失败", error = error.message ?: "连接失败") } }
            }
        }
    }

    private fun apply(action: LiveRealtimeAction) {
        when (action) {
            is LiveRealtimeAction.EmitChat -> if (!append(action.item)) return
            is LiveRealtimeAction.EmitSuperChat -> if (!append(action.item)) return
            is LiveRealtimeAction.RemoveSuperChats -> mutableState.update { state ->
                state.copy(messages = state.messages.filterNot { it.isSuperChat && it.superChatId in action.ids })
            }
            is LiveRealtimeAction.RecallDanmaku -> mutableState.update { state -> state.copy(messages = state.messages.filterNot { it.idStr == action.id }) }
            is LiveRealtimeAction.UpdateVote -> applyOriginalDesktopLiveVoteUpdate(mutableVoteSnapshot, action) {
                append(it)
            }
            else -> Unit
        }
        mutableActions.tryEmit(action)
    }

    private fun append(item: LiveDanmakuItem): Boolean {
        // Exact original scope: blocked ordinary chats never reach history OR overlay actions.
        if (!item.isSelf && !item.isSystem && !item.isSuperChat && danmakuFilter.blocks(item.uid, item.text)) return false
        if (item.isSelf && item.text == lastSentText && System.currentTimeMillis() - lastSentAtMs in 0..5000) return false
        var appended = false
        mutableState.update { state ->
            appended = item.idStr.isBlank() || state.messages.none { it.idStr == item.idStr }
            if (!appended) state else state.copy(messages = (state.messages + item).takeLast(200))
        }
        return appended
    }

    /** No automatic sends; this method is invoked only by the UI's Send action. */
    suspend fun send(message: String, color: Int = 16777215, mode: Int = 1, reply: LiveDanmakuItem? = null) {
        check(owned()) { "直播弹幕会话已关闭" }
        check(sendGuard.compareAndSet(false, true)) { "弹幕正在发送" }
        if (!publish { mutableState.update { it.copy(sending = true, error = null) } }) {
            sendGuard.set(false); throw CancellationException("直播会话或账号已切换")
        }
        try {
            val identity = repository.requireAccount()
            media.sendLiveDanmaku(LiveDanmakuSendRequest(roomId, message, color = color, mode = mode,
                replyMid = reply?.uid ?: 0, replyAttr = if (reply != null) 1 else 0,
                replyUname = reply?.uname.orEmpty(), replayDmid = reply?.idStr.orEmpty()), primaryEpoch, ::owned)
            if (!owned() || repository.account.value?.mid != identity.mid) throw CancellationException("直播会话或账号已切换")
            val own = LiveDanmakuItem(message, color, mode, uid = identity.mid,
                uname = "我", isSelf = true)
            if (!publish {
                append(own)
                mutableActions.tryEmit(LiveRealtimeAction.EmitChat(own))
                lastSentText = message; lastSentAtMs = System.currentTimeMillis()
            }) throw CancellationException("直播会话或账号已切换")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            publish { mutableState.update { it.copy(error = error.message ?: "弹幕发送失败") } }
            throw error
        } finally { sendGuard.set(false); publish { mutableState.update { it.copy(sending = false) } } }
    }

    /** The original server shield state belongs to this exact room/account Session. */
    suspend fun refreshShieldInfo() {
        val caller = currentCoroutineContext()[Job]
        val current = { caller?.isActive == true && owned() }
        var generation = 0L
        if (!publish { generation = ++shieldRequestGeneration })
            throw CancellationException("直播会话或账号已切换")
        val info = media.liveShieldInfo(roomId, primaryEpoch, current).getOrThrow()
        currentCoroutineContext().ensureActive()
        if (!publish {
            if (shieldRequestGeneration == generation) {
                mutableShieldInfo.value = info
                danmakuFilter = LiveDanmakuFilterPolicy(info)
                mutableState.update { state -> state.copy(messages = state.messages.filterNot { item ->
                    !item.isSelf && !item.isSystem && !item.isSuperChat && danmakuFilter.blocks(item.uid, item.text)
                }) }
            }
        }) throw CancellationException("直播会话或账号已切换")
    }

    private suspend fun moderate(refresh: Boolean, operation: suspend (() -> Boolean) -> Unit) {
        val caller = currentCoroutineContext()[Job]
        val current = { caller?.isActive == true && owned() }
        check(current()) { "直播弹幕会话已关闭" }
        check(moderationGuard.compareAndSet(false, true)) { "直播管理操作正在处理" }
        if (!publish {
            if (refresh) ++shieldRequestGeneration
            mutableState.update { it.copy(moderating = true, error = null) }
        }) {
            moderationGuard.set(false)
            throw CancellationException("直播会话或账号已切换")
        }
        try {
            operation(current)
            currentCoroutineContext().ensureActive()
            if (!current()) throw CancellationException("直播会话或账号已切换")
            if (refresh) {
                // A successful server write invalidates every older hydration/refresh.
                if (!publish { ++shieldRequestGeneration }) throw CancellationException("直播会话或账号已切换")
                try { refreshShieldInfo()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    // As in the original asynchronous refresh, read failure does not
                    // turn an accepted mutation into a failed write eligible for retry.
                    publish { mutableState.update { it.copy(error = error.message ?: "刷新直播屏蔽设置失败") } }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            publish { mutableState.update { it.copy(error = error.message ?: "直播管理操作失败") } }
            throw error
        } finally {
            // Publish cleanup before a newly admitted operation can publish busy=true.
            if (!publish {
                moderationGuard.set(false)
                mutableState.update { it.copy(moderating = false) }
            }) moderationGuard.set(false)
        }
    }

    /** Explicit actions only; no automatic mutation is performed by start/refresh. */
    suspend fun shieldUser(uid: Long) {
        require(uid > 0L)
        moderate(true) { current -> media.setLiveShieldUser(roomId, uid, 1, primaryEpoch, current).getOrThrow() }
    }

    suspend fun unshieldUser(uid: Long) {
        require(uid > 0L)
        moderate(true) { current -> media.setLiveShieldUser(roomId, uid, 0, primaryEpoch, current).getOrThrow() }
    }

    suspend fun addShieldKeyword(keyword: String) {
        moderate(true) { current -> media.addLiveShieldKeyword(keyword, primaryEpoch, current).getOrThrow() }
    }

    suspend fun deleteShieldKeyword(keyword: String) {
        moderate(true) { current -> media.deleteLiveShieldKeyword(keyword, primaryEpoch, current).getOrThrow() }
    }

    suspend fun setSilentRule(type: String, level: Int) {
        moderate(true) { current -> media.setLiveSilentRule(type, level, primaryEpoch, current).getOrThrow() }
    }

    suspend fun reportDanmaku(item: LiveDanmakuItem, reason: LiveReportReason) {
        require(reason in DefaultLiveReportReasons)
        moderate(false) { current ->
            var retained = false
            if (!publish { retained = mutableState.value.messages.any { it === item } })
                throw CancellationException("直播会话或账号已切换")
            require(retained && !item.isSelf && !item.isSystem) { "此弹幕已不属于当前直播消息" }
            if (item.isSuperChat) media.reportLiveSuperChat(LiveSuperChatReportRequest(
                roomId = roomId, uid = item.uid, uname = item.uname, message = item.text,
                messageId = item.superChatId, token = item.superChatToken,
                reportTime = item.superChatReportTs, reason = reason), primaryEpoch, current).getOrThrow()
            else media.reportLiveDanmaku(LiveDanmakuReportRequest(
                roomId = roomId, uid = item.uid, uname = item.uname, message = item.text,
                dmid = item.idStr, reportTime = item.reportTs, sign = item.reportSign,
                reason = reason, dmType = item.dmType), primaryEpoch, current).getOrThrow()
        }
    }


    override fun close() {
        val previous = synchronized(lifetimeLock) {
            if (!closed.compareAndSet(false, true)) return
            client.also { client = null }
        }
        // No lifetime/Store monitor encloses native/socket cancellation.
        previous?.disconnect(); scope.cancel()
        mutableState.update { it.copy(connected = false, status = "弹幕连接已关闭", sending = false, moderating = false) }
    }
}
