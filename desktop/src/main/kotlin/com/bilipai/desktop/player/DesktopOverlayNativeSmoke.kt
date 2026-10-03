package com.bilipai.desktop.player

import com.android.purebilibili.core.plugin.DanmakuStyle
import com.bilipai.desktop.danmaku.*
import java.awt.Color
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Proves the real transparent HWND renders above D3D11; a BufferedImage-only paint test cannot prove that. */
internal object DesktopOverlayNativeSmoke {
    fun run(player: MpvPlayer, outputDirectory: File) {
        val noNetwork = object : DesktopDanmakuSource {
            override suspend fun metadata(cid: Long, aid: Long): ByteArray = error("Native overlay fixture must never access HTTP")
            override suspend fun segment(cid: Long, index: Int): ByteArray = error("Native overlay fixture must never access HTTP")
            override suspend fun xml(cid: Long): ByteArray = error("Native overlay fixture must never access HTTP")
            override suspend fun special(url: String): ByteArray = error("Native overlay fixture must never access HTTP")
        }
        val frameDeadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < frameDeadline && (!player.state.value.firstVideoFrameReady || player.state.value.nativePaused != true)) {
            check(player.state.value.error == null) { "Native overlay source failed before its first decoded frame." }
            Thread.sleep(25)
        }
        check(player.state.value.firstVideoFrameReady && player.state.value.nativePaused == true) { "Native overlay source did not display a paused decoded frame." }
        val overlay = DanmakuOverlay(player, renderPlatform = DesktopWindowsDanmakuRenderPlatform { requireNotNull(SwingUtilities.getWindowAncestor(player.surface)) }, source = noNetwork)
        val robot = Robot()
        fun capture(): BufferedImage {
            var bounds: Rectangle? = null
            SwingUtilities.invokeAndWait {
                val surface = player.surface
                val point = surface.locationOnScreen
                bounds = Rectangle(point.x, point.y, surface.width, surface.height)
            }
            return robot.createScreenCapture(requireNotNull(bounds))
        }
        fun waitImage(operation: String, predicate: (BufferedImage) -> Boolean): BufferedImage {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                check(overlay.loadError.value == null) { "Native overlay failed: ${overlay.loadError.value}" }
                val image = capture()
                if (predicate(image)) return image
                Thread.sleep(50)
            }
            val failed = capture()
            ImageIO.write(failed, "png", File(outputDirectory, "native-overlay-failed.png"))
            var snapshot = ""
            SwingUtilities.invokeAndWait { snapshot = overlay.nativeRenderSnapshot() }
            File(outputDirectory, "native-overlay-failed-state.txt").writeText("operation=$operation\n$snapshot\n")
            error("Timed out waiting for native $operation. $snapshot")
        }
        fun red(image: BufferedImage) = pixels(image) { it.red > 190 && it.green < 60 && it.blue < 75 }
        fun blue(image: BufferedImage) = pixels(image) { it.blue > 190 && it.red < 60 && it.green < 60 }
        try {
            check(player.state.value.paused && player.state.value.videoCodec != null)
            val baseline = capture()
            ImageIO.write(baseline, "png", File(outputDirectory, "native-overlay-before.png"))
            check(red(baseline) < 20 && blue(baseline) < 20) { "The local native fixture already contains the overlay's proof colors." }
            SwingUtilities.invokeAndWait {
                // Original top/bottom layers have zero tracks when the row budget is <= 4.
                // Use a full-height budget for this fixed-top native pixel fixture.
                overlay.applySettings(DanmakuSettings(opacity = 1f, fontScale = 1.5f, displayAreaRatio = 1f, strokeEnabled = false, staticDurationSeconds = 20f))
                overlay.setDocument(DanmakuParser.parseDocument("<i><d p=\"0,5,40,16711680,0,0,fixture,77\">NATIVE DANMAKU PROOF</d></i>"))
            }
            ImageIO.write(waitImage("XML danmaku above D3D11") { red(it) > 100 }, "png", File(outputDirectory, "native-danmaku-overlay.png"))
            SwingUtilities.invokeAndWait { overlay.setPluginDanmakuProcessor { null } }
            waitImage("plugin filter removes visible native comments") { red(it) < 20 }
            check(overlay.commentCount.value == 0)
            SwingUtilities.invokeAndWait {
                overlay.setPluginDanmakuProcessor { item -> item.copy(content = "PLUGIN NATIVE PROOF") to
                    DanmakuStyle(textColor = androidx.compose.ui.graphics.Color(0xff0000ff), bold = true, scale = 1.4f) }
            }
            ImageIO.write(waitImage("plugin style reaches transparent native HWND") { blue(it) > 100 && red(it) < 20 }, "png",
                File(outputDirectory, "native-danmaku-plugin-style.png"))
            SwingUtilities.invokeAndWait { overlay.enabled = false; overlay.setEyeProtection(0.6f, 0f) }
            val baselineBrightness = brightness(baseline)
            val dimmed = waitImage("eye tint stays visible with danmaku disabled") { brightness(it) < baselineBrightness * 0.6 }
            check(brightness(dimmed) > baselineBrightness * 0.25) { "Eye tint replaced the video with an opaque window." }
            ImageIO.write(dimmed, "png", File(outputDirectory, "native-eye-protection.png"))
            SwingUtilities.invokeAndWait { overlay.setEyeProtection(0f, 0f) }
            waitImage("remove eye tint restores the native decoded video") { kotlin.math.abs(brightness(it) - baselineBrightness) < 5.0 }
        } finally {
            overlay.close()
            SwingUtilities.invokeAndWait { }
        }
    }

    private fun pixels(image: BufferedImage, matches: (Color) -> Boolean): Int {
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (matches(Color(image.getRGB(x, y)))) count++
        return count
    }
    private fun brightness(image: BufferedImage): Double {
        var sum = 0L
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = Color(image.getRGB(x, y)); sum += color.red + color.green + color.blue
        }
        return sum.toDouble() / (image.width * image.height * 3.0)
    }
}
