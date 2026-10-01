@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.runtime.tooling.ComposeToolingApi::class)
package com.bilipai.desktop.popupfixture

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.danmaku.*
import com.android.purebilibili.feature.video.ui.overlay.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.*
import com.sun.jna.*
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.*
import java.awt.event.InputEvent
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private interface MouseUser32:StdCallLibrary {
    interface Procedure:StdCallLibrary.StdCallCallback { fun callback(hwnd:Pointer?,message:Int,wparam:Long,lparam:Long):Long }
    fun WindowFromPoint(packed:Long):Pointer?
    fun GetAncestor(hwnd:Pointer?,flags:Int):Pointer?
    fun GetForegroundWindow():Pointer?
    fun IsChild(parent:Pointer?,child:Pointer?):Boolean
    fun IsWindow(hwnd:Pointer?):Boolean
    fun SetWindowLongPtrW(hwnd:Pointer?,index:Int,value:Pointer?):Pointer?
    fun CallWindowProcW(previous:Pointer?,hwnd:Pointer?,message:Int,wparam:Long,lparam:Long):Long
    fun DefWindowProcW(hwnd:Pointer?,message:Int,wparam:Long,lparam:Long):Long
}
private fun <T> edt(action:()->T):T {
    if(SwingUtilities.isEventDispatchThread())return action()
    val task=FutureTask(Callable(action));SwingUtilities.invokeAndWait(task);return task.get()
}
private fun waitUntil(label:String,timeout:Long=8_000,condition:()->Boolean) {
    val deadline=System.nanoTime()+timeout*1_000_000
    while(System.nanoTime()<deadline){if(condition())return;Thread.sleep(45)}
    check(condition()){label}
}
private class NativeMouse(private val api:MouseUser32,private val hwnd:Pointer):AutoCloseable {
    val presses=AtomicInteger()
    private var previous:Pointer?=null
    private val callback=object:MouseUser32.Procedure {
        override fun callback(h:Pointer?,message:Int,wparam:Long,lparam:Long):Long {
            if(message==0x0201)presses.incrementAndGet()
            val original=previous
            return if(original!=null) api.CallWindowProcW(original,h,message,wparam,lparam)
                else api.DefWindowProcW(h,message,wparam,lparam)
        }
    }
    init{ previous=api.SetWindowLongPtrW(hwnd,-4,CallbackReference.getFunctionPointer(callback));check(previous!=null){"Unable to observe task-owned video HWND"} }
    override fun close(){ if(api.IsWindow(hwnd))api.SetWindowLongPtrW(hwnd,-4,previous) }
}
private class VotePlatform(override val context:DesktopPluginContext):DesktopDynamicCardPlatform {
    var reads=0
    override val emotes=object:DesktopDynamicEmotes {
        override fun snapshot()=emptyMap<String,String>()
        override fun currentSessionKey():Any="fixture-only-owner"
        override suspend fun ensureLoaded()=snapshot()
    }
    override fun isOwned()=true
    override suspend fun getVoteInfo(voteId:Long):Result<DynamicVoteInfo>{reads++;check(voteId==70L)
        return Result.success(DynamicVoteInfo(vote_id=70,title="Native original vote dialog",join_num=2,choice_cnt=1,
            options=listOf(DynamicVoteOption(11,"Alpha",1),DynamicVoteOption(29,"Beta",1))))}
    override suspend fun submitVote(voteId:Long,optionIndexes:List<Int>,dynamicId:String)=error("No native proof submission requested")
    private fun unexpected():Nothing=error("Unrelated fixture platform action")
    override fun copyText(text:String)=unexpected();override fun shareText(text:String)=unexpected()
    override fun showFeedback(message:String)=unexpected();override fun openLink(url:String)=unexpected()
    override suspend fun searchUp(name:String)=unexpected()
    override suspend fun saveImage(url:String)=unexpected();override suspend fun saveImages(urls:List<String>)=unexpected()
    override suspend fun saveMotionPhoto(imageUrl:String,videoUrl:String)=unexpected()
    override suspend fun saveLivePhotoVideo(videoUrl:String)=unexpected();override suspend fun shareImage(url:String)=unexpected()
    override suspend fun getShareTargets(size:Int)=unexpected();override suspend fun getMessageSessions(size:Int)=unexpected()
    override suspend fun fetchMessageUserInfo(mid:Long)=unexpected();override suspend fun sendDynamicShare(receiverId:Long,content:String)=unexpected()
}

