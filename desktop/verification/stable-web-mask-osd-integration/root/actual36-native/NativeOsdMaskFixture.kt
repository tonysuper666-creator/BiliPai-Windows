@file:Suppress("DEPRECATION")
package com.bilipai.desktop.danmaku.nativeosdproof

import com.android.purebilibili.danmaku.engine.DanmakuMaskFrame
import com.bilipai.desktop.danmaku.*
import com.bilipai.desktop.player.*
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import java.awt.Color
import java.awt.EventQueue
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.Permission
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt

private class LocalOnlyFence:SecurityManager(){
    val networkAttempts=AtomicInteger()
    override fun checkPermission(permission:Permission){}
    override fun checkConnect(host:String?,port:Int){networkAttempts.incrementAndGet();error("Native OSD fixture forbids Java network")}
    override fun checkConnect(host:String?,port:Int,context:Any?)=checkConnect(host,port)
    override fun checkListen(port:Int){networkAttempts.incrementAndGet();error("Native OSD fixture forbids listeners")}
    override fun checkExec(command:String){error("Native OSD JVM cannot execute subprocesses")}
}
private interface OsdFixtureUser32:StdCallLibrary {
    fun GetClientRect(hwnd:Pointer,rectangle:OsdFixtureRect):Boolean
    fun GetDpiForWindow(hwnd:Pointer):Int
}
@Structure.FieldOrder("left","top","right","bottom")
internal class OsdFixtureRect:Structure(){
    @JvmField var left=0;@JvmField var top=0;@JvmField var right=0;@JvmField var bottom=0
}
private fun <T> edt(body:()->T):T {
    if(SwingUtilities.isEventDispatchThread())return body()
    val task=FutureTask(Callable(body));EventQueue.invokeAndWait(task);return task.get()
}
private inline fun <T> Graphics2D.usePaint(body:(Graphics2D)->T):T=try{body(this)}finally{dispose()}
private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
private fun nativeThreads()=Thread.getAllStackTraces().keys.filter{it.isAlive&&it.name in setOf("BiliPai-native-player","BiliPai-mpv-software-render")}.map{it.id}.toSet()
private val checks=mutableListOf<String>()
private fun verify(label:String,value:Boolean){check(value){label};checks+=label;println("PASS $label")}
private suspend fun until(label:String,player:MpvPlayer?=null,condition:()->Boolean){
    try{withTimeout(15_000){while(!condition()){check(player?.state?.value?.error==null){"Native player failed: ${player?.state?.value?.error}"};delay(15)}}}
    catch(failure:TimeoutCancellationException){error("Timed out: $label; state=${player?.state?.value}; output=${player?.videoOutput?.value}")}
}
private data class MeasuredCanvas(val logicalWidth:Int,val logicalHeight:Int,val physicalWidth:Int,val physicalHeight:Int,val scaleX:Double,val scaleY:Double,val dpi:Int,val monitorShortSide:Int)
private fun measure(player:MpvPlayer,window:JFrame,user32:OsdFixtureUser32)=edt {
    val canvas=player.surface.components.single()
    val hwnd=Native.getComponentPointer(canvas)
    val r=OsdFixtureRect();check(user32.GetClientRect(hwnd,r))
    val transform=requireNotNull(window.graphicsConfiguration).defaultTransform
    val mode=window.graphicsConfiguration.device.displayMode
    MeasuredCanvas(canvas.width,canvas.height,r.right-r.left,r.bottom-r.top,transform.scaleX,transform.scaleY,user32.GetDpiForWindow(hwnd),minOf(mode.width,mode.height))
}
private fun viewportJson(v:PlayerVideoViewport)=buildJsonObject {
    put("osdWidth",v.osdWidth);put("osdHeight",v.osdHeight);put("left",v.left);put("top",v.top);put("contentWidth",v.contentWidth);put("contentHeight",v.contentHeight)
}
private fun measuredJson(m:MeasuredCanvas)=buildJsonObject {
    put("logicalWidth",m.logicalWidth);put("logicalHeight",m.logicalHeight);put("physicalWidth",m.physicalWidth);put("physicalHeight",m.physicalHeight);put("defaultScaleX",m.scaleX);put("defaultScaleY",m.scaleY);put("GetDpiForWindow",m.dpi);put("physicalMonitorShortSide",m.monitorShortSide)
}
private fun frameFor(player:MpvPlayer):DanmakuMaskFrame {
    val w=player.state.value.videoWidth;val h=player.state.value.videoHeight;check(w>0&&h>0)
    val svg="<svg viewBox='0 0 $w $h'><path d='M${w*.25} ${h*.25} H${w*.75} V${h*.75} H${w*.25} Z'/></svg>"
    val raw=ByteArray(16)+("data:image/svg+xml;base64,"+Base64.getEncoder().encodeToString(svg.toByteArray())).toByteArray()
    val zipped=ByteArrayOutputStream().also{out->GZIPOutputStream(out).use{it.write(raw)}}.toByteArray()
    val bytes=ByteBuffer.allocate(32+zipped.size).order(ByteOrder.BIG_ENDIAN).apply{put("MASK".toByteArray());putInt(1);putInt(0);putInt(1);putLong(0);putLong(32);put(zipped)}.array()
    val frame=com.android.purebilibili.feature.video.danmaku.WebMaskParser.parseWindow(bytes,0,0,10_000).single()
    verify("installed original full MASK/gzip/base64/SVG parser retains native source viewBox",frame.sourceWidth==w&&frame.sourceHeight==h&&frame.startTimeMs==0L&&frame.endTimeMs==10_000L)
    return frame
}
private fun verifyClip(label:String,player:MpvPlayer,v:PlayerVideoViewport,m:MeasuredCanvas,output:Path):JsonObject {
    val frame=frameFor(player)
    val width=m.physicalWidth;val height=m.physicalHeight;check(width>0&&height>0)
    val shape=frame.path.transformedArea(v.sourceToPhysicalTransform(width,height,frame.sourceWidth,frame.sourceHeight))
    val expectedX=(v.left+v.contentWidth*.5)*width/v.osdWidth
    val expectedY=(v.top+v.contentHeight*.5)*height/v.osdHeight
    val point=v.sourceToPhysicalTransform(width,height,frame.sourceWidth,frame.sourceHeight).transform(Point2D.Double(frame.sourceWidth*.5,frame.sourceHeight*.5),null)
    verify("$label uses actual OSD margins and physical client dimensions",abs(point.x-expectedX)<0.00001&&abs(point.y-expectedY)<0.00001)
    val image=BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB)
    image.createGraphics().usePaint { root ->
        root.color=Color.RED;root.fillRect(0,0,width,height)
        (root.create() as Graphics2D).usePaint { standard ->
            applyDesktopWebMaskClip(standard,width,height,frame,v)
            standard.color=Color.BLUE;standard.fillRect(0,0,width,height)
        }
        // Existing full AdvancedDanmakuRenderer, same order after the standard-only child clip.
        val authored=DanmakuParser.parseDocument("""<i><d p="0,7,30,65280">[${expectedX/width-.04},${expectedY/height},"1-1",3,"LAYER"]</d></i>""")
        AdvancedDanmakuRenderer(authored.advanced).paint(root,1000,width,height,1f,DanmakuSettings(opacity=1f,strokeEnabled=false))
    }
    var masked=0;var unmasked=0;var laterInside=0
    for(y in 0 until height)for(x in 0 until width){
        val rgb=image.getRGB(x,y);val inside=shape.contains(x+.5,y+.5)
        if(inside&&rgb==Color.RED.rgb)masked++
        if(!inside&&rgb==Color.BLUE.rgb)unmasked++
        val c=Color(rgb,true)
        if(inside&&c.green>190&&c.red<50&&c.blue<50)laterInside++
    }
    verify("$label actual standard child preserves mapped mask pixels",masked>100)
    verify("$label actual standard child continues outside mapped mask",unmasked>100)
    verify("$label original advanced glyphs remain visible inside mask",laterInside>5)
    ImageIO.write(image,"png",output.resolve("$label-java2d.png").toFile())
    val noRect=BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB)
    noRect.createGraphics().usePaint {g->applyDesktopWebMaskClip(g,width,height,frame,null);g.color=Color.BLUE;g.fillRect(0,0,width,height)}
    verify("$label absent real rectangle skips optional mask",noRect.getRGB(width/2,height/2)==Color.BLUE.rgb)
    return buildJsonObject {put("phase",label);put("nativeViewport",viewportJson(v));put("canvas",measuredJson(m));put("sourceViewBoxWidth",frame.sourceWidth);put("sourceViewBoxHeight",frame.sourceHeight);put("maskedPixels",masked);put("unmaskedPixels",unmasked);put("originalAdvancedPixelsInsideMask",laterInside);put("nativeSourceVersion",player.currentSourceVersion)}
}

