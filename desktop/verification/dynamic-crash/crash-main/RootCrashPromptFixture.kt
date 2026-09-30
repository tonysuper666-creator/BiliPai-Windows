@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
@file:Suppress("DEPRECATION")
package com.bilipai.desktop.diagnostics.rootcrashproof
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.util.*
import com.bilipai.desktop.DesktopApp
import com.bilipai.desktop.appearance.DesktopThemePrefs
import com.bilipai.desktop.data.*
import com.bilipai.desktop.diagnostics.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.security.Permission
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val checks=mutableListOf<String>()
private fun verify(label:String,value:Boolean){check(value){label};checks+=label;println("PASS: $label")}
private class Fence:SecurityManager(){val attempts=AtomicInteger();override fun checkPermission(p:Permission){}
    override fun checkConnect(h:String,p:Int){attempts.incrementAndGet();error("No network in fixture")}
    override fun checkListen(p:Int){attempts.incrementAndGet();error("No listening in fixture")}
    override fun checkMulticast(a:java.net.InetAddress){attempts.incrementAndGet();error("No multicast in fixture")}
    override fun checkExec(c:String){attempts.incrementAndGet();error("No process in fixture")}}
private class Scene(val scene:ImageComposeScene){var nanos=0L
    fun nodes():List<SemanticsNode>{fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}}
    fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    suspend fun frame(){nanos+=30_000_000L;scene.render(nanos).close();delay(5)}
    suspend fun wait(label:String,predicate:()->Boolean){try{withTimeout(6500){while(!predicate())frame()};repeat(24){frame()}}
        catch(e:TimeoutCancellationException){error("$label actual labels=${labels()}")}}
    suspend fun pointer(point:Offset){scene.sendPointerEvent(PointerEventType.Press,point,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true));scene.sendPointerEvent(PointerEventType.Release,point,timeMillis=nanos/1_000_000+55,buttons=PointerButtons());repeat(16){frame()}}
    suspend fun click(label:String){pointer(nodes().last{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true}.boundsInRoot.center)}
    suspend fun png(path:Path){repeat(80){frame()};scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
}
private fun writable(store:DesktopPluginStore,key:String)=runCatching{store.update("fixture_root_shutdown",mapOf(key to JsonPrimitive(true)))}.isSuccess

