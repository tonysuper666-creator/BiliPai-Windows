@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.diagnostics.nativeproductuiproof

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.CrashLogPromptAction
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.util.resolveCrashSnapshotFile
import com.android.purebilibili.core.util.resolveCrashSnapshotMarkerFile
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.diagnostics.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.sun.jna.*
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Window
import java.nio.file.*
import java.security.MessageDigest
import java.security.Permission
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
private fun write(path:Path,value:JsonElement){Files.writeString(path,value.toString())}
private enum class Case { TERMINAL, UNAVAILABLE, MARKER_RETRY, DISMISS, LATE_FAILURE, SHUTDOWN }
@Suppress("DEPRECATION")
private class TaskConnectionFence:SecurityManager() {
    val denied=CopyOnWriteArrayList<String>()
    override fun checkPermission(permission:Permission) {}
    override fun checkConnect(host:String,port:Int){denied.add("connect:$host:$port");throw SecurityException("Task forbids external socket")}
    override fun checkConnect(host:String,port:Int,context:Any?){checkConnect(host,port)}
    override fun checkListen(port:Int){denied.add("listen:$port");throw SecurityException("Task forbids listener")}
    override fun checkAccept(host:String,port:Int){denied.add("accept:$host:$port");throw SecurityException("Task forbids socket accept")}
    override fun checkExec(command:String){denied.add("exec:$command");throw SecurityException("Task forbids process launch")}
}
private interface MarkerLockKernel32:StdCallLibrary {
    fun CreateFileW(name:WString,access:Int,share:Int,security:Pointer?,creation:Int,flags:Int,template:Pointer?):Pointer?
    fun CloseHandle(handle:Pointer):Boolean
}

/** Task-only native EVENT transport. The actual product provider and actor own every lease. */
private class NativeEventPort(private val unavailable:Boolean):DesktopNativeShareTransport {
    private data class Event(val path:Path,val state:AtomicInteger=AtomicInteger(0),var supplied:Boolean=false,var retired:Boolean=false)
    private val next=AtomicLong()
    private val events=java.util.concurrent.ConcurrentHashMap<Long,Event>()
    val trace=CopyOnWriteArrayList<JsonObject>()
    val prepared=CopyOnWriteArrayList<Path>()
    val retired=CopyOnWriteArrayList<Long>()
    private fun log(type:String,token:Long,event:Event)=trace.add(buildJsonObject {
        put("event",type);put("token",token);put("nativeState",event.state.get());put("dataSupplied",event.supplied)
        put("syntheticNativeEventPort",true);put("SwingEDT",SwingUtilities.isEventDispatchThread())
        put("leaseCopy",event.path.toString());put("copyExists",Files.exists(event.path))
        if(Files.exists(event.path))put("copySha256Bytes",sha(Files.readAllBytes(event.path)))
    })
    override fun prepare(path:Path):Long {
        check(Files.isRegularFile(path)&&Files.size(path) in 1..256L*1024)
        check(Files.readString(path.parent.resolve(".lease")).startsWith("PREPARED\n"))
        val token=next.incrementAndGet();val event=Event(path);events[token]=event;prepared.add(path);log("prepare",token,event);return token
    }
    override fun show(token:Long):Boolean {
        val event=events.getValue(token);check(SwingUtilities.isEventDispatchThread())
        check(Files.readString(event.path.parent.resolve(".lease")).startsWith("MAY_EXPOSE\n"))
        if(!unavailable){event.state.set(2);event.supplied=true};log("show",token,event);return !unavailable
    }
    override fun state(token:Long):Int=events.getValue(token).state.get()
    override fun retire(token:Long):DesktopNativeShareRetirement {
        val event=events.getValue(token);check(SwingUtilities.isEventDispatchThread());check(!event.retired)
        event.retired=true;retired.add(token);log("retire",token,event)
        return DesktopNativeShareRetirement(event.state.get(),event.supplied)
    }
    fun explicitEvent(state:Int){check(state in listOf(3,4,5));val token=next.get();val event=events.getValue(token)
        check(!event.retired);event.state.set(state);log("explicit-task-event",token,event)}
    fun eventAfterRetirement(state:Int){val token=next.get();val event=events.getValue(token);check(event.retired)
        event.state.set(state);log("task-event-after-retirement",token,event)}
}

