package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.SpaceApi
import com.android.purebilibili.data.repository.DesktopOriginalHomeHistoryProtocol
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteRepository
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.android.purebilibili.data.repository.FollowStateChange
import com.android.purebilibili.feature.space.DesktopOriginalTabletOwnerSpaceLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** The selected original load graph requires these actual captured request views.
 * State is the existing full original SpaceUiState, never a flat Desktop DTO. */
internal interface DesktopOriginalTabletOwnerSpaceEnvironment {
    val scope: DesktopOriginalTabletOwnerRequestScope
    val api: BilibiliApi
    val spaceApi: SpaceApi
    val history: DesktopOriginalHomeHistoryProtocol
    val favorites: DesktopOriginalFavoriteRepository
    val actions: DesktopOriginalTabletOwnerSpaceActions
    val followStateChanges: Flow<FollowStateChange>
    fun primaryAccessToken(): String?
    fun primaryAccessTokenPlatform(): String
    fun requireMid(mid: Long)
    fun <T> mutableStateFlow(initial: T): MutableStateFlow<T>
}

/** launch is intentionally a member, so every original viewModelScope.launch
 * captures inside THAT invocation's real Job. Nested async/coroutineScope retain
 * this fixed view; a later supplemental launch obtains its own captured view. */
internal class DesktopOriginalTabletOwnerRequestScope(
    private val delegate: CoroutineScope,
    private val invocation: suspend (suspend CoroutineScope.() -> Unit) -> Unit,
) : CoroutineScope {
    override val coroutineContext: CoroutineContext get() = delegate.coroutineContext
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = delegate.launch { invocation(block) }
}

/** Once per Assembly WindowPlatforms. The caller's entryScope must be the same
 * Root UI scope used by its original VM/Holder (serialized UI state mutations).
 * withEntryAdmission is the existing short entry gate and MUST NOT enter Store.
 * The Flow must be Repository.followStateEvents.changes filtered for captured
 * epoch/current owner, then mapped to the existing original FollowStateChange.
 */
internal class DesktopOriginalTabletOwnerUploadsPlatform(
    private val entryScope: CoroutineScope,
    private val isEntryOwned: () -> Boolean,
    private val withEntryAdmission: ((() -> Unit) -> Boolean),
    private val captureRequest: suspend () -> DesktopOriginalVideoRepositoryBinding,
    private val followStateChanges: Flow<FollowStateChange>,
    private val feedback: (String) -> Unit,
) : DesktopOriginalTabletAudioPlatform, AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val lock = Any()
    private val owners = LinkedHashMap<Long, Owner>()
    private fun owns() = alive.get() && entryScope.coroutineContext[Job]?.isActive == true && isEntryOwned()
    override fun ownerUploads(mid: Long): DesktopOriginalOwnerUploadsPort {
        require(mid > 0L)
        if (!owns()) throw CancellationException("Tablet uploads entry retired")
        synchronized(lock) { owners[mid] }?.let { return it.loader }
        // Constructor can launch the original follow collector. It runs outside
        // the entry/map monitors, so captureRequest cannot invert Store -> entry.
        val proposed = Owner(mid)
        var selected: Owner? = null
        val admitted = withEntryAdmission {
            if (owns()) synchronized(lock) { selected = owners.getOrPut(mid) { proposed } }
        }
        if (selected !== proposed) proposed.close()
        if (!admitted || !owns() || selected == null) throw CancellationException("Tablet uploads publication retired")
        return checkNotNull(selected).loader
    }
    override fun close() {
        if (!alive.compareAndSet(true, false)) return
        val retired = synchronized(lock) { owners.values.toList().also { owners.clear() } }
        retired.forEach(Owner::close) // Cancellation hooks never run inside entry/map locks.
    }

    private inner class Owner(private val mid: Long) : AutoCloseable {
        private val job = SupervisorJob(entryScope.coroutineContext[Job])
        private val pageScope = CoroutineScope(entryScope.coroutineContext + job)
        private val requestView = ThreadLocal<Invocation?>()
        private fun current(): Invocation = requestView.get()?.also { it.checkpoint() }
            ?: throw CancellationException("Tablet uploads has no captured invocation")
        private inner class Invocation(val binding: DesktopOriginalVideoRepositoryBinding, val caller: Job) {
            val history = DesktopOriginalHomeHistoryProtocol(binding.primaryApi)
            val actions = DesktopOriginalTabletOwnerSpaceActions(binding.primaryApi)
            val favorites = DesktopFavoriteEnvironment.forFolderDrawer(pageScope, binding.primaryApi,
                { caller.isActive && job.isActive && owns() && runCatching { binding.assertCurrent() }.isSuccess },
                binding::primaryCsrf, binding::primaryMid, feedback).favorite
            fun checkpoint() {
                caller.ensureActive(); job.ensureActive()
                if (!owns()) throw CancellationException("Tablet uploads owner retired")
                binding.assertCurrent()
            }
            fun admit(action: () -> Unit) {
                checkpoint()
                if (!binding.admitCurrentMutation {
                    caller.ensureActive(); job.ensureActive()
                    if (!owns()) throw CancellationException("Tablet uploads owner retired")
                    action()
                }) throw CancellationException("Tablet uploads response retired")
            }
        }
        private val environment = object : DesktopOriginalTabletOwnerSpaceEnvironment {
            override val scope = DesktopOriginalTabletOwnerRequestScope(pageScope) { block ->
                currentCoroutineContext().ensureActive()
                if (!owns()) throw CancellationException("Tablet uploads entry retired")
                val binding = captureRequest()
                val value = Invocation(binding, currentCoroutineContext().job)
                value.checkpoint()
                withContext(requestView.asContextElement(value)) { value.checkpoint(); block() }
            }
            override val api get() = current().binding.primaryApi
            override val spaceApi get() = current().binding.primarySpaceApi
            override val history get() = current().history
            override val favorites get() = current().favorites
            override val actions get() = current().actions
            override val followStateChanges get() = this@DesktopOriginalTabletOwnerUploadsPlatform.followStateChanges
            override fun primaryAccessToken() = current().binding.primaryAccessToken()
            override fun primaryAccessTokenPlatform() = current().binding.primaryAccessTokenPlatform()
            override fun requireMid(mid: Long) {
                require(mid == this@Owner.mid) { "Tablet uploads owner belongs to a different MID" }
                job.ensureActive()
                if (!owns()) throw CancellationException("Tablet uploads entry retired")
            }
            override fun <T> mutableStateFlow(initial: T): MutableStateFlow<T> {
                val state = MutableStateFlow(initial)
                return object : MutableStateFlow<T> by state {
                    override var value: T
                        get() = state.value
                        set(value) { current().admit { state.value = value } }
                    override fun compareAndSet(expect: T, update: T): Boolean {
                        var changed = false
                        current().admit { changed = state.compareAndSet(expect, update) }
                        return changed
                    }
                    override suspend fun emit(value: T) { this.value = value }
                    override fun tryEmit(value: T): Boolean { this.value = value; return true }
                }
            }
        }
        val loader = DesktopOriginalTabletOwnerSpaceLoader(environment)
        override fun close() { job.cancel(CancellationException("Tablet uploads owner closed")) }
    }
}
