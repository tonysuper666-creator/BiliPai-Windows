package com.bilipai.desktop.player

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.audio.*
import com.android.purebilibili.feature.video.playback.policy.shouldRefreshPremiumAudioForPlaybackSpeedChange
import com.android.purebilibili.feature.video.subtitle.*
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue

private fun equal(expected: Any?, actual: Any?) { check(expected == actual) { "Expected $expected, actual $actual" } }
private val json = Json { ignoreUnknownKeys = true }
internal fun settingsFixtureDash() = Dash(video = listOf(
    DashVideo(id = 80, baseUrl = "https://fixture.invalid/hevc", codecs = "hev1.1.6", bandwidth = 300),
    DashVideo(id = 80, baseUrl = "https://fixture.invalid/avc", codecs = "avc1.640028", bandwidth = 200),
    DashVideo(id = 80, baseUrl = "https://fixture.invalid/av1", codecs = "av01.0.08M", bandwidth = 100)),
    audio = listOf(DashAudio(id = 30280, baseUrl = "https://fixture.invalid/aac-low", bandwidth = 100, codecs = "mp4a"),
        DashAudio(id = 30280, baseUrl = "https://fixture.invalid/aac-high", bandwidth = 200, codecs = "mp4a")),
    dolby = Dolby(type = 1, audio = listOf(DashAudio(id = 30250, baseUrl = "https://fixture.invalid/dolby", bandwidth = 500, codecs = "ec-3"))),
    flac = Flac(audio = DashAudio(id = 30251, baseUrl = "https://fixture.invalid/flac", bandwidth = 800, codecs = "fLaC")))
private fun dash() = settingsFixtureDash()

private fun track(language: String, ai: Boolean = false) = SubtitleItem(
    id = if(language.startsWith("zh")) 1 else 2, lan = language,
    lanDoc = if(ai) "AI $language" else language,
    subtitleUrl = "https://fixture.hdslb.com/subtitle/$language.json", aiType = if(ai) 1 else 0)
private fun metadata(aiPrimary: Boolean = false, aiSecondary: Boolean = false) = PlayerInfoData(
    subtitle = SubtitleInfo(lan = "en-US", subtitles = listOf(track("en-US", aiSecondary), track("zh-Hans", aiPrimary))))

private class FakeSubtitleSource(var response: PlayerInfoData = metadata()) : DesktopAutomaticSubtitleDataSource, AutoCloseable {
    val files = mutableListOf<Path>()
    var metadataHook: (suspend () -> Unit)? = null
    var importHook: (suspend () -> Unit)? = null
    var metadataCount = 0
    override suspend fun metadata(bvid: String, cid: Long): PlayerInfoData {
        metadataCount++; metadataHook?.invoke(); return response
    }
    override suspend fun import(track: SubtitleTrackMeta): Path {
        importHook?.invoke()
        return Files.createTempFile("bilipai-settings-subtitle-", ".srt").also {
            Files.writeString(it, "1\n00:00:00,000 --> 00:00:02,000\nfixture\n")
            synchronized(files) { files.add(it) }
        }
    }
    override fun close() { files.forEach(Files::deleteIfExists) }
}
private class FakeSubtitlePlayer(var owner: Long? = 1L) : DesktopAutomaticSubtitlePlayer {
    override var controlVersion = 0L
    val installs = mutableListOf<Triple<DesktopOnlineSubtitleAsset?, DesktopOnlineSubtitleAsset?, SubtitleDisplayMode>>()
    override fun owns(sourceVersion: Long) = sourceVersion == owner
    override fun install(sourceVersion: Long, expectedControlVersion: Long, primary: DesktopOnlineSubtitleAsset?,
        secondary: DesktopOnlineSubtitleAsset?, mode: SubtitleDisplayMode): Boolean {
        if (!owns(sourceVersion) || controlVersion != expectedControlVersion) return false
        controlVersion++
        installs.add(Triple(primary, secondary, mode))
        return true
    }
}

