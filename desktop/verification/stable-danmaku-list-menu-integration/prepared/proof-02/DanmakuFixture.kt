package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.data.model.response.DanmakuThumbupStatsResponse
import com.android.purebilibili.feature.video.viewmodel.DesktopOriginalDanmakuSession
import com.android.purebilibili.feature.video.danmaku.*
import com.android.purebilibili.feature.video.ui.components.*
import com.bilipai.desktop.danmaku.*
import com.bilipai.desktop.danmaku.DanmakuParser
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private val FormBody.encodedNameList:List<String> get()=(0 until size).map { encodedName(it) }
private var assertions=0
private fun verify(value:Boolean,label:String){check(value){label};assertions++}
private fun identity(type:Class<*>)=buildJsonObject {
    put("class",type.name);put("path",Path.of(type.protectionDomain.codeSource.location.toURI()).toString())
    put("classSha256Bytes",MessageDigest.getInstance("SHA-256").digest(type.getResourceAsStream("/"+type.name.replace('.','/')+".class")!!.use{it.readBytes()}).joinToString(""){"%02x".format(it)})
}
private class Proto {
    val b=ByteArrayOutputStream()
    fun varint(value:Long){var n=value;while(n>127){b.write(((n and 127) or 128).toInt());n=n ushr 7};b.write(n.toInt())}
    fun v(field:Int,value:Long){varint((field*8).toLong());varint(value)}
    fun bytes(field:Int,value:ByteArray){varint((field*8+2).toLong());varint(value.size.toLong());b.write(value)}
    fun text(field:Int,value:String)=bytes(field,value.toByteArray(Charsets.UTF_8))
}
private class PendingActions:DesktopDanmakuActions {
    val stats=mutableListOf<Pair<Long,CompletableDeferred<Result<DanmakuThumbupState>>>>()
    val likes=mutableListOf<CompletableDeferred<Result<Unit>>>()
    var recalls=0;var reports=0
    override suspend fun getDanmakuThumbupState(cid:Long,dmid:Long):Result<DanmakuThumbupState>{val pending=CompletableDeferred<Result<DanmakuThumbupState>>();stats+=dmid to pending;return pending.await()}
    override suspend fun likeDanmaku(cid:Long,dmid:Long,like:Boolean):Result<Unit>{val pending=CompletableDeferred<Result<Unit>>();likes+=pending;return pending.await()}
    override suspend fun recallDanmaku(cid:Long,dmid:Long):Result<String>{recalls++;return Result.success("")}
    override suspend fun reportDanmaku(cid:Long,dmid:Long,reason:Int,content:String):Result<Unit>{reports++;return Result.success(Unit)}
}

