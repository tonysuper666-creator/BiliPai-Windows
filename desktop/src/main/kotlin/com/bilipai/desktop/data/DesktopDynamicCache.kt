package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.DynamicItem
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicCacheKeys
import com.android.purebilibili.feature.dynamic.normalizeDynamicNotInterestedIds
import com.android.purebilibili.feature.dynamic.dynamicAccountStorageName
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicUserPreferenceKeys
import com.bilipai.desktop.plugins.DesktopPreferenceKey
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
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

/** One Root-owned writer; the original MID/guest policy selects namespaces in the same Store. */
internal class DesktopDynamicCache(
    private val guard: DesktopDynamicCacheSessionGuard,
    private val store: DesktopPluginStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private sealed interface Work {
        data class Open(val owner: DesktopDynamicCacheOwner, val caller: Job, val result: CompletableDeferred<DesktopDynamicCacheSession?>) : Work
        data object SaveLatest : Work
        data class Mark(val session: DesktopDynamicCacheSession, val id: String, val result: CompletableDeferred<Unit>) : Work
        data class Barrier(val result: CompletableDeferred<Unit>) : Work
    }
    private data class PendingSave(val session: DesktopDynamicCacheSession, val items: List<DynamicItem>,
                                   val publishCurrent: ((()->Unit)->Boolean)?)
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
            is Work.Open -> try { work.result.complete(open(work.owner, work.caller)) }
                catch (failure: Exception) { work.result.completeExceptionally(failure) }
            Work.SaveLatest -> {
                val pending = synchronized(gate) { pendingSave.also { pendingSave = null; saveQueued = false } }
                if (pending != null) try { save(pending) }
                    catch (failure: Exception) { withPendingPublication(pending) { pending.session.mutableWriteFailure.value = failure } }
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
        enqueue(Work.Open(owner, currentCoroutineContext()[Job] ?: error("Dynamic cache requires a caller Job"), result))
        return result.await()
    }

    internal fun withCurrentSession(session: DesktopDynamicCacheSession, block: () -> Unit): Boolean =
        guard.withCurrentDynamicCacheOwner(session.owner) {
            check(active === session) { "账号已切换，请重新加载" }
            block()
        }

    internal fun saveTimeline(session: DesktopDynamicCacheSession, items: List<DynamicItem>,
                              publishCurrent: ((()->Unit)->Boolean)? = null) {
        try {
            // Original DynamicViewModel does not persist or cold-seed a guest timeline.
            if (session.owner.mid <= 0L) {
                check(withCurrentSession(session) {}) { "账号已切换，请重新加载" }
                return
            }
            var accepted=false
            val enqueueCurrent:()->Unit = {
              accepted = withCurrentSession(session) {
                synchronized(gate) {
                    check(accepting) { "动态缓存已停止" }
                    val next = PendingSave(session, items.take(DesktopOriginalDynamicCacheKeys.MAX_CACHE_ITEMS).toList(),publishCurrent)
                    if (!saveQueued) {
                        check(queue.trySend(Work.SaveLatest).isSuccess) { "动态缓存写入队列已满" }
                        saveQueued = true
                    }
                    // Empty is a real latest version too; an older nonempty snapshot cannot revive it.
                    pendingSave = next
                }
              }
            }
            if(publishCurrent==null)enqueueCurrent() else check(publishCurrent(enqueueCurrent)) { "动态来源已退休" }
            check(accepted) { "账号已切换，请重新加载" }
        } catch (failure: Exception) {
            if(publishCurrent==null)session.mutableWriteFailure.value=failure
            else publishCurrent { session.mutableWriteFailure.value=failure }
        }
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

    private fun currentOwner(owner: DesktopDynamicCacheOwner, caller: Job? = null) {
        caller?.ensureActive()
        check(guard.dynamicCacheOwner() == owner) { "账号已切换，请重新加载" }
    }

    private fun permit(owner: DesktopDynamicCacheOwner, caller: Job? = null,
                       session: DesktopDynamicCacheSession? = null,
                       requireAccepting: Boolean = false,
                       publishCurrent: ((()->Unit)->Boolean)? = null): DesktopPluginStore.OriginalPreferenceWritePermit {
        var result: DesktopPluginStore.OriginalPreferenceWritePermit? = null
        val acquire:()->Unit = {
            check(guard.withCurrentDynamicCacheOwner(owner) {
                caller?.ensureActive()
                if (session != null) check(active === session) { "动态缓存实例已退休" }
                if (requireAccepting) synchronized(gate) { check(accepting) { "动态缓存已停止" } }
                result = DesktopPluginStore.OriginalPreferenceWritePermit(store)
            }) { "账号已切换，请重新加载" }
        }
        if(publishCurrent==null)acquire() else check(publishCurrent(acquire)) { "动态来源已退休" }
        return checkNotNull(result)
    }

    /** Legacy namespaces are preserved. Only their exact current credential owner tag proves migration. */
    private fun migrateOwnedLegacy(owner: DesktopDynamicCacheOwner, caller: Job, legacyName: String,
                                   name: String, keys: List<String>) {
        currentOwner(owner, caller)
        store.requireObjectNamespace(name)
        if (store.preferences(name).isNotEmpty()) return
        val legacy = store.preferences(legacyName)
        if (ownerTag(legacy) != owner.namespaceTag) return
        val copied = keys.mapNotNull { key -> legacy[key]?.let { key to it } }.toMap()
        store.updateOriginalFromSnapshot(name, { currentOwner(owner, caller) },
            { permit(owner, caller) }) { snapshot ->
            val alreadyPresent = (keys + OWNER_KEY).any { snapshot[rawKey(it)] != null }
            Unit to if (alreadyPresent) emptyMap() else copied + (OWNER_KEY to JsonPrimitive(owner.namespaceTag))
        }
    }

    private fun open(owner: DesktopDynamicCacheOwner, caller: Job): DesktopDynamicCacheSession? {
        currentOwner(owner, caller)
        active?.takeIf { it.owner == owner }?.let { current ->
            return if (guard.withCurrentDynamicCacheOwner(owner) { caller.ensureActive() }) current else null
        }
        val name = dynamicAccountStorageName(DesktopOriginalDynamicCacheKeys.PREFS_DYNAMIC_CACHE, owner.mid)
        var failure: Exception? = null
        val values = try {
            migrateOwnedLegacy(owner, caller, DesktopOriginalDynamicCacheKeys.PREFS_DYNAMIC_CACHE, name,
                listOf(DesktopOriginalDynamicCacheKeys.KEY_DYNAMIC_CACHE,
                    DesktopOriginalDynamicCacheKeys.KEY_DYNAMIC_CACHE_TIME,
                    DesktopOriginalDynamicCacheKeys.KEY_NOT_INTERESTED_DYNAMIC_IDS))
            val users = DesktopOriginalDynamicUserPreferenceKeys.PREFS_DYNAMIC_USERS
            migrateOwnedLegacy(owner, caller, users, dynamicAccountStorageName(users, owner.mid),
                listOf(DesktopOriginalDynamicUserPreferenceKeys.KEY_PINNED_USERS,
                    DesktopOriginalDynamicUserPreferenceKeys.KEY_HIDDEN_USERS,
                    DesktopOriginalDynamicUserPreferenceKeys.KEY_SELECTED_TAB))
            store.requireObjectNamespace(name)
            store.preferences(name)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { failure = error; JsonObject(emptyMap()) }
        currentOwner(owner, caller)
        val cached = if (owner.mid > 0L) {
            val encoded = (values[DesktopOriginalDynamicCacheKeys.KEY_DYNAMIC_CACHE] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            encoded?.let { runCatching { json.decodeFromString<List<DynamicItem>>(it) }.getOrNull() }.orEmpty()
        } else emptyList()
        val session = DesktopDynamicCacheSession(this, owner, cached, decodeIds(values)).also {
            it.mutableWriteFailure.value = failure
        }
        return if (guard.withCurrentDynamicCacheOwner(owner) { caller.ensureActive(); active = session }) session else null
    }

    internal fun userPreferences(session: DesktopDynamicCacheSession, target: DesktopPluginStore): StateFlow<DesktopPreferenceSnapshot> {
        require(target === store) { "Dynamic preferences require the same Root Store" }
        var result: StateFlow<DesktopPreferenceSnapshot>? = null
        check(withCurrentSession(session) {
            store.requireObjectNamespace(session.userPreferenceNamespace)
            result = store.snapshot(session.userPreferenceNamespace)
        }) { "账号已切换，请重新加载" }
        return checkNotNull(result)
    }

    internal fun updateUserPreferences(session: DesktopDynamicCacheSession, target: DesktopPluginStore,
                                      caller: Job, edit: (DesktopPreferenceSnapshot) -> Map<String, JsonElement?>) {
        require(target === store) { "Dynamic preferences require the same Root Store" }
        store.updateOriginalFromSnapshot(session.userPreferenceNamespace,
            { caller.ensureActive(); requireCurrent(session); synchronized(gate) { check(accepting) { "动态缓存已停止" } } },
            { permit(session.owner, caller, session, requireAccepting = true) }) { snapshot ->
            Unit to (edit(snapshot) + (OWNER_KEY to JsonPrimitive(session.owner.namespaceTag)))
        }
    }

    private fun withPendingPublication(pending:PendingSave,block:()->Unit):Boolean {
        val publish=pending.publishCurrent
        return if(publish==null){block();true}else publish(block)
    }
    private fun save(pending: PendingSave) {
        val session = pending.session
        if(!withPendingPublication(pending){requireCurrent(session)})return
        val payload = pending.items.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) }
        store.updateOriginalFromSnapshot(session.cacheNamespace,
            { check(withPendingPublication(pending){requireCurrent(session)}) { "动态来源已退休" } },
            { permit(session.owner, session = session,publishCurrent=pending.publishCurrent) }) {
            Unit to mapOf(OWNER_KEY to JsonPrimitive(session.owner.namespaceTag),
                DesktopOriginalDynamicCacheKeys.KEY_DYNAMIC_CACHE to payload?.let(::JsonPrimitive),
                DesktopOriginalDynamicCacheKeys.KEY_DYNAMIC_CACHE_TIME to payload?.let { JsonPrimitive(nowMs()) })
        }
        withPendingPublication(pending) {
            check(withCurrentSession(session) {
                session.mutableCachedAllItems.value = pending.items
                session.mutableWriteFailure.value = null
            }) { "账号已切换，请重新加载" }
        }
    }

    private fun mark(session: DesktopDynamicCacheSession, id: String) {
        requireCurrent(session)
        val next = store.updateOriginalFromSnapshot(session.cacheNamespace, { requireCurrent(session) },
            { permit(session.owner, session = session) }) { snapshot ->
            val ids = snapshot[rawKey(DesktopOriginalDynamicCacheKeys.KEY_NOT_INTERESTED_DYNAMIC_IDS)]
                ?.let { decodeIds(JsonObject(mapOf(DesktopOriginalDynamicCacheKeys.KEY_NOT_INTERESTED_DYNAMIC_IDS to it))) }.orEmpty()
            val normalized = normalizeDynamicNotInterestedIds(ids + id, DesktopOriginalDynamicCacheKeys.MAX_NOT_INTERESTED_DYNAMIC_IDS)
            normalized to mapOf(OWNER_KEY to JsonPrimitive(session.owner.namespaceTag),
                DesktopOriginalDynamicCacheKeys.KEY_NOT_INTERESTED_DYNAMIC_IDS to JsonArray(normalized.map(::JsonPrimitive)))
        }
        check(withCurrentSession(session) {
            session.mutableNotInterestedIds.value = next
            session.mutableWriteFailure.value = null
        }) { "账号已切换，请重新加载" }
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
        fun rawKey(name: String) = DesktopPreferenceKey<JsonElement>(name) { it }
    }
}

internal class DesktopDynamicCacheSession internal constructor(
    private val cache: DesktopDynamicCache,
    internal val owner: DesktopDynamicCacheOwner,
    cachedAll: List<DynamicItem>,
    notInterested: Set<String>,
) {
    internal val cacheNamespace = dynamicAccountStorageName(DesktopOriginalDynamicCacheKeys.PREFS_DYNAMIC_CACHE, owner.mid)
    internal val userPreferenceNamespace = dynamicAccountStorageName(DesktopOriginalDynamicUserPreferenceKeys.PREFS_DYNAMIC_USERS, owner.mid)
    internal fun userPreferences(store: DesktopPluginStore) = cache.userPreferences(this, store)
    internal fun updateUserPreferences(store: DesktopPluginStore, caller: Job,
                                      edit: (DesktopPreferenceSnapshot) -> Map<String, JsonElement?>) =
        cache.updateUserPreferences(this, store, caller, edit)
    internal val mutableCachedAllItems = MutableStateFlow(cachedAll)
    internal val mutableNotInterestedIds = MutableStateFlow(notInterested)
    internal val mutableWriteFailure = MutableStateFlow<Throwable?>(null)
    val cachedAllItems: StateFlow<List<DynamicItem>> = mutableCachedAllItems.asStateFlow()
    val notInterestedIds: StateFlow<Set<String>> = mutableNotInterestedIds.asStateFlow()
    val writeFailure: StateFlow<Throwable?> = mutableWriteFailure.asStateFlow()
    fun saveTimeline(items: List<DynamicItem>,publishCurrent:((()->Unit)->Boolean)?=null) = cache.saveTimeline(this, items,publishCurrent)
    suspend fun markNotInterested(dynamicId: String) = cache.markNotInterested(this, dynamicId)
}
