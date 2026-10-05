package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.refresh.HistoryRefreshBus
import com.android.purebilibili.core.refresh.WatchLaterRefreshBus
import com.android.purebilibili.feature.list.*
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean

/** Navigation-entry scope, not another account/items/cache. Full original History/Liked
 * ViewModels and repositories are already the sole installed Favorites producer outputs.
 * Root retains this with its Home gate while Video/audio covers the list. */
internal class DesktopPersonalListsRoot(
    val gate: DesktopHomeRetainedGate,
    private val repository: DesktopRepository,
    private val globalStore: DesktopPluginStore,
    internal val recapContext: com.bilipai.desktop.plugins.DesktopPluginContext,
    library: DesktopLibrary,
    privacyModeEnabled: () -> Boolean,
    feedback: (String) -> Unit,
    val globalHazeState: dev.chrisbanes.haze.HazeState,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val job = SupervisorJob(gate.scope.coroutineContext[Job])
    val scope = CoroutineScope(gate.scope.coroutineContext + job)
    init { require(recapContext.store === globalStore) { "History recap must use Root's existing Store" } }
    val preferences = DesktopFavoritePreferences(globalStore)
    val historySearchChannel = Channel<String>(Channel.CONFLATED)
    val historyScrollToTopChannel = Channel<Unit>(Channel.CONFLATED)
    val watchLaterSearchChannel = Channel<String>(Channel.CONFLATED)
    val watchLaterScrollToTopChannel = Channel<Unit>(Channel.CONFLATED)
    private val histories = linkedMapOf<BiliPaiNavKey, DesktopPersonalListEntry>()
    private val liked = linkedMapOf<BiliPaiNavKey.LikedVideos, DesktopPersonalListEntry>()
    private val watchLater = linkedMapOf<BiliPaiNavKey, DesktopWatchLaterEntry>()
    private val following = linkedMapOf<BiliPaiNavKey.Following,DesktopFollowingEntry>()
    private val entriesLock = Any()

    fun owns(): Boolean = !closed.get() && job.isActive && gate.owns()
    private fun assertOwned() { if (!owns()) throw CancellationException("Personal list owner retired") }
    private fun <T> api(type: Class<T>, entry: DesktopPersonalListEntry): T =
        repository.ownedHomeService(type, "https://api.bilibili.com/", gate.epoch, entry::owns)

    private val cachedPosition: (String, Long) -> Long = { bvid, cid ->
        assertOwned()
        library.resumeCard(bvid)?.takeIf { it.preferredCid == cid }?.progressSeconds
            ?.coerceAtLeast(0)?.toLong()?.times(1000L) ?: 0L
    }
    private val privacy = privacyModeEnabled
    private val onFeedback = feedback
    fun articleBindings(leafScope: CoroutineScope): DesktopOriginalArticleBindings {
        assertOwned()
        return desktopOriginalArticleBindings(repository, gate, leafScope, privacy)
    }
    private fun environment(entry: DesktopPersonalListEntry): DesktopFavoriteEnvironment =
        environment(entry.scope, entry::owns, entry::assertOwned, entry::commit)
    private fun environment(entryScope: CoroutineScope, owned: () -> Boolean,
        check: () -> Unit, commit: ((() -> Unit) -> Boolean)): DesktopFavoriteEnvironment {
        fun <T> service(type: Class<T>): T = repository.ownedHomeService(type,
            "https://api.bilibili.com/", gate.epoch, owned)
        val capturedFollowOwner=repository.dynamicCacheSessionGuard.dynamicCacheOwner()?.takeIf {
            it.epoch==gate.epoch && it.mid==(gate.mid ?: 0L)
        } ?: throw CancellationException("Personal account owner retired")
        return DesktopFavoriteEnvironment(entryScope, service(BilibiliApi::class.java),
            service(SpaceApi::class.java), service(DynamicApi::class.java), service(BangumiApi::class.java),
            owned, { repository.ownedHomeCookie("bili_jct", gate.epoch, owned) },
            { check(); gate.mid }, { message -> commit { onFeedback(message) } },
            HistoryRefreshBus.changes.map { DesktopHomeClock.elapsedRealtime() },
            cachedPosition, { check(); privacy() }, WatchLaterRefreshBus::notifyChanged,
            { repository.ownedHomeAccessToken(gate.epoch, owned) },
            { repository.withPrimaryPlaybackAdmission(gate.epoch, owned) { repository.accessTokenCredentials().second } },
            { change -> check(); repository.followStateEvents.confirm(capturedFollowOwner,change) })
    }

    fun watchLater(key: BiliPaiNavKey): DesktopWatchLaterEntry {
        require(key == BiliPaiNavKey.WatchLater || key is BiliPaiNavKey.WatchLaterSearch)
        assertOwned()
        synchronized(entriesLock) { watchLater[key] }?.let { return it }
        val created = DesktopWatchLaterEntry(this,key).also { entry ->
            val favorites = environment(entry.scope,entry::owns,entry::assertOwned,entry::commit)
            val env = com.android.purebilibili.feature.watchlater.DesktopWatchLaterEnvironment(favorites,
                { force -> repository.homeWbiKeys(gate.epoch,entry::owns,favorites.api,force) },
                { failure -> java.util.logging.Logger.getLogger("BiliPai.WatchLater").warning(failure.javaClass.simpleName) })
            entry.install(env,com.android.purebilibili.feature.watchlater.WatchLaterViewModel(env))
        }
        val selected = synchronized(entriesLock) { if(!owns()) null else watchLater.getOrPut(key) {created} }
        if(selected !== created) created.close()
        return selected ?: throw CancellationException("Personal root retired during WatchLater creation")
    }

    fun following(key: BiliPaiNavKey.Following): DesktopFollowingEntry {
        assertOwned()
        synchronized(entriesLock) {following[key]}?.let {return it}
        val created=DesktopFollowingEntry(this,key).also {entry ->
            val favorites=environment(entry.scope,entry::owns,entry::assertOwned,entry::commit)
            val cache=com.android.purebilibili.feature.following.DesktopFollowingCacheContext(globalStore,entry::owns,entry::commit)
            entry.install(com.android.purebilibili.feature.following.DesktopFollowingEnvironment(favorites,cache))
        }
        val selected=synchronized(entriesLock) {if(!owns())null else following.getOrPut(key) {created}}
        if(selected !== created)created.close()
        return selected ?: throw CancellationException("Personal root retired during Following creation")
    }

    fun history(key: BiliPaiNavKey): DesktopPersonalListEntry {
        require(key == BiliPaiNavKey.History || key is BiliPaiNavKey.HistorySearch)
        assertOwned()
        synchronized(entriesLock) { histories[key] }?.let { return it }
        val created = DesktopPersonalListEntry(this, key).also { entry ->
            val env = environment(entry)
            entry.install(env, HistoryViewModel(env), FavoriteCategoryViewModel(env))
        }
        val selected = synchronized(entriesLock) { if (!owns()) null else histories.getOrPut(key) { created } }
        if (selected !== created) created.close()
        return selected ?: throw CancellationException("Personal root retired during History creation")
    }
    fun liked(key: BiliPaiNavKey.LikedVideos): DesktopPersonalListEntry {
        assertOwned()
        synchronized(entriesLock) { liked[key] }?.let { return it }
        val created = DesktopPersonalListEntry(this, key).also { entry ->
            val env = environment(entry)
            entry.install(env, LikedVideosViewModel(env, key.mid.takeIf { it > 0 },
                key.ownerName.takeIf { it.isNotBlank() }, key.isCoinArchive), FavoriteCategoryViewModel(env))
        }
        val selected = synchronized(entriesLock) { if (!owns()) null else liked.getOrPut(key) { created } }
        if (selected !== created) created.close()
        return selected ?: throw CancellationException("Personal root retired during Liked creation")
    }
    /** Root calls after actual stack changes. Root History is a MainHost destination and
     * retained even if another pager tab is selected; popped search/liked entries retire. */
    fun prune(actualStack: List<BiliPaiNavKey>) {
        val keep = actualStack.toSet()
        val retired = synchronized(entriesLock) {
            histories.keys.filter { it != BiliPaiNavKey.History && it !in keep }
                .mapNotNull(histories::remove) + liked.keys.filterNot(keep::contains).mapNotNull(liked::remove)
        }
        retired.forEach(DesktopPersonalListEntry::close)
        val oldWatch = synchronized(entriesLock) {
            watchLater.keys.filter {it != BiliPaiNavKey.WatchLater && it !in keep}.mapNotNull(watchLater::remove)
        }
        oldWatch.forEach(DesktopWatchLaterEntry::close)
        val oldFollowing=synchronized(entriesLock) {following.keys.filterNot(keep::contains).mapNotNull(following::remove)}
        oldFollowing.forEach(DesktopFollowingEntry::close)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        historySearchChannel.close(); historyScrollToTopChannel.close()
        watchLaterSearchChannel.close(); watchLaterScrollToTopChannel.close()
        val old = synchronized(entriesLock) {
            (histories.values.toList() + liked.values.toList()).also { histories.clear(); liked.clear() }
        }
        old.forEach(DesktopPersonalListEntry::close)
        val oldWatch = synchronized(entriesLock) { watchLater.values.toList().also {watchLater.clear()} }
        oldWatch.forEach(DesktopWatchLaterEntry::close)
        val oldFollowing=synchronized(entriesLock) {following.values.toList().also {following.clear()}}
        oldFollowing.forEach(DesktopFollowingEntry::close); job.cancel()
    }
    suspend fun closeAndJoin() = withContext(NonCancellable) { close(); job.join() }
}

