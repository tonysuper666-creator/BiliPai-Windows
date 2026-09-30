@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.store.DesktopDynamicSettings.DynamicFeedLayoutMode
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.DynamicUserContentFilter
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.security.MessageDigest

private fun dynamic(id:String,type:String="DYNAMIC_TYPE_WORD",mid:Long=77)=Json.decodeFromString<DynamicItem>(
    """{"id_str":"$id","type":"$type","modules":{"module_author":{"mid":$mid,"name":"Fixture","face":"","pub_ts":1}}}""")
private class PaginationScene(val scene:ImageComposeScene){
    var nanos=0L;var scrolls=0;var pointerPairs=0
    fun nodes():List<SemanticsNode>{fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
        return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}}
    fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    suspend fun frame(){nanos+=30_000_000;scene.render(nanos).close();delay(5)}
    suspend fun await(name:String,ready:()->Boolean){try{withTimeout(5000){while(!ready())frame()};repeat(10){frame()}}
        catch(failure:TimeoutCancellationException){error("$name; labels=${labels()}")}}
    suspend fun scroll(){val action=nodes().firstNotNullOfOrNull{it.config.getOrNull(SemanticsActions.ScrollBy)?.action}?:error("Actual grid has no scroll action")
        check(action.invoke(0f,5000f));scrolls++}
    suspend fun click(label:String){val node=nodes().last{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true}
        check(node.boundsInRoot.center.y in 1f..618f);val position=node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press,position,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,position,timeMillis=nanos/1_000_000+55,buttons=PointerButtons());pointerPairs++}
    fun screenshot(path:Path){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output)
    val manifest=Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonObject
    val product=Path.of(manifest["artifacts"]!!.jsonArray.first().jsonObject["path"]!!.jsonPrimitive.content)
    for(value in manifest["actualProductClassPins"]!!.jsonArray){val row=value.jsonObject;val name=row["className"]!!.jsonPrimitive.content;val type=Class.forName(name)
        check(Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()==product.toRealPath())
        val bytes=type.getResourceAsStream("/"+name.replace('.','/')+".class")!!.use{it.readAllBytes()}
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}==row["sha256Bytes"]!!.jsonPrimitive.content)}
    val checks=mutableListOf<JsonObject>()
    for(style in AppUiStyle.entries)for(dark in listOf(false,true)){
        val name="${style.name.lowercase()}-${if(dark)"dark-list"else"light-waterfall"}"
        val store=DesktopPluginStore(output.resolve(name+"-settings"));val context=DesktopPluginContext(store)
        val theme=DesktopThemePrefs(store);theme.setUiStyle(style);theme.setThemeMode(if(dark)AppThemeMode.DARK else AppThemeMode.LIGHT)
        val prefs=DesktopDynamicTimelinePreferences(context);prefs.setLayoutMode(if(dark)DynamicFeedLayoutMode.LIST else DynamicFeedLayoutMode.WATERFALL)
        val requests=mutableListOf<String>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val state=DesktopDynamicTimelineState("all",{_,offset,_->requests+=offset
            val data=when(requests.size){
                1->DynamicFeedData((0..35).map{dynamic(it.toString())},"tail-1",true)
                2->{entered.complete(Unit);release.await();DynamicFeedData((36..47).map{dynamic(it.toString())},"tail-2",true)}
                3->DynamicFeedData((48..50).map{dynamic(it.toString())},"",false)
                else->error("Unexpected repeated append")}
            DynamicFeedResponse(data=data)})
        val scene=ImageComposeScene(width=if(dark)800 else 1200,height=620,coroutineContext=coroutineContext);val ui=PaginationScene(scene)
        try{
            scene.setContent{val appearance by theme.settings.collectAsState(theme.initialSettings())
                DesktopAppearanceTheme(appearance){AppSurface(Modifier.fillMaxSize()){
                    DesktopDynamicTimelineFeed(state,prefs,{},row={AppText("Row-${it.id_str}",Modifier.height(140.dp).fillMaxWidth())})}}}
            ui.await("initial actual timeline"){state.initialized&&"Row-0" in ui.labels()}
            check(requests==listOf(""));ui.screenshot(output.resolve(name+"-initial.png"))
            ui.scroll();ui.await("actual near-end starts first append"){entered.isCompleted}
            repeat(10){ui.frame()};check(requests==listOf("","tail-1"));check(state.busy)
            release.complete(Unit);ui.await("append published and loading released"){state.page.items.size==48&&!state.busy}
            check(requests.size==2)
            ui.scroll();ui.await("second near-end exhausts original cursor"){state.page.items.size==51&&!state.busy&&!state.page.hasMore}
            repeat(10){ui.frame()};check(requests==listOf("","tail-1","tail-2"))
            ui.screenshot(output.resolve(name+"-exhausted.png"))
            checks+=buildJsonObject{put("case",name);put("passed",true);put("semanticScrollActions",ui.scrolls)
                put("originalOffsets",JsonArray(requests.map(::JsonPrimitive)));put("noDuplicateWhileBusy",true);put("stopsWhenExhausted",true)}
        }finally{release.complete(Unit);scene.close()}
    }
    for(style in AppUiStyle.entries){
        val name="${style.name.lowercase()}-selected-up-empty-article-filter"
        val store=DesktopPluginStore(output.resolve(name+"-settings"));val context=DesktopPluginContext(store)
        val theme=DesktopThemePrefs(store);theme.setUiStyle(style)
        val prefs=DesktopDynamicTimelinePreferences(context);val tabs=DesktopDynamicTabsPreferences(context)
        val requests=mutableListOf<Map<String,String>>()
        val users=DesktopDynamicUsersState(this,tabs,42,{FollowingsData(listOf(FollowingUser(77,"Fixture","")),1)},
            {emptyList()},{null},{params->requests+=params
                DynamicFeedResponse(data=if(requests.size==1)DynamicFeedData(listOf(dynamic("video","DYNAMIC_TYPE_AV")),"up-tail",true)
                    else DynamicFeedData(listOf(dynamic("article")),"",false))},{true})
        users.selectUser(77);users.filter=DynamicUserContentFilter.ARTICLE
        val timelines=mutableMapOf<String,DesktopDynamicTimelineState>()
        fun timeline(type:String)=timelines.getOrPut(type){DesktopDynamicTimelineState(type,{_,_,_->DynamicFeedResponse(data=DynamicFeedData())})}
        val scene=ImageComposeScene(width=800,height=620,coroutineContext=coroutineContext);val ui=PaginationScene(scene)
        try{
            scene.setContent{val appearance by theme.settings.collectAsState(theme.initialSettings())
                DesktopAppearanceTheme(appearance){AppSurface(Modifier.fillMaxSize()){
                    DesktopDynamicTabsHost(users,prefs,{}, {},{it},::timeline,row={AppText("Selected-${it.id_str}",Modifier.height(100.dp))})}}}
            ui.await("original concrete filter empty"){!users.userLoading&&"暂无内容" in ui.labels()&&"加载更多" in ui.labels()}
            repeat(20){ui.frame()};check(requests.size==1);check(users.hasUserMore)
            ui.screenshot(output.resolve(name+"-manual-only.png"));ui.click("加载更多")
            ui.await("actual manual footer loads article cursor"){!users.userLoading&&"Selected-article" in ui.labels()}
            check(requests.size==2);check(requests[1]["host_mid"]=="77");check(requests[1]["offset"]=="up-tail")
            check(!users.hasUserMore);ui.screenshot(output.resolve(name+"-manual-result.png"))
            checks+=buildJsonObject{put("case",name);put("passed",true);put("pointerPairs",ui.pointerPairs)
                put("emptyConcreteFilterStopsAutomaticScan",true);put("manualFallbackUsesOriginalMIDAndOffset",true)}
        }finally{users.close();scene.close()}
    }
    check(checks.size==6)
    val result=buildJsonObject{put("passed",true);put("actualProductClassPins",manifest["actualProductClassPins"]!!.jsonArray.size)
        put("productionOverrides",0);put("actualUiFlows",checks.size);put("screenshots",12);put("checks",JsonArray(checks))
        put("rowContentIsTextFixture",true);put("nativeWindowOrPackageTested",false);put("realAccountOrSocket",false)}
    Files.writeString(output.resolve("result.json"),Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),result)+"\n")
    println(result)
}