/** No production override, direct native client, HTTP or Root UI. Execute only with Root's installed immutable graph. */
fun main(args:Array<String>)=runBlocking {
    require(args.size==3);check(!GraphicsEnvironment.isHeadless())
    val video=Path.of(args[0]).toAbsolutePath().normalize();val replacement=Path.of(args[1]).toAbsolutePath().normalize();val output=Path.of(args[2]).toAbsolutePath().normalize()
    require(Files.isRegularFile(video)&&Files.isRegularFile(replacement));Files.createDirectories(output)
    val fence=LocalOnlyFence();System.setSecurityManager(fence)
    val baseline=nativeThreads();val player=MpvPlayer(useNullAudioOutput=true)
    val user32=Native.load("user32",OsdFixtureUser32::class.java,W32APIOptions.DEFAULT_OPTIONS)
    val observed=CopyOnWriteArrayList<PlayerVideoOutputState>()
    val observer=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    val observerJob=observer.launch(start=CoroutineStart.UNDISPATCHED){player.videoOutput.collect{observed+=it}}
    var window:JFrame?=null;val phases=mutableListOf<JsonObject>();var firstFrameElapsed=0L
    try {
        val own=edt {JFrame().apply {isUndecorated=true;defaultCloseOperation=JFrame.DO_NOTHING_ON_CLOSE;contentPane.add(player.surface);setSize(600,480);addNotify();validate();check(isDisplayable&&!isVisible)}}
        window=own
        verify("real task-owned HWND stays hidden",edt{own.isDisplayable&&!own.isVisible&&player.surface.components.single().isDisplayable})
        player.setMuted(true);player.setLoop(true);player.setSubtitlesVisible(false);player.setVideoPanscan(0.0)
        val started=System.nanoTime();val first=player.loadVersioned(PlaybackSource(video.toString(),referer="",cookieHeader="",title="Local native OSD proof",startPaused=true,startPositionSeconds=.25))
        until("actual first decoded frame and same-source OSD",player){player.state.value.firstVideoFrameReady&&player.state.value.nativePaused==true&&player.state.value.videoCodec!=null&&player.videoOutput.value.sourceVersion==first&&player.videoOutput.value.viewport!=null}
        firstFrameElapsed=System.nanoTime()-started
        verify("first load explicitly publishes empty bounds for its new source",observed.any{it.sourceVersion==first&&it.viewport==null&&it.displayWidth==0&&it.displayHeight==0})
        verify("first native frame has a current source receipt",player.ownsSourceVersion(first)&&player.state.value.videoWidth>0&&player.state.value.videoHeight>0)
        val nativeFrame=player.captureScreenshot(output.resolve("native-decoded-frame.png"),includeSubtitles=false)
        val decoded=requireNotNull(ImageIO.read(nativeFrame.toFile()))
        verify("actual libmpv screenshot returns decoded source dimensions",decoded.width==player.state.value.videoWidth&&decoded.height==player.state.value.videoHeight)
        val measured=measure(player,own,user32);val fit=requireNotNull(player.videoOutput.value.viewport)
        verify("native OSD dimensions equal actual Canvas client pixels",fit.osdWidth==measured.physicalWidth&&fit.osdHeight==measured.physicalHeight)
        val geometry=requireNotNull(DesktopDanmakuPaintGeometry.from(measured.logicalWidth,measured.logicalHeight,edt{own.graphicsConfiguration.defaultTransform},measured.monitorShortSide.toFloat()))
        verify("original physical paint geometry agrees with actual Canvas client pixels",geometry.viewport.widthPx==measured.physicalWidth&&geometry.viewport.heightPx==measured.physicalHeight)
        verify("non-video-aspect task-owned canvas produces real native margins",fit.left!=0||fit.top!=0||fit.contentWidth!=fit.osdWidth||fit.contentHeight!=fit.osdHeight)
        phases+=verifyClip("native-fit",player,fit,measured,output)

        player.setVideoPanscan(1.0)
        until("real native zoom/crop output",player){player.state.value.activeVideoPanscan==1.0&&player.videoOutput.value.viewport?.let{it.left<0||it.top<0}==true}
        val crop=requireNotNull(player.videoOutput.value.viewport)
        verify("native negative crop margins are preserved",crop.left<0||crop.top<0)
        phases+=verifyClip("native-crop",player,crop,measured,output)
        val seek=requireNotNull(player.seekToTracked(1.5))
        until("actual seek receipt",player){player.state.value.seekCompletedId==seek&&player.videoOutput.value.sourceVersion==first&&player.videoOutput.value.viewport!=null}
        verify("seek keeps viewport/source association",player.ownsSourceVersion(first)&&player.videoOutput.value.sourceVersion==first)
        player.setVideoPanscan(0.0)
        edt{own.setSize(430,710);own.validate()}
        val tall=measure(player,own,user32)
        until("native resized portrait canvas OSD",player){player.videoOutput.value.viewport?.let{it.osdWidth==tall.physicalWidth&&it.osdHeight==tall.physicalHeight&&it.top>=0&&it.left>=0}==true}
        phases+=verifyClip("native-tall-client",player,requireNotNull(player.videoOutput.value.viewport),tall,output)

        val second=player.loadVersioned(PlaybackSource(replacement.toString(),referer="",cookieHeader="",title="Local replacement",startPaused=true,startPositionSeconds=.5))
        verify("load immediately retires old native source token",!player.ownsSourceVersion(first)&&player.ownsSourceVersion(second))
        until("replacement native frame and real bounds",player){player.state.value.firstVideoFrameReady&&player.state.value.nativePaused==true&&player.videoOutput.value.sourceVersion==second&&player.videoOutput.value.viewport!=null}
        verify("replacement load explicitly clears observed rectangle",observed.any{it.sourceVersion==second&&it.viewport==null&&it.displayWidth==0&&it.displayHeight==0})
        verify("retired source cannot admit current native rectangle",player.videoOutput.value.takeIf{it.sourceVersion==first&&player.ownsSourceVersion(first)}?.viewport==null)
        verify("old source cannot stop its replacement",!player.stopIfSourceVersion(first)&&player.ownsSourceVersion(second))
        verify("same native output versions never regress after replacement",observed.zipWithNext().all{(a,b)->b.sourceVersion>=a.sourceVersion})
        verify("current owned source stops through existing actor",player.stopIfSourceVersion(second))
        until("stop clears native bounds",player){!player.ownsSourceVersion(second)&&player.videoOutput.value.viewport==null&&player.videoOutput.value.displayWidth==0&&player.videoOutput.value.displayHeight==0}
        verify("stop advances source identity and leaves empty bounds",player.videoOutput.value.sourceVersion>second&&player.videoOutput.value.viewport==null)
        delay(350)
        verify("retired native poll cannot republish bounds after stop",player.videoOutput.value.viewport==null&&player.videoOutput.value.displayWidth==0&&player.videoOutput.value.displayHeight==0)

        // Explicit asymmetric physical fixture, not a claim that the user's monitor is asymmetric.
        val explicit=PlayerVideoViewport(1000,800,100,200,800,400)
        val mapped=explicit.sourceToPhysicalTransform(1250,1200,100,100).transform(Point2D.Double(50.0,50.0),null)
        verify("explicit asymmetric/fractional physical fixture uses independent axes",abs(mapped.x-625)<.00001&&abs(mapped.y-600)<.00001)
        verify("fixture opens no Java network/listener",fence.networkAttempts.get()==0)
    } finally {
        observerJob.cancel();observer.cancel()
        withContext(NonCancellable+Dispatchers.IO){player.close()}
        window?.let{edt{it.dispose()}}
    }
    until("actual default native player thread cleanup"){nativeThreads()==baseline}
    verify("native worker returns to baseline after teardown",nativeThreads()==baseline)
    verify("close clears final observed rectangle",player.videoOutput.value.viewport==null)
    val names=listOf("com.bilipai.desktop.player.MpvPlayer","com.bilipai.desktop.player.MpvNative","com.bilipai.desktop.player.PlayerVideoOutputState","com.bilipai.desktop.player.PlayerVideoViewport","com.bilipai.desktop.danmaku.DesktopWebMaskPath","com.bilipai.desktop.danmaku.DesktopWebMaskPathKt","com.bilipai.desktop.danmaku.DesktopDanmakuPaintGeometry","com.bilipai.desktop.danmaku.AdvancedDanmakuRenderer","com.bilipai.desktop.danmaku.DanmakuOverlay","com.android.purebilibili.danmaku.engine.DanmakuMaskFrame","com.android.purebilibili.feature.video.danmaku.WebMaskParser")
    val origins=names.map{name->val c=Class.forName(name);buildJsonObject{put("class",name);put("codeSource",c.protectionDomain.codeSource.location.toString());put("classSha256Bytes",digest(c.getResourceAsStream("/"+name.replace('.','/')+".class")!!.use{it.readBytes()}))}}
    Files.writeString(output.resolve("result.json"),buildJsonObject {
        put("status","PASS");put("assertions",checks.size);put("checks",JsonArray(checks.map(::JsonPrimitive)));put("nativePhases",JsonArray(phases));put("actualCodeSources",JsonArray(origins));put("productionClassOverrides",0);put("nativeDecodedFrameObserved",true);put("firstNativeReceiptElapsedNanos",firstFrameElapsed);put("nativeHiddenAWTWindow",true);put("physicalMonitorPresentationObserved",false);put("RootComposeWindowMounted",false);put("RootAccountMetadataOrSmartMaskActorAccepted",false);put("nativeOverlayScreenPaintAccepted",false);put("ordinaryNativeOutputAndActualJava2DClip",true);put("asymmetricScaleIsExplicitFixtureInput",true);put("javaNetworkAttempts",fence.networkAttempts.get());put("nativeNetworkSources",0);put("liveAccountRead",false)
    }.toString())
    println("PASS ${checks.size} actual native OSD/source/Java2D assertions; Root window and screen overlay acceptance remain separate")
}
