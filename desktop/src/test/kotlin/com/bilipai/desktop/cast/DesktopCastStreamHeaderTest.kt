package com.bilipai.desktop.cast

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.cast.LocalProxyServer
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.PlaybackSegment
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import com.android.purebilibili.feature.plugin.dlna.DlnaCastPlugin
import kotlin.test.*

/** Real HTTP relay traffic, with synthetic credentials and only task-owned loopback targets. */
class DesktopCastStreamHeaderTest {
    @Test fun allPreparationLeasesMustFinishBeforeTheRelayOrCredentialsCanBeCleared() {
        HeaderFixture().use { fixture ->
            val first = DesktopCastProxySessions.acquirePreparation()
            val second = DesktopCastProxySessions.acquirePreparation()
            try {
                val media = fixture.register("/media", mapOf("Authorization" to "Bearer fixture"))
                LocalProxyServer.stopAndClear(); fixture.get(media)
                first.close(); first.close()
                assertEquals(1, DesktopCastProxySessions.pendingPreparations.value)
                LocalProxyServer.stopAndClear(); fixture.get(media)
                second.close()
                LocalProxyServer.stopAndClear()
                val current = fixture.register("/media", emptyMap())
                fixture.get(current.substringBefore("?target=") + "?target=" + media.substringAfter("?target="), expectedCode = 400)
                assertEquals(2, fixture.requests.size)
            } finally { first.close(); second.close() }
        }
    }

    @Test fun dlnaShutdownCannotStopAnActiveOrBusyGoogleReceiver() = runBlocking {
        HeaderFixture().use { fixture ->
            val active = AtomicBoolean(true)
            val busy = AtomicBoolean(false)
            val consumer = DesktopCastProxySessions.registerConsumer { active.get() || busy.get() }
            try {
                val media = fixture.register("/media", mapOf("Authorization" to "Bearer fixture"))
                DesktopCastController(fixture.context, DlnaCastPlugin()).quiesce()
                fixture.get(media)
                busy.set(true); active.set(false)
                LocalProxyServer.stopAndClear(); fixture.get(media)
                busy.set(false); LocalProxyServer.stopAndClear()
                val current = fixture.register("/media", emptyMap())
                fixture.get(current.substringBefore("?target=") + "?target=" + media.substringAfter("?target="), expectedCode = 400)
                assertEquals(2, fixture.requests.size)
            } finally { consumer.close() }
        }
    }

    @Test fun ordinaryCastUsesCurrentNativeHeadersInsteadOfReplacingThemWithCachedLegacyValues() {
        HeaderFixture().use { fixture ->
            val cached = com.bilipai.desktop.data.PlaybackSource(fixture.url("/media"), null, "Fixture", "https://cached.invalid/", cookieHeader = "cached=old")
            val current = PlaybackSource(fixture.url("/media"), streamHeaders = mapOf("Cookie" to "", "Authorization" to "Bearer fixture", "Referer" to "https://current.invalid/"))
            fixture.get(resolveDesktopCastMedia(fixture.context, "Fixture", "", cached, null, 0, 0, true, current).url)
            assertNull(fixture.requests.single().headers["cookie"])
            assertEquals(listOf("Bearer fixture"), fixture.requests.single().headers["authorization"])
            assertEquals(listOf("https://current.invalid/"), fixture.requests.single().headers["referer"])
        }
    }

    @Test fun nativeOverridesAreExactPrivateAndReceiverHeadersCannotReplaceThem() {
        HeaderFixture().use { fixture ->
            val source = PlaybackSource(fixture.url("/media"), referer = "https://base.invalid/", userAgent = "BaseFixture",
                cookieHeader = "fixture_legacy=old", streamHeaders = linkedMapOf("rEfErEr" to "https://plugin.invalid/view", "USER-agent" to "PluginFixture/4",
                    "Cookie" to "", "Authorization" to "Bearer fixture-token", "X-Plugin-Key" to "fixture-key,a=b"))
            val media = resolveDesktopNativeCastMedia(fixture.context, source, "", "video/mp4", 1_234, true)
            assertTrue(media.url.contains("/proxy?target=")); assertFalse(media.url.contains("/media"))
            listOf("fixture-token", "fixture-key", "Cookie", "Authorization", "plugin.invalid", "fixture_legacy").forEach { assertFalse(media.url.contains(it)) }
            fixture.get(media.url, mapOf("Authorization" to "malicious-receiver", "Referer" to "https://receiver.invalid/", "Cookie" to "receiver=bad"))
            val headers = fixture.requests.single().headers
            assertEquals(listOf("https://plugin.invalid/view"), headers["referer"])
            assertEquals(listOf("PluginFixture/4"), headers["user-agent"])
            assertEquals(listOf("Bearer fixture-token"), headers["authorization"])
            assertEquals(listOf("fixture-key,a=b"), headers["x-plugin-key"])
            assertFalse(headers.containsKey("cookie"))
        }
    }

