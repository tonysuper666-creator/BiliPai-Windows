package com.bilipai.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.plugin.js.*
import com.bilipai.desktop.plugins.js.DesktopJsPluginRepository
import kotlinx.coroutines.*
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage

/** The original candidate order and error fallback are retained; guest local paths aren't host files. */
@Composable
internal fun DesktopJsMediaImage(repository: DesktopJsPluginRepository, pluginId: String,
    item: BiliPaiJsMediaItem, revision: Long, networkGranted: Boolean) {
    val candidates = remember(item) { resolveBiliPaiJsMediaImageCandidates(item) }
    var loading by remember(candidates, revision, networkGranted) { mutableStateOf(candidates.isNotEmpty() && networkGranted) }
    var image by remember(candidates, revision, networkGranted) { mutableStateOf<SkiaImage?>(null) }
    LaunchedEffect(repository, pluginId, candidates, revision, networkGranted) {
        if (!networkGranted) return@LaunchedEffect
        var owned: SkiaImage? = null
        try {
            owned = loadDesktopJsMediaImage(candidates) { repository.mediaImage(pluginId, it) }
            image = owned; owned = null
        } finally { owned?.close(); loading = false }
    }
    DisposableEffect(image) { val owned = image; onDispose { owned?.close() } }
    Surface(Modifier.size(96.dp, 56.dp), shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant) {
        val current = image
        if (current != null) Image(current.toComposeImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(contentAlignment = Alignment.Center) {
            when {
                candidates.isEmpty() -> Text("无图", style = MaterialTheme.typography.labelSmall)
                !networkGranted -> Text("需网络权限", style = MaterialTheme.typography.labelSmall)
                loading -> DesktopLoadingIndicator(size = 18.dp)
                else -> Text("失败", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

internal suspend fun loadDesktopJsMediaImage(candidates: List<String>, fetch: suspend (String) -> ByteArray): SkiaImage? {
    for (candidate in candidates) {
        var owned: SkiaImage? = null
        try {
            val bytes = fetch(candidate)
            withContext(Dispatchers.IO) { owned = decodeDesktopJsImage(bytes) }
            currentCoroutineContext().ensureActive()
            return owned.also { owned = null }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { /* The next original candidate is attempted on real load/decode failure. */ }
        finally { owned?.close() }
    }
    return null
}

/** Inspect encoded metadata before allocating decoded pixels; this thumbnail is the first real frame. */
internal fun decodeDesktopJsImage(bytes: ByteArray): SkiaImage {
    require(bytes.size <= 2_097_152) { "JS 媒体图片超过大小限制" }
    Data.makeFromBytes(bytes, 0, bytes.size).use { data ->
        Codec.makeFromData(data).use { codec ->
            require(codec.width > 0 && codec.height > 0 && codec.width <= 8192 && codec.height <= 8192 &&
                codec.width.toLong() * codec.height <= 4_194_304) { "JS 媒体图片像素超过限制" }
            Bitmap().use { bitmap ->
                require(bitmap.allocN32Pixels(codec.width, codec.height)) { "JS 媒体图片无法分配画布" }
                codec.readPixels(bitmap, 0, -1)
                return SkiaImage.makeFromBitmap(bitmap)
            }
        }
    }
}

internal class DesktopJsImageBindings(val repository: DesktopJsPluginRepository, val pluginId: String,
    val revision: Long, val ownedNetworkGrant: () -> Boolean)
internal val LocalDesktopJsImageBindings = staticCompositionLocalOf<DesktopJsImageBindings> {
    error("JS image requires the original mounted module owner")
}

/** Existing image decoder/network owner; original full card retains its candidate/error order. */
@Composable
internal fun DesktopJsAuthorizedImage(model: String, contentDescription: String?, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit, onError: () -> Unit = {}) {
    val bindings = LocalDesktopJsImageBindings.current
    var image by remember(model, bindings) { mutableStateOf<SkiaImage?>(null) }
    val latestError by rememberUpdatedState(onError)
    LaunchedEffect(model, bindings) {
        if (!bindings.ownedNetworkGrant()) return@LaunchedEffect
        var owned: SkiaImage? = null
        try {
            owned = loadDesktopJsMediaImage(listOf(model)) { bindings.repository.mediaImage(bindings.pluginId, it) }
            currentCoroutineContext().ensureActive()
            if (bindings.ownedNetworkGrant() && bindings.repository.host.executionRevision.value == bindings.revision) {
                image = owned; owned = null
                if (image == null) latestError()
            }
        } finally { owned?.close() }
    }
    DisposableEffect(image) { val owned = image; onDispose { owned?.close() } }
    image?.let { Image(it.toComposeImageBitmap(), contentDescription, modifier, contentScale = contentScale) }
}
