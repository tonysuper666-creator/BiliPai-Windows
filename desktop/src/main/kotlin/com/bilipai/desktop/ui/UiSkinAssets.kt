package com.bilipai.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import com.bilipai.desktop.plugins.DesktopLottieAsset
import com.bilipai.desktop.plugins.DesktopPluginLifecycle
import com.bilipai.desktop.plugins.DesktopAnimatedSkinImage
import com.android.purebilibili.core.plugin.skin.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private data class SkinDecodedAsset(val image: SkiaImage? = null, val bitmap: ImageBitmap? = null,
    val lottie: DesktopLottieAsset? = null, val animatedImage: DesktopAnimatedSkinImage? = null) : AutoCloseable {
    override fun close() { lottie?.close(); animatedImage?.close(); image?.close() }
}

/** Call only with a path returned by DesktopPackageRepository's validated skin state or preview. */
@Composable
fun DesktopUiSkinAsset(path: String, modifier: Modifier = Modifier, playing: Boolean = true,
    contentScale: ContentScale = ContentScale.Fit, videoPlayMode: String? = null,
    loop: Boolean = true, onAnimationFinished: () -> Unit = {},
    onError: (String) -> Unit = {}, onWarning: (String) -> Unit = {}) {
    if (path.substringAfterLast('.', "").equals("mp4", true)) {
        DesktopUiSkinVideo(path, modifier, playing, videoPlayMode, onError)
        return
    }
    val errorCallback by rememberUpdatedState(onError)
    val warningCallback by rememberUpdatedState(onWarning)
    val finishedCallback by rememberUpdatedState(onAnimationFinished)
    var decoded by remember(path) { mutableStateOf<SkinDecodedAsset?>(null) }
    var elapsed by remember(path) { mutableDoubleStateOf(0.0) }
    LaunchedEffect(path) {
        var candidate: SkinDecodedAsset? = null
        try {
            withContext(Dispatchers.IO) {
                val file = Path.of(path)
                require(Files.isRegularFile(file) && Files.size(file) <= 32L * 1024 * 1024) { "皮肤资源文件无效" }
                candidate = when (file.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                    "json" -> SkinDecodedAsset(lottie = DesktopLottieAsset.read(file))
                    "png", "jpg", "jpeg", "webp" -> {
                        val bytes = Files.newInputStream(file).use { it.readNBytes(32 * 1024 * 1024 + 1) }
                        require(bytes.size <= 32 * 1024 * 1024) { "皮肤资源超过大小限制" }
                        val animated = DesktopAnimatedSkinImage.decodeOrNull(bytes)
                        if (animated != null) SkinDecodedAsset(animatedImage = animated)
                        else {
                            val image = SkiaImage.makeFromEncoded(bytes)
                            try {
                                require(image.width.toLong() * image.height <= 16_777_216) { "皮肤图片像素超过限制" }
                                SkinDecodedAsset(image, image.toComposeImageBitmap())
                            } catch (failure: Throwable) { image.close(); throw failure }
                        }
                    }
                    else -> error("不支持的皮肤资源格式")
                }
            }
            candidate?.lottie?.warnings?.takeIf { it.isNotEmpty() }?.let { warningCallback(it.joinToString("；")) }
            decoded = candidate
            candidate = null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (failure: Exception) { errorCallback(failure.message ?: "皮肤资源加载失败") }
        finally { candidate?.close() }
    }
    DisposableEffect(decoded) { val owned = decoded; onDispose { owned?.close() } }
    LaunchedEffect(decoded?.lottie, decoded?.animatedImage, playing, loop) {
        if (!playing || (decoded?.lottie == null && decoded?.animatedImage == null)) return@LaunchedEffect
        var previous: Long? = null
        while (true) {
            withFrameNanos { now ->
                if (DesktopPluginLifecycle.isAppVisible()) previous?.let { elapsed += (now - it).coerceAtLeast(0L) / 1_000_000_000.0 }
                previous = now
            }
            val duration = decoded?.lottie?.durationSeconds ?: decoded?.animatedImage?.durationSeconds ?: Double.POSITIVE_INFINITY
            if (!loop && elapsed >= duration) { finishedCallback(); break }
        }
    }
    val asset = decoded
    asset?.bitmap?.let { Image(it, null, modifier, contentScale = contentScale) }
    asset?.lottie?.let { animation ->
        Canvas(modifier) { if (size.width > 0 && size.height > 0) drawIntoCanvas { animation.render(it.nativeCanvas, size.width, size.height, elapsed, loop) } }
    }
    asset?.animatedImage?.let { animation ->
        Canvas(modifier) {
            if (size.width > 0 && size.height > 0) drawIntoCanvas { canvas ->
                val scale = contentScale.computeScaleFactor(Size(animation.width.toFloat(), animation.height.toFloat()), size)
                val width = animation.width * scale.scaleX; val height = animation.height * scale.scaleY
                val native = canvas.nativeCanvas
                native.save()
                try {
                    native.clipRect(org.jetbrains.skia.Rect.makeWH(size.width, size.height))
                    animation.render(native, org.jetbrains.skia.Rect.makeXYWH((size.width - width) / 2, (size.height - height) / 2, width, height), elapsed, loop)
                } finally { native.restore() }
            }
        }
    }
}

@Composable
fun DesktopUiSkinDecoration(surface: UiSkinSurface, selector: (UiSkinAssets) -> String?, modifier: Modifier = Modifier,
    playing: Boolean = true, loop: Boolean = true, onAnimationFinished: () -> Unit = {},
    onError: (String) -> Unit = {}, onWarning: (String) -> Unit = {}) {
    val skin = LocalUiSkinState.current
    skin.assetPath(surface, selector)?.let { path ->
        DesktopUiSkinAsset(path, modifier, playing, videoPlayMode = skin.activeSkin?.manifest?.motion?.profileVideoPlayMode,
            loop = loop, onAnimationFinished = onAnimationFinished, onError = onError, onWarning = onWarning)
    }
}
