@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.weeklyfixture

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopWeeklySeriesProtocol
import com.android.purebilibili.feature.home.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.ui.*
import java.lang.reflect.Proxy
import java.nio.file.*
import java.util.ArrayDeque
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat

private val periods = listOf(PopularSeriesPeriod(133,name="Latest 133"),PopularSeriesPeriod(42,name="History 42"))
private fun data(number: Int)=PopularSeriesOneData(
    config=PopularSeriesConfig(number=number,label="Label $number",subject="Subject $number"),reminder="Reminder $number",
    list=listOf(PopularItem(bvid="BVweekly$number",title="Weekly fixture $number"),PopularItem(bvid="",title="Invalid blank BV")))

private class FakeApi {
    var listResponse=PopularSeriesListResponse(data=PopularSeriesListData(periods))
    var response:(Int)->PopularSeriesOneResponse={ PopularSeriesOneResponse(data=data(it)) }
    val numbers=mutableListOf<Int>()
    var listCalls=0
    var cancel=false
    val api=Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,arrayOf(BilibiliApi::class.java)) { _,method,args ->
        if(cancel) throw CancellationException("test-only cancellation")
        when(method.name) {
            "getWeeklySeriesList" -> { listCalls++;listResponse }
            "getWeeklySeriesVideos" -> { val number=args!![0] as Int;numbers+=number;response(number) }
            else -> error("Unexpected API ${method.name}")
        }
    } as BilibiliApi
}

private suspend fun protocolProof(): List<String> {
    val proof=mutableListOf<String>()
    fun gate(s:String,v:Boolean){check(v){s};proof+=s}
    val fake=FakeApi();val protocol=DesktopWeeklySeriesProtocol(fake.api)
    fake.listResponse=PopularSeriesListResponse(data=PopularSeriesListData(listOf(
        PopularSeriesPeriod(99,name="first99"),PopularSeriesPeriod(133),PopularSeriesPeriod(99,name="duplicate99"),PopularSeriesPeriod(0))))
    gate("original periods filters invalids, keeps first duplicate and sorts newest first",
        protocol.getWeeklyPeriods().getOrThrow().map{it.number to it.name}==listOf(133 to "",99 to "first99"))
    gate("explicit historical request remains exact and retains config label/subject/reminder",
        protocol.getWeeklyPeriod(42).getOrThrow()==data(42) && fake.numbers==listOf(42))
    val methods=BilibiliApi::class.java.methods
    gate("original read endpoints and number query are unchanged",
        methods.single{it.name=="getWeeklySeriesList"}.getAnnotation(retrofit2.http.GET::class.java).value=="x/web-interface/popular/series/list" &&
        methods.single{it.name=="getWeeklySeriesVideos"}.getAnnotation(retrofit2.http.GET::class.java).value=="x/web-interface/popular/series/one" &&
        methods.single{it.name=="getWeeklySeriesVideos"}.parameterAnnotations[0].filterIsInstance<retrofit2.http.Query>().single().value=="number")
    fake.listResponse=PopularSeriesListResponse(code=412)
    fake.response={ PopularSeriesOneResponse(code=0,data=null) }
    gate("original error code and null-body Result failure text retained",
        protocol.getWeeklyPeriods().exceptionOrNull()?.message=="每周必看期数加载失败(412)" &&
        protocol.getWeeklyPeriod(42).exceptionOrNull()?.message=="第42期加载失败(0)")
    fake.cancel=true
    var cancelled=false
    try{protocol.getWeeklyPeriod(42)}catch(expected:CancellationException){cancelled=true}
    gate("read cancellation escapes instead of becoming a UI failure",cancelled)
    return proof
}