/** Run the actual private actor against a C API fake, without creating a HWND or starting its thread. */
private class DormantActor(val player: MpvPlayer) : AutoCloseable {
    val native = ActorNative()
    private val clazz = MpvPlayer::class.java.declaredClasses.single { it.simpleName == "Session" }
    private val actionClass = MpvPlayer::class.java.declaredClasses.single { it.simpleName == "Action" }
    private val snapshot = requireNotNull(player.currentSourceSnapshot())
    private val revision = MpvPlayer::class.java.getDeclaredField("playbackRevision").apply { isAccessible = true }.getLong(player)
    private val initialMuted = MpvPlayer::class.java.getDeclaredField("requestedLoadMute").apply { isAccessible = true }.get(player) as Boolean?
    private val actor = clazz.declaredConstructors.single().apply { isAccessible = true }
        .newInstance(player, 1L, snapshot.source, snapshot.sourceVersion, revision, initialMuted)
    private val sessionField = MpvPlayer::class.java.getDeclaredField("session").apply { isAccessible = true }
    private val perform = clazz.getDeclaredMethod("perform", MpvNative::class.java, Pointer::class.java, actionClass).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private val queue = clazz.getDeclaredField("commands").apply { isAccessible = true }.get(actor) as LinkedBlockingQueue<Any>
    init { sessionField.set(player, actor) }
    fun execute(action: Any) { perform.invoke(actor, native, Pointer(1L), action) }
    fun queued(): Any = requireNotNull(queue.poll())
    fun drain() { while (true) execute(queue.poll() ?: break) }
    fun loaded() { clazz.getDeclaredField("fileLoaded").apply { isAccessible = true }.setBoolean(actor, true) }
    override fun close() { sessionField.set(player, null) }
}
private class ActorNative : MpvNative {
    val properties = mutableListOf<Pair<String, String>>()
    private val tracks = mutableListOf<List<String>>()
    private val memories = mutableListOf<Memory>()
    var loadCount = 0
    override fun mpv_create(): Pointer? = error("No real native session")
    override fun mpv_initialize(handle: Pointer): Int = error("No real native session")
    override fun mpv_terminate_destroy(handle: Pointer) = error("No real native session")
    override fun mpv_set_option_string(handle: Pointer, name: String, value: String): Int = error("No real initialization")
    override fun mpv_set_property_string(handle: Pointer, name: String, value: String): Int {
        properties.add(name to value); return 0
    }
    override fun mpv_get_property_string(handle: Pointer, name: String): Pointer? {
        val track = Regex("track-list/(\\d+)/(.*)").matchEntire(name)
        val value = when {
            name == "playlist/0/id" -> "11"
            name == "track-list/count" -> tracks.size.toString()
            track != null -> {
                val index = track.groupValues[1].toInt()
                val item = tracks.getOrNull(index) ?: return null
                when(track.groupValues[2]) {
                    "id" -> (index + 1).toString(); "type" -> "sub"; "title" -> item[3]; "lang" -> item[4]
                    "external" -> "yes"; else -> "no"
                }
            }
            else -> return null
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        return Memory(bytes.size + 1L).also { it.write(0, bytes, 0, bytes.size); it.setByte(bytes.size.toLong(), 0); memories.add(it) }
    }
    override fun mpv_render_context_create(result: com.sun.jna.ptr.PointerByReference, handle: Pointer, params: Pointer): Int = error("Unexpected software render call in memory-only native fixture")
    override fun mpv_render_context_set_update_callback(context: Pointer, callback: MpvRenderUpdateCallback, data: Pointer?): Unit = error("Unexpected software render call in memory-only native fixture")
    override fun mpv_render_context_update(context: Pointer): Long = error("Unexpected software render call in memory-only native fixture")
    override fun mpv_render_context_render(context: Pointer, params: Pointer): Int = error("Unexpected software render call in memory-only native fixture")
    override fun mpv_render_context_free(context: Pointer): Unit = error("Unexpected software render call in memory-only native fixture")
    override fun mpv_free(data: Pointer) { memories.remove(data); (data as? Memory)?.close() }
    override fun mpv_command(handle: Pointer, args: StringArray): Int {
        val command = args.getStringArray(0).toList()
        check(command.first() == "sub-add") { "Unexpected native command" }
        tracks.add(command)
        return 0
    }
    override fun mpv_command_node(handle: Pointer, args: Pointer, result: Pointer?): Int { loadCount++; return 0 }
    override fun mpv_set_property(handle: Pointer, name: String, format: Int, data: Pointer): Int = error("Unexpected shader call")
    override fun mpv_get_property(handle: Pointer, name: String, format: Int, data: Pointer): Int = error("Unexpected shader call")
    override fun mpv_free_node_contents(node: Pointer) = error("Unexpected shader call")
    override fun mpv_request_log_messages(handle: Pointer, level: String): Int = error("No real log subscription")
    override fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer = error("No real event loop")
    override fun mpv_error_string(error: Int) = "fixture error"
}

class DesktopAdvancedPlaybackSettingsTest {
    @TestFactory fun originalPoliciesAndNativeActor(): List<DynamicTest> {
    val cases = mutableListOf<DynamicTest>()
    fun case(name: String, test: suspend () -> Unit) {
        cases.add(DynamicTest.dynamicTest(name) { runBlocking { test() } })
    }
    case("old preferences JSON preserves original defaults and user volume") {
        val p = json.decodeFromString<PlayerPreferences>("{\"volume\":23.0,\"speed\":1.25}").normalized()
        equal(23.0, p.volume); equal(true, p.hardwareDecodeEnabled)
        equal("hev1", p.videoCodecPreference); equal("avc1", p.videoSecondCodecPreference)
        equal(-2, p.defaultAudioQuality); equal(-1, p.lastSelectedAudioQuality)
        equal(SubtitleAutoPreference.OFF, p.subtitleAutoPreference)
    }
    case("new preferences round-trip with original subtitle enum and normalized codecs") {
        val original = PlayerPreferences(hardwareDecodeEnabled = false, videoCodecPreference = "AV01.0.08M",
            videoSecondCodecPreference = "h264", defaultAudioQuality = 30251, lastSelectedAudioQuality = 30250,
            subtitleAutoPreference = SubtitleAutoPreference.AUTO).normalized()
        equal(original, json.decodeFromString<PlayerPreferences>(json.encodeToString(original)))
        equal("av01", original.videoCodecPreference); equal("avc1", original.videoSecondCodecPreference)
        equal(-1, PlayerPreferences(defaultAudioQuality = 30280).normalized().defaultAudioQuality)
        equal("hev1", PlayerPreferences(videoCodecPreference = "garbage").normalized().videoCodecPreference)
    }
    case("user codec priorities use original selector and remain distinct from recovery override") {
        equal("https://fixture.invalid/hevc", resolveDesktopDashSelection(dash(), 80, PlayerPreferences()).video?.baseUrl)
        val p = PlayerPreferences(videoCodecPreference = "av01", videoSecondCodecPreference = "hev1")
        equal("hev1", resolveDesktopDashSelection(dash(), 80, p, isAv1Supported = false).video?.codecs?.substringBefore('.'))
        val forced = resolveDesktopDashSelection(dash(), 80, p, codecOverride = "avc1")
        equal("avc1", forced.firstCodec); equal("avc1", forced.secondCodec)
    }
    case("session-blocked AV1 cannot reappear during ordinary same-source refresh") {
        val p = PlayerPreferences(videoCodecPreference = "av01")
        val selected = resolveDesktopDashSelection(dash(), 80, p, blockedCodecs = setOf("av01"))
        equal("avc1", selected.firstCodec); equal("https://fixture.invalid/avc", selected.video?.baseUrl)
        equal("av01", p.videoCodecPreference)
    }
    case("follow-last audio prefers original HiRes candidate without changing persisted intent") {
        val p = PlayerPreferences(lastSelectedAudioQuality = 30251)
        val decision = resolveDesktopDashSelection(dash(), 80, p).audio
        equal(30251, decision.requestedPreferenceId); equal(30251, decision.selectedPreferenceId)
        equal("https://fixture.invalid/flac", decision.selected?.track?.baseUrl)
    }
    case("unavailable premium and unsupported Dolby use real AAC fallback and original reason") {
        val unavailable = resolveDesktopDashSelection(dash().copy(flac = null), 80,
            PlayerPreferences(defaultAudioQuality = 30251)).audio
        equal(AudioFallbackReason.REQUESTED_UNAVAILABLE, unavailable.fallbackReason)
        equal("https://fixture.invalid/aac-high", unavailable.selected?.track?.baseUrl)
        val unsupported = resolveDesktopDashSelection(dash(), 80, PlayerPreferences(defaultAudioQuality = 30250),
            isDolbyAudioSupported = false).audio
        equal(-1, unsupported.selectedPreferenceId); equal(AudioFallbackReason.REQUESTED_UNAVAILABLE, unsupported.fallbackReason)
    }
    case("premium speed threshold falls back temporarily and restores the requested preference") {
        val p = PlayerPreferences(defaultAudioQuality = 30250, speed = 1.5)
        equal(30250, resolveDesktopDashSelection(dash(), 80, p).audio.selectedPreferenceId)
        val fast = resolveDesktopDashSelection(dash(), 80, p.copy(speed = 1.51)).audio
        equal(-1, fast.selectedPreferenceId); equal(30250, fast.requestedPreferenceId)
        equal(AudioFallbackReason.SPEED_INCOMPATIBLE, fast.fallbackReason)
        check(shouldRefreshPremiumAudioForPlaybackSpeedChange(30250, 1.5f, 1.51f))
        equal(30250, resolveDesktopDashSelection(dash(), 80, p).audio.selectedPreferenceId)
    }
    case("hardware setting reaches actual native actor property") {
        MpvPlayer().use { player ->
            player.load(PlaybackSource("fixture.avi"))
            DormantActor(player).use { actor ->
                check(player.setHardwareDecodingEnabled(false)); actor.drain()
                equal("hwdec" to "no", actor.native.properties.last())
                check(player.setHardwareDecodingEnabled(true)); actor.drain()
                equal("hwdec" to "auto-safe", actor.native.properties.last())
            }
        }
    }
    case("same-owner software recovery defeats hardware intent and next owner respects user preference") {
        MpvPlayer().use { player ->
            val owner = player.loadVersioned(PlaybackSource("fixture.avi", startPositionSeconds = 3.0))
            DormantActor(player).use { actor ->
                val recoveryHardwareWrites = actor.native.properties.count { it.first == "hwdec" }
                check(player.recoverSource(owner, positionSeconds = 4.0, paused = true, forceSoftwareDecoding = true)); actor.drain()
                equal(recoveryHardwareWrites + 1, actor.native.properties.count { it.first == "hwdec" })
                equal(owner, player.currentSourceVersion); equal("hwdec" to "no", actor.native.properties.last { it.first == "hwdec" })
                check(player.setHardwareDecodingEnabled(true)); actor.drain(); equal("hwdec" to "no", actor.native.properties.last())
                check(player.setHardwareDecodingEnabled(false)); actor.drain()
                val nextSourceHardwareWrites = actor.native.properties.count { it.first == "hwdec" }
                player.load(PlaybackSource("next.avi")); actor.drain()
                equal(nextSourceHardwareWrites + 1, actor.native.properties.count { it.first == "hwdec" })
                equal(false, player.state.value.softwareDecodingRequested)
                equal("hwdec" to "no", actor.native.properties.last { it.first == "hwdec" })
                check(player.setHardwareDecodingEnabled(true)); actor.drain(); equal("hwdec" to "auto-safe", actor.native.properties.last())
            }
        }
    }
    case("queued old hardware request and explicit foreign setting cannot alter replacement source") {
        MpvPlayer().use { player ->
            val owner = player.loadVersioned(PlaybackSource("fixture.avi"))
            DormantActor(player).use { actor ->
                check(player.setHardwareDecodingEnabled(false)); val oldAction = actor.queued()
                player.load(PlaybackSource("next.avi")); actor.drain()
                val count = actor.native.properties.size
                actor.execute(oldAction); equal(count, actor.native.properties.size)
                check(!player.setHardwareDecodingEnabled(true, owner))
                equal(false, player.state.value.hardwareDecodeEnabled)
            }
        }
    }
    case("paired subtitles install both actual native slots and preserve ownership across recovery") {
        FakeSubtitleSource().use { source ->
            val metas = mapPlayerInfoSubtitleTracks(metadata().subtitle!!.subtitles)
            val p = DesktopOnlineSubtitleAsset(source.import(metas.first { it.lan == "zh-Hans" }), metas.first { it.lan == "zh-Hans" })
            val s = DesktopOnlineSubtitleAsset(source.import(metas.first { it.lan == "en-US" }), metas.first { it.lan == "en-US" })
            MpvPlayer().use { player ->
                val owner = player.loadVersioned(PlaybackSource("fixture.avi"))
                DormantActor(player).use { actor ->
                    actor.loaded()
                    check(player.installSubtitlePair(owner, player.currentSubtitleControlVersion, p, s, SubtitleDisplayMode.BILINGUAL))
                    actor.drain()
                    check("sid" to "1" in actor.native.properties); check("secondary-sid" to "2" in actor.native.properties)
                    check(player.recoverSource(owner, positionSeconds = 2.0, paused = true)); actor.drain(); actor.loaded()
                    equal(owner, player.currentSourceVersion)
                    check(player.installSubtitlePair(owner, player.currentSubtitleControlVersion, p, s, SubtitleDisplayMode.SECONDARY_ONLY))
                    actor.drain()
                    equal("no", actor.native.properties.last { it.first == "sid" }.second)
                    equal("2", actor.native.properties.last { it.first == "secondary-sid" }.second)
                }
            }
            check(source.files.all(Files::exists))
        }
    }
    case("a manual subtitle command invalidates a previously queued automatic visibility transaction") {
        MpvPlayer().use { player ->
            val owner = player.loadVersioned(PlaybackSource("fixture.avi"))
            DormantActor(player).use { actor ->
                check(player.installSubtitlePair(owner, player.currentSubtitleControlVersion, null, null, SubtitleDisplayMode.OFF))
                val automatic = actor.queued()
                player.setSubtitlesVisible(true); actor.drain()
                val count = actor.native.properties.size
                actor.execute(automatic); equal(count, actor.native.properties.size)
                equal("yes", actor.native.properties.last { it.first == "sub-visibility" }.second)
                player.stop()
                check(!player.installSubtitlePair(owner, player.currentSubtitleControlVersion, null, null, SubtitleDisplayMode.OFF))
            }
        }
    }
    case("original default language and mixed-AI bilingual semantics are retained") {
        FakeSubtitleSource(metadata(aiSecondary = true)).use { source ->
            val player = FakeSubtitlePlayer()
            DesktopAutomaticSubtitles(source, player).use { session ->
                session.load("BV-fixture", 1, 1, SubtitleAutoPreference.WITHOUT_AI, false)!!.join()
                equal("zh-Hans", player.installs.last().first?.track?.lan)
                equal("en-US", player.installs.last().second?.track?.lan)
                equal(SubtitleDisplayMode.BILINGUAL, session.state.value.mode)
            }
        }
    }
    case("AI AUTO mute initializes original mode and same session manual choice survives recovery") {
        FakeSubtitleSource(metadata(true, true)).use { source ->
            val player = FakeSubtitlePlayer()
            DesktopAutomaticSubtitles(source, player).use { session ->
                session.load("BV-ai", 1, 1, SubtitleAutoPreference.AUTO, true)!!.join()
                equal(SubtitleDisplayMode.BILINGUAL, session.state.value.mode)
                check(session.setDisplayMode(SubtitleDisplayMode.OFF))
                session.load("BV-ai", 1, 1, SubtitleAutoPreference.AUTO, true)!!.join()
                equal(SubtitleDisplayMode.OFF, session.state.value.mode)
                session.load("BV-ai", 2, 1, SubtitleAutoPreference.AUTO, false)!!.join()
                equal(SubtitleDisplayMode.OFF, session.state.value.mode)
            }
        }
    }
    case("noncooperative old metadata cannot bind subtitles into a newer native owner") {
        FakeSubtitleSource().use { source ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            source.metadataHook = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            val player = FakeSubtitlePlayer()
            DesktopAutomaticSubtitles(source, player).use { session ->
                val old = session.load("BV-old", 1, 1, SubtitleAutoPreference.ON, false)!!
                entered.await(); player.owner = 2; release.complete(Unit); old.join()
                equal(0, player.installs.size)
                source.metadataHook = null
                session.load("BV-new", 2, 2, SubtitleAutoPreference.ON, false)!!.join()
                equal(1, player.installs.size)
            }
        }
    }
    case("manual controls during asynchronous import prevent automatic overwrite") {
        FakeSubtitleSource().use { source ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            source.importHook = { entered.complete(Unit); release.await() }
            val player = FakeSubtitlePlayer()
            DesktopAutomaticSubtitles(source, player).use { session ->
                val loading = session.load("BV-fixture", 1, 1, SubtitleAutoPreference.ON, false)!!
                entered.await(); player.controlVersion++; release.complete(Unit); loading.join()
                equal(0, player.installs.size)
            }
        }
    }
    case("manual track selection stays across same-owner recovery and next owner can auto-load") {
        FakeSubtitleSource().use { source ->
            val player = FakeSubtitlePlayer()
            DesktopAutomaticSubtitles(source, player).use { session ->
                session.load("BV-first", 1, 1, SubtitleAutoPreference.ON, false)!!.join()
                session.onUserTrackSelection(1)
                equal(null, session.load("BV-first", 1, 1, SubtitleAutoPreference.ON, false))
                player.owner = 2
                session.load("BV-next", 2, 2, SubtitleAutoPreference.ON, false)!!.join()
                equal(2, source.metadataCount)
            }
        }
    }
    case("closing cancels pending subtitle query and keeps native-owned files for later reopen") {
        FakeSubtitleSource().use { source ->
            val player = FakeSubtitlePlayer()
            val session = DesktopAutomaticSubtitles(source, player)
            session.load("BV-fixture", 1, 1, SubtitleAutoPreference.ON, false)!!.join()
            val entered = CompletableDeferred<Unit>()
            source.metadataHook = { entered.complete(Unit); awaitCancellation() }
            val pending = session.load("BV-fixture", 1, 1, SubtitleAutoPreference.ON, false)!!
            entered.await(); session.close(); pending.join()
            check(pending.isCancelled); equal(1, player.installs.size)
            check(source.files.all(Files::exists))
            equal(null, session.load("BV-fixture", 1, 1, SubtitleAutoPreference.ON, false))
        }
    }
    case("metadata failures cannot leak credential-bearing transport error strings") {
        FakeSubtitleSource().use { source ->
            source.metadataHook = { error("https://fixture.invalid/?cookie=private-secret Authorization: private") }
            DesktopAutomaticSubtitles(source, FakeSubtitlePlayer()).use { session ->
                session.load("BV-fixture", 1, 1, SubtitleAutoPreference.ON, false)!!.join()
                equal("字幕加载失败，请手动重试。", session.state.value.error)
                check(!session.state.value.loading)
            }
        }
    }
    runBlocking { runPlaybackSettingsIntegrationTests { name, test -> case(name, test) } }
    return cases
    }
}
