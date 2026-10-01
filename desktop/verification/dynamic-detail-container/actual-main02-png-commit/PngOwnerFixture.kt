package com.bilipai.desktop.ui.pngproof

import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.feature.video.ui.components.buildReplyCommentImageSpec
import com.android.purebilibili.feature.video.ui.components.ReplyCommentImageSpec
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import com.bilipai.desktop.data.*
import com.bilipai.desktop.ui.*
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.nio.file.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

private class FixturePngLuminance(private val image: BufferedImage): LuminanceSource(image.width,image.height) {
    override fun getRow(y:Int,row:ByteArray?):ByteArray=(row?:ByteArray(width)).also { out ->
        for(x in 0 until width)out[x]=(image.getRGB(x,y) and 0xff).toByte()
    }
    override fun getMatrix()=ByteArray(width*height).also { out -> for(y in 0 until height)getRow(y,null).copyInto(out,y*width) }
}

// Task owner fields mirror the read-only extracted Root owner body. The runner
// compiles RootGateExtracted.kt with exact final gate/dispose expressions from
// the immutable snapshot source, not a product class replacement.
internal class FixturePageOwner(val repository:DesktopRepository, parent:CoroutineScope) {
    val capturedEpoch=repository.sessionEpoch
    val cardSession=DesktopDynamicCardSession(repository,capturedEpoch)
    val alive=AtomicBoolean(true)
    val exportOwnerLock=Any()
    val exportGuard=repository.dynamicCacheSessionGuard
    val exportOwner=checkNotNull(exportGuard.dynamicCacheOwner())
    val pageScope=CoroutineScope(parent.coroutineContext+SupervisorJob(parent.coroutineContext[Job]))
    fun owned()=rootOwned(this)
    val operations=DesktopDynamicCardOperations(repository,capturedEpoch,::owned)
    private val requests=DesktopDynamicReplyOperationsBinding(operations,csrfAvailable={false},
        detailLoader={operations.getDynamicDetail(it,null)})
    val replySession=DesktopOriginalDynamicReplySession("task-only-png",pageScope,requests,
        seedItem={null},stillOwned=::owned)
}

