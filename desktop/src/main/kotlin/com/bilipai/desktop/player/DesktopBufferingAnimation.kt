package com.bilipai.desktop.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.android.purebilibili.core.ui.LocalSystemReduceMotion
import com.android.purebilibili.core.ui.rememberVisualWorkActive
import com.bilipai.desktop.plugins.DesktopAnimatedSkinImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.*

/** Prepared on a worker, then owned exclusively by the composition's UI thread. */
internal interface DesktopBufferingFrames : AutoCloseable {
    val width: Int
    val height: Int
    val animated: Boolean
    fun draw(canvas: org.jetbrains.skia.Canvas, destination: Rect, seconds: Double)
}

internal fun loadDesktopBufferingFrames(
    reduceMotion: Boolean,
    read: (String) -> ByteArray = { path ->
        checkNotNull(DesktopBufferingFrames::class.java.getResourceAsStream(path)) {
            "Missing packaged upstream buffering resource: $path"
        }.use { it.readBytes() }
    },
    observePreparation: (String) -> Unit = {},
    decode: (ByteArray) -> DesktopAnimatedSkinImage? = DesktopAnimatedSkinImage::decodeOrNull,
): DesktopBufferingFrames {
    // makeFromEncoded can defer pixel decoding. Materialize the static pixels here too.
    observePreparation("readStaticBytes")
    val staticBytes = read("/video-buffering/bili_tv_buffering_static.png")
    observePreparation("decodeStaticPixels")
    val fallback = Image.makeFromEncoded(staticBytes).use { encoded ->
        Bitmap().use { pixels ->
            check(pixels.allocN32Pixels(encoded.width, encoded.height))
            check(encoded.readPixels(pixels)) { "Unable to decode upstream static buffering image" }
            Image.makeFromBitmap(pixels)
        }
    }
    var animation: DesktopAnimatedSkinImage? = null
    try {
        if (!reduceMotion) {
            try {
                observePreparation("readAnimationBytes")
                val bytes = read("/video-buffering/bili_tv_buffering.webp")
                observePreparation("constructDecoder")
                animation = decode(bytes)
                animation?.let { value ->
                    observePreparation("decodeFrameZero")
                    // render decodes frame zero and retains it in the existing decoder.
                    Surface.makeRasterN32Premul(value.width, value.height).use { surface ->
                        value.render(surface.canvas, Rect.makeWH(value.width.toFloat(), value.height.toFloat()), 0.0)
                    }
                }
            } catch (_: Exception) { animation?.close(); animation = null }
        }
        val readyAnimation = animation
        return object : DesktopBufferingFrames {
            override val width = readyAnimation?.width ?: fallback.width
            override val height = readyAnimation?.height ?: fallback.height
            override val animated = readyAnimation != null
            override fun draw(canvas: org.jetbrains.skia.Canvas, destination: Rect, seconds: Double) {
                if (readyAnimation != null) readyAnimation.render(canvas, destination, seconds)
                else canvas.drawImageRect(fallback, destination)
            }
            override fun close() { readyAnimation?.close(); fallback.close() }
        }
    } catch (failure: Throwable) { animation?.close(); fallback.close(); throw failure }
}

private class BufferingComposition {
    var frames by mutableStateOf<DesktopBufferingFrames?>(null)
}

@Composable
internal fun DesktopBufferingAnimation(
    tint: Color, modifier: Modifier, size: Dp,
    load: (Boolean) -> DesktopBufferingFrames = { loadDesktopBufferingFrames(it) },
) {
    val reduceMotion = LocalSystemReduceMotion.current
    val owned = remember(reduceMotion) { BufferingComposition() }
    DisposableEffect(owned) { onDispose { owned.frames?.close(); owned.frames = null } }
    LaunchedEffect(owned) {
        var pending: DesktopBufferingFrames? = null
        try {
            withContext(Dispatchers.Default) {
                // Keep ownership outside withContext: its prompt cancellation can discard a return value.
                pending = load(reduceMotion)
                ensureActive()
            }
            ensureActive()
            owned.frames = checkNotNull(pending)
            pending = null // UI owns the result from this point; no suspension during handoff.
        } finally {
            pending?.let { result -> withContext(NonCancellable + Dispatchers.Default) { result.close() } }
        }
    }
    val frames = owned.frames
    val active = rememberVisualWorkActive() && !reduceMotion
    var seconds by remember(frames) { mutableDoubleStateOf(0.0) }
    LaunchedEffect(frames, active) {
        if (active && frames?.animated == true) {
            var previous: Long? = null
            while (true) withFrameNanos { now ->
                previous?.let { seconds += (now - it).coerceAtLeast(0) / 1_000_000_000.0 }
                previous = now
            }
        }
    }
    Canvas(modifier.size(size).semantics { contentDescription = "加载中" }) {
        // An empty loading canvas keeps layout and accessibility stable without a substitute image.
        val ready = frames ?: return@Canvas
        val scale = minOf(this.size.width / ready.width, this.size.height / ready.height)
        val rect = Rect.makeXYWH((this.size.width - ready.width * scale) / 2,
            (this.size.height - ready.height * scale) / 2, ready.width * scale, ready.height * scale)
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            ColorFilter.makeBlend(tint.toArgb(), BlendMode.SRC_IN).use { filter ->
                Paint().use { paint ->
                    paint.colorFilter = filter
                    canvas.saveLayer(rect, paint)
                    try { ready.draw(canvas, rect, seconds) } finally { canvas.restore() }
                }
            }
        }
    }
}
