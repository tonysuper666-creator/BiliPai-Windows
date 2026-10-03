package com.bilipai.desktop.audio

import com.android.purebilibili.core.network.AudioApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.audio.lyrics.*
import com.android.purebilibili.feature.audio.player.*
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.subtitle.SubtitleCue
import com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource
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
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

private fun equal(expected: Any?, actual: Any?) { check(expected == actual) { "Expected $expected, actual $actual" } }
private fun onSwing(block: () -> Unit) { SwingUtilities.invokeAndWait(block) }
private fun fixtureSong(sid: Long = 81, author: String = "歌手") = SongInfoData(id = sid, uid = 901, uname = "上传者",
    author = author, title = "测试歌曲 $sid", cover = "//fixture.invalid/cover.png", intro = "原版音乐介绍", lyric = "https://fixture.invalid/lrc",
    duration = 95, passtime = 1_700_000_000, coin_num = 6, statistic = SongStatistic(sid, 1234, 50, 17, 4))
private const val LRC = "[ar:歌手]\n[00:00.00]第一行\n[00:03.50]第二行\n"

/** Actual generated Retrofit AudioApi + original serializers. Interceptor always returns locally; no HTTP leaves process. */
private class SongApiFixture {
    val sessions = DesktopSessionStore.temporary()
    val repository = DesktopRepository(sessions)
    val audio = DesktopAudioRepository(repository, DesktopCommunityRepository(repository))
    var song = fixtureSong()
    var songCode = 0
    var lyricCode = 0
    var streams = listOf("https://fixture.invalid/audio.m4a", "https://fixture.invalid/alternate.m4a")
    var afterReply: (String) -> Unit = {}
    val requests = CopyOnWriteArrayList<Request>()
    private val json = Json { ignoreUnknownKeys = true }
    val client = OkHttpClient.Builder().cookieJar(sessions).addInterceptor { chain ->
        val request = chain.request()
        requests.add(request)
        val path = request.url.encodedPath
        val body = when(path) {
            "/audio/music-service-c/web/song/info" -> json.encodeToString(SongInfoResponse(songCode, "fixture song", if(songCode == 0) song else null))
            "/audio/music-service-c/web/url" -> json.encodeToString(SongStreamResponse(data = SongStreamData(song.id, 2, 4567, streams, song.title, song.cover)))
            "/audio/music-service-c/web/song/lyric" -> json.encodeToString(SongLyricResponse(lyricCode, "fixture lyrics", if(lyricCode == 0) LRC else null))
            else -> error("Unexpected API: $path")
        }
        afterReply(path)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("offline fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    init {
        val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AudioApi::class.java)
        DesktopAudioRepository::class.java.getDeclaredField("audioApi").apply { isAccessible = true }.set(audio, api)
        skipVisitor()
    }
    fun skipVisitor() {
        fun set(name: String, value: Any) { DesktopRepository::class.java.getDeclaredField(name).apply { isAccessible = true }.set(repository, value) }
        set("visitorInitialized", true); set("visitorGeneration", repository.sessionEpoch)
    }
    fun switchAccount() { sessions.logout() }
    suspend fun prepare(sid: Long = song.id) = audio.prepare(musicSourcePlaylistItem(MusicPlaybackSource.AudioSong(sid)))
}

private class MemoryLyrics : LyricsCache {
    val documents = mutableMapOf<String, LyricDocument>()
    override suspend fun read(key: String) = documents[key]
    override suspend fun write(key: String, document: LyricDocument) { documents[key] = document }
}
private class MusicDataSource(private val action: suspend (PlaylistItem) -> PreparedListenAudio) : ListenPlaybackDataSource {
    val cache = MemoryLyrics()
    override val lyrics = LyricsRepository(emptyList(), cache)
    override suspend fun prepare(item: PlaylistItem) = action(item)
    override suspend fun subtitleTracks(item: PlaylistItem): List<SubtitleTrackMeta> = emptyList()
    override suspend fun subtitleCues(track: SubtitleTrackMeta): List<SubtitleCue> = emptyList()
}

