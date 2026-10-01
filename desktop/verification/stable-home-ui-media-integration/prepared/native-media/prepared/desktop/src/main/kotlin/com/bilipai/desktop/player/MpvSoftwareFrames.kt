package com.bilipai.desktop.player

import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Copied CPU pixels from the SAME mpv core. No native buffer or credentials escape the render thread. */
internal class MpvSoftwareFrame(val sourceVersion: Long, val sequence: Long, val width: Int,
    val height: Int, val rowBytes: Int, internal val opaqueBgra: ByteArray)

/** Render-thread mailbox. Its short lock never calls a client API, Root callback, or MPV player lock. */
internal class MpvSoftwareTarget : AutoCloseable {
    internal data class Source(val version: Long, val revision: Long, val enabled: Boolean)
    internal data class Size(val width: Int, val height: Int)
    private val gate = Any()
    private val closed = AtomicBoolean(false)
    private val mutableFrames = MutableStateFlow<MpvSoftwareFrame?>(null)
    val frames: StateFlow<MpvSoftwareFrame?> = mutableFrames.asStateFlow()
    internal val source = AtomicReference(Source(0L, 0L, false))
    internal val size = AtomicReference(Size(640, 360))
    internal val redraw = AtomicBoolean(false)

    fun resize(width: Int, height: Int) {
        if (closed.get() || width <= 0 || height <= 0) return
        // A platform raster budget, not a video/source schema limit. Preserve the viewport aspect ratio.
        val factor = minOf(1.0, 4096.0 / width, 4096.0 / height,
            kotlin.math.sqrt(8_388_608.0 / (width.toDouble() * height)))
        val next = Size((width * factor).roundToInt().coerceAtLeast(1), (height * factor).roundToInt().coerceAtLeast(1))
        if (size.getAndSet(next) != next) redraw.set(true)
    }

    internal fun beginSource(version: Long, revision: Long) = synchronized(gate) {
        if (!closed.get()) { source.set(Source(version, revision, false)); mutableFrames.value = null }
    }
    internal fun enableSource(version: Long, revision: Long) = synchronized(gate) {
        if (!closed.get() && source.get().version == version && source.get().revision == revision) {
            source.set(Source(version, revision, true)); redraw.set(true)
        }
    }
    internal fun clear() = synchronized(gate) {
        source.set(source.get().copy(enabled = false)); mutableFrames.value = null
    }
    internal fun publish(expected: Source, frame: MpvSoftwareFrame) = synchronized(gate) {
        if (!closed.get() && expected.enabled && source.get() == expected) mutableFrames.value = frame
    }
    override fun close() = synchronized(gate) {
        if (closed.compareAndSet(false, true)) { source.set(source.get().copy(enabled = false)); mutableFrames.value = null }
    }
}

/** cdecl callback does ONLY a wakeup. Never call any mpv API or Root/Compose callback here. */
internal fun interface MpvRenderUpdateCallback : Callback { fun invoke(context: Pointer?) }

/**
 * Pinned libmpv render.h 69e63f425a531f814431fba12750bdb3721357f2.
 * All render calls stay on this dedicated thread, independently of the existing client/event loop.
 * It never waits for the client thread/lock. The client worker frees it BEFORE terminate_destroy.
 */
