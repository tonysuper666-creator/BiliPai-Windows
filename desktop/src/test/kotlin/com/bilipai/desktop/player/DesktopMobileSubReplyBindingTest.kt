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

import com.android.purebilibili.core.network.grpc.ProtoWire

/** Actual generated binding, Main owner, upstream gRPC parser and loopback TLS responses. */
class DesktopMobileSubReplyBindingTest {
    @Test fun sevenArgumentsAndResponseSurviveEntryAdmission() = runBlocking {
        val calls=mutableListOf<List<Any?>>()
        val response=ReplyData(grpcNextOffset=" opaque\n😀 ")
        val delegate=java.lang.reflect.Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
            arrayOf(DesktopVideoCommentRequests::class.java)) { _,m,args ->
            when(m.name.substringBefore('-')) {
                "isOwned" -> true
                "getMobileSortedSubCommentsForSubject" -> { calls+=args!!.dropLast(1);response }
                else -> error("Unexpected port ${m.name}")
            }
        } as DesktopVideoCommentRequests
        val entry=DesktopOriginalCommentEntryRequests(delegate,{true},{it();true})
        for (offset in listOf(null,""," opaque\n😀 ")) for (supplement in listOf(false,true)) {
            assertSame(response,entry.getMobileSortedSubCommentsForSubject(42,1,901,2,offset,9876543210L,supplement).getOrThrow())
            assertEquals(listOf(42L,1,901L,2,offset,9876543210L,supplement),calls.last())
        }
        assertEquals(6,calls.size)
    }

    @Test fun actualMobileCursorValidationAndFailurePreserveOneRequest() = runBlocking {
        Harness().use { h ->
            val requests=CopyOnWriteArrayList<List<Any?>>()
            var next="";var end=false;var status="0"
            h.tls.route={ e -> when(e.requestURI.path) {
                RPC -> { requests+=readRequest(e);h.tls.grpc(e,detail(next,end),status) }
                BUVID -> h.tls.respond(e,200,"""{"code":0}""")
                else -> { h.unexpected+=e.requestURI.toString();h.tls.respond(e,500,"{}") }
            } }
            val previous=" cursor\n😀 "
            for ((cursor,terminal,valid) in listOf(Triple("",false,false),Triple(previous,false,false),
                    Triple("advanced",false,true),Triple("",true,true),Triple(previous,true,true))) {
                next=cursor;end=terminal
                val count=requests.size
                val result=h.binding.getMobileSortedSubCommentsForSubject(42,1,901,2,previous,9876543210L,false)
                assertEquals(valid,result.isSuccess,"cursor=$cursor terminal=$terminal")
                assertEquals(count+1,requests.size)
                assertEquals(listOf(42L,1L,901L,9876543210L,2L,previous),requests.last())
                if(valid) { assertTrue(result.getOrThrow().replies.orEmpty().isEmpty());assertEquals(cursor,result.getOrThrow().grpcNextOffset) }
                else assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }
            status="14"
            assertTrue(h.binding.getMobileSortedSubCommentsForSubject(42,1,901,2,null,0,false).isFailure)
            assertEquals(6,requests.size)
            assertEquals(0,h.tls.seen.count { it.target.startsWith(LOCATIONS) })
            assertTrue(h.tls.seen.all { !it.cookie.orEmpty().contains("SESSDATA") && !it.cookie.orEmpty().contains("bili_jct") && it.authorization==null })
            assertTrue(h.unexpected.isEmpty(),h.unexpected.toString())
        }
    }

    @Test fun locationSupplementIsOptInAfterValidCursorAndUsesTheRealEndpoint() = runBlocking {
        Harness().use { h ->
            var next="advanced"
            h.tls.route={ e -> when(e.requestURI.path) {
                RPC -> { readRequest(e);h.tls.grpc(e,detail(next,false,child=true)) }
                BUVID -> h.tls.respond(e,200,"""{"code":0}""")
                LOCATIONS -> h.tls.respond(e,200,"""{"code":0,"data":{"page":{"count":1},"replies":[{"rpid":902,"root":901,"oid":42,"reply_control":{"location":"IP属地：测试"}}]}}""")
                else -> { h.unexpected+=e.requestURI.toString();h.tls.respond(e,500,"{}") }
            } }
            val without=h.binding.getMobileSortedSubCommentsForSubject(42,1,901,3,"old",0,false).getOrThrow()
            assertEquals(902L,without.replies!!.single().rpid)
            assertTrue(without.replies!!.single().replyControl?.location.isNullOrBlank())
            assertEquals(1,h.tls.seen.count { it.target==RPC })
            assertEquals(0,h.tls.seen.count { it.target.startsWith(LOCATIONS) })
            val with=h.binding.getMobileSortedSubCommentsForSubject(42,1,901,3,"old",0,true).getOrThrow()
            assertEquals("IP属地：测试",with.replies!!.single().replyControl?.location)
            assertEquals(2,h.tls.seen.count { it.target==RPC })
            val location=h.tls.seen.single { it.target.startsWith(LOCATIONS) }
            val q=("https://api.bilibili.com"+location.target).toHttpUrl()
            assertEquals("42",q.queryParameter("oid"));assertEquals("1",q.queryParameter("type"))
            assertEquals("901",q.queryParameter("root"));assertEquals("1",q.queryParameter("pn"))
            next="old"
            assertTrue(h.binding.getMobileSortedSubCommentsForSubject(42,1,901,3,"old",0,true).isFailure)
            assertEquals(3,h.tls.seen.count { it.target==RPC })
            assertEquals(1,h.tls.seen.count { it.target.startsWith(LOCATIONS) })
            assertTrue(h.unexpected.isEmpty(),h.unexpected.toString())
        }
    }

    @Test fun suspendedSubReplyRejectsCancellationAccountChangeAndRetiredOwner() = runBlocking {
        for (cause in listOf("cancel","account","owner")) Harness().use { h ->
            h.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-a","bili_jct" to "csrf-a"),AccountSummary(11,"A",""))
            h.bind()
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            h.tls.route={ e -> when(e.requestURI.path) {
                RPC -> { readRequest(e);entered.countDown();check(release.await(10,TimeUnit.SECONDS));h.tls.grpc(e,detail("next",false,child=true)) }
                BUVID -> h.tls.respond(e,200,"""{"code":0}""")
                else -> { h.unexpected+=e.requestURI.toString();h.tls.respond(e,500,"{}") }
            } }
            val pending=async(Dispatchers.IO) { runCatching { h.binding.getMobileSortedSubCommentsForSubject(42,1,901,2,null,0,true).getOrThrow() } }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(10,TimeUnit.SECONDS) })
                when(cause) {
                    "cancel" -> pending.cancel()
                    "account" -> h.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-b","bili_jct" to "csrf-b"),AccountSummary(22,"B",""))
                    else -> h.owned=false
                }
            } finally { release.countDown() }
            if(cause=="cancel") {
                withTimeout(10000) { pending.join() };assertTrue(pending.isCancelled)
                assertFailsWith<CancellationException> { pending.await() }
            } else assertTrue(withTimeout(10000) { pending.await() }.exceptionOrNull() is CancellationException)
            assertEquals(1,h.tls.seen.count { it.target==RPC })
            assertEquals(0,h.tls.seen.count { it.target.startsWith(LOCATIONS) })
            assertTrue(h.tls.seen.none { it.cookie.orEmpty().contains("synthetic-b") })
            assertTrue(h.unexpected.isEmpty(),h.unexpected.toString())
        }
    }

    @Test fun bothMobileMethodsBootstrapEmptyStoreBeforeCapturingTokens() = runBlocking {
        for (sub in listOf(false,true)) Harness().use { h ->
            assertNull(h.sessions.currentCookies()["buvid3"])
            h.tls.route={ e -> when(e.requestURI.path) {
                RPC -> { readRequest(e);h.tls.grpc(e,detail("next",false)) }
                MAIN_RPC -> h.tls.grpc(e,ProtoWire.message(ProtoWire.bytes(1,ProtoWire.bool(4,true)), ProtoWire.bytes(2,ProtoWire.message(ProtoWire.int64(2,903),ProtoWire.int64(3,42),ProtoWire.bytes(12,ProtoWire.string(1,"fixture main reply"))))))
                BUVID -> h.tls.respond(e,200,"""{"code":0}""")
                else -> { h.unexpected+=e.requestURI.toString();h.tls.respond(e,500,"{}") }
            } }
            mobile(h,sub).getOrThrow()
            assertEquals(1,h.tls.seen.count { it.target=="/" })
            assertEquals(1,h.tls.seen.count { it.target.startsWith(SPI) })
            val business=h.tls.seen.single { it.target in setOf(RPC,MAIN_RPC) }
            assertTrue(business.cookie.orEmpty().contains("buvid3=visitor-bootstrap"))
            assertTrue(h.tls.seen.all { !it.cookie.orEmpty().contains("SESSDATA") && !it.cookie.orEmpty().contains("bili_jct") && it.authorization==null })
            mobile(h,sub).getOrThrow()
            assertEquals(1,h.tls.seen.count { it.target=="/" })
            assertEquals(1,h.tls.seen.count { it.target.startsWith(SPI) })
            assertEquals(2,h.tls.seen.count { it.target in setOf(RPC,MAIN_RPC) })
            assertTrue(h.unexpected.isEmpty(),h.unexpected.toString())
        }
    }

    @Test fun bootstrapCancellationAccountSwitchAndRetirementNeverSendBusinessRequests() = runBlocking {
        for(sub in listOf(false,true)) for(cause in listOf("cancel","account","owner")) Harness().use { h ->
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            h.tls.bootstrapRoute={ e ->
                if(e.requestURI.path=="/") h.tls.respond(e,200,"visitor") else {
                    entered.countDown();check(release.await(10,TimeUnit.SECONDS))
                    runCatching { h.tls.respond(e,200,"""{"code":0,"data":{"b_3":"late-visitor"}}""") }
                }
            }
            h.tls.route={ e -> h.unexpected+=e.requestURI.toString();h.tls.respond(e,500,"{}") }
            val pending=async(Dispatchers.IO) { runCatching { mobile(h,sub).getOrThrow() } }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(10,TimeUnit.SECONDS) })
                when(cause) {
                    "cancel" -> pending.cancel()
                    "account" -> h.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-new","bili_jct" to "csrf-new"),AccountSummary(99,"New",""))
                    else -> h.owned=false
                }
            } finally { release.countDown() }
            if(cause=="cancel") {
                withTimeout(10000) { pending.join() };assertTrue(pending.isCancelled)
                assertFailsWith<CancellationException> { pending.await() }
            } else assertTrue(withTimeout(10000) { pending.await() }.exceptionOrNull() is CancellationException)
            assertEquals(1,h.tls.seen.count { it.target=="/" })
            assertEquals(1,h.tls.seen.count { it.target.startsWith(SPI) })
            assertEquals(0,h.tls.seen.count { it.target in setOf(RPC,MAIN_RPC) })
            assertTrue(h.unexpected.isEmpty(),h.unexpected.toString())
        }
    }

    @Test fun concurrentEmptyStoreRequestsShareTheExistingBootstrap() = runBlocking {
        Harness().use { h ->
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            h.tls.bootstrapRoute={ e ->
                if(e.requestURI.path=="/") {
                    entered.countDown();check(release.await(10,TimeUnit.SECONDS));h.tls.respond(e,200,"visitor")
                } else h.tls.respond(e,200,"""{"code":0,"data":{"b_3":"concurrent-visitor"}}""")
            }
            h.tls.route={ e -> when(e.requestURI.path) {
                RPC -> h.tls.grpc(e,detail("next",false))
                MAIN_RPC -> h.tls.grpc(e,ProtoWire.message(ProtoWire.bytes(1,ProtoWire.bool(4,true)), ProtoWire.bytes(2,ProtoWire.message(ProtoWire.int64(2,903),ProtoWire.int64(3,42),ProtoWire.bytes(12,ProtoWire.string(1,"fixture main reply"))))))
                BUVID -> h.tls.respond(e,200,"""{"code":0}""")
                else -> { h.unexpected+=e.requestURI.toString();h.tls.respond(e,500,"{}") }
            } }
            val first=async(Dispatchers.IO) { mobile(h,false) }
            var second:kotlinx.coroutines.Deferred<Result<ReplyData>>?=null
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(10,TimeUnit.SECONDS) })
                second=async(Dispatchers.IO,start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { mobile(h,true) }
            } finally { release.countDown() }
            withTimeout(10000) { first.await().getOrThrow();checkNotNull(second).await().getOrThrow() }
            assertEquals(1,h.tls.seen.count { it.target=="/" })
            assertEquals(1,h.tls.seen.count { it.target.startsWith(SPI) })
            assertEquals(1,h.tls.seen.count { it.target==RPC })
            assertEquals(1,h.tls.seen.count { it.target==MAIN_RPC })
            assertTrue(h.tls.seen.filter { it.target in setOf(RPC,MAIN_RPC) }.all { it.cookie.orEmpty().contains("buvid3=concurrent-visitor") })
            assertTrue(h.unexpected.isEmpty(),h.unexpected.toString())
        }
    }

    @Test fun ordinaryBootstrapFailuresStayInResultForBothMobileMethods() = runBlocking {
        for(sub in listOf(false,true)) for(failSpi in listOf(false,true)) Harness().use { h ->
            h.tls.bootstrapRoute={ e -> when {
                !failSpi -> h.tls.respond(e,503,"unavailable")
                e.requestURI.path=="/" -> h.tls.respond(e,200,"visitor")
                else -> h.tls.respond(e,200,"""{"code":-500,"message":"synthetic spi failure"}""")
            } }
            h.tls.route={ e -> h.unexpected+=e.requestURI.toString();h.tls.respond(e,500,"{}") }
            val result=mobile(h,sub)
            assertTrue(result.isFailure);assertFalse(result.exceptionOrNull() is CancellationException)
            assertEquals(1,h.tls.seen.count { it.target=="/" })
            assertEquals(if(failSpi) 1 else 0,h.tls.seen.count { it.target.startsWith(SPI) })
            assertEquals(0,h.tls.seen.count { it.target in setOf(RPC,MAIN_RPC) })
            assertTrue(h.unexpected.isEmpty(),h.unexpected.toString())
        }
    }

    private suspend fun mobile(h:Harness,sub:Boolean):Result<ReplyData> =
        if(sub) h.binding.getMobileSortedSubCommentsForSubject(42,1,901,2,null,0,false)
        else h.binding.getMobileCommentsForSubject(42,1,1,20,3,CommentMainListContinuation(CommentMainListRoute.GRPC,0),false)

    private fun readRequest(e:HttpExchange):List<Any?> {
        check(e.requestMethod=="POST")
        val fields=ProtoWire.parseFields(ProtoWire.unframe(e.requestBody.readAllBytes()))
        fun n(id:Int)=fields.single { it.number==id }.varint
        val paging=fields.singleOrNull { it.number==8 }
        val offset=paging?.let { ProtoWire.parseFields(it.bytes).single { f->f.number==2 } }?.let(ProtoWire::stringValue)
        return listOf(n(1),n(2),n(3),n(4),n(7),offset)
    }
    private fun detail(next:String,end:Boolean,child:Boolean=false):ByteArray {
        val childBytes=ProtoWire.message(ProtoWire.int64(2,902),ProtoWire.int64(3,42),ProtoWire.int32(4,1),ProtoWire.int64(6,901))
        val root=ProtoWire.message(ProtoWire.int64(2,901),ProtoWire.int64(3,42),ProtoWire.int32(4,1),
            ProtoWire.bytes(14,ProtoWire.string(11,"已有属地")),
            *if(child) arrayOf(ProtoWire.bytes(1,childBytes)) else emptyArray())
        return ProtoWire.message(ProtoWire.bytes(1,ProtoWire.bool(4,end)),ProtoWire.bytes(3,root),
            ProtoWire.bytes(8,ProtoWire.string(1,next)))
    }
    private companion object {
        const val SPI="/x/frontend/finger/spi"
        const val MAIN_RPC="/bilibili.main.community.reply.v1.Reply/MainList"
        const val RPC="/bilibili.main.community.reply.v1.Reply/DetailList"
        const val LOCATIONS="/x/v2/reply/reply"
        const val BUVID="/x/internal/gaia-gateway/ExClimbWuzhi"
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
            val retrofit=DesktopRepository::class.java.getDeclaredMethod("retrofit",String::class.java).apply { isAccessible=true }
                .invoke(repository,"https://api.bilibili.com/") as retrofit2.Retrofit
            DesktopRepository::class.java.getDeclaredField("buvidApi").apply { isAccessible=true }
                .set(repository,retrofit.create(com.android.purebilibili.core.network.BuvidApi::class.java))
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
        @Volatile var bootstrapRoute: ((HttpExchange) -> Unit)? = null
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
                "SAN=dns:app.bilibili.com,dns:api.bilibili.com,dns:www.bilibili.com,dns:broadcast.chat.bilibili.com,dns:sponsor.fixture.test",
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
                try {
                    if (exchange.requestURI.path in setOf("/", "/x/frontend/finger/spi")) {
                        bootstrapRoute?.invoke(exchange) ?: when(exchange.requestURI.path) {
                            "/" -> respond(exchange,200,"fixture visitor home")
                            else -> respond(exchange,200,"""{"code":0,"data":{"b_3":"visitor-bootstrap","b_4":"synthetic-four"}}""")
                        }
                    } else route(exchange)
                } finally { exchange.close() }
            }
            server.start()
            val trusted = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("fixture", keyStore.getCertificate("fixture")) }
            trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
                .trustManagers.single() as X509TrustManager
        }

        fun grpc(exchange: HttpExchange, payload: ByteArray, status: String = "0") {
            exchange.responseHeaders.add("Content-Type", "application/grpc")
            exchange.responseHeaders.add("grpc-status", status)
            val bytes = ProtoWire.frame(payload, gzipMinLength = Int.MAX_VALUE)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
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
