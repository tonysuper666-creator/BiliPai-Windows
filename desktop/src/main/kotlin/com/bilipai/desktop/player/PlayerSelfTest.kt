package com.bilipai.desktop.player

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
import kotlin.math.sin

/** An opt-in native integration smoke test; no network, account, or ffmpeg needed. */
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
        val checks = linkedMapOf<String, String>()
        var passed = false
        try {
            check(!GraphicsEnvironment.isHeadless()) { "Native screen rendering requires an interactive desktop." }
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
            waitFor(player, "native initialization") { it.ready }
            player.setVolume(20.0)
            player.load(PlaybackSource(video.absolutePath, audio.absolutePath, referer = "", title = "Separate video + audio test"))
            waitFor(player, "separate audio/video playback") {
                !it.loading && it.positionSeconds > 0.5 && !it.videoCodec.isNullOrBlank() && !it.audioCodec.isNullOrBlank()
            }
            checks["videoCodec"] = player.state.value.videoCodec.orEmpty()
            checks["audioCodec"] = player.state.value.audioCodec.orEmpty()
            checks["nativeVersion"] = player.state.value.nativeVersion.orEmpty()
            checks["audioOutputMode"] = if (useNullAudioOutput) "CI timed null output" else "Windows audio device"
            waitFor(player, "audio/video clock synchronization") { it.avSyncSeconds != null && abs(it.avSyncSeconds) < 0.2 }
            checks["avSyncSeconds"] = player.state.value.avSyncSeconds.toString()
            waitForRenderedVideo(player, requireNotNull(frame), File(outputDirectory, "native-player-smoke.png"))
            checks["nativeVideoRendering"] = "passed"
            player.setPaused(true)
            waitFor(player, "pause") { it.paused }
            val pausedPosition = player.state.value.positionSeconds
            Thread.sleep(500)
            check(abs(player.state.value.positionSeconds - pausedPosition) < 0.15) { "Playback advanced while paused." }
            checks["pause"] = "passed"
            player.seekTo(4.0)
            waitFor(player, "absolute seek") { abs(it.positionSeconds - 4.0) < 0.3 }
            checks["seek"] = "passed"
            player.setVolume(37.0)
            player.setSpeed(1.5)
            waitFor(player, "volume and speed") { abs(it.volume - 37.0) < 0.1 && abs(it.speed - 1.5) < 0.01 }
            player.setPaused(false)
            val startPosition = player.state.value.positionSeconds
            waitFor(player, "resume") { !it.paused && it.positionSeconds > startPosition + 0.4 }
            checks["volume"] = player.state.value.volume.toString()
            checks["speed"] = player.state.value.speed.toString()
            player.seekTo(9.0)
            waitFor(player, "end of file") { it.ended }
            checks["endOfFile"] = "passed"

            player.load(PlaybackSource(File(outputDirectory, "intentionally-missing-media.avi").absolutePath, referer = ""))
            waitFor(player, "invalid media error", allowError = true) { it.error != null }
            checks["invalidMedia"] = "passed"
            player.load(PlaybackSource(video.absolutePath, referer = "", title = "Video only recovery"))
            waitFor(player, "error recovery") { it.error == null && !it.loading && it.positionSeconds > 0.3 && it.videoCodec != null }
            check(player.state.value.audioCodec == null) { "The previous DASH audio track leaked into the next video." }
            checks["errorRecoveryAndAudioIsolation"] = "passed"
            passed = true
        } catch (failure: Throwable) {
            val cause = (failure as? InvocationTargetException)?.targetException ?: failure
            checks["failure"] = cause.message ?: cause.javaClass.simpleName
        } finally {
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

    private fun waitForRenderedVideo(player: MpvPlayer, window: JFrame, screenshot: File) {
        val robot = Robot()
        val deadline = System.nanoTime() + 5_000_000_000L
        var failure = "The native test window did not become visible and focused on the desktop."
        while (System.nanoTime() < deadline) {
            var bounds: Pair<Rectangle, Rectangle>? = null
            SwingUtilities.invokeAndWait {
                // Foreground apps can cover a decoded native surface. Keep this
                // opt-in test above them, then wait for focus and actual pixels.
                window.toFront()
                window.requestFocus()
                if (window.isShowing && window.isFocused && player.surface.isShowing &&
                    player.surface.width > 0 && player.surface.height > 0) {
                    val position = window.locationOnScreen
                    val surface = player.surface.locationOnScreen
                    bounds = Rectangle(position.x, position.y, window.width, window.height) to
                        Rectangle(surface.x, surface.y, player.surface.width, player.surface.height)
                }
            }
            bounds?.let { (windowBounds, surfaceBounds) ->
                ImageIO.write(robot.createScreenCapture(windowBounds), "png", screenshot)
                try {
                    checkRenderedVideo(robot.createScreenCapture(surfaceBounds))
                    return
                } catch (notRendered: IllegalStateException) {
                    failure = notRendered.message ?: "The native video surface was not rendered."
                }
            }
            Thread.sleep(100)
        }
        error("Timed out waiting for visible native video rendering: $failure")
    }

    private fun checkRenderedVideo(image: BufferedImage) {
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
