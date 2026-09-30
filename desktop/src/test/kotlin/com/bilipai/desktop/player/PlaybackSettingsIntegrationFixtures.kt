package com.bilipai.desktop.player

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.audio.*
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.DesktopPlaybackController
import com.bilipai.desktop.DesktopPlaybackDataSource
import com.bilipai.desktop.data.*
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

private fun equal(expected: Any?, actual: Any?) { check(expected == actual) { "Expected $expected, actual $actual" } }
private fun info(bvid: String) = VideoDetails(bvid, 1, bvid, "", "", "", 0, 0, listOf(VideoPart(7, "first", 100)))
private fun onSwing(block: () -> Unit) { SwingUtilities.invokeAndWait(block) }

private class FixtureApi {
    private val json = Json { ignoreUnknownKeys = true }
    var playCalls = 0
    var navCalls = 0
    val requests = mutableListOf<okhttp3.Request>()
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests.add(request)
        val body = when(request.url.encodedPath) {
            "/x/player/wbi/playurl" -> {
                playCalls++
                json.encodeToString(PlayUrlResponse(data = PlayUrlData(quality = 80, dash = settingsFixtureDash(),
                    acceptQuality = listOf(80), acceptDescription = listOf("1080P"))))
            }
            "/x/web-interface/nav" -> {
                navCalls++
                """{"code":0,"data":{"wbi_img":{"img_url":"https://fixture.invalid/${"a".repeat(32)}.png","sub_url":"https://fixture.invalid/${"b".repeat(32)}.png"}}}"""
            }
            else -> error("Unexpected fixture API path: ${request.url.encodedPath}")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("offline fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
    fun repository(): DesktopRepository {
        val repo = DesktopRepository(DesktopSessionStore.temporary())
        fun set(name: String, value: Any) { DesktopRepository::class.java.getDeclaredField(name).apply { isAccessible = true }.set(repo, value) }
        set("api", api)
        set("visitorInitialized", true); set("visitorGeneration", repo.sessionEpoch)
        set("wbiKeys", "a".repeat(32) to "b".repeat(32)); set("wbiExpiresAt", System.currentTimeMillis() + 3_600_000)
        set("wbiGeneration", repo.sessionEpoch)
        return repo
    }
}

private class SettingsSource(private val repo: DesktopRepository) : DesktopPlaybackDataSource {
    data class Request(val preferences: PlayerPreferences, val codecOverride: String?, val forceRefresh: Boolean)
    val requests = CopyOnWriteArrayList<Request>()
    override suspend fun videoDetails(bvid: String) = info(bvid)
    override suspend fun related(bvid: String) = emptyList<VideoCard>()
    override suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String?, forceRefresh: Boolean): ResolvedSource =
        error("Controller must use its configured bottom-layer path")
    override suspend fun playbackConfigured(details: VideoDetails, index: Int, quality: Int, playbackPreferences: PlayerPreferences,
        blockedVideoCodecs: Set<String>, codecOverride: String?, forceRefresh: Boolean): ResolvedSource {
        requests.add(Request(playbackPreferences, codecOverride, forceRefresh))
        return repo.run { PlayUrlData(quality = 80, dash = settingsFixtureDash()).toPlaybackSource(
            details, quality, codecOverride, playbackPreferences, blockedVideoCodecs)!! }
    }
}

private class ControllerFixture : AutoCloseable {
    val player = MpvPlayer()
    var preferences = PlayerPreferences(defaultAudioQuality = 30251)
    val repo = DesktopRepository(DesktopSessionStore.temporary())
    val source = SettingsSource(repo)
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing + CoroutineExceptionHandler { _, error -> errors.add(error) })
    private val dir = Files.createTempDirectory("bilipai-settings-controller-")
    val remembered = mutableListOf<Int>()
    val controller = DesktopPlaybackController(repo, player, null, null, DesktopLibrary(dir),
        { preferences }, scope, dataSource = source, onRememberAudioQuality = {
            remembered.add(it); preferences = preferences.copy(lastSelectedAudioQuality = it)
        })
    suspend fun open() {
        onSwing { controller.open(VideoCard("BV-settings", "fixture", "", "", 0, 100)) }
        await { !it.opening && it.details != null }
    }
    suspend fun await(predicate: (com.bilipai.desktop.DesktopPlaybackState) -> Boolean) = withTimeout(3_000) {
        controller.state.first(predicate)
    }
    @Suppress("UNCHECKED_CAST")
    fun state(change: (PlayerState) -> PlayerState) = onSwing {
        val state = MpvPlayer::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(player) as MutableStateFlow<PlayerState>
        state.value = change(state.value)
    }
    override fun close() {
        onSwing { controller.close() }; scope.cancel(); player.close()
        val resolved = dir.toAbsolutePath().normalize()
        check(resolved.startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()) &&
            resolved.fileName.toString().startsWith("bilipai-settings-controller-"))
        Files.walk(resolved).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
            check(path.toAbsolutePath().normalize().startsWith(resolved)); Files.deleteIfExists(path)
        } }
        check(errors.isEmpty()) { "Unexpected controller background failure: ${errors.first()}" }
    }
}

