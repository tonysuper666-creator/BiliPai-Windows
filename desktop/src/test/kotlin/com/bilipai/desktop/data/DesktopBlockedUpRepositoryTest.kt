package com.bilipai.desktop.data

import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.CommunityBatch
import com.bilipai.desktop.ui.CommunityFeedState
import com.bilipai.desktop.ui.readDesktopBlockedImportFile
import com.bilipai.desktop.ui.communitySearchRows
import com.bilipai.desktop.ui.desktopVisibleSearchRows
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DesktopBlockedUpRepositoryTest {
    private class Fixture(loggedIn: Boolean = true) {
        val root = Files.createTempDirectory("blocked-management-owned-")
        val pluginStore = DesktopPluginStore(root)
        val store = DesktopBlockedUpStore(DesktopPluginContext(pluginStore))
        val sessions = DesktopSessionStore(root.resolve("synthetic-credentials.json"), persistent = false)
        val repository = DesktopRepository(sessions)
        val paths = mutableListOf<String>()
        val fields = mutableListOf<Map<String, String>>()
        val delays = mutableListOf<Long>()
        init { if (loggedIn) login("synthetic-account-A") }
        fun login(token: String) { sessions.saveAccount(mapOf("SESSDATA" to token, "bili_jct" to "synthetic-csrf"), AccountSummary(12, "Fixture", "")) }
        fun api(ensure: suspend () -> Unit = {}, response: (Request) -> Pair<Int, String>): DesktopBlockedUpRepository {
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).addInterceptor { chain ->
                val request = chain.request()
                check(request.url.host == "api.bilibili.com" && request.url.encodedPath in setOf("/x/relation/modify", "/x/relation/blacks", "/x/web-interface/card"))
                synchronized(paths) { paths += request.url.encodedPath }
                request.body?.let { body -> val buffer = Buffer(); body.writeTo(buffer)
                    synchronized(fields) { fields += buffer.readUtf8().split('&').associate { part ->
                        val pair = part.split('=', limit = 2); java.net.URLDecoder.decode(pair[0], Charsets.UTF_8) to
                            java.net.URLDecoder.decode(pair.getOrElse(1) { "" }, Charsets.UTF_8)
                    } }
                }
                val (code, text) = response(request)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                    .body(text.toResponseBody("application/json".toMediaType())).build()
            }.build()
            return DesktopBlockedUpRepository.forTests(repository, store, client, ensureSession = ensure,
                waitBetweenRequests = { delays += it })
        }
        fun card(mid: Long, name: String = "Refreshed$mid") = """{"code":0,"data":{"card":{"mid":"$mid","name":"$name","face":"face-$mid","sign":"full profile","level_info":{"current_level":6},"Official":{"title":"verified"},"vip":{"label":{"text":"VIP"}}},"follower":101,"archive_count":9}}"""
        fun blacks(ids: List<Long>, total: Int = ids.size): String = buildJsonObject {
            put("code", 0); put("data", buildJsonObject { put("total", total); put("list", JsonArray(ids.map { id ->
                buildJsonObject { put("mid", id); put("uname", " Remote$id "); put("face", "remote-face"); put("sign", "remote-sign") }
            })) })
        }.toString()
    }

    @Test fun `local guest block and unblock never make remote requests`(): Unit = runBlocking {
        val f = Fixture(false); val api = f.api { error("Guest cannot reach transport") }
        val result = api.blockUpWithBilibiliSync(23, "Actual author", "face")
        assertEquals(BilibiliBlockedListRemoteStatus.SKIPPED_NOT_LOGGED_IN, result.remoteStatus)
        assertEquals("Actual author", f.store.records.value.single().name)
        assertEquals(BilibiliBlockedListRemoteStatus.SKIPPED_NOT_LOGGED_IN, api.unblockUpWithBilibiliSync(23).remoteStatus)
        assertTrue(f.store.records.value.isEmpty()); assertTrue(f.paths.isEmpty())
        assertTrue(api.importFromBilibili().isFailure)
    }

    @Test fun `original local first block and unblock retain local changes on remote code failure`(): Unit = runBlocking {
        val f = Fixture(); val api = f.api { 200 to """{"code":-400,"message":"synthetic rejection"}""" }
        assertEquals(BilibiliBlockedListRemoteStatus.FAILED, api.blockUpWithBilibiliSync(44, "Author", "face", BlockedUpRelationSource.COMMENT).remoteStatus)
        assertEquals(setOf(44L), f.store.mids.value)
        assertEquals(BilibiliBlockedListRemoteStatus.FAILED, api.unblockUpWithBilibiliSync(44).remoteStatus)
        assertTrue(f.store.mids.value.isEmpty())
        assertEquals(listOf("5", "6"), f.fields.map { it["act"] })
        assertEquals(listOf("15", "11"), f.fields.map { it["re_src"] })
        assertEquals(listOf("44", "44"), f.fields.map { it["fid"] })
        assertTrue(f.fields.all { it["csrf"] == "synthetic-csrf" })
    }

    @Test fun `successful original relation returns full write message and persistence failure prevents POST`(): Unit = runBlocking {
        val f = Fixture(); val api = f.api { 200 to """{"code":0}""" }
        val result = api.blockUpWithBilibiliSync(44, "Author", "face")
        assertEquals(BilibiliBlockedListRemoteStatus.SUCCESS, result.remoteStatus)
        assertTrue(result.message.contains("并已写入 B站黑名单"))
        f.pluginStore.freezeWrites()
        assertFailsWith<IllegalStateException> { api.blockUpWithBilibiliSync(45, "Author", "face") }
        assertEquals(1, f.paths.size); assertEquals(setOf(44L), f.store.mids.value)
    }

    @Test fun `actual loopback 503 retry after zero still receives exactly one original POST`(): Unit = runBlocking {
        val f = Fixture(); val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/x/relation/modify") { exchange ->
            assertEquals("POST", exchange.requestMethod); exchange.requestBody.readAllBytes()
            calls.incrementAndGet(); exchange.responseHeaders.add("Retry-After", "0")
            val bytes = """{"code":-1}""".toByteArray()
            exchange.sendResponseHeaders(503, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
            val api = DesktopBlockedUpRepository.forTests(f.repository, f.store, client,
                baseUrl = "http://127.0.0.1:${server.address.port}/")
            assertEquals(BilibiliBlockedListRemoteStatus.FAILED, api.blockUpWithBilibiliSync(44, "Author", "face").remoteStatus)
            assertEquals(1, calls.get()); assertEquals(setOf(44L), f.store.mids.value)
        } finally { server.stop(0) }
    }

    @Test fun `same MID credential epoch change before request retains local action but rejects stale account POST`(): Unit = runBlocking {
        val f = Fixture()
        val api = f.api(ensure = { f.login("synthetic-account-B") }) { error("Stale request reached transport") }
        val epoch = f.repository.sessionEpoch
        assertFailsWith<BiliApiException> { api.blockUpWithBilibiliSync(46, "Author", "", expectedSessionEpoch = epoch) }
        assertEquals(setOf(46L), f.store.mids.value); assertTrue(f.paths.isEmpty())
        assertFailsWith<BiliApiException> { api.unblockUpWithBilibiliSync(46, expectedSessionEpoch = epoch) }
        assertEquals(setOf(46L), f.store.mids.value)
    }

    @Test fun `pull uses original pages defaults mapper delay and full real profile response`(): Unit = runBlocking {
        val f = Fixture(); val seenPages = mutableListOf<Int>()
        val api = f.api { request -> when (request.url.encodedPath) {
            "/x/relation/blacks" -> { assertEquals("50", request.url.queryParameter("ps")); val page = request.url.queryParameter("pn")!!.toInt()
                seenPages += page; 200 to f.blacks(if (page == 1) (100L..149L).toList() else listOf(150), 51) }
            "/x/web-interface/card" -> { assertEquals("true", request.url.queryParameter("photo")); 200 to f.card(request.url.queryParameter("mid")!!.toLong()) }
            else -> error("Pull must not POST")
        } }
        val result = api.importFromBilibili().getOrThrow()
        assertEquals(51, result.importedCount); assertEquals(listOf(1, 2), seenPages)
        assertEquals(1, f.delays.count { it == 220L }); assertEquals(50, f.delays.count { it == 120L })
        val row = f.store.records.value.first()
        assertEquals(6, row.level); assertEquals(101L, row.follower); assertEquals(9, row.archiveCount)
        assertEquals("VIP", row.vipLabel); assertEquals("verified", row.officialTitle)
        assertEquals("full profile", row.sign); assertNotNull(row.lastSyncedAt); assertFalse(row.isDeleted)
        assertTrue(f.fields.isEmpty())
    }

    @Test fun `original pull total can stop a full page and page failure never partially imports`(): Unit = runBlocking {
        val f = Fixture(); var pages = 0
        val api = f.api { req -> if (req.url.encodedPath == "/x/relation/blacks") {
            pages++; 200 to f.blacks(List(50) { 900L + it }, 50)
        } else 200 to f.card(req.url.queryParameter("mid")!!.toLong()) }
        assertEquals(50, api.importFromBilibili().getOrThrow().importedCount); assertEquals(1, pages)
        val g = Fixture(); val fail = g.api { req ->
            if (req.url.queryParameter("pn") == "1") 200 to g.blacks((100L..149L).toList(), 51)
            else 200 to """{"code":-403,"message":"fixture failure"}"""
        }
        assertTrue(fail.importFromBilibili().isFailure); assertTrue(g.store.records.value.isEmpty())
    }

    @Test fun `original pull page bound is 120 even with a never ending full duplicate page`(): Unit = runBlocking {
        val f = Fixture(); var pages = 0
        val api = f.api { req -> if (req.url.encodedPath == "/x/relation/blacks") {
            pages++; assertTrue(req.url.queryParameter("pn")!!.toInt() <= 120); 200 to f.blacks(List(50) { 7L }, 0)
        } else 200 to f.card(7) }
        val result = api.importFromBilibili().getOrThrow()
        assertEquals(120, pages); assertEquals(1, result.importedCount); assertEquals(5999, result.failedCount)
        assertEquals(120, f.delays.count { it == 220L }) // Original loop delays after the final full page too.
    }

    @Test fun `original profile missing card marks suspected deleted while transport error leaves metadata intact`(): Unit = runBlocking {
        val f = Fixture(false); f.store.upsert(BlockedUp(70, "Keep", "old", level = 4)); f.store.upsert(BlockedUp(71, "Keep2", "old2"))
        val api = f.api { req -> if (req.url.queryParameter("mid") == "70") 200 to """{"code":-404,"data":null}"""
            else throw java.io.IOException("fixture only") }
        val result = api.refreshBlockedUpProfiles()
        assertEquals(1, result.deletedCount); assertEquals(1, result.failedCount)
        val deleted = f.store.records.value.single { it.mid == 70L }
        assertTrue(deleted.isDeleted); assertEquals("Keep", deleted.name); assertEquals(4, deleted.level); assertNotNull(deleted.lastSyncedAt)
        assertNull(f.store.records.value.single { it.mid == 71L }.lastSyncedAt)
    }

    @Test fun `delayed real Retrofit profile cannot resurrect removed or reblocked record`(): Unit = runBlocking {
        val f = Fixture(false); val old = BlockedUp(80, "Old", "", blockedAt = 100); f.store.upsert(old)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val api = f.api { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); 200 to f.card(80) }
        val job = async(Dispatchers.Default) { api.refreshBlockedUpProfiles() }
        check(entered.await(5, TimeUnit.SECONDS)); f.store.remove(80); f.store.upsert(old.copy(name = "Reblocked", blockedAt = 200))
        release.countDown(); val result = job.await()
        assertEquals(0, result.updatedCount); assertEquals("Reblocked", f.store.records.value.single().name)
        assertNull(f.store.records.value.single().lastSyncedAt)
    }

    @Test fun `profile account epoch change and cancellation never commit stale metadata`(): Unit = runBlocking {
        val f = Fixture(); f.store.upsert(BlockedUp(81, "Old", ""))
        val api = f.api { f.login("synthetic-new-token"); 200 to f.card(81) }
        assertFailsWith<BiliApiException> { api.refreshBlockedUpProfiles() }
        assertEquals("Old", f.store.records.value.single().name)
        val entered = CompletableDeferred<Unit>()
        val cancelled = f.api(ensure = { entered.complete(Unit); awaitCancellation() }) { error("Cancelled session reached API") }
        val job = async { cancelled.refreshBlockedUpProfiles() }; entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertEquals("Old", f.store.records.value.single().name)
    }

    @Test fun `JSON file uses original share model with metadata and never remote import side effects`(): Unit = runBlocking {
        val f = Fixture(false)
        val up = BlockedUp(91, "中文名字", "face", sign = "profile", vipLabel = "VIP", level = 6,
            officialTitle = "Official", follower = 10, archiveCount = 9, isDeleted = true)
        val path = f.root.resolve("explicit-user-export.json")
        Files.writeString(path, buildBlockedUpShareJson(listOf(up)))
        val api = f.api { error("Local import cannot use transport") }
        assertEquals(1, api.importBlockedUps(readDesktopBlockedImportFile(path)).importedCount)
        assertEquals(up.copy(blockedAt = f.store.records.value.single().blockedAt), f.store.records.value.single())
        assertTrue(f.paths.isEmpty()); assertFalse(Files.readString(path).contains("lastSyncedAt"))
    }

    @Test fun `blacklist changes transform retained dynamic rows without losing cursor or scroll owner`(): Unit {
        val a = DynamicItem(id_str = "1", modules = DynamicModules(module_author = DynamicAuthorModule(mid = 11)))
        val b = DynamicItem(id_str = "2", modules = DynamicModules(module_author = DynamicAuthorModule(mid = 12)))
        val page = CommunityFeedState<DynamicItem, String>(); val scroll = page.scroll
        page.acceptBatch(0, CommunityBatch(listOf(a, b), "real-next-cursor"), true) { it.id_str }
        assertEquals(listOf(b), desktopVisibleDynamicItems(page.rows, setOf(11)))
        assertEquals("real-next-cursor", page.next); assertSame(scroll, page.scroll)
        assertEquals(listOf(a, b), desktopVisibleDynamicItems(page.rows, emptySet()))
        assertEquals(2, page.rows.size)
    }

    @Test fun `search maps exact original video owner UP and both live uid fields without filtering photo media or article`(): Unit {
        val results = listOf(
            CommunitySearchResult.Videos(SearchTypeData(result = listOf(SearchVideoItem(bvid = "BV-fixture", mid = 23)))),
            CommunitySearchResult.Users(SearchUpData(result = listOf(SearchUpItem(mid = 23)))),
            CommunitySearchResult.LiveRooms(LiveRoomSearchData(result = listOf(LiveRoomSearchItem(uid = 23, roomid = 41)))),
            CommunitySearchResult.LiveUsers(SearchLiveUserData(result = listOf(SearchLiveUserItem(uid = 23, roomid = 0))))
        )
        results.forEach { result ->
            val rows = communitySearchRows(result)
            assertEquals(23L, rows.single().blockedOwnerMid)
            assertTrue(desktopVisibleSearchRows(rows, setOf(23)).isEmpty())
            assertEquals(rows, desktopVisibleSearchRows(rows, emptySet()))
        }
        val photo = communitySearchRows(CommunitySearchResult.Photos(SearchPhotoData(result = listOf(SearchPhotoItem(mid = 23, id = 21)))))
        assertEquals(photo, desktopVisibleSearchRows(photo, setOf(23)))
        val liveOn = communitySearchRows(CommunitySearchResult.LiveUsers(SearchLiveUserData(result = listOf(SearchLiveUserItem(uid = 23, roomid = 41)))))
        assertEquals(41L, liveOn.single().live); assertEquals(0L, liveOn.single().user)
        assertTrue(desktopVisibleSearchRows(liveOn, setOf(23)).isEmpty())
    }

    @Test fun `corrupt migration blocks manual pull before transport and reports a readable local error`(): Unit = runBlocking {
        val f = Fixture()
        val legacy = f.root.resolve("discovery/plugin-settings.json")
        Files.createDirectories(legacy.parent); Files.writeString(legacy, "{broken")
        val api = f.api { error("Corrupt migration must be resolved before account requests") }
        val result = api.importFromBilibili()
        assertTrue(result.isFailure); assertTrue(result.exceptionOrNull()!!.message!!.contains("本地黑名单"))
        assertTrue(f.paths.isEmpty()); assertTrue(f.store.records.value.isEmpty()); assertNotNull(f.store.migrationError.value)
    }
}
