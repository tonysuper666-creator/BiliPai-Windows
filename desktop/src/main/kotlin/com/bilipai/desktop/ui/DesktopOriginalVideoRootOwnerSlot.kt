package com.bilipai.desktop.ui

import com.bilipai.desktop.DesktopPlaybackController
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.player.MpvPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.EventQueue

/** Entry admission only. Repository/NativeOwner supply Store -> entry ordering.
 * This is the lifetime of Root's ordinary playback producer, not a navigation
 * leaf or the native source. A temporarily covered Holder does not retire it. */
internal class DesktopOriginalVideoRootGate(
    private val repository: DesktopRepository,
    val capturedEpoch: Long,
    private val rootAlive: () -> Boolean,
    parentScope: CoroutineScope,
) {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val job = SupervisorJob(checkNotNull(parentScope.coroutineContext[Job]))
    val scope = CoroutineScope(parentScope.coroutineContext + job)
    fun owns() = !closed.get() && job.isActive && rootAlive() && repository.sessionEpoch == capturedEpoch
    fun commitEntry(action: () -> Unit): Boolean = synchronized(lock) {
        if (!owns()) false else { action(); true }
    }
    /** Root UI publications use this same Store -> entry admission. Waiting,
     * native cleanup and disk writes remain outside this short callback. */
    fun commit(action: () -> Unit): Boolean = try {
        repository.withPrimaryPlaybackAdmission(capturedEpoch, ::owns) { commitEntry(action) }
    } catch (_: CancellationException) { false }
      catch (_: com.bilipai.desktop.data.BiliApiException) { false }
    fun retire() { synchronized(lock) { closed.set(true) }; job.cancel() }
    suspend fun join(timeoutMs: Long): Boolean {
        check(currentCoroutineContext()[Job] !== job)
        return withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMs) { job.join(); true } ?: false
        }
    }
}

/** Root's only retained reference to the whole original ordinary-video owner.
 * The observable is a reference to the actual Assembly, never a second playback
 * state/cursor. The facade, Favorite/Story bridges, SMTC and every Holder consume
 * this same slot. All construction runs on the existing Main request lane.
 */
