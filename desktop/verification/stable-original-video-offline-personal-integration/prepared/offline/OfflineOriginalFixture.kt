@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.feature.download.DownloadTask as OriginalTask
import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.OfflineMiniPlayerPayload
import com.android.purebilibili.core.store.player.DesktopOriginalLongPressSpeedSettings
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.danmaku.*
import com.bilipai.desktop.download.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.nio.file.*
import java.awt.Font
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

private var assertions=0
private fun prove(value:Boolean,message:String){check(value){message};assertions++;println("PASS $message")}
private fun nodes(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::nodes)
private suspend fun eventually(test:()->Boolean)=withTimeout(10000){while(!test())delay(10)}

fun main(args:Array<String>):Unit=runBlocking {
    val root=Path.of(args[0]);Files.createDirectories(root)
    val clip=Path.of(args[1]);val directory=root.resolve("downloads");Files.createDirectories(directory)
    val items=(1L..2L).map { cid ->
        val original=OriginalTask(bvid="BVfixture",cid=cid,title="Original Offline $cid",cover="",ownerName="Local fixture artist",ownerFace="",
            duration=4,quality=80,qualityDesc="80",videoUrl="https://fixture.invalid/forbidden",audioUrl="",status=DownloadStatus.COMPLETED,
            createdAt=cid,groupKey="fixture-group",episodeSortIndex=cid.toInt(),lastPlaybackPositionMs=500)
        val task=DownloadTask(original,directory.toString());val taskDirectory=Path.of(task.directory);Files.createDirectories(taskDirectory)
        Files.writeString(taskDirectory.resolve(".bilipai-download"),task.id)
        val video=taskDirectory.resolve("video.mp4");Files.copy(clip,video)
        task.copy(item=original.copy(filePath=video.toString()))
    }
    val stateFile=root.resolve("queue.json");val json=Json{encodeDefaults=true;ignoreUnknownKeys=true}
    Files.writeString(stateFile,json.encodeToString(ListSerializer(DownloadTask.serializer()),items))
    var httpCalls=0
    val client=OkHttpClient.Builder().addInterceptor {httpCalls++;error("HTTP forbidden in local fixture")}.build()
    val manager=DesktopDownloadManager(client,stateFile,DownloadMuxer {_,_,_->error("No enqueue in fixture")})
    val sessions=DesktopSessionStore(root.resolve("unused-sessions.json"),persistent=false)
    val stamp=requireNotNull(sessions.dynamicCacheOwner());val alive=AtomicBoolean(true);var epoch=0L
    val entryJob=SupervisorJob();val entryScope=CoroutineScope(entryJob+Dispatchers.Default)
    val owns={alive.get()&&entryJob.isActive&&epoch==0L&&sessions.dynamicCacheOwner()==stamp}
    val admission:((()->Unit)->Boolean)={effect->var ran=false;sessions.withCurrentDynamicCacheOwner(stamp){if(owns()){effect();ran=true}};ran}
    val store=DesktopPluginStore(root.resolve("prefs"));val context=DesktopPluginContext(store)
    val writeOwned:((()->Unit)->Boolean)={effect->if(!admission(effect))throw CancellationException("Fixture entry retired");true}
    val preferences=DesktopOriginalDanmakuPreferences(store,DesktopDanmakuBlockPreferences(store,writeOwned),writeOwned)
    prove(DesktopOriginalLongPressSpeedSettings.getLongPressSpeed(context).first()==2f,"Canonical original long-press default is read from the same Store")
    store.update("settings",mapOf("long_press_speed" to JsonPrimitive(9.125f)))
    prove(DesktopOriginalLongPressSpeedSettings.getLongPressSpeed(context).first()==8f,"Original normalization clamps the actual stored key, without a second preference authority")
    val target=MpvSoftwareTarget().apply{resize(160,90)}
    val player=MpvPlayer(softwareTarget=target,useNullAudioOutput=true);player.setMuted(true)
    val renderPlatform=object:DesktopOriginalDanmakuRenderPlatform {
        override fun resolveTypeface(fontWeight:Int)=Font("Dialog",Font.PLAIN,20)
        override fun systemChromeInsetPx()=0
        override fun maximumDisplayShortSidePx()=600f // explicit synthetic fixture input, never a Root monitor claim
    }
    val overlay=DanmakuOverlay(player,renderPlatform,client)
    var acquired=0;val retained=DesktopRetainedMedia(entryScope,player){acquired++}
    val backend=DesktopOfflineTaskPlayerBinding(manager,retained,overlay,entryScope,0L,{epoch},owns,admission,{true},{"Fixture MPV failed"})
    var fullscreenCalls=0;var restores=0;var published:OfflineMiniPlayerPayload?=null;var metadataVersion:Long?=null;var mediaClears=0
    var renderCalls=0;val feedback=mutableListOf<String>()
    val window=object:DesktopOfflineWindowEffects {
        override fun applyFullscreen(fullscreen:Boolean){check(owns());fullscreenCalls++}
        override fun restoreCurrentRootChrome(){restores++}
    }
    val media=object:DesktopOfflineMediaEffects {
        override fun publish(payload:OfflineMiniPlayerPayload,player:DesktopOfflineMpvControl){if(player.isOwned()){published=payload;metadataVersion=player.sourceVersion}}
        override fun clearIfOwned(player:DesktopOfflineMpvControl){if(player.isOwned()&&metadataVersion==player.sourceVersion){published=null;metadataVersion=null;mediaClears++}}
    }
    val surface=object:DesktopOriginalOfflineSurface {
        @Composable override fun Render(player:DesktopOfflineMpvControl,modifier:Modifier,foreground:@Composable ()->Unit){renderCalls++;foreground()}
    } // This fixture-only Compose carrier proves original foreground controls; it is not HWND acceptance.
    val binding=DesktopOriginalOfflinePlayerBindings(context,backend,entryScope,preferences,DesktopDanmakuPresentationBinding{DesktopDanmakuPresentation.INLINE},window,surface,media,feedback::add)
    var scene:ImageComposeScene?=null
    try {
        val control=binding.control(items[0].id)
        control.load {error("No external episode action in direct control test")}
        val firstVersion=requireNotNull(control.sourceVersion)
        prove(control.isOwned()&&retained.current===retained.offline&&retained.offline.current==items[0].id,"Full original control loads exact task into the sole retained offline owner")
        prove(retained.offline.assetsJob==null&&retained.offline.checkpointJob==null,"Full original effects have no duplicate legacy asset/checkpoint jobs")
        player.startSoftwareTransport()
        val first=withTimeout(12000){target.frames.first{it!=null&&it.sourceVersion==firstVersion}}!!
        prove(first.width==160&&first.height==90&&first.sequence>0,"Actual unchanged MPV DLL supplies an owned local first frame")
        control.pause();control.setPlaybackSpeed(2.25f);control.volumePercent=35;control.seekTo(1500)
        eventually{player.state.value.paused&&player.state.value.speed==2.25&&player.state.value.volume==35.0}
        prove(control.playbackSpeed==2.25f&&control.volumePercent==35,"Original control facade writes actual native speed and app volume")
        binding.updatePlaybackPosition(items[0].id,1234L)
        prove(manager.tasks.value.first {it.id==items[0].id}.item.lastPlaybackPositionMs==1234L,"Original checkpoint writes exact task position into the actual existing manager and file")
        val saved=json.decodeFromString(ListSerializer(DownloadTask.serializer()),Files.readString(stateFile))
        prove(saved.first {it.id==items[0].id}.item.lastPlaybackPositionMs==1234L,"Actual manager atomically persists the original checkpoint without another task store")
        var documentAlive=true
        overlay.loadOffline(emptyList(),emptyList(),4.0,expectedSourceVersion=firstVersion,stillOwned={documentAlive&&epoch==0L&&entryJob.isActive})
        val ownedDocument=DanmakuOverlay::class.java.getDeclaredMethod("currentOfflineDocumentOwned").apply{isAccessible=true}
        prove(ownedDocument.invoke(overlay)==true,"The existing offline window actor captures its exact native and entry-read-only owner")
        val rawField=DanmakuOverlay::class.java.getDeclaredField("rawDocument").apply{isAccessible=true}
        val beforeDocument=rawField.get(overlay);documentAlive=false
        val generationField=DanmakuOverlay::class.java.getDeclaredField("generation").apply{isAccessible=true}
        val generation=(generationField.get(overlay) as java.util.concurrent.atomic.AtomicLong).get()
        val publish=DanmakuOverlay::class.java.getDeclaredMethod("publish",DanmakuWindowResult::class.java,Long::class.javaPrimitiveType).apply{isAccessible=true}
        val obsolete=DanmakuWindowResult(DanmakuDocument(listOf(DanmakuComment(1,0.5,1,25,0xffffff,"late synthetic decode"))),DanmakuFormat.PROTOBUF)
        publish.invoke(overlay,obsolete,generation)
        prove(ownedDocument.invoke(overlay)==false&&rawField.get(overlay)===beforeDocument,"An entry-retired late offline decode cannot publish while the native token is temporarily still current")
        prove(overlay.setViewportBrightness(firstVersion,0.4f)&&overlay.viewportBrightnessFor(firstVersion)==0.4f,"Viewport brightness publishes only for the actual source version")
        overlay.enabled=false;overlay.setEyeProtection(0.2f,0f)
        val panelField=DanmakuOverlay::class.java.getDeclaredField("panel").apply{isAccessible=true}
        val panel=panelField.get(overlay) as JComponent;panel.setSize(800,600)
        fun alpha():Int {
            val image=BufferedImage(800,600,BufferedImage.TYPE_INT_ARGB);val graphics=image.createGraphics()
            try{panel.paint(graphics)}finally{graphics.dispose()}
            return(image.getRGB(400,300) ushr 24) and 255
        }
        prove(alpha() in 172..175,"Actual same overlay painter composes viewport dim with existing plugin eye tint")
        prove(!overlay.setViewportBrightness(firstVersion+999,0.1f)&&overlay.viewportBrightnessFor(firstVersion)==0.4f,"Stale/foreign brightness setter cannot mutate current viewport")
        control.release()
        prove(overlay.viewportBrightnessFor(firstVersion)==null&&alpha() in 50..52,"Retiring the native source suppresses its tint while preserving plugin eye tint")
        overlay.setEyeProtection(0f,0f)

        val ownScene=ImageComposeScene(width=800,height=600,coroutineContext=coroutineContext);scene=ownScene
        ownScene.setContent {DesktopAppearanceTheme(DesktopThemeSettings()) {DesktopOriginalOfflinePlayerHost(items[0].id,binding,{})}}
        var time=0L
        suspend fun frame(n:Int=1){repeat(n){time+=32_000_000;ownScene.render(time).close();delay(10)}}
        fun all()=ownScene.semanticsOwners.flatMap{nodes(it.unmergedRootSemanticsNode)}
        fun text(t:String)=all().any{it.config.getOrNull(SemanticsProperties.Text).orEmpty().any{v->v.text==t}}
        withTimeout(10000){while(!text("Original Offline 1")||!backend.ownsAcceptedSource()){frame()}}
        player.setPaused(true);frame(8)
        prove(text("上一集")&&text("下一集")&&text("1/2"),"The complete original renderer exposes original episode controls and authoritative queue position")
        prove(published?.bvid==items[0].item.bvid&&published?.cid==1L&&published?.owner=="Local fixture artist"&&metadataVersion==player.currentSourceVersion,"Original metadata payload and actual playing observer retain title/artist/bvid/cid and native ownership")
        suspend fun clickText(t:String){
            val node=all().filter{it.config.getOrNull(SemanticsActions.OnClick)!=null&&nodes(it).any{n->n.config.getOrNull(SemanticsProperties.Text).orEmpty().any{v->v.text==t}}}.minBy{it.boundsInRoot.width*it.boundsInRoot.height}
            val point=node.boundsInRoot.center
            ownScene.sendPointerEvent(PointerEventType.Press,point,timeMillis=time/1000000,buttons=PointerButtons(isPrimaryPressed=true));frame(3)
            ownScene.sendPointerEvent(PointerEventType.Release,point,timeMillis=time/1000000+30,buttons=PointerButtons());frame(5)
        }
        clickText("下一集")
        withTimeout(10000){while(!text("Original Offline 2")||retained.offline.current!=items[1].id){frame()}}
        prove(retained.offline.current==items[1].id&&text("2/2"),"Real original next-episode pointer action loads the exact second task, without a browser chooser")
        val secondVersion=requireNotNull(retained.offline.sourceVersion)
        prove(secondVersion!=firstVersion&&!player.ownsSourceVersion(firstVersion),"Original episode replacement retires the previous native token")
        val current=binding.control(items[1].id);player.setPaused(true)
        val oldSpeed=player.state.value.speed;epoch=1L;current.setPlaybackSpeed(4f)
        prove(player.state.value.speed==oldSpeed&&!current.isOwned(),"Account retirement rejects facade writes to the real shared MPV")
        val beforePosition=manager.tasks.value.first {it.id==items[1].id}.item.lastPlaybackPositionMs
        binding.updatePlaybackPosition(items[1].id,2345L)
        prove(manager.tasks.value.first {it.id==items[1].id}.item.lastPlaybackPositionMs==beforePosition,"Retired epoch cannot write a late original playback checkpoint")
        epoch=0L
        val foreign=player.loadVersioned(PlaybackSource(clip.toString(),referer="",title="Foreign fixture source",startPaused=true))
        prove(!overlay.clearViewportBrightness(secondVersion)&&!overlay.setViewportBrightness(secondVersion,0.1f),"Old source brightness cleanup cannot touch a newer source")
        ownScene.close();scene=null;delay(30)
        prove(player.ownsSourceVersion(foreign),"Full renderer disposal and its exact-source cleanup preserve an independent native owner")
        prove(fullscreenCalls>0&&restores>0&&mediaClears>0&&renderCalls>0,"Full original effects reach declared window/media/foreground fixture boundaries, with no no-op callbacks")
        prove(httpCalls==0&&feedback.isEmpty(),"Focused acceptance performs only synthetic local IO and unchanged native playback")
        println("OFFLINE_ORIGINAL_PROOF "+buildJsonObject{put("status","PASS");put("groups",3);put("assertions",assertions);put("pointerPairs",1);put("sameRetainedMemory",true);put("realLocalNativeFrame",true);put("fullOriginalUIRendered",true);put("actualOverlayJava2DPaint",true);put("HTTPCalls",httpCalls);put("rootWindowAccepted",false);put("nativeHWNDForegroundInputAccepted",false);put("WindowsSMTCPIPAccepted",false)})
    }finally{scene?.close();backend.close();retained.close();overlay.close();player.close();alive.set(false);entryScope.cancel();manager.close();client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
}
