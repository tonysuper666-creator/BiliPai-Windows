package com.bilipai.desktop.data
import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.article.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

// Existing original API/models and complete owned prepared detail algorithm.
// Terminal application interceptor supplies all bytes; no chain.proceed/socket fallback.
fun main(args:Array<String>):Unit=runBlocking {
    val json=Json {ignoreUnknownKeys=true;encodeDefaults=true}
    val sessions=DesktopSessionStore(Files.createTempDirectory("detail-protocol-").resolve("fake.json"),persistent=false)
    fun credentials(label:String){sessions.saveAccount(mapOf("SESSDATA" to ("fixture-"+label),"bili_jct" to "fake-csrf"),AccountSummary(42,"fixture",""));sessions.saveSpiCookies(mapOf("buvid3" to "fixture-buvid"))}
    credentials("one")
    val repository=DesktopRepository(sessions)
    val rows=Collections.synchronizedList(mutableListOf<Request>())
    val scenario=AtomicReference("word")
    val held=AtomicReference<Triple<CountDownLatch,CountDownLatch,CountDownLatch>?>(null)
    val webPath="/x/polymer/web-dynamic/v1/detail"
    val opusPath="/x/polymer/web-dynamic/v1/opus/detail"
    val desktopPath="/x/polymer/web-dynamic/desktop/v1/detail"
    val articlePath="/x/article/view"
    fun word(text:String)=DynamicItem(id_str="123",type="DYNAMIC_TYPE_WORD",modules=DynamicModules(module_dynamic=DynamicContentModule(desc=DynamicDesc(text=text))))
    fun response(item:DynamicItem?,fallback:Long?=null)=json.encodeToString(DynamicDetailResponse.serializer(),DynamicDetailResponse(data=DynamicDetailData(item=item,fallback=fallback?.let {DynamicOpusFallback(id=it)})))
    fun opusResponse(text:String,title:String="Full Opus")=buildJsonObject {
        put("code",0);put("data",buildJsonObject {put("item",buildJsonObject {
            put("id_str","123");put("type","DYNAMIC_TYPE_DRAW")
            put("modules",buildJsonArray {
                add(buildJsonObject {put("module_dynamic",buildJsonObject {put("major",buildJsonObject {
                    put("type","MAJOR_TYPE_OPUS");put("opus",buildJsonObject {put("summary",buildJsonObject {put("text","preview")})})
                })})})
                add(buildJsonObject {put("module_type","MODULE_TYPE_TITLE");put("module_title",buildJsonObject {put("text",title)})})
                add(buildJsonObject {put("module_type","MODULE_TYPE_CONTENT");put("module_content",buildJsonObject {
                    put("paragraphs",buildJsonArray {add(buildJsonObject {put("para_type",1);put("text",buildJsonObject {
                        put("nodes",buildJsonArray {add(buildJsonObject {put("word",buildJsonObject {put("words",text)})})})
                    })})})
                })})
            })
        })})
    }.toString()
    val draw=DynamicItem(id_str="123",type="DYNAMIC_TYPE_DRAW",basic=DynamicBasic("900",11),
        modules=DynamicModules(module_dynamic=DynamicContentModule(major=DynamicMajor(type="MAJOR_TYPE_OPUS",opus=OpusMajor(summary=OpusSummary("preview"))))))
    val terminal=repository.httpClient.newBuilder().addInterceptor { chain ->
        val request=chain.request();rows+=request
        val path=request.url.encodedPath;val waiting=if(path==opusPath)held.getAndSet(null)else null
        try {
            waiting?.let {it.first.countDown();check(it.second.await(4,TimeUnit.SECONDS))}
            val body=when(path) {
                "/x/web-interface/nav" -> if(scenario.get()=="unsigned")"""{"code":0,"data":{}}""" else """{"code":0,"data":{"wbi_img":{"img_url":"https://fixture.invalid/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://fixture.invalid/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""
                webPath -> when(scenario.get()) {
                    "word" -> response(word("short"))
                    "rid" -> response(word(if(request.url.queryParameter("rid")!=null)"By rid" else "网页链接"))
                    "fail" -> """{"code":-400}"""
                    else -> response(draw)
                }
                desktopPath -> when(scenario.get()) {
                    "word" -> response(word("A longer full plain text"))
                    "rid" -> response(word("网页链接"))
                    else -> """{"code":-400}"""
                }
                opusPath -> when {
                    scenario.get()=="fail" || scenario.get()=="rid" -> """{"code":-400}"""
                    scenario.get().startsWith("article") && request.url.queryParameter("id")=="123" -> response(null,42)
                    scenario.get()=="articleFail" -> throw IOException("fixture secondary opus failure")
                    scenario.get().startsWith("article") -> opusResponse("Secondary article full text ".repeat(45))
                    else -> opusResponse("Complete original Opus text ".repeat(40))
                }
                articlePath -> json.encodeToString(ArticleDetailResponse.serializer(),ArticleDetailResponse(data=ArticleViewData(
                    id=42,title="",summary="Summary Title",dynamicId="article-opus",content="<h2>Article heading</h2><p>Article body.</p>")))
                else -> throw IOException("Unexpected path "+path+"; no socket fallback")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        } finally {waiting?.third?.countDown()}
    }.build()
    fun field(name:String,value:Any)=repository.javaClass.getDeclaredField(name).apply {isAccessible=true}.set(repository,value)
    field("client",terminal)
    field("api",Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(terminal).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java))
    fun warm(){field("visitorInitialized",true);field("visitorGeneration",sessions.generation)}
    warm()
    var alive=true
    fun operations()=DesktopDynamicCardOperations(repository,stillOwned={alive})
    var ops=operations()
    fun snapshot()=synchronized(rows){rows.toList()}
    fun after(start:Int)=snapshot().drop(start).filter {it.url.encodedPath!="/x/web-interface/nav"}
    fun paths(start:Int)=after(start).map {it.url.encodedPath}
    val cases=mutableListOf<String>()
    try {
        var start=snapshot().size
        val plain=ops.getDynamicDetail(" 123 ",null).getOrThrow()
        // Original WORD candidates all have Opus score 0; maxByOrNull keeps first web.
        assertEquals("short",plain.modules.module_dynamic!!.desc!!.text)
        assertEquals(listOf(webPath,desktopPath),paths(start))
        val web=after(start).first().url
        assertEquals("123",web.queryParameter("id"));assertEquals("-480",web.queryParameter("timezone_offset"))
        assertEquals("Athena",web.queryParameter("gaia_source"));assertEquals("333.1330",web.queryParameter("web_location"))
        assertEquals(DYNAMIC_DETAIL_FEATURES,web.queryParameter("features"))
        cases+="original-word-web-first-tie-desktop-attempt-and-default-query"
        scenario.set("opus");start=snapshot().size
        val seed=draw.copy(modules=draw.modules.copy(module_dynamic=draw.modules.module_dynamic!!.copy(
            major=DynamicMajor(type="MAJOR_TYPE_DRAW",draw=DrawMajor(items=listOf(DrawItem(src="https://fixture.invalid/seed.png",width=13,height=17))))),
            module_stat=DynamicStatModule(like=StatItem(count=9,status=true))))
        val full=ops.getDynamicDetail("123",seed).getOrThrow()
        assertEquals(11,full.basic!!.comment_type);assertEquals("900",full.basic!!.comment_id_str)
        assertTrue(full.modules.module_stat!!.like.status);assertEquals(9,full.modules.module_stat!!.like.count)
        assertTrue(full.modules.module_dynamic!!.major!!.opus!!.contentBlocks.any {it is OpusContentBlock.Text&&it.text.startsWith("Complete original")})
        assertEquals(listOf("https://fixture.invalid/seed.png"),full.modules.module_dynamic!!.major!!.opus!!.pics.map {it.url})
        assertEquals(listOf(webPath,opusPath),paths(start))
        val query=after(start).single {it.url.encodedPath==opusPath}.url
        assertEquals("htmlNewStyle",query.queryParameter("features"));assertEquals("-480",query.queryParameter("timezone_offset"))
        assertEquals(32,query.queryParameter("w_rid")!!.length);assertNotNull(query.queryParameter("wts"))
        cases+="original-opus-rich-raw-parser-seed-media-and-interaction"
        scenario.set("rid");start=snapshot().size
        val ridSeed=word("").copy(basic=DynamicBasic("999",17,"rid-900"))
        assertEquals("By rid",ops.getDynamicDetail("123",ridSeed).getOrThrow().modules.module_dynamic!!.desc!!.text)
        // Original WORD nonblank placeholder hits both plain-text and empty-content desktop branches.
        assertEquals(listOf(webPath,opusPath,desktopPath,desktopPath,webPath),paths(start))
        val rid=after(start).last().url;assertNull(rid.queryParameter("id"));assertEquals("rid-900",rid.queryParameter("rid"));assertEquals("2",rid.queryParameter("type"))
        scenario.set("fail");start=snapshot().size
        assertEquals("Cached seed",ops.getDynamicDetail("123",word("Cached seed")).getOrThrow().modules.module_dynamic!!.desc!!.text)
        assertEquals(listOf(webPath,opusPath,desktopPath),paths(start))
        assertEquals("动态详情为空",ops.getDynamicDetail("123",null).exceptionOrNull()!!.message)
        start=snapshot().size;assertTrue(ops.getDynamicDetail(" ",null).isFailure);assertTrue(after(start).isEmpty())
        cases+="original-rid-typed-web-cached-seed-and-empty-failure"
        scenario.set("articleFail");start=snapshot().size
        var history=0
        val article=ops.getDynamicDetail("123",seed) {id->assertEquals(42L,id);history++;throw IOException("best effort history")}.getOrThrow()
        val articleOpus=article.modules.module_dynamic!!.major!!.opus!!
        assertEquals("Summary Title",articleOpus.title)
        assertTrue(articleOpus.contentBlocks.any {it is OpusContentBlock.Heading&&it.text=="Article heading"})
        assertTrue(articleOpus.contentBlocks.any {it is OpusContentBlock.Text&&it.text=="Article body."})
        assertEquals(1,history)
        assertEquals(listOf(webPath,opusPath,articlePath,opusPath),paths(start))
        val articleQuery=after(start).single {it.url.encodedPath==articlePath}.url
        assertEquals("42",articleQuery.queryParameter("id"));assertEquals("main_web",articleQuery.queryParameter("gaia_source"));assertEquals("333.976",articleQuery.queryParameter("web_location"))
        assertEquals("article-opus",after(start).last().url.queryParameter("id"))
        scenario.set("articleRich")
        assertTrue(ops.getDynamicDetail("123",seed).getOrThrow().modules.module_dynamic!!.major!!.opus!!.contentBlocks.any {it is OpusContentBlock.Text&&it.text.startsWith("Secondary article full")})
        cases+="article-original-view-survives-secondary-opus-error-title-and-richer-merge"
        credentials("unsigned-new-epoch");warm();ops=operations();scenario.set("unsigned");start=snapshot().size
        assertTrue(ops.getDynamicDetail("123",seed).isSuccess)
        val unsigned=after(start).single {it.url.encodedPath==opusPath}.url
        assertNull(unsigned.queryParameter("w_rid"));assertNull(unsigned.queryParameter("wts"))
        cases+="original-unsigned-query-on-signing-failure"
        scenario.set("articleFail");start=snapshot().size
        val wait=Triple(CountDownLatch(1),CountDownLatch(1),CountDownLatch(1));held.set(wait)
        var late=0
        val old=async(Dispatchers.IO){ops.getDynamicDetail("123",seed){late++}.getOrThrow();late++}
        try {
            assertTrue(withContext(Dispatchers.IO){wait.first.await(4,TimeUnit.SECONDS)})
            credentials("same-mid-retired");warm();wait.second.countDown()
            try {old.await();fail("Retired detail completed")}catch(_:CancellationException){}
            assertTrue(withContext(Dispatchers.IO){wait.third.await(4,TimeUnit.SECONDS)})
            assertEquals(0,late);assertEquals(listOf(webPath,opusPath),paths(start))
        } finally {wait.second.countDown();old.cancelAndJoin()}
        cases+="same-mid-epoch-no-late-fallback-history-or-result"
        val result=buildJsonObject {put("passed",true);put("MainIntegration",false);put("sockets",false);put("actualOriginalApiAndModels",true);put("cases",buildJsonArray {cases.forEach {add(it)}})}
        if(args.isNotEmpty())Files.writeString(Path.of(args[0]),result.toString()+"\n")
        println("PASS: "+cases.size+" original detail fallback groups; no sockets")
    } finally {held.get()?.second?.countDown();terminal.dispatcher.executorService.shutdownNow();terminal.connectionPool.evictAll()}
}

