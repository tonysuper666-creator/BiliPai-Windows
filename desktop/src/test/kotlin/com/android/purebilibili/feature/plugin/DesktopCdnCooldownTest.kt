package com.android.purebilibili.feature.plugin

import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.IOException
import java.net.URI

/** Exercises the retained selection/feedback entry points, without production requests or sleeps. */
class DesktopCdnCooldownTest {
    private var now = 1_000_000L
    private val originalClock = CdnTransferClock.nowMs
    private val a = "https://a.bilivideo.com/video.m4s?token=a"
    private val b = "https://b.bilivideo.com/video.m4s?token=b"
    private val host = "a.bilivideo.com"

    @BeforeEach fun prepare() {
        CdnTransferClock.nowMs = { now }
        CdnTransferRuntime.configure(false, false)
        CdnTransferRuntime.networkChanged("test-${System.nanoTime()}")
        CdnTransferRuntime.configure(true, false)
        CdnTransferRuntime.register(listOf(a, b), emptyList())
    }

    @AfterEach fun cleanup() {
        CdnTransferRuntime.configure(false, false)
        CdnTransferRuntime.restoreRoutes()
        CdnTransferClock.nowMs = originalClock
    }

    @Test fun failureFeedbackDefersOnlyFailedHostUntilExactThirtySecondBoundary() {
        failDelegate()
        assertEquals(listOf(b, a), CdnTransferRuntime.candidates(a))
        now += 29_999
        assertEquals(b, CdnTransferRuntime.candidates(a).first())
        now++
        assertEquals(listOf(a, b), CdnTransferRuntime.candidates(a))
    }

    @Test fun manualAvoidExpiresAtSixtySecondsAndSuccessDoesNotClearItEarly() {
        CdnTransferRuntime.avoid(host)
        CdnTransferRuntime.start(host)
        CdnTransferRuntime.finish(host, success = true)
        now += 59_999
        assertEquals(b, CdnTransferRuntime.candidates(a).first())
        now++
        assertEquals(a, CdnTransferRuntime.candidates(a).first())
    }

    @Test fun automaticCooldownAlsoSurvivesSuccessAndVideoChangeButNotNetworkChange() {
        failDelegate()
        CdnTransferRuntime.start(host)
        CdnTransferRuntime.finish(host, success = true)
        val nextA = a.replace("video.m4s", "next.m4s")
        val nextB = b.replace("video.m4s", "next.m4s")
        CdnTransferRuntime.preferHost(host)
        CdnTransferRuntime.register(listOf(nextA, nextB), emptyList())
        assertNull(CdnTransferRuntime.state.value.preferredHost)
        assertEquals(listOf(nextB, nextA), CdnTransferRuntime.candidates(nextA))
        CdnTransferRuntime.networkChanged("new-network")
        assertEquals(listOf(nextA, nextB), CdnTransferRuntime.candidates(nextA))
    }

    @Test fun allCooledRoutesStayFiniteAndSignedRatherThanDisappearing() {
        failDelegate()
        CdnTransferRuntime.avoid("b.bilivideo.com")
        repeat(20) {
            assertEquals(listOf(a, b), CdnTransferRuntime.candidates(a))
            assertTrue(CdnTransferRuntime.candidates(a.replace("token=a", "token=other")).isEmpty())
        }
        failDelegate() // Even with every host cooled, the real single delegate fails once and returns.
        assertTrue(CdnTransferRuntime.state.value.nodes.all { it.active == 0 })
        CdnTransferRuntime.configure(false, false)
        assertTrue(CdnTransferRuntime.candidates(a).isEmpty())
    }

    @Test fun rateLimitExpiresAtThirtySecondsAndRecentSpeedAtTwoMinutes() {
        CdnTransferRuntime.start(host)
        CdnTransferRuntime.finish(host, false, 429)
        assertEquals(1, CdnTransferRuntime.connectionLimit())
        now += 29_999
        assertEquals(1, CdnTransferRuntime.connectionLimit())
        now++
        assertEquals(3, CdnTransferRuntime.connectionLimit())
        CdnTransferRuntime.progress("b.bilivideo.com", 1, 10_000, 1)
        now += 120_000
        assertEquals(b, CdnTransferRuntime.candidates(a).first())
        now++
        assertEquals(a, CdnTransferRuntime.candidates(a).first())
    }

    @Test fun restoreClearsManualAndAutomaticCooldownWithoutReplacingSignedRoutes() {
        failDelegate()
        CdnTransferRuntime.avoid("b.bilivideo.com")
        CdnTransferRuntime.preferHost("b.bilivideo.com")
        CdnTransferRuntime.restoreRoutes()
        assertEquals(listOf(a, b), CdnTransferRuntime.candidates(a))
    }

    @Test fun retiredSessionOpenFailureDoesNotPoisonNewSessionHost() = retiredFailure(open = true)
    @Test fun retiredSessionReadFailureDoesNotPoisonNewSessionHost() = retiredFailure(open = false)

    private fun retiredFailure(open: Boolean) {
        val platform = Platform()
        val failures = CdnTransferRuntime.state.value.nodes.firstOrNull { it.host == host }?.failures ?: 0
        val source = source(platform, failOpen = open) {
            // The old account/source is retired while its synchronous IO is finishing.
            platform.current = false
        }
        try {
            if (open) assertThrows(IOException::class.java) { source.open(spec()) }
            else {
                source.open(spec())
                assertThrows(IOException::class.java) { source.read(ByteArray(1), 0, 1) }
            }
            assertEquals(listOf(a, b), CdnTransferRuntime.candidates(a))
            val node = CdnTransferRuntime.state.value.nodes.single { it.host == host }
            assertEquals(0L, node.cooldownUntilMs)
            assertEquals(failures, node.failures)
            assertEquals(0, node.active)
        } finally { source.close() }
    }

    private fun failDelegate() {
        source(Platform(), failOpen = true).use { source ->
            assertThrows(IOException::class.java) { source.open(spec()) }
        }
    }

    private fun spec() = CdnMediaDataSpec(URI(a), 0, 1)
    private fun source(platform: Platform, failOpen: Boolean, beforeFailure: () -> Unit = {}): CdnPlaybackDataSource {
        val upstream = object : CdnMediaDataSource {
            override fun open(dataSpec: CdnMediaDataSpec): Long {
                if (failOpen) { beforeFailure(); throw IOException("scripted open failure") }
                return dataSpec.length
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                beforeFailure(); throw IOException("scripted read failure")
            }
            override fun getUri(): URI? = null
            override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
            override fun close() = Unit
        }
        val noNetwork = Call.Factory { error("single transport must not launch parallel calls") }
        return CdnPlaybackDataSource(upstream, false, noNetwork, platform)
    }

    private class Platform : CdnPlaybackPlatform {
        var current = true
        override fun assertCurrent() { if (!current) throw CancellationException("old session") }
        override fun isWifi() = false
        override fun networkIdentity() = "test-network"
        override fun authorizedCandidates(urls: List<String>) = urls.also { assertCurrent() }
        override fun assertParallelUrl(url: String) = assertCurrent()
        override fun prepareRequest(value: Request) = value.also { assertCurrent() }
        override fun validateResponse(value: Response) = assertCurrent()
        override fun bytesTransferred(count: Int) = assertCurrent()
    }
}
