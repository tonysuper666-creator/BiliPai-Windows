package com.bilipai.desktop.ui

import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.appearance.DesktopThemePrefs
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopImageSaveLocationPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import java.awt.image.BufferedImage
import java.nio.file.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext

private var assertions=0
private fun claim(condition:Boolean,message:String) {check(condition){message};assertions++}
private suspend fun eventually(condition:()->Boolean) = withTimeout(5000) {while(!condition())delay(5)}
private fun files(path:Path):List<Path> = if(!Files.exists(path))emptyList() else Files.list(path).use {it.toList()}
private fun noTemps(root:Path):Boolean = Files.walk(root).use {stream->stream.noneMatch {it.fileName.toString().startsWith(".profile-")}}
private suspend fun rejected(block:suspend ()->Unit):Boolean = try {block();false}catch(error:Exception){println("EXPECTED_REJECTION "+error.javaClass.name+": "+error.message);true}
private class QueuedDispatcher:CoroutineDispatcher() {
    val queue=LinkedBlockingQueue<Runnable>()
    override fun dispatch(context:CoroutineContext,block:Runnable) {queue.add(block)}
    fun runNext() {queue.poll()?.run()}
}

private suspend fun themeProof(root:Path) {
    val store=DesktopPluginStore(root.resolve("theme"))
    store.update("settings",mapOf("theme_mode_v2" to JsonPrimitive(3),"unrelated" to JsonPrimitive("keep")))
    val theme=DesktopThemePrefs(store,false)
    claim(theme.initialSettings().themeMode==AppThemeMode.DARK,"Legacy AMOLED mode resolves DARK")
    claim(theme.initialSettings().darkThemeStyle==DarkThemeStyle.AMOLED,"Missing dark style preserves original legacy AMOLED")
    var owned=true
    val commit:((()->Unit)->Boolean)={block->if(owned){block();true}else false}
    val originalPreferences=DesktopOriginalProfilePreferences(store,{owned},commit) {mode->theme.setThemeModeOwned(mode,{owned},commit)}
    originalPreferences.setThemeMode(AppThemeMode.LIGHT)
    val canonical=store.preferences("settings");val cache=store.preferences("theme_cache")
    claim(canonical["theme_mode_v2"]?.jsonPrimitive?.int==1,"Profile delegates exact canonical mode")
    claim(canonical["dark_theme_style_v1"]?.jsonPrimitive?.int==1,"Resolve dark style before overwriting legacy mode")
    claim(cache["theme_mode"]?.jsonPrimitive?.int==1 && cache["dark_theme_style"]?.jsonPrimitive?.int==1,"Original startup cache mirrors canonical mode/style")
    claim(canonical["unrelated"]?.jsonPrimitive?.content=="keep","Theme transaction preserves unrelated keys")
    claim(theme.settings.first().themeMode==AppThemeMode.LIGHT,"Same global appearance Flow consumes committed mode")
    claim(DesktopPluginStore(root.resolve("theme")).preferences("theme_cache")==cache,"Same backing facade shares cache generation")
    val document=Files.readString(root.resolve("theme/plugin-settings.json"))
    owned=false
    claim(rejected {originalPreferences.setThemeMode(AppThemeMode.DARK)},"Retired original preference caller is rejected")
    claim(Files.readString(root.resolve("theme/plugin-settings.json"))==document,"Retirement preserves whole persisted document")
    owned=true
    val cancelJob=Job();val scope=CoroutineScope(cancelJob+Dispatchers.Default)
    val cancelled=scope.async {theme.setThemeModeOwned(AppThemeMode.DARK,{owned}) {block->cancelJob.cancel();block();true}}
    claim(rejected {cancelled.await()},"Cancelled caller is checked within admission")
    claim(Files.readString(root.resolve("theme/plugin-settings.json"))==document,"Admission cancellation publishes neither canonical nor cache")
    claim(rejected {store.updateThemeFromSnapshot({throw CancellationException("Publication fixture cancelled")}) {_,_->
        mapOf("settings" to mapOf("theme_mode_v2" to JsonPrimitive(2)),"theme_cache" to mapOf("theme_mode" to JsonPrimitive(2)))
    }},"Theme transaction checks cancellation immediately before atomic move")
    claim(Files.readString(root.resolve("theme/plugin-settings.json"))==document,"Publication cancellation preserves both persisted namespaces")
    claim(files(root.resolve("theme")).none {it.fileName.toString().endsWith(".tmp")},"Theme publication failure closes its private document temp")
    theme.setDarkThemeStyle(DarkThemeStyle.DEFAULT)
    claim(store.preferences("settings")["dark_theme_style_v1"]?.jsonPrimitive?.int==0 && store.preferences("theme_cache")["dark_theme_style"]?.jsonPrimitive?.int==0,"Dark-style setter mirrors original cache")
    store.freezeWrites()
    claim(rejected {theme.setThemeMode(AppThemeMode.DARK)},"Frozen old backing rejects original mode/cache write")
}

