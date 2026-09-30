package com.bilipai.desktop.data

import com.android.purebilibili.core.store.*
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.lang.ref.WeakReference

typealias DesktopRecommendationMode = DesktopFeedSettings.FeedApiType

/** Original feed settings and negative-feedback storage, bound to atomic Windows preferences. */
class DesktopDiscoveryPreferences(private val root: Path = DesktopLibrary.directoryForAccount(null),
    val blockedUps: DesktopBlockedUpStore = DesktopBlockedUpStore(DesktopPluginContext(DesktopPluginStore(root)))) {
    init { blockedUps.migrateLegacyDiscoveryMids() }
    private class AccountPreferences(val context: DesktopPluginContext) {
        val feedback = DiscoveryPreferenceState(context, "today_watch_feedback") { TodayWatchFeedbackStore.getSnapshot(context) }
    }
    private val lock = Any()
    private val restoreLock = Any()
    private var writesFrozen = false
    private val accounts = mutableMapOf<Long?, AccountPreferences>()
    init {
        synchronized(instances) {
            instances.removeAll { it.get() == null }
            instances.add(WeakReference(this))
        }
    }
    private fun state(mid: Long?): AccountPreferences = synchronized(lock) {
        require(mid == null || mid > 0)
        accounts.getOrPut(mid) {
            checkWritesOpen()
            val accountRoot = if (mid == null) root else root.resolve("accounts").resolve(mid.toString())
            AccountPreferences(DesktopPluginContext(DesktopPluginStore(accountRoot.resolve("discovery"))))
        }
    }
    private val settings = state(null).context.getSharedPreferences("feed_api", 0)
    val feedMode: StateFlow<DesktopRecommendationMode> = DiscoveryPreferenceState(state(null).context, "feed_api") {
        DesktopRecommendationMode.fromValue(settings.getInt("type", DesktopRecommendationMode.WEB.value)) }
    val refreshCount: StateFlow<Int> = DiscoveryPreferenceState(state(null).context, "feed_api") {
        normalizeHomeRefreshCount(settings.getInt("home_refresh_count", DEFAULT_HOME_REFRESH_COUNT)) }

    suspend fun setFeedMode(value: DesktopRecommendationMode) = withContext(Dispatchers.IO) {
        synchronized(lock) { checkWritesOpen(); settings.edit().putInt("type", value.value).apply() }
    }
    suspend fun setRefreshCount(value: Int) = withContext(Dispatchers.IO) {
        val normalized = normalizeHomeRefreshCount(value)
        synchronized(lock) { checkWritesOpen(); settings.edit().putInt("home_refresh_count", normalized).apply() }
    }
    fun feedback(mid: Long?): StateFlow<TodayWatchFeedbackSnapshot> = state(mid).feedback
    fun blockedCreators(mid: Long?): StateFlow<Set<Long>> { require(mid == null || mid > 0); return blockedUps.mids }
    internal fun recommendationContext(mid: Long?): DesktopPluginContext = state(mid).context

    internal fun record(mid: Long?, video: TodayWatchDislikedVideoSnapshot, keywords: Set<String>, blockCreator: Boolean) = synchronized(lock) {
        checkWritesOpen()
        val state = state(mid)
        val snapshot = state.feedback.value.withDislikedVideoFeedback(video, keywords, includeCreatorSignal = blockCreator)
        TodayWatchFeedbackStore.saveSnapshot(state.context, snapshot)
        if (blockCreator && video.creatorMid > 0) blockedUps.upsert(com.android.purebilibili.core.database.entity.BlockedUp(
            video.creatorMid, video.creatorName, "", blockedAt = video.dislikedAtMillis))
    }

    internal fun changeBlocked(mid: Long?, creatorMid: Long, blocked: Boolean) = synchronized(lock) {
        checkWritesOpen()
        require(creatorMid > 0)
        val state = state(mid)
        if (blocked) blockedUps.upsert(com.android.purebilibili.core.database.entity.BlockedUp(creatorMid, "", ""))
        else blockedUps.remove(creatorMid)
        if (!blocked && creatorMid in state.feedback.value.dislikedCreatorMids) {
            val feedback = state.feedback.value.copy(dislikedCreatorMids = state.feedback.value.dislikedCreatorMids - creatorMid)
            TodayWatchFeedbackStore.saveSnapshot(state.context, feedback)
        }
    }

    suspend fun clearFeedback(mid: Long?) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            checkWritesOpen()
            val state = state(mid)
            TodayWatchFeedbackStore.clear(state.context)
        }
    }

    /** Root waits for accepted writes, then retires every held account/guest facade before restoration. */
    fun freezeWritesForRestore(): Unit = synchronized(restoreLock) {
        if (synchronized(lock) { writesFrozen }) return@synchronized
        val key = root.toAbsolutePath().normalize()
        val peers = synchronized(instances) {
            instances.removeAll { it.get() == null }
            instances.mapNotNull { it.get() }.filter { it.root.toAbsolutePath().normalize() == key }
        }
        peers.forEach { peer -> synchronized(peer.lock) {
            peer.writesFrozen = true
            peer.accounts.values.forEach { it.context.store.freezeWrites() }
        } }
        // The full-model blacklist shares the global backing, independent of account feedback.
        peers.forEach { it.blockedUps.context.store.freezeWrites() }
    }

    private fun checkWritesOpen() = check(!writesFrozen) { "推荐设置已关闭，不能写入或创建旧会话数据" }

    private companion object {
        val instances = mutableListOf<WeakReference<DesktopDiscoveryPreferences>>()
    }
}

/** Derive values from the shared atomic preference snapshot without a second cache or background job. */
private class DiscoveryPreferenceState<T : Any>(context: DesktopPluginContext, namespace: String, private val decode: () -> T) : StateFlow<T> {
    private val source = context.store.snapshot(namespace)
    private var lastSnapshot: Any? = null
    private var decoded: T? = null
    private val lock = Any()
    override val value: T get() = synchronized(lock) {
        val snapshot = source.value
        if (snapshot !== lastSnapshot || decoded == null) { decoded = decode(); lastSnapshot = snapshot }
        requireNotNull(decoded)
    }
    override val replayCache: List<T> get() = listOf(value)
    @OptIn(InternalCoroutinesApi::class)
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        source.map { value }.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
