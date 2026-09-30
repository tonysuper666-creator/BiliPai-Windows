package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.DesktopDynamicTabsPreferences
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicLong

private suspend fun eventually(ready:()->Boolean)=withTimeout(5000){while(!ready())delay(5)}
private fun preferences(root:Path)=DesktopDynamicTabsPreferences(DesktopPluginContext(DesktopPluginStore(root)))
private fun following(page:Int)=FollowingsData(
    ((page-1)*50+1..minOf(page*50,51)).map{FollowingUser(it.toLong(),"Fixture-$it","")},51)

fun main(args:Array<String>)=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output)
    val manifest=Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonObject
    val product=Path.of(manifest["artifacts"]!!.jsonArray.first().jsonObject["path"]!!.jsonPrimitive.content)
    var pinned=0
    for(entry in manifest["actualProductClassPins"]!!.jsonArray){
        val row=entry.jsonObject;val name=row["className"]!!.jsonPrimitive.content
        val type=Class.forName(name)
        check(Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()==product.toRealPath())
        val bytes=type.getResourceAsStream("/"+name.replace('.','/')+".class")!!.use{it.readAllBytes()}
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        check(digest==row["sha256Bytes"]!!.jsonPrimitive.content);pinned++
    }
    check(Modifier.isVolatile(DesktopDynamicUsersState::class.java.getDeclaredField("closed").modifiers))
    val feed=CompletableDeferred<Unit>();val live=CompletableDeferred<Unit>()
    val delayEntered=CompletableDeferred<Long>();val releaseDelay=CompletableDeferred<Unit>();val pages=mutableListOf<Int>()
    val state=DesktopDynamicUsersState(this,preferences(output.resolve("startup-settings")),42,
        followingPage={pages+=it;following(it)},liveRooms={live.await();emptyList()},unreadUsers={null},
        requestPage={DynamicFeedResponse(data=DynamicFeedData())},stillOwned={true},
        nowMs={1000L},startupDelay={delayEntered.complete(it);releaseDelay.await()})
    try{
        state.activateStartupLoads{feed.await()};state.activateStartupLoads{error("duplicate activation")}
        yield();check(!delayEntered.isCompleted);feed.complete(Unit);yield();check(!delayEntered.isCompleted)
        live.complete(Unit);check(delayEntered.await()==1200L);check(pages.isEmpty())
        releaseDelay.complete(Unit);eventually{state.users.size==50};check(pages==listOf(1))
        state.selectTab(4);eventually{state.users.size==51};check(pages==listOf(1,1,2))
        state.selectTab(4);yield();check(pages==listOf(1,1,2))
    }finally{state.close()}
    val epoch=AtomicLong(1);val waiting=CompletableDeferred<Unit>();val late=CompletableDeferred<Unit>();var lateCalls=0
    val retired=DesktopDynamicUsersState(this,preferences(output.resolve("epoch-settings")),42,
        followingPage={lateCalls++;following(it)},liveRooms={emptyList()},unreadUsers={null},
        requestPage={DynamicFeedResponse(data=DynamicFeedData())},stillOwned={epoch.get()==1L},
        startupDelay={waiting.complete(Unit);withContext(NonCancellable){late.await()}})
    retired.activateStartupLoads{};waiting.await();epoch.incrementAndGet();retired.close();late.complete(Unit)
    yield();check(lateCalls==0);check(retired.users.isEmpty())
    val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var requests=0
    val timeline=DesktopDynamicTimelineState("all",{_,_,_->requests++;entered.complete(Unit);release.await()
        DynamicFeedResponse(data=DynamicFeedData())})
    val one=async{timeline.initialize(false)};entered.await();val two=async{timeline.initialize(false)};release.complete(Unit)
    check(one.await());check(!two.await());check(requests==1)
    check(timeline.fetch(true,false));check(requests==2)
    val result=buildJsonObject{
        put("passed",true);put("actualFrozenMainClassPins",pinned);put("productionOverrides",0)
        put("originalStartupBarrierAndOnePageHydration",true);put("originalUPCompleteReload",true)
        put("sameMIDChangedEpochAndRetirement",true);put("actualOriginalTimelineRequestDeduplication",true)
        put("nativeWindowOrPackageTested",false);put("realAccountOrSocket",false)
    }
    Files.writeString(output.resolve("result.json"),Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),result)+"\n")
    println(result)
}
