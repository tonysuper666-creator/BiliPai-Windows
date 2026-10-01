@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.store.DesktopOriginalDownloadListSettings
import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.DownloadTask as OriginalTask
import com.android.purebilibili.feature.download.formatDownloadStorageBytes
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.download.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.awt.image.BufferedImage
import java.nio.file.*
import javax.imageio.ImageIO
import java.util.zip.ZipFile

private var assertions=0
private fun prove(value:Boolean,message:String) {check(value){message};assertions++}
private suspend fun eventually(test:()->Boolean) = withTimeout(5000) {while(!test())delay(5)}
private fun nodeTree(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::nodeTree)
private class Ui(val scene:ImageComposeScene) {
    var nanos=0L;var pointerPairs=0
    fun nodes()=scene.semanticsOwners.flatMap{nodeTree(it.unmergedRootSemanticsNode)}
    fun hasText(text:String)=nodes().any {node->node.config.getOrNull(SemanticsProperties.Text).orEmpty().any{it.text==text}}
    suspend fun frame() {nanos+=32_000_000;scene.render(nanos).close();delay(3)}
    suspend fun await(test:()->Boolean) {
        try {withTimeout(5000){while(!test())frame()};repeat(5){frame()}}
        catch (failure:Exception) {
            println("FIXTURE_SEMANTICS "+nodes().map{it.config.getOrNull(SemanticsProperties.Text).orEmpty().map{t->t.text} to it.config.getOrNull(SemanticsProperties.ContentDescription)});throw failure
        }
    }
    suspend fun click(text:String,description:Boolean) {
        val target=nodes().filter{node->node.config.getOrNull(SemanticsActions.OnClick)!=null && nodeTree(node).any{child->
            if(description) child.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(text)
            else child.config.getOrNull(SemanticsProperties.Text).orEmpty().any{it.text==text}
        }}.minBy{it.boundsInRoot.width*it.boundsInRoot.height}
        val point=target.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press,point,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        repeat(3){frame()}
        scene.sendPointerEvent(PointerEventType.Release,point,timeMillis=nanos/1_000_000+30,buttons=PointerButtons())
        pointerPairs++;repeat(10){frame()}
    }
}

