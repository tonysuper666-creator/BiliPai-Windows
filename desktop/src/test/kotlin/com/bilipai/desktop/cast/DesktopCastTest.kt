package com.bilipai.desktop.cast

import com.android.purebilibili.core.plugin.CastPluginMediaRequest
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.buildTvCastPlayUrlParams
import com.android.purebilibili.feature.cast.*
import com.android.purebilibili.feature.plugin.dlna.DlnaCastPlugin
import com.bilipai.desktop.data.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** All sockets bind loopback/ephemeral ports. No account jar, TV, or public endpoint is used. */
class DesktopCastTest {
    @Test fun originalSsdpTargetsAndNotifyParsingArePreserved() {
        val messages = SsdpDiscovery.resolveSsdpSearchPayloads()
        assertEquals(4, messages.size)
        assertTrue(messages.all { it.startsWith("M-SEARCH * HTTP/1.1\r\n") && it.contains("MAN: \"ssdp:discover\"") })
        val alive = "NOTIFY * HTTP/1.1\r\nlocation: http://127.0.0.1/device.xml\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:1\r\nNTS: ssdp:alive\r\n\r\n"
        assertEquals("http://127.0.0.1/device.xml", SsdpDiscovery.parseResponse(alive)?.usn)
        assertNull(SsdpDiscovery.parseResponse(alive.replace("ssdp:alive", "ssdp:byebye")))
    }

    @Test fun embeddedRendererAndSecureXmlUseOriginalParser() {
        val xml = "<root><device><friendlyName>Root</friendlyName><deviceList>${rendererXml("Living &amp; Room", "../control")}</deviceList></device></root>"
        val profile = assertNotNull(SsdpCastClient.parseDeviceProfile(xml, "http://127.0.0.1:444/devices/description.xml"))
        assertEquals("Living & Room", profile.friendlyName)
        assertEquals("http://127.0.0.1:444/control", profile.avTransportEndpoint?.controlUrl)
        assertNull(SsdpCastClient.parseDeviceProfile("<!DOCTYPE root [<!ENTITY xxe SYSTEM 'file:///does-not-exist'>]><root>&xxe;</root>", "http://127.0.0.1/description.xml"))
    }

    @Test fun realUdpDiscoveryReceivesAndDeduplicatesFixtureResponses(): Unit = runBlocking {
        CastFixture().use { fixture ->
            val devices = SsdpDiscovery.discover(fixture.context, 450)
            assertEquals(1, devices.size)
            assertEquals(fixture.url("/description.xml"), devices.single().location)
            val targets = fixture.searches.map { message ->
                message.lineSequence().first { it.startsWith("ST:", ignoreCase = true) }
                    .substringAfter(':').trim()
            }.toSet()
            assertEquals(4, targets.size)
            assertEquals(SsdpDiscovery.resolveSsdpSearchPayloads().map { message ->
                message.lineSequence().first { it.startsWith("ST:", ignoreCase = true) }
                    .substringAfter(':').trim()
            }.toSet(), targets)
            assertEquals("Loopback Renderer", SsdpCastClient.fetchDeviceProfile(devices.single())?.friendlyName)
        }
    }

    @Test fun cancellationInterruptsUdpReceiveInsteadOfWaitingForScanWindow(): Unit = runBlocking {
        CastFixture().use { fixture ->
            val scan = launch(Dispatchers.IO) { SsdpDiscovery.discover(fixture.context, 8_000) }
            withTimeout(2_000) { while (fixture.searches.isEmpty()) delay(10) }
            withTimeout(1_000) { scan.cancelAndJoin() }
        }
    }

