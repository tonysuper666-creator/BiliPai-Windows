package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.store.DanmakuSettingsScope
import com.android.purebilibili.core.store.DanmakuSettings as OriginalSettings
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.video.danmaku.DanmakuCloudSyncStatus
import com.bilipai.desktop.danmaku.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs

private var assertions=0
private fun verify(value:Boolean,label:String){check(value){label};assertions++}
private fun identity(type:Class<*>)=buildJsonObject {
    put("class",type.name);put("path",Path.of(type.protectionDomain.codeSource.location.toURI()).toString())
    put("classSha256Bytes",MessageDigest.getInstance("SHA-256").digest(type.getResourceAsStream("/"+type.name.replace('.','/')+".class")!!.use{it.readBytes()}).joinToString(""){"%02x".format(it)})
}
private class NothingApplier:AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index:Int,instance:Unit){}
    override fun insertTopDown(index:Int,instance:Unit){}
    override fun move(from:Int,to:Int,count:Int){}
    override fun remove(index:Int,count:Int){}
    override fun onClear(){}
}
private class SyncPlatform:DesktopDanmakuSettingsPlatform {
    @Volatile var owned=true
    val requests=CopyOnWriteArrayList<Pair<DanmakuCloudSyncSettings,CompletableDeferred<Result<Unit>>>>()
    override val cloud=object:DesktopDanmakuCloudRuleActions {
        override suspend fun getDanmakuCloudFilterRules():Result<DanmakuCloudFilterRules> = error("not exercised")
        override suspend fun addDanmakuCloudFilterRule(type:Int,filter:String):Result<DanmakuCloudFilterRule> = error("not exercised")
        override suspend fun deleteDanmakuCloudFilterRule(id:Long):Result<Unit> = error("not exercised")
        override suspend fun syncDanmakuCloudConfig(settings:DanmakuCloudSyncSettings):Result<Unit> {
            val pending=CompletableDeferred<Result<Unit>>();requests+=settings to pending;return pending.await()
        }
    }
    override fun isOwned()=owned
    override fun showFeedback(message:String)=error("no feedback expected")
    override fun pickRuleFile(mimeTypes:Array<String>,onSelected:(String?)->Unit)=error("no chooser")
    override suspend fun openRuleInput(fileUri:String):InputStream?=error("no file import")
    override fun elapsedRealtimeMillis()=System.nanoTime()/1_000_000
    override fun onCloudSyncFailure(message:String?)=Unit
}