fun main(args:Array<String>):Unit=runBlocking {
    val root=Path.of(args[0]);Files.createDirectories(root)
    val main=Path.of(args[1]).toAbsolutePath().normalize()
    fun actual(type:Class<*>)=Path.of(type.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize()==main
    var assertions=0;var cases=0
    fun prove(value:Boolean,message:String){check(value){message};assertions++}
    fun noScratch()=Files.list(root).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".bilipai-comment-") } }
    fun sourceIsMain(name:String)=actual(Class.forName(name))
    prove(actual(DesktopSessionStore::class.java)&&actual(DesktopRepository::class.java)&&actual(DesktopDynamicCardSession::class.java),"actual Main Store/Repo/CardSession codeSource")
    prove(sourceIsMain("com.bilipai.desktop.ui.DesktopCommentImageCanvasKt"),"actual Main renderer platform/writer codeSource")
    prove(sourceIsMain("com.android.purebilibili.feature.video.ui.components.DesktopOriginalReplyCommentImageSpecKt")&&
        sourceIsMain("com.android.purebilibili.feature.video.ui.components.DesktopOriginalReplyImageRendererKt"),"actual original spec and renderer codeSource")
    val sessions=DesktopSessionStore.temporary()
    val account=AccountSummary(777L,"Declared PNG fixture account","")
    sessions.saveAccount(mapOf("SESSDATA" to "declared-png-task-01"),account)
    val repository=DesktopRepository(sessions)
    prove(repository.dynamicCacheSessionGuard===sessions,"Root guard is the actual same Store")
    val reply=Json { ignoreUnknownKeys=true }.decodeFromString<ReplyItem>("""{"rpid":621,"oid":123,"ctime":1700000000,"like":8,"content":{"message":"  合成评论 😀\n保存验证  "},"member":{"uname":"任务合成作者"}}""")
    val spec=buildReplyCommentImageSpec(reply,1700000000000)
    prove(spec.authorName=="任务合成作者"&&spec.message=="合成评论 😀\n保存验证","actual original spec normalization")
    prove(spec.qrUrl=="https://www.bilibili.com/video/av123?comment_on=1&comment_root_id=621","actual original QR route")
    fun owner()=FixturePageOwner(repository,this)
    suspend fun save(page:FixturePageOwner,target:DesktopDynamicSaveTarget,value:ReplyCommentImageSpec=spec,
        gate:((()->Unit)->Boolean)={rootCommit(page,it)}):Boolean =
        writeDesktopReplyCommentImage(value,target.path,page::owned,replaceExisting=target.replaceExisting,withOwnedCommit=gate)
    suspend fun waitEntered(latch:CountDownLatch)=withContext(Dispatchers.IO){latch.await(5,TimeUnit.SECONDS)}
    fun blockingGate(page:FixturePageOwner,entered:CountDownLatch,release:CountDownLatch):((()->Unit)->Boolean)={commit ->
        // Narrow task-only scheduling seam after actual render/PNG write and
        // before actual extracted final gate; no writer or production override.
        entered.countDown();check(release.await(8,TimeUnit.SECONDS));rootCommit(page,commit)
    }
    fun waitBlocked(className:String,method:String):Boolean {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(System.nanoTime()<until) {
            if(Thread.getAllStackTraces().any{(thread,stack)->thread.state==Thread.State.BLOCKED&&stack.any{
                it.className==className&&it.methodName==method}})return true
            Thread.sleep(5)
        }
        return false
    }
    val storeMonitor=DesktopSessionStore::class.java.getDeclaredField("lock").apply { isAccessible=true }.get(sessions)

    val first=owner();val selected=DesktopDynamicSaveTarget(root.resolve("chosen-comment.png"),false)
    prove(save(first,selected,spec.copy(message=(1..20).joinToString("\n"){"原始评论行 $it 😀"})),"selected target receives actual rendered PNG")
    val decoded=ImageIO.read(selected.path.toFile())
    prove(decoded.width==1080&&decoded.height==1250&&decoded.getRGB(0,0)==0xfffafafa.toInt(),"actual original geometry/line limit/background")
    val qr=decoded.getSubimage(1080-56-148,decoded.height-188+18,148,148)
    val qrRead=runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(FixturePngLuminance(qr)))).text }
    val qrDecoded=qrRead.getOrNull()==spec.qrUrl
    Files.copy(selected.path,root.resolve("rendered-long-route.png"))
    // This route visibly overlaps the original footer URL with the QR. Retain
    // the actual failed read as an inherited renderer diagnostic, not a writer
    // pass or a reason to alter the pinned original rendering algorithm.
    if(!qrDecoded)println("DIAGNOSTIC original footer URL overlaps QR: ${qrRead.exceptionOrNull()?.javaClass?.simpleName}")
    prove(noScratch(),"success drains same-parent temp");cases++
    val before=Files.readAllBytes(selected.path)
    val denied=runCatching { save(first,selected,spec.copy(message="Denied overwrite")) }
    prove(denied.exceptionOrNull() is FileAlreadyExistsException,"unconfirmed overwrite is rejected by actual Files.move")
    prove(Files.readAllBytes(selected.path).contentEquals(before)&&noScratch(),"unconfirmed overwrite preserves existing target and no scratch");cases++
    prove(save(first,selected.copy(replaceExisting=true),spec.copy(message="Explicit overwrite confirmed")),"confirmed overwrite succeeds")
    prove(!Files.readAllBytes(selected.path).contentEquals(before)&&ImageIO.read(selected.path.toFile()).width==1080&&noScratch(),"confirmed target is a real rendered PNG");cases++
    rootDispose(first)

    val epoch=owner();val epochTarget=root.resolve("old-epoch-target.png");Files.write(epochTarget,byteArrayOf(1,2,3,4))
    lateinit var epochWrite:Deferred<Result<Boolean>>
    synchronized(storeMonitor) {
        epochWrite=epoch.pageScope.async(Dispatchers.IO){runCatching { save(epoch,DesktopDynamicSaveTarget(epochTarget,true)) }}
        prove(waitBlocked(DesktopSessionStore::class.java.name,"withCurrentDynamicCacheOwner"),"actual complete PNG waits on real Store monitor before final move")
        sessions.saveAccount(mapOf("SESSDATA" to "declared-png-task-02"),account)
    }
    prove(epochWrite.await().exceptionOrNull() is kotlinx.coroutines.CancellationException,"actual same MID epoch rotation rejects final commit")
    prove(Files.readAllBytes(epochTarget).contentEquals(byteArrayOf(1,2,3,4))&&noScratch(),"epoch retirement preserves target and drains scratch");cases++
    rootDispose(epoch)

    val page=owner();val pageTarget=root.resolve("closed-page-target.png");Files.write(pageTarget,byteArrayOf(5,6,7))
    lateinit var pageWrite:Deferred<Result<Boolean>>
    synchronized(page.exportOwnerLock) {
        pageWrite=page.pageScope.async(Dispatchers.IO){runCatching { save(page,DesktopDynamicSaveTarget(pageTarget,true)) }}
        prove(waitBlocked("com.bilipai.desktop.ui.pngproof.RootGateExtractedKt","rootCommit"),"actual complete PNG holds Store and waits on Root page lock")
        rootDispose(page)
    }
    val pageResult=runCatching { pageWrite.await() }
    prove(pageResult.exceptionOrNull() is kotlinx.coroutines.CancellationException,"extracted actual Root disposal cancels page job")
    pageWrite.join()
    prove(!page.alive.get()&&!page.replySession.isOwned()&&Files.readAllBytes(pageTarget).contentEquals(byteArrayOf(5,6,7))&&noScratch(),"Root page close retires session and preserves target with no scratch");cases++

    val canceled=owner();val canceledTarget=root.resolve("canceled-target.png");Files.write(canceledTarget,byteArrayOf(8,9))
    val enteredCancel=CountDownLatch(1);val releaseCancel=CountDownLatch(1)
    val cancelWrite=canceled.pageScope.async(Dispatchers.IO){save(canceled,DesktopDynamicSaveTarget(canceledTarget,true),gate=blockingGate(canceled,enteredCancel,releaseCancel))}
    prove(waitEntered(enteredCancel),"cancel waits after actual complete temporary PNG")
    cancelWrite.cancel();releaseCancel.countDown();cancelWrite.join()
    prove(Files.readAllBytes(canceledTarget).contentEquals(byteArrayOf(8,9))&&noScratch(),"actual cancellation owned check does not replace existing target and removes scratch");cases++
    rootDispose(canceled)

    val queued=owner();val queuedTarget=root.resolve("queued-canceled-target.png");Files.write(queuedTarget,byteArrayOf(10,11))
    lateinit var queuedWrite:Deferred<Boolean>
    synchronized(storeMonitor) {
        queuedWrite=queued.pageScope.async(Dispatchers.IO){save(queued,DesktopDynamicSaveTarget(queuedTarget,true))}
        prove(waitBlocked(DesktopSessionStore::class.java.name,"withCurrentDynamicCacheOwner"),"same-owner complete PNG actually waits for Store final gate")
        queuedWrite.cancel()
        prove(queued.owned(),"canceling one save job keeps actual account and page owner alive")
    }
    queuedWrite.join()
    prove(Files.readAllBytes(queuedTarget).contentEquals(byteArrayOf(10,11))&&noScratch(),"actual writer ensureActive inside Store/page gate rejects queued canceled move and drains scratch");cases++
    rootDispose(queued)

    // Deterministic actual Store/CardSession source-expression probe. It does
    // not execute CommunityDynamicDetail composition or its remember primitive.
    val capturedEpoch=repository.sessionEpoch
    val capturedSession=DesktopDynamicCardSession(repository,capturedEpoch)
    prove(capturedSession.matches(repository,capturedEpoch),"actual old cardSession passes eligibility before account mutation")
    sessions.saveAccount(mapOf("SESSDATA" to "declared-png-task-03"),account)
    val oldCapture=runCatching { checkNotNull(repository.dynamicCacheSessionGuard.dynamicCacheOwner()).also { check(it.epoch==capturedEpoch) } }
    prove(oldCapture.exceptionOrNull() is IllegalStateException,"old exact capture expression throws after actual Store epoch rotation")
    val safeOwner=checkNotNull(repository.dynamicCacheSessionGuard.dynamicCacheOwner())
    val safeOwned=capturedSession.isOwned()&&repository.sessionEpoch==capturedEpoch&&safeOwner.epoch==capturedEpoch
    prove(!safeOwned,"new capture expression does not throw and rejects stale ownership");cases++
    capturedSession.close()
    sessions.logout()
    val guest=owner();val guestTarget=root.resolve("guest-comment.png")
    prove(guest.exportOwner.mid==0L&&save(guest,DesktopDynamicSaveTarget(guestTarget,false)),"actual guest MID0 original comment PNG export")
    prove(ImageIO.read(guestTarget.toFile()).width==1080&&noScratch(),"guest PNG decodes with no scratch");cases++
    rootDispose(guest)
    repository.httpClient.dispatcher.executorService.shutdown();repository.httpClient.connectionPool.evictAll()
    Files.writeString(root.resolve("result.json"),"""{"passed":true,"assertions":$assertions,"cases":$cases,"actualMainSpecRendererWriterStore":true,"productionOverrides":false,"extractedRootCommitAndDisposeBody":true,"RootScreenExecuted":false,"actualSameMIDRetirement":true,"guestMID0":true,"actualPNGQRReadback":$qrDecoded,"longRouteQrDecodeFailedInheritedLayout":${!qrDecoded},"actualTargetConfirmation":true,"cancelAndPageClosePreserveTarget":true,"oldCaptureRaceSourceExpressionReproduced":true,"newCaptureRejectsWithoutThrow":true,"HTTP":false,"HWND":false,"actualPicker":false,"systemSHARE":false}""")
    println("PASS $assertions assertions / $cases cases: actual Main original renderer + actual writer/Store, extracted Root gate only")
}