internal class DesktopPersonalListEntry(
    private val root: DesktopPersonalListsRoot,
    val key: BiliPaiNavKey,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val job = SupervisorJob(root.scope.coroutineContext[Job])
    val scope = CoroutineScope(root.scope.coroutineContext + job)
    lateinit var environment: DesktopFavoriteEnvironment; private set
    lateinit var viewModel: BaseListViewModel; private set
    lateinit var categories: FavoriteCategoryViewModel; private set
    private var queue: DesktopFavoriteQueueBridge? = null
    val saveableKey: String = java.util.UUID.randomUUID().toString()
    val recap by lazy {
        assertOwned()
        check(viewModel is HistoryViewModel) { "Recap requires the actual History entry" }
        DesktopPersonalRecapBinding(root.recapContext, environment, root.preferences, ::owns, ::commit)
    }
    fun owns(): Boolean = !closed.get() && job.isActive && root.owns()
    fun assertOwned() { if (!owns()) throw CancellationException("Personal list entry retired") }
    fun commit(block: () -> Unit): Boolean = root.gate.commit { if (owns()) block() } && owns()
    fun install(environment: DesktopFavoriteEnvironment, viewModel: BaseListViewModel, categories: FavoriteCategoryViewModel) {
        check(!this::viewModel.isInitialized); assertOwned()
        this.environment = environment; this.viewModel = viewModel; this.categories = categories
    }
    fun queueBridge(factory: () -> DesktopFavoriteQueueBridge): DesktopFavoriteQueueBridge {
        assertOwned()
        return queue ?: factory().also { queue = it }
    }
    override fun close() { if (closed.compareAndSet(false, true)) { queue?.close(); job.cancel() } }
}
