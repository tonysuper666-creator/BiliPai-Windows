package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.feature.download.resolveOfflineMiniPlayerPayload
import com.bilipai.desktop.player.PictureInPictureController
import com.bilipai.desktop.player.WindowsMediaSession
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.settings.DesktopOriginalDanmakuPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.awt.Window

/** Defers the original request until this exact control window has finished showing. */
private class DesktopOfflineFullscreenAdmission(
    private val isOwned: () -> Boolean,
    private val currentFullscreen: () -> Boolean,
    private val setFullscreen: (Boolean) -> Unit,
    private val restoreChrome: () -> Unit,
) : DesktopOfflineWindowEffects {
    private var requested: Boolean? = null
    private var readyWindow: Any? = null
    fun windowAvailability(identity: Any, ready: Boolean) {
        if (ready) readyWindow = identity
        else if (readyWindow === identity) readyWindow = null
        if (ready) deliverRequest()
    }
    override fun applyFullscreen(fullscreen: Boolean) {
        requested = fullscreen
        deliverRequest()
    }
    private fun deliverRequest() {
        val fullscreen = requested ?: return
        if (!isOwned() || (fullscreen && readyWindow == null)) return
        if (currentFullscreen() != fullscreen) setFullscreen(fullscreen)
    }
    override fun restoreCurrentRootChrome() { restoreChrome() }
}

