package com.bilipai.desktop.player

import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs

/** Tests the actual installed native player API with a local clip and explicit fixture publications.
 * No ordinary page, account, HTTP transport or controller handoff is supplied by this fixture. */
object NativePublicationAdoptionFixture {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val output = Path.of(args[0])
        val clip = Path.of(args[1])
        val checks = mutableListOf<String>()
        fun verify(value: Boolean, message: String) {
            check(value) { message }; checks += message
        }
        suspend fun waitFor(message: String, predicate: () -> Boolean) = withTimeout(15_000L) {
            while (!predicate()) { delay(30L) }
            verify(true, message)
        }
        val oldOwner = AtomicBoolean(true)
        val newOwner = AtomicBoolean(true)
        val oldPublication = DesktopNativePlaybackPublication { command ->
            if (!oldOwner.get()) false else { command(); true }
        }
        val newPublication = DesktopNativePlaybackPublication { command ->
            if (!newOwner.get()) false else { command(); true }
        }
        val player = MpvPlayer(useNullAudioOutput = true)
        val surface = player.surface
        val canvas = surface.getComponent(0)
        var frame: JFrame? = null
        try {
            val source = PlaybackSource(clip.toUri().toString(), title = "Adoption local fixture",
                startPositionSeconds = 1.0, startPaused = true, nativePublication = oldPublication)
            val version = player.loadVersioned(source)
            val expected = requireNotNull(player.currentSourceSnapshot()).source
            val replacement = expected.copy(startPositionSeconds = 7.0, startPaused = false,
                nativePublication = newPublication)
            verify(!player.adoptPublication(version, expected, replacement), "An unattached pending load cannot be adopted")
            verify(!player.drainSourceCommands(version), "An unattached source has no native actor to drain")
            SwingUtilities.invokeAndWait {
                frame = JFrame("BiliPai Native Publication Adoption 58").apply {
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    contentPane.add(surface)
                    setSize(740, 480)
                    setLocation(140, 120)
                    isVisible = true
                }
            }
            waitFor("Actual native local video and pause readback are ready") {
                val state = player.state.value
                state.ready && !state.loading && !state.ended && state.error == null &&
                    state.videoCodec != null && state.nativePaused == true
            }
            val seekId = requireNotNull(player.seekToTracked(4.0))
            waitFor("The existing native clock acknowledges the tracked seek") {
                player.state.value.seekCompletedId == seekId && abs(player.state.value.positionSeconds - 4.0) < 0.1
            }
            player.setSpeed(1.25)
            val subtitle = output.resolveSibling("fixture-subtitle.srt")
            Files.writeString(subtitle, "1\n00:00:00,000 --> 00:00:20,000\nPublication adoption fixture\n")
            player.addSubtitle(subtitle, "Adoption fixture", "en")
            waitFor("Actual native external subtitle track and speed readback are present") {
                val state = player.state.value
                state.tracks.any { it.type == "sub" && it.external && it.selected } && abs(state.speed - 1.25) < 0.001
            }
            verify(player.drainSourceCommands(version), "The same native actor drains prior source commands")
            val before = player.state.value
            val beforeVideo = player.videoOutput.value
            val subtitleVersion = player.currentSubtitleControlVersion
            val mismatches = listOf(
                "video URL" to expected.copy(videoUrl = "file:///different-fixture.mp4", nativePublication = newPublication),
                "audio URL" to expected.copy(audioUrl = "file:///different-audio.m4a", nativePublication = newPublication),
                "referer" to expected.copy(referer = "https://fixture.invalid/", nativePublication = newPublication),
                "user agent" to expected.copy(userAgent = "fixture-different-agent", nativePublication = newPublication),
                "cookie header" to expected.copy(cookieHeader = "fixture=changed", nativePublication = newPublication),
                "title" to expected.copy(title = "Changed title", nativePublication = newPublication),
                "segments" to expected.copy(progressiveSegments = listOf(PlaybackSegment(clip.toUri().toString(), 5.0)), nativePublication = newPublication),
                "stream headers" to expected.copy(streamHeaders = mapOf("X-Fixture" to "changed"), nativePublication = newPublication),
                "authorization receipt" to expected.copy(authorizationReceipt = DesktopPlaybackAuthorizationReceipt(7, 11), nativePublication = newPublication),
                "primary epoch" to expected.copy(primaryAccountEpoch = 7, nativePublication = newPublication),
            )
            for ((field, changed) in mismatches) {
                verify(!player.adoptPublication(version, expected, changed), "A replacement with different $field is rejected")
                verify(!player.adoptPublication(version, changed.copy(nativePublication = oldPublication), replacement),
                    "An expected snapshot with different $field is rejected")
            }
            verify(!player.adoptPublication(version + 1, expected, replacement), "A foreign native version is rejected")
            verify(!player.adoptPublication(version, expected, replacement.copy(nativePublication = null)), "A missing new publication is rejected")
            verify(player.state.value == before && player.videoOutput.value == beforeVideo,
                "All rejected adoptions leave actual player and video readback unchanged")
            verify(player.adoptPublication(version, expected, replacement), "Same actual source adopts the required new publication")
            val adopted = requireNotNull(player.currentSourceSnapshot())
            verify(adopted.sourceVersion == version && adopted.source.nativePublication === newPublication,
                "The native version is retained and publication identity changes once")
            verify(adopted.source.startPositionSeconds == expected.startPositionSeconds && adopted.source.startPaused == expected.startPaused,
                "Actual retained start intent ignores the replacement start position and pause")
            verify(player.state.value == before && player.videoOutput.value == beforeVideo,
                "Successful publication adoption issues no state reset or video output reset")
            verify(player.currentSubtitleControlVersion == subtitleVersion && player.surface === surface && surface.getComponent(0) === canvas,
                "Actual subtitle ownership and the same Canvas remain intact")
            oldOwner.set(false)
            verify(!player.adoptPublication(version, expected, expected.copy(nativePublication = oldPublication)),
                "A stale old publication cannot overwrite the new owner at the same version")
            verify(player.drainSourceCommands(version), "The adopted native actor remains available for an independent drain")
            delay(600L)
            val settled = player.state.value
            verify(settled.nativePaused == true && settled.paused && abs(settled.positionSeconds - before.positionSeconds) < 0.02 &&
                settled.seekCompletedId == seekId && abs(settled.speed - 1.25) < 0.001 && settled.tracks == before.tracks,
                "Settled native position, pause, seek receipt, speed and external tracks retain continuity")
            verify(!player.drainSourceCommands(version + 1), "A foreign version cannot acquire the native drain")
            player.close()
            verify(!player.adoptPublication(version, adopted.source, adopted.source.copy(nativePublication = oldPublication)),
                "A closed player cannot adopt any publication")
            verify(!player.drainSourceCommands(version), "A closed player cannot drain a native actor")
            val origins = listOf(MpvPlayer::class.java, PlaybackSource::class.java, DesktopNativePlaybackPublication::class.java).map { type ->
                val bytes = requireNotNull(type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")).use { it.readBytes() }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                "{\"class\":\"${type.name}\",\"codeSource\":\"${type.protectionDomain.codeSource.location.toURI()}\",\"sha256ClassBytes\":\"$hash\"}"
            }
            Files.writeString(output, "{\"passed\":true,\"assertions\":${checks.size},\"checks\":[${checks.joinToString(",") { "\"$it\"" }}],\"origins\":[${origins.joinToString(",")}],\"actualNativePlayer\":true,\"localClipOnly\":true,\"realAccountUsed\":false,\"fullControllerDrain\":false,\"fullOrdinaryVideoOwnerMounted\":false,\"independentVisualAcceptance\":false}\n")
            println("PASS ${checks.size} actual native publication adoption assertions")
        } finally {
            player.close()
            SwingUtilities.invokeAndWait { frame?.dispose() }
        }
    }
}
