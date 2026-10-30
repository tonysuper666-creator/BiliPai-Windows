package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.CaptchaData
import com.android.purebilibili.data.model.response.GeetestData
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class DesktopCaptchaBridgeTest {
    private val captcha = CaptchaData("test-token", GeetestData("test-gt", "test-challenge"))
    private val payload = """{"challenge":"test-challenge","validate":"test-validate","seccode":"test-seccode"}"""
    private val expected = DesktopCaptchaResult("test-challenge", "test-validate", "test-seccode")

    @Test fun callbackRejectsForeignMissingDuplicateOriginsAndReboundHost() = runBlocking {
        DesktopCaptchaCallbackSession(captcha, buildJsonObject {}).use { session ->
            val uri = session.pageUri
            val result = async(start = CoroutineStart.UNDISPATCHED) { session.awaitResult() }
            assertEquals(403, request(uri, host = listOf("attacker.invalid:${uri.port}")).status)
            for (origins in listOf(emptyList(), listOf("https://attacker.invalid"), listOf("null"),
                listOf(origin(uri), "https://attacker.invalid"), listOf(origin(uri), origin(uri)))) {
                assertEquals(404, request(uri, payload, origins = origins).status)
            }
            assertTrue(request(uri, payload, host = listOf(uri.rawAuthority, "attacker.invalid")).status >= 400)
            assertEquals(404, request(uri.resolve("${uri.rawPath}/result?ignored=1"), payload, callback = false).status)
            assertEquals(404, request(uri, payload, contentType = "text/plain").status)
            assertFalse(result.isCompleted)
            assertEquals(204, request(uri, payload).status)
            assertEquals(expected, withTimeout(5_000) { result.await() })
            assertEquals(410, request(uri, payload).status)
        }
    }

    @Test fun malformedAndOversizedCallbacksLeaveCurrentChallengeUsable() = runBlocking {
        DesktopCaptchaCallbackSession(captcha, buildJsonObject {}).use { session ->
            val result = async(start = CoroutineStart.UNDISPATCHED) { session.awaitResult() }
            for (body in listOf("{", "[]", "{}", payload.replace("\"test-validate\"", "123"),
                payload.replace("test-validate", " "), payload.replace("test-validate", "x".repeat(9_000)))) {
                assertEquals(400, request(session.pageUri, body).status)
            }
            assertFalse(result.isCompleted)
            assertEquals(204, request(session.pageUri, payload).status)
            assertEquals(expected, withTimeout(5_000) { result.await() })
        }
    }

    @Test fun simultaneousChallengesRejectEachOthersOriginAndRoute() = runBlocking {
        DesktopCaptchaCallbackSession(captcha, buildJsonObject {}).use { first ->
            DesktopCaptchaCallbackSession(captcha, buildJsonObject {}).use { second ->
                assertEquals(404, request(second.pageUri, payload, origins = listOf(origin(first.pageUri))).status)
                val staleRoute = URI("${origin(second.pageUri)}${first.pageUri.rawPath}/result")
                assertEquals(404, request(staleRoute, payload, callback = false).status)
                assertEquals(204, request(first.pageUri, payload).status)
                assertEquals(204, request(second.pageUri, payload).status)
                assertEquals(expected, first.awaitResult())
                assertEquals(expected, second.awaitResult())
            }
        }
    }

    @Test fun browserCancelAndScriptFailureReturnActionableErrors() = runBlocking {
        for ((body, message) in listOf("""{"cancel":true}""" to "关闭", """{"failed":true}""" to "扫码登录")) {
            DesktopCaptchaCallbackSession(captcha, buildJsonObject {}).use { session ->
                assertEquals(204, request(session.pageUri, body).status)
                val error = assertFailsWith<BiliApiException> { session.awaitResult() }
                assertTrue(error.message.orEmpty().contains(message))
            }
        }
    }

    @Test fun fullBridgeUsesCookieFreeConfigurationAndClosesCallbackAfterSuccess() = runBlocking {
        ConfigurationServer().use { configuration ->
            var pageUri: URI? = null
            val bridge = DesktopCaptchaBridge(OkHttpClient(), { uri ->
                pageUri = uri
                val page = request(uri)
                assertEquals(200, page.status)
                assertTrue(page.text.contains("Cache-control: no-store", ignoreCase = true))
                assertTrue(page.text.contains("Referrer-policy: no-referrer", ignoreCase = true))
                assertFalse(page.text.contains("Set-cookie:", ignoreCase = true))
                assertFalse(page.text.contains(captcha.token))
                assertEquals(204, request(uri, payload).status)
            }, configurationUrl = configuration.url)
            assertEquals(expected, bridge.verify(captcha))
            assertNull(configuration.cookie.get())
            assertClosed(assertNotNull(pageUri))
        }
    }

    @Test fun timeoutIncludesConfigurationFetchAndCancelsItsHttpCall() = runBlocking {
        ConfigurationServer(block = true).use { configuration ->
            val cancelled = AtomicBoolean()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.set(true) }
            }).build()
            val bridge = DesktopCaptchaBridge(client, { fail("Browser opened before configuration arrived") },
                timeoutMillis = 1_000, configurationUrl = configuration.url)
            val error = assertFailsWith<BiliApiException> { bridge.verify(captcha) }
            assertTrue(configuration.received.await(5, TimeUnit.SECONDS))
            assertTrue(error.message.orEmpty().contains("超时"))
            assertTrue(cancelled.get())
        }
    }

    @Test fun configurationErrorsOfferQrLoginWithoutOpeningBrowser() = runBlocking {
        for ((body, status) in listOf("not JSON" to 200, "unavailable" to 503)) {
            ConfigurationServer(responseBody = body, statusCode = status).use { configuration ->
                val bridge = DesktopCaptchaBridge(OkHttpClient(), { fail("Browser opened after configuration failed") },
                    configurationUrl = configuration.url)
                val error = assertFailsWith<BiliApiException> { bridge.verify(captcha) }
                assertTrue(error.message.orEmpty().contains("扫码登录"))
            }
        }
    }

    @Test fun timeoutWaitingForBrowserCallbackClosesListener() = runBlocking {
        ConfigurationServer().use { configuration ->
            val uri = AtomicReference<URI>()
            val bridge = DesktopCaptchaBridge(OkHttpClient(), uri::set,
                timeoutMillis = 1_000, configurationUrl = configuration.url)
            val error = assertFailsWith<BiliApiException> { bridge.verify(captcha) }
            assertTrue(error.message.orEmpty().contains("超时"))
            assertClosed(assertNotNull(uri.get()))
        }
    }

    @Test fun callerCancellationClosesListenerAndRemainsCancellation() = runBlocking {
        ConfigurationServer().use { configuration ->
            val opened = CompletableDeferred<URI>()
            val bridge = DesktopCaptchaBridge(OkHttpClient(), { opened.complete(it) }, configurationUrl = configuration.url)
            val result = async { bridge.verify(captcha) }
            val uri = withTimeout(5_000) { opened.await() }
            result.cancel()
            assertFailsWith<CancellationException> { result.await() }
            assertClosed(uri)
        }
    }

    @Test fun parentTimeoutIsNotConvertedToCaptchaTimeout() = runBlocking {
        ConfigurationServer().use { configuration ->
            val uri = AtomicReference<URI>()
            val bridge = DesktopCaptchaBridge(OkHttpClient(), uri::set, configurationUrl = configuration.url)
            assertFailsWith<TimeoutCancellationException> { withTimeout(1_000) { bridge.verify(captcha) } }
            assertClosed(assertNotNull(uri.get()))
        }
    }

    @Test fun browserLaunchFailureClosesListenerAndOffersQrLogin() = runBlocking {
        ConfigurationServer().use { configuration ->
            var uri: URI? = null
            val bridge = DesktopCaptchaBridge(OkHttpClient(), { target ->
                uri = target
                throw IOException("No browser handler")
            }, configurationUrl = configuration.url)
            val error = assertFailsWith<BiliApiException> { bridge.verify(captcha) }
            assertTrue(error.message.orEmpty().contains("扫码登录"))
            assertClosed(assertNotNull(uri))
        }
    }

    @Test fun timeoutInterruptsStalledBrowserLaunchAndClosesListener() = runBlocking {
        ConfigurationServer().use { configuration ->
            val uri = AtomicReference<URI>()
            val interrupted = AtomicBoolean()
            val bridge = DesktopCaptchaBridge(OkHttpClient(), { target ->
                uri.set(target)
                try { check(CountDownLatch(1).await(5, TimeUnit.SECONDS)) { "Browser launch was not interrupted" } }
                catch (error: InterruptedException) { interrupted.set(true); throw error }
            }, timeoutMillis = 1_000, configurationUrl = configuration.url)
            val error = assertFailsWith<BiliApiException> { bridge.verify(captcha) }
            assertTrue(error.message.orEmpty().contains("超时"))
            assertTrue(interrupted.get())
            assertClosed(assertNotNull(uri.get()))
        }
    }

    private fun assertClosed(uri: URI) {
        assertFailsWith<IOException> { request(uri, payload) }
    }

    private fun origin(uri: URI) = "http://${uri.rawAuthority}"

    private data class HttpReply(val status: Int, val text: String)

    /** Raw HTTP lets the tests send duplicate Host/Origin headers, as a hostile client can. */
    private fun request(uri: URI, body: String? = null, origins: List<String> = listOf(origin(uri)),
        host: List<String> = listOf(uri.rawAuthority), contentType: String = "application/json",
        callback: Boolean = body != null): HttpReply = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", uri.port), 5_000)
        socket.soTimeout = 5_000
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        val target = if (callback) "${uri.rawPath}/result" else uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        val headers = buildString {
            append("${if (body == null) "GET" else "POST"} $target HTTP/1.1\r\n")
            host.forEach { append("Host: $it\r\n") }
            if (body != null) {
                origins.forEach { append("Origin: $it\r\n") }
                append("Content-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.UTF_8)); write(bytes); flush() }
        val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
        HttpReply(response.substringBefore("\r\n").split(' ')[1].toInt(), response)
    }

    private class ConfigurationServer(block: Boolean = false,
        responseBody: String = """{"status":"success","data":{"type":"fullpage"}}""", statusCode: Int = 200) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "captcha-test-config").apply { isDaemon = true } }
        private val release = CountDownLatch(if (block) 1 else 0)
        val received = CountDownLatch(1)
        val cookie = AtomicReference<String?>()
        val url = "http://127.0.0.1:${server.address.port}/gettype.php".toHttpUrl()

        init {
            server.executor = executor
            server.createContext("/gettype.php") { exchange ->
                try {
                    cookie.set(exchange.requestHeaders.getFirst("Cookie"))
                    received.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    val bytes = responseBody.toByteArray()
                    exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                } catch (_: IOException) {
                    // The timeout/cancellation test deliberately closes this connection.
                } finally { exchange.close() }
            }
            server.start()
        }

        override fun close() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
