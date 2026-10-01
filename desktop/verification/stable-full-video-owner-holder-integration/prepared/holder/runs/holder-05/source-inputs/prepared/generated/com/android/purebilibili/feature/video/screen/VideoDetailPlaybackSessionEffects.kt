package com.android.purebilibili.feature.video.screen

import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bilipai.desktop.ui.DesktopOriginalPortraitDanmakuPort as DanmakuManager
import com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform
import com.android.purebilibili.feature.video.viewmodel.PlayerToastMessage
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel
import kotlinx.coroutines.delay

@Stable
internal class VideoDetailPlaybackEventState(
    val danmakuManager: DanmakuManager,
) {
    var popupMessage by mutableStateOf<PlayerToastMessage?>(null)
        internal set
}

@Composable
internal fun rememberVideoDetailPlaybackEventState(playbackIdentity: String): VideoDetailPlaybackEventState {
    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current
    val danmakuManager = LocalDesktopOriginalVideoHolderPlatform.current.danmaku
    return remember(danmakuManager) { VideoDetailPlaybackEventState(danmakuManager) }
}

@Composable
internal fun VideoDetailPlaybackEventEffects(
    context: Context,
    viewModel: VideoPlaybackViewModel,
    state: VideoDetailPlaybackEventState,
) {
    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current
    val platform = LocalDesktopOriginalVideoHolderPlatform.current
    LaunchedEffect(viewModel, state) {
        viewModel.toastEvent.collect { message ->
            state.popupMessage = message
            delay(2_000)
            state.popupMessage = null
        }
    }
    LaunchedEffect(viewModel, state.danmakuManager) {
        viewModel.danmakuSentEvent.collect { danmakuData ->
            state.danmakuManager.addLocalDanmaku(
                text = danmakuData.text,
                color = danmakuData.color,
                mode = danmakuData.mode,
                fontSize = danmakuData.fontSize,
            )
        }
    }
    LaunchedEffect(viewModel, context) {
        viewModel.initWithContext(context)
        platform.logScreenView("VideoDetailScreen")
    }
}
