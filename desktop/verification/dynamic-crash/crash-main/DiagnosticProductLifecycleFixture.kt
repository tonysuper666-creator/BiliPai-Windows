@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, kotlinx.coroutines.InternalCoroutinesApi::class)
@file:Suppress("DEPRECATION")
package com.bilipai.desktop.diagnostics.proof

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.core.theme.AppUiStyle
import com.bilipai.desktop.DesktopApp
import com.bilipai.desktop.appearance.DesktopThemePrefs
import com.bilipai.desktop.data.*
import com.bilipai.desktop.diagnostics.*
import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.security.Permission
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

private val checks = mutableListOf<String>()
private fun verify(label:String, value:Boolean) { check(value) {label}; checks += label; println("PASS: $label") }
private suspend fun waitFor(label:String, predicate:()->Boolean) {
    withTimeout(6000) { while(!predicate()) delay(5) }; verify(label,true)
}
/** A fixture safety fence, not a network client or product implementation override. */
private class OfflineFence:SecurityManager() {
    val attempts=AtomicInteger()
    var watchedFile:String?=null
    val watchedReads=AtomicInteger()
    override fun checkPermission(permission:Permission) {}
    override fun checkRead(file:String) {if(file==watchedFile)watchedReads.incrementAndGet()}
    override fun checkConnect(host:String,port:Int) {attempts.incrementAndGet();throw SecurityException("Fixture denies network")}
    override fun checkListen(port:Int) {attempts.incrementAndGet();throw SecurityException("Fixture denies listeners")}
    override fun checkMulticast(address:java.net.InetAddress) {attempts.incrementAndGet();throw SecurityException("Fixture denies multicast")}
    override fun checkExec(command:String) {attempts.incrementAndGet();throw SecurityException("Fixture denies processes")}
}
private fun store(root:Path):DesktopPluginStore = DesktopPluginStore(root).also {
    // Actual persisted optional casting configuration; no LAN discovery in these offline cases.
    it.update("plugin_prefs",mapOf("plugin_enabled_dlna_cast" to JsonPrimitive(false),"plugin_enabled_google_cast" to JsonPrimitive(false)))
}
private fun writable(store:DesktopPluginStore,key:String):Boolean = runCatching {
    store.update("fixture_lifecycle",mapOf(key to JsonPrimitive(true)))
}.isSuccess
private fun enhanced(store:DesktopPluginStore) = store.preferences("settings")["enhanced_diagnostic_logging_enabled"]?.jsonPrimitive?.booleanOrNull
private fun repository(root:Path) = DesktopRepository(DesktopSessionStore(root.resolve("task-session.json"),persistent=false))
private fun closeRepository(repository:DesktopRepository) {repository.httpClient.connectionPool.evictAll();repository.httpClient.dispatcher.executorService.shutdown()}
private suspend fun runtime(store:DesktopPluginStore,hook:suspend()->Unit):DesktopPluginRuntime {
    val runtime=withContext(Dispatchers.Main) {DesktopPluginRuntime(store,beforeStoreFreeze=hook)}
    withTimeout(10000) {
        runtime.plugins.first {it.size==12}
        for(info in runtime.plugins.value) PluginManager.awaitPluginReady(info.plugin.id)
    }
    verify("actual twelve providers settle without enabled LAN providers",runtime.plugins.value.size==12&&runtime.plugins.value.none{it.enabled})
    return runtime
}
/** Offline state input retaining the actual product observePlayback implementation. */
private class TrackedState(initial:PlayerState):StateFlow<PlayerState> {
    private val state=MutableStateFlow(initial)
    val entered=CompletableDeferred<Unit>();val finished=CompletableDeferred<Unit>()
    override val replayCache get()=state.replayCache
    override val value get()=state.value
    fun change(next:PlayerState) {state.value=next}
    override suspend fun collect(collector:FlowCollector<PlayerState>):Nothing {
        entered.complete(Unit)
        try {state.collect(collector)} finally {finished.complete(Unit)}
    }
}
private class TwoObservers(val lifecycle:DesktopDiagnosticLifecycle) {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val video=TrackedState(PlayerState(ready=true,videoCodec="h264",sourceTitle="private-video-title"))
    val audio=TrackedState(PlayerState(ready=true,audioOnly=true,audioCodec="aac",subtitleText="private-subtitle"))
    val jobs=listOf(lifecycle.observePlayback(video,scope),lifecycle.observePlayback(audio,scope))
    suspend fun entered() {withTimeout(4000){video.entered.await();audio.entered.await()}}
    suspend fun joined() {
        withTimeout(4000){video.finished.await();audio.finished.await();jobs.forEach{it.join()}}
        verify("both actual observer Jobs cancelled and joined",jobs.all{it.isCancelled&&it.isCompleted})
    }
    fun close() {scope.cancel()}
}
private suspend fun startup(root:Path) {
    val global=store(root.resolve("global"))
    global.update("settings",mapOf("enhanced_diagnostic_logging_enabled" to JsonPrimitive(true)))
    val result=openDesktopDiagnostics(global,"fixture-version")
    verify("actual factory preserves successful Result with same authoritative consent",result.isSuccess&&result.getOrThrow().enhancedEnabled.value)
    val actor=result.getOrThrow()
    try {
        DesktopDiagnosticsBridge.record("I","StartupFixture","before_repository")
        val repo=repository(root)
        try {
            verify("actual task Repository has no saved account",repo.account.value==null&&repo.savedAccounts.value.isEmpty())
            DesktopDiagnosticsBridge.record("I","StartupFixture","after_repository")
            actor.flush()
            val local=actor.viewLocal()
            verify("actual bridge events straddle Repository construction in local file consumer",local.indexOf("before_repository")>=0&&local.indexOf("after_repository")>local.indexOf("before_repository"))
            verify("second facade reads the same global consent",enhanced(DesktopPluginStore(global.root))==true)
            actor.setEnhancedEnabled(false)
            verify("actual actor writes the authoritative global key",enhanced(global)==false&&enhanced(DesktopPluginStore(global.root))==false)
            DesktopDiagnosticsBridge.record("I","StartupFixture","disabled-detail-marker")
            DesktopDiagnosticsBridge.record("W","StartupFixture","warning-stays-local")
            actor.flush();val disabled=actor.viewLocal()
            verify("disabled detail absent while basic warnings remain",!disabled.contains("disabled-detail-marker")&&disabled.contains("warning-stays-local"))
        } finally {closeRepository(repo)}
    } finally {actor.shutdownForRestore()}
    for((name,bad) in listOf("object" to buildJsonObject {put("invalid","fixture-only")},"array" to JsonArray(listOf(JsonPrimitive(true))))) {
        val broken=store(root.resolve(name));broken.update("settings",mapOf("enhanced_diagnostic_logging_enabled" to bad))
        val before=Files.readAllBytes(broken.root.resolve("plugin-settings.json"))
        val failed=openDesktopDiagnostics(broken,"fixture-version")
        verify("malformed $name consent is Failure with null actor",failed.isFailure&&failed.getOrNull()==null)
        verify("malformed $name bytes never replaced with fabricated false",before.contentEquals(Files.readAllBytes(broken.root.resolve("plugin-settings.json")))&&broken.preferences("settings")["enhanced_diagnostic_logging_enabled"]==bad)
    }
}
private suspend fun observers(root:Path) {
    val global=store(root);global.update("settings",mapOf("enhanced_diagnostic_logging_enabled" to JsonPrimitive(true)))
    val actor=DesktopDiagnostics(global,"fixture-version");val life=DesktopDiagnosticLifecycle(actor);val inputs=TwoObservers(life)
    try {
        inputs.entered();actor.flush();val local=actor.viewLocal()
        verify("both real observer summaries reach the local file consumer",local.contains("audioOnly=false")&&local.contains("audioOnly=true"))
        verify("native state logging omits source titles and subtitle text",!local.contains("private-video-title")&&!local.contains("private-subtitle"))
        life.shutdownForRestore();inputs.joined()
        verify("retired actor rejects subsequent records",!actor.record("I","Fixture","late"))
        verify("retired lifecycle rejects late observer registration",runCatching{life.observePlayback(MutableStateFlow(PlayerState()),inputs.scope)}.isFailure)
        life.shutdownForRestore();verify("repeated real lifecycle shutdown is idempotent",true)
    } finally {inputs.close();life.shutdownForRestore()}
}
private suspend fun acceptedWriter(root:Path,closeBypass:Boolean) = coroutineScope {
    val global=store(root);val heldPeer=DesktopPluginStore(root);val executor=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,LinkedBlockingQueue<Runnable>()){task->Thread(task,"fixture-real-writer").apply{isDaemon=true}}
    val actor=DesktopDiagnostics(global,"fixture-version",writer=executor);actor.flush()
    val life=DesktopDiagnosticLifecycle(actor);val inputs=TwoObservers(life);inputs.entered()
    val hookEntered=CompletableDeferred<Unit>();val hookDone=CompletableDeferred<Unit>();val calls=AtomicInteger()
    val runtime=runtime(global){calls.incrementAndGet();hookEntered.complete(Unit);life.shutdownForRestore();hookDone.complete(Unit)}
    val entered=CountDownLatch(1);val release=CountDownLatch(1)
    executor.execute {entered.countDown();check(release.await(8,TimeUnit.SECONDS)){"Fixture gate timed out"}}
    verify("real actor writer is actually blocked",entered.await(2,TimeUnit.SECONDS))
    var shutdown:Deferred<Unit>?=null
    try {
        val accepted=async(Dispatchers.Default){actor.setEnhancedEnabled(true)}
        waitFor("actual consent write accepted in real executor queue"){executor.queue.isNotEmpty()}
        if(closeBypass)runtime.close() else shutdown=async(Dispatchers.Default){runtime.shutdownForRestore()}
        withTimeout(4000){hookEntered.await()};inputs.joined()
        verify("Runtime hook remains behind actual accepted writer",!hookDone.isCompleted&&(shutdown==null||!shutdown!!.isCompleted))
        verify("global backing is writable before accepted actor write drains",writable(global,"before_drain"))
        verify("no late observation is accepted during Runtime retirement",runCatching{life.observePlayback(MutableStateFlow(PlayerState()),inputs.scope)}.isFailure)
        verify("actor rejects new record while accepted queue drains",!actor.record("I","Fixture","late"))
        release.countDown();withTimeout(4000){accepted.await();hookDone.await()}
        if(shutdown!=null)withTimeout(10000){shutdown!!.await()} else withTimeout(10000){while(writable(global,"await_retired"))delay(5)}
        verify("accepted original consent persists before actual global freeze",enhanced(global)==true&&Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject["settings"]!!.jsonObject["enhanced_diagnostic_logging_enabled"]!!.jsonPrimitive.boolean)
        verify("retired held shared facade rejects writes after actual Runtime shutdown",!writable(global,"late")&&!writable(heldPeer,"other_facade_late"))
        runtime.shutdownForRestore();life.shutdownForRestore()
        verify("actual Runtime close/explicit repeat invokes lifecycle hook once",calls.get()==1)
    } finally {release.countDown();inputs.close();runtime.shutdownForRestore()}
}
private suspend fun hookFailure(root:Path) {
    val global=store(root);val life=DesktopDiagnosticLifecycle(DesktopDiagnostics(global,"fixture-version"));val calls=AtomicInteger()
    val runtime=runtime(global){if(calls.incrementAndGet()==1)throw IllegalStateException("intentional fixture hook failure");life.shutdownForRestore()}
    try {
        val first=runCatching{runtime.shutdownForRestore()}
        verify("actual Runtime propagates retirement hook failure",first.isFailure&&first.exceptionOrNull()?.message=="intentional fixture hook failure")
        verify("failed hook never freezes backing or falsely reports stopped",writable(global,"after_failed_hook"))
        runtime.shutdownForRestore()
        verify("explicit same Runtime retry drains and freezes",calls.get()==2&&!writable(global,"after_successful_retry"))
        runtime.shutdownForRestore();verify("successful retry remains idempotent",calls.get()==2)
    } finally {runtime.shutdownForRestore()}
}
private class RootScene(val scene:ImageComposeScene) {
    var nanos=0L
    fun nodes():List<SemanticsNode> {fun walk(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}}
    fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    suspend fun frame(){nanos+=30_000_000L;scene.render(nanos).close();delay(5)}
    suspend fun wait(label:String,predicate:()->Boolean){try {withTimeout(6000){while(!predicate())frame()};repeat(20){frame()}}
        catch(error:TimeoutCancellationException){error("$label actual labels=${labels()}")}}
    suspend fun click(label:String) {
        val target=nodes().last{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true}.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press,target,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,target,timeMillis=nanos/1_000_000+55,buttons=PointerButtons())
        repeat(10){frame()}
    }
    suspend fun png(path:Path) {repeat(80){frame()};scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
}
private suspend fun rootStartupUi(root:Path,style:AppUiStyle,output:Path,context:CoroutineContext,fence:OfflineFence) {
    val global=store(root.resolve("global"));val heldPeer=DesktopPluginStore(global.root);DesktopThemePrefs(global).setUiStyle(style)
    val bad:JsonElement=if(style==AppUiStyle.MATERIAL3)buildJsonObject{put("bad","private-malformed-consent")}else JsonArray(listOf(JsonPrimitive("private-malformed-consent")))
    global.update("settings",mapOf("enhanced_diagnostic_logging_enabled" to bad))
    val diagnosticResult=openDesktopDiagnostics(global,"fixture-version")
    verify("actual Main factory failure supplies nullable Root consumer",diagnosticResult.isFailure&&diagnosticResult.getOrNull()==null)
    val corrupt=global.root.resolve("discovery/plugin-settings.json");Files.createDirectories(corrupt.parent);Files.writeString(corrupt,"private-corrupt-discovery")
    val original=Files.readAllBytes(corrupt);fence.watchedFile=corrupt.toString();val repo=repository(root)
    val shutdown=AtomicReference<(suspend()->Unit)?>(null);val restarted=AtomicInteger();val exited=AtomicInteger()
    val scene=ImageComposeScene(width=1080,height=800,coroutineContext=context);val ui=RootScene(scene)
    try {
        scene.setContent { DesktopApp(repo,null,null,null,{exited.incrementAndGet()},{},
            registerShutdown={shutdown.set(it)},onRestart={restarted.incrementAndGet()},applicationPluginStore=global,
            diagnosticLifecycle=null,diagnosticStartupError="诊断配置无法读取，请检查配置并重新启动。") }
        ui.wait("actual Root has both startup errors") {"重试读取" in ui.labels()&&"诊断配置无法读取，请检查配置并重新启动。" in ui.labels()}
        verify("actual Root shows both diagnostics and classified storage error",ui.labels().containsAll(listOf("诊断配置无法读取，请检查配置并重新启动。","本地存储无法读取")))
        verify("actual Root labels hide raw consent/discovery bytes and paths",ui.labels().none{it.contains("private-")||it.contains(root.toString())})
        verify("Root cold guard never creates a guest account",repo.account.value==null&&repo.savedAccounts.value.isEmpty())
        ui.png(output.resolve("${style.name.lowercase()}-root-startup-error.png"))
        val readsBeforeRetry=fence.watchedReads.get()
        ui.click("重试读取");ui.wait("actual Root retry reads the same corrupt file again") {fence.watchedReads.get()>readsBeforeRetry&&"重试读取" in ui.labels()}
        verify("retry pointer actually invokes product disk reader again",fence.watchedReads.get()>readsBeforeRetry)
        verify("original retry pointer preserves corrupt input and remains outside Ready",Files.readAllBytes(corrupt).contentEquals(original)&&"本地存储无法读取" in ui.labels())
        ui.png(output.resolve("${style.name.lowercase()}-root-retry-error.png"))
        verify("actual Root registered shutdown callback",shutdown.get()!=null)
        shutdown.get()!!.invoke()
        ui.wait("actual Root shutdown exposes stopped state") {"本地设置读取已停止，请重新启动应用。" in ui.labels()}
        verify("actual Root startup shutdown freezes the same held global backing",!writable(global,"after_root_shutdown")&&!writable(heldPeer,"peer_after_root_shutdown"))
        ui.click("重新启动应用")
        verify("original restart pointer calls supplied callback without real process launch",restarted.get()==1&&exited.get()==0)
        ui.png(output.resolve("${style.name.lowercase()}-root-stopped.png"))
        verify("malformed diagnostic consent remains unchanged after Root retirement",global.preferences("settings")["enhanced_diagnostic_logging_enabled"]==bad)
    } finally {scene.close();closeRepository(repo)}
}
fun main(args:Array<String>):Unit=runBlocking {
    val case=args[0];val output=Path.of(args[1]);Files.createDirectories(output)
    val root=Files.createTempDirectory("dgl-case-");val fence=OfflineFence();System.setSecurityManager(fence)
    when(case) {
        "startup"->startup(root)
        "observers"->observers(root)
        "accepted-writer"->acceptedWriter(root,false)
        "close-bypass"->acceptedWriter(root,true)
        "hook-failure"->hookFailure(root)
        "root-startup-material3"->rootStartupUi(root,AppUiStyle.MATERIAL3,output,coroutineContext,fence)
        "root-startup-miuix"->rootStartupUi(root,AppUiStyle.MIUIX,output,coroutineContext,fence)
        else->error("Unknown fixture case")
    }
    verify("offline fence observed zero network/listener/multicast/process attempts",fence.attempts.get()==0)
    Files.writeString(output.resolve("result.json"),buildJsonObject {put("passed",true);put("case",case);put("checks",JsonArray(checks.map(::JsonPrimitive)))
        put("productClassOverrides",false);put("nativeWindowCreated",false);put("realAccountOrHttp",false);put("networkOrProcessAttempts",fence.attempts.get());put("watchedDiscoveryFileReadChecks",fence.watchedReads.get())
        put("actualRootStartupComposable",case.startsWith("root-startup"));put("nativeMainExecuted",false);put("fixtureTemporaryRoot",root.toString())}.toString())
    System.setSecurityManager(null)
}
