package com.bilipai.desktop.ui

import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.player.cache.DesktopMediaByteCache
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Sole physical app/Window resources. Entry/account changes reuse these actual
 * actors; captured cache admissions provide account partitioning. Construction
 * and shutdown run on application IO, outside Store/entry/native gates. The
 * original progress singleton is never recreated after a restore in this process.
 */
internal class DesktopOriginalVideoAppResources private constructor(
    val mediaCache: DesktopMediaByteCache,
    val progress: DesktopOriginalGlobalPlaybackProgress,
) {
    private val closed = AtomicBoolean()
    private val drain = Mutex()
    fun isActive() = !closed.get()
    suspend fun closeAndJoin() = withContext(NonCancellable + Dispatchers.IO) { drain.withLock {
        closed.set(true)
        var failure: Throwable? = null
        try {
            mediaCache.close()
            check(withTimeoutOrNull(10_000L) { mediaCache.scope.coroutineContext[Job]!!.join(); true } == true) {
                "Original media byte producer has not drained"
            }
        } catch (error: Throwable) { failure = error }
        try { check(progress.closeAndJoin()) { "Original progress writer has not drained" } }
        catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        failure?.let { throw it }
    } }
    companion object {
        /** Caller must publish the returned resource atomically to the physical
         * Root reference before returning to a cancellable Compose effect. */
        fun create(context: DesktopPluginContext, repository: DesktopRepository,
            rootScope: CoroutineScope, root: Path,
            reportFailure: (String) -> Unit): DesktopOriginalVideoAppResources {
            check(!java.awt.EventQueue.isDispatchThread())
            val cache = DesktopMediaByteCache(root, rootScope, repository)
            try {
                return DesktopOriginalVideoAppResources(cache,
                    DesktopOriginalGlobalPlaybackProgress(context, rootScope, reportFailure))
            } catch (error: Throwable) { cache.close(); throw error }
        }
    }
}

/** Only UI event data. Playback/source/queue authority remains the retained
 * Assembly. The physical Shell validates the captured source before SMTC enqueue.
 */
internal sealed interface DesktopOriginalVideoShellEvent {
    data class Feedback(val message: String) : DesktopOriginalVideoShellEvent
    data class Metadata(val owner: DesktopOriginalVideoOwnerAssembly,
        val expected: DesktopOriginalVideoAcceptedPublication,
        val title: String, val author: String, val cover: String) : DesktopOriginalVideoShellEvent
}
