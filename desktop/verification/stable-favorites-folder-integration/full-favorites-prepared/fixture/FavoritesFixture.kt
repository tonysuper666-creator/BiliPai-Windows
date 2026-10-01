package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.list.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

private var assertions=0
private fun verify(value:Boolean,message:String) {check(value){message}; assertions++}
private data class Request(val name:String,val fields:List<Any?>)
private inline fun <reified T:Any> proxy(crossinline call:(String,Array<out Any?>)->Any?):T =
    Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)){_,method,args ->
        if(method.declaringClass==Any::class.java) error("Unexpected Object dispatch")
        call(method.name,args?:emptyArray())
    } as T
private fun environment(api:BilibiliApi,alive:()->Boolean={true}):DesktopFavoriteEnvironment {
    fun unexpected(name:String):Nothing=error("Unexpected auxiliary API $name")
    return DesktopFavoriteEnvironment(CoroutineScope(SupervisorJob()+Dispatchers.Default),api,
        proxy<SpaceApi>{name,_->unexpected(name)},proxy<DynamicApi>{name,_->unexpected(name)},
        proxy<BangumiApi>{name,_->unexpected(name)},alive,{"task-fixture-csrf"},{77},{},
        emptyFlow(),{_,_->0L},{false},{},{null},{"android"})
}
private val resource=FavoriteData(id=901,bvid="BV_FIXTURE",title="fixture",ugc=FavoriteUgc(first_cid=902))
private fun response()=FavoriteResourceResponse(data=FavoriteResourceData(medias=listOf(resource)))

