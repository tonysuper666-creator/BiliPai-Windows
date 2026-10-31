package com.bilipai.desktop.player

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

import com.bilipai.desktop.data.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopCommentFraudDao
import com.android.purebilibili.data.repository.DesktopOriginalCommentFraudRepository
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.ui.DesktopOriginalCommentEntryRequests
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Real generated binding, upstream repository and PR30 owner transport. Local TLS only. */
class DesktopMobileCommentBindingTest {
    @Test fun typedContinuationSurvivesEntryAdmissionForEveryRoute() = runBlocking {
        val calls = mutableListOf<List<Any?>>()
        var response = ReplyData()
        val delegate = java.lang.reflect.Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
            arrayOf(DesktopVideoCommentRequests::class.java)) { _, method, args ->
            when(method.name.substringBefore('-')) {
                "isOwned" -> true
                "getMobileCommentsForSubject" -> { calls += args!!.dropLast(1); response }
                else -> error("Unexpected port: ${method.name}")
            }
        } as DesktopVideoCommentRequests
        val entry = DesktopOriginalCommentEntryRequests(delegate, { true }, { it(); true })
        CommentMainListRoute.entries.forEachIndexed { i, route ->
            val cursor = CommentMainListContinuation(route, 9876543210L, " offset\n😀$i ")
            response = ReplyData(mainListContinuation = cursor)
            val received = entry.getMobileCommentsForSubject(42, 1, i + 2, 17, 3, cursor, true).getOrThrow()
            assertSame(cursor, received.mainListContinuation)
            assertEquals(listOf(42L, 1, i+2, 17, 3, cursor, true), calls.last())
        }
        assertEquals(3, calls.size)
    }

    @Test fun realBindingKeepsLegacyRouteAndGuestCookiesAndRejectsMissingCursor() = runBlocking {
        Harness().use { h ->
            h.tls.route = { e ->
                when(e.requestURI.path) {
                    "/x/internal/gaia-gateway/ExClimbWuzhi" -> h.tls.respond(e,200,"""{"code":0}""")
                    "/x/v2/reply" -> h.tls.respond(e,200,"""{"code":0,"data":{"page":{"num":2,"size":20,"count":100},"replies":[]}}""")
                    else -> { h.unexpected += e.requestURI.toString(); h.tls.respond(e,500,"{}") }
                }
            }
            val cursor = CommentMainListContinuation(CommentMainListRoute.REST_LEGACY, 2L, "retain-offset")
            val result = h.binding.getMobileCommentsForSubject(42,1,2,20,4,cursor,false).getOrThrow()
            assertEquals(CommentMainListContinuation(CommentMainListRoute.REST_LEGACY,3L,null),result.mainListContinuation)
            val calls = h.tls.seen.filter { it.target.startsWith("/x/v2/reply?") }
            assertEquals(1,calls.size)
            val query = ("https://api.bilibili.com"+calls.single().target).toHttpUrl()
            assertEquals("42",query.queryParameter("oid")); assertEquals("2",query.queryParameter("pn"))
            assertTrue(h.tls.seen.all { !it.cookie.orEmpty().contains("SESSDATA") && !it.cookie.orEmpty().contains("bili_jct") })
            val before=h.tls.seen.size
            assertTrue(h.binding.getMobileCommentsForSubject(42,1,3,20,4,null,false).isFailure)
            assertEquals(before,h.tls.seen.size)
            assertTrue(h.unexpected.isEmpty(),h.unexpected.toString())
        }
    }

    @Test fun realRestMainRequestKeepsOpaqueOffsetAndNumericCursor() = runBlocking {
        Harness().use { h ->
            h.tls.route = { e ->
                when(e.requestURI.path) {
                    "/x/internal/gaia-gateway/ExClimbWuzhi" -> h.tls.respond(e,200,"""{"code":0}""")
                    "/x/web-interface/nav" -> h.tls.respond(e,200,"""{"code":0,"data":{"wbi_img":{"img_url":"https://fixture/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture/fedcba9876543210fedcba9876543210.png"}}}""")
                    "/x/v2/reply/wbi/main", "/x/v2/reply/main" -> h.tls.respond(e,200,"""{"code":0,"data":{"cursor":{"next":88,"is_end":false,"all_count":100,"pagination_reply":{"next_offset":"response-offset"}},"replies":[]}}""")
                    else -> { h.unexpected += e.requestURI.toString(); h.tls.respond(e,500,"{}") }
                }
            }
            val opaque="opaque +/😀\\\""
            for ((page,cursor) in listOf(2 to CommentMainListContinuation(CommentMainListRoute.REST_MAIN,77L,opaque),
                    3 to CommentMainListContinuation(CommentMainListRoute.REST_MAIN,9876543210L,null))) {
                val value=h.binding.getMobileCommentsForSubject(421,1,page,17,2,cursor,false).getOrThrow()
                assertEquals(CommentMainListContinuation(CommentMainListRoute.REST_MAIN,88L,"response-offset"),value.mainListContinuation)
            }
            val reads=h.tls.seen.filter { it.target.startsWith("/x/v2/reply/") }
            assertEquals(2,reads.size)
            val first=("https://api.bilibili.com"+reads[0].target).toHttpUrl()
            val encoded=checkNotNull(first.queryParameter("pagination_str"))
            assertEquals(opaque,org.json.JSONObject(encoded).getString("offset"))
            val second=("https://api.bilibili.com"+reads[1].target).toHttpUrl()
            assertEquals("9876543210",second.queryParameter("next"))
            assertEquals("421",first.queryParameter("oid"));assertEquals("17",first.queryParameter("ps"))
            assertTrue(reads.all { !it.cookie.orEmpty().contains("SESSDATA") })
            assertTrue(h.unexpected.isEmpty(),h.unexpected.toString())
        }
    }

    @Test fun suspendedResultIsRejectedAfterAccountSwitchOrOwnerRetirement() = runBlocking {
        for (switchAccount in listOf(true,false)) Harness().use { h ->
            h.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-a", "bili_jct" to "csrf-a"), AccountSummary(11,"A",""))
            h.bind()
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            h.tls.route = { e ->
                when(e.requestURI.path) {
                    "/x/internal/gaia-gateway/ExClimbWuzhi" -> h.tls.respond(e,200,"""{"code":0}""")
                    "/x/v2/reply" -> { entered.countDown(); check(release.await(10,TimeUnit.SECONDS)); h.tls.respond(e,200,"""{"code":0,"data":{"page":{"num":2,"size":20,"count":100},"replies":[]}}""") }
                    else -> { h.unexpected += e.requestURI.toString(); h.tls.respond(e,500,"{}") }
                }
            }
            val pending=async(Dispatchers.IO) { runCatching { h.binding.getMobileCommentsForSubject(42,1,2,20,4,CommentMainListContinuation(CommentMainListRoute.REST_LEGACY,2),false).getOrThrow() } }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(10,TimeUnit.SECONDS) })
                if(switchAccount) h.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-b", "bili_jct" to "csrf-b"),AccountSummary(22,"B","")) else h.owned=false
            } finally { release.countDown() }
            assertTrue(withTimeout(10000) { pending.await() }.exceptionOrNull() is CancellationException)
            assertEquals(1,h.tls.seen.count { it.target.startsWith("/x/v2/reply?") })
            assertTrue(h.tls.seen.none { it.cookie.orEmpty().contains("synthetic-b") })
            assertTrue(h.unexpected.isEmpty(),h.unexpected.toString())
        }
    }

    private class Harness : AutoCloseable {
        val tls=TlsFixture()
        val sessions=DesktopSessionStore.temporary()
        val repository=DesktopRepository(sessions)
        val unexpected=CopyOnWriteArrayList<String>()
        @Volatile var owned=true
        private val transport=tls.routed(repository.httpClient.newBuilder()).build()
        lateinit var binding:DesktopVideoCommentOperationsBinding
        init {
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible=true }.set(repository,transport)
            bind()
        }
        fun bind() {
            val dao=java.lang.reflect.Proxy.newProxyInstance(DesktopCommentFraudDao::class.java.classLoader,arrayOf(DesktopCommentFraudDao::class.java)) { _,m,_->error("Unexpected fraud: ${m.name}") } as DesktopCommentFraudDao
            val records=DesktopOriginalCommentFraudRepository(dao,{_,_,_->error("fraud")},{_,_->error("delete")})
            binding=DesktopVideoCommentOperationsBinding(DesktopDynamicCardOperations(repository,stillOwned={owned}),repository,
                { error("image") },records,{_,_,_,_,_,_->error("status")},{_,_,_,_,_,_,_->error("receipt")})
        }
        override fun close() { owned=false;transport.dispatcher.executorService.shutdownNow();transport.connectionPool.evictAll();tls.close() }
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
