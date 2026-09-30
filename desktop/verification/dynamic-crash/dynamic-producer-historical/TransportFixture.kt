package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Actual product repository / Retrofit interface; the application interceptor prevents every socket. */
fun main(args:Array<String>):Unit=runBlocking {
    val root=Files.createTempDirectory("dynamic-api-bridge-")
    val sessions=DesktopSessionStore(root.resolve("fixture-session.json"))
    sessions.saveAccount(mapOf("SESSDATA" to "task-only-not-a-real-credential"),AccountSummary(42,"fixture",""))
    val repository=DesktopRepository(sessions)
    val requests=mutableListOf<JsonObject>()
    val replies=ArrayDeque(listOf(
        DynamicFeedData(listOf(fixtureDynamic("3",30),fixtureDynamic("2",20),fixtureDynamic("1",10)),"old-tail",true,"3"),
        DynamicFeedData(listOf(fixtureDynamic("6",60),fixtureDynamic("5",50)),"increment-page-2",true,"6",3),
        DynamicFeedData(listOf(fixtureDynamic("4",40),fixtureDynamic("3",30)),"increment-end",true,"6",0),
        DynamicFeedData(listOf(fixtureDynamic("0",0)),"",false,"6",0),
        DynamicFeedData(listOf(fixtureDynamic("9",90)),"",false,"9",0),
    ))
    val actualClient=repository.httpClient
    assertSame(sessions,actualClient.cookieJar)
    val fakeNetwork=actualClient.newBuilder().addInterceptor {chain->
        val request=chain.request()
        check(request.url.host=="api.bilibili.com"&&request.url.encodedPath=="/x/polymer/web-dynamic/v1/feed/all") {
            "Unexpected transport; the fixture never calls chain.proceed or opens a socket"
        }
        requests+=buildJsonObject {
            put("type",request.url.queryParameter("type").orEmpty())
            put("offset",request.url.queryParameter("offset").orEmpty())
            put("update_baseline",request.url.queryParameter("update_baseline").orEmpty())
            put("features",request.url.queryParameter("features").orEmpty())
        }
        val body=Json.encodeToString(DynamicFeedResponse(data=replies.removeFirst()))
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    // Only task-owned private fields are bound. Production repository/interface bytes stay pinned.
    for((name,value) in listOf("client" to fakeNetwork,"visitorInitialized" to true,"visitorGeneration" to sessions.generation)) {
        repository.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(repository,value)
    }
    val store=DesktopPluginStore(root.resolve("global-store"))
    val community=DesktopCommunityRepository(repository,DesktopBlockedUpStore(DesktopPluginContext(store)))
    val state=DesktopDynamicTimelineState("video",{type,offset,baseline->
        DynamicFeedResponse(data=community.dynamicFeed(type,offset,baseline).data)
    },stillOwned={community.accountEpoch.value==sessions.generation&&community.account.value?.mid==42L})
    assertTrue(state.fetch(true,false));assertTrue(state.fetch(true,true))
    assertEquals(listOf("6","5","4","3","2","1"),state.page.items.map{it.id_str})
    assertEquals("3",state.page.incrementalRefreshBoundaryKey)
    assertTrue(state.fetch(false,true))
    assertEquals(listOf("6","5","4","3","2","1","0"),state.page.items.map{it.id_str})
    assertTrue(state.fetch(true,false));assertEquals(listOf("9"),state.page.items.map{it.id_str})
    assertEquals(listOf("","","increment-page-2","old-tail",""),requests.map{it["offset"]!!.jsonPrimitive.content})
    assertEquals(listOf("","3","","",""),requests.map{it["update_baseline"]!!.jsonPrimitive.content})
    assertTrue(requests.all{it["type"]!!.jsonPrimitive.content=="video"})
    assertTrue(requests.all{it["features"]!!.jsonPrimitive.content.isNotBlank()})
    assertTrue(replies.isEmpty())
    for(type in listOf(DesktopRepository::class.java,DesktopCommunityRepository::class.java,
        com.android.purebilibili.core.network.DynamicApi::class.java)) {
        assertEquals("main-kotlin.jar",Path.of(type.protectionDomain.codeSource.location.toURI()).fileName.toString())
    }
    Files.writeString(Path.of(args[0]),buildJsonObject {
        put("passed",true);put("actualProductRepositoryAndApi",true);put("actualApplicationCookieJarIdentity",true)
        put("requests",JsonArray(requests));put("actualSocket",false);put("realCredential",false)
        put("multiPageNewCountFetch",true);put("oldTailAppend",true);put("disabledFullReplacement",true)
    }.toString())
    fakeNetwork.dispatcher.executorService.shutdown();fakeNetwork.connectionPool.evictAll()
    println("PASS: actual product CommunityRepository / original DynamicApi type, cursor, first-page baseline, retained old-tail and replacement contract; application interceptor only")
}
