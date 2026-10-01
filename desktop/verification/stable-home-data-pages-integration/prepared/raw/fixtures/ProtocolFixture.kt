package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.DesktopFeedSettings
import com.android.purebilibili.core.refresh.WatchLaterRefreshBus
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import java.nio.file.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

private data class Invocation(val method:String,val args:List<Any?>)
private class ScriptedApi {
    val calls=CopyOnWriteArrayList<Invocation>()
    var answer:(Invocation)->Any?={error("Unexpected ${it.method}")}
    val api=Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,arrayOf(BilibiliApi::class.java)){_,method,args ->
        if(method.name=="toString")"synthetic original API" else {
            val call=Invocation(method.name,args.orEmpty().toList().dropLast(1));calls+=call;answer(call)
        }
    } as BilibiliApi
}
private fun web(vararg bvid:String)=RecommendResponse(data=RecommendData(bvid.mapIndexed{i,b->RecommendItem(id=i+1L,bvid=b,cid=i+20L,title=b)}))
private fun mobile(vararg bvid:String)=MobileFeedResponse(data=MobileFeedData(bvid.mapIndexed{i,b->MobileFeedItem(idx=i+1L,param=b,goto="av",uri="bilibili://video/$b",title=b)}))
private fun popular(bvid:String)=PopularItem(aid=11,bvid=bvid,cid=22,title=bvid)
private val absentMessage=Proxy.newProxyInstance(MessageApi::class.java.classLoader,arrayOf(MessageApi::class.java)){_,method,_->error("Unexpected message ${method.name}")} as MessageApi

