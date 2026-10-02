package com.bilipai.desktop.ui

import com.bilipai.desktop.data.*
import com.bilipai.desktop.diagnostics.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Isolated protocol/file owner fixture. The first section calls the real native actor
 * with no window: actual verified DLL prepare/retire, no ShareUI and no receiver claim. */
fun main(args: Array<String>) = runBlocking {
    val work=Path.of(args[0]); Files.createDirectories(work)
    var assertions=0
    fun verify(ok:Boolean,label:String) {check(ok){label};assertions++;println("PASS $label")}
    val active=AtomicBoolean(true)
    val store=DesktopSessionStore(work.resolve("isolated-session.json"),persistent=false)
    store.saveAccount(mapOf("SESSDATA" to "synthetic-fixture-only"),AccountSummary(42,"fixture",""))
    val owner=checkNotNull(store.dynamicCacheOwner())
    val requests=AtomicInteger()
    // Same Assets asynchronous Call/file pipeline. This test transport supplies fixed original
    // bytes locally; it is not a new production client or a claim about the final network header.
    val raw=byteArrayOf(71,73,70,56,57,97,1,0,1,0,-128,0,0,0,0,0,-1,-1,-1,33,-7,4,1,0,0,0,0,44,0,0,0,0,1,0,1,0,0,2,2,68,1,0,59)
    val client=OkHttpClient.Builder().cookieJar(store).addInterceptor { chain ->
        val request=chain.request();requests.incrementAndGet()
        check(request.url.scheme=="https" && '@' !in request.url.toString())
        check(request.header("Referer")=="https://www.bilibili.com/")
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(raw.toResponseBody()).build()
    }.build()
    val assets=DesktopDynamicImageAssets(client,active::get,store,owner)
    var capturedMime=""; var retired=0
    val nativeDll=Path.of(System.getProperty("bp.fixture.native"))
    val actor=DesktopNativeTextShare({nativeDll},DesktopNativeDiagnosticShareAssetHash.sha256,{null})
    val pool=DesktopVideoShareFiles(work.resolve("real-native-cache"),{error("image uses same Assets reader")},active::get,{block->store.withCurrentDynamicCacheOwner(owner,block)})
    val service=DesktopImagePreviewShareBindings(assets::readOriginalShareBytes,pool,active::get) { path,title,text,owned,retirement ->
        verify(Files.readAllBytes(path).contentEquals(raw),"raw byte identity ${path.fileName.toString().substringAfterLast('.')}")
        actor.shareMedia(path,title,text,owned) {safe->verify(safe,"actual native unsupplied retirement");retired++;retirement(safe)}
    }
    for ((ext,mime) in listOf("gif" to "image/gif","webp" to "image/webp","png" to "image/png","jpg" to "image/jpeg")) {
        val original=assets.readOriginalShareBytes("//fixture.invalid/original.$ext@640w.webp")
        verify(original.contentEquals(raw),"Assets original $ext identity")
        val file=pool.publish("BiliPai_share_mime.$ext",mime){Files.write(it,original)}
        capturedMime=file.mimeType;pool.retire(file,true)
        verify(capturedMime==mime,"original MIME $ext")
        verify(!service.shareImage("//fixture.invalid/original.$ext@640w.webp",active::get),"no window reports false $ext")
    }
    verify(retired==4,"real actor exact once retirement for four formats")
    verify(Files.list(work.resolve("real-native-cache/shared_images")).use{it.count()==0L},"real native files safely retired")
    actor.shutdown()
    // Preserve the actual unsupported WinRT long-path boundary, using the same real actor.
    val longRoot=Path.of(args[1]).resolve("real-native-cache")
    var failedRetirements=0
    val longActor=DesktopNativeTextShare({nativeDll},DesktopNativeDiagnosticShareAssetHash.sha256,{null})
    val longPool=DesktopVideoShareFiles(longRoot,{raw},active::get,{block->block();true})
    val longService=DesktopImagePreviewShareBindings({raw},longPool,active::get){path,title,text,owned,retirement->
        verify(path.toString().length>260,"long native path boundary reproduced")
        longActor.shareMedia(path,title,text,owned){safe->verify(safe,"failed native prepare admits safe unsupplied cleanup");failedRetirements++;retirement(safe)}
    }
    val longFailure=runCatching{longService.shareImage("//fixture.invalid/raw.gif",active::get)}.exceptionOrNull()
    verify(longFailure is IllegalStateException,"long native prepare remains unsupported")
    verify(failedRetirements==1 && Files.list(longRoot.resolve("shared_images")).use{it.count()==0L},"failed native prepare releases logical file lease exactly once")
    longActor.shutdown()
    // Synthetic native callback seams below exercise file/route admission without opening UI.
    val published=AtomicBoolean(false);var reads=0;var nativeCalls=0
    val fencedPool=DesktopVideoShareFiles(work.resolve("unpublished"),{error("unused")},{active.get()&&published.get()},{block->if(published.get()){block();true}else false})
    val fenced=DesktopImagePreviewShareBindings({reads++;raw},fencedPool,active::get){_,_,_,_,_->nativeCalls++;true}
    val before=runCatching{fenced.shareImage("//fixture.invalid/raw.gif",active::get)}.exceptionOrNull()
    verify(before is CancellationException && reads==0 && nativeCalls==0,"unpublished Root denies download and native allocation")
    verify(!Files.exists(work.resolve("unpublished/shared_images")),"pure failed construction creates no cache")
    published.set(true)
    val leaf=AtomicBoolean(true);val started=CompletableDeferred<Unit>();val canceled=CompletableDeferred<Unit>()
    val waiting=DesktopImagePreviewShareBindings({started.complete(Unit);try{awaitCancellation()}finally{canceled.complete(Unit)}},fencedPool,active::get){_,_,_,_,_->error("retired native call")}
    supervisorScope {
        val job=launch {waiting.shareImage("//fixture.invalid/raw.gif",leaf::get)}
        started.await();leaf.set(false);withTimeout(2000){job.join();canceled.await()}
        verify(job.isCancelled,"route retirement cancels actual caller pipeline")
    }
    leaf.set(true)
    val requestStarted=CompletableDeferred<Unit>(); val requestCanceled=CompletableDeferred<Unit>()
    val callerWaiting=DesktopImagePreviewShareBindings({requestStarted.complete(Unit);try{awaitCancellation()}finally{requestCanceled.complete(Unit)}},fencedPool,active::get){_,_,_,_,_->error("canceled native call")}
    supervisorScope {
        val job=launch {callerWaiting.shareImage("//fixture.invalid/raw.gif",leaf::get)}
        requestStarted.await();job.cancelAndJoin();requestCanceled.await();verify(job.isCancelled,"caller Job cancellation drains reader")
    }
    var commitNative=0
    val commitPool=DesktopVideoShareFiles(work.resolve("commit-fence"),{raw},active::get){block->leaf.set(false);block();true}
    val commitService=DesktopImagePreviewShareBindings({raw},commitPool,active::get){_,_,_,_,_->commitNative++;true}
    verify(runCatching{commitService.shareImage("//fixture.invalid/raw.gif",leaf::get)}.exceptionOrNull() is CancellationException,"leaf retirement checked inside final publication transaction")
    verify(commitNative==0 && Files.list(work.resolve("commit-fence/shared_images")).use{it.count()==0L},"failed publication removes own stage and grants nothing")
    leaf.set(true)
    val grants=mutableListOf<Pair<()->Boolean,(Boolean)->Unit>>()
    val grantPool=DesktopVideoShareFiles(work.resolve("grants"),{raw},active::get,{block->block();true})
    val grantsService=DesktopImagePreviewShareBindings({raw},grantPool,active::get){_,_,_,owned,retire->grants.add(owned to retire);true}
    verify(grantsService.shareImage("//fixture.invalid/raw.gif",leaf::get),"protocol panel success")
    verify(grants.single().first(),"completed request Job does not retire host grant")
    verify(runCatching{grantPool.clearExplicit()}.isFailure,"explicit clear rejected before native grant drain")
    leaf.set(false);verify(!grants.single().first(),"native watcher retains actual leaf retirement fence")
    grants.single().second(true);grantPool.clearExplicit()
    verify(Files.list(work.resolve("grants/shared_images")).use{it.count()==0L},"confirmed native drain permits explicit fixture clear")
    leaf.set(true)
    val unknownPool=DesktopVideoShareFiles(work.resolve("unknown"),{raw},active::get,{block->block();true})
    val unknown=DesktopImagePreviewShareBindings({raw},unknownPool,active::get){_,_,_,_,retire->retire(false);false}
    repeat(8){verify(!unknown.shareImage("//fixture.invalid/raw.gif",leaf::get),"unknown native grant retained ${it+1}")}
    verify(runCatching{unknown.shareImage("//fixture.invalid/raw.gif",leaf::get)}.exceptionOrNull() is IllegalArgumentException,"same eight-file pool bounds unknown grants")
    verify(Files.list(work.resolve("unknown/shared_images")).use{it.count()==8L},"no unsafe unknown-grant file deletion")
    assets.close(); active.set(false)
    verify(runCatching{assets.readOriginalShareBytes("//fixture.invalid/raw.gif")}.exceptionOrNull() is CancellationException,"Assets owner retirement denies new request")
    client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
    println("RESULT assertions=$assertions nativePrepareRetire=4 ShareUI=false receiver=false longPathSupported=false productionClearWired=false")
}
