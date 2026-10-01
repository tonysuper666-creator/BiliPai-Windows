package com.android.purebilibili.feature.audio.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AudioNowPlayingSession {
    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()

    /** 听视频小横条是否正悬浮在内容上方（由 AppNavigation 按可见性发布） */
    private val _barOverlayVisible = MutableStateFlow(false)
    val barOverlayVisible = _barOverlayVisible.asStateFlow()

    fun publishBarOverlayVisible(visible: Boolean) {
        _barOverlayVisible.value = visible
    }

    fun markListening() {
        _active.value = true
    }

    fun dismiss() {
        _active.value = false
    }
}