/** Uses the existing Root media actors and the sole full original foreground carrier. */
@Composable
internal fun DesktopOriginalOfflineRootHost(
    taskId: String,
    backend: DesktopOfflineTaskPlayerBinding,
    entryScope: CoroutineScope,
    context: DesktopPluginContext,
    preferences: DesktopOriginalDanmakuPreferences,
    presentation: DesktopDanmakuPresentationBinding,
    systemMedia: WindowsMediaSession?,
    pictureInPicture: PictureInPictureController?,
    pipActive: Boolean,
    window: Window?,
    isFullscreen: () -> Boolean,
    setFullscreen: (Boolean) -> Unit,
    restoreCurrentRootChrome: () -> Unit,
    onBack: () -> Unit,
    feedback: (String) -> Unit,
    cast: com.bilipai.desktop.cast.DesktopCastController,
    runtime: com.bilipai.desktop.plugins.DesktopPluginRuntime,
    castMedia: suspend () -> com.bilipai.desktop.cast.DesktopCastMediaPublication,
) {
    val actualPipActive by rememberUpdatedState(pipActive)
    val foregroundActive by rememberUpdatedState(LocalDesktopDetailForeground.current)
    val currentFeedback by rememberUpdatedState(feedback)
    val currentCastMedia by rememberUpdatedState(castMedia)
    val currentFullscreen by rememberUpdatedState(isFullscreen)
    val setActualFullscreen by rememberUpdatedState(setFullscreen)
    val restoreChrome by rememberUpdatedState(restoreCurrentRootChrome)
    val windowEffects = remember(backend, entryScope) {
        DesktopOfflineFullscreenAdmission(
            { backend.isOwned() && entryScope.isActive && foregroundActive },
            { currentFullscreen() }, { setActualFullscreen(it) }, { restoreChrome() },
        )
    }
    val surface = remember(backend, pictureInPicture, window, windowEffects, cast, runtime) {
        object : DesktopOriginalOfflineSurface {
            @Composable
            override fun Render(player: DesktopOfflineMpvControl, modifier: Modifier, foreground: @Composable () -> Unit) {
                val controls: @Composable () -> Unit = {
                    Box(Modifier.fillMaxSize()) {
                        foreground()
                        Row(Modifier.align(Alignment.TopEnd).padding(top = 58.dp, end = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DesktopOfflineCastControls(player, backend, foregroundActive, runtime, cast,
                            { currentCastMedia() }) { currentFeedback(it) }
                        if (player.isOwned() && foregroundActive && pictureInPicture != null && window != null &&
                            !player.nativePlayer.state.value.audioOnly) {
                            AppTextButton(
                                modifier = Modifier,
                                onClick = {
                                    if (player.isOwned() && foregroundActive) {
                                        if (actualPipActive) pictureInPicture.restore()
                                        else backend.selectedTask(player.taskId)?.let { task ->
                                            if (!task.item.isAudioOnly) pictureInPicture.open(window, resolveOfflineMiniPlayerPayload(task.item).title)
                                        }
                                    }
                                },
                            ) { AppText(if (actualPipActive) "返回主窗口" else "浮窗") }
                        }
                        }
                        if (actualPipActive) AppText("正在浮窗播放", modifier = Modifier.align(Alignment.Center))
                    }
                }
                if (actualPipActive) {
                    // PiP currently owns this exact Canvas. No second Canvas or command popup is mounted.
                    if (player.isForegroundOwned() && foregroundActive) Box(modifier.background(Color.Black)) { controls() }
                } else {
                    DesktopOriginalPlayerSurface(player.nativePlayer, player.sourceVersion,
                        { player.isForegroundOwned() && foregroundActive }, modifier, controls,
                        onWindowAvailability = windowEffects::windowAvailability)
                }
            }
        }
    }
    val media = remember(systemMedia, pictureInPicture, backend) {
        DesktopOfflineRootMediaEffects(systemMedia, pictureInPicture, backend) { currentFeedback(it) }
    }
    val bindings = remember(backend, entryScope, context, preferences, presentation, surface, windowEffects, media) {
        DesktopOriginalOfflinePlayerBindings(context, backend, entryScope, preferences, presentation,
            windowEffects, surface, media) { currentFeedback(it) }
    }
    DesktopOriginalOfflinePlayerHost(taskId, bindings) {
        if (backend.isOwned()) {
            if (backend.ownsAcceptedSource()) pictureInPicture?.close()
            onBack()
        }
    }
}


/** Small original-control slot in the actual offline foreground window. Shared cast actors remain Root-owned. */
@Composable
private fun DesktopOfflineCastControls(
    player: DesktopOfflineMpvControl,
    backend: DesktopOfflineTaskPlayerBinding,
    foregroundActive: Boolean,
    runtime: com.bilipai.desktop.plugins.DesktopPluginRuntime,
    cast: com.bilipai.desktop.cast.DesktopCastController,
    media: suspend () -> com.bilipai.desktop.cast.DesktopCastMediaPublication,
    feedback: (String) -> Unit,
) {
    require(cast.context === runtime.context)
    val foreground by rememberUpdatedState(foregroundActive)
    val latestMedia by rememberUpdatedState(media)
    val latestFeedback by rememberUpdatedState(feedback)
    val nativeState by player.nativePlayer.state.collectAsState()
    val expected = remember(player, nativeState) { player.nativePlayer.currentSourceSnapshot() }
    val scope = rememberCoroutineScope()
    var publication by remember(player, expected?.sourceVersion, expected?.source) {
        mutableStateOf<com.bilipai.desktop.cast.DesktopCastMediaPublication?>(null)
    }
    var protocol by remember(player, expected?.sourceVersion, expected?.source) { mutableStateOf<String?>(null) }
    var opening by remember(player, expected?.sourceVersion, expected?.source) { mutableStateOf(false) }
    var enabling by remember(player, expected?.sourceVersion, expected?.source) { mutableStateOf(false) }
    val info by runtime.plugins.collectAsState()
    fun current(): Boolean = expected != null && foreground && player.isOwned() && player.isForegroundOwned() &&
        backend.isOwned() && backend.ownsAcceptedSource() && player.sourceVersion == expected.sourceVersion &&
        player.nativePlayer.ownsSourceSnapshot(expected)
    fun commit(action: () -> Unit): Boolean {
        val captured = expected ?: return false
        val source = captured.source.nativePublication ?: return false
        var applied = false
        val accepted = source.admit { if (current()) { action(); applied = true } }
        return accepted && applied
    }
    if (!current()) return
    AppTextButton(onClick = {
        if (commit { opening = true }) scope.launch {
            try {
                currentCoroutineContext().ensureActive()
                if (!current()) throw CancellationException("Offline cast source retired")
                // Capture the SAME Root factory before showing any device picker. No IO/admission lock is held.
                val captured = latestMedia()
                currentCoroutineContext().ensureActive()
                if (!commit { publication = captured; protocol = "choose" })
                    throw CancellationException("Offline cast source retired")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { commit { latestFeedback("无法准备缓存投屏，请重新打开缓存视频") } }
            finally { commit { opening = false } }
        }
    }, enabled = !opening) { AppText(if (opening) "准备投屏…" else "投屏") }
    val source = publication ?: return
    val sourceMedia: suspend () -> com.bilipai.desktop.cast.DesktopCastMediaPublication? = {
        if (!current()) throw CancellationException("Offline cast source retired")
        source // Never substitute a newly loaded source for this open picker.
    }
    val dismiss = { commit { protocol = null; publication = null }; Unit }
    when (protocol) {
        runtime.dlnaCast.id -> com.bilipai.desktop.cast.DesktopCastDialog(cast, sourceMedia, dismiss, controlSource = source)
        runtime.googleCast.id -> com.bilipai.desktop.cast.DesktopGoogleCastDialog(runtime.context, runtime.googleCast,
            sourceMedia, dismiss, controlSource = source)
        "choose" -> androidx.compose.material3.AlertDialog(onDismissRequest = dismiss,
            title = { AppText("选择投屏协议") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(runtime.dlnaCast, runtime.googleCast).forEach { plugin ->
                        val enabled = info.any { it.plugin === plugin && it.enabled }
                        AppTextButton(enabled = !enabling && !plugin.unavailable, modifier = Modifier.fillMaxWidth(), onClick = {
                            if (commit { enabling = true }) scope.launch {
                                try {
                                    if (!enabled) runtime.setEnabled(plugin.id, true, ::current)
                                    currentCoroutineContext().ensureActive()
                                    if (!commit {
                                        check(runtime.plugins.value.any { it.plugin === plugin && it.enabled })
                                        protocol = plugin.id
                                    }) throw CancellationException("Offline cast source retired")
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { commit { latestFeedback("投屏插件启用失败，请检查插件设置") } }
                                finally { commit { enabling = false } }
                            }
                        }) { AppText(if (enabled) plugin.name else "启用 ${plugin.name}") }
                    }
                }
            }, confirmButton = { AppTextButton(onClick = dismiss) { AppText("关闭") } })
    }
}