private class MusicSessionFixture(
    val source: MusicDataSource,
    saved: ListenAudioSaved = ListenAudioSaved(),
    preferences: PlayerPreferences = PlayerPreferences(),
    private val primaryRepository: DesktopRepository? = null,
) : AutoCloseable {
    val directory = Files.createTempDirectory("bilipai-native-music-offline-")
    val player = MpvPlayer()
    val store = ListenAudioStore(directory.resolve("listen.json"))
    val repository = primaryRepository ?: DesktopRepository(DesktopSessionStore.temporary())
    private val publicationGate = Any()
    private var publicationAlive = true
    lateinit var session: ListenAudioSession
    init {
        store.save(saved)
        onSwing { session = ListenAudioSession(repository, DesktopCommunityRepository(repository), player,
            preferences, store = store, playbackDataSource = source, publication = primaryRepository?.let {
                com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication(it, allowPrimaryAccountSource = true)
            } ?: com.bilipai.desktop.player.DesktopLocalPlaybackPublication(
                    { synchronized(publicationGate) { publicationAlive } },
                    { admitted -> synchronized(publicationGate) { if (!publicationAlive) false else { admitted(); true } } })) }
    }
    suspend fun await(predicate: (ListenAudioState) -> Boolean) = withTimeout(3_000) { session.state.first(predicate) }
    fun start(sid: Long = 81) = onSwing { session.openNativeMusic(MusicPlaybackSource.AudioSong(sid)) }
    @Suppress("UNCHECKED_CAST")
    fun native(change: (PlayerState) -> PlayerState) = onSwing {
        val state = MpvPlayer::class.java.getDeclaredField("mutableState").apply { isAccessible = true }.get(player) as MutableStateFlow<PlayerState>
        state.value = change(state.value)
    }
    override fun close() { synchronized(publicationGate) { publicationAlive = false };
        onSwing { session.close() }; player.close()
        val resolved = directory.toAbsolutePath().normalize()
        check(resolved.startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()) &&
            resolved.fileName.toString().startsWith("bilipai-native-music-offline-"))
        Files.walk(resolved).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
            check(path.toAbsolutePath().normalize().startsWith(resolved)); Files.deleteIfExists(path)
        } }
    }
}
private fun prepared(item: PlaylistItem) = PreparedListenAudio(item.copy(title = "测试歌曲", owner = "歌手", duration = 95),
    PlaybackSource("file:///C:/native-music-offline.m4a", title = "测试歌曲"), songLyrics = LRC,
    songInfo = item.bvid.removePrefix("au").toLongOrNull()?.let { fixtureSong(it) })

