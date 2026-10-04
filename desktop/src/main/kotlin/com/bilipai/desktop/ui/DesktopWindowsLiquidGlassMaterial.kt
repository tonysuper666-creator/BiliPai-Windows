package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
            appearance.visible && sourceUri.isNotBlank(), { lease.alive.get() && latestOwns() })
        CompositionLocalProvider(LocalDesktopWindowsGlassMaterial provides material) {
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
    val material = LocalDesktopWindowsGlassMaterial.current
    val tuning = LocalLiquidGlassRenderConfig.current.tuning
    val enabled = LocalAppThemeConfig.current.liquidGlassEnabled && material != null &&
        material.renderer.supported && material.sourceReady && material.owns()
    val base = modifier.excludeFromLiquidBackground()
    val surfaceModifier = if (enabled) base.biliPaiFloatingDockShell(
        backdrop = material!!.backdrop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        pressProgress = 0f,
        shape = shape,
        enabled = true,
        drawLens = true,
        liquidGlassTuning = tuning,
    ).innerShadow(shape) { InnerShadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.15f)) } else base
    // One stable Surface/content callsite for ON/OFF, first ACK and resize. Only
    // material/color changes; comment fields and open menus keep composition/focus.
    Surface(surfaceModifier, shape = shape,
        color = if (enabled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        content = content)
}

// Copyright 2026, compose-miuix-ui contributors; Apache-2.0.
// Original Lens.kt constants verbatim, original LF SHA256: 579ced36f6e1f15cb9d906b42c4cf64180c598a7f5083189c12cc30ceea00e8e
// Compiled only for capability preflight; rendering calls original lens().
private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}
"""

private const val ROUNDED_RECT_REFRACTION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    float clampedX = clamp(x, 0.0, 1.0);
    return 1.0 - sqrt(max(1.0 - clampedX * clampedX, 0.0));
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}
"""

private const val ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    float clampedX = clamp(x, 0.0, 1.0);
    return 1.0 - sqrt(max(1.0 - clampedX * clampedX, 0.0));
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    // 物理光学三通道（RGB）波长色散：采样数从 7 次降低至 3 次（减少 57% GPU 纹理采样），
    // 消除冗余多次采样的混色浑浊感，色散边缘更清澈通透，大幅降低显存带宽与 TMU 压力。
    half r = content.eval(refractedCoord + dispersedCoord).r;
    half4 gSample = content.eval(refractedCoord);
    half b = content.eval(refractedCoord - dispersedCoord).b;

    return half4(r, gSample.g, b, gSample.a);
}
"""
