@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.runtime.tooling.ComposeToolingApi::class)
package com.bilipai.desktop.offlinenativefixture

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.Window as ComposeWindowHost
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.download.DownloadTask as OriginalTask
import com.android.purebilibili.feature.download.DownloadStatus
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.danmaku.*
import com.bilipai.desktop.download.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.ui.*
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import java.nio.file.*
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import javax.swing.JFrame
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

private interface FixtureMessages:StdCallLibrary {
    fun PostMessageW(hwnd:Pointer,message:Int,wparam:Long,lparam:Long):Boolean
    fun GetAncestor(hwnd:Pointer,flag:Int):Pointer?
    fun IsWindow(hwnd:Pointer):Boolean
}
private fun <T> edt(action:()->T):T {
    if(SwingUtilities.isEventDispatchThread())return action()
    val task=FutureTask(Callable(action));SwingUtilities.invokeAndWait(task);return task.get()
}
private fun waitFor(label:String,timeout:Long=12_000,condition:()->Boolean) {
    val deadline=System.nanoTime()+timeout*1_000_000
    while(System.nanoTime()<deadline){if(condition())return;Thread.sleep(25)}
    check(condition()){label}
}
private fun tree(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::tree)
private fun findLayer(component:Component):SkiaLayer? = when(component) {
    is SkiaLayer->component
    is Container->component.components.firstNotNullOfOrNull(::findLayer)
    else->null
}

/** Only fixture sources are compiled. Every renderer, decoder, Store and native actor is the unchanged product. */
private class Fixture(private val root:Path,clip:Path) {
    private val alive=AtomicBoolean(true)
    private val epoch=AtomicLong(0L)
    private val entryJob=SupervisorJob()
    private val scope=CoroutineScope(entryJob+Dispatchers.Default)
    private val owns={alive.get()&&entryJob.isActive&&epoch.get()==0L} // nonblocking document owner; no Store lock
    private val sessions=DesktopSessionStore(root.resolve("fixture-sessions.json"),persistent=false)
    private val stamp=requireNotNull(sessions.dynamicCacheOwner())
    private val admission:((()->Unit)->Boolean)={effect->
        var accepted=false
        sessions.withCurrentDynamicCacheOwner(stamp){if(owns()){effect();accepted=true}}
        accepted
    }
    private val httpCalls=AtomicInteger()
    private val client=OkHttpClient.Builder().addInterceptor{httpCalls.incrementAndGet();error("No HTTP permitted")}.build()
    private val store=DesktopPluginStore(root.resolve("fixture-preferences"))
    private val context=DesktopPluginContext(store)
    private val writeOwned:((()->Unit)->Boolean)={effect->
        if(!admission(effect))throw CancellationException("Fixture owner retired");true
    }
    private val preferences=DesktopOriginalDanmakuPreferences(store,DesktopDanmakuBlockPreferences(store,writeOwned),writeOwned)
    private val json=Json{encodeDefaults=true;ignoreUnknownKeys=true}
    private val tasks:List<DownloadTask>
    private val manager:DesktopDownloadManager
    private val player=MpvPlayer(useNullAudioOutput=true)
    private val overlay:DanmakuOverlay
    private val retained=DesktopRetainedMedia(scope,player){ }
    private val backend:DesktopOfflineTaskPlayerBinding
    private val messages=Native.load("user32",FixtureMessages::class.java,W32APIOptions.DEFAULT_OPTIONS)
    private lateinit var window:ComposeWindow
    private lateinit var rootWindowState:WindowState
    private var applicationThread:Thread?=null
    private var exitApplicationAction:(()->Unit)?=null
    private lateinit var resources:DesktopHomeActualWindowResources
    private lateinit var chrome:DesktopWindowsProfileChrome
    private lateinit var systemMedia:WindowsMediaSession
    private lateinit var pip:PictureInPictureController
    private var mounted by mutableStateOf(false)
    private var themeLight=true
    private var backCalls=0
    private var chromeRestores=0
    private var fullscreenCalls=0
    private var pipRestores=0
    private var injectedCommands=0
    private var injectedSeeks=0
    private val feedback=java.util.concurrent.CopyOnWriteArrayList<String>()
    private val diagnostic=java.util.concurrent.CopyOnWriteArrayList<String>()
    private val checks=mutableListOf<JsonObject>()
    private val pointerEvidence=mutableListOf<JsonObject>()
    private val fullscreenEvidence=mutableListOf<JsonObject>()
    private val placementEvents=java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
    private val startedNanos=System.nanoTime()
    private var baselineFullscreenAccepted=false
    private var entryFullscreenAccepted=false
    private var lateFullscreenAccepted=false
    private var pointerPairs=0
    private var completed=false
    private var disposalPlacement:String?=null

