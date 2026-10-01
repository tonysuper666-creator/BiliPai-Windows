package com.bilipai.desktop.player

import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** Native API fault schedule only. A fixture latch outside the MPV lock exposes
 * a stale admitted Unit command; no account/Store/HTTP or ordinary page is used. */
object NativeLoadAckFixture {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val checks = mutableListOf<String>()
        fun verify(value: Boolean, message: String) { check(value) { message }; checks += message }
        suspend fun waitFor(message: String, predicate: () -> Boolean) = withTimeout(15_000L) {
            while (!predicate()) delay(25L)
            verify(true, message)
        }
        val clip = Path.of(args[1])
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val staleReturned = CompletableDeferred<Unit>()
        val staleAcks = AtomicInteger()
        val acceptedAcks = AtomicInteger()
        val rejectedAcks = AtomicInteger()
        val rejected = CompletableDeferred<Unit>()
        val adoptedAcks = AtomicInteger()
        val stalePublication = object : DesktopNativePlaybackPublication {
            override fun admit(command: () -> Unit): Boolean {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS)) { "Fixture stale-command gate timed out" }
                command(); staleReturned.complete(Unit); return true
            }
            override fun onLoadCommandAccepted() { staleAcks.incrementAndGet() }
        }
        val acceptedPublication = object : DesktopNativePlaybackPublication {
            override fun admit(command: () -> Unit): Boolean { command(); return true }
            override fun onLoadCommandAccepted() { acceptedAcks.incrementAndGet() }
        }
        val rejectedPublication = object : DesktopNativePlaybackPublication {
            override fun admit(command: () -> Unit): Boolean { rejected.complete(Unit); return false }
            override fun onLoadCommandAccepted() { rejectedAcks.incrementAndGet() }
        }
        val adoptedPublication = object : DesktopNativePlaybackPublication {
            override fun admit(command: () -> Unit): Boolean { command(); return true }
            override fun onLoadCommandAccepted() { adoptedAcks.incrementAndGet() }
        }
        val legacySam = DesktopNativePlaybackPublication { command -> command(); true }
        val player = MpvPlayer(useNullAudioOutput = true)
        var frame: JFrame? = null
        try {
            val source = PlaybackSource(clip.toUri().toString(), title = "Stale local source", startPaused = true,
                startPositionSeconds = 1.0, nativePublication = stalePublication)
            val staleVersion = player.loadVersioned(source)
            verify(staleAcks.get() == 0, "Unattached queued load does not acknowledge acceptance")
            SwingUtilities.invokeAndWait {
                frame = JFrame("BiliPai Native Load ACK 60").apply {
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    contentPane.add(player.surface); setSize(740, 480); setLocation(140, 120); isVisible = true
                }
            }
            withTimeout(15_000L) { entered.await() }
            val acceptedVersion = player.loadVersioned(source.copy(title = "Accepted local source", startPositionSeconds = 2.0,
                nativePublication = acceptedPublication))
            verify(acceptedVersion > staleVersion, "A replacement native version is published before the old Unit command executes")
            release.countDown()
            withTimeout(15_000L) { staleReturned.await() }
            waitFor("The actual replacement local video reaches ready pause readback") {
                val state = player.state.value
                state.ready && !state.loading && state.videoCodec != null && state.nativePaused == true && state.error == null
            }
            verify(staleAcks.get() == 0 && acceptedAcks.get() == 1,
                "Stale admitted no-op returns normally without ACK; actual loadfile receives exactly one ACK")
            verify(player.drainSourceCommands(acceptedVersion) && acceptedAcks.get() == 1,
                "Native actor barrier does not acknowledge a new load")
            val rejectedVersion = player.loadVersioned(source.copy(title = "Rejected local source", nativePublication = rejectedPublication))
            withTimeout(15_000L) { rejected.await() }
            verify(!player.drainSourceCommands(rejectedVersion) && rejectedAcks.get() == 0,
                "Rejected publication neither loads nor emits acceptance ACK")
            val legacyVersion = player.loadVersioned(source.copy(title = "Default SAM local source", startPositionSeconds = 3.0,
                nativePublication = legacySam))
            waitFor("Existing SAM publication loads with the default no-op ACK") {
                val state = player.state.value
                state.ready && !state.loading && state.videoCodec != null && state.nativePaused == true && state.error == null
            }
            verify(player.drainSourceCommands(legacyVersion), "The default SAM load remains on the same live native actor")
            val expected = requireNotNull(player.currentSourceSnapshot()).source
            verify(player.adoptPublication(legacyVersion, expected, expected.copy(nativePublication = adoptedPublication)),
                "The already loaded source can adopt another publication")
            verify(adoptedAcks.get() == 0 && player.drainSourceCommands(legacyVersion) && adoptedAcks.get() == 0,
                "Publication adoption and read-only barrier issue no load ACK")
            val adoptedSource = requireNotNull(player.currentSourceSnapshot()).source
            verify(player.replayAuthorized(legacyVersion, adoptedSource), "The same accepted source admits an explicit replay")
            waitFor("Explicit actual native replay receives its own load acceptance ACK") {
                val state = player.state.value
                adoptedAcks.get() == 1 && state.ready && !state.loading && state.videoCodec != null && state.error == null
            }
            verify(player.currentSourceVersion == legacyVersion && adoptedAcks.get() == 1,
                "Replay keeps the native source version and acknowledges only the accepted command")
            val origins = listOf(MpvPlayer::class.java, DesktopNativePlaybackPublication::class.java).map { type ->
                val bytes = requireNotNull(type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")).use { it.readBytes() }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                "{\"class\":\"${type.name}\",\"codeSource\":\"${type.protectionDomain.codeSource.location.toURI()}\",\"sha256ClassBytes\":\"$hash\"}"
            }
            Files.writeString(Path.of(args[0]), "{\"passed\":true,\"assertions\":${checks.size},\"checks\":[${checks.joinToString(",") { "\"$it\"" }}],\"origins\":[${origins.joinToString(",")}],\"actualNativePlayer\":true,\"localClipOnly\":true,\"staleUnitNoOpReturnsNormally\":true,\"fixtureAdmissionLatchOutsideMpvLock\":true,\"realAccountUsed\":false,\"fullOrdinaryVideoOwnerMounted\":false,\"independentVisualAcceptance\":false}\n")
            println("PASS ${checks.size} actual native load acceptance ACK assertions")
        } finally {
            release.countDown(); player.close()
            SwingUtilities.invokeAndWait { frame?.dispose() }
        }
    }
}