fun main(args:Array<String>)=runBlocking {
    val dir=Path.of(args[0]);Files.createDirectories(dir)
    val auth=ScriptedApi();val guest=ScriptedApi()
    var mode=DesktopFeedSettings.FeedApiType.WEB;var token:String?=null
    val owner=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val environment=DesktopHomeProtocolEnvironment(auth.api,guest.api,absentMessage,owner,{true},{block->block();true},
        {mode},{20},{Result.success("a".repeat(32) to "b".repeat(32))},{token},{"fixture-csrf"},{"fixture-buvid"},{},{})
    val ports=DesktopHomeRequestPorts(environment);val gates=mutableListOf<String>()
    try {
        auth.answer={call->check(call.method=="getRecommendParams");web("BVweb")}
        check(ports.video.getHomeVideos(7).getOrThrow().single().bvid=="BVweb")
        @Suppress("UNCHECKED_CAST") val webParams=auth.calls.single().args.single() as Map<String,String>
        check(webParams["ps"]=="20" && webParams["fresh_type"]=="3" && webParams["fresh_idx"]=="7" && webParams["y_num"]=="7")
        check(webParams["w_rid"]?.length==32 && !webParams["wts"].isNullOrBlank())
        auth.calls.clear();mode=DesktopFeedSettings.FeedApiType.MOBILE
        check(ports.video.getHomeVideos(1).getOrThrow().single().bvid=="BVweb")
        check(auth.calls.map{it.method}==listOf("getRecommendParams"))
        gates+="original WEB signed idx/ps and MOBILE absent-token fallback without App request"

        mode=DesktopFeedSettings.FeedApiType.MERGED;token="fixture-access-token";auth.calls.clear()
        val both=CountDownLatch(2)
        auth.answer={call->
            both.countDown();check(both.await(3,TimeUnit.SECONDS)){"two original requests must run concurrently"}
            when(call.method){"getRecommendParams"->web("BVweb","BVshared");"getMobileFeedEncoded"->mobile("BVapp","BVshared");else->error(call.method)}
        }
        val merged=ports.video.getHomeVideos(2).getOrThrow()
        check(merged.map{it.bvid}==listOf("BVapp","BVweb","BVshared"))
        check(auth.calls.size==2)
        @Suppress("UNCHECKED_CAST") val mergedWeb=auth.calls.single{it.method=="getRecommendParams"}.args.single() as Map<String,String>
        @Suppress("UNCHECKED_CAST") val appParams=auth.calls.single{it.method=="getMobileFeedEncoded"}.args.single() as Map<String,String>
        check(mergedWeb["brush"]=="2" && mergedWeb["fresh_type"]=="4" && mergedWeb["feed_version"]=="V8")
        check(appParams["mobi_app"]=="android_hd" && appParams["pull"]=="false" && appParams["access_key"]=="fixture-access-token")
        check(appParams["statistics"]?.contains("%7B")==true && !appParams["sign"].isNullOrBlank())
        gates+="MERGED exactly two concurrent original Web/App requests; encoded App signature, interleave and dedup"

        auth.calls.clear();auth.answer={call->when(call.method){
            "getRegionVideos"->if(call.args[0]==13)DynamicRegionResponse(data=DynamicRegionData(archives=listOf(DynamicRegionItem(aid=11,bvid="BVregion13",cid=22)))) else DynamicRegionResponse(code=-400)
            "getLegacyRegionVideos"->RegionVideosResponse(code=-400)
            "getRankingVideos"->RankingResponse(data=RankingData(list=listOf(popular("BVranking"))))
            else->error(call.method)
        }}
        check(ports.video.getRegionVideos(202,1).getOrThrow().single().bvid=="BVranking")
        check(auth.calls.map{it.method}==listOf("getRegionVideos","getLegacyRegionVideos","getRankingVideos"))
        @Suppress("UNCHECKED_CAST") val ranking=auth.calls.last().args.single() as Map<String,String>
        check(ranking["rid"]=="1009")
        auth.calls.clear();check(ports.video.getRegionVideos(13,2).getOrThrow().single().bvid=="BVregion13")
        check(auth.calls.single().args==listOf(13,2,30))
        gates+="region202 original legacy then ranking fallback; region13 direct latest page is accepted"

        auth.calls.clear();auth.answer={call->when(call.method){
            "getWeeklySeriesList"->PopularSeriesListResponse(data=PopularSeriesListData(listOf(PopularSeriesPeriod(3),PopularSeriesPeriod(9),PopularSeriesPeriod(5))))
            "getWeeklySeriesVideos"->PopularSeriesOneResponse(data=PopularSeriesOneData(list=listOf(popular("BVweekly"))))
            "getNavInfo"->NavResponse(code=-101)
            else->error(call.method)
        }}
        check(ports.video.getWeeklyMustWatchVideos().getOrThrow().single().bvid=="BVweekly")
        check(auth.calls.last().args==listOf(9));check(!ports.video.getNavInfo().getOrThrow().isLogin)
        gates+="weekly original max period and AUTH nav -101 guest result retained for Root invalidation event"

        auth.calls.clear();guest.calls.clear();val qualities=buildGuestFallbackQualities()
        guest.answer={call->
            check(call.method=="getPlayUrlLegacy")
            check(call.args[0]=="BVpreview" && call.args[1]==99L && call.args[3]==1 && call.args[4]==0 && call.args[5]==1 && call.args[6]=="html5")
            val qn=call.args[2] as Int;check(call.args[7]==if(qn>=64)1 else 0)
            if(qn==qualities.last())PlayUrlResponse(data=PlayUrlData(quality=qn,durl=listOf(Durl(url="http://127.0.0.1/fixture-no-network.mp4")))) else PlayUrlResponse(code=-400)
        }
        check(ports.video.getPreviewVideoUrl("BVpreview",99)=="http://127.0.0.1/fixture-no-network.mp4")
        check(guest.calls.map{it.args[2]}==qualities && auth.calls.isEmpty())
        gates+="guest preview original quality sequence/MP4/html5/highQuality and no authenticated API use"

        val events=AtomicInteger();val subscription=launch(start=CoroutineStart.UNDISPATCHED){WatchLaterRefreshBus.changes.collect{events.incrementAndGet()}}
        var responseCode=0;auth.calls.clear();auth.answer={call->check(call.method in listOf("addToWatchLater","deleteFromWatchLater"));SimpleApiResponse(code=responseCode)}
        check(ports.actions.toggleWatchLater(55,true).getOrThrow());yield()
        check(auth.calls.single().args==listOf(55L,"fixture-csrf"));check(events.get()==1)
        responseCode=90001;check(ports.actions.toggleWatchLater(55,true).isFailure)
        responseCode=90003;check(ports.actions.toggleWatchLater(55,false).isFailure);yield();check(events.get()==1)
        subscription.cancelAndJoin()
        gates+="original WatchLater raw aid/CSRF; only code0 emits sole original refresh bus, 90001/90003 remain failure"
        Files.writeString(dir.resolve("protocol-result.json"),buildJsonObject {
            put("actualMainAcceptance",false);put("preparedOverrides",true);put("socketOrExternalHttp",false)
            put("gates",gates.size);put("names",JsonArray(gates.map(::JsonPrimitive)))
            put("codeSource",buildJsonObject {for(type in listOf(DesktopHomeRequestPorts::class.java,DesktopOriginalHomeVideoProtocol::class.java,WatchLaterRefreshBus::class.java,BilibiliApi::class.java))put(type.name,type.protectionDomain.codeSource?.location.toString())})
        }.toString())
        println("PASS ${gates.size} original protocol gates")
    } finally {ports.close();owner.cancel()}
}