private class QueueDispatcher:CoroutineDispatcher() {
    val tasks=ArrayDeque<Runnable>()
    override fun dispatch(context:CoroutineContext,block:Runnable){tasks.addLast(block)}
    fun drain(){var limit=200;while(tasks.isNotEmpty()){check(limit-->0);tasks.removeFirst().run()}}
}
private class Requests:DesktopWeeklySeriesRequests {
    var loaded:Result<List<PopularSeriesPeriod>> = Result.success(periods)
    var result: suspend (Int)->Result<PopularSeriesOneData> = { Result.success(data(it)) }
    var listCalls=0
    val calls=mutableListOf<Int>()
    override suspend fun getWeeklyPeriods():Result<List<PopularSeriesPeriod>> {listCalls++;return loaded}
    override suspend fun getWeeklyPeriod(number:Int):Result<PopularSeriesOneData>{calls+=number;return result(number)}
}
private fun stateProof():List<String> {
    val proof=mutableListOf<String>();fun gate(s:String,v:Boolean){check(v){s};proof+=s}
    gate("original initial resolver never invents period 1 and preserves arbitrary historical period",
        resolveWeeklyInitialNumber(null,emptyList())==null && resolveWeeklyInitialNumber(-1,periods)==133 &&
        resolveWeeklyInitialNumber(42,emptyList())==42)
    val queue=QueueDispatcher();val parent=SupervisorJob();val scope=CoroutineScope(parent+queue)
    val requests=Requests();val saved=DesktopWeeklySeriesSavedState().apply{this["weeklyNumber"]=42}
    var epoch=1L;val ownerEpoch=epoch
    val vm=WeeklySeriesViewModel(saved,requests,scope){epoch==ownerEpoch}
    vm.initialize(133);vm.initialize(99);queue.drain()
    gate("saved weeklyNumber overrides route initial only once and original raw item projection filters blank BV",
        requests.calls==listOf(42) && vm.state.value.number==42 && vm.state.value.videos.map{it.bvid}==listOf("BVweekly42") &&
        vm.state.value.label=="Label 42" && vm.state.value.subject=="Subject 42" && vm.state.value.reminder=="Reminder 42")
    requests.result={Result.failure(Exception("original fixture failure"))}
    vm.select(133);queue.drain();vm.retry();queue.drain()
    gate("failed selected period and retry remain same number without latest fallback or periods refetch",
        requests.calls==listOf(42,133,133) && requests.listCalls==1 && vm.state.value.number==133 &&
        vm.state.value.error=="original fixture failure" && vm.state.value.videos.isEmpty() && vm.state.value.label.isBlank())
    vm.select(0);vm.select(133);queue.drain()
    gate("invalid and already selected numbers do not create another read",requests.calls.size==3)
    var late:Continuation<Result<PopularSeriesOneData>>?=null
    requests.result={number->if(number==42)suspendCoroutine{late=it}else Result.success(data(number))}
    vm.select(42);queue.drain();vm.select(99);queue.drain();late!!.resume(Result.success(data(42)));queue.drain()
    gate("cancelled old period cannot publish over replacement even with a noncooperative fake callback",
        vm.state.value.number==99 && vm.state.value.videos.single().bvid=="BVweekly99")
    late=null;vm.select(42);queue.drain();epoch=2;late!!.resume(Result.success(data(42)));queue.drain()
    gate("same MID credential epoch change rejects late original Result without publishing old rows",
        vm.state.value.loading && vm.state.value.videos.isEmpty() && vm.state.value.label.isBlank())
    val calls=requests.calls.size;vm.close();vm.retry();vm.select(70);queue.drain()
    gate("closed route cannot restart an old loader",requests.calls.size==calls)
    val empty=Requests().apply{loaded=Result.failure(Exception("period list failed"))}
    val other=WeeklySeriesViewModel(DesktopWeeklySeriesSavedState(),empty,scope)
    other.initialize(null);queue.drain()
    gate("period-list failure with no requested history produces original error, never fake period request",
        other.state.value.error=="period list failed" && !other.state.value.loading && empty.calls.isEmpty())
    val history=WeeklySeriesViewModel(DesktopWeeklySeriesSavedState(),empty,scope)
    history.initialize(42);queue.drain()
    gate("explicit history can load despite failed period list and preserves separate period picker error",
        history.state.value.number==42 && history.state.value.videos.single().bvid=="BVweekly42" && history.state.value.periodsError=="period list failed")
    other.close();history.close();parent.cancel();return proof
}

private fun warm(repository:DesktopRepository,sessions:DesktopSessionStore) {
    DesktopRepository::class.java.getDeclaredField("visitorInitialized").apply{isAccessible=true}.setBoolean(repository,true)
    DesktopRepository::class.java.getDeclaredField("visitorGeneration").apply{isAccessible=true}.setLong(repository,sessions.generation)
}

