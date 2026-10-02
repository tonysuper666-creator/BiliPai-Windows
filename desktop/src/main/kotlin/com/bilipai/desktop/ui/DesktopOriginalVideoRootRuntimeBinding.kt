package com.bilipai.desktop.ui

import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicReference

/** Reference to the token returned by Runtime's REAL load, or the exact token
 * captured by the old Controller handoff. It never increments a generation.
 * onAccepted only publishes memory/a Channel event while NativeOwner's gate is
 * held; the Main dispatcher consumer waits/calls providers outside that gate. */
internal class DesktopOriginalVideoRootRuntimeBinding(
    private val repository: DesktopRepository,
    private val runtime: DesktopPluginRuntime,
    private val gate: DesktopOriginalVideoRootGate,
    private val native: () -> DesktopOriginalVideoNativeOwner,
) {
    private data class Loaded(val expected: DesktopOriginalVideoAcceptedPublication, val generation: Long)
    private val loaded = AtomicReference<Loaded?>()
    private val inherited = AtomicReference<DesktopOrdinaryPlaybackHandoff?>()
    // Read-only publication projection for Overlay's requestLock predicates. It
    // never admits native work and does not call back into Store/entry/MPV locks.
    private val published = AtomicReference<DesktopOriginalVideoAcceptedPublication?>()
    private val events = Channel<DesktopOriginalVideoAcceptedPublication>(Channel.CONFLATED)
    private val mutableVersions = MutableStateFlow<Long?>(null)
    val sourceVersions: StateFlow<Long?> = mutableVersions.asStateFlow()
    private val worker = gate.scope.launch(Dispatchers.Main) {
        events.consumeAsFlow().collectLatest { expected ->
            if (!gate.owns() || !native().isCurrent(expected)) return@collectLatest
            val prior = loaded.get()
            if (prior != null && prior.expected.sourceVersion == expected.sourceVersion &&
                prior.expected.request == expected.request) {
                loaded.compareAndSet(prior, Loaded(expected, prior.generation))
                return@collectLatest // recovery/adoption keeps provider state
            }
            val caller = checkNotNull(currentCoroutineContext()[Job])
            val stillCurrent = { caller.isActive && gate.owns() && native().isCurrent(expected) }
            val publication = DesktopRepositoryPlaybackPublication(repository)
            val calls = publication.calls(repository.httpClient, expected.nativeSource.source, stillCurrent, caller)
            val generation = runtime.onVideoLoadOwned(expected.request.bvid, expected.request.cid,
                stillCurrent, { action -> native().admitPlaybackDispatch(expected, action) }, calls)
            currentCoroutineContext().ensureActive()
            if (stillCurrent()) loaded.set(Loaded(expected, generation))
        }
    }

    /** Must precede native.adopt. The Channel uses non-immediate Main dispatch,
     * so adoption cannot run a suspend provider callback inside the native gate. */
    fun prepareInheritance(handoff: DesktopOrdinaryPlaybackHandoff) {
        check(gate.owns()); inherited.set(handoff)
    }
    fun accepted(expected: DesktopOriginalVideoAcceptedPublication) {
        published.set(expected)
        mutableVersions.value = expected.sourceVersion
        val transfer = inherited.get()
        if (transfer != null && transfer.nativeSource.sourceVersion == expected.sourceVersion &&
            transfer.request == expected.request && inherited.compareAndSet(transfer, null)) {
            transfer.playerPluginGeneration?.let { loaded.set(Loaded(expected, it)); return }
        }
        events.trySend(expected)
    }
    fun publishedReference(): DesktopOriginalVideoAcceptedPublication? = published.get()
    fun generation(expected: DesktopOriginalVideoAcceptedPublication): Long? =
        loaded.get()?.takeIf { it.expected === expected && gate.owns() && native().isCurrent(expected) }?.generation

    fun calls(expected: DesktopOriginalVideoAcceptedPublication): okhttp3.Call.Factory {
        val caller = checkNotNull(gate.scope.coroutineContext[Job])
        val owns = { gate.owns() && native().isCurrent(expected) }
        if (!owns()) throw CancellationException("Accepted plugin transport retired")
        return DesktopRepositoryPlaybackPublication(repository).calls(repository.httpClient,
            expected.nativeSource.source, owns, caller)
    }
    fun close() { events.close(); worker.cancel() }
}
