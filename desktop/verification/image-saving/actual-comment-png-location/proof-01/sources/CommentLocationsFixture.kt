package com.bilipai.desktop.ui.commentlocationsproof

import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.feature.video.ui.components.buildReplyCommentImageSpec
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopImageSaveLocationPreferences
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

fun main(args:Array<String>)=runBlocking {
    val root=Path.of(args[0]);Files.createDirectories(root)
    val main=Path.of(args[1]).toAbsolutePath().normalize()
    var assertions=0
    fun prove(value:Boolean,message:String){assertions++;check(value){message}}
    val classes=listOf("com.bilipai.desktop.plugins.DesktopPluginStore","com.bilipai.desktop.settings.DesktopImageSaveLocationPreferences",
        "com.bilipai.desktop.ui.DesktopImageSaveLifetime","com.bilipai.desktop.ui.DesktopImageSaveLocations",
        "com.bilipai.desktop.data.DesktopSessionStore","com.bilipai.desktop.ui.DesktopCommentImageCanvasKt",
        "com.android.purebilibili.data.model.response.ReplyItem",
        "com.android.purebilibili.feature.video.ui.components.DesktopOriginalReplyCommentImageSpecKt",
        "com.android.purebilibili.feature.video.ui.components.DesktopOriginalReplyImageRendererKt")
    val codeSources=classes.map { name ->
        val type=Class.forName(name);val source=Path.of(type.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize()
        prove(source==main,"actual Main04 codeSource $name")
        val bytes=type.getResourceAsStream("/"+name.replace('.','/')+".class")!!.use{it.readBytes()}
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        buildJsonObject{put("className",name);put("codeSource",source.toString());put("classSha256Bytes",digest)}
    }
    val sessions=DesktopSessionStore.temporary()
    val owner=checkNotNull(sessions.dynamicCacheOwner())
    val life=DesktopImageSaveLifetime { false }
    val store=DesktopPluginStore(root.resolve("prefs"))
    val preferences=DesktopImageSaveLocationPreferences(store,life::withCommit)
    val custom=root.resolve("custom");Files.createDirectories(custom)
    val defaults=root.resolve("default-base");Files.createDirectories(defaults)
    val defaultCalls=AtomicInteger()
    val locations=DesktopImageSaveLocations(preferences,life::isActive,life::withCommit,
        resolveDefaultDirectory={defaultCalls.incrementAndGet();Result.success(defaults.resolve("BiliPai"))})
    preferences.setImageSaveTreeUri(custom.toUri().toString())
    prove(preferences.getImageSaveTreeUriSync()==custom.toUri().toString()&&preferences.getImageSaveTreeUri().first()==custom.toUri().toString(),"same real global preference sync/flow")
    val reply=Json{ignoreUnknownKeys=true}.decodeFromString<ReplyItem>("""{"rpid":621,"oid":123,"ctime":1700000000,"like":8,"content":{"message":"  原合法合成评论\n目录验证  "},"member":{"uname":"任务合成作者"}}""")
    val spec=buildReplyCommentImageSpec(reply,1700000000000)
    prove(spec.message=="原合法合成评论\n目录验证"&&spec.authorName=="任务合成作者","actual existing original spec normalization")
    val pageLock=Any()
    fun commit(block:()->Unit):Boolean=sessions.withCurrentDynamicCacheOwner(owner){
        synchronized(pageLock){if(!locations.withCommit(block))throw CancellationException("fixture lifetime retired")}
    }
    suspend fun checkpoint(){currentCoroutineContext().ensureActive();if(!life.isActive())throw CancellationException("fixture closed")}
    fun noScratch(directory:Path)=Files.list(directory).use{files->files.noneMatch{it.fileName.toString().startsWith(".bilipai-comment-")}}
    suspend fun save(name:String,gate:((()->Unit)->Boolean)=::commit,write:suspend(DesktopDynamicSaveTarget)->Boolean={target->
        writeDesktopReplyCommentImage(spec,target.path,life::isActive,target.replaceExisting,gate)
    })=locations.save(name,::checkpoint,gate,write)

    prove(save("BiliPai_comment_custom.png"),"actual custom directory writer success")
    val png=ImageIO.read(custom.resolve("BiliPai_comment_custom.png").toFile())
    prove(png.width==1080&&png.height>0,"actual writer PNG independently decodes")
    prove(defaultCalls.get()==0&&noScratch(custom),"custom success no default/no scratch")

    var attempts=0
    prove(save("BiliPai_comment_fallback.png",write={target->
        attempts++
        if(target.path.parent==custom)false
        else writeDesktopReplyCommentImage(spec,target.path,life::isActive,target.replaceExisting,::commit)
    }),"custom Boolean false proceeds to actual default writer")
    prove(attempts==2&&defaultCalls.get()==1,"custom then default exactly one each")
    prove(!Files.exists(custom.resolve("BiliPai_comment_fallback.png"))&&ImageIO.read(defaults.resolve("BiliPai/BiliPai_comment_fallback.png").toFile()).width==1080,"default alone receives real PNG")
    prove(noScratch(custom)&&noScratch(defaults.resolve("BiliPai")),"fallback scratch drained")
    prove(preferences.getImageSaveTreeUriSync()==custom.toUri().toString(),"fallback preserves same preference")

    val sentinel=byteArrayOf(8,4,2);val original=custom.resolve("BiliPai_comment_cancel.png");Files.write(original,sentinel)
    val firstAdmission=CountDownLatch(1);val locked=CountDownLatch(1);val release=CountDownLatch(1)
    val monitor=DesktopSessionStore::class.java.getDeclaredField("lock").apply{isAccessible=true}.get(sessions)
    val blocker=Thread({check(firstAdmission.await(10,TimeUnit.SECONDS));synchronized(monitor){locked.countDown();check(release.await(15,TimeUnit.SECONDS))}},"task-png-real-store-blocker")
    blocker.start();val gateCalls=AtomicInteger()
    val queuedGate:((()->Unit)->Boolean)={block->
        val ordinal=gateCalls.incrementAndGet()
        val accepted=commit(block)
        if(ordinal==1){firstAdmission.countDown();check(locked.await(10,TimeUnit.SECONDS))}
        accepted
    }
    val saving=async(Dispatchers.IO){save("BiliPai_comment_cancel.png",gate=queuedGate)}
    val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
    var finalBlocked=false
    while(System.nanoTime()<until&&!finalBlocked){
        finalBlocked=gateCalls.get()==2&&Thread.getAllStackTraces().any{(thread,stack)->thread.state==Thread.State.BLOCKED&&stack.any{it.className==DesktopSessionStore::class.java.name&&it.methodName=="withCurrentDynamicCacheOwner"}}
        if(!finalBlocked)delay(5)
    }
    try {
        prove(finalBlocked,"actual complete PNG waits on actual SessionStore final gate")
        val scratch=Files.list(custom).use{files->files.filter{it.fileName.toString().startsWith(".bilipai-comment-")}.toList()}
        prove(scratch.size==1&&ImageIO.read(scratch.single().toFile()).width==1080,"blocked stage is complete actual writer PNG")
        saving.cancel()
        prove(life.isActive()&&currentCoroutineContext().isActive,"cancel only save Job; window lifetime and caller alive")
    } finally {release.countDown()}
    saving.join();blocker.join()
    prove(saving.isCancelled,"real saving Job cancellation propagates")
    prove(sessions.dynamicCacheOwner()==owner&&life.isActive(),"same actual Store owner and lifetime remain alive")
    prove(defaultCalls.get()==1&&gateCalls.get()==2,"cancel never fallback or re-admit")
    prove(Files.readAllBytes(original).contentEquals(sentinel)&&!Files.exists(custom.resolve("BiliPai_comment_cancel (1).png")),"canceled old job preserves collision sentinel/no late target")
    prove(noScratch(custom)&&noScratch(defaults.resolve("BiliPai")),"cancel drains PNG scratch")
    prove(preferences.getImageSaveTreeUriSync()==custom.toUri().toString(),"cancel preserves global preference")
    life.close()
    val result=buildJsonObject{
        put("passed",true);put("assertions",assertions);put("cases",3);put("codeSources",JsonArray(codeSources))
        put("actualMain04LocationsPrefsWriterStore",true);put("actualStoreFinalGateBlocked",true);put("zeroProductOverrides",true)
        put("CommunityOriginalButtonClicked",false);put("RootComposeMounted",false);put("HTTP",false);put("HWND",false);put("actualChooser",false)
        put("defaultResolverIsTaskPathCallback",true);put("actualKnownFolderCalled",false)
    }
    Files.writeString(root.resolve("result.json"),result.toString());println(result)
}
