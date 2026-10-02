package com.android.purebilibili.feature.video.screen

import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilipai.desktop.ui.DesktopOriginalPortraitDanmakuPort as DanmakuManager
import com.android.purebilibili.feature.video.ui.section.resolveVideoPlayerDanmakuSettingsScope
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel

@Composable
internal fun VideoDetailPlayerSettingsOverlayAdapter(
    context: Context,
    viewModel: VideoPlaybackViewModel,
    isFullscreenMode: Boolean,
    isPortraitFullscreen: Boolean,
    danmakuManager: DanmakuManager,
) {
    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val qualitySwitchFailureDialog by viewModel.qualitySwitchFailureDialog.collectAsStateWithLifecycle()
    val playerDiagnosticLoggingEnabled by com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings
        .getPlayerDiagnosticLoggingEnabled(context)
        .collectAsStateWithLifecycle(initialValue = true, lifecycle = lifecycle)
    val qualitySwitchFailureDialogEnabled by com.android.purebilibili.core.store.DesktopOriginalVideoHolderSettings.getQualitySwitchFailureDialogEnabled(context)
        .collectAsStateWithLifecycle(initialValue = true, lifecycle = lifecycle)
    val qualitySwitchFailureDialogOnceEnabled by com.android.purebilibili.core.store.DesktopOriginalVideoHolderSettings.getQualitySwitchFailureDialogOnceEnabled(context)
        .collectAsStateWithLifecycle(initialValue = false, lifecycle = lifecycle)
    val qualitySwitchFailureDialogShown by com.android.purebilibili.core.store.DesktopOriginalVideoHolderSettings.getQualitySwitchFailureDialogShown(context)
        .collectAsStateWithLifecycle(initialValue = false, lifecycle = lifecycle)
    val qualitySwitchDialogScope = rememberCoroutineScope()

    VideoDetailQualitySwitchFailureDialog(
        context = context,
        viewModel = viewModel,
        qualitySwitchFailureDialog = qualitySwitchFailureDialog,
        qualitySwitchFailureDialogEnabled = qualitySwitchFailureDialogEnabled,
        qualitySwitchFailureDialogOnceEnabled = qualitySwitchFailureDialogOnceEnabled,
        qualitySwitchFailureDialogShown = qualitySwitchFailureDialogShown,
        playerDiagnosticLoggingEnabled = playerDiagnosticLoggingEnabled,
        qualitySwitchDialogScope = qualitySwitchDialogScope,
    )

    val activeDanmakuScope = remember(isFullscreenMode, isPortraitFullscreen) {
        resolveVideoPlayerDanmakuSettingsScope(
            isFullscreen = isFullscreenMode,
            isPortraitFullscreen = isPortraitFullscreen
        )
    }
    val activeDanmakuBlockRulesRaw by holderPlatform.section.danmakuPreferences.blocks.getDanmakuBlockRulesRaw(activeDanmakuScope)
        .collectAsStateWithLifecycle(initialValue = "", lifecycle = lifecycle)
    val danmakuPreferenceScope = rememberCoroutineScope()

    VideoDetailDanmakuContextMenu(
        context = context,
        viewModel = viewModel,
        activeDanmakuBlockRulesRaw = activeDanmakuBlockRulesRaw,
        activeDanmakuScope = activeDanmakuScope,
        sortPreferenceScope = danmakuPreferenceScope,
    )

    androidx.compose.runtime.DisposableEffect(danmakuManager, viewModel, holderPlatform) {
        val clickLease = holderPlatform.acquireDanmakuClickListener { text, dmid, userHash, isSelf ->
            android.util.Log.d("VideoDetailScreen", "👆 Danmaku clicked: $text")
            viewModel.showDanmakuMenu(dmid, text, userHash, isSelf)
        }
        onDispose { clickLease.close() }
    }
}
