package com.android.purebilibili.feature.video.ambient

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Destination ownership remains with the source until the platform callback finishes. */
internal interface AmbientFrameSource {
    suspend fun capture(width: Int, height: Int): Bitmap?
    fun release()
}

internal class ViewAmbientFrameSource(private val view: View) : AmbientFrameSource {
    private var scratch: Bitmap? = null
    override suspend fun capture(width: Int, height: Int): Bitmap? = withContext(Dispatchers.Main.immediate) {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return@withContext null
        val destination = scratch?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                scratch?.recycle(); scratch = it
            }
        when (view) {
            is TextureView -> if (view.isAvailable) view.getBitmap(destination) else null
            is SurfaceView -> {
                if (!view.holder.surface.isValid) return@withContext null
                // PixelCopy has no cancellation API. Wait for completion before returning its
                // buffer, including on lifecycle cancellation. The controller discards late data.
                withContext(NonCancellable) {
                    suspendCoroutine { continuation ->
                        try {
                            PixelCopy.request(view, destination, { result ->
                                continuation.resume(destination.takeIf { result == PixelCopy.SUCCESS })
                            }, Handler(Looper.getMainLooper()))
                        } catch (_: IllegalArgumentException) { continuation.resume(null) }
                    }
                }
            }
            else -> null
        }
    }
    override fun release() { scratch?.recycle(); scratch = null }
}

internal data class AmbientRenderFrame(
    val raw: ImageBitmap,
    val glow: ImageBitmap,
    val atMs: Long,
    val transitionMs: Long = 120L,
    val refreshGeneration: Int = 0,
)

@Stable
internal class AmbientPresentation {
    var previous by mutableStateOf<AmbientRenderFrame?>(null)
    var current by mutableStateOf<AmbientRenderFrame?>(null)
    var visible by mutableStateOf(false)
    var requiredRefresh by mutableStateOf(0)
    var epoch by mutableStateOf(0L)
    var layoutEnabled by mutableStateOf(false)
    var inlineBoundsInWindow by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    var visibilityGate: () -> Boolean by mutableStateOf({ true })
    var videoBoundsInWindow: () -> androidx.compose.ui.geometry.Rect? by mutableStateOf({ null })
    var opacity by mutableStateOf(0.30f)
    var aspectRatio by mutableStateOf(16f / 9f)
    var failureFadeAt by mutableStateOf<Long?>(null)
    fun clear() { epoch++; previous = null; current = null; failureFadeAt = null }
}

internal data class AmbientRunPolicy(
    val active: Boolean,
    val glow: Boolean,
    val playing: Boolean,
    val saving: Boolean,
    val refreshGeneration: Int,
    val diagnosticLogging: Boolean,
)