internal class DesktopOriginalVideoRootOwnerSlot(
    private val repository: DesktopRepository,
    private val rootScope: CoroutineScope,
    private val rootAlive: () -> Boolean,
) {
    private val lock = Any()
    private val lifecycle = kotlinx.coroutines.sync.Mutex()
    private val closed = AtomicBoolean(false)
    private data class Factory(val gate: DesktopOriginalVideoRootGate,
        val construct: (DesktopOriginalVideoRootGate) -> DesktopOriginalVideoOwnerAssembly,
        val beforeRetire: (DesktopOriginalVideoOwnerAssembly) -> Unit,
        val afterDrain: suspend (DesktopOriginalVideoOwnerAssembly) -> Unit,
        val afterUnconstructedDrain: suspend () -> Unit)
    private var factory: Factory? = null
    private val mutable = MutableStateFlow<DesktopOriginalVideoOwnerAssembly?>(null)
    val assemblies: StateFlow<DesktopOriginalVideoOwnerAssembly?> = mutable.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val factoryReady: StateFlow<Boolean> = mutableReady.asStateFlow()

    fun currentAssembly(): DesktopOriginalVideoOwnerAssembly? = mutable.value?.takeIf { it.owns() }

    /** No asynchronous initialization or false token is hidden in this call.
     * Root binds the real factory before exposing typed route commands. */
    fun requireAssembly(): DesktopOriginalVideoOwnerAssembly {
        check(EventQueue.isDispatchThread()) { "Original ordinary owner construction belongs to the Root EDT" }
        val binding = synchronized(lock) {
            check(!closed.get() && rootAlive()) { "Original video Root is closed" }
            currentAssembly()?.let { return it }
            check(mutable.value == null && mutableReady.value) { "Retired ordinary producers must be joined before replacement" }
            checkNotNull(factory) { "Original video Root factory is not installed" }
        }
        if (!binding.gate.owns()) throw CancellationException("Original video factory epoch retired")
        // CPU/domain construction may read the Store. Never hold the slot monitor
        // while doing so: actual route admission already follows Store -> entry.
        val created = try { binding.construct(binding.gate) } catch (failure: Throwable) {
            synchronized(lock) {
                if (factory === binding) mutableReady.value = false
            }
            // Keep the failed factory reference until its real child Job joins.
            // No replacement producer may be constructed after partial init.
            binding.gate.retire()
            throw failure
        }
        val retained = synchronized(lock) {
            if (!closed.get() && factory === binding && binding.gate.owns() && created.owns() && mutable.value == null) {
                mutable.value = created; true
            } else false
        }
        if (!retained) {
            synchronized(lock) {
                if (factory === binding && mutable.value == null) {
                    mutable.value = created
                    mutableReady.value = false
                }
            }
            binding.gate.retire()
            created.close()
            throw CancellationException("Original video owner construction retired")
        }
        return created
    }

    /** Bind before any original route is admitted. A generation change drains
     * outside the entry/Store/slot monitors; it cannot revive the old factory. */
    suspend fun install(capturedEpoch: Long,
        construct: (DesktopOriginalVideoRootGate) -> DesktopOriginalVideoOwnerAssembly,
        beforeRetire: (DesktopOriginalVideoOwnerAssembly) -> Unit,
        afterDrain: suspend (DesktopOriginalVideoOwnerAssembly) -> Unit,
        afterUnconstructedDrain: suspend () -> Unit) {
        lifecycle.lock()
        try {
            check(!closed.get() && rootAlive())
            val old = synchronized(lock) { factory?.takeIf { it.gate.capturedEpoch == capturedEpoch && it.gate.owns() } }
            if (old != null) return
            retireAndJoin()
            if (repository.sessionEpoch != capturedEpoch || !rootAlive())
                throw CancellationException("Original video Root install epoch retired")
            val gate = DesktopOriginalVideoRootGate(repository, capturedEpoch, rootAlive, rootScope)
            synchronized(lock) {
                if (closed.get() || !gate.owns()) { gate.retire(); throw CancellationException("Original video Root install retired") }
                factory = Factory(gate, construct, beforeRetire, afterDrain, afterUnconstructedDrain)
                mutableReady.value = true
            }
        } finally { lifecycle.unlock() }
    }

    /** Migration only, invoked externally before installing a factory. Fresh
     * production startup removes the old Controller construction entirely.
     * A running compatible source is adopted; otherwise the OLD producer is
     * terminally closed and genuinely joined before a fresh owner may exist. */
    suspend fun retireLegacy(controller: DesktopPlaybackController, player: MpvPlayer): DesktopOrdinaryPlaybackHandoff? {
        check(mutable.value == null && factory == null) { "Two ordinary producers are prohibited" }
        val state = controller.state.value
        val details = state.details
        val snapshot = player.currentSourceSnapshot()
        val handoff = if (details != null && snapshot != null) {
            val cid = details.pages.getOrNull(state.currentPart)?.cid
            if (cid != null) controller.drainForOriginalVideoOwner(repository.sessionEpoch,
                snapshot.sourceVersion, details.bvid, cid) else null
        } else null
        if (handoff == null) controller.close()
        if (!controller.awaitRetiredOrdinaryProducers())
            throw IllegalStateException("Old ordinary playback producers did not retire")
        return handoff
    }

    /** Synchronous admission retirement precedes any shutdown or account write.
     * Joining and plugin retirement execute in Root's external shutdown scope. */
    fun retire() {
        val snapshot = synchronized(lock) { factory to mutable.value }
        snapshot.second?.let { snapshot.first?.beforeRetire?.invoke(it) }
        synchronized(lock) {
            if (factory === snapshot.first) { mutableReady.value = false; factory?.gate?.retire() }
        }
    }

    /** Facade.stop retires this exact owner synchronously. Join/rebinding belongs
     * to the existing external Root scope, never the canceled Assembly job. */
    fun retireAssembly(expected: DesktopOriginalVideoOwnerAssembly) {
        val binding = synchronized(lock) { factory?.takeIf { mutable.value === expected } } ?: return
        binding.beforeRetire(expected)
        synchronized(lock) {
            if (factory !== binding || mutable.value !== expected) return
            mutableReady.value = false
            binding.gate.retire()
        }
        rootScope.launch(Dispatchers.Main) {
            install(binding.gate.capturedEpoch, binding.construct, binding.beforeRetire,
                binding.afterDrain, binding.afterUnconstructedDrain)
        }
    }

    private suspend fun retireAndJoin() {
        val captured = synchronized(lock) { factory to mutable.value }
        captured.second?.let { captured.first?.beforeRetire?.invoke(it) }
        val previous = synchronized(lock) {
            mutableReady.value = false
            factory?.gate?.retire()
            val pair = factory to mutable.value
            pair
        }
        var failure: Throwable? = null
        try {
            previous.second?.let { if (!it.closeAndJoin(5_000L)) error("Original video producers did not retire") }
        } catch (retired: CancellationException) {
            // Original close can reject a now-retired memory write. The Assembly
            // still attempted every cleanup and canceled its actual entry Job.
            if (previous.first?.gate?.join(5_000L) != true) failure = retired
        } catch (error: Throwable) { failure = error }
        val gateJoined = previous.first?.gate?.join(5_000L) ?: true
        if (!gateJoined && failure == null) failure = IllegalStateException("Original video entry did not retire")
        // A failed drain stays referenced: requireAssembly cannot construct a
        // competing producer. Root may retry closeAndJoin once it is quiescent.
        if (failure == null) {
            previous.second?.let { previous.first?.afterDrain?.invoke(it) }
                ?: previous.first?.afterUnconstructedDrain?.invoke()
            synchronized(lock) {
                if (mutable.value === previous.second) mutable.value = null
                if (factory === previous.first) factory = null
            }
        }
        failure?.let { throw it }
    }

    suspend fun closeAndJoin() = withContext(NonCancellable) {
        lifecycle.lock()
        try { closed.set(true); retireAndJoin() }
        finally { lifecycle.unlock() }
    }
}