internal class MpvSoftwareRenderer(private val native: MpvNative, private val handle: Pointer,
    private val target: MpvSoftwareTarget) : AutoCloseable {
    private val stopping = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private val wake = Object()
    private val update = AtomicBoolean(true)
    private val failure = AtomicReference<Throwable?>(null)
    private val sequence = AtomicLong()
    private val callback = MpvRenderUpdateCallback { update.set(true); synchronized(wake) { wake.notifyAll() } }
    private val thread = Thread(::run, "BiliPai-mpv-software-render").apply { isDaemon = true }

    fun start() { thread.start(); ready.await(); throwIfFailed() }
    fun throwIfFailed() { failure.get()?.let { throw IllegalStateException("MPV software frame transport failed", it) } }
    override fun close() {
        stopping.set(true); synchronized(wake) { wake.notifyAll() }
        if (Thread.currentThread() !== thread) thread.join()
    }

    private fun run() {
        var context: Pointer? = null
        var pixels: Memory? = null
        var bufferSize: MpvSoftwareTarget.Size? = null
        try {
            RenderParameters().use { init ->
                init.string(1, "sw") // MPV_RENDER_PARAM_API_TYPE
                val result = PointerByReference()
                check(native.mpv_render_context_create(result, handle, init.pointer()) >= 0) { "Cannot create libmpv software render context" }
                context = requireNotNull(result.value)
            }
            val ctx = requireNotNull(context)
            native.mpv_render_context_set_update_callback(ctx, callback, null)
            ready.countDown()
            while (!stopping.get()) {
                val force = target.redraw.getAndSet(false)
                if (!update.getAndSet(false) && !force) {
                    synchronized(wake) { if (!stopping.get() && !update.get() && !target.redraw.get()) wake.wait(20L) }
                    continue
                }
                val flags = native.mpv_render_context_update(ctx)
                if ((flags and 1L) == 0L && !force) continue // MPV_RENDER_UPDATE_FRAME
                val owner = target.source.get()
                if (!owner.enabled) {
                    // Acknowledge pending frames while startup/replacement is unowned; never publish them.
                    RenderParameters().use { skipped ->
                        skipped.int(13, 1) // SKIP_RENDERING
                        check(native.mpv_render_context_render(ctx, skipped.pointer()) >= 0)
                    }
                    continue
                }
                val size = target.size.get()
                val stride = (size.width * 4 + 63) and -64
                if (size != bufferSize) {
                    pixels?.close(); pixels = Memory(stride.toLong() * size.height + 63L).also { it.clear() }; bufferSize = size
                }
                val allocation = requireNotNull(pixels)
                val aligned = allocation.share((64L - (Pointer.nativeValue(allocation) and 63L)) and 63L)
                RenderParameters().use { params ->
                    params.size(17, size.width, size.height)
                    params.string(18, "bgr0")
                    params.long(19, stride.toLong()) // size_t, x64 Windows
                    params.data(20, aligned)
                    // Keep default video timing. The render thread is allowed to wait; client commands keep draining.
                    check(native.mpv_render_context_render(ctx, params.pointer()) >= 0) { "Cannot render libmpv software frame" }
                }
                if (stopping.get()) break
                val copied = aligned.getByteArray(0, stride * size.height)
                // bgr0's fourth byte is explicitly UNINITIALIZED in render.h. Never expose it as Compose alpha.
                for (y in 0 until size.height) for (x in 0 until size.width) copied[y * stride + x * 4 + 3] = 0xff.toByte()
                target.publish(owner, MpvSoftwareFrame(owner.version, sequence.incrementAndGet(), size.width, size.height, stride, copied))
                // CPU frame completion, NOT a claim that a desktop monitor/compositor presented it.
            }
        } catch (problem: Throwable) { failure.set(problem) }
        finally {
            ready.countDown()
            try { context?.let { native.mpv_render_context_free(it) } }
            finally { pixels?.close() }
        }
    }
}

/** x64 mpv_render_param = int + 4-byte padding + pointer; terminating type zero. */
private class RenderParameters : AutoCloseable {
    private val memory = ArrayList<Memory>()
    private val values = ArrayList<Pair<Int, Pointer>>()
    private fun allocate(bytes: Long): Memory = Memory(bytes).also { it.clear(); memory += it }
    fun data(type: Int, value: Pointer) { values += type to value }
    fun int(type: Int, value: Int) { data(type, allocate(4).also { it.setInt(0, value) }) }
    fun long(type: Int, value: Long) { data(type, allocate(8).also { it.setLong(0, value) }) }
    fun size(type: Int, width: Int, height: Int) { data(type, allocate(8).also { it.setInt(0, width); it.setInt(4, height) }) }
    fun string(type: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        data(type, allocate(bytes.size + 1L).also { it.write(0, bytes, 0, bytes.size) })
    }
    fun pointer(): Pointer = allocate((values.size + 1L) * 16L).also { buffer ->
        values.forEachIndexed { i, (type, value) -> buffer.setInt(i * 16L, type); buffer.setPointer(i * 16L + 8L, value) }
    }
    override fun close() { memory.asReversed().forEach { it.close() } }
}
