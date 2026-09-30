package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import com.android.purebilibili.feature.profile.resolveProfileSkinVideoRepeatMode
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopSkinVideoRegistry
import com.bilipai.desktop.plugins.DesktopSkinVideoRepeat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.WindowStateListener
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

/** Own native instance: original once/loop rule, muted output and actual window visibility lifecycle. */
@Composable
internal fun DesktopUiSkinVideo(path: String, modifier: Modifier, playing: Boolean,
    playMode: String?, onError: (String) -> Unit) {
    val player = remember(path) { MpvPlayer(useNullAudioOutput = true) }
    val state by player.state.collectAsState()
    val errorCallback by rememberUpdatedState(onError)
    val enabled by rememberUpdatedState(playing)
    val mode = remember(playMode) { resolveProfileSkinVideoRepeatMode(playMode) }
    var visible by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        var window: Window? = null
        fun updateVisibility() {
            val owner = SwingUtilities.getWindowAncestor(player.surface)
            visible = player.surface.isShowing && owner?.isShowing == true &&
                (owner !is Frame || owner.extendedState and Frame.ICONIFIED == 0)
            player.setPaused(!enabled || !visible)
        }
        val stateListener = WindowStateListener { updateVisibility() }
        val visibilityListener = object : ComponentAdapter() {
            override fun componentShown(event: ComponentEvent) = updateVisibility()
            override fun componentHidden(event: ComponentEvent) = updateVisibility()
        }
        val hierarchy = HierarchyListener { event ->
            if (event.changeFlags and (HierarchyEvent.PARENT_CHANGED or HierarchyEvent.SHOWING_CHANGED or HierarchyEvent.DISPLAYABILITY_CHANGED).toLong() != 0L) {
                val latest = SwingUtilities.getWindowAncestor(player.surface)
                if (window !== latest) {
                    (window as? Frame)?.removeWindowStateListener(stateListener)
                    window?.removeComponentListener(visibilityListener)
                    window = latest
                    (window as? Frame)?.addWindowStateListener(stateListener)
                    window?.addComponentListener(visibilityListener)
                }
                updateVisibility()
            }
        }
        try {
            DesktopSkinVideoRegistry.register(player)
            player.setVolume(0.0); player.setMuted(true)
            // Preserve the original profile-background ZOOM behavior through mpv's real panscan option.
            player.setVideoPanscan(1.0)
            player.surface.addHierarchyListener(hierarchy)
            updateVisibility()
        } catch (failure: Exception) { player.close(); errorCallback(failure.message ?: "装扮播放器初始化失败") }
        onDispose {
            player.surface.removeHierarchyListener(hierarchy)
            (window as? Frame)?.removeWindowStateListener(stateListener)
            window?.removeComponentListener(visibilityListener)
            DesktopSkinVideoRegistry.release(player)
        }
    }
    LaunchedEffect(player, mode) { player.setLoop(mode == DesktopSkinVideoRepeat.REPEAT_MODE_ONE) }
    LaunchedEffect(player, path) {
        try {
            val file = withContext(Dispatchers.IO) {
                Path.of(path).toAbsolutePath().normalize().also { location ->
                    require(Files.isRegularFile(location) && Files.size(location) <= 32L * 1024 * 1024) { "MP4 装饰资源无效" }
                    val header = Files.newInputStream(location).use { it.readNBytes(12) }
                    require(header.size >= 8 && header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())) { "装扮资源不是 MP4 文件" }
                }
            }
            player.load(PlaybackSource(file.toString(), title = "装扮动态背景", startPaused = !playing || !visible))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (failure: Exception) { errorCallback(failure.message ?: "装扮视频加载失败") }
    }
    LaunchedEffect(player, playing, visible) { player.setPaused(!playing || !visible) }
    LaunchedEffect(state.error) { state.error?.let { errorCallback(it) } }
    SwingPanel(factory = { player.surface }, background = Color.Transparent, modifier = modifier)
}
