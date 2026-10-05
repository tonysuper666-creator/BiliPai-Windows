package com.bilipai.desktop.player

import com.android.purebilibili.feature.anime4k.Anime4KPreset
import com.bilipai.desktop.plugins.DesktopVideoShaderResources
import kotlinx.coroutines.runBlocking
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max

/** Requires real GPU hook execution and changed Windows pixels, beyond successful option assignment. */
internal object DesktopShaderNativeSmoke {
    fun run(player: MpvPlayer, outputDirectory: File) {
        check(player.state.value.paused && player.state.value.videoCodec != null)
        val owner = player.currentSourceVersion
        val position = player.state.value.positionSeconds
        val robot = Robot()
        fun capture(): BufferedImage {
            var bounds: Rectangle? = null
            SwingUtilities.invokeAndWait {
                val surface = player.surface; val point = surface.locationOnScreen
                bounds = Rectangle(point.x, point.y, surface.width, surface.height)
            }
            return robot.createScreenCapture(requireNotNull(bounds))
        }
        fun waitFor(operation: String, predicate: (PlayerVideoShaderState) -> Boolean) {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < deadline) {
                val shader = player.videoShaderState.value
                check(shader.error == null) { "$operation: ${shader.error}" }
                check(player.state.value.error == null && player.currentSourceVersion == owner && player.state.value.paused) {
                    "$operation changed native playback ownership or pause state"
                }
                if (predicate(shader)) return
                Thread.sleep(50)
            }
            error("Timed out waiting for $operation. Shader state: ${player.videoShaderState.value}")
        }
        player.setVideoShaders(emptyList())
        waitFor("empty GPU shader chain") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() && !it.active }
        Thread.sleep(200)
        val baseline = capture()
        Thread.sleep(150)
        val noise = difference(baseline, capture()).first
        ImageIO.write(baseline, "png", File(outputDirectory, "native-anime4k-before.png"))
        val resources = DesktopVideoShaderResources(File(outputDirectory, "shader-cache").toPath())
        var previousDescriptions = emptySet<String>()
        try {
            for (preset in listOf(Anime4KPreset.FAST, Anime4KPreset.QUALITY)) {
                val files = runBlocking { resources.resolveAnime4KPaths(preset) }
                val prepared = prepareVideoShaders(files)
                val exclusive = prepared.descriptions - previousDescriptions
                check(exclusive.isNotEmpty()) { "The original shader presets have no distinct render passes." }
                val version = player.setVideoShaders(files)
                waitFor("original ${preset.name} GPU hook passes") { state ->
                    state.configurationVersion == version && state.active && state.appliedFiles == prepared.paths &&
                        state.executedPasses.any { pass -> exclusive.any { pass.contains(it) } }
                }
                val deadline = System.nanoTime() + 5_000_000_000L
                var actual: BufferedImage? = null
                while (System.nanoTime() < deadline) {
                    val sample = capture(); val delta = difference(baseline, sample)
                    if (delta.first > max(0.1, noise * 3.0) && delta.second > 100) { actual = sample; break }
                    Thread.sleep(50)
                }
                val changed = requireNotNull(actual) { "${preset.name} acknowledged hooks but did not change decoded video pixels." }
                ImageIO.write(changed, "png", File(outputDirectory, "native-anime4k-${preset.name.lowercase()}.png"))
                check(abs(player.state.value.positionSeconds - position) < 0.15) { "Shader changes advanced a paused frame." }
                previousDescriptions = prepared.descriptions
            }
        } finally {
            player.setVideoShaders(emptyList())
        }
        waitFor("clear GPU shader chain") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() && !it.active }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val restored = capture()
            if (difference(baseline, restored).first <= max(0.1, noise * 2.0)) {
                ImageIO.write(restored, "png", File(outputDirectory, "native-anime4k-cleared.png")); return
            }
            Thread.sleep(50)
        }
        val failed = capture()
        val delta = difference(baseline, failed)
        ImageIO.write(failed, "png", File(outputDirectory, "native-anime4k-failed.png"))
        File(outputDirectory, "native-anime4k-failed-state.txt").writeText(
            "baselinePosition=$position\nactualPosition=${player.state.value.positionSeconds}\n" +
                "noise=$noise\nmeanDelta=${delta.first}\nchangedPixels=${delta.second}\n")
        error("Clearing shader hooks did not restore the unfiltered native video frame.")
    }

    /** Mean RGB delta and count of visibly changed pixels; dimensions must stay fixed. */
    private fun difference(before: BufferedImage, after: BufferedImage): Pair<Double, Int> {
        check(before.width == after.width && before.height == after.height)
        var sum = 0L; var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            val first = before.getRGB(x, y); val second = after.getRGB(x, y)
            val red = abs((first ushr 16 and 255) - (second ushr 16 and 255))
            val green = abs((first ushr 8 and 255) - (second ushr 8 and 255))
            val blue = abs((first and 255) - (second and 255))
            sum += red + green + blue
            if (max(red, max(green, blue)) > 8) changed++
        }
        return sum.toDouble() / (before.width * before.height * 3.0) to changed
    }
}
