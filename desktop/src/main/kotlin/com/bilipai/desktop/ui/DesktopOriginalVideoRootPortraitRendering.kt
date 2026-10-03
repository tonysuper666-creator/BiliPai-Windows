package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuViewport
import com.android.purebilibili.feature.video.screen.resolveVideoDetailSystemBarsApplySpec
import com.android.purebilibili.feature.video.screen.resolveVideoDetailSystemBarsVisibilityPolicy
import com.android.purebilibili.feature.video.ui.components.VideoAspectRatio
import com.android.purebilibili.feature.video.ui.components.resolveVideoViewportLayout
import kotlinx.coroutines.flow.StateFlow

/** A view of the SAME Section carrier, native Canvas, Window and Overlay.
 * Tokens below are local borrowed-view registrations, never a source/session
 * authority. Window/native lifetime and all data remain with the supplied owners. */
internal class DesktopOriginalVideoRootPortraitRendering(
    private val assembly: DesktopOriginalVideoOwnerAssembly,
    private val section: DesktopOriginalVideoSectionPlatform,
    private val windows: DesktopOriginalVideoWindowsWindowPort,
    private val pipActive: StateFlow<Boolean>,
    private val sameCurrent: () -> Boolean,
    private val reportUnsupported: (String) -> Unit,
    /** Store -> entry; validate exact publication, RELEASE native lock, then
     * perform the existing Section/Overlay registration. No Window work here. */
    private val withPresentationAdmission: (DesktopOriginalVideoAcceptedPublication, () -> Unit) -> Boolean,
) {
    private var nextLease = 0L
    private val danmakuLeases = linkedMapOf<Long, AutoCloseable?>() // composition effect thread only
    private var borrowedDanmaku by mutableIntStateOf(0)

    private fun owns() = sameCurrent() && assembly.owns()
    private fun verify(player: DesktopOriginalMpvSectionControl) {
        require(player === assembly.section) { "Portrait must borrow the installed same MPV control" }
    }
    private fun borrowViewport(expected: DesktopOriginalVideoAcceptedPublication): AutoCloseable? {
        var registration: AutoCloseable? = null
        if (owns()) withPresentationAdmission(expected) { registration = section.acquireViewportLease() }
        return registration
    }

    fun acquirePresentation(value: DesktopOriginalVideoOwnerAssembly, active: Boolean): AutoCloseable {
        require(value === assembly)
        if (!active || !owns()) return AutoCloseable {} // Original inactive presentation explicitly declines registration.
        val visibility = resolveVideoDetailSystemBarsVisibilityPolicy(
            isFullscreenMode = false, hideVideoPageStatusBar = false,
            isInPipMode = pipActive.value, isScreenActive = true, isPortraitFullscreen = true,
        )
        val spec = resolveVideoDetailSystemBarsApplySpec(
            visibilityPolicy = visibility, useTabletLayout = false, isLightBackground = false,
            backgroundColor = 0xff000000.toInt(), transparentColor = 0,
            blackColor = 0xff000000.toInt(), transientBarsBehavior = 2,
        )
        // Real same Window registration. Its own handle, not restoreEntryWindowChrome().
        return windows.acquirePortraitPresentation(spec)
    }

    fun acquireDanmakuPlayer(value: DesktopOriginalVideoOwnerAssembly, player: DesktopOriginalMpvSectionControl): AutoCloseable {
        require(value === assembly); verify(player)
        check(owns()) { "Portrait player view retired" }
        val token = ++nextLease
        danmakuLeases[token] = assembly.native.current()?.let(::borrowViewport)
        borrowedDanmaku = danmakuLeases.size
        return AutoCloseable {
            if (danmakuLeases.containsKey(token)) {
                danmakuLeases.remove(token)?.close()
                borrowedDanmaku = danmakuLeases.size
            }
        }
    }

    fun releasePager(value: DesktopOriginalVideoOwnerAssembly, player: DesktopOriginalMpvSectionControl) {
        require(value === assembly); verify(player)
        // Pager never created this player. Release only this entry's borrowed view.
        val handles = danmakuLeases.values.toList()
        danmakuLeases.clear(); borrowedDanmaku = 0
        handles.forEach { it?.close() }
    }

    fun recover(value: DesktopOriginalVideoOwnerAssembly, player: DesktopOriginalMpvSectionControl) {
        require(value === assembly); verify(player)
        if (owns() && !pipActive.value) {
            val expected = assembly.native.current()
            section.recoverViewport("portrait:${expected?.sourceVersion ?: "initial"}", fullscreen = true,
                pip = false, predictiveBackGeneration = 0)
        }
    }

    @Composable fun Surface(value: DesktopOriginalVideoOwnerAssembly, player: DesktopOriginalMpvSectionControl,
        foreground: @Composable () -> Unit) {
        require(value === assembly); verify(player)
        // Section265 + Nested12 reuses the one carrier even when called inside Section.
        if (owns()) section.RenderPlayerForeground { if (owns()) foreground() }
    }

    @Composable fun Viewport(value: DesktopOriginalVideoOwnerAssembly, player: DesktopOriginalMpvSectionControl,
        modifier: Modifier, resizeMode: Int, keepAwake: Boolean, navigationTextureRequested: Boolean, requiresHdr: Boolean) {
        require(value === assembly); verify(player)
        val nativeReadback by player.nativePlayer.state.collectAsState()
        val pip by pipActive.collectAsState()
        var measured by remember(this, player) { mutableStateOf(IntSize.Zero) }
        // Read the existing native flow to update on load/ready/seek; capture the
        // canonical publication afresh on every composition, never a latest URL.
        val expected = if (nativeReadback.ready) assembly.native.current() else null
        val bounds = modifier.onGloballyPositioned { if (owns()) measured = it.size }
        LaunchedEffect(navigationTextureRequested, requiresHdr) {
            if (navigationTextureRequested && owns()) reportUnsupported("Portrait Texture/Haze/alpha/arbitrary clip is unavailable on the existing HWND Canvas; original cover/scrim remains")
            if (requiresHdr && owns()) reportUnsupported("Portrait Android HDR Surface selection is unavailable; this adapter keeps the actual MPV output without claiming HDR certification")
        }
        DisposableEffect(expected, pip) {
            val lease = if (expected != null && !pip && owns()) borrowViewport(expected) else null
            onDispose { lease?.close() }
        }
        if (owns() && measured.width > 0 && measured.height > 0) {
            val layout = resolveVideoViewportLayout(measured.width, measured.height,
                VideoAspectRatio.fromResizeMode(resizeMode))
            section.NativeViewport(bounds, layout, resizeMode,
                revealAlpha = 1f, revealScale = 1f, freeScale = 1f, panX = 0f, panY = 0f,
                flipHorizontal = false, flipVertical = false, visible = !pip, keepAwake = keepAwake && !pip)
        } else Box(bounds)
    }

    @Composable fun DanmakuSurface(value: DesktopOriginalVideoOwnerAssembly, modifier: Modifier,
        videoWidth: Int, videoHeight: Int, resizeMode: Int) {
        require(value === assembly)
        val pip by pipActive.collectAsState()
        val density = LocalDensity.current.density
        var measured by remember(this) { mutableStateOf(IntSize.Zero) }
        val bounds = modifier.onGloballyPositioned { if (owns()) measured = it.size }
        val viewport = resolveDanmakuViewport(measured.width, measured.height, density)
        // videoWidth/videoHeight/resizeMode remain the original caller metadata.
        // Physical bounds are the actual Modifier slot; no guessed video-aspect box.
        if (owns() && !pip && borrowedDanmaku > 0 && viewport != null) {
            section.NativeDanmakuSurface(viewport, bounds)
        } else Box(bounds)
    }
}
