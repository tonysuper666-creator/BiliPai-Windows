@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import com.android.purebilibili.core.ui.LocalGlobalWallpaperBackdropVisible
import com.android.purebilibili.core.ui.blur.ChromeBackdropSource
import com.android.purebilibili.core.ui.wallpaper.LocalGlobalWallpaperBackdropSource
import com.android.purebilibili.feature.home.components.cards.LocalHomeCardFrostedGlassEnabled
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/** The original media-card surface borrows Root's completed wallpaper layer.
 * No new recording modifier, graphics layer, renderer, wallpaper or preference store. */
@Composable
internal fun DesktopFavoriteWallpaperBackdrop(environment: DesktopFavoriteEnvironment,
    content: @Composable () -> Unit) {
    val preferences = environment.favoriteHomeCardVisualPreferences
    val settings by preferences.settings.collectAsState(preferences.initialSettings())
    val material = LocalDesktopWindowsGlassMaterial.current
    val window = LocalAwtWindow.current
    val layer = material?.backdrop as? LayerBackdrop
    val ready = window != null && material != null && material.window === window &&
        material.renderer.supported && material.sourceReady && material.sourceHasWallpaper &&
        material.owns() && layer != null
    val recorded = rememberUpdatedState(ready)
    val source = remember(layer, window) {
        if (layer != null && window != null) ChromeBackdropSource(layer, Modifier, recorded) else null
    }
    CompositionLocalProvider(
        LocalGlobalWallpaperBackdropVisible provides ready,
        LocalGlobalWallpaperBackdropSource provides source,
        LocalHomeCardFrostedGlassEnabled provides settings.homeCardFrostedGlassEnabled,
        content = content,
    )
}

/** A popup cannot borrow its parent's screen coordinates or ready acknowledgment. */
@Composable
internal fun desktopFavoriteWallpaperEffectsSupported(): Boolean {
    val material = LocalDesktopWindowsGlassMaterial.current ?: return false
    val window = LocalAwtWindow.current ?: return false
    val effects = remember(material.renderer, window) { desktopDetailRenderEffectsSupported() }
    return material.window === window && material.renderer.supported && material.sourceReady &&
        material.sourceHasWallpaper && material.owns() && effects
}
