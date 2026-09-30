package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.DynamicApi
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalDynamicUserRepository
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real current Repository and original APIs; only the new consumer methods are explicitly overlaid. */
fun main(args:Array<String>):Unit=runBlocking {
 val root=Files.createTempDirectory("dynamic-tabs-api-");val sessions=DesktopSessionStore(root.resolve("task-session.json"))
 sessions.saveAccount(mapOf("SESSDATA" to "task-only-not-real","bili_jct" to "task-only-csrf"),AccountSummary(42,"fixture",""))
 val repository=DesktopRepository(sessions);val actual=repository.httpClient;assertSame(sessions,actual.cookieJar)
 val requests=java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
 var spacePage=0;var delayNext=false;val entered=CountDownLatch(1);val released=CountDownLatch(1)
 val client=actual.newBuilder().addInterceptor{chain->
  val request=chain.request();val path=request.url.encodedPath
  requests+=buildJsonObject{put("host",request.url.host);put("path",path)
   put("query",buildJsonObject{request.url.queryParameterNames.forEach{put(it,request.url.queryParameter(it).orEmpty())}})}
  val body=when(path){
   "/x/polymer/web-dynamic/v1/feed/all"->Json.encodeToString(tabsResponse(listOf(tabsDynamic(request.url.queryParameter("type").orEmpty()))))
   "/x/polymer/web-dynamic/v1/feed/space"->{
    if(delayNext){entered.countDown();check(released.await(3,TimeUnit.SECONDS))}
    spacePage++;Json.encodeToString(tabsResponse(listOf(tabsDynamic("space-$spacePage",77)),if(spacePage==1)"uid-tail"else "",spacePage==1))
   }
   "/x/relation/followings"->"""{"code":0,"data":{"list":[{"mid":77,"uname":"User-77","face":""}],"total":1}}"""
   "/xlive/web-ucenter/user/following"->"""{"code":0,"data":{"list":[{"uid":77,"uname":"User-77","face":"","roomid":777,"live_status":1},{"uid":78,"uname":"offline","face":"","roomid":778,"live_status":0}]}}"""
   "/dynamic_svr/v1/dynamic_svr/w_dyn_uplist"->"""{"code":0,"data":{"items":[{"has_update":1,"user_profile":{"info":{"uid":77}}},{"has_update":0,"user_profile":{"info":{"uid":78}}}]}}"""
   else->error("Unexpected fixture path; no network proceed is permitted: $path")
  }
  Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
   .body(body.toResponseBody("application/json".toMediaType())).build()
 }.build()
 for((name,value)in listOf("client" to client,"visitorInitialized" to true,"visitorGeneration" to sessions.generation))
  repository.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(repository,value)
 val store=DesktopPluginStore(root.resolve("global-store"));val community=DesktopCommunityRepository(repository,DesktopBlockedUpStore(DesktopPluginContext(store)))
 try {
 for(type in listOf("all","video","pgc","article")){assertEquals(type,community.dynamicFeed(type).data.items.single().id_str)}
 val following=community.followings(42);assertEquals(77L,following.data.list!!.single().mid)
 val live=community.dynamicFollowedLiveUsers();assertEquals(listOf(77L),live.map{it.uid});assertEquals(777L,live.single().roomid)
 val unread=community.dynamicUnreadUsers();assertEquals(77L,unread!!.items.first().user_profile!!.info!!.uid)
 val user=DesktopOriginalDynamicUserRepository(community::dynamicSelectedUserPage)
 assertEquals("space-1",user.getUserDynamicFeed(77,true).getOrThrow().single().id_str)
 assertEquals("space-2",user.getUserDynamicFeed(77,false).getOrThrow().single().id_str)
 val space=requests.filter{it["path"]!!.jsonPrimitive.content.endsWith("/feed/space")}.map{it["query"]!!.jsonObject}
 assertEquals(listOf("","uid-tail"),space.map{it["offset"]!!.jsonPrimitive.content})
 assertTrue(space.all{it["host_mid"]!!.jsonPrimitive.content=="77"&&it["features"]!!.jsonPrimitive.content==com.android.purebilibili.core.network.SPACE_DYNAMIC_FEATURES})
 assertTrue(space.all{it["web_location"]!!.jsonPrimitive.content=="333.1387"&&it["timezone_offset"]!!.jsonPrimitive.content=="-480"&&it["platform"]!!.jsonPrimitive.content=="web"})
 val followQuery=requests.single{it["path"]!!.jsonPrimitive.content=="/x/relation/followings"}["query"]!!.jsonObject
 assertEquals("42",followQuery["vmid"]!!.jsonPrimitive.content);assertEquals("50",followQuery["ps"]!!.jsonPrimitive.content)
 val liveQuery=requests.single{it["path"]!!.jsonPrimitive.content=="/xlive/web-ucenter/user/following"}["query"]!!.jsonObject
 assertEquals("1",liveQuery["page"]!!.jsonPrimitive.content);assertEquals("50",liveQuery["page_size"]!!.jsonPrimitive.content)
 delayNext=true
 val delayed=async(Dispatchers.Default){runCatching{community.dynamicSelectedUserPage(mapOf("host_mid" to "77","offset" to ""))}}
 check(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
 sessions.saveAccount(mapOf("SESSDATA" to "task-new-not-real","bili_jct" to "task-new-csrf"),AccountSummary(42,"fixture",""));released.countDown()
 val failure=delayed.await().exceptionOrNull();assertNotNull(failure,"Same MID credential replacement must reject old response")
 assertTrue(failure is CancellationException||failure is BiliApiException,"Typed ownership/auth rejection, no successful old result")
 for(type in listOf(DesktopRepository::class.java,DynamicApi::class.java,BilibiliApi::class.java))
  assertEquals("main-kotlin.jar",Path.of(type.protectionDomain.codeSource.location.toURI()).fileName.toString())
 assertTrue(Path.of(DesktopCommunityRepository::class.java.protectionDomain.codeSource.location.toURI()).fileName.toString().startsWith("classes-attempt"))
 Files.writeString(Path.of(args[0]).resolve("transport-result.json"),buildJsonObject{
  put("passed",true);put("currentProductRepositoryOriginalApis",true);put("preparedCommunityConsumerOverride",true)
  put("sameApplicationCookieJar",true);put("sameMIDLateEpochRejected",true);put("actualSocket",false);put("realCredential",false)
  put("requestTrace",JsonArray(requests));put("credentialsLogged",false)
 }.toString())
 println("PASS: actual original API queries/type, MID cursor, followings/live/unread, shared cookie jar and same-MID epoch rejection; zero sockets")
 }finally{released.countDown();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
}
