package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.data.repository.SearchRepository.SearchPageInfo
import com.android.purebilibili.feature.live.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** The original four routes retain their own Compose state. This facade only tags requests
 * and publishes them through Root's existing immutable account/navigation admission. */
internal class DesktopLiveNavigationBinding(
    private val environment: DesktopHomeProtocolEnvironment,
    searchApi: SearchApi,
    globalStore: DesktopPluginStore,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val requestsBySlot = mutableMapOf<String, DesktopLiveNavigationRequest>()
    val requests = DesktopLiveNavigationRequests(environment, searchApi)
    val preferences = DesktopLiveFavoriteTagPreferences(globalStore, ::isOwned, ::commitUi)
    fun isOwned(): Boolean = synchronized(lock) { !closed } && environment.isCurrent()
    fun commitUi(action: () -> Unit): Boolean {
        var applied = false
        environment.commitIfCurrent {
            synchronized(lock) {
                if (!closed && environment.isCurrent()) { action(); applied = true }
            }
        }
        return applied
    }
    suspend fun begin(slot: String, replaceGroup: String? = null): DesktopLiveNavigationRequest {
        val job = currentCoroutineContext()[Job] ?: error("Live request requires its caller Job")
        job.ensureActive()
        val previous = mutableListOf<Job>()
        var request: DesktopLiveNavigationRequest? = null
        val admitted = environment.commitIfCurrent {
            synchronized(lock) {
                if (!closed && environment.isCurrent()) {
                    job.ensureActive()
                    if (replaceGroup != null) {
                        val old = requestsBySlot.filter { (_, value) -> value.group == replaceGroup }
                        old.forEach { (key, value) -> requestsBySlot.remove(key); previous += value.job }
                    }
                    requestsBySlot.remove(slot)?.let { previous += it.job }
                    request = DesktopLiveNavigationRequest(this, slot, slot.substringBefore('-'), job)
                    requestsBySlot[slot] = request!!
                }
            }
        }
        if (!admitted || request == null) throw CancellationException("Live navigation owner retired")
        // Never cancel another coroutine while holding Root's Store/entry admission monitor.
        previous.distinct().filter { it !== job }.forEach { it.cancel() }
        return request!!
    }
    fun invalidate(group: String) {
        val old = mutableListOf<Job>()
        environment.commitIfCurrent {
            synchronized(lock) {
                if (!closed) requestsBySlot.filter { (_, value) -> value.group == group }
                    .forEach { (key, value) -> requestsBySlot.remove(key); old += value.job }
            }
        }
        old.distinct().forEach { it.cancel() }
    }
    internal fun publish(request: DesktopLiveNavigationRequest, allowCancelled: Boolean, action: () -> Unit): Boolean {
        if (!allowCancelled) request.job.ensureActive()
        var applied = false
        environment.commitIfCurrent {
            synchronized(lock) {
                if (!closed && requestsBySlot[request.slot] === request &&
                    (allowCancelled || request.job.isActive) && environment.isCurrent()) {
                    action(); applied = true
                }
            }
        }
        return applied
    }
    internal fun finish(request: DesktopLiveNavigationRequest, action: () -> Unit) {
        publish(request, allowCancelled = true, action)
        synchronized(lock) { if (requestsBySlot[request.slot] === request) requestsBySlot.remove(request.slot) }
    }
    override fun close() {
        val old = synchronized(lock) {
            if (closed) emptyList() else { closed = true; requestsBySlot.values.map { it.job }.also { requestsBySlot.clear() } }
        }
        old.distinct().forEach { it.cancel() }
    }
}

internal class DesktopLiveNavigationRequest internal constructor(
    private val binding: DesktopLiveNavigationBinding,
    internal val slot: String,
    internal val group: String,
    internal val job: Job,
) {
    fun publish(action: () -> Unit) {
        if (!binding.publish(this, false, action)) throw CancellationException("Live request replaced or owner retired")
    }
    fun finish(action: () -> Unit) = binding.finish(this, action)
}

internal fun <T> Result<T>.onOwnedSuccess(request: DesktopLiveNavigationRequest, action: (T) -> Unit): Result<T> {
    (exceptionOrNull() as? CancellationException)?.let { throw it }
    if (isSuccess) request.publish { action(getOrThrow()) }
    return this
}
internal fun <T> Result<T>.onOwnedFailure(request: DesktopLiveNavigationRequest, action: (Throwable) -> Unit): Result<T> {
    val error = exceptionOrNull() ?: return this
    if (error is CancellationException) throw error
    request.publish { action(error) }
    return this
}

