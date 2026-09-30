@file:Suppress("DEPRECATION")
package com.bilipai.desktop.diagnostics.leaseproof

import com.android.purebilibili.core.util.*
import com.bilipai.desktop.diagnostics.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.nio.channels.FileChannel
import java.security.Permission
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javax.swing.SwingUtilities

private val checks=mutableListOf<String>()
private fun verify(name:String,value:Boolean){check(value){name};checks+=name;println("PASS $name")}
private class Fence:SecurityManager(){val attempts=AtomicInteger();override fun checkPermission(p:Permission){}
    override fun checkConnect(h:String,p:Int){attempts.incrementAndGet();error("No network")}
    override fun checkListen(p:Int){attempts.incrementAndGet();error("No listen")}
    override fun checkMulticast(a:java.net.InetAddress){attempts.incrementAndGet();error("No multicast")}
    override fun checkExec(c:String){attempts.incrementAndGet();error("No process")}}
/** Synthetic SDK event contract only; no WinRT UI, receiver or HWND exists. */
private class EventPort:DesktopNativeShareTransport {
    data class Item(val path:Path,val state:AtomicInteger=AtomicInteger(),val supplied:AtomicBoolean=AtomicBoolean())
    val live=ConcurrentHashMap<Long,Item>();val history=CopyOnWriteArrayList<Item>()
    val next=AtomicLong();var mode=3;var maxLive=0;var retired=0;var shown=0
    val failRetireOnce=AtomicBoolean();var exposureWasDurable=true;var retiredOnEdt=true
    override fun prepare(path:Path):Long {
        val token=next.incrementAndGet();val item=Item(path);live[token]=item;history+=item;maxLive=maxOf(maxLive,live.size);return token
    }
    override fun show(token:Long):Boolean {
        check(SwingUtilities.isEventDispatchThread());val item=live.getValue(token)
        exposureWasDurable=exposureWasDurable && Files.readString(item.path.parent.resolve(".lease")).startsWith("MAY_EXPOSE\n")
        shown++;item.state.set(mode)
        item.supplied.set(mode in setOf(2,3,4,5))
        return mode!=0
    }
    override fun state(token:Long)=live.getValue(token).state.get()
    override fun retire(token:Long):DesktopNativeShareRetirement {
        retiredOnEdt=retiredOnEdt && SwingUtilities.isEventDispatchThread()
        if(failRetireOnce.compareAndSet(true,false))error("synthetic revoke HRESULT failure")
        val item=live.remove(token) ?: error("Already retired")
        retired++;return DesktopNativeShareRetirement(item.state.get(),item.supplied.get())
    }
}
private fun cacheFiles(root:Path):List<Path> {
    val cache=root.resolve("cache/diagnostic-share")
    if(!Files.exists(cache))return emptyList()
    return Files.walk(cache).use{it.filter(Files::isRegularFile).toList()}
}
private suspend fun eventually(test:()->Boolean)=withTimeout(5000){while(!test())delay(10)}