fun main(args:Array<String>)=runBlocking {
    val root=Path.of(args[0]).resolve("store");Files.createDirectories(root)
    val store=DesktopPluginStore(root);val monitor=Any();var owned=true
    val admission:((()->Unit)->Boolean)={block->synchronized(monitor){if(!owned)false else{block();true}}}
    val blocks=DesktopDanmakuBlockPreferences(store,admission)
    val preferences=DesktopOriginalDanmakuPreferences(store,blocks,admission)
    val windows=DanmakuSettings(opacity=.7f,fontScale=1.2f,blockedKeywords=listOf("legacy-ban"),blockedRules=listOf("uid:abc123"))
    preferences.importLegacyWindowsDanmakuIfAbsent(windows)
    var original=preferences.getDanmakuSettings(DanmakuSettingsScope.PORTRAIT).first()
    verify(original.opacity==.7f&&original.fontScale==1.2f&&original.blockRules==listOf("legacy-ban","uid:abc123"),"existing Windows scalar and full filters migrate into original legacy keys")
    preferences.setDanmakuOpacity(.3f,DanmakuSettingsScope.PORTRAIT)
    preferences.setDanmakuOpacity(.8f,DanmakuSettingsScope.LANDSCAPE)
    preferences.importLegacyWindowsDanmakuIfAbsent(windows.copy(opacity=.2f))
    verify(preferences.currentSettings(DanmakuSettingsScope.PORTRAIT).opacity==.3f&&preferences.currentSettings(DanmakuSettingsScope.LANDSCAPE).opacity==.8f,"existing original scoped values win; migration is absent-only")
    preferences.setDanmakuFontScale(.3f,DanmakuSettingsScope.PORTRAIT)
    preferences.setDanmakuArea(.7f,DanmakuSettingsScope.PORTRAIT)
    preferences.setDanmakuEnabled(false,DanmakuSettingsScope.PORTRAIT)
    verify(preferences.currentSettings(DanmakuSettingsScope.LANDSCAPE).let{it.fontScale==.3f&&it.displayArea==.75f&&!it.enabled},"original enabled/font/area keys are shared LANDSCAPE authority")
    preferences.setDanmakuEnabled(true,DanmakuSettingsScope.LANDSCAPE)
    val longRule="regex:"+"a".repeat(600)
    val rules=(0..320).map{"unique-rule-$it"}+longRule+"uid:abc123"
    preferences.setDanmakuBlockRulesRaw(rules.joinToString("\n"),DanmakuSettingsScope.PORTRAIT)
    original=preferences.currentSettings(DanmakuSettingsScope.PORTRAIT)
    val projected=projectOriginalDanmakuRendererSettings(windows,original)
    verify(original.blockRules.size==323&&projected.blockedRules==original.blockRules,"full original parser rules retain more than 300 entries and 500 characters")
    verify(projected.fontScale==.3f&&projected.displayAreaRatio==.75f&&projected.blockedKeywords.isEmpty(),"projection keeps original scalar values and removes a second keyword authority")
    fun comment(text:String,user:String="")=DanmakuComment(1,0.0,1,25,0xFFFFFF,text,userHash=user)
    verify(!projected.allows(comment("unique-rule-320"))&&!projected.allows(comment("safe","abc123"))&&projected.allows(comment("safe","def456")),"current renderer consumes actual original keyword and UID rules")
    val blockedScheduler=DanmakuScheduler(listOf(comment("unique-rule-320")),projected)
    verify(blockedScheduler.frame(.1,100,100,20){10}.isEmpty(),"actual Main20 Scheduler filtering consumes projected original rules")
    preferences.setDanmakuScrollDurationSeconds(50f,DanmakuSettingsScope.PORTRAIT)
    preferences.setDanmakuStaticDurationSeconds(50f,DanmakuSettingsScope.PORTRAIT)
    preferences.setDanmakuSpeed(3f,DanmakuSettingsScope.PORTRAIT)
    preferences.setDanmakuDuplicateMergeWindowMs(5,DanmakuSettingsScope.PORTRAIT)
    preferences.setDanmakuDuplicateMergeCountThreshold(100,DanmakuSettingsScope.PORTRAIT)
    original=preferences.currentSettings(DanmakuSettingsScope.PORTRAIT)
    val scalar=projectOriginalDanmakuRendererSettings(windows,original)
    verify(scalar.scrollDurationSeconds==50f&&scalar.staticDurationSeconds==50f&&scalar.speedFactor==3f,"original map/set upper bounds survive projection without hidden Windows duration caps")
    verify(scalar.duplicateMergeWindowMs==100&&scalar.duplicateMergeCountThreshold==10,"original duplicate ranges survive projection")
    val scrolling=DanmakuScheduler(listOf(comment("safe","def456")),scalar)
    val position=scrolling.frame(25.0,100,100,20){10}.single()
    verify(abs(position.x-(100.0-110.0*25.0/150.0))<.00001,"current Scheduler frame uses projected scroll50 times speed3")
    val fixed=DanmakuScheduler(listOf(comment("safe").copy(mode=5)),scalar)
    verify(fixed.frame(49.0,100,100,20){10}.size==1&&fixed.frame(50.0,100,100,20){10}.isEmpty(),"current Scheduler static50 survives old hidden20 cap")
    preferences.setDanmakuScrollDurationSeconds(1f,DanmakuSettingsScope.PORTRAIT)
    verify(projectOriginalDanmakuRendererSettings(windows,preferences.currentSettings(DanmakuSettingsScope.PORTRAIT)).scrollDurationSeconds==1f,"original lower duration1 survives projection")
    verify(DesktopDanmakuPresentation.INLINE.originalScope()==DanmakuSettingsScope.PORTRAIT&&DesktopDanmakuPresentation.FULLSCREEN_LANDSCAPE.originalScope()==DanmakuSettingsScope.LANDSCAPE&&DesktopDanmakuPresentation.FULLSCREEN_PORTRAIT.originalScope()==DanmakuSettingsScope.PORTRAIT,"required actual presentation maps the original scope function")
    val other=DesktopOriginalDanmakuPreferences(DesktopPluginStore(root),blocks,admission)
    verify(other.currentSettings(DanmakuSettingsScope.PORTRAIT)==preferences.currentSettings(DanmakuSettingsScope.PORTRAIT),"same canonical Store backing is global and not MID-bound")
    owned=false
    verify(runCatching{preferences.setDanmakuOpacity(.9f,DanmakuSettingsScope.PORTRAIT)}.exceptionOrNull() is CancellationException&&other.currentSettings(DanmakuSettingsScope.PORTRAIT).opacity==.3f,"retired write is denied without late store mutation")

    val requests=CopyOnWriteArrayList<Request>();var code=0;var csrf:String?="fixture-only-csrf";var apiOwned=true;var retireAfterResponse=false
    val client=OkHttpClient.Builder().addInterceptor{chain->
        val request=chain.request();requests+=request
        val body=when(request.url.encodedPath) {
            "/x/dm/filter/user"->"""{"code":0,"data":{"rule":[{"id":1,"type":0,"filter":"原词"}],"rule1":[{"id":2,"type":1,"filter":"a.*"}],"rule2":[{"id":3,"type":2,"filter":"abc123"}],"toast":"原提示"}}"""
            "/x/dm/filter/user/add"->"""{"code":0,"data":{"id":4,"type":1,"filter":"原regex"}}"""
            else->"""{"code":$code,"message":""}"""
        }
        if(retireAfterResponse)apiOwned=false
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val api=Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client).addConverterFactory(Json{ignoreUnknownKeys=true}.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
    val protocol=DesktopOriginalDanmakuCloudRuleProtocol(api,{csrf},{if(!apiOwned)throw CancellationException("retired")})
    try {
        val rulesResult=protocol.getDanmakuCloudFilterRules().getOrThrow()
        verify(rulesResult.rules.map{it.id}==listOf(1L,2L,3L)&&rulesResult.toast=="原提示","actual existing Retrofit response folds rule/rule1/rule2 without a second catalog")
        verify(protocol.addDanmakuCloudFilterRule(1,"原regex").getOrThrow().id==4L,"original add response mapping")
        fun fields()=(requests.last().body as FormBody).let{form->(0 until form.size).associate{form.name(it) to form.value(it)}}
        verify(fields()==mapOf("type" to "1","filter" to "原regex","csrf" to csrf),"original add raw type/filter/CSRF")
        protocol.deleteDanmakuCloudFilterRule(4).getOrThrow()
        verify(fields()==mapOf("ids" to "4","csrf" to csrf),"original delete ids/CSRF")
        val cloudSettings=DanmakuCloudSyncSettings(true,false,true,false,true,false,.4f,.7f,3f,.3f)
        code=23004;verify(protocol.syncDanmakuCloudConfig(cloudSettings).isSuccess,"original code23004 is successful configuration sync")
        val config=fields()
        verify(config==mapOf("dm_switch" to "true","blockscroll" to "false","blocktop" to "true","blockbottom" to "false","blockcolor" to "true","blockspecial" to "false","opacity" to "0.4","dmarea" to "75","speedplus" to "1.6","fontsize" to "0.51","csrf" to csrf),"actual original config payload flags, area rounding, speed and cloud fontsize mapping")
        code=-400;verify(protocol.syncDanmakuCloudConfig(cloudSettings).exceptionOrNull()?.message=="弹幕云同步参数错误","original configuration error message")
        val before=requests.size;csrf=null
        verify(protocol.syncDanmakuCloudConfig(cloudSettings).exceptionOrNull()?.message=="请先登录"&&requests.size==before,"guest performs no mutation request")
        csrf="fixture-only-csrf";retireAfterResponse=true
        verify(runCatching{protocol.getDanmakuCloudFilterRules()}.exceptionOrNull() is CancellationException,"owner retired during response cannot return late success")
    } finally {client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}

    val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val clock=BroadcastFrameClock();val uiScope=CoroutineScope(SupervisorJob()+dispatcher+clock)
    val recomposer=Recomposer(uiScope.coroutineContext)
    val recomposing=uiScope.launch{recomposer.runRecomposeAndApplyChanges()}
    val ticking=uiScope.launch{while(isActive){androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications();clock.sendFrame(System.nanoTime());delay(10)}}
    val composition=Composition(NothingApplier(),recomposer);val platform=SyncPlatform()
    var binding:DesktopDanmakuCloudSyncBinding?=null
    val syncEnabled=mutableStateOf(true)
    suspend fun settled(){delay(50)}
    suspend fun getBinding()=withContext(dispatcher){checkNotNull(binding)}
    try {
        withContext(dispatcher){composition.setContent { binding=rememberDesktopOriginalDanmakuCloudSyncBinding(OriginalSettings(),syncEnabled.value,true,platform) }}
        settled()
        withContext(dispatcher){getBinding().queueChange{it.copy(opacity=.2f)}}
        settled();verify(getBinding().uiState.status==DanmakuCloudSyncStatus.PENDING,"original queue sets pending UI status")
        delay(450);verify(platform.requests.isEmpty(),"automatic queue retains original700ms debounce")
        withContext(dispatcher){getBinding().queueChange{it.copy(opacity=.6f)}}
        delay(350);verify(platform.requests.isEmpty(),"replacement resets automatic debounce")
        withTimeout(1200){while(platform.requests.size<1)delay(10)}
        withTimeout(500){while(getBinding().uiState.status!=DanmakuCloudSyncStatus.SYNCING)delay(10)}
        verify(platform.requests.single().first.opacity==.6f&&getBinding().uiState.status==DanmakuCloudSyncStatus.SYNCING,"only latest exact config is dispatched")
        platform.requests[0].second.complete(Result.success(Unit));settled()
        verify(getBinding().uiState.status==DanmakuCloudSyncStatus.SUCCESS&&getBinding().uiState.lastSuccessAtMillis!=null,"original successful sync state and timestamp")
        withContext(dispatcher){getBinding().requestNow()}
        withTimeout(300){while(platform.requests.size<2)delay(10)}
        verify(platform.requests.size==2,"manual original version bypasses700ms delay")
        platform.requests[1].second.complete(Result.failure(Exception("原失败")));settled()
        verify(getBinding().uiState.status==DanmakuCloudSyncStatus.FAILURE&&getBinding().uiState.message=="原失败","original failure reducer is consumed")
        withContext(dispatcher){getBinding().onEnabledChange(false);syncEnabled.value=false};settled()
        verify(getBinding().uiState.status==DanmakuCloudSyncStatus.IDLE,"disable clears pending and original UI state")
        withContext(dispatcher){syncEnabled.value=true};settled()
        withContext(dispatcher){getBinding().requestNow()}
        withTimeout(300){while(platform.requests.size<3)delay(10)}
        platform.owned=false;platform.requests[2].second.complete(Result.success(Unit));settled()
        verify(getBinding().uiState.status==DanmakuCloudSyncStatus.SYNCING,"retired owner prevents late success receipt mutation")
        withContext(dispatcher){getBinding().requestNow();getBinding().queueChange{it.copy(opacity=.9f)}};delay(750)
        verify(platform.requests.size==3,"retired callbacks cannot enqueue new work")
    } finally {
        withContext(dispatcher){composition.dispose()};recomposer.cancel();recomposing.cancelAndJoin();ticking.cancelAndJoin();uiScope.cancel();dispatcher.close()
    }
    val classes=listOf(DesktopOriginalDanmakuPreferences::class.java,DesktopDanmakuBlockPreferences::class.java,
        DesktopPluginStore::class.java,DanmakuSettings::class.java,DanmakuScheduler::class.java,
        BilibiliApi::class.java,DesktopOriginalDanmakuCloudRuleProtocol::class.java,
        DanmakuCloudSyncSettings::class.java,DanmakuCloudSyncStatus::class.java,
        Class.forName("com.bilipai.desktop.ui.DesktopOriginalDanmakuCloudSyncBindingKt"),
        Class.forName("com.android.purebilibili.feature.video.ui.components.DanmakuSettingsPanelKt"))
    val result=buildJsonObject {
        put("status","PASS");put("groupedCases",3);put("assertions",assertions)
        put("actualCodeSources",JsonArray(classes.map(::identity)))
        put("actualOriginalPanelMounted",false);put("actualRootProjectionInstalled",false)
        put("actualMain20SchedulerUsed",true);put("androidEngineTimingParityClaim",false)
        put("noSocket",true);put("noExternalHTTP",true);put("noHWND",true);put("noAccountRead",true)
    }
    Files.writeString(Path.of(args[0]).resolve("result.json"),Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),result))
    println("SETTINGS_PROOF_PASS groups=3 assertions=$assertions")
}
