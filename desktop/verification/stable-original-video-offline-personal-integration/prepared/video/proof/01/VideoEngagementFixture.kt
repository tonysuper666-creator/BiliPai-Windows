package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol
import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.feature.video.usecase.TripleActionResult
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

private var assertions = 0
private fun verify(condition: Boolean, label: String) { check(condition) { label }; assertions++ }
private fun form(request: Request): Map<String,String> {
    val value = request.body as FormBody
    return (0 until value.size).associate { value.name(it) to value.value(it) }
}
private fun identity(type: Class<*>) = buildJsonObject {
    put("class", type.name)
    put("path", Path.of(type.protectionDomain.codeSource.location.toURI()).toString())
    val bytes = type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")!!.use { it.readBytes() }
    put("classSha256Bytes", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
}
private class ControlledActions : VideoEngagementActions {
    var like: suspend () -> Result<Boolean> = { error("Unconfigured like") }
    var triple: suspend () -> Result<TripleActionResult> = { error("Unconfigured triple") }
    override suspend fun toggleLike(aid:Long,currentlyLiked:Boolean,bvid:String) = like()
    override suspend fun doTripleAction(aid:Long) = triple()
    override suspend fun toggleFollow(mid:Long,currentlyFollowing:Boolean):Result<Boolean> = error("Unused follow")
    override suspend fun toggleDislike(aid:Long,currentlyDisliked:Boolean,bvid:String):Result<Boolean> = error("Unused dislike")
    override suspend fun toggleFavorite(aid:Long,currentlyFavorited:Boolean,bvid:String):Result<Boolean> = error("Unused favorite")
    override suspend fun toggleWatchLater(aid:Long,currentlyInWatchLater:Boolean,bvid:String):Result<Boolean> = error("Unused watchlater")
    override suspend fun doCoin(aid:Long,count:Int,alsoLike:Boolean,bvid:String):Result<Boolean> = error("Unused coin")
}
private fun subject(generation: Long) = VideoSubjectSnapshot("BV-fixture-$generation", 80+generation, 900+generation, 77, "local", "", 1000, generation)

fun main(args: Array<String>) = runBlocking {
    val requests = CopyOnWriteArrayList<Request>()
    var owned = true
    var code = 0
    var coinCode = 34005
    var retireOn: String? = null
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); requests.add(request)
        val path = request.url.encodedPath
        val body = when (path) {
            "/x/v3/fav/folder/created/list-all" -> """{"code":0,"message":"0","data":{"count":2,"list":[{"id":15,"fid":150,"title":"first"},{"id":99,"fid":990,"title":"second"}]}}"""
            "/x/web-interface/coin/add" -> """{"code":$coinCode,"message":"coin-original"}"""
            else -> """{"code":$code,"message":"raw-original"}"""
        }
        if (retireOn == path) owned = false
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
        .addConverterFactory(Json { ignoreUnknownKeys=true }.asConverterFactory("application/json".toMediaType()))
        .build().create(BilibiliApi::class.java)
    fun assertOwned() { if (!owned) throw CancellationException("Synthetic owner retired") }
    val folders = DesktopOriginalFavoriteFolderProtocol(api, {77L}, {"fixture-csrf"}, ::assertOwned)
    val protocol = DesktopOriginalVideoEngagementProtocol(api, {"fixture-csrf"}, {77L}, {"fixture-sess"}, {"fixture-access"}, ::assertOwned, { error("Unused follow confirmation") }, folders)
    for (idempotent in listOf(65007,65005)) {
        code = idempotent
        verify(protocol.dislikeVideo(901,true).getOrThrow(), "Original dislike idempotent code $idempotent")
        verify(form(requests.last())["dislike"]=="0" && form(requests.last())["access_key"]=="fixture-access", "Original APP dislike=0/access_key")
    }
    code=0
    verify(!protocol.dislikeVideo(901,false).getOrThrow() && form(requests.last())["dislike"]=="1", "Original cancellation dislike=1")
    code=-101
    verify(protocol.dislikeVideo(901,true).exceptionOrNull()?.message=="视频点踩需要 APP 鉴权，请先在登录页完成高画质（TV）鉴权登录", "Original APP authorization failure text")
    code=0;requests.clear()
    val partial = protocol.tripleAction(901).getOrThrow()
    verify(partial.likeSuccess && !partial.coinSuccess && partial.favoriteSuccess && partial.coinMessage=="已投满2个硬币", "Original partial sequential triple fields")
    verify(requests.map { it.url.encodedPath }==listOf("/x/web-interface/archive/like","/x/web-interface/coin/add","/x/v3/fav/folder/created/list-all","/x/v3/fav/resource/deal"), "Original triple order and default folder read")
    verify(form(requests[1])["multiply"]=="2" && form(requests[1])["select_like"]=="1", "Original triple 2 coins with like")
    verify(form(requests.last())["add_media_ids"]=="15" && form(requests.last())["del_media_ids"]=="", "Original first folder ID, one favorite POST")
    code=90001
    verify(protocol.toggleWatchLater(901,true).exceptionOrNull()?.message=="稍后再看列表已满", "Original watchlater full text")
    code=90003
    verify(protocol.toggleWatchLater(901,true).exceptionOrNull()?.message=="视频已被删除", "Original watchlater deleted text")
    code=0; requests.clear(); retireOn="/x/web-interface/archive/like"
    verify(runCatching { protocol.tripleAction(901) }.exceptionOrNull() is CancellationException, "Owner retirement rethrows cancellation instead of partial success")
    verify(requests.size==1, "Retirement after like response sends no subsequent coin/favorite")
    owned=true;requests.clear();retireOn="/x/v3/fav/folder/created/list-all"
    verify(runCatching { protocol.favoriteVideo(901,true) }.exceptionOrNull() is CancellationException, "Default-folder late response rejected")
    verify(requests.size==1 && requests.single().method=="GET", "Retired folder response cannot cause favorite POST")
    retireOn=null;owned=true

    val context = DesktopPluginContext(DesktopPluginStore(Path.of(args[1])))
    val scope = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    val actions = ControlledActions()
    var balance = CompletableDeferred<Double>()
    val environment = DesktopOriginalVideoEngagementEnvironment(context,scope,actions,VideoCoinBalanceLoader { balance.await() }, {owned}, { block -> if(owned) {block();true} else false })
    val vm = VideoEngagementViewModel(environment)
    var callbackCount=0
    try {
        vm.bindSubject(subject(1),VideoEngagementSeed(isLoggedIn=true,likeCount=10,isDisliked=true))
        val pending = CompletableDeferred<Result<Boolean>>()
        actions.like={pending.await()};vm.toggleLike(onResult={callbackCount++})
        vm.bindSubject(subject(2),VideoEngagementSeed(isLoggedIn=true,likeCount=20))
        pending.complete(Result.success(true));yield()
        verify(vm.uiState.value.subject?.generation==2L && vm.uiState.value.likeCount==20 && !vm.uiState.value.isLiked, "Late original like receipt cannot mutate replacement subject")
        verify(callbackCount==0, "Late like callback suppressed")
        actions.like={Result.success(true)}
        vm.toggleLike(onResult={callbackCount++});yield()
        verify(vm.uiState.value.isLiked && vm.uiState.value.likeCount==21 && callbackCount==1, "Current like receipt increments once and invokes callback")
        vm.sync(VideoEngagementSeed(isLoggedIn=true,likeCount=0,isLiked=false))
        verify(vm.uiState.value.isLiked && vm.uiState.value.likeCount==21, "Original locally modified fields survive refresh sync")
        vm.bindSubject(subject(3),VideoEngagementSeed(isLoggedIn=true,likeCount=12,isDisliked=true))
        vm.toggleLike();yield()
        verify(vm.uiState.value.isLiked && !vm.uiState.value.isDisliked && vm.uiState.value.likeCount==13, "Original mutual exclusivity preserved")
        vm.bindSubject(subject(4),VideoEngagementSeed(isLoggedIn=true,likeCount=4))
        verify(withTimeoutOrNull(40) { vm.events.first() }==null, "Queued old-subject messages discarded in original channel")
        val failure = CompletableDeferred<Result<Boolean>>()
        actions.like={failure.await()};vm.toggleLike()
        vm.bindSubject(subject(5),VideoEngagementSeed(isLoggedIn=true,likeCount=5));failure.complete(Result.failure(Exception("old failure")));yield()
        verify(withTimeoutOrNull(40) { vm.events.first() }==null && vm.uiState.value.likeCount==5, "Old-subject mutation failure has no new feedback/state")
        vm.openCoinDialog();vm.bindSubject(subject(6),VideoEngagementSeed(isLoggedIn=true));balance.complete(42.0);yield()
        verify(!vm.uiState.value.coinDialogVisible && vm.uiState.value.userCoinBalance==null, "Old coin-balance response cannot open replacement dialog")
        actions.triple={Result.success(TripleActionResult(true,false,"已投满2个硬币",true))}
        vm.doTripleAction();yield()
        verify(vm.uiState.value.isLiked && vm.uiState.value.isFavorited && vm.uiState.value.coinCount==2 && !vm.uiState.value.tripleCelebrationVisible, "Original already-coined partial triple visual policy")
        vm.bindSubject(subject(7),VideoEngagementSeed(isLoggedIn=true,likeCount=7))
        val retirement=CompletableDeferred<Result<Boolean>>();actions.like={retirement.await()};vm.toggleLike(onResult={callbackCount++});owned=false;retirement.complete(Result.success(true));yield()
        verify(vm.uiState.value.likeCount==7 && !vm.uiState.value.isLiked && callbackCount==1, "Retired account/entry rejects callback and state")
    } finally { scope.cancel();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll() }
    val identities = listOf(DesktopOriginalVideoEngagementProtocol::class.java,VideoEngagementViewModel::class.java,VideoSubjectSnapshot::class.java,VideoEngagementSeed::class.java,DesktopOriginalFavoriteFolderProtocol::class.java,BilibiliApi::class.java,DesktopPluginStore::class.java).map(::identity)
    Files.writeString(Path.of(args[0]), buildJsonObject {
        put("status","PASS");put("groupedCases",3);put("assertions",assertions)
        put("origins",JsonArray(identities));put("noSocket",true);put("noActualAccountRead",true)
        put("syntheticCredentials","explicit fixture only");put("actualRootOrNativeSurfaceMounted",false)
    }.toString())
    println("Video engagement protocol/subject proof PASS: $assertions assertions")
}
