package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

// Source-only isolated fixture. Compile with the augmented Operations copy in this directory
// instead of the production Operations source; include the existing API/model producers once.
// No MockWebServer, socket, account import, Android context, UI, or shared Gradle mutation.
// The terminal application interceptor returns every response without chain.proceed.
// Therefore this verifies requests/result retirement, not BridgeInterceptor cookie persistence.
fun main(args: Array<String>): Unit = runBlocking {
    val root = Files.createTempDirectory("dynamic-editor-fake-api-")
    val sessions = DesktopSessionStore(root.resolve("fake-session.json"), persistent = false)
    fun credentials(suffix: String) = sessions.saveAccount(
        mapOf("SESSDATA" to "not-real-$suffix", "bili_jct" to "fixture-csrf"),
        AccountSummary(42L, "fixture", ""),
    )
    credentials("one")
    val repository = DesktopRepository(sessions)
    val records = Collections.synchronizedList(mutableListOf<Pair<Request, String>>())
    val hold = AtomicReference<Triple<CountDownLatch, CountDownLatch, CountDownLatch>?>(null)
    val activeHold = AtomicReference<Triple<CountDownLatch, CountDownLatch, CountDownLatch>?>(null)
    val failNextUpload = AtomicBoolean(false)
    val createBody = AtomicReference("""{"code":0,"data":{"dyn_id_str":"9001","dynamic_id_str":"9002","dyn_id":9003}}""")
    val uploadPath = "/x/dynamic/feed/draw/upload_bfs"
    val createPath = "/x/dynamic/feed/create/dyn"
    val editPath = "/x/dynamic/feed/edit/dyn"
    val imageKey = "7cd084941338484aae1ad9425b84077c"
    val subKey = "4932caff0ff746eab6f01bf08b70ac45"
    val fixtureClient = repository.httpClient.newBuilder().addInterceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
        records += request to body
        val waiting = if (path == uploadPath) hold.getAndSet(null) else null
        try {
            waiting?.let { (entered, released, _) ->
                entered.countDown()
                check(released.await(4, TimeUnit.SECONDS)) { "Fixture upload was not released" }
            }
            val response = when (path) {
                uploadPath -> if (failNextUpload.getAndSet(false))
                    """{"code":-400,"message":"fixture upload failed"}"""
                else """{"code":0,"data":{"image_url":"https://fixture.invalid/uploaded.png","image_width":13,"image_height":17,"img_size":1.25}}"""
                createPath -> createBody.get()
                editPath -> """{"code":0,"message":""}"""
                "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"mid":42,"uname":"fixture","wbi_img":{"img_url":"https://fixture.invalid/$imageKey.png","sub_url":"https://fixture.invalid/$subKey.png"}}}"""
                "/x/polymer/web-dynamic/v1/mention/search" -> """{"code":0,"data":{"groups":[{"items":[{"uid":77,"name":"Fixture"},{"uid":0,"name":"invalid"}]},{"items":[{"uid":77,"name":"duplicate"},{"uid":88,"name":"Other"},{"uid":99,"name":" "}]}]}}"""
                "/x/topic/pub/search" -> """{"code":0,"data":{"topic_items":[{"id":66,"name":"Topic"},{"id":66,"name":"duplicate"},{"id":0,"name":"invalid"},{"id":67,"name":" "}]}}"""
                "/x/vote/create" -> """{"code":0,"data":{"vote_id":88}}"""
                "/x/new-reserve/up/reserve/create" -> """{"code":0,"data":{"sid":99}}"""
                else -> throw IOException("Unexpected fixture request: $path; transport is deliberately closed")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(response.toResponseBody("application/json".toMediaType())).build()
        } finally {
            waiting?.third?.countDown()
        }
    }.build()
    val fixtureJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    val fixtureApi = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(fixtureClient)
        .addConverterFactory(fixtureJson.asConverterFactory("application/json".toMediaType()))
        .build().create(BilibiliApi::class.java)
    fun setField(name: String, value: Any) = repository.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(repository, value)
    fun warmVisitor() {
        setField("visitorInitialized", true)
        setField("visitorGeneration", sessions.generation)
    }
    // Repository.api was constructed before the client replacement: replace it too for WBI nav.
    setField("client", fixtureClient)
    setField("api", fixtureApi)
    warmVisitor()
    var alive = true
    val operations = DesktopDynamicCardOperations(repository, stillOwned = { alive })
    fun snapshot(): List<Pair<Request, String>> = synchronized(records) { records.toList() }
    fun after(index: Int) = snapshot().drop(index)
    fun onlySince(index: Int, path: String) = after(index).single { it.first.url.encodedPath == path }
    fun jsonBody(row: Pair<Request, String>) = Json.parseToJsonElement(row.second).jsonObject
    fun obj(value: JsonObject, key: String) = value.getValue(key).jsonObject
    fun text(value: JsonObject, key: String) = value.getValue(key).jsonPrimitive.content
    fun integer(value: JsonObject, key: String) = value.getValue(key).jsonPrimitive.int
    fun stringQuery(row: Pair<Request, String>, key: String) = row.first.url.queryParameter(key)
    fun assertCreateDefaults(row: Pair<Request, String>) {
        assertEquals("POST", row.first.method)
        assertEquals("fixture-csrf", stringQuery(row, "csrf"))
        assertEquals("web", stringQuery(row, "platform"))
        assertEquals("{\"platform\":\"web\",\"device\":\"pc\"}", stringQuery(row, "x-bili-device-req-json"))
        assertEquals("{\"spm_id\":\"333.999\"}", stringQuery(row, "x-bili-web-req-json"))
    }
    fun assertUpload(row: Pair<Request, String>, filename: String, mime: String) {
        assertEquals("POST", row.first.method)
        assertEquals("multipart", row.first.body?.contentType()?.type)
        assertTrue("name=\"file_up\"; filename=\"$filename\"" in row.second)
        assertTrue("Content-Type: $mime" in row.second)
        for ((part, value) in listOf("category" to "daily", "biz" to "new_dyn", "csrf" to "fixture-csrf"))
            assertTrue(Regex("name=\\\"$part\\\"[\\s\\S]*?\\r\\n\\r\\n$value\\r\\n").containsMatchIn(row.second), "Missing multipart $part=$value")
        assertTrue("fixture-image-bytes" in row.second)
    }
    val providerCalls = mutableListOf<String>()
    val imageProvider: suspend (String) -> Triple<String?, String?, ByteArray> = { source ->
        check(!source.startsWith("http:") && !source.startsWith("https:")) { "Remote images must be reused" }
        providerCalls += source
        Triple("fixture.png", "image/png", "fixture-image-bytes".toByteArray())
    }
    val cases = mutableListOf<String>()
    try {
        var start = snapshot().size
        val voteOnly = DynamicPublishDraft(text = " ", voteId = 88, voteTitle = "Vote")
        assertEquals("9001", operations.publishDynamic(voteOnly, imageProvider).getOrThrow())
        val voteOnlyRow = onlySince(start, createPath)
        assertCreateDefaults(voteOnlyRow)
        val voteOnlyReq = obj(jsonBody(voteOnlyRow), "dyn_req")
        assertEquals(1, integer(voteOnlyReq, "scene"))
        assertNull(voteOnlyReq["pics"]?.takeUnless { it is JsonNull })
        assertNull(voteOnlyReq["option"]?.takeUnless { it is JsonNull })
        val voteNodes = obj(voteOnlyReq, "content").getValue("contents").jsonArray
        assertEquals(listOf(4, 1), voteNodes.map { integer(it.jsonObject, "type") })
        assertEquals("88", text(voteNodes.first().jsonObject, "biz_id"))
        assertEquals(listOf(createPath), after(start).map { it.first.url.encodedPath })
        assertTrue(providerCalls.isEmpty())
        cases += "vote-only/public-create"

        start = snapshot().size
        val rich = DynamicPublishDraft(text = " hello @Fixture [fixture] world ", title = " Title ",
            imageUris = listOf("picked:rich"), voteId = 88, voteTitle = "Vote", reserveId = 99,
            private = true, mentions = listOf(DynamicPublishMention(77, "Fixture")),
            emotes = listOf("[fixture]"), topic = DynamicPublishTopic(66, "Topic"))
        assertEquals("9001", operations.publishDynamic(rich, imageProvider).getOrThrow())
        assertEquals(listOf(uploadPath, createPath), after(start).map { it.first.url.encodedPath })
        assertUpload(onlySince(start, uploadPath), "fixture.png", "image/png")
        val richRow = onlySince(start, createPath)
        assertCreateDefaults(richRow)
        val richReq = obj(jsonBody(richRow), "dyn_req")
        assertEquals(2, integer(richReq, "scene"))
        assertEquals("Title", text(obj(richReq, "content"), "title"))
        val nodes = obj(richReq, "content").getValue("contents").jsonArray.map { it.jsonObject }
        assertEquals(listOf(1, 2, 9, 1, 4, 1), nodes.map { integer(it, "type") })
        assertEquals("@Fixture ", text(nodes[1], "raw_text")); assertEquals("77", text(nodes[1], "biz_id"))
        assertEquals("[fixture]", text(nodes[2], "raw_text"))
        assertEquals("88", text(nodes[4], "biz_id"))
        val pic = richReq.getValue("pics").jsonArray.single().jsonObject
        assertEquals("https://fixture.invalid/uploaded.png", text(pic, "img_src"))
        assertEquals(13, integer(pic, "img_width")); assertEquals(17, integer(pic, "img_height"))
        assertEquals(1.25f, pic.getValue("img_size").jsonPrimitive.float)
        assertEquals(1, integer(obj(richReq, "option"), "private_pub"))
        val attach = obj(obj(richReq, "attach_card"), "common_card")
        assertEquals(14, integer(attach, "type")); assertEquals(99L, attach.getValue("biz_id").jsonPrimitive.long)
        assertEquals(0, integer(attach, "reserve_source")); assertEquals(0, integer(attach, "reserve_lottery"))
        assertEquals(66L, obj(richReq, "topic").getValue("id").jsonPrimitive.long)
        assertTrue(Regex("42_\\d+_\\d{4}").matches(text(richReq, "upload_id")))
        cases += "multipart-full-original-content-and-metadata"

        start = snapshot().size
        providerCalls.clear()
        val reused = DynamicCreatePic("https://fixture.invalid/existing.png", 20, 30, 2.5f)
        val removed = DynamicCreatePic("https://fixture.invalid/removed.png", 31, 41, 3.5f)
        operations.editDynamic("9001", rich.copy(imageUris = listOf(reused.img_src, "picked:new"),
            existingImages = listOf(reused, removed), private = false), imageProvider).getOrThrow()
        assertEquals(listOf("picked:new"), providerCalls)
        assertEquals(listOf(uploadPath, "/x/web-interface/nav", editPath), after(start).map { it.first.url.encodedPath })
        val editRow = onlySince(start, editPath)
        val editBody = jsonBody(editRow); val editReq = obj(editBody, "dyn_req")
        assertEquals("9001", text(editBody, "dyn_id_str"))
        val editPics = editReq.getValue("pics").jsonArray.map { it.jsonObject }
        assertEquals(listOf(reused.img_src, "https://fixture.invalid/uploaded.png"), editPics.map { text(it, "img_src") })
        assertEquals(20, integer(editPics.first(), "img_width")); assertEquals(30, integer(editPics.first(), "img_height"))
        assertEquals(2.5f, editPics.first().getValue("img_size").jsonPrimitive.float)
        assertNull(editReq["option"]?.takeUnless { it is JsonNull })
        assertEquals(text(editReq, "upload_id"), stringQuery(editRow, "w_dyn_req.upload_id"))
        assertEquals("web", stringQuery(editRow, "platform")); assertEquals("fixture-csrf", stringQuery(editRow, "csrf"))
        assertEquals("{\"platform\":\"web\",\"device\":\"pc\",\"spmid\":\"333.1368\"}", stringQuery(editRow, "x-bili-device-req-json"))
        assertEquals("{\"app_meta\":{\"from\":\"create.dynamic.web\",\"mobi_app\":\"web\"}}", stringQuery(editRow, "w_dyn_req.meta"))
        assertTrue(Regex("\\d+").matches(requireNotNull(stringQuery(editRow, "wts"))))
        assertTrue(Regex("[0-9a-f]{32}").matches(requireNotNull(stringQuery(editRow, "w_rid"))))
        assertFalse(editRow.first.url.queryParameterNames.any { it.startsWith("dm_") })
        cases += "edit-remote-reuse-removal-order-and-real-WBI"

        start = snapshot().size
        assertEquals(listOf(77L, 88L), operations.searchMentionUsers("  fixture  ").getOrThrow().map { it.uid })
        assertEquals(listOf(66L), operations.searchPublishTopics("  topic  ").getOrThrow().map { it.id })
        assertEquals("fixture", stringQuery(onlySince(start, "/x/polymer/web-dynamic/v1/mention/search"), "keyword"))
        val topicRow = onlySince(start, "/x/topic/pub/search")
        assertEquals("topic", stringQuery(topicRow, "keywords")); assertEquals("20", stringQuery(topicRow, "page_size"))
        assertEquals("1", stringQuery(topicRow, "page_num"))
        start = snapshot().size
        operations.searchMentionUsers(" ").getOrThrow(); operations.searchPublishTopics(" ").getOrThrow()
        assertNull(stringQuery(onlySince(start, "/x/polymer/web-dynamic/v1/mention/search"), "keyword"))
        assertNull(stringQuery(onlySince(start, "/x/topic/pub/search"), "keywords"))
        cases += "mention-topic-filter-dedupe-defaults"

        start = snapshot().size
        assertEquals(88L, operations.createVote(" Vote ", listOf(" A ", "", " B "), " desc ", 9, 3).getOrThrow().voteId)
        val voteInfo = obj(jsonBody(onlySince(start, "/x/vote/create")), "vote_info")
        assertEquals("Vote", text(voteInfo, "title")); assertEquals("desc", text(voteInfo, "desc"))
        assertEquals(2, integer(voteInfo, "choice_cnt")); assertEquals(259200, integer(voteInfo, "duration"))
        assertEquals(42L, voteInfo.getValue("vote_publisher").jsonPrimitive.long)
        assertEquals(listOf("A", "B"), voteInfo.getValue("options").jsonArray.map { text(it.jsonObject, "opt_desc") })
        assertEquals(99L, operations.createReserve(" Reserve ", 2000000000L, 1).getOrThrow().reserveId)
        val reserveForm = onlySince(start, "/x/new-reserve/up/reserve/create").second.split('&').associate { field ->
            field.substringBefore('=') to URLDecoder.decode(field.substringAfter('='), "UTF-8")
        }
        assertEquals("2", reserveForm["type"]); assertEquals("1", reserveForm["from"])
        assertEquals("1", reserveForm["sub_type"]); assertEquals("Reserve", reserveForm["title"])
        assertEquals("2000000000", reserveForm["live_plan_start_time"]); assertEquals("fixture-csrf", reserveForm["csrf"])
        cases += "vote-days-once-and-reserve-seconds"

        start = snapshot().size
        assertTrue(operations.publishDynamic(DynamicPublishDraft("", title = "Title"), imageProvider).isFailure)
        assertTrue(operations.publishDynamic(DynamicPublishDraft("", reserveId = 99), imageProvider).isFailure)
        assertTrue(operations.publishDynamic(DynamicPublishDraft("x", imageUris = listOf("empty"))) {
            Triple(null, null, byteArrayOf())
        }.isFailure)
        assertTrue(operations.publishDynamic(DynamicPublishDraft("x", imageUris = listOf("oversize"))) {
            Triple(null, null, ByteArray(15 * 1024 * 1024 + 1))
        }.isFailure)
        assertEquals(start, snapshot().size)
        val failedSources = mutableListOf<String>()
        assertTrue(operations.publishDynamic(DynamicPublishDraft("x", imageUris = listOf("first", "second"))) {
            failedSources += it
            if (it == "second") failNextUpload.set(true)
            Triple("fixture.png", "image/png", "fixture-image-bytes".toByteArray())
        }.isFailure)
        assertEquals(listOf("first", "second"), failedSources)
        assertEquals(listOf(uploadPath, uploadPath), after(start).map { it.first.url.encodedPath })
        cases += "original-empty-size-and-upload-failure-boundaries"

        for ((response, expected) in listOf(
            """{"code":0,"data":{"dynamic_id_str":" 9002 ","dyn_id":9003}}""" to "9002",
            """{"code":0,"data":{"dyn_id":9003}}""" to "9003",
            """{"code":0}""" to "ok",
        )) {
            createBody.set(response)
            assertEquals(expected, operations.publishDynamic(DynamicPublishDraft("ID fallback"), imageProvider).getOrThrow())
        }
        cases += "original-created-ID-fallback"

        start = snapshot().size
        var waiting = Triple(CountDownLatch(1), CountDownLatch(1), CountDownLatch(1))
        activeHold.set(waiting)
        hold.set(waiting)
        val cancellationProviders = AtomicInteger(0); val cancellationCallbacks = AtomicInteger(0)
        val cancelled = async(Dispatchers.Default) {
            operations.publishDynamic(DynamicPublishDraft("cancel", imageUris = listOf("first", "second"))) {
                cancellationProviders.incrementAndGet(); Triple("fixture.png", "image/png", "fixture-image-bytes".toByteArray())
            }.getOrThrow().also { cancellationCallbacks.incrementAndGet() }
        }
        assertTrue(withContext(Dispatchers.IO) { waiting.first.await(4, TimeUnit.SECONDS) })
        cancelled.cancel(); waiting.second.countDown()
        assertFailsWith<CancellationException> { cancelled.await() }
        assertTrue(withContext(Dispatchers.IO) { waiting.third.await(4, TimeUnit.SECONDS) })
        assertEquals(1, cancellationProviders.get()); assertEquals(0, cancellationCallbacks.get())
        assertEquals(listOf(uploadPath), after(start).map { it.first.url.encodedPath })
        cases += "cancelled-upload-no-second-image-or-final-request-or-callback"

        start = snapshot().size
        waiting = Triple(CountDownLatch(1), CountDownLatch(1), CountDownLatch(1))
        activeHold.set(waiting)
        hold.set(waiting)
        val epochProviders = AtomicInteger(0); val epochCallbacks = AtomicInteger(0)
        val oldEpoch = repository.sessionEpoch
        val retired = async(Dispatchers.Default) {
            operations.publishDynamic(DynamicPublishDraft("epoch", imageUris = listOf("first", "second"))) {
                epochProviders.incrementAndGet(); Triple("fixture.png", "image/png", "fixture-image-bytes".toByteArray())
            }.getOrThrow().also { epochCallbacks.incrementAndGet() }
        }
        assertTrue(withContext(Dispatchers.IO) { waiting.first.await(4, TimeUnit.SECONDS) })
        credentials("two")
        assertEquals(42L, repository.account.value?.mid); assertTrue(repository.sessionEpoch > oldEpoch)
        waiting.second.countDown()
        assertFailsWith<CancellationException> { retired.await() }
        assertTrue(withContext(Dispatchers.IO) { waiting.third.await(4, TimeUnit.SECONDS) })
        assertEquals(1, epochProviders.get()); assertEquals(0, epochCallbacks.get())
        assertEquals(listOf(uploadPath), after(start).map { it.first.url.encodedPath })
        val beforeOldCall = snapshot().size
        assertFailsWith<CancellationException> { operations.publishDynamic(voteOnly, imageProvider) }
        assertEquals(beforeOldCall, snapshot().size)
        warmVisitor()
        val fresh = DesktopDynamicCardOperations(repository, stillOwned = { alive })
        alive = false
        assertFailsWith<CancellationException> { fresh.editDynamic("9001", voteOnly, imageProvider) }
        assertEquals(beforeOldCall, snapshot().size)
        assertFalse(Files.exists(root.resolve("fake-session.json")))
        cases += "same-MID-epoch-and-closed-owner-no-late-transport-or-callback"

        if (args.isNotEmpty()) {
            val output = Path.of(args[0]); Files.createDirectories(output)
            Files.writeString(output.resolve("editor-protocol-result.json"), buildJsonObject {
                put("passed", true); put("actualSockets", false); put("persistentCredentials", false)
                put("actualOriginalApiAndModels", true); put("sourceOnlyUntilRootRuns", false)
                put("cases", JsonArray(cases.map(::JsonPrimitive)))
            }.toString())
        }
        println("PASS: ${cases.size} editor protocol cases; original API/models and augmented actual Operations; terminal fake responses, no sockets")
    } finally {
        activeHold.getAndSet(null)?.second?.countDown()
        hold.getAndSet(null)?.second?.countDown()
        fixtureClient.dispatcher.executorService.shutdown()
        fixtureClient.connectionPool.evictAll()
    }
}
