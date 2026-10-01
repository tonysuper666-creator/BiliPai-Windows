package com.bilipai.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.bilipai.desktop.plugins.DesktopLottieAsset
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Existing Root transport and retained Home data owner's same epoch/admission; no new client/cache. */
internal class DesktopHomeLottieBinding(
    private val client: OkHttpClient,
    private val stillOwned: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val gate = Any()
    private val closed = AtomicBoolean(false)
    private val calls = LinkedHashSet<Call>()
    private val leases = LinkedHashSet<DesktopHomeLottieLease>()
    fun isCurrent(): Boolean = !closed.get() && stillOwned()
    private fun assertCurrent() { if (!isCurrent()) throw CancellationException("Home animation owner retired") }

    private suspend fun read(url: String): ByteArray {
        currentCoroutineContext().ensureActive(); assertCurrent()
        val address = url.toHttpUrl()
        require(address.isHttps || address.scheme == "http")
        val call = client.newCall(Request.Builder().url(address).get().build())
        val context = currentCoroutineContext()
        val finished = CompletableDeferred<Result<ByteArray>>()
        var enqueued = false
        try {
            var registered = false
            val admitted = commitIfCurrent {
                synchronized(gate) { if (!closed.get() && stillOwned()) { calls += call; registered = true } }
            }
            if (!admitted || !registered) throw CancellationException("Home animation admission retired")
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) { finished.complete(Result.failure(error)) }
                override fun onResponse(call: Call, response: Response) {
                    finished.complete(runCatching {
                        response.use {
                            check(it.isSuccessful) { "动画下载失败 (HTTP ${it.code})" }
                            val body = requireNotNull(it.body) { "动画内容为空" }
                            val expected = body.contentLength()
                            require(expected <= MAX_BYTES) { "动画超过资源大小限制" }
                            val bytes = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    context.ensureActive(); assertCurrent()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(bytes.size().toLong() + count <= MAX_BYTES) { "动画超过资源大小限制" }
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            context.ensureActive(); assertCurrent()
                            require(bytes.size() > 0 && (expected < 0 || bytes.size().toLong() == expected)) { "动画内容为空或不完整" }
                            bytes.toByteArray()
                        }
                    })
                }
            })
            enqueued = true
            val bytes = try { finished.await().getOrThrow() }
                catch (cancelled: CancellationException) { call.cancel(); withContext(NonCancellable) { finished.await() }; throw cancelled }
            context.ensureActive(); assertCurrent()
            return bytes
        } finally {
            if (enqueued && !finished.isCompleted) { call.cancel(); withContext(NonCancellable) { finished.await() } }
            synchronized(gate) { calls -= call }
        }
    }

    /** Caller captures this resource INSIDE its IO block before that block's cancellable return. */
    internal suspend fun load(url: String): DesktopHomeLottieLease {
        val bytes = read(url)
        currentCoroutineContext().ensureActive(); assertCurrent()
        val asset = DesktopLottieAsset.decode(bytes) // Existing Skottie engine and validation; not a fake animation.
        try {
            currentCoroutineContext().ensureActive(); assertCurrent()
            return DesktopHomeLottieLease(this@DesktopHomeLottieBinding, asset)
        } catch (failure: Throwable) { asset.close(); throw failure }
    }

    internal fun install(lease: DesktopHomeLottieLease, block: () -> Unit): Boolean {
        var ran = false
        val accepted = commitIfCurrent {
            synchronized(gate) {
                if (!closed.get() && stillOwned()) { leases += lease; block(); ran = true }
            }
        }
        return accepted && ran
    }
    internal fun forget(lease: DesktopHomeLottieLease) { synchronized(gate) { leases -= lease } }
    internal fun commitUi(block: () -> Unit): Boolean {
        var ran = false
        val accepted = commitIfCurrent { if (!closed.get() && stillOwned()) { block(); ran = true } }
        return accepted && ran
    }
    internal fun fail(message: String) {
        commitUi { onFailure(message) }
    }
    /** Never call while retaining SessionStore/app admission. Native asset close runs outside gate. */
    override fun close() {
        val retired = synchronized(gate) {
            if (!closed.compareAndSet(false, true)) return
            (calls.toList() to leases.toList()).also { calls.clear(); leases.clear() }
        }
        retired.first.forEach { it.cancel() }
        retired.second.forEach { it.close() }
    }
    private companion object { const val MAX_BYTES = 32L * 1024 * 1024 }
}

/** Per-composition decoded resource, NOT an item/account/animation cache authority. */
internal class DesktopHomeLottieLease(private val owner: DesktopHomeLottieBinding,
    private val asset: DesktopLottieAsset) : AutoCloseable {
    private val gate = Any()
    private var closed = false
    val durationSeconds: Double get() = asset.durationSeconds
    fun render(canvas: org.jetbrains.skia.Canvas, width: Float, height: Float, elapsed: Double, iterations: Int) {
        if (!owner.isCurrent()) return
        synchronized(gate) {
            if (closed) return
            val total = durationSeconds * iterations.toDouble()
            // Original ErrorState passes one iteration. Multi-iteration slots stop on their final frame.
            val atEnd = elapsed >= total
            val seconds = if (atEnd) durationSeconds else elapsed % durationSeconds
            asset.render(canvas, width, height, seconds, loop = !atEnd)
        }
    }
    override fun close() {
        owner.forget(this)
        synchronized(gate) { if (!closed) { closed = true; asset.close() } }
    }
}

/** Maps the original Url + size + iterations contract onto the existing real Skottie renderer.
 * Failure retains the original empty animation slot and reports through the required owned feedback.
 */
@Composable
internal fun DesktopHomeActualErrorAnimation(binding: DesktopHomeLottieBinding, url: String, size: Dp, iterations: Int) {
    require(iterations > 0)
    var lease by remember(binding, url) { mutableStateOf<DesktopHomeLottieLease?>(null) }
    var elapsed by remember(binding, url, iterations) { mutableDoubleStateOf(0.0) }
    LaunchedEffect(binding, url) {
        var candidate: DesktopHomeLottieLease? = null
        try {
            // Capture in the inner block so prompt cancellation on withContext's return cannot leak native assets.
            withContext(Dispatchers.IO) { candidate = binding.load(url) }
            val loaded = requireNotNull(candidate)
            if (binding.install(loaded) { lease = loaded }) candidate = null
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { binding.fail(failure.message ?: "动画加载失败") }
        finally { candidate?.close() }
    }
    DisposableEffect(lease) { val own = lease; onDispose { own?.close() } }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val playing = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(binding, lease, playing, iterations) {
        val animation = lease ?: return@LaunchedEffect
        if (!playing) return@LaunchedEffect
        var previous: Long? = null
        val total = animation.durationSeconds * iterations.toDouble()
        while (binding.isCurrent() && elapsed < total) {
            withFrameNanos { now ->
                previous?.let { earlier -> binding.commitUi {
                    elapsed = (elapsed + (now - earlier).coerceAtLeast(0L) / 1_000_000_000.0).coerceAtMost(total)
                } }
                previous = now
            }
        }
    }
    Box(Modifier.size(size)) {
        val animation = lease
        if (animation != null && binding.isCurrent()) Canvas(Modifier.size(size)) {
            if (this.size.width > 0 && this.size.height > 0) drawIntoCanvas {
                animation.render(it.nativeCanvas, this.size.width, this.size.height, elapsed, iterations)
            }
        }
    }
}
