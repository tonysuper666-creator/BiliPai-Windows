package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.feature.video.policy.resolveFavoriteFolderMediaId
import com.android.purebilibili.feature.video.viewmodel.DesktopOriginalFavoriteFolderSession
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

private var assertions=0
private fun verify(value:Boolean,description:String) { check(value){description};assertions++ }
private suspend fun await(description:String,test:()->Boolean) { withTimeout(5000){while(!test())delay(5)};verify(test(),description) }
private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
private fun identity(type:Class<*>)=buildJsonObject {
    put("class",type.name);put("path",Path.of(type.protectionDomain.codeSource.location.toURI()).toString())
    put("classSha256Bytes",sha(type.getResourceAsStream("/"+type.name.replace('.','/')+".class")!!.use{it.readBytes()}))
}

fun main(args:Array<String>)=runBlocking {
    val requests=CopyOnWriteArrayList<Request>()
    var owned=true
    var catalog="""{"code":0,"message":"0","data":{"count":3,"list":[{"id":5,"fid":55,"title":"甲","fav_state":1},{"id":0,"fid":8,"title":"乙","fav_state":1},{"id":11,"fid":111,"title":"丙","fav_state":0}]}}"""
    var dealCode=0
    val client=OkHttpClient.Builder().addInterceptor { chain ->
        val request=chain.request();requests.add(request)
        val body=when(request.url.encodedPath) {
            "/x/v3/fav/folder/created/list-all" -> catalog
            "/x/v3/fav/resource/deal" -> """{"code":$dealCode,"message":"deal-result"}"""
            else -> error("Unexpected transport ${request.url}")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val api=Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
        .addConverterFactory(Json{ignoreUnknownKeys=true}.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
    val protocol=DesktopOriginalFavoriteFolderProtocol(api,{77L},{"fixture-csrf"},{if(!owned)throw CancellationException("epoch retired")})
    val received=protocol.getFavoriteFolders(1234).getOrThrow()
    val query=requests.last().url
    verify(query.queryParameter("up_mid")=="77" && query.queryParameter("type")=="2" && query.queryParameter("rid")=="1234","Original video-membership query")
    verify(query.queryParameter("web_location")=="333.1387","Original web_location default")
    verify(received.map(::resolveFavoriteFolderMediaId)==listOf(5L,8L,11L),"Original id first, fid fallback")
    protocol.getFavoriteFolders().getOrThrow()
    verify(requests.last().url.queryParameter("type")==null && requests.last().url.queryParameter("rid")==null,"Null aid omits type/rid")
    val initialPosts=requests.count{it.method=="POST"}
    protocol.updateFavoriteFolders(1234,setOf(11,5),setOf(9,8)).getOrThrow()
    val form=requests.last().body as FormBody
    val fields=(0 until form.size).associate{form.name(it) to form.value(it)}
    verify(fields==mapOf("rid" to "1234","type" to "2","add_media_ids" to "5,11","del_media_ids" to "8,9","csrf" to "fixture-csrf"),"Original sorted single POST fields")
    protocol.updateFavoriteFolders(1234,emptySet(),emptySet()).getOrThrow()
    verify(requests.count{it.method=="POST"}==initialPosts+1,"Empty mutation performs no POST")
    owned=false
    verify(runCatching{protocol.getFavoriteFolders(1234)}.exceptionOrNull() is CancellationException,"Old epoch rejected before transport")
    owned=true

    val feedback=CopyOnWriteArrayList<String>();val projections=CopyOnWriteArrayList<Pair<Boolean,Int>>()
    var currentCount=10;var loadedFavorite:Boolean?=null;var created:Triple<String,String,Boolean>?=null
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    val environment=DesktopFavoriteFolderEnvironment(scope,{owned},{1234},{currentCount},protocol::getFavoriteFolders,protocol::updateFavoriteFolders,
        {title,intro,privateFolder->created=Triple(title,intro,privateFolder);Result.success(true)},
        {loadedFavorite=it},{favorite,count->currentCount=count;projections.add(favorite to count)},feedback::add)
    val session=DesktopOriginalFavoriteFolderSession(environment)
    try {
        session.showFavoriteFolderDialog()
        await("Real returned fav_state drives original preselection"){!session.isFavoriteFoldersLoading.value && session.favoriteSelectedFolderIds.value==setOf(5L,8L)}
        verify(loadedFavorite==true && session.favoriteFolderDialogVisible.value,"Root loaded projection and sheet visibility")
        session.toggleFavoriteFolderSelection(0)
        verify(session.favoriteSelectedFolderIds.value==setOf(5L,8L),"Zero ID rejected")
        session.toggleFavoriteFolderSelection(received[0]);session.toggleFavoriteFolderSelection(received[2])
        verify(session.favoriteSelectedFolderIds.value==setOf(8L,11L),"Original checkbox multi-select")
        session.saveFavoriteFolderSelection()
        await("Successful original save releases busy and dismisses"){!session.isSavingFavoriteFolders.value && !session.favoriteFolderDialogVisible.value}
        verify(projections.last()==(true to 10),"Moving between folders keeps original favorite count")
        verify(session.favoriteFolders.value.map{it.fav_state}==listOf(0,1,1),"Successful receipt updates original folder membership")
        verify(session.favoriteFolderSaveEvent.value?.let{it.aid==1234L && it.isFavorited && it.version==1L}==true,"Original immutable aid/save event version")
        session.showFavoriteFolderDialog()
        session.toggleFavoriteFolderSelection(8);session.toggleFavoriteFolderSelection(11)
        session.saveFavoriteFolderSelection()
        await("Remove all receipt settles"){session.favoriteFolderSaveEvent.value?.version==2L && !session.isSavingFavoriteFolders.value}
        verify(projections.last()==(false to 9) && feedback.last()=="已取消收藏","Original membership removal count/text")
        val before=requests.size
        session.showFavoriteFolderDialog();session.saveFavoriteFolderSelection()
        verify(requests.size==before && feedback.last()=="收藏夹未变更","Unchanged selection dismisses without API")
        session.showFavoriteFolderDialog();session.toggleFavoriteFolderSelection(11)
        dealCode=-400
        session.saveFavoriteFolderSelection()
        await("Single failed mutation releases busy"){!session.isSavingFavoriteFolders.value && feedback.last().startsWith("收藏失败:")}
        verify(session.favoriteFolderDialogVisible.value && session.favoriteFolderSaveEvent.value?.version==2L,"Failure leaves dialog/last receipt unchanged")
        dealCode=0;session.saveFavoriteFolderSelection()
        await("Failure permits subsequent real transport retry"){session.favoriteFolderSaveEvent.value?.version==3L}
        verify(projections.last()==(true to 10) && feedback.last()=="收藏设置已保存","New membership increments count once")
        session.showFavoriteFolderDialog()
        session.createFavoriteFolder("  原标题  ","简介",true)
        await("Create triggers original membership reload"){created!=null && !session.isFavoriteFoldersLoading.value}
        verify(created==Triple("  原标题  ","简介",true),"Create preserves title/intro/privacy callback arguments")
        verify(session.favoriteSelectedFolderIds.value==setOf(11L),"Create reload keeps available local selection")
    } finally {session.close();scope.cancel()}

    // Cancelled old load deliberately completes late; only the replacement request controls busy.
    val deferred=CopyOnWriteArrayList<CompletableDeferred<Result<List<FavFolder>>>>()
    val replacementScope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    val replacementEnvironment=DesktopFavoriteFolderEnvironment(replacementScope,{true},{1234},{10},
        {val pending=CompletableDeferred<Result<List<FavFolder>>>();deferred.add(pending);withContext(NonCancellable){pending.await()}},
        {_,_,_->Result.success(true)},{_,_,_->Result.success(true)},{},{_,_->},{})
    val replacement=DesktopOriginalFavoriteFolderSession(replacementEnvironment)
    try {
        replacement.showFavoriteFolderDialog();replacement.showFavoriteFolderDialog()
        verify(deferred.size==2 && replacement.isFavoriteFoldersLoading.value,"Two actual requests have distinct load generations")
        deferred[0].complete(Result.success(listOf(FavFolder(id=99,fav_state=1))))
        delay(20)
        verify(replacement.isFavoriteFoldersLoading.value && replacement.favoriteFolders.value.isEmpty(),"Old cancelled finally cannot clear replacement or commit old folders")
        deferred[1].complete(Result.success(received))
        await("Replacement returns current source"){!replacement.isFavoriteFoldersLoading.value && replacement.favoriteFolders.value==received}
        replacement.invalidateFavoriteFolderCache();replacement.showFavoriteFolderDialog()
        verify(deferred.size==3 && replacement.isFavoriteFoldersLoading.value,"Cache invalidation starts fresh request")
        replacement.invalidateFavoriteFolderCache()
        deferred[2].complete(Result.success(listOf(FavFolder(id=99,fav_state=1))))
        delay(20)
        verify(!replacement.isFavoriteFoldersLoading.value && replacement.favoriteFolders.value.isEmpty(),"Invalidated pending read cannot restore stale cache")
    } finally {replacement.close();replacementScope.cancel()}

    // Only page/epoch retires, while the existing parent scope stays alive.
    var currentOwner=true;var lateCallbacks=0
    val pendingSave=CompletableDeferred<Result<Boolean>>()
    val retiredScope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    val retiredEnvironment=DesktopFavoriteFolderEnvironment(retiredScope,{currentOwner},{1234},{10},
        {Result.success(listOf(FavFolder(id=11)))},{_,_,_->pendingSave.await()},
        {_,_,_->Result.success(true)},{},{_,_->lateCallbacks++},{lateCallbacks++})
    val retired=DesktopOriginalFavoriteFolderSession(retiredEnvironment)
    try {
        retired.showFavoriteFolderDialog();retired.toggleFavoriteFolderSelection(11);retired.saveFavoriteFolderSelection()
        verify(retired.isSavingFavoriteFolders.value && retiredScope.isActive,"Live parent owns pending save")
        currentOwner=false;pendingSave.complete(Result.success(true))
        await("Retired pending save releases only its own busy"){!retired.isSavingFavoriteFolders.value}
        verify(lateCallbacks==0 && retired.favoriteFolderSaveEvent.value==null,"Retired owner has no late UI feedback/count/event receipt")
    } finally {retired.close();retiredScope.cancel()}

    val codes=listOf(DesktopOriginalFavoriteFolderProtocol::class.java,DesktopOriginalFavoriteFolderSession::class.java,
        DesktopFavoriteFolderEnvironment::class.java,BilibiliApi::class.java,FavFolder::class.java).map(::identity)
    val result=buildJsonObject {
        put("status","PASS");put("groupedCases",4);put("assertions",assertions)
        put("requests",requests.size);put("actualCodeSources",JsonArray(codes))
        put("actualOriginalUiMounted",false);put("actualRootVideoStateProjectionConsumed",false)
        put("transport","existing original Retrofit/API/model with memory application interceptor, zero socket")
        put("noHTTP",true);put("noHWND",true);put("noAccountReads",true);put("noMainEdits",true)
    }
    Files.writeString(Path.of(args[0]),result.toString());println(result)
    client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
}
