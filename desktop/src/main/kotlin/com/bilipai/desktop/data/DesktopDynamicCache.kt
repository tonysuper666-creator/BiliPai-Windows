package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.DynamicItem
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicCacheKeys
import com.android.purebilibili.feature.dynamic.normalizeDynamicNotInterestedIds
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** One Root-owned writer for the original global dynamic cache namespace. */
internal class DesktopDynamicCache(
    private val guard: DesktopDynamicCacheSessionGuard,
    private val store: DesktopPluginStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private sealed interface Work {
        data class Open(val owner: DesktopDynamicCacheOwner, val result: CompletableDeferred<DesktopDynamicCacheSession?>) : Work
        data object SaveLatest : Work
        data class Mark(val session: DesktopDynamicCacheSession, val id: String, val result: CompletableDeferred<Unit>) : Work
        data class Barrier(val result: CompletableDeferred<Unit>) : Work
    }
    private data class PendingSave(val session: DesktopDynamicCacheSession, val items: List<DynamicItem>)
    private val gate = Any()
    private val shutdown = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Work>(64)
    private var accepting = true
    private var saveQueued = false
    private var pendingSave: PendingSave? = null
    @Volatile private var active: DesktopDynamicCacheSession? = null
    private val worker = scope.launch {
        for (work in queue) when (work) {
            is Work.Open -> try { work.result.complete(open(work.owner)) }
                catch (failure: Exception) { work.result.completeExceptionally(failure) }
            Work.SaveLatest -> {
                val pending = synchronized(gate) { pendingSave.also { pendingSave = null; saveQueued = false } }
                if (pending != null) try { save(pending) }
                    catch (failure: Exception) { pending.session.mutableWriteFailure.value = failure }
            }
            is Work.Mark -> try { mark(work.session, work.id); work.result.complete(Unit) }
                catch (failure: Exception) {
                    work.session.mutableWriteFailure.value = failure
                    work.result.completeExceptionally(failure)
                }
            is Work.Barrier -> work.result.complete(Unit)
        }
    }

    /** Await the cold seed before creating a page or starting its original startup barrier. */
    suspend fun openCurrent(): DesktopDynamicCacheSession? {
        val owner = guard.dynamicCacheOwner() ?: return null
        val result = CompletableDeferred<DesktopDynamicCacheSession?>()
        enqueue(Work.Open(owner, result))
        return result.await()
    }

    internal fun withCurrentSession(session: DesktopDynamicCacheSession, block: () -> Unit): Boolean =
        guard.withCurrentDynamicCacheOwner(session.owner) {
            check(active === session) { "账号已切换，请重新加载" }
            block()
        }

    internal fun saveTimeline(session: DesktopDynamicCacheSession, items: List<DynamicItem>) {
        try {
            val accepted = withCurrentSession(session) {
                synchronized(gate) {
                    check(accepting) { "动态缓存已停止" }
                    val next = PendingSave(session, items.take(DesktopOriginalDynamicCacheKeys.MAX_CACHE_ITEMS).toList())
                    if (!saveQueued) {
                        check(queue.trySend(Work.SaveLatest).isSuccess) { "动态缓存写入队列已满" }
                        saveQueued = true
                    }
                    // Empty is a real latest version too; an older nonempty snapshot cannot revive it.
                    pendingSave = next
                }
            }
            check(accepted) { "账号已切换，请重新加载" }
        } catch (failure: Exception) { session.mutableWriteFailure.value = failure }
    }

    internal suspend fun markNotInterested(session: DesktopDynamicCacheSession, id: String) {
        require(id.isNotBlank()) { "无法识别该动态" }
        val result = CompletableDeferred<Unit>()
        check(withCurrentSession(session) { enqueue(Work.Mark(session, id, result)) }) { "账号已切换，请重新加载" }
        // Accepted work belongs to Root. Cancelling the card cannot cancel its disk transaction.
        result.await()
    }

    suspend fun flush() {
        val result = CompletableDeferred<Unit>()
        enqueue(Work.Barrier(result))
        result.await()
    }

    fun stopAccepting() = synchronized(gate) {
        if (accepting) { accepting = false; queue.close() }
    }

    /** Drain accepted writes before the shared PluginStore generation is frozen or restored. */
    suspend fun shutdownForRestore() = withContext(NonCancellable) {
        shutdown.withLock {
            stopAccepting()
            worker.join()
            scope.cancel()
        }
    }

    private fun enqueue(work: Work) = synchronized(gate) {
        check(accepting) { "动态缓存已停止" }
        check(queue.trySend(work).isSuccess) { "动态缓存写入队列已满" }
    }

    private fun requireCurrent(session: DesktopDynamicCacheSession) {
        check(active === session && guard.dynamicCacheOwner() == session.owner) { "账号已切换，请重新加载" }
    }

    private fun open(owner: DesktopDynamicCacheOwner): DesktopDynamicCacheSession? {
        var session: DesktopDynamicCacheSession? = null
        val current = guard.withCurrentDynamicCacheOwner(owner) {
            if (active?.owner == owner) { session = active; return@withCurrentDynamicCacheOwner }
            val values = try {
                store.requireObjectNamespace(DesktopOriginalDynamicCacheKeys.PREFS_DYNAMIC_CACHE)
                store.preferences(DesktopOriginalDynamicCacheKeys.PREFS_DYNAMIC_CACHE)
            } catch (failure: Exception) {
                // A local cache problem is visible, but must not prevent a usable network feed.
                session = DesktopDynamicCacheSession(this, owner, emptyList(), emptySet()).also {
                    it.mutableWriteFailure.value = failure; active = it
                }
                return@withCurrentDynamicCacheOwner
            }
            val owned = ownerTag(values) == owner.namespaceTag
            val cached = if (owned) {
                val encoded = (values[DesktopOriginalDynamicCacheKeys.KEY_DYNAMIC_CACHE] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                encoded?.let { runCatching { json.decodeFromString<List<DynamicItem>>(it) }.getOrNull() }.orEmpty()
            } else emptyList()
            val ids = if (owned) decodeIds(values) else emptySet()
            session = DesktopDynamicCacheSession(this, owner, cached, ids).also { active = it }
        }
        return if (current) session else null
    }

    private fun save(pending: PendingSave) {
        val session = pending.session
        requireCurrent(session)
        val payload = pending.items.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) }
        val accepted = guard.withCurrentDynamicCacheOwner(session.owner) {
            store.updateFromSnapshot(DesktopOriginalDynamicCacheKeys.PREFS_DYNAMIC_CACHE) {
                ownerChanges(session.owner).apply {
                    put(DesktopOriginalDynamicCacheKeys.KEY_DYNAMIC_CACHE, payload?.let(::JsonPrimitive))
                    put(DesktopOriginalDynamicCacheKeys.KEY_DYNAMIC_CACHE_TIME, payload?.let { JsonPrimitive(nowMs()) })
                }
            }
            session.mutableCachedAllItems.value = pending.items
            session.mutableWriteFailure.value = null
        }
        check(accepted) { "账号已切换，请重新加载" }
    }

    private fun mark(session: DesktopDynamicCacheSession, id: String) {
        requireCurrent(session)
        val accepted = guard.withCurrentDynamicCacheOwner(session.owner) {
            var next = emptySet<String>()
            store.updateFromSnapshot(DesktopOriginalDynamicCacheKeys.PREFS_DYNAMIC_CACHE) {
                val values = store.preferences(DesktopOriginalDynamicCacheKeys.PREFS_DYNAMIC_CACHE)
                val owned = ownerTag(values) == session.owner.namespaceTag
                next = normalizeDynamicNotInterestedIds((if (owned) decodeIds(values) else emptySet()) + id,
                    DesktopOriginalDynamicCacheKeys.MAX_NOT_INTERESTED_DYNAMIC_IDS)
                ownerChanges(session.owner).apply {
                    put(DesktopOriginalDynamicCacheKeys.KEY_NOT_INTERESTED_DYNAMIC_IDS, JsonArray(next.map(::JsonPrimitive)))
                }
            }
            session.mutableNotInterestedIds.value = next
            session.mutableWriteFailure.value = null
        }
        check(accepted) { "账号已切换，请重新加载" }
    }

    /** Called under both the original SessionStore and PluginStore monitors. */
    private fun ownerChanges(owner: DesktopDynamicCacheOwner): MutableMap<String, JsonElement?> {
        val values = store.preferences(DesktopOriginalDynamicCacheKeys.PREFS_DYNAMIC_CACHE)
        return linkedMapOf<String, JsonElement?>(OWNER_KEY to JsonPrimitive(owner.namespaceTag)).apply {
            if (ownerTag(values) != owner.namespaceTag) {
                put(DesktopOriginalDynamicCacheKeys.KEY_DYNAMIC_CACHE, null)
                put(DesktopOriginalDynamicCacheKeys.KEY_DYNAMIC_CACHE_TIME, null)
                put(DesktopOriginalDynamicCacheKeys.KEY_NOT_INTERESTED_DYNAMIC_IDS, null)
            }
        }
    }

    private fun decodeIds(values: JsonObject): Set<String> = normalizeDynamicNotInterestedIds(
        (values[DesktopOriginalDynamicCacheKeys.KEY_NOT_INTERESTED_DYNAMIC_IDS] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }.orEmpty(),
        DesktopOriginalDynamicCacheKeys.MAX_NOT_INTERESTED_DYNAMIC_IDS)

    private fun ownerTag(values: JsonObject): String? =
        (values[OWNER_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val OWNER_KEY = "_desktop_session_owner_v1"
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal class DesktopDynamicCacheSession internal constructor(
    private val cache: DesktopDynamicCache,
    internal val owner: DesktopDynamicCacheOwner,
    cachedAll: List<DynamicItem>,
    notInterested: Set<String>,
) {
    internal val mutableCachedAllItems = MutableStateFlow(cachedAll)
    internal val mutableNotInterestedIds = MutableStateFlow(notInterested)
    internal val mutableWriteFailure = MutableStateFlow<Throwable?>(null)
    val cachedAllItems: StateFlow<List<DynamicItem>> = mutableCachedAllItems.asStateFlow()
    val notInterestedIds: StateFlow<Set<String>> = mutableNotInterestedIds.asStateFlow()
    val writeFailure: StateFlow<Throwable?> = mutableWriteFailure.asStateFlow()
    fun saveTimeline(items: List<DynamicItem>) = cache.saveTimeline(this, items)
    suspend fun markNotInterested(dynamicId: String) = cache.markNotInterested(this, dynamicId)
}
