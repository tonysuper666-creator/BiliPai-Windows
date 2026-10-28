package com.bilipai.desktop.network

import com.android.purebilibili.core.network.FORCE_COOKIE_HEADER
import com.android.purebilibili.core.plugin.feed.createPublicContentHttpClient
import com.android.purebilibili.core.plugin.feed.sanitizePublicContentUrl
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.cache.DesktopMediaOriginHeaders
import com.bilipai.desktop.player.cache.origin
import com.sun.net.httpserver.HttpServer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopCredentialBoundaryTest {
    private data class Received(val uri: String, val headers: Map<String, List<String>>)

    private class Endpoint : AutoCloseable {
        val received = LinkedBlockingQueue<Received>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                received.add(Received(exchange.requestURI.toASCIIString(),
                    exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.toList() }))
                if (exchange.requestURI.path == "/redirect") {
                    exchange.responseHeaders.add("Location",
                        "http://third-party.example:${address.port}/final?id=42&%61ccess_token=redirect-secret")
                    exchange.sendResponseHeaders(302, -1)
                } else exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
        fun url(host: String, path: String = "/"): String = "http://$host:${server.address.port}$path"
        fun take(): Received = assertNotNull(received.poll(10, TimeUnit.SECONDS))
        override fun close() = server.stop(0)
    }

    private fun request(url: String) = Request.Builder().url(url)
        .header(FORCE_COOKIE_HEADER, "SESSDATA=fixture-session")
        .header("Authorization", "Bearer fixture-token")
        .header("Access-Token", "fixture-token")
        .header("Referer", "https://www.bilibili.com/?access_key=fixture-key")
        .build()

    private fun assertAnonymous(received: Received, referer: String? = null) {
        for (name in listOf("cookie", "authorization", "access-token", "x-bilipai-force-cookie")) {
            assertNull(received.headers[name], name)
        }
        assertEquals(referer?.let(::listOf), received.headers["referer"])
        assertFalse(received.uri.contains("secret"), received.uri)
    }

    @Test
    fun `encoded credential aliases are removed while public query bytes survive`() {
        val url = ("https://user:password@example.com/?%61ccess_token=secret&x=a%20b&x=a+b" +
            "&flag&empty=&%53ESSDATA=secret&%62ili_jct=secret&Access%2DToken=secret&tail=%2f").toHttpUrl()
        assertEquals("https://example.com/?x=a%20b&x=a+b&flag&empty=&tail=%2f", sanitizePublicContentUrl(url).toString())
        assertEquals("https://example.com/", sanitizePublicContentUrl("https://example.com/?%61ccess_token=x".toHttpUrl()).toString())
    }

    @Test
    fun `repository sends explicit credentials only to Bilibili domains on the wire`() {
        Endpoint().use { endpoint ->
            val repository = DesktopRepository(DesktopSessionStore(Path.of("unused-credential-test"), persistent = false))
            val client = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY)
                .dns { listOf(InetAddress.getByName("127.0.0.1")) }.build()
            try {
                client.newCall(request(endpoint.url("api.bilibili.com", "/?access_key=fixture-key"))).execute().use {
                    assertEquals(204, it.code)
                }
                val own = endpoint.take()
                assertEquals(listOf("SESSDATA=fixture-session"), own.headers["cookie"])
                assertEquals(listOf("Bearer fixture-token"), own.headers["authorization"])
                assertEquals("/?access_key=fixture-key", own.uri)
                for (host in listOf("third-party.example", "bilibili.com.evil.example", "evilbilibili.com")) {
                    client.newCall(request(endpoint.url(host, "/?id=42&%61ccess_token=secret"))).execute().close()
                    val foreign = endpoint.take()
                    assertAnonymous(foreign, "https://www.bilibili.com/")
                    assertEquals("/?id=42", foreign.uri)
                }
                client.newCall(request(endpoint.url("api.bilibili.com", "/redirect"))).execute().close()
                assertNotNull(endpoint.take().headers["cookie"])
                val redirected = endpoint.take()
                assertAnonymous(redirected, "https://www.bilibili.com/")
                assertEquals("/final?id=42", redirected.uri)
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun mediaClient() = DesktopRepository(DesktopSessionStore(Path.of("unused-media-test"), persistent = false))
        .httpClient.newBuilder().proxy(Proxy.NO_PROXY)
        .dns { listOf(InetAddress.getByName("127.0.0.1")) }
        // Deliberately overbroad fixture jar proves the final boundary, independently of jar scoping.
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().hostOnlyDomain(url.host)
                .name("SESSDATA").value("fixture-jar-session").build())
        }).build()

    @Test
    fun `Akamai UPOS signature and clean referer survive without account credentials`() {
        Endpoint().use { endpoint ->
            val client = mediaClient()
            try {
                val signed = "/video?deadline=1&mid=7&buvid=X%2bY&upsig=s&uparams=deadline,mid,buvid"
                val url = endpoint.url("upos-hz-mirrorakam.akamaized.net", signed).toHttpUrl()
                val media = DesktopMediaOriginHeaders(mapOf("Referer" to "https://www.bilibili.com",
                    "Authorization" to "Bearer fixture-account", "Cookie" to "SESSDATA=fixture-media-session"), setOf(url.origin()))
                val original = Request.Builder().url(url).tag(DesktopMediaOriginHeaders::class.java, media).build()
                client.newCall(original).execute().close()
                val safe = endpoint.take()
                assertEquals(signed, safe.uri)
                assertEquals(listOf("https://www.bilibili.com"), safe.headers["referer"])
                assertNull(safe.headers["cookie"])
                assertNull(safe.headers["authorization"])
                client.newCall(original.newBuilder().url(url.toString() + "&%61ccess_key=secret&SESSDATA=secret&csrf=secret&buvid3=secret")
                    .header(FORCE_COOKIE_HEADER, "SESSDATA=fixture-forced-session").build()).execute().close()
                val stripped = endpoint.take()
                assertEquals(signed, stripped.uri)
                assertNull(stripped.headers["cookie"])
                assertNull(stripped.headers["authorization"])
                for (host in listOf("other-tenant.akamaized.net", "upos-hz-mirrorakam.akamaized.net.evil.example")) {
                    client.newCall(original.newBuilder().url(endpoint.url(host, signed)).build()).execute().close()
                    val foreign = endpoint.take()
                    assertFalse(foreign.uri.contains("buvid=X"))
                    assertNull(foreign.headers["cookie"])
                    assertNull(foreign.headers["authorization"])
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test
    fun `plugin same origin authentication survives but account data and cross origin credentials do not`() {
        Endpoint().use { endpoint ->
            val client = mediaClient()
            try {
                val url = endpoint.url("plugin-media.example", "/v.mp4?%61ccess_token=plugin-token&%61ccess_key=secret&SESSDATA=secret&bili_jct=secret&csrf=secret&buvid4=secret").toHttpUrl()
                val headers = mapOf("Authorization" to "Bearer plugin-token", "Referer" to "https://plugin-media.example/player",
                    "X-Plugin-Key" to "plugin-key", "X-CSRF-Token" to "fixture-account-csrf")
                val media = DesktopMediaOriginHeaders(headers, setOf(url.origin()))
                val original = Request.Builder().url(url).tag(DesktopMediaOriginHeaders::class.java, media).build()
                for (forced in listOf(false, true)) {
                    val request = original.newBuilder().apply {
                        if (forced) header(FORCE_COOKIE_HEADER, "SESSDATA=fixture-forced-session")
                    }.build()
                    client.newCall(request).execute().close()
                    val own = endpoint.take()
                    assertEquals("/v.mp4?%61ccess_token=plugin-token", own.uri)
                    assertEquals(listOf("Bearer plugin-token"), own.headers["authorization"])
                    assertEquals(listOf("https://plugin-media.example/player"), own.headers["referer"])
                    assertEquals(listOf("plugin-key"), own.headers["x-plugin-key"])
                    assertNull(own.headers["cookie"])
                    assertNull(own.headers["x-csrf-token"])
                }
                val unsafeReferer = DesktopMediaOriginHeaders(headers + ("Referer" to "https://user:password@plugin-media.example/player?access_token=secret"), setOf(url.origin()))
                client.newCall(original.newBuilder().tag(DesktopMediaOriginHeaders::class.java, unsafeReferer).build()).execute().close()
                assertEquals(listOf("https://plugin-media.example/"), endpoint.take().headers["referer"])
                client.newCall(original.newBuilder().url(endpoint.url("plugin-media.example", "/redirect")).build()).execute().close()
                assertEquals(listOf("Bearer plugin-token"), endpoint.take().headers["authorization"])
                val foreign = endpoint.take()
                assertEquals("/final?id=42", foreign.uri)
                assertNull(foreign.headers["authorization"])
                assertNull(foreign.headers["cookie"])
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test
    fun `public client strips aliases again after a redirect`() {
        Endpoint().use { endpoint ->
            val client = createPublicContentHttpClient().newBuilder().proxy(Proxy.NO_PROXY)
                .dns { listOf(InetAddress.getByName("127.0.0.1")) }.build()
            try {
                client.newCall(request(endpoint.url("api.bilibili.com", "/redirect?%61ccess_token=secret"))).execute().close()
                assertAnonymous(endpoint.take())
                val redirected = endpoint.take()
                assertAnonymous(redirected)
                assertEquals("/final?id=42", redirected.uri)
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }
}
