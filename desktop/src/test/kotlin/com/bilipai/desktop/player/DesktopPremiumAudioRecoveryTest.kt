package com.bilipai.desktop.player

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.audio.*
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import com.bilipai.desktop.player.platform.DesktopPremiumAudioMedia3ErrorCodes as Codes
import com.sun.jna.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue

private fun equal(expected: Any?, actual: Any?) { check(expected == actual) { "Expected $expected, actual $actual" } }
private fun dash() = Dash(video = listOf(DashVideo(id = 80, baseUrl = "https://fixture.invalid/video", codecs = "avc1.640028")),
    audio = listOf(DashAudio(id = 30280, baseUrl = "https://fixture.invalid/aac-low", bandwidth = 100, codecs = "mp4a"),
        DashAudio(id = 30280, baseUrl = "https://fixture.invalid/aac-high", backupUrl = listOf("https://fixture.invalid/aac-backup"), bandwidth = 200, codecs = "mp4a")),
    flac = Flac(audio = DashAudio(id = 30251, baseUrl = "https://fixture.invalid/flac", bandwidth = 800, codecs = "fLaC")))
private fun resolved(value: Dash = dash(), quality: Int = AUDIO_QUALITY_HI_RES) = ResolvedSource("https://fixture.invalid/actual-cdn-video",
    "https://fixture.invalid/flac", "Native audio fixture", "https://www.bilibili.com/", quality = 80,
    videoAlternatives = listOf("https://fixture.invalid/video-backup"), videoCodecFamily = "avc1", cachedDashData = value,
    audioSelection = resolveAudioStreamSelection(value, quality))
private fun audioFailure(owner: Long = 1, attempt: Long = 2) = PlayerFailure(PlayerFailureKind.AUDIO_OUTPUT, -14,
    "Audio output initialization failed", sourceVersion = owner, attemptId = attempt)
private fun ready(source: ResolvedSource = resolved(), failure: PlayerFailure = audioFailure()) =
    resolveDesktopPremiumAudioRecovery(source, failure, 1.0) as DesktopPremiumAudioRecoveryPlan.Ready

