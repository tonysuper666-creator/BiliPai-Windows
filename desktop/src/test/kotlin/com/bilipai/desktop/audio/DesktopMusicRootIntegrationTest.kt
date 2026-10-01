package com.bilipai.desktop.audio

import com.android.purebilibili.core.network.AudioApi
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.audio.lyrics.*
import com.android.purebilibili.feature.audio.player.MusicPlaybackSource
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.subtitle.SubtitleCue
import com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta
import com.android.purebilibili.feature.video.ui.section.resolveDisplayBgmList
import com.android.purebilibili.feature.space.SpaceExternalPlaylist
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.ui.desktopSpaceListenPlaybackQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.nio.file.Files
import javax.swing.SwingUtilities

private fun onMusicSwing(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
private fun same(expected: Any?, actual: Any?) = check(expected == actual) { "Expected $expected, actual $actual" }

private class RootMusicDataSource(val prepareSong: suspend (PlaylistItem) -> PreparedListenAudio) : ListenPlaybackDataSource {
    override val lyrics = LyricsRepository(emptyList(), object : LyricsCache {
        override suspend fun read(key: String): LyricDocument? = null
        override suspend fun write(key: String, document: LyricDocument) = Unit
    })
    override suspend fun prepare(item: PlaylistItem) = prepareSong(item)
    override suspend fun subtitleTracks(item: PlaylistItem): List<SubtitleTrackMeta> = emptyList()
    override suspend fun subtitleCues(track: SubtitleTrackMeta): List<SubtitleCue> = emptyList()
}
private fun preparedMusic(item: PlaylistItem) = PreparedListenAudio(item, com.bilipai.desktop.player.PlaybackSource("file:///C:/synthetic-root-music.m4a", title = item.title))

private class RootMusicFixture(initialAccount: Boolean = false, onAcquire: () -> Unit = {},
    prepare: suspend (PlaylistItem) -> PreparedListenAudio = ::preparedMusic) : AutoCloseable {
    val directory = Files.createTempDirectory("bp-m-")
    val sessions = DesktopSessionStore.temporary()
    val repository = DesktopRepository(sessions)
    val player = MpvPlayer()
    val dataSource = RootMusicDataSource(prepare)
    lateinit var session: ListenAudioSession
    init {
        Files.writeString(directory.resolve("fixture-owner.json"), "{\"owner\":\"native-music-root-offline\"}")
        if (initialAccount) sessions.saveAccount(mapOf("SESSDATA" to "fixture-before", "bili_jct" to "fixture-only"), AccountSummary(9, "fixture", ""))
        onMusicSwing { session = ListenAudioSession(repository, DesktopCommunityRepository(repository), player,
            onAcquirePlayback = onAcquire, store = ListenAudioStore(directory.resolve("listen.json")), playbackDataSource = dataSource) }
    }
    suspend fun loaded() = withTimeout(3_000) { session.state.first { !it.loading && it.active } }
    @Suppress("UNCHECKED_CAST")
    fun native(change: (PlayerState) -> PlayerState) = onMusicSwing {
        val state = MpvPlayer::class.java.getDeclaredField("mutableState").apply { isAccessible = true }.get(player) as MutableStateFlow<PlayerState>
        state.value = change(state.value)
    }
    override fun close() { onMusicSwing { session.close() }; player.close() }
}

internal fun runMusicRootIntegrationCases(): List<String> = runBlocking {
    val passed = mutableListOf<String>()
    suspend fun case(name: String, action: suspend () -> Unit) { action(); passed += name }
    case("Stable original BGM music ID opens detail ahead of its jump URL") {
        same(DesktopBgmMusicTarget.Detail("au501", cid = 99),
            resolveDesktopBgmMusicTarget(BgmInfo("au501", "Song", "https://www.bilibili.com/audio/au501"), "BVx", 99))
    }
    case("AU detail ID is independent of video CID and Listen retains its separate source identity") {
        same(DesktopBgmMusicTarget.Detail("au501", cid = 9988), resolveDesktopBgmMusicTarget(BgmInfo("au501"), "BVx", 9988))
        same(MusicPlaybackSource.AudioSong(501), nativeMusicSourceForListenItem(PlaylistItem("AU501", 999, "Song", "", "")))
        same(null, nativeMusicSourceForListenItem(PlaylistItem("au0", 12, "Invalid", "", "")))
    }
    case("Stable detail route retains original server music IDs without inventing a playback source") {
        same(DesktopBgmMusicTarget.Detail("501", cid = 99), resolveDesktopBgmMusicTarget(BgmInfo("501"), "BVx", 99))
        same(DesktopBgmMusicTarget.Detail("AU501", cid = 99), resolveDesktopBgmMusicTarget(BgmInfo("AU501"), "BVx", 99))
        same(DesktopBgmMusicTarget.Detail("ma19", cid = 99), resolveDesktopBgmMusicTarget(BgmInfo("ma19"), "BVx", 99))
        same(null, resolveDesktopBgmMusicTarget(BgmInfo(""), "BVx", 99))
    }
    case("MA detail retains the selected part and unknown CID does not manufacture Listen playback") {
        same(DesktopBgmMusicTarget.Detail("MA19", cid = 9988), resolveDesktopBgmMusicTarget(BgmInfo("MA19"), "BVx", 9988))
        same(DesktopBgmMusicTarget.Detail("MA19", cid = 0), resolveDesktopBgmMusicTarget(BgmInfo("MA19"), "BVx", 0))
        same(null, nativeMusicSourceForListenItem(PlaylistItem("BVx", 0, "Unknown part", "", "")))
    }
    case("Original pure BGM display preserves list precedence") {
        val single = BgmInfo("au501")
        val many = listOf(BgmInfo("MA1"), BgmInfo("au502"))
        same(many, resolveDisplayBgmList(single, many)); same(listOf(single), resolveDisplayBgmList(single, emptyList()))
    }
    case("Video target rejects account epoch, CID, BV and native source replacement") {
        val target = DesktopMusicVideoTarget("BVx", 9988, 7, 2)
        check(target.isCurrent("BVx", 9988, 7, 2))
        check(!target.isCurrent("BVx", 9988, 7, 3)); check(!target.isCurrent("BVx", 9989, 7, 2))
        check(!target.isCurrent("BVy", 9988, 7, 2)); check(!target.isCurrent("BVx", 9988, 8, 2))
    }
    case("Actual original player metadata API preserves BGM and CID using synthetic interceptor") {
        val json = Json { ignoreUnknownKeys = true }
        val requests = mutableListOf<Request>()
        val data = PlayerInfoData(bvid = "BVx", cid = 9988, bgmInfo = BgmInfo("MA19", "Part BGM"))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            check(request.url.encodedPath == "/x/player/wbi/v2")
            same("BVx", request.url.queryParameter("bvid")); same("9988", request.url.queryParameter("cid"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("offline only")
                .body(json.encodeToString(PlayerInfoResponse(data = data)).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
        val reply = api.getPlayerInfo(mapOf("bvid" to "BVx", "cid" to "9988"))
        same(data, reply.data); same(1, requests.size)
        same(DesktopBgmMusicTarget.Detail("MA19", cid = 9988), resolveDesktopBgmMusicTarget(reply.data!!.bgmInfo!!, "BVx", 9988))
    }
    case("Actual original AU API SID501 synthetic response has no invented credential or endpoint") {
        val json = Json { ignoreUnknownKeys = true }
        var count = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); count++
            check(request.url.encodedPath == "/audio/music-service-c/web/song/info"); same("501", request.url.queryParameter("sid"))
            check(request.header("Cookie") == null)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("offline only")
                .body(json.encodeToString(SongInfoResponse(data = SongInfoData(id = 501, title = "Synthetic AU501"))).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AudioApi::class.java)
        same(501L, api.getSongInfo(501).data?.id); same(1, count)
    }
    case("Single acquisition preserves selected CID and starting position without duplicate preparation") {
        var count = 0
        RootMusicFixture { count++; preparedMusic(it) }.use { f ->
            onMusicSwing { f.session.openNativeMusic(MusicPlaybackSource.VideoAudio("BVx", 9988, "Exact part"), 17.75) }
            f.loaded(); same(1, count); same(9988L, f.session.state.value.current?.cid); same(17.75, f.player.state.value.positionSeconds)
        }
    }
    case("Opening paused current music does not restart or destroy retained queue") {
        RootMusicFixture().use { f ->
            val items = listOf(PlaylistItem("au501", title = "A", cover = "", owner = ""), PlaylistItem("au502", title = "B", cover = "", owner = ""))
            onMusicSwing { f.session.play(items); }; f.loaded()
            onMusicSwing { f.session.pause() }
            check(!shouldStartNativeMusic(MusicPlaybackSource.AudioSong(501), f.session.state.value, true))
            same(2, f.session.state.value.queue.size)
        }
    }
    case("Back-to-video handoff preserves owned progress and exact CID") {
        val source = MusicPlaybackSource.VideoAudio("BVx", 9988, "Part")
        val state = ListenAudioState(listOf(PlaylistItem("BVx", 9988, "Exact", "cover", "UP", duration = 90)), 0)
        val card = nativeMusicVideoReturnCard(source, state, PlayerState(positionSeconds = 17.9), true)
        same(9988L, card.preferredCid); same(17, card.progressSeconds); same("Exact", card.title)
        same(null, nativeMusicVideoReturnCard(source, state, PlayerState(positionSeconds = 80.0), false).progressSeconds)
        same(null, nativeMusicVideoReturnCard(source, state.copy(currentIndex = -1), PlayerState(positionSeconds = 80.0), true).progressSeconds)
    }
    case("Same MID credential epoch invalidates old active session synchronously") {
        RootMusicFixture(initialAccount = true).use { f ->
            onMusicSwing { f.session.openNativeMusic(MusicPlaybackSource.AudioSong(501)) }; f.loaded()
            val before = f.repository.sessionEpoch
            f.sessions.saveAccount(mapOf("SESSDATA" to "fixture-only", "bili_jct" to "fixture-only"), AccountSummary(9, "fixture", ""))
            check(f.repository.sessionEpoch > before); onMusicSwing {}
            same(null, f.session.ownedPlaybackSourceVersion); same(null, f.player.currentSourceSnapshot())
            val version = f.player.currentSourceVersion
            onMusicSwing { f.session.openNativeMusic(MusicPlaybackSource.AudioSong(502)); f.session.togglePause() }
            same(version, f.player.currentSourceVersion)
        }
    }
    case("Late uncancellable preparation cannot acquire native source after epoch change") {
        val ready = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val done = CompletableDeferred<Unit>()
        RootMusicFixture { item -> withContext(NonCancellable) { ready.complete(Unit); finish.await(); done.complete(Unit); preparedMusic(item) } }.use { f ->
            onMusicSwing { f.session.openNativeMusic(MusicPlaybackSource.AudioSong(501)) }
            withTimeout(3_000) { ready.await() }
            f.sessions.logout(); onMusicSwing {}
            finish.complete(Unit); withTimeout(3_000) { done.await() }; onMusicSwing {}
            same(null, f.player.currentSourceSnapshot()); same(null, f.session.ownedPlaybackSourceVersion)
        }
    }
    case("Epoch close and route disposal preserve a foreign source") {
        RootMusicFixture().use { f ->
            onMusicSwing { f.session.openNativeMusic(MusicPlaybackSource.AudioSong(501)) }; f.loaded()
            val foreign = f.player.loadVersioned(com.bilipai.desktop.player.PlaybackSource("file:///C:/synthetic-foreign.m4a"))
            f.sessions.logout(); onMusicSwing {}; onMusicSwing { f.session.close() }
            same(foreign, f.player.currentSourceVersion)
        }
    }
    case("Epoch invalidation hides ownership before the Swing collector can close") {
        RootMusicFixture().use { f ->
            onMusicSwing { f.session.openNativeMusic(MusicPlaybackSource.AudioSong(501)) }; f.loaded()
            onMusicSwing { f.sessions.logout(); same(null, f.session.ownedPlaybackSourceVersion) }
        }
    }
    case("Account change during Root acquire hook cannot stop a newly acquired foreign source") {
        var acquire: () -> Unit = {}
        RootMusicFixture(onAcquire = { acquire() }).use { f ->
            var foreign = 0L
            acquire = { f.sessions.logout(); foreign = f.player.loadVersioned(com.bilipai.desktop.player.PlaybackSource("file:///C:/synthetic-foreign.m4a")) }
            onMusicSwing { f.session.openNativeMusic(MusicPlaybackSource.AudioSong(501)) }
            same(foreign, f.player.currentSourceVersion)
            check(f.player.currentSourceSnapshot() != null)
        }
        for (changeAccount in listOf(false, true)) {
            acquire = {}
            RootMusicFixture(onAcquire = { acquire() }).use { f ->
                onMusicSwing { f.session.openNativeMusic(MusicPlaybackSource.AudioSong(501)) }; f.loaded()
                f.native { it.copy(durationSeconds = 95.0, loading = false, paused = true) }
                onMusicSwing { f.session.pause() }
                var foreign = 0L
                acquire = {
                    if (changeAccount) f.sessions.logout()
                    foreign = f.player.loadVersioned(com.bilipai.desktop.player.PlaybackSource("file:///C:/synthetic-resume-foreign.m4a", startPaused = true))
                }
                onMusicSwing { f.session.togglePause() }
                same(foreign, f.player.currentSourceVersion)
                same(true, f.player.state.value.paused)
            }
        }
    }
    case("Original Space play-all preserves queue order ownerFace selected CID and own-account resume") {
        val items = listOf(PlaylistItem("BVfirst", 1100, "First", "c1", "UP", ownerFace = "face1"),
            PlaylistItem("BVsecond", 0, "Second", "c2", "UP", ownerFace = "face2"))
        val history = listOf(VideoCard("BVsecond", "Old", "", "", 0, 90, preferredCid = 2200, progressSeconds = 32, authorMid = 7))
        val queue = desktopSpaceListenPlaybackQueue(SpaceExternalPlaylist(items, 1), 7, history)!!
        same(listOf("BVfirst", "BVsecond"), queue.first.map { it.bvid }); same(1, queue.second); same(32.0, queue.third)
        same(1100L, queue.first[0].cid); same(2200L, queue.first[1].cid); same("face2", queue.first[1].ownerFace)
        val foreign = desktopSpaceListenPlaybackQueue(SpaceExternalPlaylist(items, 1), 8, history)!!
        same(0L, foreign.first[1].cid); same(0.0, foreign.third)
    }
    case("Original Space invalid queue and different-part progress cannot drive audio handoff") {
        val items = listOf(PlaylistItem("BVx", 2200, "Part", "", ""))
        val history = listOf(VideoCard("BVx", "Old", "", "", 0, 90, preferredCid = 1100, progressSeconds = 50, authorMid = 7))
        same(null, desktopSpaceListenPlaybackQueue(SpaceExternalPlaylist(items, 5), 7, history))
        val queue = desktopSpaceListenPlaybackQueue(SpaceExternalPlaylist(items, 0), 7, history)!!
        same(2200L, queue.first.single().cid); same(0.0, queue.third)
    }
    passed
}

class DesktopMusicRootIntegrationTest {
    @org.junit.jupiter.api.Test fun originalRoutesAndRetainedSessionLifecycle(): Unit = runBlocking {
        org.junit.jupiter.api.Assertions.assertEquals(18, runMusicRootIntegrationCases().size)
    }
}
