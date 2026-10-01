package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import com.android.purebilibili.feature.profile.resolveProfileSkinVideoRepeatMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Profile delegates to the existing retained media owner. Its original wallpaper uses RESUMED;
 * its skin video uses STARTED and the sole original repeat policy. No main/audio player is acquired.
 * Original WallpaperMedia applies alignment to its image/GIF branch; video remains PlayerView ZOOM. */
internal class DesktopOriginalProfileMedia(
    private val owner: DesktopHomeMediaLifetime,
    private val feedback: (String) -> Unit,
) : DesktopProfileMedia {
    override suspend fun isVideoUri(uri: String): Boolean = withContext(Dispatchers.IO) {
        check(owner.isOwned()) { "Profile media owner retired" }
        desktopHomeWallpaperIsVideo(uri, ::desktopHomeWallpaperFile)
    }
    @Composable override fun wallpaper(uri: String, imageModel: Any, alignment: Alignment,
        playing: Boolean, video: Boolean, modifier: Modifier) {
        if (video) DesktopHomeMpvTexture(owner, uri, true, playing, null, modifier, feedback)
        else DesktopHomeWallpaperImages(owner, uri, imageModel, playing, modifier,
            ::desktopHomeWallpaperFile, feedback, alignment)
    }
    @Composable override fun skinVideo(path: String, playMode: String?, playbackEnabled: Boolean, modifier: Modifier) {
        DesktopHomeMpvTexture(owner, path, true, playbackEnabled, null, modifier, feedback,
            repeatOne = resolveProfileSkinVideoRepeatMode(playMode) != 0,
            minimumPlayingState = Lifecycle.State.STARTED)
    }
}