fun main(args:Array<String>):Unit=runBlocking {
    val root=Path.of(args[0]).toAbsolutePath().normalize();Files.createDirectories(root)
    val jar=Path.of(args[1]);var loaded=0
    ZipFile(jar.toFile()).use{z->z.entries().asSequence().filter{it.name.endsWith(".class")}.forEach{
        Class.forName(it.name.removeSuffix(".class").replace('/','.'),false,ClassLoader.getSystemClassLoader());loaded++
    }}
    prove(loaded>0,"Every new candidate class loads without initialization")
    val store=DesktopPluginStore(root.resolve("settings"));val context=DesktopPluginContext(store)
    val managed=root.resolve("downloads");Files.createDirectories(managed)
    store.update("settings",mapOf("download_path" to JsonPrimitive(managed.toString()),"download_export_tree_uri" to JsonPrimitive("content://legacy/tree/primary%3ADownload"),"unrelated" to JsonPrimitive("keep")))
    prove(DesktopOriginalDownloadListSettings.getDownloadPath(context).first()==managed.toString(),"Original canonical managed path getter reads the same Store")
    prove(DesktopOriginalDownloadListSettings.getDownloadExportTreeUri(context).first()?.startsWith("content://")==true,"Original export getter retains the real legacy input")
    prove(desktopOriginalDownloadDestination(context){true}==managed,"Actual enqueue destination consumes that canonical key")
    prove(runCatching{resolveDesktopOriginalDownloadDestination("content://legacy/tree/x",managed)}.isFailure,"Historical Android URI is rejected as a Windows managed destination")
    prove(runCatching{resolveDesktopOriginalDownloadDestination("relative-folder",managed)}.isFailure,"Relative location cannot pretend to be a real managed destination")
    prove(runCatching{desktopOriginalDownloadDestination(context){false}}.exceptionOrNull() is CancellationException,"Retired caller cannot publish a destination")
    prove(formatDownloadStorageBytes(0)=="0 B" && formatDownloadStorageBytes(1024)=="1 KB" && formatDownloadStorageBytes(1048576)=="1.0 MB","Complete original storage summary handles actual task-byte values")
    prove(store.preferences("settings")["unrelated"]?.jsonPrimitive?.content=="keep","Reading list settings does not create or overwrite another preference state")

    val cover=root.resolve("local-cover.png");ImageIO.write(BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",cover.toFile())
    fun task(cid:Long,title:String,status:DownloadStatus)=OriginalTask(bvid="BVfixture",cid=cid,title=title,cover="",ownerName="fixture",ownerFace="",duration=4,quality=80,qualityDesc="1080P",videoUrl="https://fixture.invalid/video",audioUrl="",status=status,createdAt=cid,localCoverPath=cover.toString())
    fun wrap(item:OriginalTask)=DownloadTask(item,managed.toString())
    val paused=wrap(task(2,"Paused synthetic task",DownloadStatus.PAUSED))
    val completeBase=wrap(task(1,"Completed synthetic task",DownloadStatus.COMPLETED))
    for(t in listOf(paused,completeBase)) {
        val dir=Path.of(t.directory);Files.createDirectories(dir);Files.writeString(dir.resolve(".bilipai-download"),t.id)
        Files.write(dir.resolve("video.mp4"),byteArrayOf(1,2,3));Files.write(dir.resolve("audio.m4s"),byteArrayOf(4,5));Files.write(dir.resolve("danmaku.pb"),byteArrayOf(6))
    }
    val complete=completeBase.copy(item=completeBase.item.copy(filePath=Path.of(completeBase.directory).resolve("video.mp4").toString(),fileSize=3))
    val stateFile=root.resolve("queue.json");val json=Json{encodeDefaults=true;ignoreUnknownKeys=true}
    Files.writeString(stateFile,json.encodeToString(ListSerializer(DownloadTask.serializer()),listOf(complete,paused)))
    var transportCalls=0
    val client=OkHttpClient.Builder().addInterceptor{transportCalls++;error("Fixture forbids HTTP")}.build()
    val manager=DesktopDownloadManager(client,stateFile,DownloadMuxer{_,_,_->error("Fixture does not start download or mux")})
    val entryJob=SupervisorJob();val entryScope=CoroutineScope(entryJob+Dispatchers.Default);var owned=true;var admitted=0;val feedback=mutableListOf<String>()
    val bindings=DesktopOriginalDownloadListBindings(context,manager,entryScope,{owned},{effect->if(owned&&entryJob.isActive){admitted++;effect();true}else false},{true},{feedback.add(it)})
    try {
        bindings.tasks.first{it.size==2}
        prove(bindings.tasks.value.keys==manager.tasks.value.map{it.id}.toSet(),"Transient map exposes the actual queue's original task identities")
        prove(bindings.displayedDownloadLocation(DesktopDownloadManager.defaultDownloadRoot().toString(),managed.toString(),"content://legacy/tree/x")==managed.toString(),"Storage display uses the actual Windows managed path, not Android SAF fiction")
        bindings.reportUnavailableLocation(managed.toString(),"content://legacy/tree/x")
        prove(feedback.any{it.contains("Android")},"Unsupported real legacy export input gives explicit feedback")
        val scene=ImageComposeScene(width=820,height=700,coroutineContext=coroutineContext);val ui=Ui(scene)
        try {
            scene.setContent {DesktopAppearanceTheme(DesktopThemeSettings()) {
                CompositionLocalProvider(LocalDesktopDetailForeground provides owned) {
                    DesktopOriginalDownloadListHost(bindings,{}, {error("Fixture does not open online media")},{error("Fixture does not start offline media")})
                }
            }}
            ui.await{ui.hasText("离线缓存")&&ui.hasText("Paused synthetic task")&&ui.hasText("继续全部")}
            println("FIXTURE_LIST_RENDERED")
            prove(ui.hasText("1080P"),"Original task cards show real quality labels")
            prove(ui.hasText("Completed synthetic task"),"Complete original list renders both restored task states")
            ui.click("删除",true)
            println("FIXTURE_DELETE_POINTER")
            ui.await{ui.hasText("删除缓存")}
            prove(manager.tasks.value.size==2,"Original confirmation prevents immediate destructive deletion")
            ui.click("删除",false)
            eventually{manager.tasks.value.size==1&&!Files.exists(Path.of(paused.directory))}
            prove(manager.tasks.value.single().id==complete.id,"Real original confirmation removes only its selected queue task")
            prove(!Files.exists(Path.of(paused.directory)),"The existing manager removes selected video/audio/Danmaku assets after original confirmation")
            prove(Files.exists(Path.of(complete.directory).resolve("video.mp4")),"Unrelated completed task files stay intact")
            owned=false;entryJob.cancel()
            bindings.removeTask(complete.id)
            prove(manager.tasks.value.size==1&&Files.exists(Path.of(complete.directory)),"Retired entry cannot delete another task")
            prove(admitted==1,"Only the actual accepted delete reaches the entry admission callback")
            prove(transportCalls==0,"Local fixture never performs HTTP or starts a second player")
            println("DOWNLOAD_LIST_PROOF "+buildJsonObject{put("status","PASS");put("groups",3);put("assertions",assertions);put("pointerPairs",ui.pointerPairs);put("loadedCandidateClasses",loaded);put("actualRootRuntimeAccepted",false);put("rootMounted",false);put("HTTPCalls",transportCalls);put("chooserOpened",false);put("offscreenOriginalUi",true);put("actualQueueAndLocalDelete",true)})
        } finally {scene.close()}
    } finally {entryScope.cancel();manager.close();client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
}