fun main(args:Array<String>):Unit=runBlocking {
    val style=AppUiStyle.valueOf(args[0]);val action=args[1];val out=Path.of(args[2]);Files.createDirectories(out)
    val root=Files.createTempDirectory("root-crash-task-");val store=DesktopPluginStore(root);val peer=DesktopPluginStore(root)
    DesktopThemePrefs(store).setUiStyle(style)
    val corrupt=root.resolve("discovery/plugin-settings.json");Files.createDirectories(corrupt.parent);Files.writeString(corrupt,"private-corrupt-discovery-fixture")
    val originalCorrupt=Files.readAllBytes(corrupt)
    val writer=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,LinkedBlockingQueue<Runnable>()){r->Thread(r,"fixture-root-crash-writer").apply{isDaemon=true}}
    val actor=DesktopDiagnostics(store,"root-crash-fixture",writer=writer);actor.flush()
    verify("actual retained actor saves synthetic crash",actor.persistLocalCrash(IllegalStateException("root-fixture-synthetic-crash Cookie=root-fixture-secret")));actor.flush()
    val snapshot=resolveCrashSnapshotFile(root.toFile()).toPath();val marker=resolveCrashSnapshotMarkerFile(root.toFile()).toPath();val before=Files.readAllBytes(snapshot)
    val lifecycle=DesktopDiagnosticLifecycle(actor);val originalOwner=lifecycle.crashPrompt
    val repo=DesktopRepository(DesktopSessionStore(root.resolve("session.json"),persistent=false))
    val shutdown=AtomicReference<(suspend()->Unit)?>(null);val exitCalls=AtomicInteger();val restartCalls=AtomicInteger()
    val gateEntered=CountDownLatch(1);val release=CountDownLatch(1)
    val barrier=action=="pending-shutdown"
    if(barrier){writer.execute{gateEntered.countDown();check(release.await(12,TimeUnit.SECONDS))};verify("real diagnostic queue gate enters",gateEntered.await(2,TimeUnit.SECONDS))}
    val fence=Fence();System.setSecurityManager(fence)
    val scene=ImageComposeScene(width=1080,height=900,coroutineContext=coroutineContext);val probe=Scene(scene)
    try {
        scene.setContent {DesktopApp(repo,null,null,null,{exitCalls.incrementAndGet()},{},
            registerShutdown={shutdown.set(it)},onRestart={restartCalls.incrementAndGet()},
            applicationPluginStore=store,diagnosticLifecycle=lifecycle)}
        probe.wait("actual corrupt discovery fails outside Ready") {"本地存储无法读取" in probe.labels()&&shutdown.get()!=null}
        verify("Root uses exactly the lifecycle retained controller and same actor",lifecycle.crashPrompt===originalOwner&&originalOwner.diagnostics===actor)
        verify("Root failure keeps original guest bytes and no account",Files.readAllBytes(corrupt).contentEquals(originalCorrupt)&&repo.account.value==null&&repo.savedAccounts.value.isEmpty())
        if(barrier) {
            probe.wait("actual Root has accepted pending prompt read") {writer.queue.isNotEmpty()&&originalOwner.state.value.busy}
            val task=async(Dispatchers.Default){shutdown.get()!!.invoke()}
            probe.wait("actual Root lifecycle retires controller first") {originalOwner.state.value.closed}
            verify("actual Root shutdown waits pending prompt read",!task.isCompleted)
            verify("actor still accepts finite record after controller retirement before drain",actor.record("W","Fixture","after_controller_retired_before_actor_close"))
            verify("global backing still writable while accepted read blocks retirement",writable(store,"before_actor_drain"))
            release.countDown();withTimeout(6500){task.await()}
            verify("late accepted reader cannot reopen prompt or viewer",originalOwner.state.value.closed&&!originalOwner.state.value.pending&&!originalOwner.state.value.viewerRequested)
            verify("unhandled pending crash remains available for next session",Files.exists(marker)&&Files.readAllBytes(snapshot).contentEquals(before))
        } else {
            probe.wait("actual Root prompt appears despite failed discovery") {"检测到上次闪退日志" in probe.labels()&&"分享" in probe.labels()}
            verify("original prompt and actual storage boundary coexist","本地存储无法读取" in probe.labels()&&"关闭" in probe.labels())
            probe.png(out.resolve("$action-root-original-prompt.png"))
            when(action){"share"->probe.click("分享");"dismiss"->probe.click("关闭");"outside"->probe.pointer(Offset(15f,15f));else->error("Unexpected action")}
            probe.wait("actual Root marker-only action completes") {!originalOwner.state.value.busy&&!Files.exists(marker)}
            verify("actual Root original action preserves snapshot bytes",Files.readAllBytes(snapshot).contentEquals(before))
            verify("Root handled state hides repeat prompt",originalOwner.state.value.handled&&"检测到上次闪退日志" !in probe.labels())
            if(action=="share") {
                probe.wait("Root SHARE opens actual same actor local viewer") {"本地诊断日志" in probe.labels()&&"导出日志" in probe.labels()}
                verify("Root local viewer uses retained controller",originalOwner.state.value.viewerRequested&&lifecycle.crashPrompt===originalOwner)
                probe.png(out.resolve("share-root-actual-local-viewer.png"))
                // Root export is hard bound to a real OS chooser: deliberately do not click it.
                probe.click("关闭");probe.wait("Root viewer closes to actual storage error") {!originalOwner.state.value.viewerRequested&&"本地存储无法读取" in probe.labels()}
                verify("actual Root viewer close keeps snapshot",Files.readAllBytes(snapshot).contentEquals(before))
            }else verify("Root nonshare never opens viewer",!originalOwner.state.value.viewerRequested)
            shutdown.get()!!.invoke()
        }
        probe.wait("actual Root stopped storage boundary") {"本地设置读取已停止，请重新启动应用。" in probe.labels()}
        verify("controller is retired before Root shutdown returns",originalOwner.state.value.closed)
        verify("same actor rejects work after actual Root lifecycle drain",!actor.record("W","Fixture","after_root_close"))
        verify("actual Root freezes existing global and peer facades after actor drain",!writable(store,"late")&&!writable(peer,"peer_late"))
        verify("Root shutdown preserves original corrupt discovery bytes",Files.readAllBytes(corrupt).contentEquals(originalCorrupt))
        shutdown.get()!!.invoke();verify("actual Root shutdown repeat remains safe",originalOwner.state.value.closed)
        probe.png(out.resolve("$action-root-stopped.png"))
        verify("no exit/restart or automatic external sharing",exitCalls.get()==0&&restartCalls.get()==0&&fence.attempts.get()==0)
        Files.writeString(out.resolve("result.json"),buildJsonObject{put("passed",true);put("style",style.name);put("action",action);put("checks",JsonArray(checks.map(::JsonPrimitive)))
            put("taskRoot",root.toString());put("actualDesktopApp",true);put("actualLifecycleOwnedController",true);put("productionClassOverrides",false)
            put("nativeMainWindow",false);put("OSChooserClicked",false);put("businessHttpOrAccount",false);put("networkOrProcessAttempts",fence.attempts.get())}.toString())
    }finally{release.countDown();scene.close();lifecycle.shutdownForRestore();repo.httpClient.connectionPool.evictAll();repo.httpClient.dispatcher.executorService.shutdown();System.setSecurityManager(null)}
}
