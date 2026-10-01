package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.audio.lyrics.*
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.subtitle.*
import com.bilipai.desktop.*
import com.bilipai.desktop.audio.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.swing.Swing
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

private var assertions = 0
private fun verify(value: Boolean, message: String) { check(value) { message }; assertions++ }
private fun swing(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) { while (!withContext(Dispatchers.Swing) { condition() }) delay(5) }
private fun item(id: String, cid: Long = 7) = PlaylistItem(id, cid, id, "", "UP", duration = 100)
private inline fun <reified T: Any> api(crossinline call: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, args -> call(m.name, args ?: emptyArray()) } as T

private class Fixture(root: Path) : AutoCloseable {
    val player = MpvPlayer()
    val audioPlayer = MpvPlayer()
    val repository = DesktopRepository(DesktopSessionStore.temporary())
    val globalStore = DesktopPluginStore(root.resolve("global"))
    val community = DesktopCommunityRepository(repository, DesktopBlockedUpStore(com.bilipai.desktop.plugins.DesktopPluginContext(globalStore)))
    val epoch = AtomicLong()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    var preparingGate: CompletableDeferred<Unit>? = null
    val preparingEntered = CompletableDeferred<Unit>()
    var calls = 0
    var audioCalls = 0
    val source = object : DesktopPlaybackDataSource {
        override val sessionEpoch get() = epoch.get()
        override suspend fun videoDetails(bvid: String) = VideoDetails(bvid, 51, bvid, "", "UP", "", 0, 0,
            listOf(VideoPart(7, "P1", 100), VideoPart(9, "P2", 100)))
        override suspend fun related(bvid: String) = emptyList<VideoCard>()
        override suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String?, forceRefresh: Boolean): ResolvedSource {
            calls++
            return ResolvedSource("file:///C:/synthetic-unopened.avi", null, details.title, "", quality = quality, videoCodecFamily = "avc1")
        }
    }
    val controller = DesktopPlaybackController(repository, player, null, null, DesktopLibrary(root.resolve("library")) { false },
        { PlayerPreferences() }, scope, dataSource = source)
    val data = object : ListenPlaybackDataSource {
        override val lyrics = LyricsRepository(emptyList(), object : LyricsCache {
            override suspend fun read(key: String): LyricDocument? = null
            override suspend fun write(key: String, document: LyricDocument) = Unit
        })
        override suspend fun prepare(item: PlaylistItem): PreparedListenAudio {
            audioCalls++; preparingEntered.complete(Unit); preparingGate?.await()
            return PreparedListenAudio(item.copy(title = "resolved ${item.title}"), PlaybackSource("file:///C:/synthetic-unopened.avi", title = item.title))
        }
        override suspend fun subtitleTracks(item: PlaylistItem) = emptyList<SubtitleTrackMeta>()
        override suspend fun subtitleCues(track: SubtitleTrackMeta) = emptyList<SubtitleCue>()
    }
    val listen = ListenAudioSession(repository, community, audioPlayer,
        store = ListenAudioStore(root.resolve("listen.json")), playbackDataSource = data)
    override fun close() { swing { listen.close(); controller.close() }; scope.cancel(); audioPlayer.close(); player.close() }
}

private suspend fun realVideoQueue(root: Path) {
    Fixture(root).use { f ->
        var videoReveals = 0; var selectedAudio = false
        val bridge = DesktopFavoriteQueueBridge(f.controller, f.listen, { true }, { it == selectedAudio },
            { selectedAudio = it; true }, { videoReveals++ }, {})
        lateinit var token: DesktopFavoriteQueueToken
        swing {
            token = bridge.openQueue(listOf(item("BV-one", 7), item("BV-two", 9)), 0, false)!!
            verify(bridge.revealIfOwned("BV-one", 7, false), "Original callback reveals already admitted video queue")
            verify(!bridge.revealIfOwned("BV-one", 7, false), "Reveal admission is consumed exactly once")
        }
        await { !f.controller.state.value.opening }
        val version = f.player.currentSourceVersion
        swing {
            f.player.recoverSource(version, positionSeconds = 27.5, paused = true)
            bridge.appendQueue(listOf(item("BV-one", 999), item("BV-three", 9)), token)
            verify(f.controller.ownsQueue(token.owner), "Actual controller lease is retained")
            verify(f.player.currentSourceVersion == version && f.player.state.value.positionSeconds == 27.5 && f.player.state.value.paused, "Append leaves source/position/pause unchanged")
            verify(f.controller.state.value.queue.first().preferredCid == 7L, "Original current CID cannot be replaced by raw continuation")
            verify(f.controller.state.value.queue.map { it.bvid } == listOf("BV-one", "BV-two", "BV-three"), "Original unseen-BVID append order")
            verify(f.calls == 1 && videoReveals == 1, "Reveal does not issue a second ordinary open")
            f.controller.next()
        }
        await { !f.controller.state.value.opening && f.controller.state.value.queueIndex == 1 }
        swing {
            bridge.appendQueue(listOf(item("BV-four", 7)), token)
            verify(f.controller.ownsQueue(token.owner) && f.controller.state.value.queueIndex == 1, "Continuation survives actual next")
            f.controller.open(favoriteQueueVideoCard(item("BV-foreign")))
        }
        await { !f.controller.state.value.opening }
        swing {
            bridge.appendQueue(listOf(item("BV-late")), token)
            verify(f.controller.state.value.queue.single().bvid == "BV-foreign", "Ordinary open retires old continuation")
        }
    }
}

