package com.android.purebilibili.data.repository.fraudProof

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.FORCE_COOKIE_HEADER
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.data.repository.DesktopOriginalCommentFraudProtocol
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.*
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val json=Json { ignoreUnknownKeys=true; coerceInputValues=true }
private const val TARGET=701L
private const val ROOT=700L
private const val AID=100L

fun main(args:Array<String>):Unit=runBlocking {
    val scratch=Path.of(args[0]).toRealPath();val candidate=Path.of(args[1]).toRealPath();val main=Path.of(args[2]).toRealPath()
    var assertions=0
    fun prove(v:Boolean,m:String){check(v){m};assertions++}
    val cases=mutableListOf<String>();val sources=mutableListOf<JsonObject>()
    for((clazz,expected) in listOf(DesktopOriginalCommentFraudProtocol::class.java to candidate,
        CommentFraudStatus::class.java to candidate, DesktopDynamicCardOperations::class.java to candidate,
        DesktopSessionStore::class.java to main,DesktopRepository::class.java to main,
        BilibiliApi::class.java to main,ReplyItem::class.java to main)) {
        val actual=Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath()
        prove(actual==expected,"code source ${clazz.name}")
        sources+=buildJsonObject{put("class",clazz.name);put("path",actual.toString())}
    }
    suspend fun case(name:String,body:suspend ()->Unit){body();cases+=name}
    case("root-guest-original-header-and-literal-invisible-limit") {
        val h=Harness { """{"code":0,"data":{"replies":[{"rpid":701,"invisible":true}]}}""" }
        prove(h.check()==CommentFraudStatus.NORMAL,"preserve original String.contains regex-looking literal limit")
        val r=h.calls.requests.single()
        prove(r.url.queryParameter("type")=="1"&&r.url.queryParameter("mode")=="2"&&r.url.queryParameter("next")=="0"&&r.url.queryParameter("ps")=="20","original root URL fields")
        prove(r.header("Cookie")=="buvid3=declared-visitor;"&&r.header(FORCE_COOKIE_HEADER)=="buvid3=declared-visitor;","guest force-cookie excludes all account cookies")
        prove(r.header("User-Agent")!!.contains("Chrome/131.0.0.0")&&r.header("Origin")=="https://www.bilibili.com"&&r.header("Referer")=="https://www.bilibili.com"&&r.header("Accept")=="application/json, text/plain, */*","original raw headers")
        prove(h.signs.isEmpty()&&h.visitor.get()==1,"visible guest short circuit has no auth or WBI")
    }
    case("sub-last-prev-use-real-rcount") {
        val h=Harness { r->if(r.url.queryParameter("pn")=="3") """{"code":0,"data":{"replies":[{"rpid":701}]}}""" else """{"code":0,"data":{"rcount":61,"page":{"count":20}}}""" }
        prove(h.check(ROOT)==CommentFraudStatus.NORMAL,"previous-page guest target")
        prove(h.calls.requests.map{it.url.queryParameter("pn")}==listOf("1","4","3"),"rcount wins over 20-window count; preserve last/prev order")
        prove(h.calls.requests.all{it.url.queryParameter("type")=="1"&&it.url.queryParameter("root")=="700"&&it.url.queryParameter("ps")=="20"},"original sub fields")
    }
    case("sub-ctime-binary-five-step-bound-algorithm") {
        val h=Harness { r->if(r.url.queryParameter("pn")=="5") """{"code":0,"data":{"replies":[{"rpid":701,"ctime":50}]}}""" else """{"code":0,"data":{"rcount":240,"page":{"count":20},"replies":[{"rpid":999,"ctime":10}]}}""" }
        prove(h.check(ROOT,sentAt=50)==CommentFraudStatus.NORMAL,"binary guest target")
        prove(h.calls.requests.map{it.url.queryParameter("pn")}==listOf("1","12","11","5"),"original middle-page algorithm")
    }
    case("sub-auth-full-nested-model-invisible") {
        val h=Harness { r->if(r.header(FORCE_COOKIE_HEADER)==null) """{"code":0,"data":{"hots":[{"rpid":800,"replies":[{"rpid":701,"invisible":true}]}]}}""" else """{"code":0,"data":{"page":{"count":20},"replies":[]}}""" }
        prove(h.check(ROOT)==CommentFraudStatus.INVISIBLE,"original auth hots nested reply parser")
        val auth=h.calls.requests.last()
        prove(auth.url.encodedPath=="/x/v2/reply/reply"&&auth.url.queryParameter("oid")=="100"&&auth.url.queryParameter("type")=="1"&&auth.url.queryParameter("root")=="700"&&auth.url.queryParameter("pn")=="1","original typed sub request")
    }
    case("root-auth-top-wbi-and-guest-single-omitted-type") {
        val h=Harness { r->when { r.url.queryParameter("seek_rpid")!=null->"""{"code":0,"data":{"top":{"upper":{"rpid":701}}}}""";r.url.encodedPath.endsWith("/reply/reply")->"""{"code":0}""";else->"""{"code":0,"data":{"replies":[]}}""" } }
        prove(h.check()==CommentFraudStatus.UNDER_REVIEW,"auth top reply plus visible single reply page")
        prove(h.signs.single()==mapOf("oid" to "100","type" to "1","mode" to "2","next" to "0","ps" to "20","seek_rpid" to "701"),"exact original unsigned WBI map")
        val auth=h.calls.requests[1]
        prove(auth.url.queryParameter("w_rid")=="declared-signature"&&auth.url.queryParameter("wts")=="123"&&auth.header(FORCE_COOKIE_HEADER)==null,"same original typed API signed auth route")
        val single=h.calls.requests.last()
        prove(single.url.queryParameter("type")==null&&single.url.queryParameter("root")=="701"&&single.url.queryParameter("pn")=="1"&&single.url.queryParameter("ps")=="1","original guest single reply omits type")
    }
    case("root-auth-invisible-and-deleted-hint") {
        val invisible=Harness { r->if(r.url.queryParameter("seek_rpid")!=null) """{"code":0,"data":{"replies":[{"rpid":701,"invisible":true}]}}""" else if(r.url.encodedPath.endsWith("/reply/reply")) """{"code":12022}""" else """{"code":0,"data":{"replies":[]}}""" }
        prove(invisible.check()==CommentFraudStatus.INVISIBLE,"typed invisible overrides guest single page")
        val deleted=Harness { r->if(r.url.queryParameter("seek_rpid")!=null) """{"code":12009}""" else """{"code":0,"data":{"replies":[]}}""" }
        prove(deleted.check()==CommentFraudStatus.DELETED&&deleted.calls.requests.size==2,"original auth deletion hint short circuit")
    }
    case("root-double-miss-real-second-probe-delay") {
        val h=Harness { """{"code":0,"data":{"replies":[]}}""" }
        val started=System.nanoTime()
        prove(h.check()==CommentFraudStatus.DELETED,"original second probe confirms deletion")
        prove((System.nanoTime()-started)/1_000_000>=2100,"real original 2200ms delay executed")
        prove(h.calls.requests.size==4&&h.signs.size==2,"both guest/auth repeat exactly once")
    }
    case("root-retry-guest-reappearance-avoids-false-deletion") {
        val guest=AtomicInteger()
        val h=Harness { r->if(r.header(FORCE_COOKIE_HEADER)!=null&&guest.incrementAndGet()==2) """{"code":0,"data":{"replies":[{"rpid":701}]}}""" else """{"code":0,"data":{"replies":[]}}""" }
        prove(h.check()==CommentFraudStatus.UNKNOWN,"original retry guest reappearance does not reuse as normal; no false deletion")
        prove(h.calls.requests.size==3&&h.signs.size==1,"no second auth after guest reappearance")
    }
    case("ordinary-raw-failure-is-unknown-and-next-check-remains-usable") {
        val h=Harness { """{"code":0,"data":{"replies":[{"rpid":701}]}}""" }
        h.calls.fail=true
        prove(h.check(ROOT)==CommentFraudStatus.UNKNOWN,"raw ordinary IOException returns original UNKNOWN")
        h.calls.fail=false
        prove(h.check()==CommentFraudStatus.NORMAL,"next check works after ordinary failure")
    }
    case("cancel-wait-is-cancellation-not-unknown") {
        val h=Harness { """{"code":0}""" }
        val job=async{h.check(wait=5000)}
        withTimeout(1000){while(h.visitor.get()==0)delay(5)}
        job.cancel()
        prove(runCatching{job.await()}.exceptionOrNull() is CancellationException,"cancel delay throws rather than success UNKNOWN")
        prove(h.calls.requests.isEmpty(),"canceled original wait has no transport")
    }
    case("retired-owner-late-raw-response-closed-and-canceled") {
        val h=Harness { """{"code":0}""" };h.calls.hold=true
        val job=async{h.check()};h.calls.entered.await()
        h.alive.set(false)
        val late=TrackedBody("""{"code":0,"data":{"replies":[{"rpid":701}]}}""")
        h.calls.complete(late)
        prove(runCatching{job.await()}.exceptionOrNull() is CancellationException,"retired owner prevents late NORMAL/UNKNOWN receipt")
        prove(h.calls.last!!.isCanceled()&&late.closed.get()==1,"cancel underlying call and close late response once")
        prove(h.calls.requests.size==1,"retired raw has no auth follow-on")
    }
    case("actual-ops-same-mid-epoch-replacement") {
        val store=DesktopSessionStore.temporary()
        store.saveAccount(mapOf("SESSDATA" to "declared-fraud-old","bili_jct" to "declared-csrf","buvid3" to "declared-visitor"),AccountSummary(42,"Declared fraud fixture",""),imported=true)
        val repository=DesktopRepository(store);val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        val client=repository.httpClient.newBuilder().addInterceptor { chain->
            val request=chain.request();check(request.url.encodedPath=="/x/v2/reply/main");calls.incrementAndGet()
            check(request.header("Cookie")=="buvid3=declared-visitor;"){"anonymous existing Ops excludes real temporary account cookies"}
            entered.countDown();check(release.await(3,TimeUnit.SECONDS))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("declared terminal, no proceed")
                .body("""{"code":0,"data":{"replies":[{"rpid":701}]}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        fun replace(name:String,value:Any)=repository.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(repository,value)
        replace("client",client);replace("visitorInitialized",true);replace("visitorGeneration",store.generation)
        val ops=DesktopDynamicCardOperations(repository)
        val result=async(Dispatchers.Default){ops.checkCommentStatus(AID,TARGET,waitMs=0)}
        prove(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)},"actual Ops raw reached terminal interceptor")
        store.saveAccount(mapOf("SESSDATA" to "declared-fraud-new","bili_jct" to "declared-csrf"),AccountSummary(42,"Declared fraud fixture",""))
        release.countDown()
        prove(runCatching{result.await()}.exceptionOrNull() is CancellationException,"actual same MID new SessionStore epoch cancels old check")
        prove(!ops.isOwned()&&calls.get()==1,"actual same owner authority and no late follow-on")
        client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
    }
    val output=buildJsonObject {
        put("status","PASS");put("assertions",assertions);put("caseCount",cases.size);put("cases",JsonArray(cases.map{JsonPrimitive(it)}))
        put("actualCodeSources",JsonArray(sources));put("fakeRetrofitCallFactoryNoSocket",true);put("oneActualOpsTerminalInterceptorNoProceed",true)
        put("actualTemporarySessionStoreSameMidEpoch",true);put("noHTTP",true);put("noSocket",true);put("noHWND",true);put("noMainEdits",true)
        put("preparedProtocolOnly",true);put("notBgmUiOrPersistentFraudRecordsE2E",true)
    }
    Files.writeString(scratch.resolve("result.json"),Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),output)+"\n");println(output)
}

private class Harness(script:(Request)->String) {
    val alive=AtomicBoolean(true);val visitor=AtomicInteger();val signs=CopyOnWriteArrayList<Map<String,String>>()
    val calls=FakeCalls(script)
    private val api=Retrofit.Builder().baseUrl("https://api.bilibili.com/").callFactory(calls)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
    private val protocol=DesktopOriginalCommentFraudProtocol(api,calls,{"declared-visitor"},
        {params->signs+=params.toMap();params+mapOf("w_rid" to "declared-signature","wts" to "123")},{visitor.incrementAndGet()}){
        if(!alive.get())throw CancellationException("Declared fixture owner retired")
    }
    suspend fun check(root:Long=0,sentAt:Long=0,wait:Long=0)=protocol.checkCommentStatus(AID,TARGET,root,false,sentAt,wait).getOrThrow()
}

private class FakeCalls(private val script:(Request)->String):Call.Factory {
    val requests=CopyOnWriteArrayList<Request>();val entered=CompletableDeferred<Unit>()
    @Volatile var fail=false;@Volatile var hold=false;@Volatile var last:FakeCall?=null
    override fun newCall(request:Request):Call=FakeCall(request).also{last=it}
    fun complete(body:ResponseBody){val call=checkNotNull(last);call.callback!!.onResponse(call,response(call.request(),body))}
    private fun response(r:Request,body:ResponseBody)=Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("declared fake call").body(body).build()
    inner class FakeCall(private val req:Request):Call {
        private val canceled=AtomicBoolean();private val executed=AtomicBoolean();@Volatile var callback:Callback?=null
        private val tags=mutableMapOf<Class<*>,Any?>()
        @Suppress("UNCHECKED_CAST") override fun <T> tag(type:Class<out T>):T?=synchronized(tags){tags[type] as T?}
        override fun <T:Any> tag(type:kotlin.reflect.KClass<T>):T?=tag(type.java)
        @Suppress("UNCHECKED_CAST") override fun <T> tag(type:Class<T>,computeIfAbsent:()->T):T=synchronized(tags){tags.getOrPut(type,computeIfAbsent) as T}
        override fun <T:Any> tag(type:kotlin.reflect.KClass<T>,computeIfAbsent:()->T):T=tag(type.java,computeIfAbsent)
        override fun request()=req
        override fun execute():Response=error("No synchronous or socket transport permitted")
        override fun enqueue(callback:Callback){check(executed.compareAndSet(false,true));this.callback=callback;requests+=req;entered.complete(Unit)
            if(hold)return
            if(fail)callback.onFailure(this,IOException("Declared ordinary transport failure"))
            else callback.onResponse(this,response(req,script(req).toResponseBody("application/json".toMediaType())))
        }
        override fun cancel(){canceled.set(true)}
        override fun isExecuted()=executed.get()
        override fun isCanceled()=canceled.get()
        override fun timeout()=Timeout.NONE
        override fun clone():Call=FakeCall(req)
    }
}

private class TrackedBody(raw:String):ResponseBody() {
    val closed=AtomicInteger();private val bytes=raw.toByteArray();private val stream=object:ForwardingSource(Buffer().write(bytes)){
        override fun close(){closed.incrementAndGet();super.close()}
    }.buffer()
    override fun contentType()="application/json".toMediaType()
    override fun contentLength()=bytes.size.toLong()
    override fun source()=stream
}