internal fun runNativeMusicOfflineCases(): Int = runBlocking {
    var count = 0
    suspend fun case(name: String, action: suspend () -> Unit) { action(); println("PASS $name"); count++ }
    case("AU SID and NativeMusic BV/CID retain original distinct identities and owners") {
        val au = MusicPlaybackSource.AudioSong(81)
        val video = MusicPlaybackSource.VideoAudio("BVfixture", 220, "视频标题")
        equal("au:81", au.stableId); equal("video:BVfixture:220", video.stableId)
        equal(MusicPlaybackOwner.MINI_PLAYER_MANAGER, resolveMusicPlaybackOwner(au))
        equal(MusicPlaybackOwner.PLAYER_VIEW_MODEL, resolveMusicPlaybackOwner(video))
        equal("au81", musicSourcePlaylistItem(au).bvid)
        equal(220L, musicSourcePlaylistItem(video).cid)
        check(!shouldReleaseMusicPlayerOnScreenExit(true))
        check(!video.matches(PlaylistItem("BVfixture", 221, "", "", "")))
    }
    case("real original AudioApi queries return full song metadata stream and lyric") {
        val f = SongApiFixture(); val result = f.prepare()
        equal(f.song, result.songInfo); equal(f.song.title, result.item.title); equal(f.song.author, result.item.owner)
        equal(f.song.duration.toLong(), result.item.duration); equal(f.streams.first(), result.source.videoUrl); equal(LRC, result.songLyrics)
        equal(3, f.requests.size); check(f.requests.all { it.url.queryParameter("sid") == "81" })
        val stream = f.requests[1]; equal("2", stream.url.queryParameter("quality")); equal("2", stream.url.queryParameter("privilege"))
        equal(1234L, result.songInfo?.statistic?.play); equal(901L, result.songInfo?.uid)
    }
    case("artist fallback uses original uploader without discarding raw author field") {
        val f = SongApiFixture(); f.song = fixtureSong(author = "")
        val result = f.prepare(); equal("上传者", result.item.owner); equal("", result.songInfo?.author)
    }
    case("unplayable stream reply fails before requesting lyrics") {
        val f = SongApiFixture(); f.streams = listOf("", "rtmp://fixture.invalid/audio")
        check(runCatching { f.prepare() }.exceptionOrNull() is java.io.IOException); equal(2, f.requests.size)
    }
    case("song API business failure is preserved and no stream request follows") {
        val f = SongApiFixture(); f.songCode = -404
        val error = runCatching { f.prepare() }.exceptionOrNull()
        check(error is BiliApiException && error.apiCode == -404); equal(1, f.requests.size)
    }
    case("lyric API failure does not discard a playable song or metadata") {
        val f = SongApiFixture(); f.lyricCode = -404
        val result = f.prepare(); equal(null, result.songLyrics); equal(f.song, result.songInfo)
        equal(f.streams.first(), result.source.videoUrl)
    }
    for((step, expected) in listOf("info" to 1, "url" to 2, "lyric" to 3)) {
        case("account replacement after $step cannot continue under another account") {
            val f = SongApiFixture(); f.afterReply = { path -> if(path.endsWith("/$step")) f.switchAccount() }
            check(runCatching { f.prepare() }.exceptionOrNull() is IllegalStateException); equal(expected, f.requests.size)
        }
    }
    case("existing scoped CookieJar never adds Bilibili account Cookie to external audio") {
        val f = SongApiFixture()
        f.sessions.saveAccount(mapOf("SESSDATA" to "fake-fixture-only", "bili_jct" to "fake-fixture-csrf"), AccountSummary(1, "fake", ""))
        f.skipVisitor()
        equal("", f.prepare().source.cookieHeader)
        check(f.requests.all { it.url.host == "api.bilibili.com" })
    }
    case("retained session loads AU metadata and original timed lyrics with au cache key") {
        val api = SongApiFixture(); val source = MusicDataSource { api.audio.prepare(it) }
        MusicSessionFixture(source, preferences = PlayerPreferences(audioOnly = false), primaryRepository = api.repository).use { f ->
            f.start(); f.await { !it.loading && it.active && it.lyrics != null && !it.lyricsLoading }
            equal(api.song, f.session.state.value.songInfo)
            // Preserve actual API account authority through the same repository publication gate.
            equal(api.repository.sessionEpoch, f.player.currentSourceSnapshot()?.source?.primaryAccountEpoch)
            equal("第一行", f.session.state.value.lyrics?.lines?.first()?.text)
            equal(3500L, f.session.state.value.lyrics?.lines?.get(1)?.startTimeMs)
            check(source.cache.documents.containsKey("au:81")); check(f.player.state.value.audioOnly)
            equal(f.player.currentSourceVersion, f.session.ownedPlaybackSourceVersion)
            val owned = f.player.currentSourceVersion
            val original = projectNativeMusicState(MusicPlaybackSource.AudioSong(81), f.session.state.value, f.player.state.value, true)
            equal(owned, f.player.currentSourceVersion); equal(api.song, original.songInfo)
            // Route projection and returning to another screen cannot dispose the retained player.
            projectNativeMusicPlayerState(original, PlaybackMode.SEQUENTIAL, 1.25)
            equal(owned, f.player.currentSourceVersion)
        }
    }
    case("native projection preserves pause progress metadata and original single-song queue controls") {
        MusicSessionFixture(MusicDataSource(::prepared)).use { f ->
            f.start(); f.await { !it.loading && it.active }
            f.native { it.copy(loading = false, ready = true, paused = false, durationSeconds = 95.0, positionSeconds = 9.75) }
            val state = projectNativeMusicState(MusicPlaybackSource.AudioSong(81), f.session.state.value, f.player.state.value, true)
            val player = projectNativeMusicPlayerState(state, PlaybackMode.REPEAT_ONE, 1.5)
            equal(9750L, player.positionMs); equal(95000L, player.durationMs); check(player.isPlaying)
            equal(1.5f, player.playbackSpeed); check(!player.queueControls.hasPrevious && !player.queueControls.hasNext)
            equal("au:81", player.queue.single().stableId)
        }
    }
    case("stale route and foreign native owner cannot publish foreign progress or lyrics") {
        MusicSessionFixture(MusicDataSource(::prepared)).use { f ->
            f.start(); f.await { !it.loading && it.active && it.lyrics != null }
            val foreign = f.player.loadVersioned(PlaybackSource("file:///C:/foreign.m4a", title = "Foreign", startPositionSeconds = 41.0))
            equal(null, f.session.ownedPlaybackSourceVersion)
            val state = projectNativeMusicState(MusicPlaybackSource.AudioSong(82), f.session.state.value, f.player.state.value, false)
            equal(null, state.songInfo); equal(null, state.lyricsDocument); equal(0L, state.currentPositionMs); check(!state.isPlaying)
            val previousRoute = projectNativeMusicState(MusicPlaybackSource.AudioSong(81), f.session.state.value, f.player.state.value, false)
            equal(null, previousRoute.lyricsDocument); equal(0L, previousRoute.currentPositionMs); check(previousRoute.error != null)
            onSwing { f.session.close() }; equal(foreign, f.player.currentSourceVersion)
        }
    }
    case("pending AU preparation cannot overwrite a newly installed foreign source") {
        val started = CompletableDeferred<Unit>(); val result = CompletableDeferred<PreparedListenAudio>()
        MusicSessionFixture(MusicDataSource { item -> started.complete(Unit); result.await().copy(item = item) }).use { f ->
            f.start(); withTimeout(3_000) { started.await() }
            val foreign = f.player.loadVersioned(PlaybackSource("file:///C:/foreign.m4a", title = "Foreign"))
            result.complete(prepared(musicSourcePlaylistItem(MusicPlaybackSource.AudioSong(81))))
            f.await { !it.loading && !it.active }
            equal(foreign, f.player.currentSourceVersion); equal("Foreign", f.player.state.value.sourceTitle)
            equal(null, f.session.state.value.songInfo)
        }
    }
    case("cancelled old AU result cannot replace a newer AU song even if provider finishes late") {
        val started = CompletableDeferred<Unit>(); val late = CompletableDeferred<PreparedListenAudio>(); val finished = CompletableDeferred<Unit>()
        MusicSessionFixture(MusicDataSource { item ->
            if(item.bvid == "au81") withContext(NonCancellable) { started.complete(Unit); late.await().also { finished.complete(Unit) } }
            else prepared(item)
        }).use { f ->
            f.start(); withTimeout(3_000) { started.await() }; f.start(82); f.await { it.current?.bvid == "au82" && !it.loading }
            val owner = f.player.currentSourceVersion
            late.complete(prepared(musicSourcePlaylistItem(MusicPlaybackSource.AudioSong(81))))
            withTimeout(3_000) { finished.await() }; onSwing { }
            equal(owner, f.player.currentSourceVersion); equal(82L, f.session.state.value.songInfo?.id)
        }
    }
    case("saved AU queue resumes progress and owner-only close releases the audio source") {
        val item = musicSourcePlaylistItem(MusicPlaybackSource.AudioSong(81))
        MusicSessionFixture(MusicDataSource(::prepared), ListenAudioSaved(listOf(item), 0, positionSeconds = 22.5)).use { f ->
            onSwing { f.session.togglePause() }; f.await { !it.loading && it.active }
            equal(22.5, f.player.state.value.positionSeconds); equal(81L, f.session.state.value.songInfo?.id)
            val owner = f.player.currentSourceVersion
            onSwing { f.session.close() }; check(f.player.currentSourceVersion > owner)
            equal(null, f.player.currentSourceSnapshot()); equal(22.5, f.store.read().positionSeconds)
        }
    }
    case("NativeMusic preserves requested part and does not masquerade as AU song metadata") {
        val source = MusicPlaybackSource.VideoAudio("BVfixture", 4422, "指定分P")
        MusicSessionFixture(MusicDataSource(::prepared)).use { f ->
            onSwing { f.session.openNativeMusic(source) }; f.await { !it.loading && it.active }
            equal("BVfixture", f.session.state.value.current?.bvid); equal(4422L, f.session.state.value.current?.cid)
            val projection = projectNativeMusicState(source, f.session.state.value, f.player.state.value, true)
            equal(null, projection.songInfo); equal(source, projection.source)
            equal("video:BVfixture:4422", projectNativeMusicPlayerState(projection, PlaybackMode.SEQUENTIAL, 1.0).queue.single().stableId)
        }
    }
    case("invalid SID and CID fail before any repository call") {
        check(runCatching { musicSourcePlaylistItem(MusicPlaybackSource.AudioSong(0)) }.isFailure)
        check(runCatching { musicSourcePlaylistItem(MusicPlaybackSource.VideoAudio("BV", 0, "")) }.isFailure)
    }
    println("$count native MusicDetail/NativeMusic offline cases PASS. No HTTP, HWND, GPU, shared Gradle or account secrets.")
    count
}

class DesktopNativeMusicIntegrationTest {
    @org.junit.jupiter.api.Test
    fun originalAudioRequestsAndRetainedPlaybackOwnership() {
        org.junit.jupiter.api.Assertions.assertEquals(18, runNativeMusicOfflineCases())
    }
}
