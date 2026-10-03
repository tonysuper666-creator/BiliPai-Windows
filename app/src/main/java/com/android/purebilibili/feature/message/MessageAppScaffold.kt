package com.android.purebilibili.feature.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.core.store.HomeWallpaperEffectMode
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.core.ui.ImmersiveAppScaffold
import com.android.purebilibili.core.ui.LocalGlobalWallpaperBackdropVisible
import com.android.purebilibili.feature.home.HomeWallpaperBackdrop
import com.android.purebilibili.feature.home.resolveHomeWallpaperBackdropAppearance
import com.android.purebilibili.feature.home.resolveHomeWallpaperUri

/** Capture the same opaque wallpaper + content composite that the message page displays. */
@Composable
internal fun MessageAppScaffold(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    blurContentReady: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val context = LocalContext.current
    val wallpaperEnabled = LocalGlobalWallpaperBackdropVisible.current
    val homeUri by SettingsManager.getHomeWallpaperUri(context)
        .collectAsStateWithLifecycle(initialValue = "")
    val splashUri by SettingsManager.getSplashWallpaperUri(context)
        .collectAsStateWithLifecycle(initialValue = "")
    val mode by SettingsManager.getHomeWallpaperEffectMode(context)
        .collectAsStateWithLifecycle(initialValue = HomeWallpaperEffectMode.SOFT_BLUR)
    val uri = resolveHomeWallpaperUri(homeUri, splashUri)
    val baseColor = MaterialTheme.colorScheme.background
    val dataSaver = remember(context) { SettingsManager.isDataSaverActive(context) }
    val appearance = remember(wallpaperEnabled, uri, mode, baseColor, dataSaver) {
        resolveHomeWallpaperBackdropAppearance(
            hasWallpaper = wallpaperEnabled && uri.isNotBlank(),
            effectMode = mode,
            isDarkTheme = baseColor.luminance() < 0.5f,
            isDataSaverActive = dataSaver,
            globalWallpaper = true,
        )
    }
    ImmersiveAppScaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBarSurfaceColor = baseColor,
        topBar = topBar,
        blurContentReady = blurContentReady,
    ) { padding ->
        // This subtree is inside the scaffold's source; top chrome is its sibling.
        Box(Modifier.fillMaxSize()) {
            HomeWallpaperBackdrop(
                wallpaperUri = uri,
                appearance = appearance,
                baseColor = baseColor,
                isDataSaverActive = dataSaver,
            )
            content(padding)
        }
    }
}