/** Actual private native actor and real end-file ABI; it never starts a thread, HWND or real native DLL. */
internal class PremiumRecoveryActor(private val player: MpvPlayer) : AutoCloseable {
    val native = PremiumRecoveryNative()
    private val clazz = MpvPlayer::class.java.declaredClasses.single { it.simpleName == "Session" }
    private val action = MpvPlayer::class.java.declaredClasses.single { it.simpleName == "Action" }
    private val snapshot = requireNotNull(player.currentSourceSnapshot())
    private val revision = MpvPlayer::class.java.getDeclaredField("playbackRevision").apply { isAccessible = true }.getLong(player)
    private val initialMuted = MpvPlayer::class.java.getDeclaredField("requestedLoadMute").apply { isAccessible = true }.get(player) as Boolean?
    private val actor = clazz.getDeclaredConstructor(
        MpvPlayer::class.java, java.lang.Long.TYPE, PlaybackSource::class.java,
        java.lang.Long.TYPE, java.lang.Long.TYPE, java.lang.Boolean::class.java,
        DesktopNativePresentationTransfer::class.java, DesktopNativeTerminalPresentation::class.java,
    ).apply { isAccessible = true }
        .newInstance(player, 1L, snapshot.source, snapshot.sourceVersion, revision, initialMuted, null, null)
    private val field = MpvPlayer::class.java.getDeclaredField("session").apply { isAccessible = true }
    private val perform = clazz.getDeclaredMethod("perform", MpvNative::class.java, Pointer::class.java, action).apply { isAccessible = true }
    private val receive = clazz.getDeclaredMethod("receiveEvent", MpvNative::class.java, Pointer::class.java, Pointer::class.java).apply { isAccessible = true }
    private val refresh = clazz.getDeclaredMethod("refreshState", MpvNative::class.java, Pointer::class.java).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private val commands = clazz.getDeclaredField("commands").apply { isAccessible = true }.get(actor) as LinkedBlockingQueue<Any>
    init { field.set(player, actor) }
    fun drain() { while(true) perform.invoke(actor, native, Pointer(1L), commands.poll() ?: break) }
    fun loaded() {
        Memory(8).use { entry -> entry.setLong(0, 11); event(6, entry) }
        event(8, null)
        refresh.invoke(actor, native, Pointer(1L))
    }
    fun fail(code: Int): PlayerFailure {
        Memory(24).use { end ->
            end.clear(); end.setInt(0, 4); end.setInt(4, code); end.setLong(8, 11); event(7, end)
        }
        return requireNotNull(player.state.value.failure)
    }
    private fun event(id: Int, data: Pointer?) = Memory(24).use { event ->
        event.clear(); event.setInt(0, id); event.setPointer(16, data); receive.invoke(actor, native, Pointer(1L), event); Unit
    }
    override fun close() { field.set(player, null); native.close() }
}
internal class PremiumRecoveryNative : MpvNative, AutoCloseable {
    val loads = mutableListOf<List<Any>>()
    val properties = mutableListOf<Pair<String, String>>()
    private val buffers = mutableListOf<Memory>()
    private val values = mutableMapOf("playlist/0/id" to "11", "time-pos" to "27.75", "duration" to "120.0", "pause" to "yes",
        "video-codec" to "h264", "audio-codec" to "flac", "track-list/count" to "0", "volume" to "37.0", "speed" to "1.0")
    override fun mpv_get_property_string(handle: Pointer, name: String): Pointer? {
        val text = values[name] ?: return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        return Memory(bytes.size + 1L).also { it.write(0, bytes, 0, bytes.size); it.setByte(bytes.size.toLong(), 0); buffers.add(it) }
    }
    override fun mpv_render_context_create(result: com.sun.jna.ptr.PointerByReference, handle: Pointer, params: Pointer): Int = error("Unexpected software render call in memory-only native fixture")
    override fun mpv_render_context_set_update_callback(context: Pointer, callback: MpvRenderUpdateCallback, data: Pointer?): Unit = error("Unexpected software render call in memory-only native fixture")
    override fun mpv_render_context_update(context: Pointer): Long = error("Unexpected software render call in memory-only native fixture")
    override fun mpv_render_context_render(context: Pointer, params: Pointer): Int = error("Unexpected software render call in memory-only native fixture")
    override fun mpv_render_context_free(context: Pointer): Unit = error("Unexpected software render call in memory-only native fixture")
    override fun mpv_free(data: Pointer) { buffers.remove(data); (data as? Memory)?.close() }
    override fun mpv_set_property_string(handle: Pointer, name: String, value: String): Int { properties.add(name to value); values[name] = value; return 0 }
    private fun node(pointer: Pointer): Any = when(pointer.getInt(8)) {
        1 -> pointer.getPointer(0).getString(0, "UTF-8")
        7, 8 -> {
            val list = pointer.getPointer(0); val count = list.getInt(0); val values = list.getPointer(8)
            if(pointer.getInt(8) == 7) (0 until count).map { node(values.share(it * 16L)) }
            else { val keys = list.getPointer(16); (0 until count).associate { keys.getPointer(it * 8L).getString(0, "UTF-8") to node(values.share(it * 16L)) } }
        }
        else -> error("Unexpected node format")
    }
    @Suppress("UNCHECKED_CAST")
    override fun mpv_command_node(handle: Pointer, args: Pointer, result: Pointer?): Int {
        val command = node(args) as List<Any>; check(command.first() == "loadfile"); loads.add(command); return 0
    }
    override fun mpv_command(handle: Pointer, args: StringArray): Int { check(args.getStringArray(0).first() == "sub-add"); return 0 }
    override fun mpv_error_string(error: Int) = if(error == -14) "audio output initialization failed" else "fixture terminal error"
    override fun mpv_create(): Pointer? = error("No real native initialization")
    override fun mpv_initialize(handle: Pointer): Int = error("No real native initialization")
    override fun mpv_terminate_destroy(handle: Pointer) = error("No real native initialization")
    override fun mpv_set_option_string(handle: Pointer, name: String, value: String): Int = error("Unexpected initialization")
    override fun mpv_set_property(handle: Pointer, name: String, format: Int, data: Pointer): Int = error("Unexpected shader")
    override fun mpv_get_property(handle: Pointer, name: String, format: Int, data: Pointer): Int = -10
    override fun mpv_free_node_contents(node: Pointer) = error("Unexpected shader")
    override fun mpv_request_log_messages(handle: Pointer, level: String): Int = error("No event loop")
    override fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer = error("No event loop")
    override fun close() { buffers.forEach { it.close() }; buffers.clear() }
}

