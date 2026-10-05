@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import com.android.purebilibili.core.ui.LocalAppPopupSurfaceRenderer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.core.store.HomeWallpaperEffectScope
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.feature.home.HomeWallpaperBackdrop
import com.android.purebilibili.feature.home.isStaticHomeWallpaperUri
import com.android.purebilibili.feature.home.resolveHomeWallpaperBackdropAppearance
import com.android.purebilibili.feature.home.components.LocalLiquidGlassRenderConfig
import com.android.purebilibili.feature.home.components.biliPaiFloatingDockShell
import com.android.purebilibili.feature.home.components.liquid.InnerShadow
import com.android.purebilibili.feature.home.components.liquid.ROUNDED_RECT_REFRACTION_SHADER
import com.android.purebilibili.feature.home.components.liquid.ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER
import com.android.purebilibili.feature.home.components.liquid.innerShadow
import com.bilipai.desktop.appearance.isDesktopInDarkTheme
import org.jetbrains.skia.RuntimeEffect
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal data class DesktopWindowsGlassRendererSupport(val supported: Boolean, val error: String? = null)

/** The library's Skiko isRuntimeShaderSupported() is unconditional. Compile both
 * ORIGINAL Lens variants and check the real blur backend instead of trusting it.
 * This is capability preflight, not a monitor-present or pixel-equivalence claim. */
internal fun probeDesktopWindowsGlassRenderer(): DesktopWindowsGlassRendererSupport = try {
    check(BlurEffect(1f, 1f).isSupported()) { "当前渲染器不支持玻璃模糊" }
    RuntimeEffect.makeForShader(ROUNDED_RECT_REFRACTION_SHADER).close()
    RuntimeEffect.makeForShader(ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER).close()
    DesktopWindowsGlassRendererSupport(true)
} catch (failure: Exception) {
    // RuntimeEffect errors can embed the whole shader. Keep the UI readable.
    DesktopWindowsGlassRendererSupport(false, "液态玻璃效果初始化失败；使用普通材质")
}

internal data class DesktopWindowsGlassMaterialBinding(
    val backdrop: Backdrop,
    val renderer: DesktopWindowsGlassRendererSupport,
    val sourceReady: Boolean,
    val sourceHasWallpaper: Boolean,
    val owns: () -> Boolean,
    val window: java.awt.Window? = null,
)

internal val LocalDesktopWindowsGlassMaterial = staticCompositionLocalOf<DesktopWindowsGlassMaterialBinding?> { null }

private class DesktopWindowsGlassSourceLease {
    val alive = AtomicBoolean(true)
    val size = AtomicReference(IntSize.Zero)
    val epoch = AtomicInteger()
    val queued = AtomicBoolean()
    val pendingDraw = AtomicReference<DesktopWindowsGlassDrawAck?>()
}

private data class DesktopWindowsGlassDrawAck(val source: Any, val epoch: Int, val size: IntSize)

/** Coalesce completed draws, not guesses about the next composition. An older
 * queued EDT callback can acknowledge the latest completed draw only after the
 * same exact source/geometry/owner checks. Native and graphics work stay outside. */
private fun scheduleDesktopWindowsGlassAck(lease: DesktopWindowsGlassSourceLease,
    currentSource: () -> Any, owns: () -> Boolean, publish: (DesktopWindowsGlassDrawAck) -> Unit) {
    if (!lease.queued.compareAndSet(false, true)) return
    EventQueue.invokeLater {
        try {
            val draw = lease.pendingDraw.getAndSet(null)
            if (draw != null && lease.alive.get() && owns() && currentSource() === draw.source &&
                lease.epoch.get() == draw.epoch && lease.size.get() == draw.size) publish(draw)
        } finally {
            lease.queued.set(false)
            if (lease.alive.get() && lease.pendingDraw.get() != null)
                scheduleDesktopWindowsGlassAck(lease, currentSource, owns, publish)
        }
    }
}

/** A separate background layer is the ONLY source. It contains the original
 * wallpaper and its original scrim/color policy, never the foreground consumers
 * or Swing/native video. Animated media keeps the original stable-color fallback. */
