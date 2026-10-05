package com.bilipai.desktop.brand

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bilipai.desktop.ui.DesktopHomeWindowBackgroundPort
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlin.math.min
import kotlin.math.roundToInt

/** One composition owns one result and its renderer, including the verified static PNG. No global cache. */
internal class DesktopMaidResourceOwner<T : AutoCloseable> : AutoCloseable {
    private val lock = Any()
    private val started = AtomicBoolean(false)
    private val ready = CompletableDeferred<T?>()
    private var closed = false
    var current: T? by mutableStateOf(null)
        private set

    suspend fun load(factory: () -> T?) {
        if (!started.compareAndSet(false, true)) return
        val caller = currentCoroutineContext()[Job]
        if (synchronized(lock) { closed }) return
        var orphan: T? = null
        try {
            // Capture inside IO: prompt cancellation on the dispatcher return
            // cannot strand the just-decoded native resources outside finally.
            val result = withContext(Dispatchers.IO) { factory().also { orphan = it } }
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (!closed && caller?.isActive != false) {
                    current = result
                    orphan = null
                    ready.complete(result)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The original BlueSnow body owns its resource-error/static branch.
        } finally {
            try { orphan?.close() } finally { ready.complete(null) }
        }
    }

    suspend fun await(): T? {
        val expected = ready.await()
        return synchronized(lock) { expected.takeIf { !closed && current === expected } }
    }
    fun useCurrent(expected: T, action: (T) -> Unit): Boolean = synchronized(lock) {
        if (closed || current !== expected) false else { action(expected); true }
    }
    override fun close() {
        val released = synchronized(lock) {
            if (closed) return
            closed = true
            current.also { current = null; ready.complete(null) }
        }
        released?.close()
    }
}

/** A new animate invocation starts a new frame-clock epoch; hidden time is never accumulated. */
internal class DesktopMaidFrameProgress(val durationMs: Double, initialProgress: Float) {
    init { require(durationMs.isFinite() && durationMs > 0.0); require(initialProgress.isFinite()) }
    private val initial = initialProgress.coerceIn(0f, 1f)
    private var originNanos: Long? = null
    private var latestNanos: Long? = null
    fun advance(frameNanos: Long): Float {
        require(frameNanos >= 0 && (latestNanos == null || frameNanos >= latestNanos!!))
        if (originNanos == null) originNanos = frameNanos
        latestNanos = frameNanos
        return (initial + (frameNanos - originNanos!!).toDouble() / (durationMs * 1_000_000.0)).coerceIn(0.0, 1.0).toFloat()
    }
}

internal sealed interface DesktopMaidCompositionSpec {
    data class Animation(val animation: DesktopMaidAnimation) : DesktopMaidCompositionSpec
}

internal class DesktopMaidComposition(
    val animation: DesktopMaidAnimation,
    val renderer: BrandMotionRenderer,
    val owner: DesktopMaidResourceOwner<DesktopMaidComposition>,
) : AutoCloseable {
    fun requirePreparedImage(expected: DesktopMaidAnimation) {
        check(animation === expected && renderer.supportsAnimation)
        check(owner.useCurrent(this) {}) { "Maid composition retired" }
    }
    override fun close() = renderer.close()
}

internal class DesktopMaidCompositionResult(val owner: DesktopMaidResourceOwner<DesktopMaidComposition>) {
    suspend fun await(): DesktopMaidComposition {
        val composition = checkNotNull(owner.await()) { "Maid packaged resources unavailable" }
        check(composition.renderer.supportsAnimation) { "Maid timeline unavailable; matching verified still retained" }
        return composition
    }
}

@Composable
internal fun rememberDesktopMaidComposition(spec: DesktopMaidCompositionSpec): DesktopMaidCompositionResult {
    val animation = (spec as DesktopMaidCompositionSpec.Animation).animation
    val owner = remember(animation) { DesktopMaidResourceOwner<DesktopMaidComposition>() }
    val result = remember(owner) { DesktopMaidCompositionResult(owner) }
    LaunchedEffect(owner) {
        owner.load {
            fun packaged(name: String, expectedBytes: Int): ByteArray {
                require(name.matches(Regex("bilipai_maid_[a-z_]+\\.(json|png)")))
                return requireNotNull(DesktopMaidComposition::class.java.getResourceAsStream("/brand-motion/$name")).use {
                    it.readNBytes(expectedBytes + 1).also { bytes -> require(bytes.size == expectedBytes) }
                }
            }
            val asset = animation.asset
            val opened = BrandMotionRenderer.open(animation, packaged(asset.jsonFileName, asset.jsonBytes),
                packaged(asset.pngFileName, asset.pngBytes))
            (opened as? BrandMotionRendererOpenResult.Ready)?.renderer?.let { DesktopMaidComposition(animation, it, owner) }
        }
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    return result
}

internal class DesktopMaidAnimatable {
    var composition: DesktopMaidComposition? by mutableStateOf(null)
        private set
    var progress: Float by mutableFloatStateOf(0f)
        private set
    suspend fun snapTo(composition: DesktopMaidComposition, progress: Float) {
        currentCoroutineContext().ensureActive()
        composition.requirePreparedImage(composition.animation)
        require(progress.isFinite())
        this.composition = composition
        this.progress = progress.coerceIn(0f, 1f)
    }
    suspend fun animate(composition: DesktopMaidComposition, iteration: Int, iterations: Int,
        initialProgress: Float, continueFromPreviousAnimate: Boolean) {
        require(iteration == 1 && iterations == 1 && !continueFromPreviousAnimate)
        composition.requirePreparedImage(composition.animation)
        this.composition = composition
        progress = initialProgress.coerceIn(0f, 1f)
        val clock = DesktopMaidFrameProgress(composition.renderer.durationMs, progress)
        while (progress < 1f) {
            val frame = withFrameNanos { it }
            currentCoroutineContext().ensureActive()
            composition.requirePreparedImage(composition.animation)
            progress = clock.advance(frame)
        }
    }
}

@Composable internal fun rememberDesktopMaidAnimatable(): DesktopMaidAnimatable = remember { DesktopMaidAnimatable() }

@Composable
internal fun rememberDesktopMaidForeground(background: DesktopHomeWindowBackgroundPort): State<Boolean> {
    val foreground = remember(background) { mutableStateOf(!background.isInBackground) }
    DisposableEffect(background) {
        val listener = object : DesktopHomeWindowBackgroundPort.Listener {
            override fun onEnterBackground() { foreground.value = false }
            override fun onEnterForeground() { foreground.value = true }
        }
        background.addListener(listener)
        foreground.value = !background.isInBackground
        onDispose { background.removeListener(listener) }
    }
    return foreground
}

@Composable
internal fun DesktopMaidMotionCanvas(composition: DesktopMaidComposition?, progress: () -> Float, modifier: Modifier) {
    Canvas(modifier) { composition?.let { current ->
        current.owner.useCurrent(current) { drawMaid(it.renderer, progress().toDouble(), false) }
    } }
}

@Composable
internal fun DesktopMaidStillImage(result: DesktopMaidCompositionResult, contentDescription: String, modifier: Modifier) {
    val composition = result.owner.current
    Canvas(modifier.semantics { this.contentDescription = contentDescription }) { composition?.let { current ->
        current.owner.useCurrent(current) { drawMaid(it.renderer, 1.0, true) }
    } }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMaid(renderer: BrandMotionRenderer, progress: Double, still: Boolean) {
    if (size.width <= 0f || size.height <= 0f) return
    // A very large consumer cannot exceed the renderer's CPU allocation budget.
    // Scale the bounded raster onto the existing Canvas, without a second lease.
    val factor = min(1f, BrandMotionRenderBudget.MAX_SIDE / maxOf(size.width, size.height))
    val width = (size.width * factor).roundToInt().coerceAtLeast(1)
    val height = (size.height * factor).roundToInt().coerceAtLeast(1)
    drawIntoCanvas { target ->
        val canvas = target.nativeCanvas
        val saved = canvas.save()
        try {
            canvas.scale(size.width / width, size.height / height)
            if (still) renderer.renderStill(canvas, width, height) else renderer.render(canvas, width, height, progress)
        } finally { canvas.restoreToCount(saved) }
    }
}
