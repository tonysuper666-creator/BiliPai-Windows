package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.core.ui.AppPopupSurfaceRenderer
import com.android.purebilibili.core.ui.AppPopupSurfaceType
import com.android.purebilibili.core.ui.components.AppSurface

/** A rendering projection of Root's existing wallpaper/settings lease. It never
 * writes preferences, decodes native video, or authorizes a settings operation. */
internal data class DesktopWindowsGlassSourceConfiguration(
    val wallpaperUri: String,
    val home: HomeSettings,
    val showHomeWallpaper: Boolean,
    val isDataSaverActive: Boolean,
    val owns: () -> Boolean,
)

internal val LocalDesktopWindowsGlassSourceConfiguration =
    staticCompositionLocalOf<DesktopWindowsGlassSourceConfiguration?> { null }

/** The existing AppPopupSurface hook uses the SAME original FloatingDock/Lens
 * modifier as the player panels. Fallback retains AppSurface's actual selected
 * Material3/Miuix implementation, shape, content color and tonal elevation. */
internal object DesktopWindowsPopupSurfaceRenderer : AppPopupSurfaceRenderer {
    @Composable
    override fun Render(
        type: AppPopupSurfaceType,
        modifier: Modifier,
        shape: Shape,
        containerColor: Color,
        contentColor: Color,
        tonalElevation: Dp,
        content: @Composable () -> Unit,
    ) {
        val material = desktopWindowsGlassSurfaceMaterial(modifier, shape, containerColor)
        AppSurface(
            modifier = material.modifier,
            shape = shape,
            color = if (material.enabled) Color.Transparent else containerColor,
            contentColor = contentColor,
            tonalElevation = if (material.enabled) 0.dp else tonalElevation,
            content = content,
        )
    }
}

/** Each owned popup records its OWN original wallpaper/solid background layer.
 * Root's layer coordinates and readability sample must not cross native windows.
 * Original foreground content is never recorded into the source backdrop. */
@Composable
internal fun DesktopWindowsPopupMaterialHost(
    sourceOwner: Any,
    owns: () -> Boolean,
    content: @Composable () -> Unit,
) {
    val source = LocalDesktopWindowsGlassSourceConfiguration.current
    val latestOwns by rememberUpdatedState(owns)
    CompositionLocalProvider(LocalDesktopLiquidReadabilityEnvironment provides null) {
        DesktopWindowsGlassBackgroundHost(
            sourceOwner = sourceOwner,
            wallpaperUri = source?.wallpaperUri.orEmpty(),
            home = source?.home ?: HomeSettings(),
            showHomeWallpaper = source?.showHomeWallpaper ?: false,
            isDataSaverActive = source?.isDataSaverActive ?: false,
            owns = { source != null && source.owns() && latestOwns() },
            content = content,
        )
    }
}

/** Exact Dialog platform port for the original cache confirmation body. The
 * original confirm/cancel callbacks retain all cache-operation authority. */
@Composable
internal fun DesktopWindowsPopupDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    DesktopWindowsPlayerDialog(
        title = "清理缓存",
        onDismissRequest = onDismissRequest,
        dismissOnEscape = properties.dismissOnBackPress,
        preferredHeightDp = 600,
    ) {
        DesktopDetailWindow {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
        }
    }
}