    @Test fun originalPluginActuallyDiscoversCastsAndControlsSoap(): Unit = runBlocking {
        CastFixture().use { fixture ->
            val plugin = DlnaCastPlugin()
            val controller = DesktopCastController(fixture.context, plugin)
            try {
                controller.startDiscovery()
                val route = withTimeout(12_000) { controller.routes.first { it.isNotEmpty() } }.single()
                val media = CastPluginMediaRequest(fixture.url("/media"), "A & B <title>", "Creator", LocalProxyServer.DASH_CONTENT_TYPE, 9_000)
                controller.cast(route, media).getOrThrow()
                withTimeout(3_000) { controller.playbackState.first { it.durationMs == 60_000L } }
                assertTrue(controller.playbackState.value.isActive)
                assertTrue(fixture.actions.containsAll(listOf("SetAVTransportURI", "Seek", "Play", "GetTransportInfo", "GetPositionInfo")))
                val uri = fixture.envelopes.first { it.contains("<u:SetAVTransportURI") }
                assertTrue(uri.contains("A &amp;amp; B &amp;lt;title&amp;gt;"))
                assertTrue(uri.contains("application/dash+xml"))
                controller.pause().getOrThrow(); assertFalse(controller.playbackState.value.isPlaying)
                controller.seek(21_000).getOrThrow()
                assertTrue(fixture.envelopes.any { it.contains("<Target>00:00:21</Target>") })
                controller.play().getOrThrow(); controller.stop().getOrThrow()
                assertFalse(controller.playbackState.value.isActive)
                assertTrue(fixture.actions.containsAll(listOf("Pause", "Stop")))
            } finally { controller.quiesce() }
        }
    }

    @Test fun cancellationInterruptsStalledSoapRequest(): Unit = runBlocking {
        CastFixture().use { fixture ->
            fixture.stallSetUri.set(true)
            val device = SsdpDiscovery.SsdpDevice(fixture.url("/description.xml"), "fixture", "uuid:fixture", "MediaRenderer")
            val cast = async(Dispatchers.IO) { SsdpCastClient.cast(device, fixture.url("/media"), "title", "creator") }
            withTimeout(2_000) { while (!fixture.actions.contains("SetAVTransportURI")) delay(10) }
            withTimeout(1_000) { cast.cancelAndJoin() }
            assertTrue(cast.isCancelled)
        }
    }

    @Test fun proxyPreservesRangeHeadCorsAndRejectsUnregisteredTargets() {
        CastFixture().use { fixture ->
            LocalProxyServer.ensureStarted()
            val proxy = LocalProxyServer.getProxyUrl(fixture.context, fixture.url("/media"))
            val client = OkHttpClient()
            client.newCall(Request.Builder().url(proxy).header("Range", "bytes=2-5").build()).execute().use {
                assertEquals(206, it.code); assertEquals("cdef", it.body.string())
                assertEquals("bytes 2-5/10", it.header("Content-Range")); assertEquals("bytes", it.header("Accept-Ranges"))
                assertEquals("*", it.header("Access-Control-Allow-Origin"))
                assertEquals("4", it.header("Content-Length")); assertNull(it.header("Transfer-Encoding"))
            }
            assertEquals("bytes=2-5", fixture.lastRange)
            assertEquals("https://www.bilibili.com", fixture.lastReferer)
            assertNull(fixture.lastCookie)
            client.newCall(Request.Builder().url(proxy).head().build()).execute().use {
                assertEquals(200, it.code); assertEquals("", it.body.string())
                assertEquals("10", it.header("Content-Length")); assertNull(it.header("Transfer-Encoding"))
                assertEquals("keep-alive", it.header("Connection"))
            }
            assertEquals("HEAD", fixture.lastMethod)
            client.newCall(Request.Builder().url(proxy).method("OPTIONS", null).build()).execute().use {
                assertEquals(200, it.code); assertTrue(it.header("Access-Control-Allow-Methods").orEmpty().contains("HEAD"))
            }
            val forbidden = proxy.substringBefore("?url=") + "?url=" + java.net.URLEncoder.encode(fixture.url("/unexpected"), "UTF-8")
            client.newCall(Request.Builder().url(forbidden).build()).execute().use { assertEquals(400, it.code) }
            assertEquals(0, fixture.unexpectedRequests)
        }
    }

