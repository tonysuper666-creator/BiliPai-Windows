package com.bilipai.desktop.player

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs

/** Opt-in real Windows output check using one owned actor and silent local PCM. */
object WindowsAudioOutputNativeFixture {
    private fun <T> edt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        val result = AtomicReference<Result<T>>()
        SwingUtilities.invokeAndWait { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(50) }
        error("Timed out: $description")
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1 && System.getProperty("os.name").startsWith("Windows"))
        val report = Path.of(args[0]).toRealPath()
        Files.list(report).use { check(it.findAny().isEmpty) }
        val data = Path.of(requireNotNull(System.getenv("LOCALAPPDATA"))).toRealPath()
        check(data.startsWith(report.parent.toRealPath()) && data != report)
        val rate = 48_000
        val bytes = rate * 60 * 4
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(rate); putInt(rate * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(bytes)
        }.array()
        val wav = report.resolve("owned-silence.wav")
        Files.newOutputStream(wav).use { output ->
            output.write(header)
            val zeros = ByteArray(64 * 1024)
            var remaining = bytes
            while (remaining > 0) { val count = minOf(remaining, zeros.size); output.write(zeros, 0, count); remaining -= count }
        }
        val actor = MpvPlayer()
        val owner = Any()
        val live = AtomicBoolean(true)
        val gate = Any()
        var frame: JFrame? = null
        var failure: Throwable? = null
        val cases = mutableListOf<JsonObject>()
        fun quiet() {
            val s = actor.state.value
            check(s.muted && abs(s.volume) < 0.1 && abs(s.speed - 1.0) < 0.001)
        }
        fun load(title: String) {
            val publication = DesktopLocalPlaybackPublication(live::get) { action -> synchronized(gate) {
                if (!live.get()) false else { action(); true }
            } }
            actor.load(publication.ownedSource(PlaybackSource(wav.toString(), referer = "", title = title), live::get))
        }
        fun observe(name: String, phase: DesktopWindowsAudioOutputPhase) {
            await(name) {
                check(live.get())
                val output = actor.windowsAudioOutput.value
                check(output.phase != DesktopWindowsAudioOutputPhase.ERROR && actor.state.value.error == null) { output.error ?: actor.state.value.error.orEmpty() }
                output.phase == phase && actor.state.value.positionSeconds > 0.15
            }
            val before = actor.state.value.positionSeconds
            Thread.sleep(650)
            quiet()
            val after = actor.state.value.positionSeconds
            check(after - before > 0.3)
            val state = actor.windowsAudioOutput.value
            check(state.sourceVersion == actor.currentSourceVersion && state.appliedRevision == state.requestedRevision)
            val diagnostic = requireNotNull(runBlocking { actor.captureNativeAudioDiagnostic() })
            check(diagnostic.sourceVersion == actor.currentSourceVersion)
            cases += buildJsonObject {
                put("case", name); put("passed", true); put("phase", state.phase.name)
                put("driver", state.activeDriver); put("configuredExclusive", state.configuredExclusive)
                put("configuredDevice", state.configuredDeviceId); put("outputSampleRate", state.outputPcm?.sampleRate)
                put("outputChannels", state.outputPcm?.channelCount); put("outputFormat", state.outputPcm?.format)
                put("sourceVersion", state.sourceVersion); put("clockDelta", after - before)
                put("muted", actor.state.value.muted); put("volume", actor.state.value.volume)
            }
        }
        try {
            actor.setVolume(0.0); actor.setMuted(true)
            actor.bindWindowsAudioOutputOwner(owner)
            frame = edt { JFrame("BiliPai · silent Windows output validation").apply {
                defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
                contentPane.add(actor.surface); setSize(400, 240); isVisible = true
            } }
            await("actual native session ready") { actor.state.value.ready }
            val devices = runBlocking { actor.queryWindowsAudioDevices() }
            check(devices.available && devices.devices.any { it.name.startsWith("wasapi/") })
            load("owned shared silence")
            observe("shared output", DesktopWindowsAudioOutputPhase.ACTIVE_SHARED)
            val version = actor.currentSourceVersion
            actor.requestWindowsAudioOutputPreferences(owner, DesktopWindowsAudioOutputPreferences(exclusive = true), live::get)
            check(actor.currentSourceVersion == version && actor.windowsAudioOutput.value.phase == DesktopWindowsAudioOutputPhase.PENDING_NEXT_PLAY)
            quiet()
            load("owned exclusive silence")
            observe("exclusive next owned load", DesktopWindowsAudioOutputPhase.ACTIVE_EXCLUSIVE)
            check(actor.windowsAudioOutput.value.activeDriver == "wasapi" && actor.windowsAudioOutput.value.outputPcm?.channelCount == 2)
            actor.requestWindowsAudioOutputPreferences(owner, DesktopWindowsAudioOutputPreferences(), live::get)
            check(actor.windowsAudioOutput.value.phase == DesktopWindowsAudioOutputPhase.PENDING_NEXT_PLAY)
            quiet()
            load("owned shared silence restored")
            observe("shared restored on next load", DesktopWindowsAudioOutputPhase.ACTIVE_SHARED)
        } catch (caught: Throwable) { failure = caught }
        finally {
            synchronized(gate) { live.set(false) }
            try { actor.retireWindowsAudioOutputOwner(owner); actor.close() }
            catch (close: Throwable) { if (failure == null) failure = close else failure!!.addSuppressed(close) }
            finally { edt { frame?.dispose() } }
        }
        val closed = edt { frame?.isDisplayable != true }
        val passed = failure == null && closed && cases.size == 3
        Files.writeString(report.resolve("result.json"), buildJsonObject {
            put("passed", passed); put("cases", JsonArray(cases)); put("ownedWindowClosed", closed)
            put("realAccountRead", false); put("systemDefaultChanged", false); put("actualMainUiTest", false)
            put("phantomOpticalListeningVerified", false); put("bitPerfectVerified", false)
            put("failureType", failure?.javaClass?.name); put("failure", failure?.message)
        }.toString())
        check(passed) { "Windows output native check failed: ${failure?.message}" }
    }
}
