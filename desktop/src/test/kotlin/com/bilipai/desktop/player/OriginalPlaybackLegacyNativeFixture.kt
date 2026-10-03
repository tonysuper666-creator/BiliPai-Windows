package com.bilipai.desktop.player

import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlaybackPreferenceOperation
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.sin

/** Opt-in actual MPV commands/local encoded video+audio; independent native leaf scope. */
object OriginalPlaybackLegacyNativeFixture {
    private fun waitFor(player: MpvPlayer, live: AtomicBoolean, description: String, predicate: (PlayerState) -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos()
        while (System.nanoTime() < deadline) {
            check(live.get()) { "Owned native window retired during $description" }
            val state = player.state.value
            check(state.error == null && state.operationError == null) { "Actual MPV failed during $description: $state" }
            if (predicate(state)) return
            Thread.sleep(75)
        }
        error("Actual MPV timed out: $description; ${player.state.value}")
    }
    private fun record(rows: MutableList<JsonObject>, id: String, player: MpvPlayer, extra: Map<String, JsonElement> = emptyMap()) {
        val state = player.state.value
        rows.add(buildJsonObject {
            put("id", id); put("sourceVersion", player.currentSourceVersion); put("speed", state.speed)
            put("nativeVersion", state.nativeVersion?.let(::JsonPrimitive) ?: JsonNull)
            put("volume", state.volume); put("muted", state.muted); put("audioOnlyIntent", state.audioOnly)
            put("loopIntent", state.looping); put("nativePaused", state.nativePaused?.let(::JsonPrimitive) ?: JsonNull)
            put("positionSeconds", state.positionSeconds); put("durationSeconds", state.durationSeconds)
            put("videoCodec", state.videoCodec?.let(::JsonPrimitive) ?: JsonNull)
            put("audioCodec", state.audioCodec?.let(::JsonPrimitive) ?: JsonNull)
            put("tracks", JsonArray(state.tracks.map { buildJsonObject { put("id", it.id); put("type", it.type); put("selected", it.selected) } }))
            put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
            put("operationError", state.operationError?.let(::JsonPrimitive) ?: JsonNull)
            extra.forEach { (name, value) -> put(name, value) }
        })
    }
    private fun drain(player: MpvPlayer, version: Long) {
        check(runBlocking { player.drainSourceCommands(version) }) { "Actual MPV command barrier did not acknowledge source" }
        Thread.sleep(450) // require subsequent native polling, not optimistic setter state alone
        check(player.currentSourceVersion == version)
    }
    private fun clockAtSpeed(player: MpvPlayer, live: AtomicBoolean, speed: Double): Double {
        player.setPaused(true)
        waitFor(player, live, "actual native pause") { it.nativePaused == true }
        player.seekTo(1.0)
        drain(player, player.currentSourceVersion)
        waitFor(player, live, "actual native seek at 1 second") { abs(it.positionSeconds - 1.0) < 0.3 }
        player.setPaused(false)
        waitFor(player, live, "actual native resume") { it.nativePaused == false && it.positionSeconds > 1.05 }
        val before = player.state.value.positionSeconds
        val started = System.nanoTime()
        Thread.sleep(1000)
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        val delta = player.state.value.positionSeconds - before
        check(player.state.value.muted && abs(player.state.value.speed - speed) < 0.02)
        check(delta > seconds * speed * 0.65 && delta < seconds * speed * 1.35) {
            "Actual native source clock differs from speed $speed: delta=$delta elapsed=$seconds"
        }
        return delta / seconds
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1 && !SwingUtilities.isEventDispatchThread())
        val report = Path.of(args[0]).toRealPath()
        Files.list(report).use { check(it.findAny().isEmpty) }
        val video = report.resolve("local-native-video.avi").toFile()
        val audio = report.resolve("local-native-audio.wav").toFile()
        createVideo(video); createAudio(audio) // unchanged existing PlayerSelfTest media encoder
        val player = MpvPlayer()
        player.setVolume(0.0); player.setMuted(true) // retained before mounting: no test audio reaches speakers
        val live = AtomicBoolean(true)
        val windowRef = AtomicReference<JFrame?>()
        val rows = mutableListOf<JsonObject>()
        var success = false
        var failure: Throwable? = null
        try {
            SwingUtilities.invokeAndWait {
                windowRef.set(JFrame("BiliPai · muted legacy preference native fixture").apply {
                    defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
                    addWindowListener(object : WindowAdapter() {
                        override fun windowClosing(event: WindowEvent) { live.set(false) }
                        override fun windowClosed(event: WindowEvent) { live.set(false) }
                    })
                    contentPane.add(player.surface); setSize(720, 440); setLocationRelativeTo(null)
                    isVisible = true
                })
            }
            waitFor(player, live, "real native initialization, retained muted volume0") { it.ready && it.muted && it.volume < 0.1 }
            player.load(PlaybackSource(video.absolutePath, audio.absolutePath, referer = "", title = "Local silent legacy regression", startPaused = true))
            waitFor(player, live, "actual separate video/audio native file and native pause") {
                !it.loading && it.nativePaused == true && !it.videoCodec.isNullOrBlank() && !it.audioCodec.isNullOrBlank() && it.durationSeconds > 9
            }
            val snapshot = requireNotNull(player.currentSourceSnapshot())
            val version = player.currentSourceVersion
            check(snapshot.sourceVersion == version && player.ownsSourceSnapshot(snapshot))
            val store = DesktopPluginStore(report.resolve("private-settings"))
            val plugin = DesktopPluginContext(store)
            val owns = { live.get() && player.ownsSourceSnapshot(snapshot) }
            val context = DesktopOriginalPlayerSettingsContext(plugin, owns, commitIfCurrent = { action ->
                if (owns()) { action(); true } else false
            })
            record(rows, "native-local-source-muted-volume0", player)
            runBlocking { DesktopOriginalPlaybackPreferenceOperation.run(context, owns) {
                DesktopOriginalVideoPlayerSettings.setLastPlaybackSpeed(context, 1.5f)
            } }
            player.setSpeed(1.5); player.setAudioOnly(true); player.setLoop(true)
            drain(player, version)
            waitFor(player, live, "actual newer speed/audio-only choices") {
                abs(it.speed - 1.5) < 0.01 && it.audioOnly && it.looping && it.muted &&
                    it.tracks.any { t -> t.type == "audio" && t.selected } && it.tracks.none { t -> t.type == "video" && t.selected }
            }
            val oldUi = PlayerPreferences(volume = 0.0, speed = 1.0, muted = true, audioOnly = false, playbackMode = PlaybackMode.SEQUENTIAL)
            val nextUi = oldUi.copy(volume = 37.0)
            val changes = DesktopLegacyPlaybackPreferenceChanges.between(oldUi, nextUi)
            check(changes == DesktopLegacyPlaybackPreferenceChanges(false, true, false, false, false, false))
            player.applyLegacyPreferenceChanges(changes, nextUi)
            drain(player, version)
            waitFor(player, live, "volume-only command preserves newer actual speed/audio choice") {
                abs(it.volume - 37.0) < 0.1 && abs(it.speed - 1.5) < 0.01 && it.muted && it.audioOnly &&
                    it.tracks.none { t -> t.type == "video" && t.selected }
            }
            val measuredRate = clockAtSpeed(player, live, 1.5)
            player.seekTo(9.1); drain(player, version)
            waitFor(player, live, "actual native EOF wraps after volume-only change") { !it.ended && it.positionSeconds < 2 && it.nativePaused == false }
            check(player.ownsSourceSnapshot(snapshot))
            record(rows, "volume-only-preserves-real-native-speed-audio-and-loop", player,
                mapOf("measuredNativeClockRate" to JsonPrimitive(measuredRate), "actualEofWrapObserved" to JsonPrimitive(true)))
            // SAME production helper/force delta as Root's explicit SetSpeed command.
            // Root's physical shortcut input itself is outside this independent native scope.
            val sameCachedSpeed = nextUi.copy(speed = oldUi.speed)
            check(DesktopLegacyPlaybackPreferenceChanges.between(nextUi, sameCachedSpeed).speed == false)
            val forced = DesktopLegacyPlaybackPreferenceChanges.between(nextUi, sameCachedSpeed).copy(speed = true)
            runBlocking { DesktopOriginalPlaybackPreferenceOperation.run(context, owns) {
                DesktopOriginalVideoPlayerSettings.setLastPlaybackSpeed(context, sameCachedSpeed.speed.toFloat())
            } }
            player.applyLegacyPreferenceChanges(forced, sameCachedSpeed)
            drain(player, version)
            waitFor(player, live, "same legacy cached speed forced into real native actor") { abs(it.speed - 1.0) < 0.01 && it.audioOnly && it.muted }
            val forcedRate = clockAtSpeed(player, live, 1.0)
            check(runBlocking { DesktopOriginalVideoPlayerSettings.getLastPlaybackSpeed(context).first() } == 1.0f)
            val disk = Json.parseToJsonElement(Files.readString(report.resolve("private-settings/plugin-settings.json"))).jsonObject
            // Actual Store document is private to this fixture; inspect namespaces without using a second backing.
            check((disk["settings"]?.jsonObject?.get("last_playback_speed") as? JsonPrimitive)?.floatOrNull == 1.0f)
            check((disk["playback_speed_cache"]?.jsonObject?.get("last_speed") as? JsonPrimitive)?.floatOrNull == 1.0f)
            record(rows, "same-cached-speed-still-reaches-real-native-and-original-store", player,
                mapOf("measuredNativeClockRate" to JsonPrimitive(forcedRate), "physicalRootShortcutExercised" to JsonPrimitive(false)))
            success = true
        } catch (error: Throwable) {
            failure = error; error.printStackTrace()
        } finally {
            live.set(false)
            player.close()
            SwingUtilities.invokeAndWait { windowRef.get()?.dispose() }
            val receipt = buildJsonObject {
                put("schema", 1); put("passed", success); put("scope", "ACTUAL_OWNED_LOCAL_MPV_PRODUCTION_DELTA_LEAF")
                put("actualMainInvoked", false); put("physicalRootShortcutExercised", false); put("otherApplicationPauseRequested", false)
                put("realAccountUsed", false); put("networkSourceUsed", false); put("initialVolumeZero", true); put("mutedThroughout", true)
                put("ownedWindowDisposed", windowRef.get()?.isDisplayable != true)
                put("sourceOwnerRetired", !live.get()); put("error", failure?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                put("observations", JsonArray(rows))
            }
            Files.writeString(report.resolve("native-observations.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), receipt), CREATE_NEW, WRITE)
        }
        check(success) { "Actual native legacy preference regression failed: $failure" }
    }

    private const val WIDTH = 320
    private const val HEIGHT = 180
    private const val FPS = 20
    private const val SECONDS = 10

    private fun createVideo(file: File) {
        val frames = List(FPS * SECONDS) { index ->
            val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                color = Color(24, 27, 38); fillRect(0, 0, WIDTH, HEIGHT)
                color = Color(250, 106, 151); fillRect(0, HEIGHT - 24, WIDTH * index / (FPS * SECONDS), 24)
                color = Color(82, 191, 248); fillOval(10 + index % 260, 45, 44, 44)
                color = Color.WHITE; font = Font("SansSerif", Font.BOLD, 16)
                drawString("BiliPai · Native Windows", 18, 30)
                font = Font("Monospaced", Font.PLAIN, 15)
                drawString("DASH test: ${index / FPS}.${index % FPS * 5}s", 18, 135)
                dispose()
            }
            ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        }
        val avih = leInts(1_000_000 / FPS, 0, 0, 0x10, frames.size, 0, 1, 64 * 1024, WIDTH, HEIGHT, 0, 0, 0, 0)
        val strh = ByteArrayOutputStream().apply {
            write("vidsMJPG".toByteArray(Charsets.US_ASCII))
            write(leInts(0, 0, 0, 1, FPS, 0, frames.size, 64 * 1024, -1, 0))
            write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(0).putShort(0)
                .putShort(WIDTH.toShort()).putShort(HEIGHT.toShort()).array())
        }.toByteArray()
        val strf = ByteArrayOutputStream().apply {
            write(leInts(40, WIDTH, HEIGHT))
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(1).putShort(24).array())
            write("MJPG".toByteArray(Charsets.US_ASCII))
            write(leInts(WIDTH * HEIGHT * 3, 0, 0, 0, 0))
        }.toByteArray()
        val hdrl = listChunk("hdrl", chunk("avih", avih) + listChunk("strl", chunk("strh", strh) + chunk("strf", strf)))
        val movie = ByteArrayOutputStream()
        val index = ByteArrayOutputStream()
        frames.forEach { frame ->
            index.write("00dc".toByteArray(Charsets.US_ASCII))
            index.write(leInts(0x10, movie.size() + 4, frame.size))
            movie.write(chunk("00dc", frame))
        }
        val body = "AVI ".toByteArray(Charsets.US_ASCII) + hdrl + listChunk("movi", movie.toByteArray()) + chunk("idx1", index.toByteArray())
        file.writeBytes("RIFF".toByteArray(Charsets.US_ASCII) + leInts(body.size) + body)
    }

    private fun createAudio(file: File) {
        val sampleRate = 48_000
        val samples = ByteBuffer.allocate(sampleRate * SECONDS * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(sampleRate * SECONDS) { index ->
            val amplitude = (sin(index * 2.0 * Math.PI * 440.0 / sampleRate) * 1_500).toInt().toShort()
            samples.putShort(amplitude)
        }
        val format = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16).array()
        val body = "WAVE".toByteArray(Charsets.US_ASCII) + chunk("fmt ", format) + chunk("data", samples.array())
        file.writeBytes("RIFF".toByteArray(Charsets.US_ASCII) + leInts(body.size) + body)
    }

    private fun chunk(tag: String, body: ByteArray): ByteArray =
        tag.toByteArray(Charsets.US_ASCII) + leInts(body.size) + body + if (body.size % 2 == 0) byteArrayOf() else byteArrayOf(0)
    private fun listChunk(type: String, body: ByteArray): ByteArray = chunk("LIST", type.toByteArray(Charsets.US_ASCII) + body)
    private fun leInts(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach(::putInt) }.array()
}
