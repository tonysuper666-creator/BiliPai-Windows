package com.bilipai.desktop.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** Opt-in one-actor native test. It proves driver acceptance + processed frames,
 * not Tensor utilization, HDR wire output, a Main UI or persistence. Uses local
 * H264 SDR media supplied by Root; no config/profile/account/network is read. */
object WindowsNvidiaVideoNativeFixture {
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val result = AtomicReference<Result<T>>()
        SwingUtilities.invokeAndWait { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2 && System.getProperty("os.name").startsWith("Windows"))
        val report = Path.of(args[0]).toRealPath()
        Files.list(report).use { check(it.findAny().isEmpty) }
        val media = Path.of(args[1]).toRealPath()
        check(Files.isRegularFile(media) && Files.size(media) > 0)
        val local = Path.of(requireNotNull(System.getenv("LOCALAPPDATA"))).toRealPath()
        check(local.startsWith(report.parent.toRealPath()) && local != report)
        val player = MpvPlayer()
        val enabled = MutableStateFlow(false)
        val started = MutableStateFlow(true)
        val pip = MutableStateFlow(false)
        val live = AtomicBoolean(true)
        val admission = Any()
        val enhancement = DesktopVideoEnhancementSession(player, enabled, started, pip,
            { value -> enabled.value = value; CompletableDeferred(Unit) })
        var window: JFrame? = null
        var failure: Throwable? = null
        val observations = mutableListOf<JsonObject>()
        val inputTimeline = mutableListOf<JsonObject>()
        var previousInputs: JsonObject? = null
        fun decisionInputs(): JsonObject {
            val native = player.nvidiaVideoState.value
            val output = player.videoOutput.value
            val playback = player.state.value
            val session = enhancement.state.value
            val decision = resolveDesktopNvidiaVideoDecision(output.inputWidth, output.inputHeight,
                output.displayWidth, output.displayHeight, output.maximumTextureDimension,
                output.gamma, output.dolbyVisionProfile, output.hdrDisplay.hdrEnabled,
                native.targetTransfer.takeIf { native.sourceVersion == player.currentSourceVersion },
                native.targetPrimaries.takeIf { native.sourceVersion == player.currentSourceVersion })
            val host = edt { window?.let { Triple(it.extendedState, it.isVisible, it.isDisplayable) } }
            return buildJsonObject {
                put("sourceVersion", player.currentSourceVersion); put("outputSourceVersion", output.sourceVersion)
                put("configurationVersion", native.configurationVersion); put("nativeSourceVersion", native.sourceVersion)
                put("enabled", enabled.value); put("hostStarted", started.value); put("pipActive", pip.value)
                put("windowExtendedState", host?.first); put("windowVisible", host?.second); put("windowDisplayable", host?.third)
                put("ready", playback.ready); put("loading", playback.loading); put("ended", playback.ended)
                put("audioOnly", playback.audioOnly); put("playbackErrorPresent", playback.error != null)
                put("videoCodec", playback.videoCodec); put("hardwareDecoder", playback.hardwareDecoder)
                put("hardwareDecodeEnabled", playback.hardwareDecodeEnabled); put("softwareDecodeRequested", playback.softwareDecodingRequested)
                put("firstVideoFrameReady", playback.firstVideoFrameReady)
                put("maximumTextureDimension", output.maximumTextureDimension); put("intermediateFormat", output.intermediateFormat)
                put("decodedWidth", output.inputWidth); put("decodedHeight", output.inputHeight); put("decodedTransfer", output.gamma)
                put("displayWidth", output.displayWidth); put("displayHeight", output.displayHeight)
                put("dolbyVisionProfile", output.dolbyVisionProfile); put("windowsHdrEnabled", output.hdrDisplay.hdrEnabled)
                put("targetTransfer", native.targetTransfer); put("targetPrimaries", native.targetPrimaries)
                put("gpuName", native.gpuName); put("gpuVendorId", native.gpuVendorId); put("gpuContext", native.currentGpuContext)
                put("sessionStatusText", session.statusText); put("sessionBypassReason", session.bypassReason.name)
                put("sessionSourceVersion", session.sourceVersion); put("sessionRequested", session.requested)
                put("sessionAvailable", session.available); put("sessionActive", session.active); put("sessionPending", session.pending)
                put("sessionError", session.error); put("nativeRequestedScale", native.requestedScale)
                put("nativeHdrRequested", native.hdrRequested); put("nativePending", native.pending); put("nativeError", native.error)
                put("decisionKind", decision.kind.name); put("decisionScale", decision.scale)
                put("decisionHdr", decision.hdr); put("decisionSourceIsHdr", decision.sourceIsHdr)
            }
        }
        fun observeInputs() {
            val inputs = decisionInputs()
            if (inputs != previousInputs && inputTimeline.size < 128) {
                inputTimeline += buildJsonObject {
                    put("sample", inputTimeline.size); put("clock", player.state.value.positionSeconds); put("inputs", inputs)
                }
                previousInputs = inputs
            }
        }
        fun await(description: String, checkError: Boolean = true, condition: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            while (System.nanoTime() < until) {
                observeInputs()
                check(player.state.value.error == null) { "Native local playback failed" }
                if (checkError) check(player.nvidiaVideoState.value.error == null) { player.nvidiaVideoState.value.error.orEmpty() }
                if (condition()) return
                Thread.sleep(50)
            }
            error("Timed out: $description")
        }
        fun quiet() {
            val state = player.state.value
            check(state.muted && state.volume < 0.1 && kotlin.math.abs(state.speed - 1.0) < 0.001)
        }
        fun receipt(name: String) {
            quiet()
            val state = player.nvidiaVideoState.value
            val output = player.videoOutput.value
            observations += buildJsonObject {
                put("case", name); put("decisionInputs", decisionInputs()); put("sourceVersion", player.currentSourceVersion)
                put("configurationVersion", state.configurationVersion)
                put("requestedScale", state.requestedScale); put("hdrRequested", state.hdrRequested)
                put("driverVsrAccepted", state.driverVsrAccepted); put("driverHdrAccepted", state.driverHdrAccepted)
                put("active", state.active); put("hdrConversionActive", state.hdrConversionActive)
                put("pending", state.pending); put("error", state.error)
                put("gpuName", state.gpuName); put("gpuVendorId", state.gpuVendorId)
                put("gpuContext", state.currentGpuContext)
                put("decodedWidth", output.inputWidth); put("decodedHeight", output.inputHeight)
                put("decodedTransfer", output.gamma); put("processedWidth", state.outputWidth); put("processedHeight", state.outputHeight)
                put("processedTransfer", state.outputTransfer); put("targetTransfer", state.targetTransfer); put("targetPrimaries", state.targetPrimaries)
                put("displayWidth", output.displayWidth); put("displayHeight", output.displayHeight)
                put("windowsHdrKnown", output.hdrDisplay.known); put("windowsHdrEnabled", output.hdrDisplay.hdrEnabled)
                put("clock", player.state.value.positionSeconds); put("firstFrame", player.state.value.firstVideoFrameReady)
                put("muted", player.state.value.muted); put("volume", player.state.value.volume)
            }
        }
        fun load(title: String): Long {
            val publication = DesktopLocalPlaybackPublication(live::get) { action -> synchronized(admission) {
                if (!live.get()) false else { action(); true }
            } }
            return player.loadVersioned(publication.ownedSource(PlaybackSource(media.toString(), referer = "", title = title), live::get))
        }
        try {
            player.setVolume(0.0); player.setMuted(true); player.setLoop(true)
            window = edt { JFrame("BiliPai · owned NVIDIA native validation").apply {
                defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
                contentPane.add(player.surface); setSize(1280, 800); isVisible = true
                addWindowStateListener { event -> started.value = event.newState and JFrame.ICONIFIED == 0 }
            } }
            await("native ready") { player.state.value.ready }
            val first = load("Owned local SDR video")
            await("baseline decoded frame") {
                val state = player.nvidiaVideoState.value
                player.state.value.firstVideoFrameReady && player.state.value.positionSeconds > 0.3 &&
                    state.gpuVendorId == 0x10de && state.currentGpuContext == "d3d11" && player.videoOutput.value.inputWidth > 0
            }
            check(!player.nvidiaVideoState.value.active)
            receipt("baseline-original-output")
            edt { window!!.extendedState = JFrame.MAXIMIZED_BOTH; window!!.toFront() }
            await("actual viewport larger than decoded video") {
                val output = player.videoOutput.value
                output.displayWidth > output.inputWidth || output.displayHeight > output.inputHeight
            }
            receipt("maximized-input-before-enable")
            enabled.value = true
            await("actual automatic NVIDIA VSR processing") {
                val state = player.nvidiaVideoState.value
                state.sourceVersion == first && state.active && state.driverVsrAccepted && state.requestedScale > 1.0 &&
                    state.outputWidth > state.inputWidth && state.outputHeight > state.inputHeight && enhancement.state.value.active
            }
            val firstToken = player.nvidiaVideoState.value.configurationVersion
            val firstSource = requireNotNull(player.currentSourceSnapshot())
            receipt("automatic-vsr-native-processed-frames")
            val clock = player.state.value.positionSeconds
            Thread.sleep(750)
            check(player.state.value.positionSeconds > clock + 0.3 && player.ownsSourceSnapshot(firstSource))
            quiet()
            runBlocking { player.captureScreenshot(report.resolve("processed-local-frame.png"), includeSubtitles = false) }
            // Change the real owned viewport. Policy must use decoded dimensions, not
            // its own post-filter dimensions, and preserve the same native source.
            edt { window!!.extendedState = JFrame.NORMAL; window!!.setSize(1000, 700) }
            await("small viewport original output") {
                val state = player.nvidiaVideoState.value
                state.sourceVersion == first && state.requestedScale == 1.0 &&
                    (state.active && state.hdrConversionActive || !state.active && !state.pending)
            }
            check(player.ownsSourceSnapshot(firstSource))
            receipt("same-source-owned-resize")
            edt { window!!.extendedState = JFrame.MAXIMIZED_BOTH }
            await("resized viewport VSR restored") { player.nvidiaVideoState.value.active && player.nvidiaVideoState.value.requestedScale > 1.0 }
            val second = load("Replacement local SDR video without BV label")
            check(second > first)
            check(player.setNvidiaVideoEnhancementIfSourceVersion(first, NvidiaVideoOptions(2.0)) == null)
            check(!player.clearNvidiaVideoEnhancementIfConfigurationVersion(firstToken))
            await("unlabelled replacement source automatic VSR") {
                val state = player.nvidiaVideoState.value
                state.sourceVersion == second && state.active && state.driverVsrAccepted && enhancement.state.value.active
            }
            receipt("unlabelled-replacement-source")
            enabled.value = false
            await("disabled owned filter original output") {
                val state = player.nvidiaVideoState.value
                state.requestedScale == 1.0 && !state.hdrRequested && !state.active && !state.pending &&
                    state.outputWidth == player.videoOutput.value.inputWidth && state.outputHeight == player.videoOutput.value.inputHeight
            }
            receipt("disabled-original-output-restored")
        } catch (caught: Throwable) {
            failure = caught
            receipt("failure-native-readback")
        } finally {
            live.set(false)
            enhancement.close(); player.close()
            edt { window?.dispose() }
            val result = buildJsonObject {
                put("passed", failure == null); put("scope", "ONE_ACTOR_NATIVE_PIPELINE_NOT_MAIN_OR_TENSOR_OR_HDR_WIRE")
                put("tensorExecutionVerified", false); put("hdrWireOutputVerified", false); put("profileOrAccountRead", false)
                put("failureClass", failure?.javaClass?.name); put("failureMessage", failure?.message)
                put("observations", JsonArray(observations))
                put("decisionInputTimeline", JsonArray(inputTimeline))
                put("decisionInputTimelineLimit", 128)
            }
            Files.writeString(report.resolve("result.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result))
        }
        failure?.let { throw it }
    }
}
