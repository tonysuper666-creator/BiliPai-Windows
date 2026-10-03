package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.feature.audio.player.AudioNowPlayingSession
import com.bilipai.desktop.player.MpvPlayer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive

/** The existing Window context observes complete original getter flows from the sole Store.
 * Source admission is the accepted native publication, before MPV's native lock.
 */
@Composable internal fun DesktopOriginalBackgroundPlaybackEffects(
    context: DesktopOriginalPlayerSettingsContext,
    player: MpvPlayer?,
    hidden: Boolean,
    isPip: Boolean,
    isInAudioMode: Boolean,
    live: () -> Boolean,
) {
    if (player == null) return
    val currentLive by rememberUpdatedState(live)
    val currentContext by rememberUpdatedState(context)
    val controller = remember(player) {
        DesktopOriginalBackgroundPlaybackController(player) {
            currentLive() && currentContext.isCurrentForOriginalWrite()
        }
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    var settings by remember(context) { mutableStateOf<DesktopOriginalBackgroundPlaybackSettings?>(null) }
    LaunchedEffect(context) {
        combine(DesktopOriginalPlaybackSettingsPreferences.getBackgroundPlaybackEnabled(context),
            DesktopOriginalPlaybackSettingsPreferences.getMiniPlayerMode(context),
            DesktopOriginalPlaybackSettingsPreferences.getStopPlaybackOnExit(context),
            DesktopOriginalPlaybackSettingsPreferences.getAudioNowPlayingBarEnabled(context)) { background, mode, stop, bar ->
            DesktopOriginalBackgroundPlaybackSettings(background, mode, stop, bar)
        }.collect { value -> if (isActive && currentLive()) settings = value }
    }
    val native by player.state.collectAsState()
    val audioSessionActive by AudioNowPlayingSession.active.collectAsState()
    val sourceVersion = player.currentSourceVersion
    LaunchedEffect(controller, settings, hidden, isPip, isInAudioMode, native.ready, native.loading,
        native.paused, native.nativePaused, native.audioOnly, native.ended, sourceVersion, audioSessionActive) {
        val observed = settings ?: return@LaunchedEffect
        if (isActive && currentLive()) controller.update(hidden, observed,
            // Current original Holder maps its mini flag to this very same PiP owner.
            isMiniMode = isPip, isPip = isPip,
            isInAudioMode = isInAudioMode || native.audioOnly, originalAudioSessionActive = audioSessionActive)
    }
}
