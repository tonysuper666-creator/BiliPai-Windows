package com.android.purebilibili.feature.video.ambient

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.android.purebilibili.core.store.SettingsManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope

internal val LocalAmbientPresentation = staticCompositionLocalOf<AmbientPresentation?> { null }
internal val LocalAmbientController = staticCompositionLocalOf<AmbientFrameController?> { null }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun BindPlayerAmbient(
    player: Player,
    playerView: PlayerView?,
    identity: Any,
    quality: Int,
    width: Int,
    height: Int,
    foreground: Boolean,
    excluded: Boolean,
    hdr: Boolean,
    anime4k: Boolean,
    transitioning: Boolean,
    firstFrameReady: Boolean,
    statusBar: Boolean,
    diagnosticLogging: Boolean,
) {
    val context = LocalContext.current
    val presentation = LocalAmbientPresentation.current ?: return
    val controller = LocalAmbientController.current ?: return
    val settings by SettingsManager.getVideoAmbientSettings(context).collectAsStateWithLifecycle(AmbientSettings())
    val environment = rememberAmbientEnvironment(settings.enabled)
    val navTransition = LocalNavTransitionScope.current
    var refresh by remember(player) { mutableIntStateOf(0) }
    var seekSettlesAt by remember(player) { mutableLongStateOf(0L) }
    var surfaceGeneration by remember(playerView) { mutableIntStateOf(0) }
    val surface = playerView?.videoSurfaceView
    DisposableEffect(player, surface) {
        presentation.requiredRefresh = refresh
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { refresh++ }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                seekSettlesAt = android.os.SystemClock.elapsedRealtime() + 200
                refresh++
                presentation.requiredRefresh = refresh
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    refresh++
                    presentation.requiredRefresh = refresh
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_READY) refresh++ }
        }
        player.addListener(listener)
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceGeneration++; refresh++ }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surfaceGeneration++; refresh++ }
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceGeneration++ }
        }
        (surface as? SurfaceView)?.holder?.addCallback(callback)
        onDispose {
            player.removeListener(listener)
            (surface as? SurfaceView)?.holder?.removeCallback(callback)
        }
    }
    var textureIdentity by remember(surface) { mutableStateOf((surface as? TextureView)?.surfaceTexture) }
    LaunchedEffect(surface) {
        if (surface is TextureView) while (isActive) {
            textureIdentity = surface.surfaceTexture
            delay(250)
        }
    }
    val recoveryReady = rememberUpdatedState {
        foreground && !excluded && !transitioning && !navTransition.isRunning &&
            kotlin.math.abs(navTransition.relativeDepth) <= 0.001f
    }
    LaunchedEffect(player) {
        snapshotFlow { recoveryReady.value() }.collect { ready ->
            if (ready) {
                refresh++
                presentation.requiredRefresh = refresh
            }
        }
    }
    val glowAllowed = settings.enabled && !hdr && !anime4k && !environment.severe
    val active = foreground && !excluded && !transitioning && firstFrameReady && (glowAllowed || statusBar)
    val latestPolicy = rememberUpdatedState {
        val moving = transitioning || navTransition.isRunning || kotlin.math.abs(navTransition.relativeDepth) > 0.001f
        AmbientRunPolicy(active && !moving && android.os.SystemClock.elapsedRealtime() >= seekSettlesAt, glowAllowed, player.isPlaying,
            settings.powerSaving || environment.saving, refresh, diagnosticLogging)
    }
    val surfaceLocation = remember(surface) { IntArray(2) }
    SideEffect {
        presentation.layoutEnabled = glowAllowed && !excluded
        presentation.visible = glowAllowed && active
        presentation.visibilityGate = { !transitioning && !navTransition.isRunning && kotlin.math.abs(navTransition.relativeDepth) <= 0.001f }
        presentation.videoBoundsInWindow = {
            surface?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 }?.let {
                it.getLocationInWindow(surfaceLocation)
                androidx.compose.ui.geometry.Rect(
                    surfaceLocation[0].toFloat(), surfaceLocation[1].toFloat(),
                    (surfaceLocation[0] + it.width).toFloat(), (surfaceLocation[1] + it.height).toFloat(),
                )
            }
        }
        presentation.opacity = settings.opacity
        presentation.aspectRatio = width.coerceAtLeast(1).toFloat() / height.coerceAtLeast(1)
    }
    // Navigation progress is deliberately read inside the loop/draw provider, not high-frequency composition.
    LaunchedEffect(controller, surface, identity, quality, width, height, surfaceGeneration, textureIdentity, settings.enabled || statusBar) {
        controller.invalidate()
        if ((settings.enabled || statusBar) && (surface is SurfaceView || surface is TextureView)) {
            controller.run(ViewAmbientFrameSource(surface), width, height) { latestPolicy.value() }
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.invalidate(); presentation.visible = false }
    }
}