    @Test fun nativeLegacyCookieRefererAndUserAgentAreRetainedWithoutAnAccountJar() {
        HeaderFixture().use { fixture ->
            val source = PlaybackSource(fixture.url("/media"), cookieHeader = "fixture_legacy=only", referer = "https://fixture.invalid/base", userAgent = "Fixture-Legacy-UA")
            val media = resolveDesktopNativeCastMedia(fixture.context, source, "", "video/mp4", 0, true)
            fixture.get(media.url)
            val headers = fixture.requests.single().headers
            assertEquals(listOf("fixture_legacy=only"), headers["cookie"])
            assertEquals(listOf("https://fixture.invalid/base"), headers["referer"])
            assertEquals(listOf("Fixture-Legacy-UA"), headers["user-agent"])
        }
    }

    @Test fun explicitEmptyHeadersRemoveBridgeDefaultsAndLegacyCookie() {
        HeaderFixture().use { fixture ->
            val source = PlaybackSource(fixture.url("/media"), cookieHeader = "fixture=old", streamHeaders = mapOf("Cookie" to "", "User-Agent" to "", "Referer" to ""))
            fixture.get(resolveDesktopNativeCastMedia(fixture.context, source, "", "video/mp4", 0, true).url)
            val headers = fixture.requests.single().headers
            assertTrue(listOf("cookie", "user-agent", "referer").none(headers::containsKey))
        }
    }

    @Test fun sameUrlRegistrationsHaveIndependentImmutableHeadersAndStopRevokesAll() {
        HeaderFixture().use { fixture ->
            val values = linkedMapOf("Authorization" to "Bearer fixture-first", "Cookie" to "fixture=first")
            val first = fixture.register("/media", values)
            values["Authorization"] = "Bearer mutated-input"
            val second = fixture.register("/media", mapOf("Cookie" to "", "Authorization" to "Bearer fixture-second"))
            assertNotEquals(first, second)
            fixture.get(first); fixture.get(second); fixture.get(first)
            assertEquals(listOf("Bearer fixture-first", "Bearer fixture-second", "Bearer fixture-first"), fixture.requests.map { it.headers["authorization"]?.single() })
            assertNull(fixture.requests[1].headers["cookie"])
            LocalProxyServer.stopAndClear(); LocalProxyServer.ensureStarted()
            // Restart may acquire another ephemeral port: retain only the old opaque id.
            val current = fixture.register("/media", emptyMap())
            val revoked = current.substringBefore("?target=") + "?target=" + first.substringAfter("?target=")
            fixture.get(revoked, expectedCode = 400)
            assertEquals(3, fixture.requests.size)
        }
    }

    @Test fun sameOriginRedirectKeepsTheRegisteredHeaders() {
        HeaderFixture().use { fixture ->
            fixture.get(fixture.register("/same-redirect", mapOf("Authorization" to "Bearer fixture", "Cookie" to "fixture=value", "X-Plugin-Key" to "key")))
            assertEquals(2, fixture.requests.size)
            fixture.requests.forEach { assertEquals(listOf("Bearer fixture"), it.headers["authorization"]); assertEquals(listOf("fixture=value"), it.headers["cookie"]) }
        }
    }

