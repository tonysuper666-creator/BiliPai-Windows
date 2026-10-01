package com.bilipai.desktop.ui.commentImageProof

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import com.bilipai.desktop.data.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.nio.file.Files
import java.nio.file.Path
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
private val subject = json.decodeFromString<DynamicItem>("""{"id_str":"100","type":"DYNAMIC_TYPE_WORD","basic":{"comment_id_str":"100","comment_type":17},"modules":{"module_dynamic":{"desc":{"text":"Declared fixture subject"}},"module_stat":{"comment":{"count":3}}}}""")
private val page = json.decodeFromString<ReplyData>("""{"page":{"count":3},"cursor":{"all_count":3,"is_end":true}}""")
private val reply = json.decodeFromString<ReplyItem>("""{"rpid":701,"oid":100,"root":700,"parent":700,"content":{"message":"Declared reply target"},"member":{"mid":"42","uname":"Declared fixture"}}""")

private data class Wire(val path: String, val text: String, val oneShot: Boolean)
private fun form(text: String) = text.split('&').filter { it.isNotEmpty() }.associate {
    val p=it.split('=',limit=2); URLDecoder.decode(p[0],"UTF-8") to URLDecoder.decode(p.getOrElse(1){""},"UTF-8")
}

fun main(args: Array<String>): Unit = runBlocking {
    val scratch=Path.of(args[0]).toRealPath()
    val candidate=Path.of(args[1]).toRealPath()
    val main=Path.of(args[2]).toRealPath()
    var assertions=0
    fun prove(value:Boolean,message:String){check(value){message};assertions++}
    val cases=mutableListOf<String>()
    val sources=mutableListOf<JsonObject>()
    for((clazz,expected) in listOf(
        DesktopDynamicCardOperations::class.java to candidate,
        DesktopDynamicReplyOperationsBinding::class.java to candidate,
        DesktopOriginalDynamicReplySession::class.java to candidate,
        DesktopDynamicEditorSelectedImages::class.java to candidate,
        DesktopSessionStore::class.java to main, DesktopRepository::class.java to main,
        BilibiliApi::class.java to main, ReplyPicture::class.java to main, DynamicItem::class.java to main)) {
        val actual=Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath()
        prove(actual==expected,"actual class source ${clazz.name}")
        sources+=buildJsonObject{put("class",clazz.name);put("path",actual.toString())}
    }
    suspend fun runCase(name:String,body:suspend Harness.()->Unit) {
        Harness(scratch.resolve(name)).use { h -> h.body() }
        cases+=name
    }
    runCase("image-only-nine-ordered-original-wire") {
        val selected=select(9)
        val result=post("",selected)
        prove(result==true to "评论成功","image-only nine success callback")
        prove(wire.size==10&&wire.take(9).all{it.path.endsWith("upload_bfs")}&&wire.last().path.endsWith("/reply/add"),"all nine ordered upload before original add")
        for((i,item) in wire.take(9).withIndex()) {
            prove(item.oneShot&&item.text.contains("filename=\"selected_$i.png\"")&&item.text.contains("Content-Type: image/png"),"one-shot original multipart filename/MIME $i")
            prove(item.text.contains("\r\n\r\ndaily\r\n")&&item.text.contains("\r\n\r\nnew_dyn\r\n")&&item.text.contains("\r\n\r\nfixture-csrf\r\n"),"original category/biz/CSRF $i")
            prove(item.text.contains("declared-image-$i"),"real selected stream bytes $i")
        }
        val fields=form(wire.last().text)
        prove(fields["oid"]=="100"&&fields["type"]=="17"&&fields["message"]=="","original dynamic comment target/image-only message")
        prove("root" !in fields&&"parent" !in fields&&"sync_to_dynamic" !in fields,"original absent root/parent/sync defaults")
        val pictures=json.parseToJsonElement(fields.getValue("pictures")).jsonArray
        prove(pictures.size==9,"complete picture payload")
        prove(pictures.map{it.jsonObject.getValue("img_src").jsonPrimitive.content}==(1..9).map{"https://fixture.invalid/$it.png"},"upload URL order preserved")
        prove(pictures.all{it.jsonObject.keys==setOf("img_src","img_width","img_height","img_size")},"original full ReplyPicture schema")
        prove(session.commentReplyTarget.value==null,"original successful post clears reply target")
    }
    runCase("text-only-and-immutable-reply-target") {
        session.startCommentReply(reply)
        val result=post("reply body",emptyList(),afterAdmission={session.clearCommentReplyTarget()})
        prove(result.first,"text reply succeeds")
        prove(wire.size==1,"no image uploads for text-only reply")
        val fields=form(wire.single().text)
        prove(fields["root"]=="700"&&fields["parent"]=="701"&&fields["message"]=="reply body","immutable original target captured before queued post")
        prove("pictures" !in fields,"text-only original omits pictures")
    }
    runCase("blank-and-ten-rejected-before-transport") {
        prove(post("",emptyList())==false to "请输入评论内容","original empty validation")
        val nine=select(9)
        prove(post("body",nine+"file:///declared-tenth.png")==false to "最多选择 9 张图片","original nine upper bound")
        prove(wire.isEmpty(),"invalid comments never open transport")
    }
    runCase("reply-image-prohibition-before-upload") {
        session.startCommentReply(reply)
        prove(post("body",select(1))==false to "回复暂不支持图片","original reply image prohibition")
        prove(wire.isEmpty()&&session.commentReplyTarget.value?.parentRpid==701L,"rejected reply does not upload or clear original target")
    }
    runCase("ordinary-upload-failure-and-next-attempt") {
        val images=select(3);failUpload=2
        val result=post("body",images)
        prove(!result.first&&result.second=="declared upload failure","ordinary upload failure reaches callback")
        prove(wire.size==2&&wire.none{it.path.endsWith("/reply/add")},"failed ordered upload stops post")
        failUpload=0;wire.clear();uploads.set(0)
        prove(post("retry",images).first,"ordinary upload failure permits next attempt")
        prove(wire.size==4,"retry preserves all same selected handles")
    }
    runCase("ordinary-add-failure-and-next-attempt") {
        val images=select(1);failAdd=true
        prove(post("body",images)==false to "评论区已关闭","original add error mapping")
        failAdd=false;wire.clear();uploads.set(0)
        prove(post("retry",images).first&&wire.size==2,"ordinary post failure permits retry with same selection")
    }
    runCase("same-mid-account-replacement-no-late-post-or-callback") {
        val images=select(1);holdUpload=true
        val callback=AtomicInteger()
        session.postComment("100","body",images){_,_->callback.incrementAndGet()}
        awaitEntered()
        store.saveAccount(mapOf("SESSDATA" to "declared-new-owner","bili_jct" to "fixture-csrf"),AccountSummary(42,"Declared fixture",""))
        release.countDown();awaitTerminal()
        delay(200)
        prove(!operations.isOwned()&&callback.get()==0,"same MID new actual Store epoch rejects old receipt")
        prove(wire.size==1&&wire.none{it.path.endsWith("/reply/add")},"old streamed upload cannot cause later post")
    }
    runCase("closed-page-keeps-account-no-late-post-or-callback") {
        val images=select(1);holdUpload=true
        val callback=AtomicInteger()
        session.postComment("100","body",images){_,_->callback.incrementAndGet()}
        awaitEntered();session.close();selected.close();release.countDown();awaitTerminal();delay(200)
        prove(operations.isOwned()&&!session.isOwned()&&callback.get()==0,"page child cancellation with actual account active has no late callback")
        prove(wire.size==1&&wire.none{it.path.endsWith("/reply/add")},"closed page cannot post after upload await")
    }
    runCase("request-generation-change-after-upload-no-late-post") {
        val images=select(1);holdUpload=true
        val callback=AtomicInteger()
        session.postComment("100","body",images){_,_->callback.incrementAndGet()}
        awaitEntered();session.loadComments("100");release.countDown();awaitTerminal();delay(200)
        prove(operations.isOwned()&&session.isOwned()&&callback.get()==0,"same page new request generation rejects old post result")
        prove(wire.size==1&&wire.none{it.path.endsWith("/reply/add")},"post checks original request generation after final image await")
    }
    val output=buildJsonObject {
        put("status","PASS");put("assertions",assertions);put("caseCount",cases.size)
        put("cases",JsonArray(cases.map{JsonPrimitive(it)}));put("actualCodeSources",JsonArray(sources))
        put("terminalApplicationInterceptorNoProceed",true);put("temporaryDeclaredCredentialStoreOnly",true)
        put("noSocket",true);put("noHTTP",true);put("noHWND",true);put("noMainEdits",true)
        put("notRootButtonOrChooserE2E",true);put("preparedOnly",true)
    }
    Files.writeString(scratch.resolve("result.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),output)+"\n")
    println(output)
}

private class Harness(val scratch:Path):AutoCloseable {
    val store=DesktopSessionStore.temporary()
    val repository:DesktopRepository
    val operations:DesktopDynamicCardOperations
    val selected:DesktopDynamicEditorSelectedImages
    val session:DesktopOriginalDynamicReplySession
    val wire=CopyOnWriteArrayList<Wire>()
    val uploads=AtomicInteger()
    val entered=CountDownLatch(1);val release=CountDownLatch(1);val terminal=CountDownLatch(1)
    @Volatile var holdUpload=false
    @Volatile var failUpload=0
    @Volatile var failAdd=false
    private val failures=CopyOnWriteArrayList<Throwable>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default+CoroutineExceptionHandler{_,t->failures+=t})
    init {
        Files.createDirectories(scratch)
        store.saveAccount(mapOf("SESSDATA" to "declared-comment-image-fixture","bili_jct" to "fixture-csrf"),AccountSummary(42,"Declared fixture",""))
        repository=DesktopRepository(store)
        val client=repository.httpClient.newBuilder().addInterceptor { chain ->
            val r=chain.request();check(r.url.host=="api.bilibili.com")
            check(r.url.encodedPath in setOf("/x/dynamic/feed/draw/upload_bfs","/x/v2/reply/add"))
            val text=Buffer().also{r.body?.writeTo(it)}.readUtf8()
            wire+=Wire(r.url.encodedPath,text,r.body!!.isOneShot())
            val body=if(r.url.encodedPath.endsWith("upload_bfs")) {
                val index=uploads.incrementAndGet()
                if(holdUpload){entered.countDown();check(release.await(5,TimeUnit.SECONDS));terminal.countDown()}
                if(failUpload==index) """{"code":-1,"message":"declared upload failure"}"""
                else """{"code":0,"data":{"image_url":"https://fixture.invalid/$index.png","image_width":13,"image_height":17,"img_size":1.25}}"""
            } else if(failAdd) """{"code":12002}""" else """{"code":0,"data":{"reply":{"rpid":9001,"oid":100,"ctime":10,"content":{"message":"Declared posted comment"},"member":{"mid":"42","uname":"Declared fixture"}}}}"""
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("declared terminal fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        fun replace(name:String,value:Any)=repository.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(repository,value)
        replace("client",client)
        replace("api",Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java))
        replace("visitorInitialized",true);replace("visitorGeneration",store.generation)
        operations=DesktopDynamicCardOperations(repository)
        selected=DesktopDynamicEditorSelectedImages(operations::isOwned,operations::withOwnedEditorImageAdmission)
        val binding=DesktopDynamicReplyOperationsBinding(operations,{true},{Result.success(subject)},selected::read)
        val reads=object:DesktopDynamicReplyRequests by binding {
            override suspend fun getCommentCountForSubject(oid:Long,type:Int)=Result.success(3)
            override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean)=Result.success(com.bilipai.desktop.ui.commentImageProof.page)
            override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long)=Result.success(com.bilipai.desktop.ui.commentImageProof.page)
        }
        session=DesktopOriginalDynamicReplySession("100",scope,reads,{subject})
    }
    fun select(count:Int):List<String> = selected.accept((0 until count).map { i ->
        scratch.resolve("selected_$i.png").also{Files.writeString(it,"declared-image-$i")}
    })
    suspend fun post(message:String,images:List<String>,afterAdmission:()->Unit={}):Pair<Boolean,String> {
        val result=CompletableDeferred<Pair<Boolean,String>>()
        session.postComment("100",message,images){ok,text->result.complete(ok to text)}
        afterAdmission()
        return withTimeout(5000){result.await()}
    }
    suspend fun awaitEntered()=withContext(Dispatchers.IO){check(entered.await(5,TimeUnit.SECONDS))}
    suspend fun awaitTerminal()=withContext(Dispatchers.IO){check(terminal.await(5,TimeUnit.SECONDS))}
    override fun close(){release.countDown();session.close();selected.close();scope.cancel();check(failures.isEmpty()){failures.toString()}}
}
