// Original source app/src/main/java/com/android/purebilibili/feature/home/HomeWallpaperBackdrop.kt
// LF SHA256 d966dbd9074fdf29b97785ebf7a724e5635e8098abf2f44c9d2ccf31eb38d2f6
package com.android.purebilibili.feature.home
import coil3.request.crossfade
import com.android.purebilibili.core.ui.MediaContrastPalette
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration
import coil3.compose.LocalPlatformContext as LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import coil3.size.Scale
import com.android.purebilibili.core.ui.wallpaper.isVideoWallpaper
@Composable
internal fun HomeWallpaperBackdrop(
    wallpaperUri: String,
    appearance: HomeWallpaperBackdropAppearance,
    baseColor: Color,
    isDataSaverActive: Boolean = false,
    playbackEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseColor)
    ) {
        if (!appearance.visible) return@Box

        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        val decodeSize = remember(
            configuration.screenWidthDp,
            configuration.screenHeightDp,
            density.density,
            isDataSaverActive,
            appearance.blurRadiusDp
        ) {
            resolveHomeWallpaperDecodeSizePx(
                screenWidthDp = configuration.screenWidthDp,
                screenHeightDp = configuration.screenHeightDp,
                density = density.density,
                isDataSaverActive = isDataSaverActive,
                blurRadiusDp = appearance.blurRadiusDp
            )
        }
        val imageRequest = remember(context, wallpaperUri, decodeSize) {
            val cacheKey = "home_wallpaper_${wallpaperUri.hashCode()}_${decodeSize.first}x${decodeSize.second}"
            ImageRequest.Builder(context)
                .data(wallpaperUri)
                .size(decodeSize.first, decodeSize.second)
                .scale(Scale.FILL)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .crossfade(180)
                .build()
        }
        val imageModifier = Modifier
            .fillMaxSize()
            .then(
                if (appearance.blurRadiusDp > 0f) {
                    Modifier.blur(appearance.blurRadiusDp.dp)
                } else {
                    Modifier
                }
            )

        com.bilipai.desktop.ui.LocalDesktopHomeMediaPorts.current.wallpaper.wallpaperSurface(
            uri = wallpaperUri,
            imageModel = imageRequest,
            playbackEnabled = playbackEnabled && !isDataSaverActive,
            modifier = imageModifier
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(baseColor.copy(alpha = appearance.baseBackgroundAlpha))
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MediaContrastPalette.Scrim.copy(alpha = appearance.scrimAlpha))
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            baseColor.copy(alpha = appearance.bottomScrimAlpha)
                        )
                    )
                )
        )
    }
}

/**
 * The first wallpaper glass path only samples stable image content. Animated media keeps the
 * existing lightweight tint until a frame-aware source is available.
 */

internal fun isStaticHomeWallpaperUri(uri: String): Boolean {
    val extension = uri
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    return !isVideoWallpaper(uri) && extension !in setOf("gif", "webp")
}