internal suspend fun runPlaybackSettingsIntegrationTests(case: suspend (String, suspend () -> Unit) -> Unit) {
    case("original Retrofit fake response reaches preference-aware repository and media URL cache") {
        val api = FixtureApi(); val repo = api.repository(); val info = info("BV-settings")
        val high = PlayerPreferences(defaultAudioQuality = 30251)
        val first = repo.playback(info, playbackPreferences = high)
        equal("hev1", first.videoCodecFamily); equal("https://fixture.invalid/flac", first.audioUrl)
        equal(30251, first.audioSelection?.selectedPreferenceId)
        equal(first, repo.playback(info, playbackPreferences = high)); equal(1, api.playCalls)
        val changed = repo.playback(info, playbackPreferences = high.copy(defaultAudioQuality = -1))
        equal("https://fixture.invalid/aac-high", changed.audioUrl); equal(2, api.playCalls)
        val fast = repo.playback(info, playbackPreferences = high.copy(speed = 2.0))
        equal(-1, fast.audioSelection?.selectedPreferenceId)
        equal(AudioFallbackReason.SPEED_INCOMPATIBLE, fast.audioSelection?.fallbackReason)
        val refreshed = repo.playback(info, codecOverride = "avc1", forceRefresh = true, playbackPreferences = high)
        equal("avc1", refreshed.videoCodecFamily); equal(1, api.navCalls)
        check(api.requests.all { it.header("Cookie") == null })
        check(api.requests.filter { it.url.encodedPath.endsWith("playurl") }.all { it.url.queryParameter("cid") == "7" })
    }
    case("preferences store adds settings without resetting existing volume or speed") {
        val file = Files.createTempFile("bilipai-settings-store-", ".json")
        try {
            Files.writeString(file, "{\"volume\":31.0,\"speed\":1.25,\"unknownFutureField\":true}")
            val store = PlayerPreferencesStore(file); val previous = store.read()
            equal(31.0, previous.volume); equal(1.25, previous.speed); equal("hev1", previous.videoCodecPreference)
            val next = previous.copy(hardwareDecodeEnabled = false, defaultAudioQuality = 30251)
            store.save(next); equal(next.normalized(), store.read())
        } finally { Files.deleteIfExists(file) }
    }
    case("codec preference reload keeps native source token CID pause progress and Cast accessor") {
        ControllerFixture().use { f ->
            f.open()
            val owner = f.player.currentSourceVersion
            f.state { it.copy(loading = false, ready = true, videoCodec = "hevc", durationSeconds = 100.0,
                positionSeconds = 17.0, paused = true) }
            val old = f.preferences; f.preferences = old.copy(videoCodecPreference = "avc1")
            onSwing { f.controller.onPlaybackPreferencesChanged(old, f.preferences) }
            f.await { !it.opening && f.source.requests.size >= 2 }
            equal(owner, f.player.currentSourceVersion)
            equal(7L, f.controller.state.value.details?.pages?.single()?.cid)
            equal(17.0, f.player.state.value.positionSeconds); equal(true, f.player.state.value.paused)
            equal(null, f.source.requests.last().codecOverride)
            equal("avc1", f.controller.currentCastSource(owner)?.videoCodecFamily)
            equal(30251, f.controller.currentCastSource(owner)?.audioSelection?.requestedPreferenceId)
        }
    }
    case("default audio affects next load while explicit current selection persists only after accepted replacement") {
        ControllerFixture().use { f ->
            f.open(); val owner = f.player.currentSourceVersion
            val old = f.preferences; f.preferences = old.copy(defaultAudioQuality = -1)
            onSwing { f.controller.onPlaybackPreferencesChanged(old, f.preferences) }
            equal(1, f.source.requests.size)
            equal(30251, f.controller.currentCastSource(owner)?.audioSelection?.selectedPreferenceId)
            onSwing { f.controller.selectAudioQuality(30250) }
            f.await { !it.opening && f.source.requests.size >= 2 }
            equal(listOf(30250), f.remembered)
            equal(30250, f.controller.currentCastSource(owner)?.audioSelection?.selectedPreferenceId)
            equal(owner, f.player.currentSourceVersion)
        }
    }
    case("speed refresh changes only effective premium audio and preserves requested selection") {
        ControllerFixture().use { f ->
            f.open(); val owner = f.player.currentSourceVersion
            val previous = f.preferences; f.preferences = previous.copy(speed = 2.0)
            onSwing { f.player.setSpeed(2.0); f.controller.onPlaybackPreferencesChanged(previous, f.preferences) }
            f.await { !it.opening && f.source.requests.size >= 2 }
            val fast = f.controller.currentCastSource(owner)?.audioSelection
            equal(-1, fast?.selectedPreferenceId); equal(30251, fast?.requestedPreferenceId)
            equal(AudioFallbackReason.SPEED_INCOMPATIBLE, fast?.fallbackReason)
            equal(emptyList<Int>(), f.remembered)
            val old = f.preferences; f.preferences = old.copy(speed = 1.0)
            onSwing { f.player.setSpeed(1.0); f.controller.onPlaybackPreferencesChanged(old, f.preferences) }
            f.await { !it.opening && f.source.requests.size >= 3 }
            equal(30251, f.controller.currentCastSource(owner)?.audioSelection?.selectedPreferenceId)
        }
    }
    case("foreign native owner rejects a stale controller preferences mutation") {
        ControllerFixture().use { f ->
            f.open()
            onSwing { f.player.load(PlaybackSource("foreign.avi", title = "Foreign")) }
            val owner = f.player.currentSourceVersion
            val previous = f.preferences; f.preferences = previous.copy(hardwareDecodeEnabled = false, videoCodecPreference = "avc1")
            onSwing { f.controller.onPlaybackPreferencesChanged(previous, f.preferences) }
            equal(1, f.source.requests.size); equal(owner, f.player.currentSourceVersion)
            equal("Foreign", f.player.state.value.sourceTitle); check(f.player.state.value.hardwareDecodeEnabled)
        }
    }
}
