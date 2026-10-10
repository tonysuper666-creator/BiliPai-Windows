package com.bilipai.desktop.cast

import com.android.purebilibili.core.plugin.CastPluginMediaRequest
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.ui.DesktopHomeRetainedGate
import com.bilipai.desktop.ui.DesktopOfflineNativeSourcePublication
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Real temporary final-file bytes, production native/Store admission and NanoHTTPD serialization.
 * MPV stays headless. No decoder, receiver, discovery, public endpoint or account data is used.
 */
class DesktopOfflineLocalCastTest {
    private inner class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("bp-local-cast-")
        val file = Files.write(directory.resolve("merged.mp4"), "abcdefghij".toByteArray())
        private val sessions = DesktopSessionStore(directory.resolve("fixture-session.json"), persistent = false)
        private val repository = DesktopRepository(sessions)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val alive = AtomicBoolean(true)
        val player = MpvPlayer()
        val entry = DesktopHomeRetainedGate(repository.dynamicCacheSessionGuard,
            checkNotNull(repository.dynamicCacheSessionGuard.dynamicCacheOwner()),
            { repository.sessionEpoch }, { repository.account.value?.mid }, alive::get, scope)
        val native = DesktopOfflineNativeSourcePublication(player, entry::owns, entry::commit)
        private val source = PlaybackSource(file.toString(), title = "Merged local fixture", nativePublication = native)
        val frame: DesktopCastPublicationFrame
        init {
            assertTrue(entry.commit {
                player.loadVersioned(source)
                native.bind(assertNotNull(player.currentSourceSnapshot()))
            })
            frame = DesktopCastPublicationFrame(DesktopRepositoryPlaybackPublication(repository, true),
                source.copy(primaryAccountEpoch = repository.sessionEpoch), entry::owns, nativeAdmission = native)
        }
        suspend fun register(): String = withContext(frame) {
            DesktopCastProxySessions.registerLocalFile(file, "video/mp4", native)
        }
        fun response(id: String, method: NanoHTTPD.Method = NanoHTTPD.Method.GET, range: String? = null): NanoHTTPD.Response =
            serveDesktopLocalCastFile(session(id, method, range))
        override fun close() {
            native.close(); entry.close(); alive.set(false); scope.cancel(); player.close()
            DesktopCastProxySessions.clear()
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun completedFileGetAndRangesSerializeActualBytesAndLengths(): Unit = runBlocking {
        Fixture().use { f ->
            val id = f.register()
            val full = serialize(f.response(id), NanoHTTPD.Method.GET)
            assertEquals(200, full.code); assertEquals("10", full.headers["content-length"])
            assertEquals("abcdefghij", full.body)
            listOf("bytes=2-5" to "cdef", "bytes=7-" to "hij", "bytes=-3" to "hij").forEach { (range, body) ->
                val part = serialize(f.response(id, range = range), NanoHTTPD.Method.GET)
                assertEquals(206, part.code); assertEquals(body.length.toString(), part.headers["content-length"])
                assertEquals(body, part.body); assertEquals("bytes", part.headers["accept-ranges"])
                assertEquals(if (range == "bytes=2-5") "bytes 2-5/10" else "bytes 7-9/10", part.headers["content-range"])
            }
            listOf("bytes=10-", "bytes=5-2", "bytes=0-1,4-5", "bytes=999999999999999999999-").forEach { range ->
                val rejected = serialize(f.response(id, range = range), NanoHTTPD.Method.GET)
                assertEquals(416, rejected.code); assertEquals("bytes */10", rejected.headers["content-range"])
            }
        }
    }

    @Test fun headIgnoresBothValidAndUnsatisfiableRangesWithoutOpeningMediaStream(): Unit = runBlocking {
        Fixture().use { f ->
            val id = f.register()
            listOf(null, "bytes=2-5", "bytes=100-", "bytes=0-1,4-5").forEach { range ->
                val response = f.response(id, NanoHTTPD.Method.HEAD, range)
                // The real HEAD branch must use the empty in-memory stream, never LocalInputStream/file channel.
                assertIs<ByteArrayInputStream>(response.data)
                assertEquals(0, response.data.available())
                val head = serialize(response, NanoHTTPD.Method.HEAD)
                assertEquals(200, head.code); assertEquals("10", head.headers["content-length"])
                assertNull(head.headers["content-range"]); assertEquals("", head.body)
            }
        }
    }

    @Test fun retiredNativeSourceRejectsOldRegistrationAndAlreadyOpenReadStream(): Unit = runBlocking {
        Fixture().use { f ->
            val id = f.register()
            val response = f.response(id)
            try {
                assertEquals(200, response.status.requestStatus)
                assertEquals('a'.code, response.data.read())
                f.native.close()
                assertFailsWith<IOException> { response.data.read() }
                assertEquals(404, f.response(id).status.requestStatus)
                assertFailsWith<CancellationException> { f.register() }
            } finally { response.close() }
        }
    }

    @Test fun sourceReplacementUnderSameVersionCannotReadOldOpaqueTarget(): Unit = runBlocking {
        Fixture().use { f ->
            val id = f.register()
            val initial = assertNotNull(f.player.currentSourceSnapshot())
            val replacement = Files.write(f.directory.resolve("next-merged.mp4"), "klmnopqrst".toByteArray())
            assertTrue(f.player.recoverSource(initial.sourceVersion, replacement = initial.source.copy(videoUrl = replacement.toString())))
            assertEquals(initial.sourceVersion, assertNotNull(f.player.currentSourceSnapshot()).sourceVersion)
            assertEquals(404, f.response(id).status.requestStatus)
        }
    }

    @Test fun controlOnlySourceUsesActualFrameAdmissionWithoutResolvingMediaAgain(): Unit = runBlocking {
        Fixture().use { f ->
            var resolutions = 0
            var commands = 0
            val publication = DesktopCastMediaPublication(f.frame) {
                resolutions++
                CastPluginMediaRequest("http://127.0.0.1/unused", "fixture", "", "video/mp4")
            }
            publication.use { assertEquals("fixture", it.title) }
            assertEquals(1, resolutions)
            publication.onSource {
                val current = assertNotNull(su.litvak.chromecast.api.v2.DesktopCastPublication.current())
                current.admit(Runnable { commands++ })
            }
            assertEquals(1, commands); assertEquals(1, resolutions)
            // Retire AFTER onSource's preflight. The actual Java transmission callback must still reject it.
            assertFailsWith<CancellationException> {
                publication.onSource {
                    f.native.close()
                    assertNotNull(su.litvak.chromecast.api.v2.DesktopCastPublication.current()).admit(Runnable { commands++ })
                }
            }
            assertEquals(1, commands); assertEquals(1, resolutions)
            assertNull(su.litvak.chromecast.api.v2.DesktopCastPublication.current())
        }
    }

    private data class Wire(val code: Int, val headers: Map<String, String>, val body: String)
    private fun serialize(response: NanoHTTPD.Response, method: NanoHTTPD.Method): Wire {
        val output = ByteArrayOutputStream()
        response.setRequestMethod(method)
        try {
            // Serialize the production Response itself; no replacement HTTP/range implementation.
            val send = NanoHTTPD.Response::class.java.getDeclaredMethod("send", OutputStream::class.java)
            send.isAccessible = true
            send.invoke(response, output)
        } finally { response.close() }
        val wire = output.toByteArray().toString(Charsets.ISO_8859_1)
        val head = wire.substringBefore("\r\n\r\n")
        val lines = head.split("\r\n")
        return Wire(lines.first().split(' ')[1].toInt(), lines.drop(1).associate {
            it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
        }, wire.substringAfter("\r\n\r\n", ""))
    }
    private fun session(id: String, method: NanoHTTPD.Method, range: String?): NanoHTTPD.IHTTPSession =
        Proxy.newProxyInstance(NanoHTTPD.IHTTPSession::class.java.classLoader,
            arrayOf(NanoHTTPD.IHTTPSession::class.java)) { _, called, _ ->
            when (called.name) {
                "getMethod" -> method
                "getUri" -> "/local/$id"
                "getHeaders" -> if (range == null) emptyMap<String, String>() else mapOf("range" to range)
                "getParameters" -> emptyMap<String, List<String>>()
                else -> error("Unexpected HTTP session operation: ${called.name}")
            }
        } as NanoHTTPD.IHTTPSession
}