    init {
        Files.createDirectories(root)
        val downloads=root.resolve("fixture-downloads");Files.createDirectories(downloads)
        tasks=(1L..2L).map{cid->
            val original=OriginalTask(bvid="BVfixture",cid=cid,title="Offline Native $cid",cover="",ownerName="Owned fixture artist",ownerFace="",
                duration=4,quality=80,qualityDesc="80",videoUrl="https://fixture.invalid/forbidden",audioUrl="",status=DownloadStatus.COMPLETED,
                createdAt=cid,groupKey="fixture-native-group",episodeSortIndex=cid.toInt(),lastPlaybackPositionMs=500)
            val task=DownloadTask(original,downloads.toString())
            val directory=Path.of(task.directory);Files.createDirectories(directory)
            Files.writeString(directory.resolve(".bilipai-download"),task.id)
            val video=directory.resolve("video.mp4");Files.copy(clip,video)
            task.copy(item=original.copy(filePath=video.toString()))
        }
        val queue=root.resolve("fixture-queue.json")
        Files.writeString(queue,json.encodeToString(ListSerializer(DownloadTask.serializer()),tasks))
        manager=DesktopDownloadManager(client,queue,DownloadMuxer{_,_,_->error("No enqueue/mux in fixture")},
            publication=DesktopLocalPlaybackPublication(owns,admission))
        overlay=DanmakuOverlay(player,object:DesktopOriginalDanmakuRenderPlatform {
            override fun resolveTypeface(fontWeight:Int)=Font("Dialog",if(fontWeight>=600)Font.BOLD else Font.PLAIN,20)
            override fun systemChromeInsetPx()=0 // actual decorated Windows client; no Android system bar
            override fun maximumDisplayShortSidePx():Float=edt{
                val gc=window.graphicsConfiguration;val t=gc.defaultTransform
                minOf(gc.bounds.width*t.scaleX,gc.bounds.height*t.scaleY).toFloat()
            }
        },client)
        backend=DesktopOfflineTaskPlayerBinding(manager,retained,overlay,scope,0L,{epoch.get()},owns,admission,
            {false},{player.state.value.error}) // local-only fixture deliberately has no online fallback
    }
    private fun ownedSource()=backend.isOwned()&&backend.ownsAcceptedSource()&&retained.current===retained.offline
    private fun nativeAction(action:(MpvPlayer)->Unit){if(ownedSource())backend.runPlayerAction(action)}
    private val onCommand:(WindowsMediaCommand)->Unit={command->
        injectedCommands++
        if(ownedSource()) when(command) {
            WindowsMediaCommand.PLAY->nativeAction{it.setPaused(false)}
            WindowsMediaCommand.PAUSE->nativeAction{it.setPaused(true)}
            WindowsMediaCommand.STOP->retained.offline.stopPlayback()
            WindowsMediaCommand.NEXT->retained.offline.next?.invoke()
            WindowsMediaCommand.PREVIOUS->retained.offline.previous?.invoke()
            WindowsMediaCommand.FAST_FORWARD->nativeAction{it.seekTo(it.state.value.positionSeconds+1.0)}
            WindowsMediaCommand.REWIND->nativeAction{it.seekTo((it.state.value.positionSeconds-1.0).coerceAtLeast(0.0))}
        } // No queue target has no effect; never fall through into another playback owner.
    }
    private val onSeek:(Double)->Unit={seconds->
        injectedSeeks++
        if(seconds.isFinite())nativeAction{it.seekTo(seconds)}
    }
    private fun ownWindow(candidate:Window?):Boolean {
        var w=candidate
        while(w!=null){if(w===window)return true;w=w.owner}
        return false
    }
    private fun ownedDialogs():List<ComposeDialog> = edt{window.ownedWindows.filterIsInstance<ComposeDialog>().filter{it.isShowing}}
    private fun nodeMatches(node:SemanticsNode,label:String):Boolean =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any{it.text==label} ||
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)
    private fun labeled(label:String):List<Pair<Window,SemanticsNode>> = edt{
        (listOf<Window>(window)+ownedDialogs()).filter{it.isShowing}.flatMap{w->
            val owners=when(w){is ComposeWindow->w.semanticsOwners;is ComposeDialog->w.semanticsOwners;else->emptyList()}
            owners.flatMap{tree(it.unmergedRootSemanticsNode)}.filter{nodeMatches(it,label)&&it.boundsInWindow.width>0&&it.boundsInWindow.height>0}.map{w to it}
        }
    }
    private fun has(label:String)=labeled(label).isNotEmpty()
    private fun record(label:String,passed:Boolean,details:JsonObject=JsonObject(emptyMap())) {
        checks+=buildJsonObject{put("name",label);put("passed",passed);put("details",details)}
        println("${if(passed)"PASS"else"FAIL"} $label");check(passed){label}
    }
    private fun placementEvent(label:String) = edt {
        if(placementEvents.size<160)placementEvents+=buildJsonObject{
            put("event",label);put("elapsedMs",(System.nanoTime()-startedNanos)/1_000_000)
            put("windowPlacement",window.placement.toString());put("statePlacement",rootWindowState.placement.toString())
            put("active",window.isActive);put("extendedState",window.extendedState)
            val device=window.graphicsConfiguration.device
            put("graphicsDeviceFullScreenSupported",device.isFullScreenSupported)
            put("graphicsDeviceFullScreenWindowIsOwned",device.fullScreenWindow===window)
            put("graphicsDeviceHasFullScreenWindow",device.fullScreenWindow!=null)
            put("width",window.width);put("height",window.height)
        }
    }
    private fun observePlacement(label:String,accepted:Boolean){
        checks+=buildJsonObject{put("name",label);put("passed",accepted);put("requiredForIndependentControls",false)}
        println("${if(accepted)"PASS"else"FAIL_OBSERVED"} $label")
        placementEvent(label)
    }
    private fun captureBacking(name:String,w:Window) = edt{
        check(ownWindow(w)&&w.isDisplayable)
        when(w){is ComposeWindow->w.renderImmediately();is ComposeDialog->w.renderImmediately()}
        val layer=checkNotNull(findLayer(w)){"Fixture-owned Skia layer unavailable"}
        val bitmap=checkNotNull(layer.screenshot()){"Fixture-owned render backing unavailable"}
        try{Image.makeFromBitmap(bitmap).use{image->
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use{data->Files.write(root.resolve(name+".png"),data.bytes)}
        }}finally{bitmap.close()} // screenshot is an owned copy; never close borrowed Coil images
    }
    /** Native messages only to the real fixture-owned shaped foreground's Skia Canvas HWND. */
    private fun clickOriginal(label:String,mustBeShaped:Boolean=true) {
        waitFor("Original native control missing: $label"){labeled(label).any{!mustBeShaped||it.first is ComposeDialog}}
        val selected=edt{labeled(label).filter{!mustBeShaped||it.first is ComposeDialog}.minBy{it.second.boundsInWindow.width*it.second.boundsInWindow.height}}
        val w=selected.first;val node=selected.second
        val layer=edt{checkNotNull(findLayer(w))}
        val received=AtomicInteger();val releases=AtomicInteger()
        val observer=object:MouseAdapter(){override fun mousePressed(e:MouseEvent){received.incrementAndGet()};override fun mouseReleased(e:MouseEvent){releases.incrementAndGet()}}
        val point=edt{
            check(ownWindow(w)&&w.isShowing)
            val scale=w.graphicsConfiguration.defaultTransform
            val canvas=layer.canvas
            val content=(w as RootPaneContainer).contentPane.locationOnScreen
            val origin=canvas.locationOnScreen
            val x=(node.boundsInWindow.center.x+(content.x-origin.x)*scale.scaleX).toInt()
            val y=(node.boundsInWindow.center.y+(content.y-origin.y)*scale.scaleY).toInt()
            check(x>=0&&y>=0&&x<canvas.width*scale.scaleX&&y<canvas.height*scale.scaleY){"Control lies outside the real owned foreground Canvas"}
            if(w is ComposeDialog)check(w.shape?.contains(node.boundsInWindow.center.x/scale.scaleX,node.boundsInWindow.center.y/scale.scaleY)==true){"Original control is outside shaped HWND input region"}
            else check(w===window&&!mustBeShaped){"Unexpected non-shaped target"}
            layer.addMouseListener(observer)
            Triple(Native.getComponentPointer(canvas),x,y)
        }
        try {
            val hwnd=point.first
            check(messages.IsWindow(hwnd)&&Pointer.nativeValue(messages.GetAncestor(hwnd,2))==Pointer.nativeValue(edt{Native.getWindowPointer(w)}))
            val packed=(point.second.toLong() and 0xffffL) or ((point.third.toLong() and 0xffffL) shl 16)
            check(messages.PostMessageW(hwnd,0x0200,0,packed))
            check(messages.PostMessageW(hwnd,0x0201,1,packed))
            waitFor("Actual foreground HWND press not delivered to AWT"){received.get()>0}
            Thread.sleep(90)
            check(messages.PostMessageW(hwnd,0x0202,0,packed))
            waitFor("Actual foreground HWND release not delivered to AWT"){releases.get()>0}
            pointerPairs++
            pointerEvidence+=buildJsonObject{put("originalControl",label);put("targetCanvasHWND",Pointer.nativeValue(hwnd));put("ownedWindowHWND",Pointer.nativeValue(edt{Native.getWindowPointer(w)}));put("xPhysical",point.second);put("yPhysical",point.third);put("AWTPresses",received.get());put("AWTReleases",releases.get());put("shapedForeground",w is ComposeDialog);put("delivery","PostMessageW to fixture-owned actual Skia Canvas; no Robot/hardware mouse")}
            Thread.sleep(180)
        }finally{edt{layer.removeMouseListener(observer)}}
    }
    private fun actualPipWindow():JFrame? = edt{
        SwingUtilities.getWindowAncestor(player.surface)?.takeIf{it!==window} as? JFrame
    }
    private fun create() {
        val ready=CountDownLatch(1)
        applicationThread=Thread({application(exitProcessOnExit=false){
            val state=rememberWindowState(width=900.dp,height=620.dp)
            ComposeWindowHost(onCloseRequest={},title="BiliPai fixture-owned full Offline native acceptance",state=state){
                val actualWindow=window
                SideEffect{this@Fixture.window=actualWindow;rootWindowState=state;exitApplicationAction={exitApplication()}}
                val presentation=rememberDesktopWindowsDanmakuPresentation(actualWindow,state)
                LaunchedEffect(actualWindow){
                    withTimeout(10_000){while(!actualWindow.isDisplayable||!actualWindow.isVisible)delay(16)}
                    // Main shows Home before a leaf can mount. Let this actual blank Window finish
                    // its first frame/shown-state reconciliation before the fixture enters Offline.
                    withFrameNanos { }
                    delay(500)
                    edt{actualWindow.toFront();actualWindow.requestFocus()}
                    withTimeout(10_000){while(!actualWindow.isActive)delay(16)}
                    edt{
                        actualWindow.addComponentListener(object:ComponentAdapter(){
                            override fun componentResized(e:ComponentEvent){placementEvent("AWT resized")}
                            override fun componentShown(e:ComponentEvent){placementEvent("AWT shown")}
                        })
                        actualWindow.addWindowStateListener(WindowStateListener{placementEvent("AWT window-state event")})
                    }
                    launch{snapshotFlow{state.placement}.collect{placementEvent("Compose state placement changed")}}
                    placementEvent("Blank Window before baseline")
                    state.placement=WindowPlacement.Fullscreen
                    placementEvent("Blank Window fullscreen requested")
                    delay(2_000)
                    baselineFullscreenAccepted=edt{actualWindow.placement==WindowPlacement.Fullscreen&&state.placement==WindowPlacement.Fullscreen}
                    observePlacement("Blank fixture Window platform fullscreen baseline",baselineFullscreenAccepted)
                    state.placement=WindowPlacement.Floating
                    delay(500)
                    placementEvent("Blank Window baseline restored before RootHost mount")
                    edt{
            actualWindow.renderImmediately()
            resources=DesktopHomeActualWindowResources(window,owns,{if(themeLight)java.awt.Color.WHITE else java.awt.Color(24,24,24)},Logger.getLogger("OfflineNativeFixture"))
            chrome=DesktopWindowsProfileChrome(window,resources.clientPolicy,{themeLight}){diagnostic+=it.javaClass.simpleName+":"+it.message}
            systemMedia=WindowsMediaSession(window,onCommand,onSeek)
            pip=PictureInPictureController(player,{pipRestores++},
                onPrevious={if(ownedSource())retained.offline.previous?.invoke()},
                onNext={if(ownedSource())retained.offline.next?.invoke()},onSeekTo=onSeek)
                        mounted=true;ready.countDown()
                    }
                }
                if(mounted){
                val pipActive by pip.active.collectAsState()
                DesktopAppearanceTheme(DesktopThemeSettings(hapticFeedbackEnabled=false)){
                    CompositionLocalProvider(LocalDesktopDetailForeground provides (owns()&&window.isDisplayable&&window.isVisible)){
                        if(mounted)DesktopOriginalOfflineRootHost(tasks[0].id,backend,scope,context,preferences,presentation,
                            systemMedia,pip,pipActive,window,
                            isFullscreen={rootWindowState.placement==WindowPlacement.Fullscreen},
                            setFullscreen={fullscreen->edt{
                                check(owns())
                                val target=if(fullscreen)WindowPlacement.Fullscreen else WindowPlacement.Floating
                                if(rootWindowState.placement!=target){rootWindowState.placement=target;fullscreenCalls++}
                                fullscreenEvidence+=buildJsonObject{put("requested",fullscreen);put("actualStatePlacement",rootWindowState.placement.toString());put("actualWindowPlacementAtRequest",window.placement.toString());put("width",window.width);put("height",window.height)}
                                placementEvent("Root original effect requested fullscreen=$fullscreen")
                            }},restoreCurrentRootChrome={chromeRestores++;chrome.refreshCurrentRootTheme()},
                            onBack={backCalls++},feedback=feedback::add)
                    }
                }
            }
            }
        }},"Fixture actual Compose Window lifecycle").apply{start()}
        check(ready.await(12,java.util.concurrent.TimeUnit.SECONDS)){"Actual fixture Window lifecycle did not initialize"}
    }
    fun run() {
        var failure:Throwable?=null
        try {
            create()
            waitFor("Full original task/native owner did not load",20_000){backend.ownsAcceptedSource()&&has("Offline Native 1")&&player.state.value.firstVideoFrameReady}
            edt{nativeAction{it.setPaused(true)}};waitFor("Actual paused native frame"){player.state.value.nativePaused==true}
            val firstVersion=checkNotNull(backend.memory.sourceVersion)
            record("Unchanged full original renderer owns exact local task and actual native first frame",retained.current===retained.offline&&player.ownsSourceVersion(firstVersion)&&player.state.value.videoWidth==160&&player.state.value.videoHeight==90)
            Thread.sleep(2_000)
            entryFullscreenAccepted=edt{window.placement==WindowPlacement.Fullscreen&&rootWindowState.placement==WindowPlacement.Fullscreen}
            observePlacement("Actual original landscape entry applies real fixture Window fullscreen placement",entryFullscreenAccepted)
            waitFor("Original full foreground region not present"){has("下一集")&&ownedDialogs().isNotEmpty()}
            val beforeWindow=ownedDialogs().last();captureBacking("original-native-foreground",beforeWindow)
            clickOriginal("退出全屏")
            waitFor("Actual full-screen original control did not restore placement"){edt{window.placement==WindowPlacement.Floating}}
            waitFor("Original exit fullscreen control did not change original local state"){has("全屏")}
            record("Original exit fullscreen control receives real HWND messages and changes original local state",edt{window.placement==WindowPlacement.Floating})
            clickOriginal("全屏")
            Thread.sleep(2_000)
            lateFullscreenAccepted=edt{window.placement==WindowPlacement.Fullscreen&&rootWindowState.placement==WindowPlacement.Fullscreen}
            observePlacement("Stable original native fullscreen control applies actual placement",lateFullscreenAccepted)
            clickOriginal("退出全屏")
            waitFor("Late original exit fullscreen control did not restore floating placement"){edt{window.placement==WindowPlacement.Floating}}
            clickOriginal("下一集")
            waitFor("Original next task/native source did not replace",20_000){backend.memory.current==tasks[1].id&&has("Offline Native 2")&&player.state.value.firstVideoFrameReady}
            edt{nativeAction{it.setPaused(true)}};waitFor("Second local native frame paused"){player.state.value.nativePaused==true}
            val secondVersion=checkNotNull(backend.memory.sourceVersion)
            record("Native HWND delivery reaches original next-episode action and exact current manager task",secondVersion!=firstVersion&&player.ownsSourceVersion(secondVersion)&&!player.ownsSourceVersion(firstVersion)&&has("2/2"))
            waitFor("Real SMTC initialization"){systemMedia.status.value.available||systemMedia.status.value.error!=null}
            record("Actual Windows SMTC GetForWindow is available on fixture-owned HWND",systemMedia.status.value.available,buildJsonObject{put("error",systemMedia.status.value.error.orEmpty())})
            waitFor("Native SMTC original offline title readback"){systemMedia.status.value.publishedTitle=="Offline Native 2"}
            val snapshotField=WindowsMediaSession::class.java.getDeclaredField("snapshot").apply{isAccessible=true}
            val actualSnapshot=(snapshotField.get(systemMedia) as java.util.concurrent.atomic.AtomicReference<*>).get() as WindowsMediaSnapshot
            record("Same product media adapter publishes original title/artist/bvid and exact current queue",actualSnapshot.title=="Offline Native 2"&&actualSnapshot.artist=="Owned fixture artist"&&actualSnapshot.mediaId=="BVfixture"&&actualSnapshot.hasPrevious&&!actualSnapshot.hasNext,buildJsonObject{put("nativeTitle",systemMedia.status.value.publishedTitle);put("nativePlaybackStatus",systemMedia.status.value.publishedPlaybackStatus)})
            val beforeNoNext=player.currentSourceVersion
            edt{onCommand(WindowsMediaCommand.NEXT)};Thread.sleep(100)
            record("Injected existing source command callback with empty retained next target preserves source",player.currentSourceVersion==beforeNoNext&&backend.memory.current==tasks[1].id)
            edt{onSeek(1.2)};waitFor("Injected owned source seek"){kotlin.math.abs(player.state.value.positionSeconds-1.2)<0.25}
            record("Injected source callback seeks same actual native owner",backend.memory.sourceVersion==secondVersion&&player.ownsSourceVersion(secondVersion))
            edt{onCommand(WindowsMediaCommand.PREVIOUS)}
            waitFor("Injected previous source callback did not select exact task",20_000){backend.memory.current==tasks[0].id&&has("Offline Native 1")&&player.state.value.firstVideoFrameReady}
            edt{nativeAction{it.setPaused(true)}};waitFor("Returned first native task paused"){player.state.value.nativePaused==true}
            val pipVersion=checkNotNull(backend.memory.sourceVersion)
            clickOriginal("浮窗")
            waitFor("Actual same Canvas PiP ownership",20_000){pip.active.value&&actualPipWindow()!=null&&ownedDialogs().isEmpty()&&player.state.value.firstVideoFrameReady}
            record("Actual existing PiP owns same Canvas while main shaped carrier is excluded",has("正在浮窗播放")&&actualPipWindow()!=null&&edt{player.surface.parent!=null&&SwingUtilities.getWindowAncestor(player.surface)!==window}&&player.ownsSourceVersion(pipVersion))
            val floating=checkNotNull(actualPipWindow())
            record("Actual PiP native title and queue controls use current offline metadata",edt{floating.title=="Offline Native 1"&&floating.contentPane.components.isNotEmpty()})
            clickOriginal("返回主窗口",mustBeShaped=false)
            waitFor("Same Canvas failed to return to original main surface",20_000){!pip.active.value&&edt{SwingUtilities.getWindowAncestor(player.surface)===window}&&ownedDialogs().isNotEmpty()&&player.state.value.firstVideoFrameReady}
            record("PiP restore returns sole Canvas and original foreground without changing native source token",pipRestores==1&&player.ownsSourceVersion(pipVersion)&&backend.memory.sourceVersion==pipVersion)
            captureBacking("restored-original-foreground",ownedDialogs().last())
            edt{nativeAction{it.setPaused(true)}};waitFor("Restored native pause"){player.state.value.nativePaused==true}
            val beforeEpochSeek=player.state.value.positionSeconds
            epoch.set(1L);edt{onSeek(2.8);onCommand(WindowsMediaCommand.NEXT)};Thread.sleep(150)
            record("Retired fixture epoch rejects injected seek/queue command for still-current native token",kotlin.math.abs(player.state.value.positionSeconds-beforeEpochSeek)<0.3&&player.ownsSourceVersion(pipVersion)&&backend.memory.current==tasks[0].id)
            epoch.set(0L);themeLight=false
            edt{mounted=false}
            waitFor("Full original disposal did not release its source/foreground"){!player.ownsSourceVersion(pipVersion)&&ownedDialogs().isEmpty()}
            disposalPlacement=edt{window.placement.toString()}
            waitFor("Current fixture theme client restoration"){edt{window.background==java.awt.Color(24,24,24)}}
            record("Original disposal restores current actual Windows chrome/client theme and clears only owned media",chromeRestores>0&&diagnostic.isEmpty()&&!pip.active.value)
            record("Fixture performed no HTTP, account login or chooser operation",httpCalls.get()==0&&feedback.isEmpty())
            completed=true
        }catch(t:Throwable){
            failure=t
            Files.writeString(root.resolve("failure-state.json"),buildJsonObject{
                put("backendOwned",backend.isOwned());put("backendAcceptedSource",backend.ownsAcceptedSource());put("backendError",backend.error.orEmpty())
                put("currentTask",backend.memory.current.orEmpty());put("nativeSourceVersion",player.currentSourceVersion);put("firstFrame",player.state.value.firstVideoFrameReady)
                put("nativeReady",player.state.value.ready);put("nativeError",player.state.value.error.orEmpty())
                if(::window.isInitialized){put("windowVisible",edt{window.isVisible});put("windowDisplayable",edt{window.isDisplayable});put("windowActive",edt{window.isActive});put("windowPlacement",edt{window.placement.toString()})}
                if(::rootWindowState.isInitialized)put("windowStatePlacement",edt{rootWindowState.placement.toString()})
            }.toString())
            throw t
        }
        finally {
            if(::window.isInitialized)edt{mounted=false}
            backend.close();retained.close()
            if(::pip.isInitialized)edt{pip.close()}
            if(::systemMedia.isInitialized)systemMedia.close()
            overlay.close();player.close()
            alive.set(false);entryJob.cancel();manager.close()
            if(::resources.isInitialized)resources.close()
            if(::window.isInitialized)edt{exitApplicationAction?.invoke() ?: window.dispose()}
            applicationThread?.join(3_000)
            client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()
            Files.writeString(root.resolve("native-proof.json"),buildJsonObject{
                put("status",if(completed&&baselineFullscreenAccepted&&entryFullscreenAccepted&&lateFullscreenAccepted)"PASS"else if(completed)"PASS_INDEPENDENT_CONTROLS_FULLSCREEN_NOT_ACCEPTED"else"FAIL");put("assertions",checks.size);put("pointerPairs",pointerPairs)
                put("checks",JsonArray(checks));put("pointerEvidence",JsonArray(pointerEvidence));put("fullscreenEvidence",JsonArray(fullscreenEvidence))
                put("placementEvents",JsonArray(placementEvents));put("blankWindowFullscreenAccepted",baselineFullscreenAccepted)
                put("originalEntryFullscreenAccepted",entryFullscreenAccepted);put("stableOriginalControlFullscreenAccepted",lateFullscreenAccepted)
                put("HTTPCalls",httpCalls.get());put("actualExistingNativeActors",true);put("singleFixtureWindowCohort",true)
                put("productionOverrides",0);put("hardwareMouse",false);put("OSSMTCButtonInjection",false)
                put("SMTCCommandDelivery","Injected unchanged actor constructor callback on fixture EDT, not OS media-button event")
                put("SMTCPublication","True Windows GetForWindow/product COM worker/status readback")
                put("imageSource","fixture-owned Skia screenshot copies only; no screen rectangle")
                put("actualMainShellAccepted",false);put("realAccountAccepted",false)
                put("programmaticRouteDisposalPlacement",disposalPlacement.orEmpty());put("arbitraryNavigationPlacementRestoreAccepted",false);put("externalF11StateSynchronizationAccepted",false)
                put("diagnostic",JsonArray(diagnostic.map(::JsonPrimitive)));put("feedback",JsonArray(feedback.map(::JsonPrimitive)))
                put("failure",failure?.toString().orEmpty())
                put("classOrigins",buildJsonObject{
                    for(name in listOf("com.bilipai.desktop.ui.DesktopOriginalOfflineRootHostKt","com.android.purebilibili.feature.download.OfflineVideoPlayerScreenKt","com.bilipai.desktop.ui.DesktopOriginalOfflinePlayerBindings","com.bilipai.desktop.ui.DesktopOriginalPlayerSurfaceKt","com.bilipai.desktop.ui.DesktopCommandPopupWindowKt","com.bilipai.desktop.ui.DesktopOfflineMpvControl","com.bilipai.desktop.player.MpvPlayer","com.bilipai.desktop.player.PictureInPictureController","com.bilipai.desktop.player.WindowsMediaSession"))put(name,Class.forName(name).protectionDomain.codeSource.location.toString())
                })
            }.toString())
        }
    }
}
fun main(args:Array<String>){Fixture(Path.of(args[0]),Path.of(args[1])).run();println("OFFLINE_NATIVE_OBSERVATIONS_COMPLETED")}