    @Test fun manifestAndErrorHeadDoNotWriteBodyOrGzipIntoPersistentConnection() {
        CastFixture().use { fixture ->
            val manifest = "<MPD>loopback manifest</MPD>"
            val url = LocalProxyServer.registerDashManifest(fixture.context, manifest)
            val client = OkHttpClient()
            client.newCall(Request.Builder().url(url).header("Accept-Encoding", "gzip").head().build()).execute().use {
                assertEquals(200, it.code); assertEquals("", it.body.string())
                assertEquals(manifest.toByteArray().size.toString(), it.header("Content-Length"))
                assertNull(it.header("Content-Encoding")); assertNull(it.header("Transfer-Encoding"))
            }
            client.newCall(Request.Builder().url(url.substringBefore("/dash/") + "/missing").header("Accept-Encoding", "gzip").head().build()).execute().use {
                assertEquals(404, it.code); assertEquals("", it.body.string())
                assertNull(it.header("Content-Encoding")); assertNull(it.header("Transfer-Encoding"))
            }
            client.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(200, it.code); assertEquals(manifest, it.body.string())
            }
        }
    }

    @Test fun castManifestKeepsVideoAudioAndSegmentIndex() {
        CastFixture().use { fixture ->
            val dash = Dash(60, 1.5f, listOf(DashVideo(80, fixture.url("/media?kind=video"), bandwidth = 900,
                mimeType = "video/mp4", codecs = "avc1.640028", segmentBase = SegmentBase("0-99", "100-199"))),
                listOf(DashAudio(30280, fixture.url("/media?kind=audio"), bandwidth = 200, mimeType = "audio/mp4", codecs = "mp4a.40.2")))
            val source = PlaybackSource(fixture.url("/media?kind=video"), fixture.url("/media?kind=audio"), "test", "https://www.bilibili.com", cachedDashData = dash)
            val media = resolveDesktopCastMedia(fixture.context, "title", "creator", source, null, 60_000, 1_234, true)
            assertEquals(LocalProxyServer.DASH_CONTENT_TYPE, media.contentType); assertEquals(1_234L, media.startPositionMs)
            OkHttpClient().newCall(Request.Builder().url(media.url).build()).execute().use {
                assertEquals(200, it.code)
                val xml = it.body.string()
                assertTrue(xml.contains("contentType=\"video\"")); assertTrue(xml.contains("contentType=\"audio\""))
                assertTrue(xml.contains("indexRange=\"100-199\"")); assertTrue(xml.contains("Initialization range=\"0-99\""))
                assertTrue(xml.contains("/proxy?url=")); assertFalse(xml.contains("Cookie"))
            }
        }
    }

    @Test fun splitTracksAndProgressiveSegmentsNeverSilentlyTruncate() {
        CastFixture().use { fixture ->
            val split = PlaybackSource(fixture.url("/media"), fixture.url("/audio"), "test", "referer")
            assertFailsWith<IllegalStateException> { resolveDesktopCastMedia(fixture.context, "title", "author", split, null, 0, 0, true) }
            val segments = PlayUrlData(durl = listOf(Durl(url = fixture.url("/first")), Durl(url = fixture.url("/second"))))
            assertFailsWith<IllegalStateException> { resolveDesktopCastMedia(fixture.context, "title", "author", split.copy(audioUrl = null), segments, 0, 0, true) }
        }
    }

    @Test fun tvParametersKeepOriginalAccessKeyAndQualityDefaults() {
        val params = buildTvCastPlayUrlParams(7, 9, 0, "test-token")
        assertEquals("80", params["qn"]); assertEquals("test-token", params["access_key"])
        assertEquals("test-token", params["mobile_access_key"]); assertEquals("7", params["object_id"])
        assertEquals("1", params["is_proj"]); assertEquals("0", params["protocol"])
    }
}

private fun rendererXml(name: String, control: String): String = """
    <device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>$name</friendlyName><modelName>Fixture</modelName>
    <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>$control</controlURL></service></serviceList></device>
""".trimIndent()

