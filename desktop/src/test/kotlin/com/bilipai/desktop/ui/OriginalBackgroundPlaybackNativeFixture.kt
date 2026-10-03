package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences as OriginalPrefs
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.awt.Color
import java.awt.Font
import java.awt.Frame
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.sin

/** Opt-in real private local MPV/Window/controller. Does not call Main/typed Root. */
object OriginalBackgroundPlaybackNativeFixture {
    private data class NativeClock(val before: Double, val after: Double, val duration: Double, val playing: Boolean) {
        val delta: Double get() = after - before
    }
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val outcome = AtomicReference<Result<T>?>()
        SwingUtilities.invokeAndWait { outcome.set(runCatching(block)) }
        return requireNotNull(outcome.get()).getOrThrow()
    }
    private fun await(description: String, predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < end) { if (predicate()) return; Thread.sleep(40) }
        error("Background native fixture timed out: $description")
    }
    private class PauseReadRaceGate(private val beforeCapture: Boolean) : DesktopNativePauseReadbackObserver, AutoCloseable {
        private val claimed = AtomicBoolean(false)
        private val readBarrier = CountDownLatch(1)
        private val continueRead = CountDownLatch(1)
        private val published = CountDownLatch(1)
        private val continueWorker = CountDownLatch(1)
        val observation = AtomicReference<DesktopNativePauseReadObservation?>()
        val accepted = AtomicReference<Boolean?>()
        private val nativeWorker = AtomicReference<Thread?>()
        val sameWorker = AtomicBoolean(false)
        private fun bounded(latch: CountDownLatch, description: String) {
            check(latch.await(15, TimeUnit.SECONDS)) { "Actual native race barrier timed out: $description" }
        }
        override fun beforeRead() {
            if (beforeCapture && claimed.compareAndSet(false, true)) {
                nativeWorker.set(Thread.currentThread()); readBarrier.countDown()
                bounded(continueRead, "queued user Pause before capture")
            }
        }
        override fun afterRead(value: DesktopNativePauseReadObservation) {
            if (beforeCapture) {
                if (claimed.get() && observation.compareAndSet(null, value)) check(Thread.currentThread() === nativeWorker.get())
            } else if (value.nativePaused == false && claimed.compareAndSet(false, true)) {
                nativeWorker.set(Thread.currentThread()); observation.set(value); readBarrier.countDown()
                bounded(continueRead, "user Pause after actual native pause=false read")
            }
        }
        override fun afterPublish(value: DesktopNativePauseReadObservation, pauseFieldsPublished: Boolean) {
            if (value !== observation.get()) return
            check(Thread.currentThread() === nativeWorker.get())
            check(Thread.currentThread().name == "BiliPai-native-player")
            sameWorker.set(true); accepted.set(pauseFieldsPublished); published.countDown()
            bounded(continueWorker, "check optimistic intent and actual hidden policy before native command")
        }
        fun awaitReadBarrier() = bounded(readBarrier, "native read barrier")
        fun releaseRead() = continueRead.countDown()
        fun awaitPublished() = bounded(published, "actual stale native readback final publication")
        fun releaseWorker() = continueWorker.countDown()
        override fun close() { continueRead.countDown(); continueWorker.countDown() }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1 && !SwingUtilities.isEventDispatchThread())
        val report = Path.of(args[0]).toRealPath(); Files.list(report).use { check(it.findAny().isEmpty) }
        val video = report.resolve("local-native-video.avi").toFile(); val audio = report.resolve("local-native-audio.wav").toFile()
        createVideo(video); createAudio(audio)
        val player = MpvPlayer(); player.setVolume(0.0); player.setMuted(true)
        val live = AtomicBoolean(true); val sourceLive = AtomicBoolean(true); val sourceGate = Any()
        val publication = DesktopLocalPlaybackPublication(sourceLive::get) { action -> synchronized(sourceGate) {
            if (!sourceLive.get()) false else { action(); true }
        } }
        fun localSource(title: String, paused: Boolean = false) = publication.ownedSource(
            PlaybackSource(video.absolutePath, audio.absolutePath, referer = "", title = title, startPaused = paused), sourceLive::get)
        val window = edt { JFrame("BiliPai · muted original background native fixture").apply {
            defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
            contentPane.add(player.surface); setSize(720, 440); setLocationRelativeTo(null); isVisible = true
        } }
        val pip = PictureInPictureController(player, onRestore = {
            window.contentPane.add(player.surface); window.validate(); window.repaint()
        })
        val controller = edt { DesktopOriginalBackgroundPlaybackController(player) { live.get() && window.isDisplayable } }
        val store = DesktopPluginStore(report.resolve("private-settings")); val context = DesktopOriginalPlayerSettingsContext(
            DesktopPluginContext(store), live::get, commitIfCurrent = { action -> synchronized(sourceGate) {
                if (!live.get()) false else { action(); true }
            } })
        val currentSettings = AtomicReference<DesktopOriginalBackgroundPlaybackSettings?>()
        fun readSettings() = runBlocking { DesktopOriginalBackgroundPlaybackSettings(
            OriginalPrefs.getBackgroundPlaybackEnabled(context).first(), OriginalPrefs.getMiniPlayerMode(context).first(),
            OriginalPrefs.getStopPlaybackOnExit(context).first(), OriginalPrefs.getAudioNowPlayingBarEnabled(context).first()) }
        fun refresh() {
            check(SwingUtilities.isEventDispatchThread())
            val settings = currentSettings.get() ?: return
            controller.update(hidden = window.extendedState and Frame.ICONIFIED != 0 || !window.isShowing,
                settings = settings, isMiniMode = pip.active.value, isPip = pip.active.value,
                isInAudioMode = player.state.value.audioOnly,
                originalAudioSessionActive = com.android.purebilibili.feature.audio.player.AudioNowPlayingSession.active.value)
        }
        fun background(enabled: Boolean) {
            runBlocking { DesktopOriginalPlaybackPreferenceOperation.run(context, live::get) {
                OriginalPrefs.setBackgroundPlaybackEnabled(context, enabled)
            } }
            currentSettings.set(readSettings()); edt { refresh() }
        }
        fun hide() {
            edt { window.extendedState = window.extendedState or Frame.ICONIFIED }
            await("actual owned native Window ICONIFIED") { edt { window.extendedState and Frame.ICONIFIED != 0 } }
            edt { refresh() }
        }
        fun show() {
            edt { window.extendedState = window.extendedState and Frame.ICONIFIED.inv(); window.toFront() }
            await("actual same native Window restored") { edt { window.extendedState and Frame.ICONIFIED == 0 && window.isShowing } }
            edt { refresh() }
        }
        fun readyPlaying() = await("actual ready native clock without error") {
            val s = player.state.value
            check(s.error == null && s.operationError == null)
            s.ready && !s.loading && s.nativePaused == false && s.durationSeconds > 9 && s.positionSeconds > 0.1 &&
                s.audioCodec != null && (s.audioOnly || s.videoCodec != null) && s.muted && abs(s.volume) < 0.1
        }
        fun paused() = await("actual native pause readback") {
            val s = player.state.value
            check(s.error == null && s.operationError == null)
            s.nativePaused == true && !s.loading && s.muted && abs(s.volume) < 0.1
        }
        fun clock(playing: Boolean): NativeClock {
            val before = player.state.value.positionSeconds; Thread.sleep(550)
            val state = player.state.value; val after = state.positionSeconds
            check(state.error == null && state.operationError == null && state.muted && abs(state.volume) < 0.1)
            val delta = after - before
            if (playing) check(delta > 0.2 || (before > state.durationSeconds - 0.8 && after < 0.8)) { "Actual clock did not progress: $before -> $after" }
            else check(abs(delta) < 0.12) { "Paused clock progressed: $before -> $after" }
            return NativeClock(before, after, state.durationSeconds, playing)
        }
        val rows = mutableListOf<JsonObject>()
        fun record(id: String, clock: NativeClock, extra: Map<String, JsonElement> = emptyMap()) {
            val state = player.state.value
            val settings = requireNotNull(currentSettings.get())
            rows.add(buildJsonObject {
                put("id", id); put("sameWindowIdentity", System.identityHashCode(window)); put("sourceVersion", player.currentSourceVersion)
                put("actualIconified", edt { window.extendedState and Frame.ICONIFIED != 0 })
                put("nativePaused", state.nativePaused?.let(::JsonPrimitive) ?: JsonNull); put("positionSeconds", state.positionSeconds)
                put("nativeClockDelta", clock.delta); put("clockBefore", clock.before); put("clockAfter", clock.after)
                put("clockDuration", clock.duration); put("clockExpectedPlaying", clock.playing)
                put("muted", state.muted); put("volume", state.volume); put("nativeVersion", state.nativeVersion)
                put("videoCodec", state.videoCodec); put("audioCodec", state.audioCodec)
                put("policyBackgroundEnabled", settings.backgroundPlaybackEnabled); put("policyMiniMode", settings.mode.name)
                put("policyStopOnExit", settings.stopPlaybackOnExit); put("policyAudioBarEnabled", settings.audioNowPlayingBarEnabled)
                put("actualOriginalAudioSessionActive", com.android.purebilibili.feature.audio.player.AudioNowPlayingSession.active.value)
                put("actualPipOwnerActive", pip.active.value); put("actualAudioOnlyIntent", state.audioOnly)
                put("nativeVideoTrackSelected", state.tracks.any { it.type == "video" && it.selected })
                put("sourceOwnerCurrent", sourceLive.get()); put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
                put("operationError", state.operationError?.let(::JsonPrimitive) ?: JsonNull)
                extra.forEach { (key, value) -> put(key, value) }
            })
        }
        fun failureDiagnostic(caught: Throwable): JsonObject {
            val before = player.state.value
            val beforeVersion = player.currentSourceVersion
            val audioRead = runCatching { runBlocking { player.captureNativeAudioDiagnostic() } }
            val native = audioRead.getOrNull()
            val after = player.state.value
            fun safeState(state: PlayerState) = buildJsonObject {
                put("ready", state.ready); put("loading", state.loading); put("ended", state.ended)
                put("pausedIntent", state.paused); put("nativePaused", state.nativePaused?.let(::JsonPrimitive) ?: JsonNull)
                put("positionSeconds", state.positionSeconds); put("durationSeconds", state.durationSeconds)
                put("muted", state.muted); put("volume", state.volume); put("speed", state.speed)
                put("loopIntent", state.looping); put("audioOnlyIntent", state.audioOnly)
                put("pausedForCache", state.pausedForCache)
                put("bufferedForwardSeconds", state.bufferedForwardSeconds?.let(::JsonPrimitive) ?: JsonNull)
                put("firstVideoFrameReady", state.firstVideoFrameReady); put("nativeVersion", state.nativeVersion)
                put("audioCodec", state.audioCodec); put("videoCodec", state.videoCodec)
                put("videoWidth", state.videoWidth); put("videoHeight", state.videoHeight)
                put("error", state.error); put("operationError", state.operationError)
                put("failure", state.failure?.let { failure -> buildJsonObject {
                    put("kind", failure.kind.name); put("nativeCode", failure.nativeCode?.let(::JsonPrimitive) ?: JsonNull)
                    put("safeMessage", failure.safeMessage); put("sourceVersion", failure.sourceVersion)
                    put("attemptId", failure.attemptId)
                    put("safeNativeLogs", JsonArray(failure.diagnostics.map(::JsonPrimitive)))
                } } ?: JsonNull)
                put("tracks", JsonArray(state.tracks.map { track -> buildJsonObject {
                    put("id", track.id); put("type", track.type); put("selected", track.selected); put("external", track.external)
                } }))
            }
            return buildJsonObject {
                put("schema", 1); put("failureOnly", true); put("acceptanceProof", false)
                put("capturedBeforePlayerClose", true); put("originalFailureClass", caught.javaClass.name)
                put("completedOriginalCaseCount", rows.size)
                put("sourceVersionBeforeRead", beforeVersion); put("sourceVersionAfterRead", player.currentSourceVersion)
                put("sourceOwnerCurrent", sourceLive.get()); put("fixtureOwnerCurrent", live.get())
                put("actualBeforeState", safeState(before)); put("actualAfterState", safeState(after))
                put("actualOwnedWindow", edt { buildJsonObject {
                    put("identity", System.identityHashCode(window)); put("displayable", window.isDisplayable)
                    put("showing", window.isShowing); put("visible", window.isVisible)
                    put("iconified", window.extendedState and Frame.ICONIFIED != 0)
                    put("actualSurfaceParentSameOwner", SwingUtilities.getWindowAncestor(player.surface) === window)
                    put("clientWidth", window.contentPane.width); put("clientHeight", window.contentPane.height)
                } })
                put("nativeActorReadCompleted", native != null)
                put("nativeActorReadFailureClass", audioRead.exceptionOrNull()?.javaClass?.name)
                put("actualNativeAudio", native?.let { snapshot -> buildJsonObject {
                    put("sourceVersion", snapshot.sourceVersion); put("playbackRevision", snapshot.playbackRevision)
                    put("activeSourceVersion", snapshot.activeSourceVersion); put("activePlaybackRevision", snapshot.activePlaybackRevision)
                    put("pauseIntentSerial", snapshot.pauseIntentSerial); put("pendingPauseIntent", snapshot.pendingPauseIntent)
                    put("sessionIdentity", snapshot.sessionIdentity); put("workerThreadName", snapshot.workerThreadName)
                    put("fileLoaded", snapshot.fileLoaded)
                    put("properties", buildJsonObject { snapshot.properties.forEach { (name, read) ->
                        put(name, buildJsonObject { put("nativeCode", read.nativeCode); put("value", read.value) })
                    } })
                    put("safeNativeLogs", JsonArray(snapshot.safeNativeLogs.map(::JsonPrimitive)))
                } } ?: JsonNull)
            }
        }
        val listener = object : WindowAdapter() {
            override fun windowStateChanged(event: WindowEvent) { refresh() }
            override fun windowClosing(event: WindowEvent) { live.set(false) }
        }
        var success = false; var failure: Throwable? = null
        try {
            edt { window.addWindowStateListener(listener); window.addWindowListener(listener) }
            await("same actual native initialized muted volume0") { player.state.value.ready && player.state.value.muted && abs(player.state.value.volume) < 0.1 }
            player.load(localSource("Original background policy local source")); player.setLoop(true); readyPlaying()
            background(false); hide(); paused(); record("disabled-minimize-pauses-native-clock", clock(false))
            show(); readyPlaying(); record("same-source-restore-resumes-prior-playing", clock(true))
            hide(); paused(); player.setPaused(true) // explicit user Pause supersedes automatic intent
            paused(); show(); paused(); record("explicit-user-pause-is-never-auto-resumed", clock(false))
            player.setPaused(false); readyPlaying(); background(true); hide(); readyPlaying()
            record("enabled-minimize-keeps-original-background-clock", clock(true)); show()
            background(false); player.setAudioOnly(true)
            await("real native video track disabled for audio mode exemption") {
                player.state.value.audioOnly && player.state.value.tracks.none { it.type == "video" && it.selected }
            }
            hide(); readyPlaying(); record("actual-native-audio-only-exemption-keeps-clock", clock(true)); show()
            player.setAudioOnly(false); await("real native video track restored") { player.state.value.tracks.any { it.type == "video" && it.selected } }
            edt { pip.open(window, "BiliPai · actual existing PiP owner") }
            await("actual existing PiP owner mounted") { pip.active.value && player.surface.parent !== window.contentPane && player.state.value.ready && !player.state.value.loading }
            hide(); readyPlaying(); record("actual-existing-pip-owner-exemption-keeps-clock", clock(true))
            show(); edt { pip.restore(); refresh() }; readyPlaying()
            for ((beforeCapture, raceId) in listOf(
                false to "user-pause-before-hidden-rejects-old-native-read",
                true to "queued-user-pause-before-poll-capture-is-never-auto-resumed")) {
                player.setPaused(false); readyPlaying(); edt { refresh() }
                val sameSource = requireNotNull(player.currentSourceSnapshot())
                val gate = PauseReadRaceGate(beforeCapture)
                check(player.pauseReadbackObserver == null)
                player.pauseReadbackObserver = gate
                try {
                    gate.awaitReadBarrier() // real native actor; no sleep selects the race
                    player.setPaused(true) // real original public user command, queued on that held native worker
                    check(player.state.value.paused && player.state.value.nativePaused == null)
                    if (beforeCapture) player.invalidateBackgroundPauseIntents() // same production Dispose leaf must not revoke a queued user command
                    gate.releaseRead(); gate.awaitPublished()
                    val read = requireNotNull(gate.observation.get())
                    check(read.nativePaused == false) { "Race requires an actual old playing native pause read" }
                    check(read.pendingIntentAtCapture == beforeCapture)
                    check(gate.accepted.get() == false && gate.sameWorker.get())
                    check(player.ownsSourceSnapshot(sameSource))
                    check(player.state.value.paused && player.state.value.nativePaused == null) {
                        "Old native read overwrote explicit user Pause before hidden policy"
                    }
                    hide() // actual ICONIFIED and original policy run before the queued native user command
                    check(player.state.value.paused && player.state.value.nativePaused == null)
                    gate.releaseWorker(); player.pauseReadbackObserver = null
                    paused(); show(); paused()
                    check(player.ownsSourceSnapshot(sameSource))
                    record(raceId, clock(false), mapOf(
                        "requestedPauseBeforeCapture" to JsonPrimitive(beforeCapture),
                        "actualOldNativeReadPaused" to JsonPrimitive(false),
                        "pendingIntentAtCapture" to JsonPrimitive(read.pendingIntentAtCapture),
                        "automaticIntentInvalidationWhileUserPausePending" to JsonPrimitive(beforeCapture),
                        "actualStalePauseReadbackPublished" to JsonPrimitive(false),
                        "optimisticUserPauseRetained" to JsonPrimitive(true),
                        "optimisticNativePauseReadbackUnset" to JsonPrimitive(true),
                        "sameActualNativeWorker" to JsonPrimitive(gate.sameWorker.get()),
                        "sameSourceAcrossUserPauseHiddenRestore" to JsonPrimitive(player.ownsSourceSnapshot(sameSource)),
                        "actualReadSourceVersion" to JsonPrimitive(read.sourceVersion),
                        "actualReadRevision" to JsonPrimitive(read.revision),
                        "actualReadIntentSerial" to JsonPrimitive(read.intentSerial),
                        "raceOrderedByLatches" to JsonPrimitive(true)))
                } finally {
                    player.pauseReadbackObserver = null; gate.close()
                }
            }
            player.setPaused(false); readyPlaying()
            val old = requireNotNull(player.currentSourceSnapshot())
            // The fixture's own short source admission orders queued old pause before
            // replacement without waiting inside a gate. Worker final source guard must reject it.
            edt { synchronized(sourceGate) {
                check(old.source.nativePublication!!.admit { checkNotNull(player.pauseForBackground(old)) })
                player.load(localSource("Replacement source rejects queued old pause"))
            } }
            readyPlaying(); check(player.currentSourceVersion > old.sourceVersion)
            record("replacement-rejects-queued-obsolete-owned-pause", clock(true))
            val retired = requireNotNull(player.currentSourceSnapshot())
            edt { synchronized(sourceGate) {
                check(retired.source.nativePublication!!.admit { checkNotNull(player.pauseForBackground(retired)) })
                sourceLive.set(false)
            } }
            readyPlaying(); record("retired-publication-rejects-queued-owned-pause", clock(true))
            success = true
        } catch (caught: Throwable) {
            failure = caught; caught.printStackTrace()
            runCatching { Files.writeString(report.resolve("native-failure-diagnostic.json"),
                failureDiagnostic(caught).toString() + "\n", CREATE_NEW, WRITE) }
                .onFailure { diagnosticFailure -> System.err.println("Failure diagnostic could not be written: ${diagnosticFailure.javaClass.name}") }
        }
        finally {
            edt { controller.close(); window.removeWindowStateListener(listener); window.removeWindowListener(listener); pip.close() }
            live.set(false); sourceLive.set(false); player.close(); edt { window.dispose() }
            val receipt = buildJsonObject {
                put("schema", 1); put("passed", success); put("scope", "ACTUAL_OWNED_LOCAL_WINDOW_MPV_ORIGINAL_BACKGROUND_CONTROLLER")
                put("actualMainInvoked", false); put("productionComposeEffectsInvoked", false); put("typedRootAudioModeExercised", false)
                put("originalAudioSessionManuallySeeded", false); put("realAccountUsed", false); put("networkSourceUsed", false)
                put("mutedThroughout", rows.all { it["muted"] == JsonPrimitive(true) }); put("initialVolumeZero", true)
                put("ownedWindowDisposed", !window.isDisplayable); put("sourceOwnerRetired", !sourceLive.get())
                put("observations", JsonArray(rows)); put("error", failure?.let { JsonPrimitive(it.javaClass.name + ": " + it.message) } ?: JsonNull)
            }
            Files.writeString(report.resolve("native-observations.json"), receipt.toString(), CREATE_NEW, WRITE)
        }
        if (!success) kotlin.system.exitProcess(92)
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