private suspend fun importProof(root:Path,probe:Path,video:Path) {
    val state=root.resolve("io");Files.createDirectories(state)
    val image=BufferedImage(16,12,BufferedImage.TYPE_INT_RGB)
    for(y in 0 until 12)for(x in 0 until 16)image.setRGB(x,y,if(y<6)0x0088ff else 0xffcc00)
    val png=root.resolve("synthetic.png");val gif=root.resolve("synthetic.gif")
    check(ImageIO.write(image,"png",png.toFile()));check(ImageIO.write(image,"gif",gif.toFile()))
    val owned=AtomicBoolean(true)
    val commit:((()->Unit)->Boolean)={block->if(owned.get()){block();true}else false}
    val metadata=DesktopProfileFfprobeWidth(probe)
    val effects=DesktopProfileOwnedFiles(state,owned::get,commit,metadata)
    val imported=effects.import(png.toUri().toString(),state.resolve("profile_wallpaper").toFile(),true)
    claim(imported.extension=="img" && imported.name.startsWith("wallpaper_"),"Original image .img convention preserved")
    claim(Files.readAllBytes(imported.toPath()).contentEquals(Files.readAllBytes(png)),"Real Skia validation retains PNG bytes")
    val importedGif=effects.import(gif.toUri().toString(),state.resolve("splash").toFile(),false)
    claim(Files.readAllBytes(importedGif.toPath()).contentEquals(Files.readAllBytes(gif)),"GIF raw bytes preserved under .img")
    val importedVideo=effects.import(video.toUri().toString(),state.resolve("home_wallpaper").toFile(),false)
    claim(importedVideo.extension=="mp4","Original source video extension preserved")
    claim(Files.readAllBytes(importedVideo.toPath()).contentEquals(Files.readAllBytes(video)),"Video import preserves encoded bytes")
    claim(metadata.read(importedVideo.toPath()){}==160,"Already bundled real ffprobe validates video width")
    val invalid=root.resolve("invalid.png");Files.write(invalid,byteArrayOf(1,2,3))
    val count=files(state.resolve("profile_wallpaper")).size
    claim(rejected {effects.import(invalid.toUri().toString(),state.resolve("profile_wallpaper").toFile(),true)},"Invalid image rejected")
    claim(files(state.resolve("profile_wallpaper")).size==count,"Invalid image private temp cleaned")
    val badVideo=root.resolve("invalid.mp4");Files.write(badVideo,byteArrayOf(4,5,6))
    claim(rejected {effects.import(badVideo.toUri().toString(),state.resolve("home_wallpaper").toFile(),false)},"Real metadata rejects invalid video")
    claim(files(state.resolve("home_wallpaper")).size==1,"Invalid video and metadata temporaries cleaned")
    val target=state.resolve("images/profile_bg_fixture.jpg");Files.createDirectories(target.parent)
    effects.write(target.toFile(),Files.readAllBytes(png))
    claim(Files.readAllBytes(target).contentEquals(Files.readAllBytes(png)),"Bounded physical write preserves payload")
    claim(rejected {effects.write(target.toFile(),byteArrayOf(9))},"No-clobber rejects existing final file")
    claim(Files.readAllBytes(target).contentEquals(Files.readAllBytes(png)),"Existing file remains byte identical")
    claim(rejected {effects.write(state.resolve("images/empty.jpg").toFile(),ByteArray(0))},"Empty download rejected before publication")
    claim(rejected {effects.write(state.resolve("../escape.jpg").toFile(),byteArrayOf(1))},"Import actor rejects non-original directory")
    val body=Files.readAllBytes(png).toResponseBody()
    claim(effects.read(body).contentEquals(Files.readAllBytes(png)),"Owned response source read returns exact bytes")
    effects.delete(target.toFile());claim(!Files.exists(target),"Original delete effect closes physical file")
    owned.set(false)
    claim(rejected {effects.import(png.toUri().toString(),state.resolve("splash").toFile(),false)},"Retired entry cannot import")
    claim(noTemps(state),"All normal/error physical temporaries closed and deleted")

    val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
    val supersede=DesktopProfileOwnedFiles(state,{true},{block->block();true},DesktopProfileVideoWidth {_,checkpoint->
        entered.complete(Unit);withContext(NonCancellable){release.await()};checkpoint();160
    })
    coroutineScope {
        val old=async {supersede.import(video.toUri().toString(),state.resolve("home_wallpaper").toFile(),false)}
        entered.await()
        val fresh=async(start=CoroutineStart.UNDISPATCHED) {supersede.import(png.toUri().toString(),state.resolve("home_wallpaper").toFile(),true)}
        release.complete(Unit)
        claim(rejected {old.await()},"New import generation rejects older metadata result")
        claim(Files.readAllBytes(fresh.await().toPath()).contentEquals(Files.readAllBytes(png)),"Newest import publishes real original bytes")
    }
    claim(files(state.resolve("home_wallpaper")).size==2 && noTemps(state),"Superseded imported video never publishes and leaves no stage")

    suspend fun dispatchBackCancellation(directory:Path,action:suspend ()->Unit) {
        Files.createDirectories(directory);val before=files(directory).size
        val dispatcher=QueuedDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val task=scope.async {action()};dispatcher.runNext()
        eventually {dispatcher.queue.isNotEmpty() && files(directory).any {it.fileName.toString().startsWith("wallpaper_") || it.fileName.toString()=="dispatch.jpg"}}
        claim(files(directory).size>before,"IO publication reached before UI dispatch resumes")
        task.cancel();while(!task.isCompleted){dispatcher.runNext();delay(1)}
        claim(files(directory).size==before,"Cancellation during UI dispatch-back cleans private published file")
        scope.cancel()
    }
    val dispatchRoot=root.resolve("dispatch");Files.createDirectories(dispatchRoot)
    val dispatchEffects=DesktopProfileOwnedFiles(dispatchRoot,{true},{block->block();true},metadata)
    dispatchBackCancellation(dispatchRoot.resolve("profile_wallpaper")) {dispatchEffects.import(png.toUri().toString(),dispatchRoot.resolve("profile_wallpaper").toFile(),true)}
    dispatchBackCancellation(dispatchRoot.resolve("images")) {dispatchEffects.write(dispatchRoot.resolve("images/dispatch.jpg").toFile(),Files.readAllBytes(png))}
    claim(noTemps(dispatchRoot),"Dispatch-back cancellation closes native/source/output handles and private stages")

    val readEntered=java.util.concurrent.CountDownLatch(1);val readRelease=java.util.concurrent.CountDownLatch(1)
    val readClosed=AtomicBoolean()
    val blockingSource=object:okio.Source {
        var emitted=false
        override fun read(sink:okio.Buffer,byteCount:Long):Long {
            if(emitted)return -1
            readEntered.countDown();check(readRelease.await(5,java.util.concurrent.TimeUnit.SECONDS))
            emitted=true;sink.write(byteArrayOf(1,2,3));return 3
        }
        override fun close() {readClosed.set(true)}
        override fun timeout()=okio.Timeout.NONE
    }
    val buffered=blockingSource.buffer()
    val blockingBody=object:okhttp3.ResponseBody() {
        override fun contentLength()=3L
        override fun contentType():okhttp3.MediaType?=null
        override fun source()=buffered
    }
    val readScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val readTask=readScope.async {dispatchEffects.write(dispatchRoot.resolve("images/cancelled-read.jpg").toFile(),blockingBody)}
    claim(withContext(Dispatchers.IO){readEntered.await(5,java.util.concurrent.TimeUnit.SECONDS)},"Actual file writer reaches an owned blocking body read")
    readTask.cancel();readRelease.countDown()
    claim(rejected {readTask.await()},"Cancellation rechecked after body read before physical publication")
    eventually {readClosed.get()}
    claim(!Files.exists(dispatchRoot.resolve("images/cancelled-read.jpg")) && noTemps(dispatchRoot),"Mid-read cancellation removes private stage and emits no final file")
    claim(readClosed.get(),"Mid-read cancellation closes the supplied response source")
    readScope.cancel()
}

