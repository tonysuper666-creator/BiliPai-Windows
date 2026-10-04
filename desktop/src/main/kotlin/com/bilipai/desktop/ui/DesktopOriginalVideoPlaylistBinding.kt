package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.player.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonPrimitive

/** The sole original PlaylistManager instance, over the SAME global preference Store.
 * Its Android SharedPreferences.apply becomes a serial, owned Root-scope write.
 * Enqueue admission is the linearization point; accepted persistence is in-flight.
 * No filesystem operation, worker join or native command runs inside Root admission.
 */
internal class DesktopOriginalVideoPlaylistBinding(
    private val store: DesktopPluginStore,
    private val rootScope: CoroutineScope,
    private val rootOwned: () -> Boolean,
    private val rootAdmission: ((() -> Unit) -> Boolean),
    private val onPersistenceFailure: (Throwable) -> Unit,
) : DesktopOriginalVideoOwnerPlaylist, AutoCloseable {
    private val persistenceLock = Any()
    private var tail: Job? = null
    private val writes = linkedSetOf<Job>()
    @Volatile private var closed = false
    private val rootJob = checkNotNull(rootScope.coroutineContext[Job])
    private fun owns() = !closed && rootJob.isActive && rootOwned()
    private fun assertOwned() { if (!owns()) throw CancellationException("Original playlist owner retired") }
    private val original = DesktopOriginalPlaylistManager(
        readSnapshot = { assertOwned(); (store.preferences("playlist_manager_state")["snapshot_json"] as? JsonPrimitive)?.takeIf { it.isString }?.content },
        enqueueSnapshot = ::enqueueSnapshot,
        reportPersistenceFailure = { error -> rootScope.launch { if (owns()) onPersistenceFailure(error) } },
    )
    init { mutate { original.init() } }
    override val playlist: StateFlow<List<PlaylistItem>> get() = original.playlist
    override val currentIndex: StateFlow<Int> get() = original.currentIndex
    override val playMode: StateFlow<PlayMode> get() = original.playMode
    override val isExternalPlaylist: StateFlow<Boolean> get() = original.isExternalPlaylist
    override val externalPlaylistSource: StateFlow<ExternalPlaylistSource> get() = original.externalPlaylistSource
    val shuffleEnabled: StateFlow<Boolean> get() = original.shuffleEnabled
    val uiState get() = original.uiState

    private fun <T> mutate(action: () -> T): T {
        assertOwned()
        var result: Any? = null
        var accepted = false
        if (!rootAdmission { assertOwned(); result = action(); accepted = true } || !accepted)
            throw CancellationException("Original playlist admission retired")
        @Suppress("UNCHECKED_CAST") return result as T
    }
    override fun playAt(index: Int): PlaylistItem? = mutate { original.playAt(index) }
    override fun playNext(): PlaylistItem? = mutate { original.playNext() }
    override fun playPrevious(): PlaylistItem? = mutate { original.playPrevious() }
    override fun setPlaylist(items: List<PlaylistItem>, startIndex: Int) = mutate { original.setPlaylist(items, startIndex) }
    fun setExternalPlaylist(items: List<PlaylistItem>, startIndex: Int, source: ExternalPlaylistSource): PlaylistSession =
        mutate { original.setExternalPlaylist(items, startIndex, source) }
    fun addToPlaylist(item: PlaylistItem) = mutate { original.addToPlaylist(item) }
    fun addAllToPlaylist(items: List<PlaylistItem>) = mutate { original.addAllToPlaylist(items) }
    fun addAllToPlaylistIfCurrent(items: List<PlaylistItem>, session: PlaylistSession): Boolean =
        mutate { original.addAllToPlaylistIfCurrent(items, session) }
    fun removeFromPlaylist(bvid: String) = mutate { original.removeFromPlaylist(bvid) }
    fun clearPlaylist() = mutate { original.clearPlaylist() }
    fun setPlayMode(mode: PlayMode) = mutate { original.setPlayMode(mode) }
    fun setShuffleEnabled(enabled: Boolean) = mutate { original.setShuffleEnabled(enabled) }
    fun togglePlayMode(): PlayMode = mutate { original.togglePlayMode() }
    fun hasNext(): Boolean { assertOwned(); return original.hasNext() }
    fun hasPrevious(): Boolean { assertOwned(); return original.hasPrevious() }
    fun getCurrentItem(): PlaylistItem? { assertOwned(); return original.getCurrentItem() }
    fun getPlayModeText(): String { assertOwned(); return original.getPlayModeText() }
    fun getPlayModeIcon(): String { assertOwned(); return original.getPlayModeIcon() }
    fun isSessionCurrent(session: PlaylistSession): Boolean { assertOwned(); return original.isSessionCurrent(session) }
    fun captureSession(): PlaylistSession { assertOwned(); return original.captureDesktopSession() }
    /** Keep the source list/session check and original selection in one Root admission. */
    fun playAtIfCurrent(session:PlaylistSession,items:List<PlaylistItem>,index:Int,item:PlaylistItem):PlaylistItem? = mutate {
        if(!original.isSessionCurrent(session) || original.playlist.value!==items ||
            items.getOrNull(index)!==item)null else original.playAt(index)
    }
    fun replaceQueueIfCurrent(items: List<PlaylistItem>, index: Int, session: PlaylistSession): Boolean =
        mutate { original.replaceQueueIfCurrent(items,index,session) }
    fun resolveSelectedCidIfCurrent(session: PlaylistSession, bvid: String, expectedCid: Long, resolvedCid: Long): Boolean =
        mutate { original.resolveSelectedCidIfCurrent(session,bvid,expectedCid,resolvedCid) }
    fun adoptQueue(items: List<PlaylistItem>, index: Int, source: ExternalPlaylistSource,
        external: Boolean, progress: ShuffleProgress): PlaylistSession = mutate { original.adoptQueue(items,index,source,external,progress) }

    private fun enqueueSnapshot(raw: String) {
        assertOwned()
        val created = synchronized(persistenceLock) {
            val previous = tail
            rootScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                // Only this persistence chain is serialized. This does not create a
                // player actor or retain a second list/cache/prefs document.
                previous?.join()
                currentCoroutineContext().ensureActive()
                var permit = false
                if (!rootAdmission { assertOwned(); currentCoroutineContextJobActive(this); permit = true } || !permit)
                    throw CancellationException("Original playlist write retired")
                // Already admitted: store writesFrozen and its own backing protect
                // the in-flight atomic replacement, outside Store/entry admission.
                store.update("playlist_manager_state", mapOf("snapshot_json" to raw.takeIf { it.isNotBlank() }?.let(::JsonPrimitive)))
            }.also { tail = it; writes += it }
        }
        created.invokeOnCompletion { error ->
            synchronized(persistenceLock) { writes.remove(created) }
            if (error != null && error !is CancellationException && owns()) onPersistenceFailure(error)
        }
        created.start() // queues IO; never waits in admission
    }
    private fun currentCoroutineContextJobActive(scope: CoroutineScope) { checkNotNull(scope.coroutineContext[Job]).ensureActive() }
    override fun close() {
        val pending = synchronized(persistenceLock) { closed = true; writes.toList() }
        pending.forEach(Job::cancel) // completion callbacks never execute under this monitor
    }
    suspend fun closeAndJoin() {
        val pending = synchronized(persistenceLock) { closed = true; writes.toList() }
        pending.forEach(Job::cancel)
        withContext(NonCancellable) { pending.joinAll() }
    }
}