/** Existing raw694 / Live78 and dynamic search helpers remain sole original transports.
 * SearchApi and the existing BilibiliApi must be Root's same owner-tagged service, never a new client.
 * Original SearchRepository nav-key extraction/WBI unsigned fallback is selected verbatim. */
internal class DesktopLiveNavigationRequests(
    private val environment: DesktopHomeProtocolEnvironment,
    searchApi: SearchApi,
) {
    private val live = DesktopOriginalLiveListProtocol(environment)
    private val followed = DesktopOriginalHomeLiveProtocol(environment.api)
    private val search = DesktopOriginalLiveSearchProtocol(searchApi, environment.api)
    private val existingUpSearch = DesktopDynamicUpSearch(searchApi, search::signSearch)
    private suspend fun <T> owned(call: suspend () -> Result<T>): Result<T> {
        currentCoroutineContext().ensureActive()
        if (!environment.isCurrent()) throw CancellationException("Live owner retired")
        val value = call()
        currentCoroutineContext().ensureActive()
        if (!environment.isCurrent()) throw CancellationException("Live owner retired")
        (value.exceptionOrNull() as? CancellationException)?.let { throw it }
        return value
    }
    suspend fun getLiveAreaIndex() = owned { live.getLiveAreaIndex() }
    suspend fun getAreaRoomsPage(parentAreaId: Int, areaId: Int, page: Int, sortType: String, areaTitle: String) =
        owned { live.getAreaRoomsPage(parentAreaId = parentAreaId, areaId = areaId, page = page, sortType = sortType, areaTitle = areaTitle) }
    suspend fun getFollowedLivePage(page: Int) = owned { followed.getFollowedLivePage(page) }
    suspend fun searchLive(keyword: String, page: Int, order: SearchLiveOrder) = owned { search.searchLive(keyword, page, order) }
    suspend fun searchUp(keyword: String, page: Int) = owned { existingUpSearch.searchUp(keyword, page) }
}

/** Read/write the original key on Root's sole global settings backing, independent of MID.
 * Getter fallback, setter validity/dedup/take(12), and the UI's separate takeLast(8) policy
 * match original SettingsManager/LiveAreaScreenPolicy. No second settings schema is emitted. */
internal class DesktopLiveFavoriteTagPreferences(
    private val store: DesktopPluginStore,
    private val isOwned: () -> Boolean,
    private val commit: ((() -> Unit) -> Boolean),
) {
    private val key = "live_favorite_tags"
    val favoriteTags: Flow<List<LiveFavoriteTagEntry>> = store.snapshot("settings").map { prefs ->
        val raw = prefs[com.bilipai.desktop.plugins.stringPreferencesKey(key)].orEmpty()
        if (raw.isBlank()) emptyList()
        else runCatching { Json.decodeFromString<List<LiveFavoriteTagEntry>>(raw) }.getOrDefault(emptyList())
    }
    suspend fun setLiveFavoriteTags(tags: List<LiveFavoriteTagEntry>) {
        val normalized = tags
            .filter { it.parentAreaId > 0 && it.areaId >= 0 && it.title.isNotBlank() }
            .distinctBy { it.parentAreaId to it.areaId }
            .take(12)
        val value = if (normalized.isEmpty()) "" else Json.encodeToString(normalized)
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job] ?: error("Live favorite edit requires a Job")
            job.ensureActive()
            var applied = false
            commit {
                if (isOwned()) {
                    job.ensureActive()
                    // Root Store -> retained entry -> the one global plugin backing.
                    store.update("settings", mapOf(key to JsonPrimitive(value)))
                    applied = true
                }
            }
            if (!applied) throw CancellationException("Live favorite editor owner retired")
        }
    }
}

internal val LocalDesktopLiveNavigationBinding = staticCompositionLocalOf<DesktopLiveNavigationBinding> {
    error("Live sub-navigation requires Root's retained owner binding")
}

/** One immutable route's binding. Root retains it while video covers the route and closes it
 * only on route exit/replacement or epoch retirement, before awaiting jobs outside Store. */
@Composable internal fun DesktopLiveNavigationHost(binding: DesktopLiveNavigationBinding, content: @Composable () -> Unit) {
    key(binding) { CompositionLocalProvider(LocalDesktopLiveNavigationBinding provides binding, content = content) }
}