private suspend fun realAudioQueue(root: Path) {
    Fixture(root).use { f ->
        val owner = Any()
        swing { verify(f.listen.playQueueForOwner(owner, listOf(item("BV-one", 9), item("BV-two")), 0), "Listen returns only actual owned admission") }
        await { !f.listen.state.value.loading && f.listen.state.value.active }
        val version = f.audioPlayer.currentSourceVersion
        swing {
            f.audioPlayer.recoverSource(version, positionSeconds = 19.0, paused = true)
            f.listen.pause()
            verify(f.listen.ownsQueue(owner), "Loaded pause retains actual Listen queue lease")
            verify(f.listen.appendQueueForOwner(owner, listOf(item("BV-one", 99), item("BV-three"))), "Loaded audio continuation accepted")
            verify(f.listen.state.value.current?.cid == 9L && f.listen.state.value.current?.title == "resolved BV-one", "Append retains resolved audio CID/metadata")
            verify(f.audioPlayer.currentSourceVersion == version && f.audioPlayer.state.value.positionSeconds == 19.0 && f.audioPlayer.state.value.paused, "Audio append preserves actual actor state")
            f.listen.next()
        }
        await { !f.listen.state.value.loading && f.listen.state.value.currentIndex == 1 }
        swing {
            verify(f.listen.ownsQueue(owner), "Audio internal next retains lease")
            f.listen.play(listOf(item("BV-new")))
        }
        await { !f.listen.state.value.loading }
        swing {
            verify(!f.listen.appendQueueForOwner(owner, listOf(item("BV-stale"))), "Normal audio play retires lease")
            verify(!f.listen.stopQueueForOwner(owner), "Old audio owner cannot stop replacement")
        }
    }
}

private suspend fun pendingNativeReplacement(root: Path) {
    Fixture(root).use { f ->
        val owner = Any(); f.preparingGate = CompletableDeferred()
        swing { verify(f.listen.playQueueForOwner(owner, listOf(item("BV-one")), 0), "Pending Listen source has actual baseline lease") }
        withTimeout(5_000) { f.preparingEntered.await() }
        swing {
            verify(f.listen.appendQueueForOwner(owner, listOf(item("BV-two"))), "Pending same actor can append before transport returns")
            f.audioPlayer.loadVersioned(PlaybackSource("file:///C:/foreign.avi", title = "foreign"))
            verify(!f.listen.ownsQueue(owner), "Pending foreign native load retires admission")
            verify(!f.listen.appendQueueForOwner(owner, listOf(item("BV-stale"))), "Pending foreign actor rejects append")
            verify(!f.listen.stopQueueForOwner(owner), "Pending foreign actor rejects stop")
        }
        val foreign = f.audioPlayer.currentSourceVersion
        f.preparingGate!!.complete(Unit)
        await { !f.listen.state.value.loading }
        verify(f.audioPlayer.currentSourceVersion == foreign && f.audioPlayer.state.value.sourceTitle == "foreign", "Delayed old audio result cannot replace foreign actor")
    }
}

