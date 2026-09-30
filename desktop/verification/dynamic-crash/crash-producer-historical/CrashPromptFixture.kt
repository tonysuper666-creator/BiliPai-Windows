@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
@file:Suppress("DEPRECATION")
package com.bilipai.desktop.diagnostics.crashproof
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.util.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.diagnostics.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.security.Permission
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

private val checks=mutableListOf<String>()
private fun verify(label:String,value:Boolean){check(value){label};checks+=label;println("PASS: $label")}
private suspend fun waitFor(label:String,predicate:()->Boolean){withTimeout(4000){while(!predicate())delay(5)};verify(label,true)}
private class Fence:SecurityManager(){val attempts=AtomicInteger();override fun checkPermission(p:Permission){}
    override fun checkConnect(h:String,p:Int){attempts.incrementAndGet();error("Offline fixture")}
    override fun checkListen(p:Int){attempts.incrementAndGet();error("Offline fixture")}
    override fun checkMulticast(a:java.net.InetAddress){attempts.incrementAndGet();error("Offline fixture")}
    override fun checkExec(c:String){attempts.incrementAndGet();error("Offline fixture")}}
private fun snapshot(root:Path)=resolveCrashSnapshotFile(root.toFile()).toPath()
private fun marker(root:Path)=resolveCrashSnapshotMarkerFile(root.toFile()).toPath()
private suspend fun populate(actor:DesktopDiagnostics){actor.flush();verify("same actual actor saves synthetic local crash",actor.persistLocalCrash(IllegalStateException("fixture-local-crash Cookie=fixture-secret")));actor.flush()}
private suspend fun disk(root:Path):Unit=coroutineScope {
    for(pending in listOf(false,true))for(handled in listOf(false,true))verify("original show policy $pending/$handled",shouldShowPendingCrashLogPrompt(pending,handled)==(pending&&!handled))
    for(action in CrashLogPromptAction.entries) {
        val dir=root.resolve(action.name);val actor=DesktopDiagnostics(DesktopPluginStore(dir),"fixture-version");val owner=DesktopCrashPromptController(actor)
        try {populate(actor);val before=Files.readAllBytes(snapshot(dir));owner.load();verify("actual pending read $action",owner.state.value.pending&&!owner.state.value.handled)
            owner.handle(action)
            verify("original handled state hides repeated prompt $action",owner.state.value.handled&&!shouldShowPendingCrashLogPrompt(owner.state.value.pending,owner.state.value.handled))
            verify("original action clear policy $action",shouldClearPendingCrashLogAfterAction(action)==(action!=CrashLogPromptAction.IGNORE))
            verify("marker policy actual disk $action",Files.exists(marker(dir))==(action==CrashLogPromptAction.IGNORE))
            verify("snapshot bytes remain unchanged $action",Files.readAllBytes(snapshot(dir)).contentEquals(before))
            verify("only explicit SHARE requests same actor viewer $action",owner.state.value.viewerRequested==(action==CrashLogPromptAction.SHARE))
            if(action==CrashLogPromptAction.IGNORE){val next=DesktopCrashPromptController(actor);next.load();verify("ignored marker prompts on new session owner",next.state.value.pending&&!next.state.value.handled);next.close()}
            owner.shutdownForRestore();verify("prompt retirement keeps retained actor alive",actor.record("W","Fixture","owner_retired_actor_alive"));actor.flush()
        }finally{owner.shutdownForRestore();actor.shutdownForRestore()}
    }
    val dir=root.resolve("clear-failure");val actor=DesktopDiagnostics(DesktopPluginStore(dir),"fixture-version");val owner=DesktopCrashPromptController(actor)
    try {populate(actor);owner.load();val before=Files.readAllBytes(snapshot(dir));Files.delete(marker(dir));Files.createDirectory(marker(dir))
        owner.handle(CrashLogPromptAction.DISMISS)
        verify("real marker IO failure publishes fixed safe state",owner.state.value.error=="崩溃日志标记无法清理，原快照已保留。"&&owner.state.value.retryAction==CrashLogPromptAction.DISMISS)
        verify("failed clear preserves snapshot and marker obstacle",Files.readAllBytes(snapshot(dir)).contentEquals(before)&&Files.isDirectory(marker(dir)))
        Files.delete(marker(dir));Files.writeString(marker(dir),"fixture-marker");owner.handle(CrashLogPromptAction.DISMISS)
        verify("explicit same owner clear retry succeeds",!Files.exists(marker(dir))&&owner.state.value.error==null&&Files.readAllBytes(snapshot(dir)).contentEquals(before))
    }finally{owner.shutdownForRestore();actor.shutdownForRestore()}
    for(share in listOf(false,true)) {
        val dir=root.resolve(if(share)"late-share"else"late-read")
        val writer=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,LinkedBlockingQueue<Runnable>()){r->Thread(r,"fixture-crash-writer").apply{isDaemon=true}}
        val actor=DesktopDiagnostics(DesktopPluginStore(dir),"fixture-version",writer=writer);populate(actor)
        val owner=DesktopCrashPromptController(actor);if(share)owner.load()
        val before=Files.readAllBytes(snapshot(dir));val entered=CountDownLatch(1);val release=CountDownLatch(1)
        writer.execute{entered.countDown();check(release.await(8,TimeUnit.SECONDS))};verify("actual actor queue gate enters share=$share",entered.await(2,TimeUnit.SECONDS))
        try {val operation=async(Dispatchers.Default){if(share)owner.handle(CrashLogPromptAction.SHARE)else owner.load()}
            waitFor("actual delayed actor read accepted share=$share"){writer.queue.isNotEmpty()}
            val shutdown=async(Dispatchers.Default){owner.shutdownForRestore()}
            waitFor("prompt owner retires before late actor result share=$share"){owner.state.value.closed}
            verify("retirement waits accepted pending operation share=$share",!shutdown.isCompleted)
            release.countDown();withTimeout(4000){operation.await();shutdown.await()}
            verify("late read cannot reopen prompt/viewer share=$share",owner.state.value.closed&&!owner.state.value.pending&&!owner.state.value.viewerRequested&&owner.state.value.error==null)
            verify("unfulfilled closed share/read retains marker and snapshot share=$share",Files.exists(marker(dir))&&Files.readAllBytes(snapshot(dir)).contentEquals(before))
            verify("prompt close never closes shared diagnostics share=$share",actor.record("W","Fixture","after_prompt_close"));actor.flush()
        }finally{release.countDown();owner.shutdownForRestore();actor.shutdownForRestore()}
    }
}
private class Scene(val scene:ImageComposeScene){var nanos=0L
    fun nodes():List<SemanticsNode>{fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}}
    fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    suspend fun frame(){nanos+=30_000_000L;scene.render(nanos).close();delay(5)}
    suspend fun wait(label:String,predicate:()->Boolean){try{withTimeout(6000){while(!predicate())frame()};repeat(24){frame()}}catch(e:TimeoutCancellationException){error("$label actual=${labels()}")}}
    suspend fun pointer(point:Offset){scene.sendPointerEvent(PointerEventType.Press,point,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true));scene.sendPointerEvent(PointerEventType.Release,point,timeMillis=nanos/1_000_000+55,buttons=PointerButtons());repeat(15){frame()}}
    suspend fun click(label:String){pointer(nodes().last{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true}.boundsInRoot.center)}
    suspend fun png(path:Path){repeat(80){frame()};scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
}
private suspend fun ui(root:Path,style:AppUiStyle,out:Path,context:CoroutineContext) {
    for(action in listOf("share","dismiss","outside","read-failure")) {
        val dir=root.resolve(action);val store=DesktopPluginStore(dir);val prefs=DesktopThemePrefs(store);prefs.setUiStyle(style)
        val actor=DesktopDiagnostics(store,"fixture-version");populate(actor);val before=Files.readAllBytes(snapshot(dir));val owner=DesktopCrashPromptController(actor)
        if(action=="read-failure"){Files.delete(marker(dir));Files.createDirectory(marker(dir))}
        var chooserCalls=0;val export=dir.resolve("explicit-fixture-export.txt")
        val scene=ImageComposeScene(width=1080,height=900,coroutineContext=context);val probe=Scene(scene)
        try {scene.setContent{val theme by prefs.settings.collectAsState(prefs.initialSettings());DesktopAppearanceTheme(theme){AppSurface(Modifier.fillMaxSize()){DesktopCrashPromptHost(owner){chooserCalls++;export}}}}
            if(action=="read-failure") {
                probe.wait("safe real reader failure") {"崩溃日志提示无法读取，请重试。" in probe.labels()}
                verify("actual read failure UI hides raw path/Throwable",probe.labels().none{it.contains(dir.toString())||it.contains("IllegalArgumentException")})
                probe.png(out.resolve("${style.name.lowercase()}-safe-read-failure.png"))
                Files.delete(marker(dir));Files.writeString(marker(dir),"fixture-marker")
                probe.click("重试读取");probe.wait("original prompt after actual repair") {"检测到上次闪退日志" in probe.labels()}
                verify("read retry repairs without clearing snapshot or marker",Files.exists(marker(dir))&&Files.readAllBytes(snapshot(dir)).contentEquals(before));probe.click("关闭")
            } else {
                probe.wait("original pending crash dialog") {"检测到上次闪退日志" in probe.labels()&&"分享" in probe.labels()}
                verify("original text/actions retained and no invented ignore control",probe.labels().any{it.startsWith("应用已在私有目录保存一份脱敏后的崩溃快照")}&&"关闭" in probe.labels()&&"忽略" !in probe.labels())
                probe.png(out.resolve("${style.name.lowercase()}-$action-prompt.png"))
                when(action){"share"->probe.click("分享");"dismiss"->probe.click("关闭");"outside"->probe.pointer(Offset(15f,15f))}
            }
            probe.wait("actual marker-only action completes") {!owner.state.value.busy&&!Files.exists(marker(dir))}
            verify("pointer action keeps snapshot bytes $style/$action",Files.readAllBytes(snapshot(dir)).contentEquals(before))
            verify("pointer handled prevents prompt redisplay $style/$action",owner.state.value.handled&&"检测到上次闪退日志" !in probe.labels())
            if(action=="share") {
                probe.wait("actual retained actor viewer") {"本地诊断日志" in probe.labels()&&"导出日志" in probe.labels()}
                verify("share never automatically chooses or exports",chooserCalls==0&&!Files.exists(export))
                probe.click("导出日志");probe.wait("explicit original viewer export") {Files.exists(export)&&"日志已导出到你选择的本地文件" in probe.labels()}
                verify("explicit local export reads same preserved crash",chooserCalls==1&&Files.readString(export).contains("fixture-local-crash")&&!Files.readString(export).contains("fixture-secret"))
                probe.png(out.resolve("${style.name.lowercase()}-actual-viewer-export.png"));probe.click("关闭")
                verify("viewer close leaves crash snapshot for settings export",Files.exists(snapshot(dir)))
            }else verify("nonshare actions never request viewer/export",!owner.state.value.viewerRequested&&chooserCalls==0)
        }finally{scene.close();owner.shutdownForRestore();actor.shutdownForRestore()}
    }
}
fun main(args:Array<String>):Unit=runBlocking {
    val out=Path.of(args[1]);Files.createDirectories(out);val root=Files.createTempDirectory("crash-prompt-task-");val fence=Fence();System.setSecurityManager(fence)
    when(args[0]){"disk"->disk(root);"material3"->ui(root,AppUiStyle.MATERIAL3,out,coroutineContext);"miuix"->ui(root,AppUiStyle.MIUIX,out,coroutineContext);else->error("Unknown case")}
    verify("zero network/listener/multicast/process attempts",fence.attempts.get()==0)
    Files.writeString(out.resolve("result.json"),buildJsonObject{put("passed",true);put("case",args[0]);put("checks",JsonArray(checks.map(::JsonPrimitive)));put("taskRoot",root.toString());put("actualSharedActor",true);put("HWND",false);put("osChooser",false);put("productionClassOverrides",false);put("networkAttempts",fence.attempts.get())}.toString());System.setSecurityManager(null)
}