@Composable
internal fun DesktopWindowsGlassBackgroundHost(
    sourceOwner: Any,
    wallpaperUri: String,
    home: HomeSettings,
    showHomeWallpaper: Boolean,
    isDataSaverActive: Boolean,
    owns: () -> Boolean,
    content: @Composable () -> Unit,
) {
    val window = LocalAwtWindow.current
    val dark = isDesktopInDarkTheme()
    val color = MaterialTheme.colorScheme.background
    val density = LocalDensity.current.density
    val global = home.homeWallpaperEffectScope == HomeWallpaperEffectScope.GLOBAL
    val sourceUri = wallpaperUri.takeIf { (showHomeWallpaper || global) && isStaticHomeWallpaperUri(it) }.orEmpty()
    val appearance = remember(sourceUri, home.homeWallpaperEffectMode, dark, isDataSaverActive, global) {
        resolveHomeWallpaperBackdropAppearance(sourceUri.isNotBlank(), home.homeWallpaperEffectMode,
            dark, isDataSaverActive, globalWallpaper = global)
    }
    val renderer = remember { probeDesktopWindowsGlassRenderer() }
    val latestOwns by rememberUpdatedState(owns)
    // Root ownership alone owns the long-lived layer/lease. URI/configuration and
    // measured size only invalidate its ACK, never page/editor/graphics resources.
        val lease = remember(sourceOwner) { DesktopWindowsGlassSourceLease() }
        val backdrop = key(sourceOwner) { rememberLayerBackdrop() }
        val source = remember(sourceUri, home.homeWallpaperEffectMode, isDataSaverActive, density) { Any() }
        val latestSource by rememberUpdatedState(source)
        var acknowledgedDraw by remember(lease) { mutableStateOf<DesktopWindowsGlassDrawAck?>(null) }
        DisposableEffect(lease) { onDispose { lease.alive.set(false) } }
        val sourceReady = acknowledgedDraw?.let { it.source === source && it.epoch == lease.epoch.get() &&
            it.size == lease.size.get() && it.size.width > 0 && it.size.height > 0 } == true
        val material = DesktopWindowsGlassMaterialBinding(backdrop, renderer, sourceReady,
            appearance.visible && sourceUri.isNotBlank(), { lease.alive.get() && latestOwns() }, window)
        val configuration = DesktopWindowsGlassSourceConfiguration(wallpaperUri, home,
            showHomeWallpaper, isDataSaverActive, { lease.alive.get() && latestOwns() })
        CompositionLocalProvider(LocalDesktopWindowsGlassMaterial provides material,
            LocalDesktopWindowsGlassSourceConfiguration provides configuration,
            LocalAppPopupSurfaceRenderer provides DesktopWindowsPopupSurfaceRenderer) {
            Box(Modifier.fillMaxSize()) {
                HomeWallpaperBackdrop(sourceUri, appearance, color, isDataSaverActive,
                    playbackEnabled = false,
                    modifier = Modifier.fillMaxSize().onSizeChanged { size ->
                        if (lease.size.getAndSet(size) != size) lease.epoch.incrementAndGet()
                    }.drawWithContent {
                        drawContent() // includes successful layerBackdrop recording
                        val epoch = lease.epoch.get()
                        val drawnSize = IntSize(size.width.toInt(), size.height.toInt())
                        if (renderer.supported && lease.alive.get() && latestOwns() &&
                            drawnSize.width > 0 && drawnSize.height > 0 &&
                            (acknowledgedDraw?.source !== source || acknowledgedDraw?.epoch != epoch ||
                                acknowledgedDraw?.size != drawnSize)) {
                            lease.pendingDraw.set(DesktopWindowsGlassDrawAck(source, epoch, drawnSize))
                            scheduleDesktopWindowsGlassAck(lease, { latestSource },
                                { lease.alive.get() && latestOwns() }, { acknowledgedDraw = it })
                        }
                    }.layerBackdrop(backdrop))
                content()
            }
        }
}

/** Shared Windows panel material. No second shader implementation is rendered:
 * the already-produced ORIGINAL FloatingDockChrome/Lens/Vibrancy/InnerShadow run
 * against the actual Root background layer and original BILIPAI_TUNED tuning. */
@Composable
internal fun DesktopWindowsGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable () -> Unit,
) {
    val material = desktopWindowsGlassSurfaceMaterial(modifier, shape, MaterialTheme.colorScheme.surfaceContainerLow)
    // One stable Surface/content callsite across preference, ACK and resize.
    Surface(material.modifier, shape = shape,
        color = if (material.enabled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        content = content)
}

internal data class DesktopWindowsGlassSurfaceMaterial(val modifier: Modifier, val enabled: Boolean)

/** Shared original material projection for both panels and AppPopupSurface.
 * A native child must supply its own layer; inherited Root coordinates are unsafe. */
@Composable
internal fun desktopWindowsGlassSurfaceMaterial(
    modifier: Modifier,
    shape: Shape,
    containerColor: Color,
): DesktopWindowsGlassSurfaceMaterial {
    val material = LocalDesktopWindowsGlassMaterial.current
    val tuning = LocalLiquidGlassRenderConfig.current.tuning
    val enabled = LocalAppThemeConfig.current.liquidGlassEnabled && material != null &&
        material.window === LocalAwtWindow.current && material.renderer.supported &&
        material.sourceReady && material.owns()
    val base = modifier.excludeFromLiquidBackground()
    val surfaceModifier = if (enabled) base.biliPaiFloatingDockShell(
        backdrop = material!!.backdrop,
        containerColor = containerColor,
        pressProgress = 0f,
        shape = shape,
        enabled = true,
        drawLens = true,
        liquidGlassTuning = tuning,
    ).innerShadow(shape) { InnerShadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.15f)) } else base
    return DesktopWindowsGlassSurfaceMaterial(surfaceModifier, enabled)
}
