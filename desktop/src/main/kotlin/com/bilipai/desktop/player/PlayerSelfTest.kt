package com.bilipai.desktop.player

import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

/** An opt-in native integration smoke test; no internet, account, or ffmpeg needed. */
object PlayerSelfTest {
    /** Returns true only after separate-track playback, controls, errors and EOF pass. */
    fun run(outputDirectory: File, useNullAudioOutput: Boolean = false): Boolean {
        require(!SwingUtilities.isEventDispatchThread()) { "Run the player smoke test off the UI thread." }
        outputDirectory.mkdirs()
        val video = File(outputDirectory, "native-smoke-video.avi")
        val audio = File(outputDirectory, "native-smoke-audio.wav")
        createVideo(video)
        createAudio(audio)
        // The explicit CI mode retains decoding and a timed audio clock on a
        // Windows runner without a sound device. Local runs use real audio output.
        val player = MpvPlayer(useNullAudioOutput)
        var frame: JFrame? = null
        var mediaSession: WindowsMediaSession? = null
        var pip: PictureInPictureController? = null
        val checks = linkedMapOf<String, String>()
        val visibilityTrace = NativeVisibilityTrace()
        var passed = false
        try {
            check(!GraphicsEnvironment.isHeadless()) { "Native screen rendering requires an interactive desktop." }
            player.setVideoPanscan(1.0) // Must survive mounting, rather than relying on a setter against an existing session.
            SwingUtilities.invokeAndWait {
                frame = JFrame("BiliPai · Windows native playback smoke test")
                requireNotNull(frame).apply {
                    defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
                    contentPane.add(player.surface)
                    setSize(720, 440)
                    setLocationRelativeTo(null)
                    check(isAlwaysOnTopSupported) { "The desktop cannot keep the native test window visible." }
                    isAlwaysOnTop = true
                    isVisible = true
                    toFront()
                    requestFocus()
                }
            }
            waitFor(player, "native initialization with retained profile-skin zoom") { it.ready && it.activeVideoPanscan == 1.0 }
            player.setVolume(20.0)
            player.load(PlaybackSource(video.absolutePath, audio.absolutePath, referer = "", title = "Separate video + audio test"))
            waitFor(player, "separate audio/video playback") {
                !it.loading && it.firstVideoFrameReady && it.positionSeconds > 0.5 && !it.videoCodec.isNullOrBlank() && !it.audioCodec.isNullOrBlank()
            }
            checks["videoCodec"] = player.state.value.videoCodec.orEmpty()
            checks["audioCodec"] = player.state.value.audioCodec.orEmpty()
            checks["nativeVersion"] = player.state.value.nativeVersion.orEmpty()
            checks["audioOutputMode"] = if (useNullAudioOutput) "CI timed null output" else "Windows audio device"
            waitFor(player, "audio/video clock synchronization") { it.avSyncSeconds != null && abs(it.avSyncSeconds) < 0.2 }
            checks["avSyncSeconds"] = player.state.value.avSyncSeconds.toString()
            checks["nativeWindowFocusedAtPixelCapture"] = waitForRenderedVideo(player, requireNotNull(frame),
                File(outputDirectory, "native-player-smoke.png"), visibilityTrace).toString()
            checks["nativeVideoRendering"] = "passed"
            player.setPaused(true)
            waitFor(player, "actual native pause and synchronized position readback") { it.paused && it.nativePaused == true }
            val pausedPosition = player.state.value.positionSeconds
            Thread.sleep(500)
            check(abs(player.state.value.positionSeconds - pausedPosition) < 0.15) { "Playback advanced while paused." }
            checks["pause"] = "passed"
            val captured = File(outputDirectory, "native-video-screenshot.png")
            captured.delete()
            runBlocking { player.captureScreenshot(captured.toPath(), includeSubtitles = false) }
            val capturedImage = requireNotNull(ImageIO.read(captured)) { "Native screenshot is not an image." }
            check(capturedImage.width == WIDTH && capturedImage.height == HEIGHT) { "Screenshot did not use the decoded video frame size." }
            checkRenderedVideo(capturedImage)
            checks["nativeFrameScreenshot"] = "passed"
            mediaSession = WindowsMediaSession(requireNotNull(frame), {}, {})
            requireNotNull(mediaSession).update(WindowsMediaSnapshot("BiliPai 原生媒体测试", "本地音轨", "native-smoke", player.state.value,
                isAudio = true, hasPrevious = true, hasNext = true))
            val smtcDeadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < smtcDeadline && requireNotNull(mediaSession).status.value.publishedTitle.isEmpty() &&
                requireNotNull(mediaSession).status.value.error == null) Thread.sleep(50)
            val smtc = requireNotNull(mediaSession).status.value
            check(smtc.available && smtc.error == null && smtc.publishedTitle == "BiliPai 原生媒体测试" && smtc.publishedPlaybackStatus == 4 &&
                abs(smtc.publishedPositionSeconds - pausedPosition) < 0.3) { "Native SMTC metadata/status/timeline failed: $smtc" }
            checks["windowsMediaSessionNativeReadback"] = "passed"
            check(runCatching { runBlocking { player.captureScreenshot(captured.toPath()) } }.isFailure) { "Screenshot overwrote an existing destination." }
            check(player.state.value.error == null) { "A screenshot destination error damaged playback." }
            checks["screenshotErrorIsolation"] = "passed"

            player.setMuted(true)
            Thread.sleep(500) // Allow native-property polling to replace the optimistic UI value.
            check(player.state.value.muted) { "Native mute did not remain enabled." }
            player.setAudioOnly(true)
            player.setPaused(false)
            val audioOnlyStart = player.state.value.positionSeconds
            waitFor(player, "audio-only mode") {
                it.audioOnly && it.videoCodec == null && it.audioCodec != null && it.positionSeconds > audioOnlyStart + 0.3
            }
            player.setPaused(true)
            player.setAudioOnly(false)
            waitFor(player, "restore video from audio-only mode") { !it.audioOnly && it.videoCodec != null && it.audioCodec != null }
            player.setMuted(false)
            Thread.sleep(500)
            check(!player.state.value.muted) { "Native unmute did not remain disabled." }
            checks["muteAndAudioOnly"] = "passed"

            val seekId = requireNotNull(player.seekToTracked(4.0)) { "Native seek was not submitted" }
            waitFor(player, "native seek completion event") { it.seekCompletedId == seekId && abs((it.seekCompletedPositionSeconds ?: 0.0) - 4.0) < 0.3 }
            checks["seek"] = "passed"
            checks["seekCompletionEvent"] = "passed"
            val subtitle = File(outputDirectory, "native-smoke-subtitle.srt")
            subtitle.writeText("1\n00:00:00,000 --> 00:00:09,800\nBiliPai subtitle smoke\n", Charsets.UTF_8)
            player.addSubtitle(subtitle.toPath(), "Native subtitle smoke", "en")
            waitFor(player, "external subtitle selection") {
                it.tracks.any { track -> track.type == "sub" && track.selected && track.external } &&
                    it.subtitleText?.contains("BiliPai subtitle smoke") == true
            }
            val subtitleId = player.state.value.tracks.first { it.type == "sub" && it.selected }.id
            player.setSubtitlesVisible(false)
            waitFor(player, "hide subtitles") { !it.subtitlesVisible && it.subtitleText == null }
            player.setSubtitlesVisible(true)
            waitFor(player, "show subtitles") { it.subtitlesVisible && it.subtitleText?.contains("BiliPai subtitle smoke") == true }
            player.selectSubtitleTrack(null)
            waitFor(player, "disable subtitle track") { it.tracks.none { track -> track.type == "sub" && track.selected } }
            player.selectSubtitleTrack(subtitleId)
            waitFor(player, "restore subtitle track") { it.tracks.any { track -> track.type == "sub" && track.selected } }
            val secondarySubtitle = File(outputDirectory, "native-smoke-secondary-subtitle.srt")
            secondarySubtitle.writeText("1\n00:00:00,000 --> 00:00:09,800\nSecondary subtitle smoke\n", Charsets.UTF_8)
            player.addSubtitle(secondarySubtitle.toPath(), "Secondary subtitle smoke", "en", select = false)
            waitFor(player, "load secondary subtitle track") { it.tracks.count { track -> track.type == "sub" } == 2 }
            val secondaryId = player.state.value.tracks.first { it.type == "sub" && it.id != subtitleId }.id
            player.selectSecondarySubtitleTrack(secondaryId)
            waitFor(player, "bilingual subtitle selection") {
                it.subtitleText?.contains("BiliPai subtitle smoke") == true &&
                    it.secondarySubtitleText?.contains("Secondary subtitle smoke") == true &&
                    it.tracks.count { track -> track.type == "sub" && track.selected } == 2
            }
            val reattachPosition = player.state.value.positionSeconds
            SwingUtilities.invokeAndWait {
                requireNotNull(frame).contentPane.apply {
                    remove(player.surface)
                    validate()
                    add(player.surface)
                    validate()
                }
            }
            waitFor(player, "surface reattachment preserves pause position and bilingual subtitles") {
                it.ready && !it.loading && it.paused && abs(it.positionSeconds - reattachPosition) < 0.3 &&
                    it.videoCodec != null && it.audioCodec != null &&
                    it.subtitleText?.contains("BiliPai subtitle smoke") == true &&
                    it.secondarySubtitleText?.contains("Secondary subtitle smoke") == true && it.activeVideoPanscan == 1.0
            }
            checks["surfaceReattachmentAndSubtitleRetention"] = "passed"
            checks["retainedVideoPanscanAfterMountAndReattachment"] = "passed"
            player.setVideoPanscan(0.0)
            waitFor(player, "restore normal native video fit") { it.activeVideoPanscan == 0.0 }
            val recoveryVersion = player.currentSourceVersion
            check(player.recoverSource(recoveryVersion, positionSeconds = reattachPosition, paused = true, forceSoftwareDecoding = true)) {
                "Same-source decoder fallback was rejected."
            }
            waitFor(player, "software-decoding recovery preserves source ownership position and bilingual subtitles") {
                it.ready && !it.loading && it.paused && it.softwareDecodingRequested && it.hardwareDecoder == null &&
                    player.currentSourceVersion == recoveryVersion && abs(it.positionSeconds - reattachPosition) < 0.3 &&
                    it.videoCodec != null && it.audioCodec != null &&
                    it.subtitleText?.contains("BiliPai subtitle smoke") == true &&
                    it.secondarySubtitleText?.contains("Secondary subtitle smoke") == true
            }
            checks["softwareRecoveryOwnershipPositionAndSubtitleRetention"] = "passed"
            var pipRestored = false
            pip = PictureInPictureController(player, onRestore = {
                requireNotNull(frame).contentPane.add(player.surface)
                requireNotNull(frame).validate()
                pipRestored = true
            }, onDetachSurface = {
                requireNotNull(frame).contentPane.remove(player.surface)
                requireNotNull(frame).validate()
            })
            SwingUtilities.invokeAndWait { requireNotNull(pip).open(requireNotNull(frame), "Native PiP smoke") }
            waitFor(player, "floating native player preserves position/subtitles") {
                requireNotNull(pip).active.value && player.surface.isShowing && SwingUtilities.getWindowAncestor(player.surface) !== frame &&
                    it.ready && !it.loading && it.paused && abs(it.positionSeconds - reattachPosition) < 0.3 &&
                    it.subtitleText?.contains("BiliPai subtitle smoke") == true && it.secondarySubtitleText?.contains("Secondary subtitle smoke") == true
            }
            SwingUtilities.invokeAndWait { requireNotNull(pip).restore() }
            waitFor(player, "restore floating native player") {
                pipRestored && !requireNotNull(pip).active.value && it.ready && !it.loading && it.paused &&
                    it.videoCodec != null && abs(it.positionSeconds - reattachPosition) < 0.3
            }
            checks["nativeFloatingWindowAndRestore"] = "passed"
            player.selectSecondarySubtitleTrack(null)
            waitFor(player, "disable secondary subtitle") { it.secondarySubtitleText == null && it.tracks.count { track -> track.type == "sub" && track.selected } == 1 }
            checks["externalSubtitlesAndTrackSelection"] = "passed"
            checks["bilingualSubtitles"] = "passed"
            player.setVolume(37.0)
            player.setSpeed(1.5)
            waitFor(player, "volume and speed") { abs(it.volume - 37.0) < 0.1 && abs(it.speed - 1.5) < 0.01 }
            player.setPaused(false)
            val startPosition = player.state.value.positionSeconds
            waitFor(player, "resume") { !it.paused && it.positionSeconds > startPosition + 0.4 }
            checks["volume"] = player.state.value.volume.toString()
            checks["speed"] = player.state.value.speed.toString()
            player.seekTo(9.0)
            player.setLoop(true)
            waitFor(player, "repeat current file") { !it.ended && it.positionSeconds < 2.0 && !it.paused }
            checks["singleFileLoop"] = "passed"
            player.setLoop(false)
            // A loop can publish position zero before its native seek has
            // restarted playback. Establish the independent EOF fixture only
            // after a real seek acknowledgement and a moving playback clock.
            val terminalSetupSeek = requireNotNull(player.seekToTracked(2.0))
            waitFor(player, "settled playback before terminal floating-window check") {
                it.seekCompletedId == terminalSetupSeek &&
                    abs((it.seekCompletedPositionSeconds ?: 0.0) - 2.0) < 0.3 &&
                    it.positionSeconds > 2.2 && !it.paused && !it.ended
            }
            player.setPaused(true)
            waitFor(player, "pause before terminal floating-window check") { it.nativePaused == true }
            val terminalPresentationSource = requireNotNull(player.currentSourceSnapshot())
            pipRestored = false
            SwingUtilities.invokeAndWait { requireNotNull(pip).open(requireNotNull(frame), "Native PiP EOF smoke") }
            waitFor(player, "same paused source in floating window before EOF") {
                requireNotNull(pip).active.value && player.surface.isShowing &&
                    SwingUtilities.getWindowAncestor(player.surface) !== frame &&
                    it.ready && !it.loading && it.nativePaused == true && it.firstVideoFrameReady &&
                    player.ownsSourceSnapshot(terminalPresentationSource)
            }
            player.setPaused(false)
            player.seekTo(9.0)
            waitFor(player, "end of file inside floating window") { it.ended }
            checks["endOfFile"] = "passed"
            val endedPosition = player.state.value.positionSeconds
            SwingUtilities.invokeAndWait { requireNotNull(pip).restore() }
            waitFor(player, "EOF return restores an idle Main core without replay") {
                pipRestored && !requireNotNull(pip).active.value && player.surface.isShowing &&
                    SwingUtilities.getWindowAncestor(player.surface) === frame &&
                    it.ready && !it.loading && it.ended && it.paused &&
                    player.ownsSourceSnapshot(terminalPresentationSource)
            }
            Thread.sleep(1_200)
            check(player.state.value.ended && player.state.value.paused &&
                abs(player.state.value.positionSeconds - endedPosition) < .2 &&
                player.ownsSourceSnapshot(terminalPresentationSource)) { "Returning an ended PiP implicitly replayed its source." }
            val terminalNative = requireNotNull(runBlocking { player.captureNativeAudioDiagnostic() })
            val idle = requireNotNull(terminalNative.properties["idle-active"])
            check(!terminalNative.fileLoaded && terminalNative.sourceVersion == terminalPresentationSource.sourceVersion &&
                idle.nativeCode >= 0 && idle.value == "yes") { "EOF PiP return loaded media without a replay command." }
            checks["nativeFloatingEofReturnPreservesTerminalSource"] = "passed"
            val replayOwnership = player.currentSourceVersion
            player.togglePause()
            waitFor(player, "replay after end of file") { !it.loading && !it.ended && !it.paused && it.positionSeconds < 2.0 && it.videoCodec != null }
            checks["replayAfterEndOfFile"] = "passed"
            check(player.currentSourceVersion == replayOwnership) { "Same-source replay changed media ownership." }

            player.setSpeed(1.0)
            val segments = listOf(PlaybackSegment(video.toURI().toASCIIString(), 3.0), PlaybackSegment(video.toURI().toASCIIString(), 4.0))
            player.load(PlaybackSource(video.absolutePath, referer = "", title = "Two progressive segments", progressiveSegments = segments,
                startPositionSeconds = 4.2, startPaused = true))
            waitFor(player, "progressive EDL continuous duration and cross-segment seek") {
                !it.loading && it.paused && abs(it.durationSeconds - 7.0) < 0.15 && abs(it.positionSeconds - 4.2) < 0.3 && it.videoCodec != null
            }
            player.seekTo(2.6)
            player.setPaused(false)
            waitFor(player, "progressive EDL automatic segment transition") { !it.loading && !it.ended && it.positionSeconds > 3.3 && it.positionSeconds < 6.5 && it.videoCodec != null }
            player.seekTo(6.5)
            waitFor(player, "progressive EDL end of whole timeline") { it.ended }
            checks["progressiveSegmentsDurationSeekAndTransition"] = "passed"

            // Saturate the native event queue with old sources; only the latest playlist entry may publish state.
            repeat(20) { index ->
                val source = if (index % 2 == 0) File(outputDirectory, "rapid-missing-$index.avi").absolutePath else video.absolutePath
                player.load(PlaybackSource(source, referer = "", title = "Rapid source $index", startPositionSeconds = 1.5, startPaused = true))
            }
            waitFor(player, "latest source after rapid replacement") {
                !it.loading && it.paused && it.videoCodec != null && abs(it.positionSeconds - 1.5) < 0.3
            }
            check(player.state.value.sourceTitle == "Rapid source 19" && player.state.value.failure == null && !player.state.value.ended)
            val latestVersion = player.currentSourceVersion
            repeat(10) { index ->
                val source = if (index % 2 == 0) File(outputDirectory, "rapid-recovery-missing-$index.avi").absolutePath else video.absolutePath
                check(player.recoverSource(latestVersion, PlaybackSource(source, referer = "", title = "Rapid recovery $index"), 2.2, true))
            }
            waitFor(player, "latest same-owner recovery after rapid replacement") {
                !it.loading && it.paused && it.videoCodec != null && abs(it.positionSeconds - 2.2) < 0.3
            }
            check(player.currentSourceVersion == latestVersion && player.state.value.sourceTitle == "Rapid recovery 9" &&
                player.state.value.failure == null && !player.state.value.ended)
            checks["rapidReplacementAndSameOwnerRecoveryIsolation"] = "passed"

            DesktopControllerNativeSmoke.run(player, video, outputDirectory)
            checks["nativeControllerPartsQueueAndHeartbeat"] = "passed"
            DesktopControllerNativeSmoke.runCdnRecovery(player, video, outputDirectory)
            checks["nativeHttpFailureAuthorizedCdnRecoveryAndRedaction"] = "passed"
            DesktopOverlayNativeSmoke.run(player, outputDirectory)
            checks["nativeOverlayPluginStyleAndEyeTint"] = "passed"
            checks["nativeBasXmlPixelsAndCanvasSeek"] = "passed"
            com.bilipai.desktop.ui.DesktopFeedbackCarrierNativeSmoke.run(player, outputDirectory)
            checks["nativeDecorativeFeedbackCarrierPixelsInputAndLifetime"] = "passed"
            DesktopRetainedMediaNativeSmoke.run(player, requireNotNull(frame), video)
            checks["nativeRetainedMediaHostJobsAndOwnership"] = "passed"
            val streamHeaderCases = DesktopStreamHeaderNativeSmoke.run(player, video)
            check(streamHeaderCases.size == 6) { "The native stream-header fixture did not complete all stages." }
            checks["nativeArbitraryHttpStreamHeadersOwnershipResetAndRedaction"] = "passed"
            checks["nativeHttpStreamHeaderStageCount"] = streamHeaderCases.size.toString()

            player.load(PlaybackSource(File(outputDirectory, "intentionally-missing-media.avi").absolutePath, referer = ""))
            waitFor(player, "invalid media error", allowError = true) { it.error != null }
            check(player.state.value.failure?.kind == PlayerFailureKind.FILE_IO && !player.state.value.failure?.diagnostics.isNullOrEmpty()) {
                "Missing local media did not publish typed, bounded native diagnostics: ${player.state.value.failure}"
            }
            checks["invalidMedia"] = "passed"
            val recoveryOwnership = player.loadVersioned(PlaybackSource(video.absolutePath, referer = "", title = "Video only recovery",
                startPositionSeconds = 2.0, startPaused = true))
            waitFor(player, "error recovery and resume position") {
                it.error == null && !it.loading && abs(it.positionSeconds - 2.0) < 0.3 && it.paused && it.videoCodec != null
            }
            check(player.state.value.audioCodec == null) { "The previous DASH audio track leaked into the next video." }
            check(player.state.value.tracks.none { it.type == "sub" }) { "The previous subtitle track leaked into the next video." }
            check(!player.state.value.softwareDecodingRequested && player.state.value.failure == null) { "A prior fallback or failure leaked into a new source." }
            checks["errorRecoveryAndAudioIsolation"] = "passed"
            checks["sourceResumePositionAndPause"] = "passed"
            check(!player.stopIfSourceVersion(replayOwnership) && player.state.value.videoCodec != null) { "An old source owner stopped the newer video." }
            check(player.stopIfSourceVersion(recoveryOwnership)) { "The current source owner could not release its stream." }
            checks["sourceOwnershipAndSameSourceReplay"] = "passed"
            passed = true
        } catch (failure: Throwable) {
            val cause = (failure as? InvocationTargetException)?.targetException ?: failure
            checks["failure"] = cause.message ?: cause.javaClass.simpleName
            runCatching { appendFailureDiagnostics(player, visibilityTrace, checks) }.onFailure {
                checks["failureObservation.diagnosticError"] = it.javaClass.simpleName
            }
        } finally {
            mediaSession?.close()
            pip?.close()
            player.close()
            SwingUtilities.invokeAndWait {
                frame?.let { window ->
                    try { window.isAlwaysOnTop = false } finally { window.dispose() }
                }
            }
            checks["nativeClosed"] = (!player.state.value.ready).toString()
            val report = buildString {
                append("{\n  \"passed\": $passed")
                checks.forEach { (key, value) -> append(",\n  ${jsonString(key)}: ${jsonString(value)}") }
                append("\n}\n")
            }
            File(outputDirectory, "native-player-smoke.json").writeText(report)
            println(report)
        }
        return passed
    }

    /** Per-invocation observations only; never an alternate visibility or rendering gate. */
    private class NativeVisibilityTrace {
        var attempts = 0
        var lastGeometry: Map<String, String> = emptyMap()
        var lastPixelCapture: Map<String, String> = emptyMap()

        fun observe(player: MpvPlayer, window: JFrame, elapsedMillis: Long) {
            check(SwingUtilities.isEventDispatchThread())
            attempts++
            lastGeometry = runCatching {
                fun rect(value: Rectangle) = "${value.x},${value.y},${value.width},${value.height}"
                val surface = player.surface
                val windowBounds = if (window.isShowing) window.locationOnScreen.let {
                    Rectangle(it.x, it.y, window.width, window.height)
                } else null
                val surfaceBounds = if (surface.isShowing) surface.locationOnScreen.let {
                    Rectangle(it.x, it.y, surface.width, surface.height)
                } else null
                val fields = linkedMapOf(
                    "elapsedMillis" to elapsedMillis.toString(),
                    "windowIdentity" to System.identityHashCode(window).toString(),
                    "windowDisplayable" to window.isDisplayable.toString(),
                    "windowShowing" to window.isShowing.toString(),
                    "windowFocused" to window.isFocused.toString(),
                    "windowActive" to window.isActive.toString(),
                    "surfaceDisplayable" to surface.isDisplayable.toString(),
                    "surfaceShowing" to surface.isShowing.toString(),
                    "surfaceSameWindow" to (SwingUtilities.getWindowAncestor(surface) === window).toString(),
                    "windowBounds" to (windowBounds?.let(::rect) ?: "unavailable"),
                    "surfaceBounds" to (surfaceBounds?.let(::rect) ?: "unavailable"),
                )
                surface.graphicsConfiguration?.let { config ->
                    fields["surfaceGraphicsBounds"] = rect(config.bounds)
                    fields["surfaceScale"] = "${config.defaultTransform.scaleX},${config.defaultTransform.scaleY}"
                    windowBounds?.let { fields["surfaceMonitor.windowIntersection"] = rect(it.intersection(config.bounds)) }
                    surfaceBounds?.let { fields["surfaceMonitor.surfaceIntersection"] = rect(it.intersection(config.bounds)) }
                }
                fields
            }.getOrElse { mapOf("diagnosticError" to it.javaClass.simpleName) }
        }
    }

    /** Same actor, existing fixed native-property whitelist and 1.5s timeout; no commands or source metadata. */
    private fun appendFailureDiagnostics(player: MpvPlayer, trace: NativeVisibilityTrace, checks: MutableMap<String, String>) {
        fun put(name: String, value: Any?) { checks["failureObservation.$name"] = value?.toString()?.take(512) ?: "unavailable" }
        put("geometryAttempts", trace.attempts)
        trace.lastGeometry.forEach { (key, value) -> put("geometry.$key", value) }
        trace.lastPixelCapture.forEach { (key, value) -> put("pixels.$key", value) }
        val state = player.state.value
        put("state.ready", state.ready); put("state.loading", state.loading)
        put("state.paused", state.paused); put("state.nativePaused", state.nativePaused); put("state.ended", state.ended)
        put("state.positionSeconds", state.positionSeconds); put("state.durationSeconds", state.durationSeconds)
        put("state.firstVideoFrameReady", state.firstVideoFrameReady); put("state.hardwareDecoder", state.hardwareDecoder)
        val output = player.videoOutput.value
        put("output.sourceVersion", output.sourceVersion)
        put("output.inputDimensions", "${output.inputWidth},${output.inputHeight}")
        put("output.viewport", output.viewport)
        val gpu = player.nvidiaVideoState.value
        put("gpu.sourceVersion", gpu.sourceVersion); put("gpu.name", gpu.gpuName)
        put("gpu.vendorId", gpu.gpuVendorId); put("gpu.context", gpu.currentGpuContext)
        // Flow values above and the actor response below are separately timed observations.
        val native = runBlocking { player.captureNativeAudioDiagnostic() }
        put("native.available", native != null)
        if (native == null) return
        put("native.sourceVersion", native.sourceVersion); put("native.playbackRevision", native.playbackRevision)
        put("native.activeSourceVersion", native.activeSourceVersion); put("native.activePlaybackRevision", native.activePlaybackRevision)
        put("native.sessionIdentity", native.sessionIdentity); put("native.workerThread", native.workerThreadName)
        put("native.fileLoaded", native.fileLoaded); put("native.pendingPauseIntent", native.pendingPauseIntent)
        listOf("current-vo", "pause", "time-pos", "duration", "idle-active", "core-idle", "eof-reached", "seeking").forEach { name ->
            val property = native.properties[name]
            put("native.$name.code", property?.nativeCode); put("native.$name.value", property?.value)
        }
        native.safeNativeLogs.takeLast(16).forEachIndexed { index, value -> put("native.safeLog$index", value) }
    }

    private fun waitForRenderedVideo(player: MpvPlayer, window: JFrame, screenshot: File, trace: NativeVisibilityTrace): Boolean {
        val robot = Robot()
        val deadline = System.nanoTime() + 5_000_000_000L
        val context = File(screenshot.parentFile, "${screenshot.nameWithoutExtension}-context.png")
        val firstRejected = File(screenshot.parentFile, "${screenshot.nameWithoutExtension}-first-rejected.png")
        val firstRejectedObservations = File(screenshot.parentFile, "${screenshot.nameWithoutExtension}-first-rejected.json")
        val observations = File(screenshot.parentFile, "${screenshot.nameWithoutExtension}-capture.json")
        firstRejected.delete()
        firstRejectedObservations.delete()
        var failure = "The native test window did not become visible on the desktop."
        while (System.nanoTime() < deadline) {
            var bounds: Pair<Rectangle, Rectangle>? = null
            var focused = false
            SwingUtilities.invokeAndWait {
                // Startup requests the foreground once and keeps this owned window on top.
                // Screen sampling is read-only: repeated focus/stacking requests can disturb
                // native presentation. Retain the actual focus and physical-pixel checks.
                focused = window.isFocused
                if (window.isShowing && player.surface.isShowing &&
                    player.surface.width > 0 && player.surface.height > 0) {
                    val position = window.locationOnScreen
                    val surface = player.surface.locationOnScreen
                    bounds = Rectangle(position.x, position.y, window.width, window.height) to
                        Rectangle(surface.x, surface.y, player.surface.width, player.surface.height)
                }
                trace.observe(player, window, (System.nanoTime() - (deadline - 5_000_000_000L)) / 1_000_000L)
            }
            bounds?.let { (windowBounds, surfaceBounds) ->
                val beforeSampleNanos = System.nanoTime()
                val beforeState = player.state.value
                val beforeOutput = player.videoOutput.value
                val captureStartNanos = System.nanoTime()
                val surfaceImage = robot.createScreenCapture(surfaceBounds)
                val captureEndNanos = System.nanoTime()
                val afterState = player.state.value
                val afterOutput = player.videoOutput.value
                val afterSampleNanos = System.nanoTime()
                // This later full-window image supplies context, not a same-frame proof.
                ImageIO.write(robot.createScreenCapture(windowBounds), "png", context)
                fun rect(value: Rectangle) = "${value.x},${value.y},${value.width},${value.height}"
                val fields = linkedMapOf(
                    "image" to screenshot.name,
                    "imageKind" to "physical surface; exactly the BufferedImage checked below",
                    "contextImage" to context.name,
                    "contextTiming" to "separate later Robot capture; not the validated frame",
                    "nativeStateTiming" to "near cached StateFlow reads; not same-instant native property ACKs, nor atomic with pixels or each other",
                    "windowBoundsAwtLogical" to rect(windowBounds),
                    "surfaceBoundsAwtLogical" to rect(surfaceBounds),
                    "surfaceImagePixels" to "${surfaceImage.width},${surfaceImage.height}",
                    "focusedNearCapture" to focused.toString(),
                    "beforeSampleNanos" to beforeSampleNanos.toString(),
                    "captureStartNanos" to captureStartNanos.toString(),
                    "captureEndNanos" to captureEndNanos.toString(),
                    "afterSampleNanos" to afterSampleNanos.toString(),
                    "beforePositionSeconds" to beforeState.positionSeconds.toString(),
                    "afterPositionSeconds" to afterState.positionSeconds.toString(),
                    "beforeNativePaused" to beforeState.nativePaused.toString(),
                    "afterNativePaused" to afterState.nativePaused.toString(),
                    "beforeOutputSourceVersion" to beforeOutput.sourceVersion.toString(),
                    "afterOutputSourceVersion" to afterOutput.sourceVersion.toString(),
                    "beforeViewport" to beforeOutput.viewport.toString(),
                    "afterViewport" to afterOutput.viewport.toString(),
                )
                try {
                    check(beforeOutput.sourceVersion > 0 && beforeOutput.sourceVersion == afterOutput.sourceVersion &&
                        beforeOutput.inputWidth == WIDTH && beforeOutput.inputHeight == HEIGHT &&
                        afterOutput.inputWidth == WIDTH && afterOutput.inputHeight == HEIGHT &&
                        beforeOutput.viewport != null && beforeOutput.viewport == afterOutput.viewport) {
                        "Native fixture viewport was unavailable or changed across the physical capture."
                    }
                    val integrity = saveCheckedSurfaceCapture(surfaceImage, screenshot, requireNotNull(beforeOutput.viewport))
                    fields["validated"] = "true"
                    fields["cyanPixels"] = integrity.cyanPixels.toString()
                    fields["pinkPixels"] = integrity.pinkPixels.toString()
                    integrity.bands.forEachIndexed { index, band -> fields["backgroundBand.$index"] = band.toString() }
                    fields["earlierRejectedCapture"] = firstRejected.exists().toString()
                    if (firstRejected.exists()) fields["firstRejectedImage"] = firstRejected.name
                    writePixelCaptureObservations(observations, fields)
                    trace.lastPixelCapture = fields
                    return focused
                } catch (notRendered: IllegalStateException) {
                    failure = notRendered.message ?: "The native video surface was not rendered."
                    fields["validated"] = "false"
                    fields["failure"] = failure
                    fields["firstRejectedImage"] = firstRejected.name
                    if (!firstRejected.exists()) {
                        ImageIO.write(surfaceImage, "png", firstRejected)
                        writePixelCaptureObservations(firstRejectedObservations, fields)
                    }
                    writePixelCaptureObservations(observations, fields)
                    trace.lastPixelCapture = fields
                }
            }
            Thread.sleep(100)
        }
        error("Timed out waiting for visible native video rendering: $failure")
    }

    private fun writePixelCaptureObservations(file: File, fields: Map<String, String>) {
        file.writeText(fields.entries.joinToString(prefix = "{\n", postfix = "\n}\n", separator = ",\n") {
            "  ${jsonString(it.key)}: ${jsonString(it.value)}"
        })
    }

    internal data class FixtureBackgroundBand(
        val physicalBounds: Rectangle,
        val fillFraction: Double,
        val minimumRowFill: Double,
        val minimumColumnFill: Double,
    )

    internal data class RenderedFixtureIntegrity(val cyanPixels: Int, val pinkPixels: Int, val bands: List<FixtureBackgroundBand>)

    /** The exact same pixels checked here are encoded into the published surface PNG. No second capture. */
    internal fun saveCheckedSurfaceCapture(image: BufferedImage, screenshot: File, viewport: PlayerVideoViewport): RenderedFixtureIntegrity {
        val integrity = checkRenderedFixtureSurface(image, viewport)
        check(ImageIO.write(image, "png", screenshot)) { "No PNG encoder for validated native surface." }
        return integrity
    }

    internal fun checkRenderedVideo(image: BufferedImage) {
        var cyanPixels = 0
        var pinkPixels = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = Color(image.getRGB(x, y))
            if (color.blue > 180 && color.green > 135 && color.red < 135) cyanPixels++
            if (color.red > 180 && color.green < 145 && color.blue in 100..200) pinkPixels++
        }
        check(cyanPixels >= 100 && pinkPixels >= 100) {
            "Decoded video was not visible on the native Windows surface (cyan=$cyanPixels, pink=$pinkPixels)."
        }
    }

    /** Additional fixture-only spatial gate; every physical caller must supply actual native OSD bounds. */
    internal fun checkRenderedFixtureSurface(image: BufferedImage, viewport: PlayerVideoViewport): RenderedFixtureIntegrity {
        checkRenderedVideo(image)
        // Diagnostic counts only: acceptance above remains the unchanged existing checker.
        var cyanPixels = 0
        var pinkPixels = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = Color(image.getRGB(x, y))
            if (color.blue > 180 && color.green > 135 && color.red < 135) cyanPixels++
            if (color.red > 180 && color.green < 145 && color.blue in 100..200) pinkPixels++
        }
        // Actual createFixtureFrame geometry: the moving circle occupies y45..88,
        // labels end at y135, and the progress block starts at y156. These two
        // static interior background strips are untouched in every encoded frame.
        // Native OSD bounds (including crop/pan margins) supply the transform; no
        // screenshot-aspect or current-position inference selects the regions.
        val transform = viewport.sourceToPhysicalTransform(image.width, image.height, WIDTH, HEIGHT)
        val bands = listOf(Rectangle(4, 98, WIDTH - 8, 12), Rectangle(4, 143, WIDTH - 8, 8)).map { sourceBand ->
            val mapped = transform.createTransformedShape(sourceBand).bounds2D
            val left = maxOf(0, ceil(mapped.minX).toInt() + 1)
            val top = maxOf(0, ceil(mapped.minY).toInt() + 1)
            val right = minOf(image.width, floor(mapped.maxX).toInt() - 1)
            val bottom = minOf(image.height, floor(mapped.maxY).toInt() - 1)
            check(right - left >= 8 && bottom - top >= 2) { "Native fixture background strip was not sufficiently visible." }
            val rows = IntArray(bottom - top)
            val columns = IntArray(right - left)
            var good = 0
            for (y in top until bottom) for (x in left until right) {
                val color = Color(image.getRGB(x, y))
                // Interior MJPEG/color-conversion tolerance. Pure black is 38
                // blue levels from the source background and cannot be accepted.
                if (abs(color.red - 24) <= 18 && abs(color.green - 27) <= 18 && abs(color.blue - 38) <= 18) {
                    good++; rows[y - top]++; columns[x - left]++
                }
            }
            val width = right - left
            val height = bottom - top
            val band = FixtureBackgroundBand(Rectangle(left, top, width, height), good.toDouble() / (width * height),
                rows.minOrNull()!!.toDouble() / width, columns.minOrNull()!!.toDouble() / height)
            check(band.fillFraction >= 0.98 && band.minimumRowFill >= 0.95 && band.minimumColumnFill >= 0.95) {
                "Native fixture background was discontinuous: $band"
            }
            band
        }
        return RenderedFixtureIntegrity(cyanPixels, pinkPixels, bands)
    }

    private fun waitFor(player: MpvPlayer, operation: String, allowError: Boolean = false, condition: (PlayerState) -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            val state = player.state.value
            if (condition(state)) return
            if (!allowError && state.error != null) error("$operation: ${state.error}")
            Thread.sleep(50)
        }
        error("Timed out waiting for $operation. State: ${player.state.value}")
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach {
            when (it) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (it.code < 32) append("\\u%04x".format(it.code)) else append(it)
            }
        }
        append('"')
    }

    private const val WIDTH = 320
    private const val HEIGHT = 180
    private const val FPS = 20
    private const val SECONDS = 10

    /** Same generated MJPEG frame; exposed only for headless image-contract tests. */
    internal fun createFixtureFrame(index: Int): BufferedImage {
        require(index in 0 until FPS * SECONDS)
        return BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB).also { image ->
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
        }
    }

    private fun createVideo(file: File) {
        val frames = List(FPS * SECONDS) { index ->
            val image = createFixtureFrame(index)
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