    @Test fun crossOriginRedirectCannotCarryRegisteredCredentialsOrPluginHeaders() {
        HeaderFixture().use { fixture ->
            fixture.get(fixture.register("/cross-redirect", mapOf("Authorization" to "Bearer fixture", "Cookie" to "fixture=value", "X-Plugin-Key" to "key",
                "Referer" to "https://plugin.invalid/", "User-Agent" to "Fixture-Explicit-UA")))
            assertEquals(2, fixture.requests.size)
            assertEquals(listOf("Bearer fixture"), fixture.requests[0].headers["authorization"])
            assertTrue(listOf("authorization", "cookie", "x-plugin-key", "referer", "user-agent").none(fixture.requests[1].headers::containsKey))
        }
    }

    @Test fun authenticatedRangeHeadAndFollowingGetUseOneUnpollutedConnection() {
        HeaderFixture().use { fixture ->
            val proxy = fixture.register("/media", mapOf("Authorization" to "Bearer fixture", "Range" to "bytes=0-1"))
            fixture.get(proxy, mapOf("Range" to "bytes=2-5"), expectedCode = 206, expectedBody = "cdef")
            assertEquals(listOf("bytes=2-5"), fixture.requests[0].headers["range"])
            // Use a registration without a fixed range for HEAD and keepalive GET.
            val full = fixture.register("/media", mapOf("Authorization" to "Bearer fixture"))
            fixture.client.newCall(Request.Builder().url(full).head().build()).execute().use {
                assertEquals(200, it.code); assertEquals("10", it.header("Content-Length")); assertEquals("", it.body.string())
            }
            fixture.get(full)
            assertEquals("HEAD", fixture.requests[1].method)
            assertTrue(fixture.requests.all { it.headers["authorization"] == listOf("Bearer fixture") })
        }
    }

    @Test fun bothDashTracksCarryRegisteredHeadersButTheManifestContainsNoCredentials() {
        HeaderFixture().use { fixture ->
            val dash = Dash(60, 1.5f, listOf(DashVideo(80, fixture.url("/media?kind=video"), bandwidth = 900, mimeType = "video/mp4", codecs = "avc1.640028")),
                listOf(DashAudio(30280, fixture.url("/media?kind=audio"), bandwidth = 200, mimeType = "audio/mp4", codecs = "mp4a.40.2")))
            LocalProxyServer.ensureStarted()
            val manifestUrl = buildDesktopCastDash(fixture.context, dash, 80, 60_000, mapOf("Authorization" to "Bearer fixture", "X-Plugin-Key" to "secret-fixture"))
            val xml = fixture.client.newCall(Request.Builder().url(manifestUrl).build()).execute().use { assertEquals(200, it.code); it.body.string() }
            listOf("Bearer", "secret-fixture", "Authorization", "kind=video", "kind=audio").forEach { assertFalse(xml.contains(it)) }
            val urls = Regex("<BaseURL>(.*?)</BaseURL>").findAll(xml).map { it.groupValues[1].replace("&amp;", "&") }.toList()
            assertEquals(2, urls.size); urls.forEach { fixture.get(it) }
            assertEquals(setOf("kind=video", "kind=audio"), fixture.requests.map { it.query }.toSet())
            assertTrue(fixture.requests.all { it.headers["authorization"] == listOf("Bearer fixture") && it.headers["x-plugin-key"] == listOf("secret-fixture") })
        }
    }

    @Test fun registrationRejectsHeaderInjectionAndTransportOverridesWithoutPrintingValues() {
        assertFailsWith<IllegalArgumentException> { DesktopCastProxySessions.register("http://127.0.0.1/media", mapOf("X-Fixture" to "secret\r\nCookie: leak")) }
        assertFailsWith<IllegalArgumentException> { DesktopCastProxySessions.register("http://127.0.0.1/media", mapOf("Host" to "private-fixture.invalid")) }
        assertFailsWith<IllegalArgumentException> { DesktopCastProxySessions.register("http://user:secret@127.0.0.1/media", emptyMap()) }
        val id = DesktopCastProxySessions.register("http://127.0.0.1/media?private=fixture", mapOf("Authorization" to "Bearer secret"))
        val label = assertNotNull(DesktopCastProxySessions.find(id)).toString()
        assertFalse(label.contains("secret")); assertFalse(label.contains("private=fixture"))
        DesktopCastProxySessions.clear()
    }

