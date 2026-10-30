package com.bilipai.desktop.data

import com.android.purebilibili.core.network.FORCE_COOKIE_HEADER
import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.network.WbiKeyManager
import com.android.purebilibili.core.network.socket.LiveDanmakuClient
import com.android.purebilibili.data.repository.SubtitleContentRepository
import com.android.purebilibili.feature.search.resolveArticleNavigationTarget
import com.android.purebilibili.feature.search.resolveArticleWebUrl
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** TON-1082 (TON-1071 B1): upstream NetworkModule requests through the real bridge. A local TLS server stands in for
 * the Bilibili hosts; the repository's own client (interceptors, CookieJar = the Store, cache, network interceptors)
 * reaches it through a socket factory that only rewrites the connect address. Synthetic accounts only. */
class DesktopUpstreamOwnerTest {
    private val closeables = mutableListOf<Closeable>()
    @AfterTest fun close() { closeables.asReversed().forEach { runCatching { it.close() } } }

    @Test fun missingOwnerFailsLoudlyOnEveryEntryAndNothingIsSent() {
        val fixture = fixture()
        val request = Request.Builder().url("https://api.bilibili.com/x/web-interface/nav").build()
        assertNull(DesktopUpstreamCalls.currentOwner())
        assertFailsWith<UnsupportedDesktopCapability> { runBlocking { NetworkModule.api.getNavInfo() } }
        assertFailsWith<UnsupportedDesktopCapability> { runBlocking { NetworkModule.guestApi.getNavInfo() } }
        assertFailsWith<UnsupportedDesktopCapability> { DesktopUpstreamCalls.main.newCall(request) }
        assertFailsWith<UnsupportedDesktopCapability> { NetworkModule.playbackApi() }
        // The upstream clients themselves are gates, including clients derived with newBuilder().
        for (client in listOf(NetworkModule.okHttpClient, NetworkModule.guestOkHttpClient, NetworkModule.playbackOkHttpClient,
            NetworkModule.okHttpClient.newBuilder().followRedirects(false).build()))
            assertFailsWith<UnsupportedDesktopCapability> { client.newCall(request).execute() }
        // The runCatching of the article redirect resolver no longer turns a missing owner into a fallback.
        assertFailsWith<UnsupportedDesktopCapability> { runBlocking { resolveArticleNavigationTarget(42) } }
        assertFailsWith<UnsupportedDesktopCapability> { runBlocking { resolveArticleWebUrl(42) } }
        assertFailsWith<UnsupportedDesktopCapability> {
            DesktopUpstreamCalls.webSocket.newWebSocket(Request.Builder().url("https://broadcast.chat.bilibili.com/sub").build(), object : WebSocketListener() {})
        }
        assertTrue(fixture.seen.isEmpty(), "nothing may reach the network: ${fixture.seen}")
    }

    @Test fun gateKeepsTheSharedClientContractAndItsCacheIsTheGuestPartition() {
        assertSame(DesktopUpstreamHttpCache.guest, NetworkModule.okHttpClient.cache)
        assertEquals(32L * 1024 * 1024, NetworkModule.okHttpClient.cache?.maxSize())
        assertTrue(Protocol.HTTP_2 in NetworkModule.okHttpClient.protocols)
        assertTrue(Protocol.HTTP_2 in NetworkModule.guestOkHttpClient.protocols)
        val factory = LiveDanmakuClient::class.java.getDeclaredField("webSocketFactory").apply { isAccessible = true }
        assertSame(DesktopUpstreamCalls.webSocket, factory.get(LiveDanmakuClient(CoroutineScope(Dispatchers.Unconfined))))
    }

    @Test fun retrofitAndDirectCallsCarryTheOwnersAccountOnly() = runBlocking {
        val fixture = fixture()
        val (repository, _) = repository(fixture, 11L to "sess-a", 22L to "sess-b")
        val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        assertEquals(22L, owner.identity.mid)
        DesktopUpstreamCalls.withOwnerScope(owner) { NetworkModule.api.getNavInfo() }
        DesktopUpstreamCalls.withOwner(owner) { DesktopUpstreamCalls.main.newCall(nav()).execute().close() }
        assertEquals(2, fixture.seen.size)
        fixture.seen.forEach { seen ->
            assertTrue("SESSDATA=sess-b" in seen.cookie.orEmpty(), seen.toString())
            assertFalse("sess-a" in seen.cookie.orEmpty(), seen.toString())
        }
    }

