package com.android.purebilibili.feature.video.viewmodel

import com.bilipai.desktop.ui.DesktopOriginalVideoComposerEnvironment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

data class VideoComposerUiState(
    val subject: VideoSubjectSnapshot? = null,
    val commentDraft: String = "",
    val danmakuDraft: String = "",
    val isSendingComment: Boolean = false,
    val isSendingDanmaku: Boolean = false,
    val commentDialogVisible: Boolean = false,
    val danmakuDialogVisible: Boolean = false
)

sealed interface VideoComposerEvent {
    data object CommentSent : VideoComposerEvent
    data object DanmakuSent : VideoComposerEvent
}

internal class VideoComposerViewModel(
    private val environment: DesktopOriginalVideoComposerEnvironment,
) : AutoCloseable {
    private fun <T> MutableStateFlow(initial: T): MutableStateFlow<T> =
        com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(initial, environment::commit)

    private val _uiState = MutableStateFlow(VideoComposerUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = Channel<VideoComposerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow().mapNotNull { event ->
        event.takeIf { environment.isCurrent() }
    }
    private var mentionSearchJob: Job? = null

    fun bindSubject(subject: VideoSubjectSnapshot) {
        if (!shouldRebindVideoDomain(_uiState.value.subject, subject)) return
        mentionSearchJob?.cancel()
        _uiState.value = VideoComposerUiState(subject = subject)
    }

    fun updateCommentDraft(text: String) {
        _uiState.value = _uiState.value.copy(commentDraft = text)
    }

    fun updateDanmakuDraft(text: String) {
        _uiState.value = _uiState.value.copy(danmakuDraft = text)
    }

    internal suspend fun notifyCommentSent() {
        currentCoroutineContext().ensureActive()
        environment.assertCurrent()
        _events.send(VideoComposerEvent.CommentSent)
    }

    internal suspend fun notifyDanmakuSent() {
        currentCoroutineContext().ensureActive()
        environment.assertCurrent()
        _events.send(VideoComposerEvent.DanmakuSent)
    }

    override fun close() {
        mentionSearchJob?.cancel()
        _events.close()
    }
}