private class CastFixture : AutoCloseable {
    private val root = Files.createTempDirectory("bp-cast-")
    val context = DesktopPluginContext(DesktopPluginStore(root))
    private val threads = Executors.newCachedThreadPool { task -> Thread(task, "cast-loopback-fixture").apply { isDaemon = true } }
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { executor = threads }
    private val udp = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
    private val running = AtomicBoolean(true)
    val searches = CopyOnWriteArrayList<String>()
    val actions = CopyOnWriteArrayList<String>()
    val envelopes = CopyOnWriteArrayList<String>()
    val stallSetUri = AtomicBoolean(false)
    private val releaseStall = CountDownLatch(1)
    @Volatile var lastRange: String? = null
    @Volatile var lastReferer: String? = null
    @Volatile var lastCookie: String? = null
    @Volatile var lastMethod: String? = null
    @Volatile var unexpectedRequests = 0
    fun url(path: String) = "http://127.0.0.1:${http.address.port}$path"
    init {
        http.createContext("/") { exchange ->
            try { handle(exchange) } finally { exchange.close() }
        }
        http.start()
        DesktopCastNetwork.configureForFixture(context, InetSocketAddress(InetAddress.getByName("127.0.0.1"), udp.localPort))
        threads.submit {
            while (running.get()) {
                try {
                    val packet = DatagramPacket(ByteArray(4096), 4096); udp.receive(packet)
                    searches += String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val payload = "HTTP/1.1 200 OK\r\nLOCATION: ${url("/description.xml")}\r\nSERVER: fixture\r\nUSN: uuid:fixture\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n".toByteArray()
                    repeat(2) { udp.send(DatagramPacket(payload, payload.size, packet.socketAddress)) }
                } catch (_: java.net.SocketException) { if (running.get()) throw AssertionError("Fixture UDP failed") }
            }
        }
    }
    private fun handle(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/description.xml" -> reply(exchange, 200, "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">${rendererXml("Loopback Renderer", "/control")}</root>", "text/xml")
            "/control" -> {
                val action = exchange.requestHeaders.getFirst("SOAPACTION").orEmpty().substringAfter('#').trim('"')
                actions += action; envelopes += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                if (action == "SetAVTransportURI" && stallSetUri.get()) releaseStall.await(4, TimeUnit.SECONDS)
                val fields = when (action) {
                    "GetTransportInfo" -> "<CurrentTransportState>PLAYING</CurrentTransportState>"
                    "GetPositionInfo" -> "<RelTime>00:00:09</RelTime><TrackDuration>00:01:00</TrackDuration>"
                    else -> ""
                }
                reply(exchange, 200, "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">$fields</u:${action}Response></s:Body></s:Envelope>", "text/xml")
            }
            "/media" -> {
                lastMethod = exchange.requestMethod
                lastRange = exchange.requestHeaders.getFirst("Range"); lastReferer = exchange.requestHeaders.getFirst("Referer"); lastCookie = exchange.requestHeaders.getFirst("Cookie")
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                if (lastRange == "bytes=2-5") { exchange.responseHeaders.add("Content-Range", "bytes 2-5/10"); reply(exchange, 206, "cdef", "video/mp4") }
                else reply(exchange, 200, "abcdefghij", "video/mp4")
            }
            else -> { unexpectedRequests++; reply(exchange, 404, "Unexpected fixture route", "text/plain") }
        }
    }
    private fun reply(exchange: HttpExchange, code: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(); exchange.responseHeaders.add("Content-Type", contentType)
        if (exchange.requestMethod == "HEAD") {
            exchange.responseHeaders.add("Content-Length", bytes.size.toString())
            exchange.sendResponseHeaders(code, -1L)
        } else {
            exchange.sendResponseHeaders(code, bytes.size.toLong()); exchange.responseBody.write(bytes)
        }
    }
    override fun close() {
        running.set(false); releaseStall.countDown(); udp.close()
        SsdpCastClient.clearPlaybackSession(); LocalProxyServer.stopAndClear(); http.stop(0)
        threads.shutdownNow(); threads.awaitTermination(2, TimeUnit.SECONDS)
        DesktopCastNetwork.clearFixture(context)
        // Only this fixture's directory; no updater, installed app, or account path is enumerated.
        Files.walk(root).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