class DesktopPremiumAudioRecoveryTest {
    @TestFactory fun originalPoliciesAndNativeActor(): List<DynamicTest> {
    val cases = mutableListOf<DynamicTest>()
    fun case(name: String, test: suspend () -> Unit) {
        cases.add(DynamicTest.dynamicTest(name) { runBlocking { test() } })
    }
    case("unchanged original HiRes policy requires an audio renderer and supported error code") {
        check(isPremiumAudioPlaybackFailure(Codes.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, AUDIO_QUALITY_HI_RES, "mpv/audio-output", null))
        check(!isPremiumAudioPlaybackFailure(Codes.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, AUDIO_QUALITY_DOLBY, "audio", null))
        check(!isPremiumAudioPlaybackFailure(Codes.ERROR_CODE_DECODING_FAILED, AUDIO_QUALITY_HI_RES, "video", "video/avc"))
        check(isPremiumAudioPlaybackFailure(Codes.ERROR_CODE_DECODING_FAILED, AUDIO_QUALITY_HI_RES, null, "audio/flac"))
    }
    case("native mapper accepts only actual AO init failure and never generic or video decoder evidence") {
        equal(Codes.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, desktopPremiumAudioFailureCode(audioFailure()))
        for(kind in listOf(PlayerFailureKind.DECODER, PlayerFailureKind.VIDEO_OUTPUT, PlayerFailureKind.NETWORK, PlayerFailureKind.UNKNOWN))
            equal(null, desktopPremiumAudioFailureCode(audioFailure().copy(kind = kind)))
        equal(null, desktopPremiumAudioFailureCode(audioFailure().copy(nativeCode = -15)))
        equal(null, desktopPremiumAudioFailureCode(audioFailure().copy(nativeCode = null)))
    }
    case("actual original Retrofit fake PlayUrl reply feeds original cached DASH AAC selection") {
        var calls = 0
        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++; check(chain.request().url.encodedPath == "/x/player/wbi/playurl")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("offline")
                .body(json.encodeToString(PlayUrlResponse(data = PlayUrlData(dash = dash(), quality = 80))).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
        val reply = api.getPlayUrl(mapOf("bvid" to "BVfixture", "cid" to "901", "qn" to "80"))
        val source = resolved(requireNotNull(reply.data?.dash)); val result = ready(source).source
        equal("https://fixture.invalid/aac-high", result.audioUrl); equal(listOf("https://fixture.invalid/aac-backup"), result.audioAlternatives)
        equal(source.videoUrl, result.videoUrl); equal(source.videoAlternatives, result.videoAlternatives); equal(1, calls)
        equal(AUDIO_QUALITY_HI_RES, result.audioSelection?.requestedPreferenceId)
        equal(AUDIO_QUALITY_AUTO, result.audioSelection?.selectedPreferenceId); equal(AudioFallbackReason.DECODER_ERROR, result.audioSelection?.fallbackReason)
        equal(AUDIO_QUALITY_AUTO, effectiveAudioPreferenceAfterFailure(result.audioSelection))
    }
    case("AAC and Dolby selected audio never trigger HiRes fallback") {
        for(quality in listOf(AUDIO_QUALITY_AUTO, AUDIO_QUALITY_DOLBY))
            equal(DesktopPremiumAudioRecoveryPlan.NotApplicable, resolveDesktopPremiumAudioRecovery(resolved(quality = quality), audioFailure(), 1.0))
    }
    case("missing cached DASH or absent standard audio is explicitly unavailable") {
        val source = resolved()
        equal(DesktopPremiumAudioRecoveryPlan.Unavailable(DesktopPremiumAudioRecoveryPlan.Reason.MISSING_CACHED_DASH),
            resolveDesktopPremiumAudioRecovery(source.copy(cachedDashData = null), audioFailure(), 1.0))
        equal(DesktopPremiumAudioRecoveryPlan.Unavailable(DesktopPremiumAudioRecoveryPlan.Reason.NO_STANDARD_AUDIO),
            resolveDesktopPremiumAudioRecovery(source.copy(cachedDashData = dash().copy(audio = emptyList())), audioFailure(), 1.0))
    }
    case("invalid standard URLs cannot be turned into a successful fallback") {
        val source = resolved().copy(cachedDashData = dash().copy(audio = listOf(DashAudio(baseUrl = "file:///C:/fake.m4a", bandwidth = 900))))
        equal(DesktopPremiumAudioRecoveryPlan.Unavailable(DesktopPremiumAudioRecoveryPlan.Reason.NO_PLAYABLE_STANDARD_URL),
            resolveDesktopPremiumAudioRecovery(source, audioFailure(), 1.0))
    }
    case("actual native actor AO end-file becomes typed failure then same-owner AAC reload keeps pause position headers and subtitles") {
        val subtitle = Files.createTempFile("bilipai-premium-audio-", ".srt")
        try {
            Files.writeString(subtitle, "1\n00:00:00,000 --> 00:00:50,000\nfixture\n")
            MpvPlayer().use { player ->
                val token = player.loadVersioned(PlaybackSource("https://fixture.invalid/actual-cdn-video", "https://fixture.invalid/flac",
                    cookieHeader = "fixture-cookie-only", title = "Fixture", startPositionSeconds = 27.75, startPaused = true,
                    streamHeaders = mapOf("X-Fixture" to "preserved")))
                PremiumRecoveryActor(player).use { actor ->
                    check(player.recoverSource(token)); actor.drain(); actor.loaded()
                    player.addSubtitle(subtitle, "Fixture subtitle", "zh", select = false); actor.drain()
                    val failure = actor.fail(-14); equal(PlayerFailureKind.AUDIO_OUTPUT, failure.kind); equal(-14, failure.nativeCode)
                    val oldSnapshot = requireNotNull(player.currentSourceSnapshot()).source
                    val plan = ready(failure = failure)
                    check(!applyDesktopPremiumAudioRecovery(player, failure.copy(kind = PlayerFailureKind.DECODER), plan) { true })
                    check(applyDesktopPremiumAudioRecovery(player, failure, plan) { true }); equal(token, player.currentSourceVersion)
                    equal(27.75, player.state.value.positionSeconds); check(player.state.value.paused)
                    check(!player.state.value.softwareDecodingRequested)
                    val current = requireNotNull(player.currentSourceSnapshot()).source
                    equal(oldSnapshot.videoUrl, current.videoUrl); equal(oldSnapshot.streamHeaders, current.streamHeaders)
                    equal(oldSnapshot.cookieHeader, current.cookieHeader); equal("https://fixture.invalid/aac-high", current.audioUrl)
                    actor.drain()
                    @Suppress("UNCHECKED_CAST") val options = actor.native.loads.last()[4] as Map<String, String>
                    equal("https://fixture.invalid/aac-high", options["audio-files"]); equal("27.75", options["start"]); equal("yes", options["pause"])
                    equal("https://fixture.invalid/actual-cdn-video", actor.native.loads.last()[1])
                    val assets = MpvPlayer::class.java.getDeclaredField("externalSubtitles").apply { isAccessible = true }.get(player) as List<*>
                    equal(1, assets.size)
                    check(!applyDesktopPremiumAudioRecovery(player, failure, plan) { true })
                }
            }
        } finally { Files.deleteIfExists(subtitle) }
    }
    case("replaced media owner account or failure attempt cannot apply stale premium fallback") {
        MpvPlayer().use { player ->
            val owner = player.loadVersioned(PlaybackSource("https://fixture.invalid/video", "https://fixture.invalid/flac"))
            PremiumRecoveryActor(player).use { actor ->
                check(player.recoverSource(owner)); actor.drain(); actor.loaded(); val failure = actor.fail(-14); val plan = ready(failure = failure)
                check(!applyDesktopPremiumAudioRecovery(player, failure, plan) { false })
                equal("https://fixture.invalid/flac", player.currentSourceSnapshot()?.source?.audioUrl)
                val replacement = player.loadVersioned(PlaybackSource("file:///C:/foreign.avi"))
                check(!applyDesktopPremiumAudioRecovery(player, failure, plan) { true }); equal(replacement, player.currentSourceVersion)
            }
        }
    }
    return cases
    }
}