    @Test fun nativeSplitTracksAndProgressiveSegmentsCannotBeTruncated() {
        HeaderFixture().use { fixture ->
            val source = PlaybackSource(fixture.url("/media"), audioUrl = fixture.url("/audio"))
            assertFailsWith<IllegalStateException> { resolveDesktopNativeCastMedia(fixture.context, source, "", "video/mp4", 0, true) }
            assertFailsWith<IllegalStateException> { resolveDesktopNativeCastMedia(fixture.context, source.copy(audioUrl = null,
                progressiveSegments = listOf(PlaybackSegment(fixture.url("/first")), PlaybackSegment(fixture.url("/second")))), "", "video/mp4", 0, true) }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun ordinaryVideoSourceRetainsItsAuthorizedCookiesAndReferer() {
        HeaderFixture().use { fixture ->
            val source = com.bilipai.desktop.data.PlaybackSource(fixture.url("/media"), null, "Fixture", "https://www.bilibili.com/video/BVfixture", cookieHeader = "fixture=ordinary")
            fixture.get(resolveDesktopCastMedia(fixture.context, "Fixture", "", source, null, 0, 0, true).url)
            assertEquals(listOf("fixture=ordinary"), fixture.requests.single().headers["cookie"])
            assertEquals(listOf(source.referer), fixture.requests.single().headers["referer"])
        }
    }
}

private data class HeaderRequest(val method: String, val query: String?, val headers: Map<String, List<String>>)

private class HeaderFixture : AutoCloseable {
    private val root = Files.createTempDirectory("bp-ch-")
    val context = DesktopPluginContext(DesktopPluginStore(root))
    val client = OkHttpClient()
    private val threads = Executors.newCachedThreadPool { Thread(it, "cast-header-loopback-fixture").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { executor = threads }
    private val foreign = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { executor = threads }
    val requests = CopyOnWriteArrayList<HeaderRequest>()
    fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"
    init {
        server.createContext("/") { handle(it, false) }; foreign.createContext("/") { handle(it, true) }
        server.start(); foreign.start()
        DesktopCastNetwork.configureForFixture(context, InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9))
    }
    fun register(path: String, headers: Map<String, String>): String {
        LocalProxyServer.ensureStarted(); return LocalProxyServer.getProxyUrl(context, url(path), headers)
    }
    fun get(url: String, headers: Map<String, String> = emptyMap(), expectedCode: Int = 200, expectedBody: String = "abcdefghij") {
        client.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use {
            assertEquals(expectedCode, it.code)
            val body = it.body.string()
            if (expectedCode == 200 || expectedCode == 206) assertEquals(expectedBody, body)
            else assertFalse(body.contains("secret"))
        }
    }
    private fun handle(exchange: HttpExchange, isForeign: Boolean) {
        try {
            val path = exchange.requestURI.path
            if (path !in setOf("/media", "/same-redirect", "/cross-redirect") || (isForeign && path != "/media")) {
                exchange.sendResponseHeaders(404, -1); return
            }
            requests += HeaderRequest(exchange.requestMethod, exchange.requestURI.rawQuery, exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.toList() })
            when (path) {
                "/same-redirect", "/cross-redirect" -> {
                    exchange.responseHeaders.add("Location", if (path == "/same-redirect") url("/media") else "http://127.0.0.1:${foreign.address.port}/media")
                    exchange.sendResponseHeaders(302, -1)
                }
                else -> {
                    val partial = exchange.requestHeaders.getFirst("Range") == "bytes=2-5"
                    val body = (if (partial) "cdef" else "abcdefghij").toByteArray()
                    exchange.responseHeaders.add("Content-Type", "video/mp4")
                    if (partial) exchange.responseHeaders.add("Content-Range", "bytes 2-5/10")
                    if (exchange.requestMethod == "HEAD") {
                        exchange.responseHeaders.add("Content-Length", body.size.toString()); exchange.sendResponseHeaders(200, -1)
                    } else { exchange.sendResponseHeaders(if (partial) 206 else 200, body.size.toLong()); exchange.responseBody.write(body) }
                }
            }
        } finally { exchange.close() }
    }
    override fun close() {
        LocalProxyServer.stopAndClear(); DesktopCastNetwork.clearFixture(context)
        server.stop(0); foreign.stop(0); threads.shutdownNow(); threads.awaitTermination(2, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
