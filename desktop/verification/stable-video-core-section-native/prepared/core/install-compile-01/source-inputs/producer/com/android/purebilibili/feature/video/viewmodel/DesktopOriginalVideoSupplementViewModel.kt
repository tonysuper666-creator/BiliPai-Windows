package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.note.VideoNoteUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
fun interface VideoSupplementLoader {
    suspend fun load(subject: VideoSubjectSnapshot): VideoSupplementSeed?
}

internal class VideoSupplementViewModel(
    private val environment:com.bilipai.desktop.ui.DesktopOriginalVideoSupplementEnvironment,
    private val loader:VideoSupplementLoader,
    private val startDelayMs:Long=300L,
) : AutoCloseable {
    private val viewModelScope get() = environment.scope
    private fun <T> MutableStateFlow(initial:T):MutableStateFlow<T> =
        com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(initial, environment::commit)

    private val _uiState = MutableStateFlow(VideoSupplementUiState())
    val uiState = _uiState.asStateFlow()
    private var subjectJob: Job? = null

    fun bindSubject(subject: VideoSubjectSnapshot, seed: VideoSupplementSeed) {
        if (!shouldRebindVideoDomain(_uiState.value.subject, subject)) {
            sync(seed)
            return
        }
        subjectJob?.cancel()
        _uiState.value = VideoSupplementUiState(
            subject = subject,
            visible = _uiState.value.visible,
            aiSummary = seed.aiSummary,
            videoNoteState = seed.videoNoteState,
            videoTags = seed.videoTags,
            onlineCount = seed.onlineCount,
            ownerFollowerCount = seed.ownerFollowerCount,
            ownerVideoCount = seed.ownerVideoCount
        )
        startDeferredLoad(subject)
    }

    fun sync(seed: VideoSupplementSeed) {
        val current = _uiState.value
        _uiState.value = current.copy(
            aiSummary = seed.aiSummary,
            videoNoteState = seed.videoNoteState,
            videoTags = seed.videoTags,
            onlineCount = seed.onlineCount,
            ownerFollowerCount = seed.ownerFollowerCount,
            ownerVideoCount = seed.ownerVideoCount
        )
    }

    fun setVisible(visible: Boolean) {
        if (_uiState.value.visible == visible) return
        _uiState.value = _uiState.value.copy(visible = visible)
        if (!visible) subjectJob?.cancel()
        else _uiState.value.subject?.let(::startDeferredLoad)
    }

    private fun startDeferredLoad(subject: VideoSubjectSnapshot) {
        if (!_uiState.value.visible) return
        subjectJob?.cancel()
        subjectJob = viewModelScope.launch {
            delay(startDelayMs)
            val loaded = loader.load(subject) ?: return@launch
            val current = _uiState.value
            if (current.visible && current.subject?.generation == subject.generation) {
                sync(loaded)
            }
        }
    }

    override fun close() {
        subjectJob?.cancel()

    }
}