private class Ui(private val width:Int,private val height:Int,context:kotlin.coroutines.CoroutineContext) {
    val errors=CopyOnWriteArrayList<Throwable>()
    private val job=SupervisorJob();private val scope=CoroutineScope(context+job+CoroutineExceptionHandler{_,failure->errors+=failure})
    val scene=ImageComposeScene(width=width,height=height,coroutineContext=scope.coroutineContext)
    private var nanos=0L;val pointers=mutableListOf<JsonObject>();var screenshots=0
    private fun visit(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::visit)
    fun nodes()=scene.semanticsOwners.flatMap{visit(it.unmergedRootSemanticsNode)}
    private fun topNodes()=scene.semanticsOwners.lastOrNull()?.let{visit(it.unmergedRootSemanticsNode)}.orEmpty()
    fun texts()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text)?.map{t->t.text}.orEmpty()}
    suspend fun settle(){repeat(12){nanos+=30_000_000L;scene.render(nanos).close();yield();delay(3)}}
    suspend fun await(label:String,condition:()->Boolean){withTimeout(6000){while(!condition()){settle();delay(5)}};settle();check(errors.isEmpty()){ "$label: $errors" }}
    suspend fun click(text:String){
        await("visible top modal action $text"){topNodes().any{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==text}==true&&it.boundsInRoot.width>0&&it.boundsInRoot.height>0}}
        val node=topNodes().last{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==text}==true&&it.boundsInRoot.width>0&&it.boundsInRoot.height>0}
        var action=node;while(action.config.getOrNull(SemanticsActions.OnClick)==null&&action.parent!=null)action=action.parent!!
        val bounds=action.boundsInRoot;check(action.config.getOrNull(SemanticsActions.OnClick)!=null)
        check(bounds.left>=0&&bounds.top>=0&&bounds.right<=width&&bounds.bottom<=height){"Clipped action $text: $bounds"}
        val point=node.boundsInRoot.center;check(point.x in 0f..width.toFloat()&&point.y in 0f..height.toFloat())
        pointers+=buildJsonObject {put("text",text);put("x",point.x);put("y",point.y);put("actionLeft",bounds.left);put("actionTop",bounds.top);put("actionRight",bounds.right);put("actionBottom",bounds.bottom);put("actualPointer",true);put("topModalOnly",true)}
        scene.sendPointerEvent(PointerEventType.Press,point,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,point,timeMillis=nanos/1_000_000+55,buttons=PointerButtons())
        println("actual pointer $text at $point");settle();check(errors.isEmpty()){errors.toString()}
    }
    fun save(directory:Path,name:String){
        scene.render(nanos+1).use{Files.write(directory.resolve(name+".png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)};screenshots++
        write(directory.resolve(name+"-semantics.json"),JsonArray(nodes().map{n->buildJsonObject {
            put("text",JsonArray(n.config.getOrNull(SemanticsProperties.Text)?.map{JsonPrimitive(it.text)}.orEmpty()))
            put("left",n.boundsInRoot.left);put("top",n.boundsInRoot.top);put("right",n.boundsInRoot.right);put("bottom",n.boundsInRoot.bottom)
            put("onClick",n.config.getOrNull(SemanticsActions.OnClick)!=null)
        }}))
    }
    suspend fun close(){scene.close();job.cancelAndJoin()}
}

private suspend fun runCase(output:Path,style:AppUiStyle,theme:AppThemeMode,case:Case,context:kotlin.coroutines.CoroutineContext):JsonObject {
    val name="${style.name.lowercase()}-${theme.name.lowercase()}-${case.name.lowercase()}"
    val directory=output.resolve(name);Files.createDirectories(directory)
    val root=directory.resolve("task-owned-global-store");Files.createDirectories(root)
    val store=DesktopPluginStore(root);val actor=openDesktopDiagnostics(store,"native-actual-main-ui-task").getOrThrow()
    val port=NativeEventPort(case==Case.UNAVAILABLE||case==Case.MARKER_RETRY)
    val factoryCalls=AtomicInteger();var chooserCalls=0
    // There is no fixture HWND or second Window. Root's retained Window binding is source-audited separately.
    val windowReference=AtomicReference<Window?>(null)
    val provider=DesktopNativeCrashShare({error("Task port must not load a native DLL")},"0".repeat(64),windowReference::get,actor,{factoryCalls.incrementAndGet();port})
    val lifecycle=DesktopDiagnosticLifecycle(actor,provider)
    val ui=Ui(960,900,context)
    val snapshot=resolveCrashSnapshotFile(root.toFile()).toPath();val marker=resolveCrashSnapshotMarkerFile(root.toFile()).toPath()
    var lock:Pointer?=null;var kernel:MarkerLockKernel32?=null;var stage="seed";var shutdownDone=false
    fun stateJson()=buildJsonObject {
        val s=lifecycle.crashPrompt.state.value;put("pending",s.pending);put("handled",s.handled);put("loaded",s.loaded);put("busy",s.busy)
        put("closed",s.closed);put("viewerRequested",s.viewerRequested);put("error",s.error?.let(::JsonPrimitive)?:JsonNull)
        put("retryAction",s.retryAction?.name?.let(::JsonPrimitive)?:JsonNull);put("markerExists",Files.exists(marker));put("snapshotExists",Files.exists(snapshot))
        if(Files.exists(snapshot))put("snapshotSha256Bytes",sha(Files.readAllBytes(snapshot)))
        put("transportFactoryCalls",factoryCalls.get());put("syntheticNativeEvents",true)
    }
    suspend fun fallbackViewer(readRetry:Boolean=false){
        ui.await("original native failure error") {lifecycle.crashPrompt.state.value.error=="系统分享不可用，已打开本地诊断日志；原快照已保留。"&&"重试读取" in ui.texts()}
        ui.save(directory,"04-original-failure-dialog");write(directory.resolve("04-state.json"),stateJson())
        ui.click(if(readRetry)"重试读取" else "关闭");ui.await("actual local viewer") {lifecycle.crashPrompt.state.value.error==null&&"本地诊断日志" in ui.texts()&&ui.texts().any{"最近一次崩溃快照" in it}&&"正在读取本地日志…" !in ui.texts()}
        ui.save(directory,"05-original-local-viewer")
    }
    try {
        check(actor.persistLocalCrash(IllegalStateException("synthetic_native_share_ui_task")));actor.flush()
        val snapshotHash=sha(Files.readAllBytes(snapshot));check(Files.exists(marker))
        ui.scene.setContent {DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,themeMode=theme,hapticFeedbackEnabled=false)) {
            DesktopCrashPromptHost(lifecycle.crashPrompt){chooserCalls++;null}
        }}
        ui.await("original startup prompt") {lifecycle.crashPrompt.state.value.pending&&"检测到上次闪退日志" in ui.texts()&&"分享" in ui.texts()&&"关闭" in ui.texts()}
        check(factoryCalls.get()==0&&port.prepared.isEmpty())
        check(ui.texts().any{"不会自动上传或写入公共下载目录" in it})
        ui.save(directory,"01-original-crash-prompt");write(directory.resolve("01-state.json"),stateJson())
        if(case==Case.DISMISS){
            stage="original-dismiss";ui.click("关闭");ui.await("original DISMISS clears only marker"){!lifecycle.crashPrompt.state.value.pending&&!Files.exists(marker)}
            check(factoryCalls.get()==0&&port.prepared.isEmpty()&&Files.exists(snapshot)&&sha(Files.readAllBytes(snapshot))==snapshotHash)
            ui.save(directory,"02-dismiss-marker-only")
        }else {
            if(case==Case.MARKER_RETRY){
                kernel=Native.load("kernel32",MarkerLockKernel32::class.java)
                // GENERIC_READ, SHARE_READ | SHARE_WRITE, OPEN_EXISTING. Delete sharing deliberately absent.
                val absoluteMarker=marker.toAbsolutePath().toString()
                val nativeMarker=if(absoluteMarker.startsWith("\\\\?\\"))absoluteMarker else "\\\\?\\"+absoluteMarker
                lock=kernel!!.CreateFileW(WString(nativeMarker),0x80000000.toInt(),3,null,3,0x80,null)
                check(lock!=null&&Pointer.nativeValue(lock)!=-1L){"Task marker lock was not acquired"}
            }
            stage="original-share";ui.click("分享")
            ui.await("actual SHARE finished controller operation"){lifecycle.crashPrompt.state.value.handled&&!lifecycle.crashPrompt.state.value.busy&&port.prepared.size==1}
            check(factoryCalls.get()==1&&Files.exists(snapshot)&&sha(Files.readAllBytes(snapshot))==snapshotHash)
            ui.save(directory,"02-after-original-share");write(directory.resolve("02-state.json"),stateJson())
            when(case){
                Case.MARKER_RETRY->{
                    stage="marker-delete-failure";check(Files.exists(marker)&&lifecycle.crashPrompt.state.value.pending)
                    check(lifecycle.crashPrompt.state.value.retryAction==CrashLogPromptAction.SHARE)
                    check(lifecycle.crashPrompt.state.value.error=="崩溃日志标记无法清理，原快照已保留。")
                    ui.await("original clear retry visible"){"重试清理" in ui.texts()};ui.save(directory,"03-real-marker-denial-retry")
                    check(kernel!!.CloseHandle(lock!!));lock=null
                    stage="explicit-clear-retry";ui.click("重试清理")
                    ui.await("same original retry clears marker"){!lifecycle.crashPrompt.state.value.pending&&!lifecycle.crashPrompt.state.value.busy&&!Files.exists(marker)&&port.prepared.size==2}
                    check(sha(Files.readAllBytes(snapshot))==snapshotHash&&port.retired.size==2&&port.prepared.none{Files.exists(it)})
                    fallbackViewer();ui.click("关闭");ui.await("viewer dismissed"){!lifecycle.crashPrompt.state.value.viewerRequested}
                }
                Case.UNAVAILABLE->{
                    check(!Files.exists(marker)&&!lifecycle.crashPrompt.state.value.pending&&port.retired.size==1&&port.prepared.none{Files.exists(it)})
                    fallbackViewer(readRetry=true);check(port.prepared.size==1&&factoryCalls.get()==1)
                    // Original retry label is read retry. It does not initiate a new native SHARE.
                    ui.click("关闭");ui.await("viewer dismissed"){!lifecycle.crashPrompt.state.value.viewerRequested}
                }
                Case.TERMINAL->{
                    check(!Files.exists(marker)&&!lifecycle.crashPrompt.state.value.viewerRequested&&lifecycle.crashPrompt.state.value.error==null)
                    val copy=port.prepared.single();check(Files.exists(copy)&&port.retired.isEmpty())
                    write(directory.resolve("03-before-explicit-completion.json"),buildJsonObject {put("copySha256Bytes",sha(Files.readAllBytes(copy)));put("durableState",Files.readString(copy.parent.resolve(".lease")));put("eventNotYetDelivered",true)})
                    stage="explicit-native-completion";port.explicitEvent(3)
                    ui.await("actual native watcher terminal acknowledgment"){port.retired.size==1&&!Files.exists(copy)};actor.flush()
                    check(sha(Files.readAllBytes(snapshot))==snapshotHash&&!lifecycle.crashPrompt.state.value.viewerRequested)
                    ui.save(directory,"03-after-explicit-completion")
                }
                Case.LATE_FAILURE->{
                    check(!Files.exists(marker)&&port.retired.isEmpty());val copy=port.prepared.single();check(Files.exists(copy))
                    stage="explicit-late-failure";port.explicitEvent(5)
                    ui.await("late failure drains actual native owner"){port.retired.size==1&&lifecycle.crashPrompt.state.value.viewerRequested};actor.flush()
                    check(Files.exists(copy)&&Files.readString(copy.parent.resolve(".lease")).startsWith("RETIRED\n"))
                    check(sha(Files.readAllBytes(copy))==snapshotHash);fallbackViewer()
                    stage="viewer-explicit-clear";ui.click("清理日志");ui.await("same actor clear hook acknowledgment"){"本地诊断日志已清理" in ui.texts()&&!Files.exists(copy)&&!Files.exists(snapshot)}
                    check(actor.artifactSize()==0L&&port.retired.size==1);ui.save(directory,"06-explicit-viewer-clear");ui.click("关闭")
                }
                Case.SHUTDOWN->{
                    val copy=port.prepared.single();check(Files.exists(copy)&&port.retired.isEmpty())
                    stage="actual-lifecycle-shutdown";lifecycle.shutdownForRestore();shutdownDone=true;ui.settle()
                    check(port.retired.size==1&&Files.exists(copy)&&Files.readString(copy.parent.resolve(".lease")).startsWith("RETIRED\n"))
                    val before=sha(Files.readAllBytes(copy));port.eventAfterRetirement(5)
                    check(!provider.shareSnapshot());check(lifecycle.crashPrompt.state.value.closed&&!lifecycle.crashPrompt.state.value.viewerRequested)
                    check(!actor.persistLocalCrash(IllegalStateException("late_rejected_task")))
                    check(sha(Files.readAllBytes(copy))==before&&sha(Files.readAllBytes(snapshot))==snapshotHash)
                    ui.save(directory,"03-after-drained-lifecycle")
                }
                else->error("unreachable")
            }
        }
        check(chooserCalls==0)
        stage="final-drain";lifecycle.shutdownForRestore();shutdownDone=true;lifecycle.shutdownForRestore();store.freezeWrites()
        check(!provider.shareSnapshot()&&!actor.record("W","NativeUiTask","retired_write"))
        write(directory.resolve("final-state.json"),stateJson());write(directory.resolve("synthetic-native-transport-events.json"),JsonArray(port.trace.toList()))
        write(directory.resolve("actual-pointer-events.json"),JsonArray(ui.pointers))
        return buildJsonObject {put("passed",true);put("style",style.name);put("themeMode",theme.name);put("case",case.name)
            put("pointerPairs",ui.pointers.size);put("screenshots",ui.screenshots);put("oneActualDiagnosticActor",true);put("actualNativeProviderLifecycleControllerHost",true)
            put("productionOverrides",0);put("syntheticNativeTransport",true);put("actualFileDeletionFailure",case==Case.MARKER_RETRY)
            put("lifecycleDrainedBeforeStoreFreeze",true);put("chooserCalls",chooserCalls);put("HWND",false);put("ShareUI",false);put("receiver",false);put("wholeMainExecuted",false)}
    }catch(failure:Throwable){runCatching{ui.save(directory,"FAILED-current-frame")}
        write(directory.resolve("FAILED-state.json"),stateJson());write(directory.resolve("FAILED-native-events.json"),JsonArray(port.trace.toList()))
        Files.writeString(directory.resolve("FAILED.txt"),"stage=$stage\n"+failure.stackTraceToString());throw failure
    }finally {
        lock?.let{check(kernel!!.CloseHandle(it))};ui.close();if(!shutdownDone)lifecycle.shutdownForRestore();store.freezeWrites()
    }
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output)
    val pins=Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonArray
    for(row in pins){val p=row.jsonObject;val name=p["class"]!!.jsonPrimitive.content;val type=Class.forName(name,false,Thread.currentThread().contextClassLoader)
        check(Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()==Path.of(p["codeSource"]!!.jsonPrimitive.content).toRealPath())
        val bytes=type.getResourceAsStream("/"+name.replace('.','/')+".class")!!.use{it.readAllBytes()};check(sha(bytes)==p["sha256Bytes"]!!.jsonPrimitive.content)
    }
    write(output.resolve("actual-loaded-class-identities.json"),pins)
    val fence=TaskConnectionFence()
    @Suppress("DEPRECATION")
    System.setSecurityManager(fence)
    val checks=mutableListOf<JsonObject>()
    fun report(passed:Boolean,failure:Throwable?=null)=write(output.resolve("ui.json"),buildJsonObject {
        put("passed",passed);put("checks",JsonArray(checks));put("productionOverrides",0);put("ImageComposeScene",true)
        put("syntheticNativeTransport",true);put("HWND",false);put("ShareUI",false);put("receiver",false);put("wholeMainExecuted",false)
        put("socketAndProcessFenceInstalled",true);put("deniedConnectionsOrProcesses",JsonArray(fence.denied.map(::JsonPrimitive)))
        if(failure!=null)put("failure",failure.stackTraceToString())
    })
    try {
        for(style in AppUiStyle.entries)for(theme in listOf(AppThemeMode.LIGHT,AppThemeMode.DARK))for(case in listOf(Case.TERMINAL,Case.UNAVAILABLE,Case.MARKER_RETRY,Case.DISMISS)){
            checks+=runCase(output,style,theme,case,coroutineContext);report(false)
        }
        for(case in listOf(Case.LATE_FAILURE,Case.SHUTDOWN)){checks+=runCase(output,AppUiStyle.MATERIAL3,AppThemeMode.LIGHT,case,coroutineContext);report(false)}
        check(checks.size==18&&fence.denied.isEmpty());report(true);println("PASS actual Main original native SHARE/DISMISS/fallback/retry UI, 4-theme matrix plus explicit late-failure and lifecycle seams; synthetic native transport only")
    }catch(failure:Throwable){report(false,failure);throw failure}
}