    @Test fun switchBetweenCreatingAndEnqueuingNeverSendsWithEitherAccount() = runBlocking {
        val fixture = fixture()
        val (repository, sessions) = repository(fixture, 11L to "sess-a", 22L to "sess-b")
        val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        val created = DesktopUpstreamCalls.withOwner(owner) { DesktopUpstreamCalls.main.newCall(nav()) }
        assertTrue(sessions.activateAccount(11))
        val failure = AtomicReference<IOException>()
        val done = CountDownLatch(1)
        created.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { failure.set(e); done.countDown() }
            override fun onResponse(call: Call, response: Response) { response.close(); done.countDown() }
        })
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertRetired(assertNotNull(failure.get()))
        // Retrofit: the same retired owner, after the switch.
        assertRetired(assertFails { DesktopUpstreamCalls.withOwnerScope(owner) { NetworkModule.api.getNavInfo() } })
        assertFalse(owner.isCurrent())
        assertTrue(fixture.seen.isEmpty(), "a retired owner sent ${fixture.seen}")
    }

    @Test fun switchAfterResponseHeadersDoesNotPublishTheOldResultOrCookies() = runBlocking {
        val fixture = fixture()
        // Counted by the outermost application interceptor: the response headers passed every desktop owner check.
        val headersAccepted = CountDownLatch(2)
        val (repository, sessions) = repository(fixture, 11L to "sess-a", 22L to "sess-b") { headersAccepted.countDown() }
        SubtitleContentRepository.clearCache()
        WbiKeyManager.invalidateCache()
        val release = CountDownLatch(1)
        fixture.route = { exchange ->
            val path = exchange.requestURI.path
            val body = if (path.contains("subtitle")) """{"body":[{"from":0.0,"to":1.0,"content":"fixture"}]}"""
                else """{"code":0,"message":"0","data":{"isLogin":true,"mid":22,"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/aaaa.png","sub_url":"https://i0.hdslb.com/bfs/wbi/bbbb.png"}}}"""
            val cookieB = exchange.requestHeaders.getFirst("Cookie").orEmpty().contains("sess-b")
            if (cookieB) exchange.responseHeaders.add("Set-Cookie", "probe=from-b; Domain=.bilibili.com; Path=/; Secure")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.flush()
            if (cookieB) release.await(10, TimeUnit.SECONDS)
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val ownerB = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        val subtitleUrl = "https://www.bilibili.com/fixture/subtitle/fixture.json"
        // Direct newCall path (SubtitleContentRepository) and Retrofit path (WbiKeyManager -> NetworkModule.api).
        val subtitle = async(Dispatchers.IO) { runCatching { DesktopUpstreamCalls.withOwnerScope(ownerB) { SubtitleContentRepository.cues(subtitleUrl, "BV1fixture", 1).getOrThrow() } } }
        val keys = async(Dispatchers.IO) { runCatching { DesktopUpstreamCalls.withOwnerScope(ownerB) { WbiKeyManager.refreshKeys().getOrThrow() } } }
        assertTrue(headersAccepted.await(10, TimeUnit.SECONDS))
        // Both responses are now in the callers' hands, bodies still unread.
        assertTrue(sessions.activateAccount(11))
        release.countDown()
        assertRetired(subtitle.await().exceptionOrNull() ?: fail("the old subtitle result was published"))
        assertRetired(keys.await().exceptionOrNull() ?: fail("the old WBI keys were published"))
        // Account A starts clean: both requests reach the network again, with A's credentials and no B cookie.
        val ownerA = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        DesktopUpstreamCalls.withOwnerScope(ownerA) {
            assertEquals("fixture", SubtitleContentRepository.cues(subtitleUrl, "BV1fixture", 1).getOrThrow().single().content)
            assertEquals("aaaa" to "bbbb", WbiKeyManager.getWbiKeys().getOrThrow())
        }
        val forA = fixture.seen.filter { "sess-a" in it.cookie.orEmpty() }
        assertEquals(2, forA.size, fixture.seen.toString())
        forA.forEach { assertFalse("probe=" in it.cookie.orEmpty(), it.toString()) }
        WbiKeyManager.invalidateCache()
    }

    @Test fun explicitGuestNeverCarriesAccountCredentialsAndUsesTheGuestCache() = runBlocking {
        val fixture = fixture()
        val (repository, _) = repository(fixture, 22L to "sess-b")
        val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        val guest = repository.upstreamOwner(DesktopUpstreamPurpose.Guest, { true })
        DesktopUpstreamCalls.withOwnerScope(owner) { NetworkModule.guestApi.getNavInfo() }
        // An explicit guest owner stays a guest even on the account services.
        DesktopUpstreamCalls.withOwnerScope(guest) { NetworkModule.api.getNavInfo() }
        assertFailsWith<UnsupportedDesktopCapability> { guest.withPurpose(DesktopUpstreamPurpose.Main) }
        DesktopUpstreamCalls.withOwner(owner) {
            DesktopUpstreamCalls.guest.newCall(nav().newBuilder().header("Cookie", "SESSDATA=forged")
                .header(FORCE_COOKIE_HEADER, "SESSDATA=forged").build()).execute().close()
        }
        assertEquals(3, fixture.seen.size)
        fixture.seen.forEach { seen ->
            assertTrue(Regex("^buvid3=[0-9a-f]{32}infoc$").matches(seen.cookie.orEmpty()), seen.toString())
            assertNull(seen.forcedCookie)
        }
        // The 32 MiB NetworkModule cache is the one guest requests actually use.
        fixture.route = { exchange -> fixture.respond(exchange, 200, """{"code":0}""", "Cache-Control" to "public, max-age=600") }
        val url = "https://api.bilibili.com/x/fixture/guest-cache/${System.nanoTime()}"
        val hits = NetworkModule.okHttpClient.cache!!.hitCount()
        repeat(2) { DesktopUpstreamCalls.withOwner(guest) { DesktopUpstreamCalls.guest.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() } } }
        assertEquals(1, fixture.seen.count { it.target.startsWith("/x/fixture/guest-cache/") })
        assertEquals(hits + 1, NetworkModule.okHttpClient.cache!!.hitCount())
    }

    @Test fun accountCacheIsPartitionedByIdentityAndDeletedWhenRetired() = runBlocking {
        val fixture = fixture()
        val (repository, sessions) = repository(fixture, 11L to "sess-a", 22L to "sess-b")
        fixture.route = { exchange -> fixture.respond(exchange, 200, """{"code":0,"from":"${exchange.requestHeaders.getFirst("Cookie")}"}""",
            "Cache-Control" to "private, max-age=600") }
        val url = "https://api.bilibili.com/x/fixture/account-cache/${System.nanoTime()}"
        fun get(owner: DesktopUpstreamOwner) = DesktopUpstreamCalls.withOwner(owner) {
            DesktopUpstreamCalls.main.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
        }
        val ownerB = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        val first = get(ownerB)
        assertEquals(first, get(ownerB))
        assertEquals(1, fixture.seen.size)
        val partitionB = assertNotNull(repository.upstreamAccountCacheDirectory())
        assertTrue(partitionB.exists())
        assertTrue(Regex("^[0-9a-f-]{36}$").matches(partitionB.name), "partition names never name an account: ${partitionB.name}")
        assertTrue(sessions.activateAccount(11))
        val ownerA = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        val forA = get(ownerA)
        assertTrue("sess-a" in forA && "sess-b" !in forA, forA)
        assertEquals(2, fixture.seen.size)
        assertFalse(partitionB.exists(), "the retired identity's responses are deleted")
        val partitionA = assertNotNull(repository.upstreamAccountCacheDirectory())
        assertNotEquals(partitionB, partitionA)
        repository.logout()
        assertNull(repository.upstreamAccountCacheDirectory())
        assertFalse(partitionA.exists())
    }

    @Test fun sameEpochInAnotherStoreOrWindowIsAnotherOwner() = runBlocking {
        val fixture = fixture()
        val (first, _) = repository(fixture, 11L to "sess-first")
        val (second, _) = repository(fixture, 11L to "sess-second")
        assertEquals(first.sessionEpoch, second.sessionEpoch)
        var firstWindowOpen = true
        val ownerFirst = first.upstreamOwner(DesktopUpstreamPurpose.Main, { firstWindowOpen })
        val ownerSecond = second.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        assertNotEquals(ownerFirst.identity, ownerSecond.identity)
        DesktopUpstreamCalls.withOwnerScope(ownerFirst) { NetworkModule.api.getNavInfo() }
        DesktopUpstreamCalls.withOwnerScope(ownerSecond) { NetworkModule.api.getNavInfo() }
        assertEquals(listOf(true to false, false to true), fixture.seen.map { ("sess-first" in it.cookie.orEmpty()) to ("sess-second" in it.cookie.orEmpty()) })
        // Closing the first window retires its owner only.
        val pending = DesktopUpstreamCalls.withOwner(ownerFirst) { DesktopUpstreamCalls.main.newCall(nav()) }
        firstWindowOpen = false
        assertRetired(assertFailsWith<IOException> { pending.execute() })
        assertRetired(assertFails { DesktopUpstreamCalls.withOwnerScope(ownerFirst) { NetworkModule.api.getNavInfo() } })
        assertTrue(ownerSecond.isCurrent())
        var ran = false
        DesktopUpstreamSession(first) { firstWindowOpen }.withOwner { ran = true }
        assertFalse(ran, "an operation that starts after its window closed does not run")
        assertEquals(2, fixture.seen.size)
    }

    @Test fun playbackOwnerFollowsItsAuthorizationRevisionAndNeverFallsBack() = runBlocking {
        val fixture = fixture()
        val (repository, sessions) = repository(fixture, 22L to "sess-playback", 11L to "sess-main")
        assertTrue(sessions.setPlaybackAccountMid(22, sessions.generation) { true })
        val main = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        assertFailsWith<UnsupportedDesktopCapability> { DesktopUpstreamCalls.withOwner(main) { NetworkModule.playbackApi() } }
        assertFailsWith<UnsupportedDesktopCapability> { DesktopUpstreamCalls.withOwner(main) { NetworkModule.playbackBangumiApi() } }
        val authorization = repository.capturePlaybackAuthorization(repository.sessionEpoch) { true }
        val playback = repository.upstreamOwner(DesktopUpstreamPurpose.Playback(authorization), { true })
        DesktopUpstreamCalls.withOwnerScope(playback) { NetworkModule.playbackApi().getNavInfo() }
        assertTrue("SESSDATA=sess-playback" in fixture.seen.single().cookie.orEmpty(), fixture.seen.toString())
        assertFalse("sess-main" in fixture.seen.single().cookie.orEmpty())
        // The playback revision changes: the captured owner retires, nothing more is sent.
        assertTrue(sessions.setPlaybackAccountMid(null, sessions.generation) { true })
        assertFalse(playback.isCurrent())
        assertRetired(assertFails { DesktopUpstreamCalls.withOwnerScope(playback) { NetworkModule.playbackApi().getNavInfo() } })
        assertEquals(1, fixture.seen.size)
    }

    @Test fun ownerFollowsThreadSwitchesAndNestedOwnersRestore() = runBlocking {
        val fixture = fixture()
        val (first, _) = repository(fixture, 11L to "sess-a")
        val (second, _) = repository(fixture, 22L to "sess-b")
        val a = first.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        val b = second.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        DesktopUpstreamCalls.withOwnerScope(a) {
            withContext(Dispatchers.IO) { assertSame(a, DesktopUpstreamCalls.currentOwner()) }
            DesktopUpstreamCalls.withOwnerScope(b) {
                withContext(Dispatchers.Default) { assertSame(b, DesktopUpstreamCalls.currentOwner()); NetworkModule.api.getNavInfo() }
                DesktopUpstreamCalls.withOwner(a) { assertSame(a, DesktopUpstreamCalls.currentOwner()) }
                assertSame(b, DesktopUpstreamCalls.currentOwner())
            }
            assertSame(a, DesktopUpstreamCalls.currentOwner())
            withContext(Dispatchers.IO) { NetworkModule.api.getNavInfo() }
            // A new independent scope does not inherit the owner.
            assertNull(CoroutineScope(Dispatchers.IO).async { DesktopUpstreamCalls.currentOwner() }.await())
            // Nor does an OkHttp callback thread.
            val callbackOwner = AtomicReference<Any?>("unset")
            val done = CountDownLatch(1)
            DesktopUpstreamCalls.main.newCall(nav()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { callbackOwner.set(e); done.countDown() }
                override fun onResponse(call: Call, response: Response) { response.close(); callbackOwner.set(DesktopUpstreamCalls.currentOwner()); done.countDown() }
            })
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertNull(callbackOwner.get())
        }
        assertNull(DesktopUpstreamCalls.currentOwner())
        assertEquals(listOf("sess-b", "sess-a", "sess-a"), fixture.seen.map { if ("sess-a" in it.cookie.orEmpty()) "sess-a" else if ("sess-b" in it.cookie.orEmpty()) "sess-b" else "none" })
    }

    @Test fun webSocketHandshakeCarriesTheOwnerAndARetiredOwnerNeverConnects() {
        val fixture = fixture()
        val (repository, sessions) = repository(fixture, 11L to "sess-a", 22L to "sess-b")
        val socketRequest = Request.Builder().url("https://broadcast.chat.bilibili.com/sub").build()
        val failed = CountDownLatch(1)
        val listener = object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failed.countDown() }
        }
        val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        DesktopUpstreamCalls.withOwner(owner) { DesktopUpstreamCalls.webSocket.newWebSocket(socketRequest, listener) }
        assertTrue(failed.await(10, TimeUnit.SECONDS)) // The fixture answers 200 instead of 101.
        val handshake = fixture.seen.single()
        assertEquals("/sub", handshake.target)
        assertTrue("SESSDATA=sess-b" in handshake.cookie.orEmpty())
        val retired = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        assertTrue(sessions.activateAccount(11))
        val callback = CountDownLatch(1)
        DesktopUpstreamCalls.withOwner(retired) {
            DesktopUpstreamCalls.webSocket.newWebSocket(socketRequest, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { callback.countDown() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { callback.countDown() }
            })
        }
        assertFalse(callback.await(1, TimeUnit.SECONDS), "callbacks of a retired socket owner are not delivered")
        assertEquals(1, fixture.seen.size)
        val guest = repository.upstreamOwner(DesktopUpstreamPurpose.Guest, { true })
        assertFailsWith<UnsupportedDesktopCapability> { DesktopUpstreamCalls.withOwner(guest) { DesktopUpstreamCalls.webSocket.newWebSocket(socketRequest, listener) } }
    }

    @Test fun articleRedirectResolverUsesTheOwnerWithoutFollowingRedirects() = runBlocking {
        val fixture = fixture()
        val (repository, _) = repository(fixture, 22L to "sess-b")
        fixture.route = { exchange ->
            exchange.responseHeaders.add("Location", "https://www.bilibili.com/opus/777")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        assertEquals("https://www.bilibili.com/opus/777", DesktopUpstreamCalls.withOwnerScope(owner) { resolveArticleWebUrl(42) })
        val seen = fixture.seen.single()
        assertEquals("HEAD" to "/read/cv42", seen.method to seen.target)
        assertTrue("SESSDATA=sess-b" in seen.cookie.orEmpty())
    }

    @Test fun thirdPartyTransportHasNoAccountAndRefusesBilibiliHosts() {
        val fixture = fixture()
        val client = fixture.routed(DesktopUpstreamCalls.publicTransport.newBuilder()).build()
        client.newCall(Request.Builder().url("https://sponsor.fixture.test/api/skipSegments?videoID=BV1&access_key=forged")
            .header("Cookie", "SESSDATA=forged").header("Authorization", "Bearer forged").build()).execute().close()
        val seen = fixture.seen.single()
        assertNull(seen.cookie)
        assertNull(seen.authorization)
        assertEquals("/api/skipSegments?videoID=BV1", seen.target)
        assertFailsWith<UnsupportedDesktopCapability> { client.newCall(nav()).execute() }
        assertEquals(1, fixture.seen.size)
    }

    @Test fun guestMustRemoveCredentialQueryParameters() {
        val fixture = fixture()
        val (repository, _) = repository(fixture, 11L to "sess-a")
        val guest = repository.upstreamOwner(DesktopUpstreamPurpose.Guest, { true })
        DesktopUpstreamCalls.withOwner(guest) {
            DesktopUpstreamCalls.main.newCall(nav().newBuilder()
                .url("https://api.bilibili.com/x/web-interface/nav?access_key=fixture-secret&csrf=fixture-csrf")
                .build()).execute().close()
        }
        assertEquals("/x/web-interface/nav", fixture.seen.single().target)
    }

    @Test fun retiredPlaybackMustNotSendViaMainService() = runBlocking {
        val fixture = fixture()
        val (repository, sessions) = repository(fixture, 22L to "sess-playback", 11L to "sess-main")
        assertTrue(sessions.setPlaybackAccountMid(22, sessions.generation) { true })
        val authorization = repository.capturePlaybackAuthorization(repository.sessionEpoch) { true }
        val playback = repository.upstreamOwner(DesktopUpstreamPurpose.Playback(authorization), { true })
        assertTrue(sessions.setPlaybackAccountMid(null, sessions.generation) { true })
        assertFalse(playback.isCurrent())
        val result = runCatching { DesktopUpstreamCalls.withOwnerScope(playback) { NetworkModule.api.getNavInfo() } }
        assertTrue(result.isFailure, "retired playback owner sent main request: ${fixture.seen}")
        assertTrue(fixture.seen.isEmpty())
    }

    @Test fun thirdPartyRedirectMustRefuseBilibili() {
        val fixture = fixture()
        fixture.route = { exchange ->
            if (exchange.requestHeaders.getFirst("Host").startsWith("sponsor.fixture.test")) {
                exchange.responseHeaders.add("Location", "https://api.bilibili.com/x/web-interface/nav?access_key=fixture-secret")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else fixture.respond(exchange, 200, "{}")
        }
        val client = fixture.routed(DesktopUpstreamCalls.publicTransport.newBuilder()).build()
        runCatching { client.newCall(Request.Builder().url("https://sponsor.fixture.test/redirect").build()).execute().close() }
        assertEquals(1, fixture.seen.size, "redirect reached Bilibili: ${fixture.seen}")
    }

    @Test fun retiredOwnerMustNotReadSubtitleCache() = runBlocking {
        val fixture = fixture()
        val (repository, sessions) = repository(fixture, 11L to "sess-a", 22L to "sess-b")
        SubtitleContentRepository.clearCache()
        fixture.route = { exchange -> fixture.respond(exchange, 200,
            """{"body":[{"from":0.0,"to":1.0,"content":"private-fixture"}]}""") }
        val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        val url = "https://www.bilibili.com/fixture/subtitle/cache-review.json"
        DesktopUpstreamCalls.withOwnerScope(owner) { SubtitleContentRepository.cues(url, "BV1cacheReview", 1).getOrThrow() }
        assertTrue(sessions.activateAccount(11))
        val result = runCatching { DesktopUpstreamCalls.withOwnerScope(owner) {
            SubtitleContentRepository.cues(url, "BV1cacheReview", 1).getOrThrow()
        } }
        assertTrue(result.isFailure, "retired owner successfully reads global cached result")
    }

    @Test fun guestStripsCredentialsFromFormsHeadersAndRedirectHops() {
        val fixture = fixture()
        val (repository, _) = repository(fixture, 11L to "sess-a")
        fixture.route = { exchange ->
            if (exchange.requestURI.path == "/x/fixture/hop") {
                exchange.responseHeaders.add("Location", "https://api.bilibili.com/x/fixture/landing?access_key=fixture-secret&keep=1")
                exchange.sendResponseHeaders(302, -1); exchange.close()
            } else fixture.respond(exchange, 200, """{"code":0,"form":"${exchange.requestBody.readAllBytes().decodeToString()}"}""")
        }
        val guest = repository.upstreamOwner(DesktopUpstreamPurpose.Guest, { true })
        val form = okhttp3.FormBody.Builder().add("csrf", "fixture-csrf").add("access_key", "fixture-secret").add("oid", "7").build()
        val posted = DesktopUpstreamCalls.withOwner(guest) {
            DesktopUpstreamCalls.guest.newCall(Request.Builder().url("https://api.bilibili.com/x/fixture/form").post(form)
                .header("Authorization", "Bearer fixture").header("X-CSRF-Token", "fixture-csrf").build()).execute().use { it.body.string() }
        }
        assertTrue("\"form\":\"oid=7\"" in posted, posted)
        val sent = fixture.seen.single()
        assertNull(sent.authorization)
        assertTrue(Regex("^buvid3=[0-9a-f]{32}infoc$").matches(sent.cookie.orEmpty()), sent.toString())
        DesktopUpstreamCalls.withOwner(guest) { DesktopUpstreamCalls.guest.newCall(Request.Builder().url("https://api.bilibili.com/x/fixture/hop").build()).execute().close() }
        assertEquals(listOf("/x/fixture/hop", "/x/fixture/landing?keep=1"), fixture.seen.drop(1).map { it.target })
    }

    @Test fun ownersDerivedFromPlaybackRetireWithItOnEveryPath() = runBlocking {
        val fixture = fixture()
        val (repository, sessions) = repository(fixture, 22L to "sess-playback", 11L to "sess-main")
        assertTrue(sessions.setPlaybackAccountMid(22, sessions.generation) { true })
        val authorization = repository.capturePlaybackAuthorization(repository.sessionEpoch) { true }
        val playback = repository.upstreamOwner(DesktopUpstreamPurpose.Playback(authorization), { true })
        // Created while current, revoked before it runs: the main call, its clone and a guest call all retire.
        val created = DesktopUpstreamCalls.withOwner(playback) { listOf(DesktopUpstreamCalls.main.newCall(nav()), DesktopUpstreamCalls.guest.newCall(nav())) }
        val socketDelivered = CountDownLatch(1)
        assertTrue(sessions.setPlaybackAccountMid(null, sessions.generation) { true })
        for (call in created + created.map { it.clone() }) assertRetired(assertFailsWith<IOException> { call.execute() })
        assertFailsWith<BiliApiException> { DesktopUpstreamCalls.withOwner(playback) { DesktopUpstreamCalls.main.newCall(nav()) } }
        DesktopUpstreamCalls.withOwner(playback) {
            DesktopUpstreamCalls.webSocket.newWebSocket(Request.Builder().url("https://broadcast.chat.bilibili.com/sub").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socketDelivered.countDown() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { socketDelivered.countDown() }
            })
        }
        assertFalse(socketDelivered.await(1, TimeUnit.SECONDS))
        assertTrue(fixture.seen.isEmpty(), "a revoked playback owner sent ${fixture.seen}")
    }

    @Test fun retiredOwnerReadsNoWbiOrEmoteCacheAndAccountsDoNotShareSubtitles() = runBlocking {
        val fixture = fixture()
        val (repository, sessions) = repository(fixture, 11L to "sess-a", 22L to "sess-b")
        WbiKeyManager.invalidateCache(); SubtitleContentRepository.clearCache()
        fixture.route = { exchange ->
            val body = if (exchange.requestURI.path.contains("subtitle")) """{"body":[{"from":0.0,"to":1.0,"content":"${exchange.requestHeaders.getFirst("Cookie")}"}]}"""
                else """{"code":0,"data":{"isLogin":true,"mid":22,"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/cccc.png","sub_url":"https://i0.hdslb.com/bfs/wbi/dddd.png"}}}"""
            fixture.respond(exchange, 200, body)
        }
        val url = "https://www.bilibili.com/fixture/subtitle/partition.json"
        val ownerB = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        DesktopUpstreamCalls.withOwnerScope(ownerB) {
            WbiKeyManager.getWbiKeys().getOrThrow()
            assertEquals(com.android.purebilibili.feature.dynamic.components.DynamicEmoteCatalogSessionKey(22, true),
                com.android.purebilibili.feature.dynamic.components.DynamicEmoteCatalog.currentSessionKey())
            SubtitleContentRepository.cues(url, "BV1partition", 1).getOrThrow()
        }
        assertTrue(sessions.activateAccount(11))
        assertRetired(assertFails { DesktopUpstreamCalls.withOwnerScope(ownerB) { WbiKeyManager.getWbiKeys().getOrThrow() } })
        assertRetired(assertFails { DesktopUpstreamCalls.withOwner(ownerB) { com.android.purebilibili.feature.dynamic.components.DynamicEmoteCatalog.snapshot() } })
        // A same-epoch-number store or a later account gets its own subtitle partition, not B's cached cues.
        val ownerA = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        val forA = DesktopUpstreamCalls.withOwnerScope(ownerA) { SubtitleContentRepository.cues(url, "BV1partition", 1).getOrThrow() }
        assertTrue("sess-a" in forA.single().content && "sess-b" !in forA.single().content, forA.toString())
        WbiKeyManager.invalidateCache()
    }

    @Test fun admittedBlockHoldsOffAnAccountSwitchUntilItFinishes() {
        val fixture = fixture()
        val (repository, sessions) = repository(fixture, 11L to "sess-a", 22L to "sess-b")
        val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        val switched = CountDownLatch(1)
        val switcher = Thread { sessions.activateAccount(11); switched.countDown() }
        DesktopUpstreamCalls.withOwner(owner) {
            DesktopUpstreamCalls.admitted("fixture publish") {
                switcher.start()
                // The switch needs the same Store admission: it cannot land between the check and this write.
                assertFalse(switched.await(500, TimeUnit.MILLISECONDS), "an account switch interleaved with an admitted write")
                assertTrue(owner.isCurrent())
            }
        }
        assertTrue(switched.await(10, TimeUnit.SECONDS))
        assertFalse(owner.isCurrent())
        assertFailsWith<BiliApiException> { DesktopUpstreamCalls.withOwner(owner) { DesktopUpstreamCalls.admitted("fixture publish") { fail("retired owner published") } } }
    }

    @Test fun emoteFastPathDoesNotReturnAnotherStoresCatalog() = runBlocking {
        val fixture = fixture()
        fixture.route = { exchange ->
            val label = if (exchange.requestHeaders.getFirst("Cookie").orEmpty().contains("sess-review-a")) "A" else "B"
            fixture.respond(exchange, 200,
                """{"code":0,"data":{"packages":[{"id":1,"text":"fixture","emote":[{"id":1,"text":"[review]","url":"https://i0.hdslb.com/$label.png"}]}]}}""")
        }
        val (repoA, _) = repository(fixture, 77101L to "sess-review-a")
        val (repoB, _) = repository(fixture, 77102L to "sess-review-b")
        val ownerB = repoB.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        var interleave = false
        val aReading = CountDownLatch(1)
        val bPublished = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        val ownerA = repoA.upstreamOwner(DesktopUpstreamPurpose.Main, {
            if (interleave) {
                interleave = false
                // This callback runs when A enters admission, after the fast-path cache-key check.
                // A and B have different Store locks; B can publish while A is paused at this point.
                aReading.countDown()
                check(bPublished.await(10, TimeUnit.SECONDS)) { "B did not publish" }
            }
            true
        })
        val first = DesktopUpstreamCalls.withOwnerScope(ownerA) {
            com.android.purebilibili.feature.dynamic.components.DynamicEmoteCatalog.ensureLoaded()
        }
        assertEquals("https://i0.hdslb.com/A.png", first["[review]"])
        val worker = Thread {
            try {
                check(aReading.await(10, TimeUnit.SECONDS))
                runBlocking { DesktopUpstreamCalls.withOwnerScope(ownerB) {
                    com.android.purebilibili.feature.dynamic.components.DynamicEmoteCatalog.ensureLoaded()
                } }
            } catch (failure: Throwable) { workerFailure.set(failure) }
            finally { bPublished.countDown() }
        }.also { it.start() }
        interleave = true
        val second = DesktopUpstreamCalls.withOwnerScope(ownerA) {
            com.android.purebilibili.feature.dynamic.components.DynamicEmoteCatalog.ensureLoaded()
        }
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertNull(workerFailure.get())
        assertFalse(interleave, "interleaving hook must have run")
        assertTrue(ownerA.isCurrent() && ownerB.isCurrent())
        assertEquals("https://i0.hdslb.com/A.png", second["[review]"], "A must not receive B's catalog")
        // The snapshot readers return the entries and packages of the reader's own account, from one value.
        val catalog = com.android.purebilibili.feature.dynamic.components.DynamicEmoteCatalog
        for ((owner, label) in listOf(ownerA to "A", ownerB to "B")) DesktopUpstreamCalls.withOwner(owner) {
            assertEquals("https://i0.hdslb.com/$label.png", catalog.snapshot()["[review]"])
            assertEquals("https://i0.hdslb.com/$label.png", catalog.snapshotPackages().single().emote?.single()?.url)
        }
    }

    private fun nav() = Request.Builder().url("https://api.bilibili.com/x/web-interface/nav").build()

    private fun assertRetired(failure: Throwable) {
        val retired = failure is CancellationException || (failure is BiliApiException && failure.apiCode == -101) ||
            (failure is IOException && failure.message.orEmpty().contains("retired"))
        assertTrue(retired, "expected a retired-owner failure, got $failure")
    }

    private suspend fun assertFails(block: suspend () -> Unit): Throwable {
        try { withTimeout(10_000) { block() } } catch (failure: Throwable) { return failure }
        fail("expected a failure")
    }

    private fun fixture() = TlsFixture().also(closeables::add)

    private fun repository(fixture: TlsFixture, vararg accounts: Pair<Long, String>,
        afterResponseHeaders: (() -> Unit)? = null): Pair<DesktopRepository, DesktopSessionStore> {
        val sessions = DesktopSessionStore.temporary()
        accounts.forEach { (mid, sessData) ->
            sessions.saveAccount(mapOf("SESSDATA" to sessData, "bili_jct" to "csrf-$sessData"), AccountSummary(mid, "Fixture $mid", ""))
        }
        val repository = DesktopRepository(sessions)
        val transport = fixture.routed(repository.httpClient.newBuilder()).apply {
            if (afterResponseHeaders != null) interceptors().add(0, okhttp3.Interceptor { chain ->
                chain.proceed(chain.request()).also { afterResponseHeaders() }
            })
        }.build()
        DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
        closeables += Closeable {
            repository.logout()
            transport.dispatcher.executorService.shutdownNow()
            transport.connectionPool.evictAll()
        }
        return repository to sessions
    }

    private class TlsFixture : Closeable {
        data class Seen(val method: String, val host: String?, val target: String, val cookie: String?,
            val forcedCookie: String?, val authorization: String?)
        val seen = CopyOnWriteArrayList<Seen>()
        @Volatile var route: (HttpExchange) -> Unit = { respond(it, 200, """{"code":0,"message":"0","data":{"isLogin":true,"mid":1}}""") }
        private val directory: Path = Files.createTempDirectory("ton1082-tls")
        private val keyStore: KeyStore
        private val executor = Executors.newCachedThreadPool()
        private val server: HttpsServer
        private val trustManager: X509TrustManager

        init {
            val store = directory.resolve("fixture.p12")
            val keytool = Path.of(System.getProperty("java.home"), "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
            val process = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fixture", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=api.bilibili.com", "-ext",
                "SAN=dns:api.bilibili.com,dns:www.bilibili.com,dns:broadcast.chat.bilibili.com,dns:sponsor.fixture.test",
                "-validity", "2", "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD)
                .redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().decodeToString()
            check(process.waitFor() == 0) { output }
            keyStore = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(store).use { load(it, PASSWORD.toCharArray()) } }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, PASSWORD.toCharArray()) }
            server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) })
            server.executor = executor
            server.createContext("/") { exchange ->
                val headers = exchange.requestHeaders
                seen += Seen(exchange.requestMethod, headers.getFirst("Host"), exchange.requestURI.rawPath +
                    (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""), headers.getFirst("Cookie"),
                    headers.getFirst(FORCE_COOKIE_HEADER), headers.getFirst("Authorization"))
                try { route(exchange) } finally { exchange.close() }
            }
            server.start()
            val trusted = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("fixture", keyStore.getCertificate("fixture")) }
            trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
                .trustManagers.single() as X509TrustManager
        }

        fun respond(exchange: HttpExchange, code: Int, body: String, vararg headers: Pair<String, String>) {
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.responseHeaders.add("Content-Type", "application/json")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        /** Only the connect address changes: names, TLS (SNI and certificate check), cookies and caches stay real. */
        fun routed(builder: OkHttpClient.Builder): OkHttpClient.Builder {
            val port = server.address.port
            val loopback = InetAddress.getLoopbackAddress()
            return builder.proxy(Proxy.NO_PROXY)
                .dns { listOf(loopback) }
                .protocols(listOf(Protocol.HTTP_1_1))
                .socketFactory(object : SocketFactory() {
                    override fun createSocket(): Socket = object : Socket() {
                        override fun connect(endpoint: SocketAddress?, timeout: Int) = super.connect(InetSocketAddress(loopback, port), timeout)
                    }
                    override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
                    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
                    override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
                    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
                })
                .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory, trustManager)
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            DesktopUpstreamHttpCache.deleteTree(directory)
        }

        companion object { const val PASSWORD = "ton1082-fixture" }
    }
}