private suspend fun exactRequestAndIdentity() {
    val requests=Collections.synchronizedList(mutableListOf<Request>())
    val env=environment(proxy<BilibiliApi>{name,args->
        requests.add(Request(name,args.dropLast(1)))
        check(name=="getFavoriteList");response()
    })
    try {
        val data=env.favorite.getFavoriteList(55,pn=-2,ps=100,keyword=null,order="",type=1,tid=12,platform="").getOrThrow()
        verify(requests.single()==Request("getFavoriteList",listOf(55L,1,20,"","mtime",1,12,"web")),"original request fields/clamps")
        val item=data.medias!!.single().toVideoItem()
        verify(item.aid==901L&&item.bvid=="BV_FIXTURE"&&item.cid==902L,"aid and first CID retained")
        val collection=FavoriteData(id=903,type=21,season_id=904,title="collection",media_count=9).toVideoItem()
        verify(collection.isCollectionResource&&collection.collectionId==904L&&collection.bvid.isBlank(),"type21 original collection model")
        val route=resolveSubscribedFavoriteCollectionRoute(FavFolder(id=904,type=21,title="collection",mid=77,source=FavFolderSource.SUBSCRIBED))!!
        verify(route.type=="favorite_season"&&route.id==904L&&route.mid==77L,"original subscribed route")
    } finally {env.scope.cancel()}
}
private suspend fun originalVmCatalogSearchOrder() {
    val requests=Collections.synchronizedList(mutableListOf<Request>())
    val env=environment(proxy<BilibiliApi>{name,args->
        requests.add(Request(name,args.dropLast(1)))
        when(name) {
            "getNavInfo"->NavResponse(data=NavData(isLogin=true,mid=77))
            "getFavFolders"->FavFolderResponse(data=FavFolderList(count=2,list=listOf(
                FavFolder(id=55,title="first",cover="fixture-cover",media_count=1),
                FavFolder(id=56,title="second",cover="fixture-cover",media_count=1))))
            "getCollectedFavFolders"->FavFolderResponse(data=FavFolderList(count=1,list=listOf(FavFolder(id=904,title="subscribed",type=21,mid=77))))
            "getFavoriteList"->response()
            else->error("Unexpected API $name")
        }
    })
    try {
        val vm=FavoriteViewModel(env);vm.loadData()
        withTimeout(5000){vm.getFolderUiState(0).first{it.items.isNotEmpty()};vm.subscribedFolders.first{it.isNotEmpty()}}
        verify(vm.folders.value.size==2&&vm.folders.value.all{it.source==FavFolderSource.OWNED},"owned original catalog")
        verify(vm.subscribedFolders.value.single().source==FavFolderSource.SUBSCRIBED,"subscribed original catalog")
        verify(requests.any{it.name=="getCollectedFavFolders"&&it.fields==listOf(77L,1,20,"web")},"original subscribed page fields")
        vm.switchFolder(1);verify(vm.selectedFolderIndex.value==1,"original selected folder")
        vm.searchVideos("  fixture  ",FavoriteSearchScope.ALL_VIDEO_FOLDERS)
        withTimeout(5000){vm.searchUiState.first{!it.isLoading&&it.items.isNotEmpty()}}
        verify(requests.any{it.name=="getFavoriteList"&&it.fields==listOf(56L,1,20,"fixture","mtime",1,0,"web")},"original all-folder search type")
        vm.changeFavoriteOrder(FavoriteResourceOrder.PLAY_COUNT)
        withTimeout(5000){vm.getFolderUiState(1).first{!it.isLoading&&it.items.isNotEmpty()}}
        verify(requests.any{it.name=="getFavoriteList"&&it.fields[0]==56L&&it.fields[4]=="view"},"original order reload")
        verify(vm.getFolderUiState(1).value.items.single().cid==902L,"raw CID reaches original VM")
    } finally {env.scope.cancel()}
}
private suspend fun retiredTransportReceipt() {
    val pending=CompletableDeferred<Continuation<Any?>>()
    var alive=true
    val env=environment(proxy<BilibiliApi>{name,args->
        check(name=="getFavFolders")
        @Suppress("UNCHECKED_CAST") val continuation=args.last() as Continuation<Any?>
        pending.complete(continuation);COROUTINE_SUSPENDED
    }) {alive}
    try {
        val task=env.scope.async{env.favorite.getFavFolders(77)}
        val continuation=withTimeout(5000){pending.await()}
        alive=false
        continuation.resume(FavFolderResponse(data=FavFolderList(list=listOf(FavFolder(id=55,title="late")))))
        val failure=runCatching{withTimeout(5000){task.await()}}.exceptionOrNull()
        verify(failure is CancellationException,"retired response must cancel, not publish success/failure UI")
        verify(runCatching{env.csrf()}.exceptionOrNull() is CancellationException,"retired identity rejected")
    } finally {env.scope.cancel()}
}
private suspend fun originalQuickDefaultFolder() {
    val requests=Collections.synchronizedList(mutableListOf<Request>())
    val api=proxy<BilibiliApi>{name,args->
        requests.add(Request(name,args.dropLast(1)))
        when(name) {
            "getFavFolders"->FavFolderResponse(data=FavFolderList(list=listOf(FavFolder(id=55,title="default"),FavFolder(id=56,title="other"))))
            "dealFavorite"->SimpleApiResponse()
            else->error("Unexpected API $name")
        }
    }
    val env=DesktopFavoriteEnvironment.forFolderDrawer(CoroutineScope(SupervisorJob()+Dispatchers.Default),
        api,{true},{"task-fixture-csrf"},{77},{})
    try {
        verify(env.actions.favoriteVideo(901,true).getOrThrow(),"original quick save success value")
        verify(requests==listOf(Request("getFavFolders",listOf(77L,null,null,"333.1387")),
            Request("dealFavorite",listOf(901L,2,"55","","task-fixture-csrf"))),"original default-first-folder then one mutation")
        requests.clear()
        verify(!env.actions.favoriteVideo(901,false,folderId=56).getOrThrow(),"original cancellation success value")
        verify(requests.single()==Request("dealFavorite",listOf(901L,2,"","56","task-fixture-csrf")),"explicit folder removal exact delta")
    } finally {env.scope.cancel()}
}
private suspend fun actualGlobalPreferenceProjection(root:Path) {
    val store=DesktopPluginStore(root)
    store.update("settings",mapOf("unrelated_fixture_key" to JsonPrimitive("keep"),"header_blur_enabled" to JsonPrimitive(true),
        "home_video_duration_badges_visible" to JsonPrimitive(false)))
    val prefs=DesktopFavoritePreferences(store)
    verify(prefs.store===store,"same Root global store")
    val defaults=prefs.initialHomeSettings()
    verify(defaults.cardTransitionEnabled&&!defaults.cardAnimationEnabled&&defaults.pinchToChangeGridColumnsEnabled,"original selected appearance defaults")
    verify(defaults.homeDurationStyle==com.android.purebilibili.core.store.HomeDurationStyle.HIDDEN,"original legacy duration migration")
    prefs.setBackToTopOffset(18f,-20f)
    val reread=DesktopFavoritePreferences(store)
    verify(reread.initialBackToTopOffset()==(18f to -20f),"actual disk-backed original offset keys")
    verify(store.preferences("settings")["unrelated_fixture_key"]==JsonPrimitive("keep"),"unrelated key retained")
    verify(Files.readString(root.resolve("plugin-settings.json")).contains("back_to_top_button_offset_x_dp"),"actual persisted file")
    val quick=DesktopFavoriteInteractionPreferences(store)
    verify(!quick.getQuickSaveDefaultFolder().first(),"original real quick default false")
    quick.setQuickSaveDefaultFolder(true)
    verify(DesktopFavoriteInteractionPreferences(store).getQuickSaveDefaultFolder().first(),"actual global quick preference true")
    verify(store.preferences("settings")["unrelated_fixture_key"]==JsonPrimitive("keep"),"quick update retains other keys")
}
fun main(args:Array<String>)=runBlocking {
    val root=Path.of(args.single())
    exactRequestAndIdentity();originalVmCatalogSearchOrder();retiredTransportReceipt();originalQuickDefaultFolder();actualGlobalPreferenceProjection(root)
    println("Favorites prepared 5 groups / $assertions assertions PASS; socket=false; MainIntegration=false")
    listOf(BilibiliApi::class.java,FavFolder::class.java,DesktopFavoriteEnvironment::class.java,FavoriteViewModel::class.java,
        DesktopFavoritePreferences::class.java,DesktopPluginStore::class.java).forEach {
        println("CodeSource ${it.name}: ${it.protectionDomain.codeSource.location}")
    }
}