fun main(args:Array<String>)=runBlocking {
    val rawContent="弹幕".repeat(180)+" 00:42"
    val proto=Proto().apply{v(1,901);v(2,12345);v(3,5);v(4,72);v(5,0xAABBCC);text(6,"123");text(7,rawContent);v(9,8);v(11,2);v(13,9);v(15,12);v(24,60001);v(28,3);v(29,1)}
    val segment=Proto().apply{bytes(1,proto.b.toByteArray())}.b.toByteArray()
    val parsed=DanmakuParser.parseProtobuf(listOf(segment))
    verify(parsed.comments.size==1,"actual original protobuf parser feeds existing desktop parser")
    verify(parsed.comments.single().text.length<rawContent.length,"existing display safety cap remains")
    val item=originalDanmakuPoolItems(DanmakuPoolSourceSnapshot(44,3,1,parsed.comments)).single()
    verify(item.danmakuId==901L&&item.showAtTime==12345L&&item.text=="$rawContent x3","original ID/time/full source text/count formatting")
    verify(item.pool==2&&item.attr==9&&item.likeCount==12L&&item.duplicateCount==3,"original pool/attr/like/count fields preserved")
    verify(item.weight==8&&item.isSelf&&item.isVipGradualColor,"original weight/self/vip fields preserved")
    verify(item.textSizeScale==2.56f&&item.textColor==0xFFAABBCC.toInt(),"original untruncated font-size policy and color alpha")
    val copied=parsed.comments.single().copy(id=4)
    verify(copied.originalElement===parsed.comments.single().originalElement,"source payload survives sole WindowLoader's data-class copy")
    val xmlText="甲".repeat(330)
    val xml=DanmakuParser.parseDocument("<i><d p=\"1.25,4,36,123456,0,2, 123 ,902\">$xmlText</d></i>")
    val xmlItem=originalDanmakuPoolItems(DanmakuPoolSourceSnapshot(44,3,2,xml.comments)).single()
    verify(xmlItem.text==xmlText&&xmlItem.pool==2&&xmlItem.userHash==" 123 "&&xmlItem.showAtTime==1250L,"original legacy source attributes/content factory")
    verify(!xmlItem.isSelf&&xmlItem.likeCount==0L,"legacy absent fields use original model defaults")
    verify(resolveDanmakuClickUserHash(xmlItem.userHash)=="123"&&resolveDanmakuClickIsSelf("123",123),"original manager current-MID numeric user rule")
    verify(!resolveDanmakuClickIsSelf("7b",123)&&!resolveDanmakuClickIsSelf("123",0),"no invented CRC/self/guest flag")
    verify(resolveDanmakuTimestampJumpMs("1:02:03")==3723000L&&resolveDanmakuTimestampJumpMs("1:99")==null,"original timestamp policy")
    verify(DanmakuReportReasons.map{it.second}==(1..8).toList(),"original report reason codes")
    verify(resolveDanmakuRecallConfirmationPreview("  hello   world  ",5)=="hello...","original recall preview policy")

    val requests=mutableListOf<Request>();var responseCode=0;var owned=true;var csrf:String?="task-fixture-csrf"
    val client=OkHttpClient.Builder().addInterceptor { chain ->
        val request=chain.request();requests+=request
        val body=if(request.url.encodedPath.endsWith("/stats"))"""{"code":0,"data":{"901":{"likes":-8,"user_like":1,"id_str":"901"}}}"""
            else """{"code":$responseCode,"message":""}"""
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val api=Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
        .addConverterFactory(Json{ignoreUnknownKeys=true}.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
    val protocol=DesktopOriginalDanmakuProtocol(api,{csrf},{if(!owned)throw CancellationException("retired")})
    try {
        verify(protocol.getDanmakuThumbupState(44,901).getOrThrow()==DanmakuThumbupState(0,true),"original stats key matching/nonnegative/count/user_like")
        verify(requests.last().url.queryParameter("oid")=="44"&&requests.last().url.queryParameter("ids")=="901","actual existing Retrofit stats GET fields")
        verify(protocol.likeDanmaku(44,901,true).isSuccess,"like success")
        val like=requests.last().body as FormBody
        verify(like.value(like.encodedNameList.indexOf("oid"))=="44"&&like.value(like.encodedNameList.indexOf("dmid"))=="901","actual like oid/dmid fields")
        verify(like.value(like.encodedNameList.indexOf("op"))=="1"&&like.value(like.encodedNameList.indexOf("platform"))=="web_player","actual op and platform default")
        verify(like.value(like.encodedNameList.indexOf("csrf"))==csrf,"same injected CSRF")
        protocol.likeDanmaku(44,901,false).getOrThrow()
        verify((requests.last().body as FormBody).let{it.value(it.encodedNameList.indexOf("op"))}=="2","cancel-like op2")
        responseCode=65004;verify(protocol.likeDanmaku(44,901,true).exceptionOrNull()?.message=="已经点过赞了","original mapped like failure")
        responseCode=36302;verify(protocol.recallDanmaku(44,901).exceptionOrNull()?.message=="弹幕发送超过2分钟，无法撤回","original recall time restriction message")
        responseCode=0;protocol.reportDanmaku(44,901,8,"原说明").getOrThrow()
        val report=requests.last().body as FormBody
        verify(report.value(report.encodedNameList.indexOf("cid"))=="44"&&report.value(report.encodedNameList.indexOf("reason"))=="8"&&report.value(report.encodedNameList.indexOf("content"))=="原说明","original report cid/reason/content")
        val count=requests.size;csrf=null
        verify(protocol.recallDanmaku(44,901).exceptionOrNull()?.message=="请先登录"&&requests.size==count,"guest mutation performs no request")
        owned=false;verify(runCatching{protocol.getDanmakuThumbupState(44,901)}.exceptionOrNull() is CancellationException&&requests.size==count,"retired owned API performs no request")
    } finally {client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}

    val parent=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val actions=PendingActions();val feedback=mutableListOf<String>();var epoch=16L
    val environment=DesktopDanmakuSessionEnvironment(44,3,16,parent,actions,{epoch==16L},{123},feedback::add,{})
    val session=DesktopOriginalDanmakuSession(environment)
    try {
        session.showDanmakuMenu(901,"旧");val first=actions.stats.last().second
        session.hideDanmakuMenu();session.showDanmakuMenu(901,"新");val replacement=actions.stats.last().second
        first.complete(Result.success(DanmakuThumbupState(99,true)));yield()
        verify(session.danmakuMenuState.value.text=="新"&&session.danmakuMenuState.value.voteLoading,"old query/finally cannot clear replacement")
        replacement.complete(Result.success(DanmakuThumbupState(7,false)));yield()
        verify(session.danmakuMenuState.value.voteCount==7&&!session.danmakuMenuState.value.voteLoading,"replacement gets original stats receipt")
        session.likeDanmaku(901,true);verify(session.danmakuMenuState.value.voteLoading,"pending same-menu like busy")
        actions.likes.last().completeExceptionally(CancellationException("task only cancel"));yield()
        verify(!session.danmakuMenuState.value.voteLoading&&session.likedDanmakuIds.value.isEmpty(),"single mutation cancellation releases only own busy, no optimistic mutation")
        session.likeDanmaku(901,true);val oldLike=actions.likes.last()
        session.showDanmakuMenu(902,"替换菜单");val secondMenu=actions.stats.last().second
        oldLike.complete(Result.success(Unit));yield()
        verify(session.danmakuMenuState.value.dmid==902L&&session.danmakuMenuState.value.voteLoading,"old confirmed like cannot clear replacement menu")
        secondMenu.complete(Result.success(DanmakuThumbupState(4,false)));yield()
        verify(session.danmakuMenuState.value.voteCount==4&&!session.danmakuMenuState.value.voteLoading,"old-ID refresh does not cancel new-ID query")
        verify(session.likedDanmakuIds.value.contains(901)&&feedback.contains("点赞成功"),"list/menu share original confirmed liked state/message")
        session.recallDanmaku(901);session.reportDanmaku(901,8);yield()
        verify(feedback.contains("撤回成功")&&feedback.contains("举报成功")&&actions.recalls==1&&actions.reports==1,"original success messages")
        session.likeDanmaku(902,true);val late=actions.likes.last();val before=feedback.size
        epoch=17;late.complete(Result.success(Unit));yield()
        verify(902L !in session.likedDanmakuIds.value&&feedback.size==before,"same-MID epoch retired response cannot confirm or call old UI")
        epoch=16;session.close();val statCount=actions.stats.size
        verify(runCatching{session.showDanmakuMenu(903,"closed")}.exceptionOrNull() is CancellationException&&actions.stats.size==statCount,"closed session rejects direct new action even while Root owner remains live")
        verify(parent.coroutineContext[Job]!!.isActive,"session closes only its own child job")
    } finally {session.close();parent.cancel()}
    val result=buildJsonObject {
        put("status","PASS");put("groupedCases",3);put("assertions",assertions)
        put("actualCodeSources",buildJsonArray{listOf(DanmakuParser::class.java,DanmakuProto::class.java,DesktopOriginalDanmakuProtocol::class.java,DesktopOriginalDanmakuSession::class.java,DanmakuThumbupStatsResponse::class.java,BilibiliApi::class.java).forEach{add(identity(it))}})
        put("scope","prepared renderer compile; raw-source projection, existing memory Retrofit API/models, selected session protocol/cancellation; no GUI/native/actual Root button consumer")
    }
    Files.writeString(Path.of(args.single()),result.toString());println(result)
}