fun main(args:Array<String>):Unit=runBlocking {
    val case=args[0];val out=Path.of(args[1]);Files.createDirectories(out)
    val root=Files.createTempDirectory("native-share-lease-")
    val store=DesktopPluginStore(root);val held=DesktopPluginStore(root)
    val writer=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,LinkedBlockingQueue<Runnable>()){r->Thread(r,"fixture-actual-diagnostic-writer").apply{isDaemon=true}}
    val actor=DesktopDiagnostics(store,"fixture-native-leases",writer=writer)
    val api=EventPort();val provider=DesktopNativeCrashShare({error("No native DLL in this synthetic contract fixture")},"0".repeat(64),{null},actor,{api})
    val lifecycle=DesktopDiagnosticLifecycle(actor,provider)
    val fence=Fence();System.setSecurityManager(fence)
    val release=CountDownLatch(1)
    try {
        check(actor.persistLocalCrash(IllegalStateException("synthetic-private-crash")));actor.flush()
        val snapshot=resolveCrashSnapshotFile(root.toFile()).toPath();val marker=resolveCrashSnapshotMarkerFile(root.toFile()).toPath()
        val original=Files.readAllBytes(snapshot)
        when(case) {
            "repeat-terminal"->{
                repeat(40){index->
                    api.mode=if(index%2==0)3 else 4
                    check(provider.shareSnapshot())
                    eventually{api.live.isEmpty()&&cacheFiles(root).isEmpty()};actor.flush()
                    check(cacheFiles(root).isEmpty())
                }
                verify("40 completed/canceled event-contract shares free every native session and private lease",api.retired==40&&api.shown==40&&api.maxLive==1)
                verify("terminal sharing exceeds prior 8 and 16 limits without cumulative degradation",cacheFiles(root).isEmpty())
                verify("original crash snapshot and pending marker remain owned by original action policy",Files.readAllBytes(snapshot).contentEquals(original)&&Files.exists(marker))
                verify("exposure intent is durable before transport Show and retirement runs on EDT",api.exposureWasDurable&&api.retiredOnEdt)
            }
            "missing-events"->{
                api.mode=2;check(provider.shareSnapshot());val first=api.history.single().path;val bytes=Files.readAllBytes(first)
                delay(700)
                verify("no completion event and no timeout deletes an exposed copy",Files.readAllBytes(first).contentEquals(bytes)&&api.live.size==1)
                check(provider.shareSnapshot());val second=api.history.last().path
                verify("new explicit SHARE retires the old window session before a fresh single source",api.retired==1&&api.live.size==1&&api.maxLive==1&&first!=second)
                verify("retired unknown first copy remains unchanged while new copy is granted",Files.readAllBytes(first).contentEquals(bytes)&&Files.exists(second))
                lifecycle.shutdownForRestore()
                verify("shutdown retires source references but preserves both unknown receiver copies",api.live.isEmpty()&&Files.exists(first)&&Files.exists(second))
                val actor2=DesktopDiagnostics(DesktopPluginStore(root),"fresh-owner")
                val api2=EventPort();api2.mode=3
                val provider2=DesktopNativeCrashShare({error("No DLL")},"0".repeat(64),{null},actor2,{api2})
                val lifecycle2=DesktopDiagnosticLifecycle(actor2,provider2)
                try {
                    check(provider2.shareSnapshot());eventually{api2.live.isEmpty()};actor2.flush()
                    verify("fresh actor keeps persistent unknown leases while completing a new share",Files.exists(first)&&Files.exists(second)&&api2.retired==1)
                    actor2.clearAll()
                    verify("explicit same-actor clearAll removes persistent copies and original diagnostic files",cacheFiles(root).isEmpty()&&!Files.exists(snapshot)&&!Files.exists(marker))
                }finally{lifecycle2.shutdownForRestore()}
            }
            "before-data"->{
                api.mode=1;check(provider.shareSnapshot());val old=api.history.last().path
                api.mode=0;check(!provider.shareSnapshot())
                verify("unfulfilled pane request is distinguished from an exposed copy by post-drain outcome",!Files.exists(old)&&api.retired==2&&api.live.isEmpty())
                verify("Show false and no supplied storage items leave no private lease",cacheFiles(root).isEmpty())
                verify("failed or unfulfilled native pane leaves original crash evidence intact",Files.readAllBytes(snapshot).contentEquals(original)&&Files.exists(marker))
            }
            "clear-queued"->{
                val entered=CountDownLatch(1);writer.execute{entered.countDown();release.await()}
                check(entered.await(5,TimeUnit.SECONDS))
                val share=async(Dispatchers.Default){provider.shareSnapshot()}
                eventually{writer.queue.isNotEmpty()}
                val clear=async(Dispatchers.Default){actor.clearAll()}
                delay(50)
                verify("clearAll waits the accepted actor copy while retiring its generation",share.isActive&&clear.isActive&&api.next.get()==0L)
                release.countDown();check(!share.await());clear.await()
                verify("late accepted copy never calls native Show and cannot resurrect cleared cache",api.shown==0&&api.next.get()==0L&&cacheFiles(root).isEmpty())
                verify("same actor clearAll finishes original diagnostic deletion after the share drain",!Files.exists(snapshot)&&!Files.exists(marker))
                check(actor.persistLocalCrash(IllegalStateException("new-crash-after-explicit-clear")))
                api.mode=3;check(provider.shareSnapshot());eventually{api.live.isEmpty()&&cacheFiles(root).isEmpty()};actor.flush()
                verify("successful clearAll reopens only a fresh share generation",api.retired==1&&cacheFiles(root).isEmpty())
            }
            "clear-live-retry"->{
                api.mode=2;check(provider.shareSnapshot());val copy=api.history.last().path
                api.failRetireOnce.set(true)
                verify("COM retirement failure blocks deletion and retains retry ownership",runCatching{actor.clearAll()}.isFailure&&Files.exists(copy)&&Files.exists(snapshot)&&api.live.size==1)
                actor.clearAll()
                verify("clearAll retry revokes native before deleting unknown lease and original files",api.live.isEmpty()&&!Files.exists(copy)&&!Files.exists(snapshot)&&cacheFiles(root).isEmpty())
                verify("retired old source cannot publish or supply new data after clearAll",api.history.first().state.get()==2&&api.live.isEmpty())
                check(actor.persistLocalCrash(IllegalStateException("fresh-crash")));api.mode=4
                check(provider.shareSnapshot());eventually{api.live.isEmpty()&&cacheFiles(root).isEmpty()};actor.flush()
                verify("new source after retry uses a distinct lease and remains usable",api.retired==2&&cacheFiles(root).isEmpty())
            }
            "locked-copy"->{
                api.mode=2;check(provider.shareSnapshot());val copy=api.history.last().path
                FileChannel.open(copy,StandardOpenOption.READ,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE).use {
                    api.live.values.single().state.set(3)
                    eventually{api.live.isEmpty() && actor.error.value!=null}
                    verify("real Windows file handle can reject unlink after native terminal retirement",Files.exists(copy)&&Files.readString(copy.parent.resolve(".lease")).startsWith("TERMINAL\n"))
                    verify("filesystem failure leaves visible safe error and no retained native session",actor.error.value!=null&&api.live.isEmpty())
                }
                api.mode=3;check(provider.shareSnapshot());eventually{api.live.isEmpty()&&cacheFiles(root).isEmpty()};actor.flush()
                verify("next explicit request prunes known-terminal disk lease after lock release",!Files.exists(copy)&&cacheFiles(root).isEmpty()&&api.retired==2)
            }
            "budget"->{
                check(actor.persistLocalCrash(IllegalStateException(("G".repeat(127)+"\n").repeat(300*1024/128))))
                check(Files.size(snapshot)==256L*1024)
                val external=root.resolve("unrelated-sparse.bin")
                FileChannel.open(external,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use {
                    it.position(32L*1024*1024-1);it.write(java.nio.ByteBuffer.wrap(byteArrayOf(1)))
                }
                api.mode=2;repeat(64){check(provider.shareSnapshot())}
                val retained=api.history.map{it.path};val sizes=retained.sumOf(Files::size)
                verify("only managed private snapshot bytes count toward the exact 16MiB budget",sizes==16L*1024*1024&&Files.size(external)==32L*1024*1024)
                verify("budget is checked before another native grant without mutating old copies",!provider.shareSnapshot()&&api.shown==64&&api.live.isEmpty()&&retained.all(Files::exists))
                verify("budget exhaustion exposes safe degradation state rather than fake receiver completion",provider.error.value!=null&&retained.all{Files.readString(it.parent.resolve(".lease")).startsWith("RETIRED\n")})
                lifecycle.crashPrompt.load();lifecycle.crashPrompt.handle(com.android.purebilibili.CrashLogPromptAction.SHARE)
                verify("same lifecycle original SHARE exposes the fixed 16MiB reason and real local-viewer fallback",lifecycle.crashPrompt.state.value.viewerRequested&&lifecycle.crashPrompt.state.value.error?.contains("16MiB")==true&&!Files.exists(marker)&&Files.exists(snapshot))
                actor.clearAll()
                verify("explicit clear releases full managed budget while leaving unrelated files untouched",cacheFiles(root).isEmpty()&&Files.size(external)==32L*1024*1024)
                check(actor.persistLocalCrash(IllegalStateException("fresh-after-budget-clear")));api.mode=3
                check(provider.shareSnapshot());eventually{api.live.isEmpty()&&cacheFiles(root).isEmpty()};actor.flush()
                verify("native sharing recovers after explicit budget cleanup",api.retired==65&&cacheFiles(root).isEmpty())
            }
            "restore-retirement"->{
                api.mode=2;check(provider.shareSnapshot());val copy=api.history.last().path
                lifecycle.shutdownForRestore();held.freezeWrites()
                verify("restore drains native and actor before the authoritative global backing freezes",api.live.isEmpty()&&Files.exists(copy))
                verify("retired provider and actor reject all new lease writes or grants",!provider.shareSnapshot()&&runCatching{actor.prepareNativeCrashShareLease()}.isFailure)
                verify("old existing global facade cannot write after restore freeze",runCatching{held.update("settings",mapOf("late" to JsonPrimitive(true)))}.isFailure)
                verify("unknown exposed read-only bytes stay intact across retirement",Files.readAllBytes(copy).contentEquals(original))
            }
            else->error("Unknown fixture")
        }
        lifecycle.shutdownForRestore();lifecycle.shutdownForRestore()
        verify("repeated shutdown remains idempotent after lease changes",api.live.isEmpty())
        verify("no network listener multicast process or real share receiver",fence.attempts.get()==0)
        out.resolve("result.json").toFile().writeText(buildJsonObject {
            put("passed",true);put("case",case);put("checks",JsonArray(checks.map(::JsonPrimitive)))
            put("taskRoot",root.toString());put("syntheticSdkEventPort",true);put("nativeRetired",api.retired)
            put("maxNativeLive",api.maxLive);put("nativeShownPortCalls",api.shown);put("networkAttempts",fence.attempts.get())
            put("actualShareUI",false);put("realReceiver",false);put("HWND",false);put("currentMainExecuted",false)
        }.toString())
    }finally{release.countDown();lifecycle.shutdownForRestore();System.setSecurityManager(null)}
}
