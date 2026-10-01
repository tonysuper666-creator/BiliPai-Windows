package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.android.purebilibili.feature.video.controller.PlaybackProgressManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The original SharedPreferences.apply disk lane over the existing global Store.
 * Apply admits one immutable edit, while the original Manager's memory cache is
 * already immediately updated. Atomic disk replacement runs serially on IO,
 * outside SessionStore/entry/native gates. No new preference Store is constructed.
 */
internal class DesktopOriginalProgressWriter(
    val store: DesktopPluginStore,
    appScope: CoroutineScope,
    private val reportFailure: (String) -> Unit,
) {
    private data class Edit(val values: Map<String, JsonElement?>, val clear: Boolean)
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val failure = AtomicReference<Throwable?>()
    private val edits = Channel<Edit>(Channel.UNLIMITED)
    private val job = appScope.launch(Dispatchers.IO) {
        for (edit in edits) {
            try { store.update("video_progress", edit.values, edit.clear) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (failure.compareAndSet(null, error)) reportFailure("播放进度未能保存，请检查本地存储")
            }
        }
    }
    init { store.requireObjectNamespace("video_progress") }
    fun apply(values: Map<String, JsonElement?>, clear: Boolean) = synchronized(lock) {
        check(!closed.get() && job.isActive) { "Original global progress writer is closed" }
        check(edits.trySend(Edit(values.toMap(), clear)).isSuccess) { "Original progress edit was rejected" }
    }
    /** Root calls this before the global Store freeze, outside all admissions. */
    suspend fun closeAndJoin(timeoutMs: Long = 5_000L): Boolean {
        require(timeoutMs > 0)
        synchronized(lock) { closed.set(true); edits.close() }
        check(currentCoroutineContext()[Job] !== job)
        val drained = withContext(NonCancellable) { withTimeoutOrNull(timeoutMs) { job.join(); true } ?: false }
        return drained && !job.isCancelled && failure.get() == null
    }
}

/** Exact namespace/Long/editor subset consumed by the unchanged original Manager. */
internal class DesktopOriginalProgressPreferences(
    context: DesktopPluginContext,
    private val writer: DesktopOriginalProgressWriter,
) {
    private val store = context.store.also { require(it === writer.store) }
    val all: Map<String, Any> get() = store.preferences("video_progress").mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.let { key to it }
    }.toMap()
    fun edit() = Editor()
    inner class Editor {
        private val values = linkedMapOf<String, JsonElement?>()
        private var clearing = false
        fun putLong(key: String, value: Long): Editor = apply { values[key] = JsonPrimitive(value) }
        fun remove(key: String): Editor = apply { values[key] = null }
        fun clear(): Editor = apply { clearing = true; values.clear() }
        fun apply() { writer.apply(values, clearing) }
    }
}

/** One real global progress owner, shared by ordinary playback/queue/UI hot reads.
 * Duration comes from that exact accepted native subject, never Library seconds.
 */
internal class DesktopOriginalGlobalPlaybackProgress(
    context: DesktopPluginContext,
    appScope: CoroutineScope,
    reportFailure: (String) -> Unit,
) {
    private val writer = DesktopOriginalProgressWriter(context.store, appScope, reportFailure)
    private val prefs = DesktopOriginalProgressPreferences(context, writer)
    private val manager = PlaybackProgressManager.getInstance(prefs)
    fun forEntry(durationMs: (String, Long) -> Long, owned: () -> Boolean): DesktopOriginalVideoProgressPort =
        object : DesktopOriginalVideoProgressPort {
            override fun getCachedPosition(bvid: String, cid: Long): Long {
                if (!owned()) throw CancellationException("Original progress entry retired")
                return manager.getCachedPosition(bvid, cid)
            }
            override fun savePosition(bvid: String, cid: Long, positionMs: Long) {
                if (!owned()) throw CancellationException("Original progress entry retired")
                manager.savePosition(bvid, cid, positionMs, durationMs(bvid, cid))
            }
        }
    suspend fun closeAndJoin(): Boolean = writer.closeAndJoin()
}