private suspend fun uiProof(output:Path):List<String> = coroutineScope {
    val proof=mutableListOf<String>()
    for(style in AppUiStyle.entries){
        val temporary=Files.createTempDirectory("bilipai-weekly-task-")
        val store=DesktopPluginStore(temporary);val context=DesktopPluginContext(store)
        val sessions=DesktopSessionStore(temporary.resolve("fake-session.json"),persistent=false)
        val repo=DesktopRepository(sessions);warm(repo,sessions)
        val prefs=DesktopDiscoveryPreferences(temporary,DesktopBlockedUpStore(context))
        val discovery=DesktopDiscoveryRepository(repo,prefs);val fake=FakeApi()
        DesktopDiscoveryRepository::class.java.getDeclaredField("api").apply{isAccessible=true}.set(discovery,fake.api)
        val requests=discovery.weeklySeriesRequests()
        var back=0;var clicked:Pair<VideoItem,List<VideoItem>>?=null;var pairs=0
        val scene=ImageComposeScene(width=720,height=680,coroutineContext=coroutineContext)
        var time=0L
        try {
            scene.setContent { DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false),systemLanguageTags=listOf("en-US")) {
                CompositionLocalProvider(LocalDesktopHomeCardPreferences provides DesktopHomeCardPreferences(context),
                    LocalDesktopBrowseMemory provides remember { DesktopBrowseMemory() }) {
                    DesktopWeeklySeriesScreen(requests,repo,onBack={back++},onVideoClick={v,list->clicked=v to list},modifier=Modifier.fillMaxSize())
                }
            } }
            suspend fun settle(){repeat(24){time+=30_000_000L;scene.render(time).close();yield();delay(2)}}
            fun nodes():List<SemanticsNode>{fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
                return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}}
            fun texts()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
            fun gate(s:String,v:Boolean){check(v){"$style: $s; actual=${texts()}"};proof+="$style: $s"}
            suspend fun click(label:String,description:Boolean=false){
                val candidates=nodes().filter{if(description)it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true
                    else it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true}
                check(candidates.isNotEmpty()){ "$style missing $label: ${texts()}" }
                val p=candidates.last().boundsInWindow.center
                check(p.x in 0f..720f && p.y in 0f..680f){"Outside actual scene $label $p"}
                scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=time/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
                scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=time/1_000_000+45,buttons=PointerButtons());pairs++;settle()
            }
            settle()
            gate("original scaffold, raw label/subject/reminder and single latest request are visible",
                texts().containsAll(listOf("每周必看","第133期 · 选期","Label 133\nSubject 133","Reminder 133","Weekly fixture 133")) &&
                fake.listCalls==1 && fake.numbers==listOf(133))
            click("第133期 · 选期")
            gate("original AppAlertDialog period rows opened",texts().containsAll(listOf("选择期数","Latest 133","History 42")))
            scene.render(time+1).use{Files.write(output.resolve("${style.name.lowercase()}-original-periods.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
            click("History 42")
            gate("historical pointer actually requests 42 and changes exact card metadata without refetching list",
                fake.numbers==listOf(133,42) && fake.listCalls==1 && texts().containsAll(listOf("第42期 · 选期","Label 42\nSubject 42","Reminder 42","Weekly fixture 42")))
            click("Weekly fixture 42")
            gate("original ElegantVideoCard pointer passes original selected VideoItem and current exact queue",
                clicked?.first?.bvid=="BVweekly42" && clicked?.second?.map{it.bvid}==listOf("BVweekly42"))
            scene.render(time+1).use{Files.write(output.resolve("${style.name.lowercase()}-original-history.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
            click("返回",true)
            gate("original back icon calls injected Root navigation with four genuine pointer pairs",back==1 && pairs==4)
        } finally {scene.close()}
    }
    proof
}

fun main(args:Array<String>):Unit = runBlocking {
    val output=Path.of(args[0]);val protocols=protocolProof();val states=stateProof();val ui=uiProof(output)
    val source=buildJsonObject {
        put("passed",true);put("preparedOnly",true);put("candidateClasses11Base",true);put("candidateRuntimeAcceptance",false)
        put("sockets",false);put("realAccount",false);put("actualHWND",false);put("ShellRoutingMounted",false)
        put("protocolChecks",JsonArray(protocols.map(::JsonPrimitive)));put("stateChecks",JsonArray(states.map(::JsonPrimitive)))
        put("uiChecks",JsonArray(ui.map(::JsonPrimitive)))
        put("classSources",buildJsonObject {
            for(c in listOf(WeeklySeriesViewModel::class.java,DesktopWeeklySeriesProtocol::class.java,DesktopDiscoveryRepository::class.java,
                DesktopWeeklySeriesRequests::class.java,DesktopAppearanceThemeClass())) put(c.name,c.protectionDomain.codeSource.location.toString())
        })
    }
    Files.writeString(output.resolve("proof.json"),source.toString());println(source)
}
private fun DesktopAppearanceThemeClass():Class<*> = Class.forName("com.bilipai.desktop.appearance.DesktopAppearanceThemeKt")
