package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** Required media binding. Root owns the existing Listen session, preview MPV lifetime and feedback.
 * A preview surface is an independent muted/non-muted native lease; disposal must stop only its source.
 * First-frame receipt must be emitted by the native renderer, never by composition/mount. */
internal class DesktopHomeMediaPorts(
    val musicOverlayVisible:StateFlow<Boolean>,
    val previewSurface:@Composable (url:String,muted:Boolean,onFirstFrame:(()->Unit)?,modifier:Modifier)->Unit,
    val feedback:(String)->Unit,
    val wallpaper:DesktopHomeWallpaperPort,
)
internal val LocalDesktopHomeMediaPorts=staticCompositionLocalOf<DesktopHomeMediaPorts>{
    error("Home requires Root's current music, actual native preview and feedback consumers")
}

/** Platform capabilities are provided by Root, not a simulated Android SDK/default. */
internal class DesktopHomePlatform(val supportsNativeParticleDissolve:Boolean,val supportsHomeChromeLiquidGlass:Boolean,val supportsDirectHazeLiquidGlassFallback:Boolean,val legacyTopChromeSafetyGapRequired:Boolean, val supportsRenderEffectBackedHaze:Boolean, val recreateHazeOnResume:Boolean, val background:DesktopHomeWindowBackgroundPort, val deviceCornerRadiusPx:Float, val debugFrameMetricsEnabled:Boolean)
internal val LocalDesktopHomePlatform=staticCompositionLocalOf<DesktopHomePlatform>{
    error("Home requires the current window renderer capability binding")
}
internal object DesktopHomeClock { fun elapsedRealtime():Long=System.nanoTime()/1_000_000L }

internal interface DesktopHomeWallpaperPort {
 @Composable fun wallpaperSurface(uri:String,imageModel:Any,playbackEnabled:Boolean,modifier:Modifier)
}

internal interface DesktopHomeWindowBackgroundPort {
 val isInBackground:Boolean
 interface Listener {fun onEnterBackground();fun onEnterForeground()}
 fun addListener(listener:Listener)
 fun removeListener(listener:Listener)
}
