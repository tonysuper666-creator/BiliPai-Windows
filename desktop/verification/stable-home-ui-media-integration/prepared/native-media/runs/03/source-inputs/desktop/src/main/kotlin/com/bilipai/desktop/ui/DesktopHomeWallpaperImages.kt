package com.bilipai.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import com.android.purebilibili.core.ui.wallpaper.isVideoWallpaper
import com.bilipai.desktop.plugins.DesktopAnimatedSkinImage
import kotlinx.coroutines.*
import org.jetbrains.skia.Rect
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Original extension policy + real Windows file MIME. Android content: requires a real migrated file chooser URI. */
internal fun desktopHomeWallpaperIsVideo(uri: String, fileForUri: (String) -> Path?): Boolean =
    isVideoWallpaper(uri) || fileForUri(uri)?.let { runCatching { Files.probeContentType(it)?.startsWith("video/") == true }.getOrDefault(false) } == true

/** Windows local-document boundary; no guessed Pictures directory or alternate HTTP client. */
internal fun desktopHomeWallpaperFile(uri: String): Path? {
    val parsed = runCatching { URI(uri) }.getOrNull()
    return when {
        parsed?.scheme.equals("file", true) -> Path.of(parsed)
        Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(uri) -> Path.of(uri)
        parsed?.scheme == null -> Path.of(uri)
        else -> null // content:/http(s): are not local files; do not pretend Android SAF grants survived migration.
    }
}

/** Original static AsyncImage model/crop + actual GIF/WebP codec animation, paused with the same lifecycle/setting. */
@Composable
internal fun DesktopHomeWallpaperImages(owner: DesktopHomeMediaLifetime, uri: String, imageModel: Any,
    playbackEnabled: Boolean, modifier: Modifier, fileForUri: (String) -> Path?, feedback: (String) -> Unit) {
    var animation by remember(owner, uri) { mutableStateOf<DesktopAnimatedSkinImage?>(null) }
    val feedbackNow by rememberUpdatedState(feedback)
    LaunchedEffect(owner, uri) {
        var opened: DesktopAnimatedSkinImage? = null
        try {
            val path = fileForUri(uri)
            if (path != null) withContext(Dispatchers.IO) {
                val caller = currentCoroutineContext()
                check(owner.isOwned()) { "Home media owner retired" }
                Files.newInputStream(path).use { input ->
                    val output = ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        caller.ensureActive(); check(owner.isOwned()) { "Home media owner retired" }
                        val n = input.read(chunk); if (n < 0) break
                        require(output.size().toLong() + n <= 32L * 1024 * 1024) { "壁纸图片超过大小限制" }
                        output.write(chunk, 0, n)
                    }
                    caller.ensureActive(); check(owner.isOwned()) { "Home media owner retired" }
                    opened = DesktopAnimatedSkinImage.decodeOrNull(output.toByteArray())
                }
            }
            currentCoroutineContext().ensureActive()
            owner.commitOwned { animation = opened; opened = null }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { owner.commitOwned { feedbackNow(failure.message ?: "壁纸图片无法解码") } }
        finally { opened?.close() }
    }
    DisposableEffect(animation) { val own = animation; onDispose { own?.close() } }
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val playing = playbackEnabled && state.isAtLeast(Lifecycle.State.RESUMED)
    var seconds by remember(animation) { mutableDoubleStateOf(0.0) }
    LaunchedEffect(animation, playing) {
        if (animation != null && playing) {
            var previous = 0L
            while (owner.isOwned()) withFrameNanos { now ->
                if (previous != 0L) seconds += (now - previous) / 1_000_000_000.0
                previous = now
            }
        }
    }
    val current = animation
    if (current == null) AsyncImage(model = imageModel, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    else Canvas(modifier) {
        if (owner.isOwned() && size.width > 0f && size.height > 0f) drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val scale = maxOf(size.width / current.width, size.height / current.height)
            val width = current.width * scale; val height = current.height * scale
            canvas.save()
            try {
                canvas.clipRect(Rect.makeWH(size.width, size.height))
                current.render(canvas, Rect.makeXYWH((size.width - width) / 2f, (size.height - height) / 2f, width, height), seconds)
            } finally { canvas.restore() }
        }
    }
}