private suspend fun retainedOriginalEntry(root: Path) {
    Fixture(root).use { f ->
        val currentEpoch = AtomicLong(17)
        val secondPage = CompletableDeferred<Continuation<Any?>>()
        val continueAll = CompletableDeferred<Unit>()
        var fetchAll = false
        val first = FavoriteData(id = 51, bvid = "BV-one", title = "one", ugc = FavoriteUgc(first_cid = 7))
        val last = FavoriteData(id = 53, bvid = "BV-three", title = "three", ugc = FavoriteUgc(first_cid = 9))
        val actualApi = api<BilibiliApi> { name, args -> when (name) {
            "getNavInfo" -> NavResponse(data = NavData(isLogin = true, mid = 77))
            "getFavFolders" -> FavFolderResponse(data = FavFolderList(count = 1, list = listOf(FavFolder(id = 55, title = "folder", cover = "", media_count = 2))))
            "getCollectedFavFolders" -> FavFolderResponse(data = FavFolderList(count = 0, list = emptyList()))
            "getFavoriteList" -> if (fetchAll && args[1] == 2) {
                @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Any?>
                secondPage.complete(continuation); COROUTINE_SUSPENDED
            } else FavoriteResourceResponse(data = FavoriteResourceData(medias = listOf(first), has_more = fetchAll))
            else -> error("Unexpected Favorites API $name")
        } }
        lateinit var entry: DesktopFavoritesRootEntry
        swing { entry = DesktopFavoritesRootEntry(17, currentEpoch::get, f.scope, actualApi,
            api<SpaceApi> { name, _ -> error(name) }, api<DynamicApi> { name, _ -> error(name) }, api<BangumiApi> { name, _ -> error(name) },
            f.globalStore, { 77 }, { "task-only-csrf" }, {}, { true })
        }
        withTimeout(5_000) { entry.viewModel.getFolderUiState(0).first { !it.isLoading && it.items.isNotEmpty() } }
        var selectedAudio = false
        val bridge = DesktopFavoriteQueueBridge(f.controller, f.listen, entry::isOwned, { it == selectedAudio }, { selectedAudio = it; true }, {}, {})
        lateinit var token: DesktopFavoriteQueueToken
        swing {
            token = bridge.openQueue(listOf(item("BV-one")), 0, false)!!
            bridge.revealIfOwned("BV-one", 7, false)
            fetchAll = true
            entry.viewModel.loadAllForPlayback(0) { rows ->
                bridge.appendQueue(rows.map { item(it.bvid, it.cid) }, token)
                continueAll.complete(Unit)
            }
        }
        val continuation = withTimeout(5_000) { secondPage.await() }
        // The CommonList UI is covered/unmounted here; no entry.close and no new VM.
        verify(entry.isOwned() && entry.scope.isActive, "Video cover keeps the actual original entry scope alive")
        continuation.resume(FavoriteResourceResponse(data = FavoriteResourceData(medias = listOf(last), has_more = false)))
        withTimeout(5_000) { continueAll.await() }
        await { !f.controller.state.value.opening }
        verify(f.controller.state.value.queue.map { it.bvid } == listOf("BV-one", "BV-three"), "Original same-scope loadAll really continues and appends under video cover")
        verify(entry.viewModel.getFolderUiState(0).value.items.size == 2, "Same original retained VM receives continuation")
        currentEpoch.incrementAndGet()
        verify(!entry.isOwned(), "Same MID new epoch invalidates retained entry")
        swing { bridge.appendQueue(listOf(item("BV-retired")), token); entry.close() }
        verify(f.controller.state.value.queue.size == 2 && !entry.scope.isActive && entry.searchChannel.isClosedForSend,
            "Retired entry cancels jobs/channels without stopping its valid already-playing source")
    }
}

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args.single()); Files.createDirectories(root)
    realVideoQueue(root.resolve("video")); realAudioQueue(root.resolve("audio")); pendingNativeReplacement(root.resolve("pending")); retainedOriginalEntry(root.resolve("entry"))
    println("Prepared Root Favorites wiring 4 groups / $assertions assertions PASS; actual HWND=false; socket=false; mountedRoot=false")
    listOf(DesktopFavoriteQueueBridge::class.java, DesktopFavoritesRootEntry::class.java, ListenAudioSession::class.java,
        DesktopPlaybackController::class.java, DesktopPluginStore::class.java, com.android.purebilibili.feature.list.FavoriteViewModel::class.java)
        .forEach { println("CodeSource ${it.name}: ${it.protectionDomain.codeSource.location}") }
}
