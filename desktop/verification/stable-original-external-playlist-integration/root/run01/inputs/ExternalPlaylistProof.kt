package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.data.repository.DesktopOriginalExternalPlaylistSchema as Schema
import com.bilipai.desktop.plugins.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.*
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private var assertions = 0
private fun verify(value: Boolean, message: String) { check(value) { message }; assertions++ }
private inline fun <reified T> api(noinline invoke: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        invoke(method.name, args ?: emptyArray())
    } as T

fun main() = runBlocking {
    withTimeout(40_000) {
        val owner = AtomicBoolean(true)
        fun checkOwner() { if (!owner.get()) throw CancellationException("Fixture captured account retired") }
        var nav = NavResponse(data = NavData())
        var navFailure: Exception? = null
        var afterNav: () -> Unit = {}
        var afterSearch: () -> Unit = {}
        var searchCalls = 0
        var query = emptyMap<String, String>()
        var reply = SearchTypeResponse(data = SearchTypeData(page = 1, numResults = 1, numPages = 1,
            result = listOf(SearchVideoItem(bvid = "BV1original", title = "<em>Song</em>", pic = "//cover", author = "UP", duration = "03:00", typeId = 3))))
        val searchApi = api<SearchApi> { method, args ->
            check(method == "search")
            @Suppress("UNCHECKED_CAST")
            query = (args[0] as Map<String, String>).toMap()
            searchCalls++
            afterSearch()
            reply
        }
        val navApi = api<BilibiliApi> { method, _ ->
            check(method == "getNavInfo")
            navFailure?.let { throw it }
            afterNav()
            nav
        }
        val search = DesktopOriginalCapturedVideoSearch(searchApi, navApi, ::checkOwner)
        val (videos, page) = search.search("song").getOrThrow()
        verify(query["page_size"] == "20" && query["platform"] == "pc", "original search parameter defaults")
        verify(query["order"] == "totalrank" && query["duration"] == "0", "original sorting and duration values")
        verify(!query.containsKey("tids") && !query.containsKey("pubtime_begin_s"), "original unset filter omission")
        verify(!query.containsKey("w_rid"), "missing nav keys preserve original unsigned fallback")
        verify(videos.single().title == "Song" && videos.single().pic == "https://cover", "original search conversion and HTML cleanup")
        verify(videos.single().duration == 180 && videos.single().tid == 3, "original duration and zone conversion")
        verify(page.currentPage == 1 && page.totalResults == 1 && page.hasMore, "original nonempty-page pagination policy")
        nav = NavResponse(data = NavData(wbi_img = WbiImg("https://i/${"a".repeat(32)}.png", "https://i/${"b".repeat(32)}.png")))
        search.search("song", order = SearchOrder.CLICK, duration = SearchDuration.TEN_TO_30MIN, tids = 3, page = 2, pubBegin = 10, pubEnd = 20).getOrThrow()
        verify(query.containsKey("w_rid") && query.containsKey("wts"), "original WBI signer consumes nav keys")
        verify(query["tids"] == "3" && query["pubtime_begin_s"] == "10" && query["pubtime_end_s"] == "20", "original optional filters retained")
        verify(query["page"] == "2" && query["order"] == "click", "original explicit search page/order")
        navFailure = IOException("nav offline")
        search.search("fallback").getOrThrow()
        verify(!query.containsKey("w_rid"), "ordinary nav failure preserves unsigned fallback")
        navFailure = CancellationException("caller cancelled")
        verify(runCatching { search.search("cancel") }.exceptionOrNull() is CancellationException, "nav cancellation propagates")
        navFailure = null
        val beforeRetirement = searchCalls
        afterNav = { owner.set(false) }
        verify(runCatching { search.search("retired nav") }.exceptionOrNull() is CancellationException, "account retirement after nav rejected")
        verify(searchCalls == beforeRetirement, "retired nav cannot issue search")
        owner.set(true); afterNav = {}; afterSearch = { owner.set(false) }
        verify(runCatching { search.search("late result") }.exceptionOrNull() is CancellationException, "late old-account search rejected before conversion")
        owner.set(true); afterSearch = {}; reply = SearchTypeResponse(code = -412)
        verify(search.search("blocked").exceptionOrNull()?.message == "搜索请求被拦截，请稍后重试", "original search error policy")

        val responseText = AtomicReference("""{"data":{"cdlist":[{"dissname":"QQ List","nickname":"Owner","logo":"cover","songlist":[{"name":"Song","singer":[{"name":"Artist"}],"album":{"name":"Album","mid":"MID"},"interval":200,"subtitle":"Translated"},{"name":""},7]}]}}""")
        val handlerPool = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = handlerPool
        val startedBody = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        val startedRetiredBody = CountDownLatch(1)
        val releaseRetiredBody = CountDownLatch(1)
        val readingBody = CountDownLatch(1)
        val readingRetiredBody = CountDownLatch(1)
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.use { it.readBytes() }
                if (exchange.requestURI.path in listOf("/slow", "/retired")) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write("first".toByteArray()); exchange.responseBody.flush()
                    val retired = exchange.requestURI.path == "/retired"
                    (if (retired) startedRetiredBody else startedBody).countDown()
                    (if (retired) releaseRetiredBody else releaseBody).await(5, TimeUnit.SECONDS)
                    exchange.responseBody.write("last".toByteArray())
                } else {
                    val bytes = responseText.get().toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
            } catch (_: IOException) { } finally { exchange.close() }
        }
        server.start()
        val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().url.encodedPath !in listOf("/slow", "/retired")) response else {
                val body = response.body
                val reads = AtomicInteger()
                val checked = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (reads.incrementAndGet() == 2)
                            (if (chain.request().url.encodedPath == "/retired") readingRetiredBody else readingBody).countDown()
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source(): BufferedSource = checked
                }).build()
            }
        }.build()
        val observed = mutableListOf<Request>()
        val latestCall = AtomicReference<Call>()
        val calls = Call.Factory { original ->
            synchronized(observed) { observed.add(original) }
            val rewritten = original.url.newBuilder().scheme("http").host("127.0.0.1").port(server.address.port).build()
            client.newCall(original.newBuilder().url(rewritten).build()).also(latestCall::set)
        }
        val domain = DesktopOriginalExternalPlaylistDomain(
            DesktopOriginalExternalPlaylistHttp(calls, requireNotNull(currentCoroutineContext()[Job]), ::checkOwner), search)
        try {
            val qq = domain.fetchPlaylist(Schema.Source.QQ, "12345").getOrThrow()
            verify(qq.name == "QQ List" && qq.author == "Owner" && qq.tracks.size == 1, "original QQ parser skips blank/scalar songs")
            verify(qq.tracks.single().artists == listOf("Artist") && qq.tracks.single().durationMs == 200000L, "original QQ artist/duration parser")
            verify(qq.tracks.single().coverUrl.endsWith("MID.jpg") && qq.tracks.single().translatedTitle == "Translated", "original QQ album-cover and translated title")
            verify(observed.last().header("Cookie") == null && observed.last().header("Referer") == "https://y.qq.com", "QQ request has only original public headers")
            responseText.set("""{"code":200,"playlist":{"name":"Cloud List","creator":{"nickname":"Cloud UP"},"tracks":[{"name":"Cloud Song","ar":[{"name":"Singer"}],"al":{"name":"Album","picUrl":"http://cover"},"dt":180123,"tns":["译名"]}]}}""")
            val cloud = domain.fetchPlaylist(Schema.Source.NETEASE, "54321").getOrThrow()
            verify(cloud.name == "Cloud List" && cloud.author == "Cloud UP", "original cloud playlist metadata")
            verify(cloud.tracks.single().coverUrl == "https://cover" && cloud.tracks.single().durationMs == 180123L, "original cloud cover normalization and milliseconds")
            verify(cloud.tracks.single().translatedTitle == "译名", "original cloud translated-title parser")
            val cloudRequest = observed.last()
            verify(cloudRequest.method == "POST" && cloudRequest.header("Cookie")?.contains("SESSDATA") == false, "cloud request retains only original public device cookie")
            val body = Buffer().also { requireNotNull(cloudRequest.body).writeTo(it) }.readUtf8()
            val encrypted = URLDecoder.decode(body.substringAfter("params="), Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
            val plaintext = cipher.doFinal(encrypted.chunked(2).map { it.toInt(16).toByte() }.toByteArray()).toString(Charsets.UTF_8)
            val pieces = plaintext.split("-36cd479b6b5-")
            val expectedMd5 = java.security.MessageDigest.getInstance("MD5").digest("nobody${pieces[0]}use${pieces[1]}md5forencrypt".toByteArray()).joinToString("") { "%02x".format(it) }
            verify(pieces[0] == "/api/v6/playlist/detail" && pieces[1].contains("\"id\":\"54321\""), "original eapi encrypted path/payload")
            verify(pieces[2] == expectedMd5 && pieces[1].contains("\"n\":\"1000\""), "original eapi MD5 and requested count")
            val requestCount = observed.size
            verify(domain.fetchPlaylist(Schema.Source.NETEASE, "abc").isFailure && observed.size == requestCount, "original invalid cloud id rejected before HTTP")
            responseText.set("""{"code":401}""")
            verify(domain.fetchPlaylist(Schema.Source.NETEASE, "54321").isFailure, "original cloud error response retained")

            nav = NavResponse(data = NavData())
            val track = Schema.ExternalTrack("Song", listOf("Artist", "Singer"), durationMs = 200000L, translatedTitle = "译名")
            reply = SearchTypeResponse(data = SearchTypeData(result = listOf(
                SearchVideoItem(bvid = "blacklisted", duration = "200", typeId = 26),
                SearchVideoItem(bvid = "zero", duration = "0", typeId = 3),
                SearchVideoItem(bvid = "too-long", duration = "221", typeId = 3),
                SearchVideoItem(bvid = "first", title = "First", author = "UP", duration = "180", typeId = 3),
                SearchVideoItem(bvid = "second", duration = "200", typeId = 3))))
            verify(domain.matchTrack(track).video?.bvid == "first", "original blacklist/duration/first-acceptable matching")
            verify(query["keyword"] == "Song 译名 - Artist Singer", "original translated title and artist search query")
            verify(domain.matchTrack(track.copy(durationMs = 0)).video == null, "original unknown track duration cannot match")
            reply = SearchTypeResponse(code = -412)
            verify(domain.matchTrack(track).video == null, "original ordinary search failure yields unmatched outcome")
            reply = SearchTypeResponse(data = SearchTypeData(result = listOf(SearchVideoItem(bvid = "first", duration = "200", typeId = 3))))
            val progress = mutableListOf<Int>()
            val resumed = domain.matchTracks(listOf(track, track, track), 2) { completed, total, _ -> verify(total == 3, "resume total remains full original size"); progress.add(completed) }
            verify(resumed.size == 1 && progress == listOf(3), "original resume skips completed tracks and keeps absolute progress")

            val storeRoot = Files.createTempDirectory("bilipai-original-import-")
            val store = DesktopPluginStore(storeRoot)
            store.update("settings", mapOf("unrelated" to JsonPrimitive("retain")))
            val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), owner::get, { action -> checkOwner(); action(); true })
            val checkpoint = Schema.ImportCheckpoint(cloud, listOf(Schema.MatchOutcome(cloud.tracks.single(), Schema.MatchedVideo("BV1saved", "Saved"))), 999)
            domain.saveImportCheckpoint(context, checkpoint)
            val restored = domain.loadImportCheckpoint(context)!!
            verify(restored.completedCount == 1 && restored.outcomes.single().video?.bvid == "BV1saved", "original checkpoint serialization and clamp")
            verify(store.preferences("settings")["unrelated"] == JsonPrimitive("retain"), "checkpoint shares actual settings document without dropping keys")
            domain.clearImportCheckpoint(context)
            verify(domain.loadImportCheckpoint(context) == null, "original clear checkpoint removes only checkpoint key")
            store.update("settings", mapOf("external_playlist_import_checkpoint_v1" to JsonPrimitive("invalid")))
            verify(domain.loadImportCheckpoint(context) == null, "original malformed checkpoint remains recoverable")
            val before = Files.readString(storeRoot.resolve("plugin-settings.json"))
            owner.set(false)
            verify(runCatching { domain.saveImportCheckpoint(context, checkpoint) }.exceptionOrNull() is CancellationException, "retired actual settings context rejects checkpoint write")
            verify(Files.readString(storeRoot.resolve("plugin-settings.json")) == before, "retired checkpoint leaves persisted document unchanged")
            owner.set(true)

            val cancelled = async(Dispatchers.IO) {
                val transport = DesktopOriginalExternalPlaylistHttp(calls, requireNotNull(currentCoroutineContext()[Job]), ::checkOwner)
                transport.withResponse(Request.Builder().url("http://fixture/slow").build()) { it.body.string() }
            }
            verify(withContext(Dispatchers.IO) { startedBody.await(5, TimeUnit.SECONDS) }, "real loopback body read started")
            verify(withContext(Dispatchers.IO) { readingBody.await(5, TimeUnit.SECONDS) }, "actual client is blocked inside the response body read")
            cancelled.cancel()
            withTimeout(2000) { cancelled.join() }
            verify(cancelled.isCancelled && latestCall.get().isCanceled(), "actual caller cancellation cancels HTTP while body read blocks")
            releaseBody.countDown()
            val retired = async(Dispatchers.IO) {
                val transport = DesktopOriginalExternalPlaylistHttp(calls, requireNotNull(currentCoroutineContext()[Job]), ::checkOwner)
                transport.withResponse(Request.Builder().url("http://fixture/retired").build()) { it.body.string() }
            }
            verify(withContext(Dispatchers.IO) { startedRetiredBody.await(5, TimeUnit.SECONDS) }, "old account body read started")
            verify(withContext(Dispatchers.IO) { readingRetiredBody.await(5, TimeUnit.SECONDS) }, "old account request is blocked inside response body read")
            owner.set(false); releaseRetiredBody.countDown()
            verify(runCatching { retired.await() }.exceptionOrNull() is CancellationException, "retirement during body read rejects old result")
        } finally {
            releaseBody.countDown(); releaseRetiredBody.countDown()
            server.stop(0); handlerPool.shutdownNow()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
        println("ExternalPlaylistProof PASS $assertions assertions; actual installed domain/search/HTTP + local loopback + same settings Store")
    }
}