private class Fixture(private val output:Path,video:Path) {
    private val api=Native.load("user32",MouseUser32::class.java,W32APIOptions.DEFAULT_OPTIONS)
    private val robot=Robot().apply{autoDelay=60}
    private val player=MpvPlayer(useNullAudioOutput=true)
    private val video=Files.copy(video,Files.createTempDirectory("bilipai-command-video-task-").resolve("video.avi"))
    private val context=DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bilipai-command-popup-task-")))
    private val actions=VotePlatform(context)
    private lateinit var window:ComposeWindow
    private var surfaceSize by mutableStateOf(IntSize.Zero)
    private var mounted by mutableStateOf(false)
    private var fixed by mutableStateOf(false)
    private var commands by mutableStateOf(false)
    private var contentWindow:Window?=null
    private var pointerPairs=0
    private var nativeDecodedProof=false
    private val checks=mutableListOf<JsonObject>()
    private val item=CommandDanmakuItem("native-one",CommandDanmakuType.VOTE,"Native actual command",0,1_000,
        voteKind=VoteDanmakuKind.VOTE,voteId="70",voteTitle="Native actual command")
    fun create(){edt {
        window=ComposeWindow().apply {
            title="BiliPai task-owned command hit-test ${UUID.randomUUID()}"
            defaultCloseOperation=javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
            setSize(900,620);setLocation(90,80);isAlwaysOnTop=true
            setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=AppUiStyle.MATERIAL3,hapticFeedbackEnabled=false),systemLanguageTags=listOf("en-US")) {
                    CompositionLocalProvider(LocalDesktopDynamicCardBindings provides actions) {
                        Box(Modifier.fillMaxSize().onSizeChanged{surfaceSize=it}) {
                            SwingPanel(factory={player.surface},modifier=Modifier.fillMaxSize())
                            if(mounted){
                                val content:@Composable ()->Unit = {
                                    val actualAwt=LocalAwtWindow.current
                                    SideEffect{contentWindow=actualAwt}
                                    val density=LocalDensity.current
                                    val viewport=resolveDanmakuViewport(surfaceSize.width,surfaceSize.height,density.density,360f)
                                    val state=rememberCommandDanmakuOverlayState("same-original-dialog-owner")
                                    if(viewport!=null) CommandDanmakuOverlay(if(commands)listOf(item)else emptyList(),player,viewport,state,1f,{},{})
                                }
                                if(fixed)DesktopVideoCommandPopup(surfaceSize,content)else legacyPopup(surfaceSize,content)
                            }
                        }
                    }
                }
            }
            isVisible=true;toFront();requestFocus()
        }
    }
    waitUntil("actual mpv initialization"){player.state.value.ready}
    player.load(PlaybackSource(video.toString(),referer="",cookieHeader="",title="Owned synthetic command video",startPaused=true,startPositionSeconds=0.25))
    waitUntil("actual decoded and paused D3D11 frame"){player.state.value.firstVideoFrameReady && player.state.value.nativePaused==true && player.state.value.videoCodec!=null}
    nativeDecodedProof=true
    }
    private fun owns(w:Window?):Boolean=w!=null && (w===window || owns(w.owner))
    private fun roots()=edt{Window.getWindows().filter{it.isDisplayable && owns(it)}}
    private fun checkPoint(p:Point){
        val current=api.WindowFromPoint((p.x.toLong() and 0xffff_ffffL) or (p.y.toLong() shl 32))
        val root=Pointer.nativeValue(api.GetAncestor(current,2))
        check(roots().any{Pointer.nativeValue(Native.getWindowPointer(it))==root}){"Input target left task-owned windows"}
        check(roots().any{Pointer.nativeValue(Native.getWindowPointer(it))==Pointer.nativeValue(api.GetAncestor(api.GetForegroundWindow(),2))}){"Foreground left owned fixture"}
    }
    private fun click(p:Point){checkPoint(p);robot.mouseMove(p.x,p.y);checkPoint(p)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);pointerPairs++;Thread.sleep(400)}
    private fun point(fx:Double,fy:Double)=edt{val p=player.surface.locationOnScreen;Point(p.x+(player.surface.width*fx).toInt(),p.y+(player.surface.height*fy).toInt())}
    private fun nodes(label:String):List<Pair<Window,SemanticsNode>> = edt {
        fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
        roots().flatMap { w ->
            val owners=when(w){is ComposeWindow->w.semanticsOwners;is ComposeDialog->w.semanticsOwners;else->emptyList()}
            owners.flatMap{tree(it.unmergedRootSemanticsNode)}.filter{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true}.map{w to it}
        }
    }
    private fun click(label:String){waitUntil("missing actual original control $label"){nodes(label).isNotEmpty()}
        val p=edt{val (w,n)=nodes(label).last();val b=n.boundsInWindow;val parent=(w as javax.swing.RootPaneContainer).contentPane.locationOnScreen
            val scale=w.graphicsConfiguration.defaultTransform;Point(parent.x+(b.center.x/scale.scaleX).toInt(),parent.y+(b.center.y/scale.scaleY).toInt())}
        click(p)
    }
    private fun record(s:String,passed:Boolean,details:JsonObject=JsonObject(emptyMap())){
        checks+=buildJsonObject{put("name",s);put("passed",passed);put("details",details)};println("${if(passed)"PASS"else"FAIL"} $s");check(passed){s}}
    fun run(){
        var observed:NativeMouse?=null
        try {
            create()
            val blank=point(0.82,0.8)
            val target=api.WindowFromPoint((blank.x.toLong() and 0xffff_ffffL) or (blank.y.toLong() shl 32))!!
            val native=edt{Native.getComponentPointer(player.surface.components.single())}
            record("real target belongs to actual mpv Canvas HWND",target==native || api.IsChild(native,target))
            observed=NativeMouse(api,target)
            click(blank);waitUntil("native click observer initial response"){observed!!.presses.get()==1}
            edt{mounted=true};Thread.sleep(750)
            val before=observed.presses.get();click(blank)
            val originalBlank=observed.presses.get()>before
            checks+=buildJsonObject{put("name","original full Popup blank diagnostic");put("passed",true);put("blankActuallyPassed",originalBlank)
                put("LocalAwtWindowIsParent",edt{contentWindow===window});put("videoMousePresses",observed.presses.get())}
            edt{mounted=false};Thread.sleep(250);edt{fixed=true;mounted=true};Thread.sleep(850)
            val prior=observed.presses.get();click(blank);waitUntil("fixed blank native click"){observed!!.presses.get()>prior}
            record("empty shaped popup forwards actual Robot click to native video HWND",true)
            edt{commands=true}
            waitUntil("original command rendered"){nodes("Native actual command").isNotEmpty()}
            val host=edt{roots().filterIsInstance<ComposeDialog>().single{it.owner===window}}
            record("native window shape is actual measured card only, never the full video rectangle",edt{
                host.shape!=null && !host.shape.bounds.isEmpty && host.shape.bounds.width<host.width && host.shape.bounds.height<host.height && !host.shape.contains(host.width*.82,host.height*.8)})
            val priorCard=observed.presses.get();click("Native actual command")
            waitUntil("original DynamicVoteDialog opened"){actions.reads==1 && nodes("Native original vote dialog").isNotEmpty()}
            record("card responds above real mpv and opens sole original vote dialog without falling into native video",observed.presses.get()==priorCard)
            ImageIO.write(robot.createScreenCapture(edt{window.bounds}),"png",output.resolve("original-dialog-over-mpv.png").toFile())
            player.seekTo(4.0)
            waitUntil("native seek past command interval"){player.state.value.positionSeconds>3.8}
            waitUntil("original card retires measured region"){edt{host.shape?.bounds?.isEmpty==true}}
            record("expiry removes card region while the already-open original dialog remains",nodes("Native original vote dialog").isNotEmpty() && actions.reads==1)
            click("关闭");waitUntil("original dialog dismissed"){nodes("Native original vote dialog").isEmpty()}
            val after=observed.presses.get();click(blank);waitUntil("blank forwards after original dialog expires"){observed!!.presses.get()>after}
            record("empty region still passes native click after original dialog closes",true)
            edt{mounted=false};waitUntil("owned shaped popup released"){edt{!host.isDisplayable && window.ownedWindows.none{it.isDisplayable}}}
            record("popup disposal releases native window and region",true)
            ImageIO.write(robot.createScreenCapture(edt{window.bounds}),"png",output.resolve("released-popup-native-video.png").toFile())
        } finally {
            observed?.close()
            edt{mounted=false}
            player.close()
            edt{if(::window.isInitialized)window.dispose()}
            Files.writeString(output.resolve("native-proof.json"),buildJsonObject{
                put("preparedOnly",true);put("MainOrCandidateProductAccepted",false);put("oneOwnedNativeFixture",true)
                put("checks",JsonArray(checks));put("pointerPairs",pointerPairs);put("BilibiliHTTP",false)
                put("realMpvDecodedFrame",nativeDecodedProof);put("fakeVoteReadOnly",true)
            }.toString())
        }
    }
}
fun main(args:Array<String>){Fixture(Path.of(args[0]),Path.of(args[1])).run();println("Owned native command popup fixture complete")}