internal class AmbientFrameController(
    private val presentation: AmbientPresentation,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val sessionMutex = Mutex()
    private var generation = 0L
    fun invalidate() { generation++; presentation.clear() }

    suspend fun run(source: AmbientFrameSource, width: Int, height: Int, policy: () -> AmbientRunPolicy) {
        val session = ++generation
        sessionMutex.withLock {
            val analyzer = AmbientFrameAnalyzer()
            val (w, h) = ambientSampleSize(width, height)
            val pixels = IntArray(w * h)
            var failures = 0
            var lastRefresh = -1
            var frames = 0
            var reportAt = clock()
            try {
                while (currentCoroutineContext().isActive && generation == session) {
                    val config = policy()
                    if (!config.active) { delay(100); continue }
                    if (!shouldSampleAmbientFrame(config.playing, config.refreshGeneration, lastRefresh)) { delay(100); continue }
                    val started = clock()
                    val sample = try { source.capture(w, h) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    currentCoroutineContext().ensureActive()
                    if (generation != session || !policy().active || policy().refreshGeneration != config.refreshGeneration) continue
                    if (sample == null) {
                        failures++
                        if (failures >= 3) {
                            presentation.failureFadeAt = started
                            if (config.diagnosticLogging) android.util.Log.d("VideoAmbient", "sampling failed; retry backoff=2000ms")
                            delay(2000)
                            lastRefresh = -1
                            presentation.clear()
                        }
                    } else {
                        sample.getPixels(pixels, 0, w, 0, 0, w, h)
                        val raw = sample.copy(Bitmap.Config.ARGB_8888, false) ?: continue
                        val result = withContext(Dispatchers.Default) {
                            val crop = analyzer.analyze(pixels, w, h, started)
                            if (config.glow) Bitmap.createBitmap(analyzer.blur(pixels, w, h, crop), w, h, Bitmap.Config.ARGB_8888)
                            else raw
                        }
                        currentCoroutineContext().ensureActive()
                        if (generation != session || !policy().active || policy().refreshGeneration != config.refreshGeneration) continue
                        // Never recycle published images: Compose/RenderThread may still reference them.
                        val oldCurrent = presentation.current
                        val oldPrevious = presentation.previous
                        val publishAt = clock()
                        val elapsed = publishAt - (oldCurrent?.atMs ?: 0)
                        presentation.previous = if (oldCurrent != null && oldPrevious != null && elapsed < oldCurrent.transitionMs) {
                            val blended = withContext(Dispatchers.Default) {
                                val a = oldPrevious.glow.asAndroidBitmap()
                                val b = oldCurrent.glow.asAndroidBitmap()
                                if (a.width != b.width || a.height != b.height) b else {
                                    val ap = IntArray(a.width * a.height)
                                    val bp = IntArray(ap.size)
                                    a.getPixels(ap, 0, a.width, 0, 0, a.width, a.height)
                                    b.getPixels(bp, 0, b.width, 0, 0, b.width, b.height)
                                    val mix = (elapsed / oldCurrent.transitionMs.toFloat()).coerceIn(0f, 1f)
                                    for (i in ap.indices) {
                                        var c = -0x1000000
                                        for (channel in 0..2) {
                                            val shift = channel * 8
                                            val av = ap[i] shr shift and 255
                                            val bv = bp[i] shr shift and 255
                                            c = c or ((av + (bv - av) * mix).toInt().coerceIn(0, 255) shl shift)
                                        }
                                        ap[i] = c
                                    }
                                    Bitmap.createBitmap(ap, a.width, a.height, Bitmap.Config.ARGB_8888)
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (generation != session || !policy().active || policy().refreshGeneration != config.refreshGeneration) continue
                            oldCurrent.copy(glow = blended.asImageBitmap())
                        } else oldCurrent
                        presentation.current = AmbientRenderFrame(raw.asImageBitmap(), result.asImageBitmap(), clock(),
                            if (config.refreshGeneration != lastRefresh) 48L else analyzer.transitionMs, config.refreshGeneration)
                        presentation.failureFadeAt = null
                        failures = 0; frames++
                        lastRefresh = config.refreshGeneration
                    }
                    val now = clock()
                    if (config.diagnosticLogging && now - reportAt >= 10000) {
                        android.util.Log.d("VideoAmbient", "fps=${frames * 1000f / (now - reportAt)} sample_ms=${now - started} saving=${config.saving} static=${analyzer.static}")
                        frames = 0; reportAt = now
                    }
                    val dueAt = started + ambientIntervalMs(config.glow, config.saving, analyzer.static)
                    // Seek/resume/foreground events interrupt the interval, not an in-flight
                    // PixelCopy. Its callback still owns the destination until completion.
                    while (clock() < dueAt) {
                        val latest = policy()
                        if (!latest.active || latest.refreshGeneration != config.refreshGeneration ||
                            latest.playing != config.playing || latest.saving != config.saving || latest.glow != config.glow) break
                        delay(minOf(50L, (dueAt - clock()).coerceAtLeast(1L)))
                    }
                }
            } finally { source.release() }
        }
    }
}
