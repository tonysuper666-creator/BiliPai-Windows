package com.android.purebilibili.core.ui.wallpaper
import com.bilipai.desktop.ui.LocalDesktopProfileEnvironment

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared image/GIF/video renderer. Video uses a TextureView so Compose clipping works. */
@Composable
internal fun WallpaperMedia(
    uri: String,
    modifier: Modifier = Modifier,
    imageModel: Any = uri,
    alignment: Alignment = Alignment.Center,
    playbackEnabled: Boolean = true,
    video: Boolean = isVideoWallpaper(uri),
) {
    val environment = LocalDesktopProfileEnvironment.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val playing = foreground && playbackEnabled
    // Original file-extension/MIME policy, using the actual Windows URI resolver.
    val resolvedVideo by produceState(video, uri, video) {
        value = video || environment.media.isVideoUri(uri)
    }
    // Required physical carrier owns the same existing Root media lease/image actor.
    environment.media.wallpaper(uri, imageModel, alignment, playing, resolvedVideo, modifier)
}