private suspend fun galleryProof(root:Path) {
    val owner=DesktopDynamicCacheOwner(7,11,"fixture")
    val gate=object:DesktopDynamicCacheSessionGuard {
        override fun dynamicCacheOwner()=owner
        override fun withCurrentDynamicCacheOwner(expected:DesktopDynamicCacheOwner,block:()->Unit):Boolean {
            check(expected==owner);block();return true
        }
    }
    val store=DesktopPluginStore(root.resolve("gallery-settings"))
    val lifetime=DesktopImageSaveLifetime {false}
    val prefs=DesktopImageSaveLocationPreferences(store,lifetime::withCommit)
    val pictures=root.resolve("fixture-pictures");Files.createDirectories(pictures)
    val locations=DesktopImageSaveLocations(prefs,lifetime::isActive,lifetime::withCommit,
        resolveDefaultDirectory={Result.success(pictures.resolve("BiliPai"))})
    // A fixture-only non-network client constructor is explicit; production receives sole Root client.
    val assets=DesktopDynamicImageAssets(OkHttpClient(),{true},gate,owner,
        selectTarget={_,_->error("Chooser must not open in proof")},imageSaveLocations=locations)
    var profileOwned=true
    val profileCommit:((()->Unit)->Boolean)={block->if(profileOwned){block();true}else false}
    val raw=byteArrayOf(71,73,70,56,57,97,1,2,3)
    claim(assets.saveProfileGalleryBytes(raw,"profile-fixture.jpg",{profileOwned},profileCommit),"Existing sole gallery actor publishes Profile payload")
    claim(Files.readAllBytes(pictures.resolve("BiliPai/profile-fixture.jpg")).contentEquals(raw),"Original supplied gallery bytes are not re-encoded")
    claim(assets.saveProfileGalleryBytes(raw,"profile-fixture.jpg",{profileOwned},profileCommit),"Original duplicate display name uses sole Locations collision policy")
    claim(files(pictures.resolve("BiliPai")).size==2,"Gallery collisions preserve both existing files")
    profileOwned=false
    claim(rejected {assets.saveProfileGalleryBytes(raw,"retired.jpg",{profileOwned},profileCommit)},"Profile retirement gates global Assets actor")
    claim(!Files.exists(pictures.resolve("BiliPai/retired.jpg")),"Retired Profile emits no saved file")
    lifetime.close();profileOwned=true
    claim(rejected {assets.saveProfileGalleryBytes(raw,"closed.jpg",{profileOwned},profileCommit)},"Actual global lifetime remains authoritative")
    assets.close()
}

fun main(args:Array<String>)=runBlocking {
    assertActual44Origins()
    val root=Path.of(args[0]);Files.createDirectories(root)
    themeProof(root);importProof(root,Path.of(args[1]),Path.of(args[2]));galleryProof(root)
    println("PROFILE_PLATFORM_PROOF {\"groups\":3,\"assertions\":$assertions,\"originalImport\":true,\"realLocalSkia\":true,\"realBundledFfprobe\":true,\"sameStoreTheme\":true,\"prospectiveOverrideFiles\":0,\"rootMounted\":false,\"actualChooserOpened\":false,\"actualDwmWindow\":false,\"externalHttp\":false}")
}
