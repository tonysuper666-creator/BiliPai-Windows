package com.bilipai.desktop.settings

import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.diagnostics.DesktopNativeTextShare
import com.bilipai.desktop.diagnostics.DesktopNativeDiagnosticShareAssetHash
import com.bilipai.desktop.ui.DesktopVideoShareFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import java.awt.Frame
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean

/** Real pinned native owners, isolated files and our own hidden Canvas peer.
 * This is no Root/account/receiver fixture. Read-only reflection records actual
 * session identity and its real termination acknowledgment; it never changes it. */
fun main(args:Array<String>)=runBlocking {
    val work=Path.of(args[0]);val clip=work.resolve("local-media.mp4")
    val result=Path.of(args[1]);var assertions=0
    fun verify(ok:Boolean,label:String) {check(ok){label};assertions++;println("PASS $label")}
    fun field(owner:Any,name:String):Any?=owner.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(owner)
    val player=MpvPlayer(useNullAudioOutput=true)
    val active=AtomicBoolean(true)
    val dll=Path.of(System.getProperty("bp.fixture.native"))
    val share=DesktopNativeTextShare({dll},DesktopNativeDiagnosticShareAssetHash.sha256,{null})
    var frame:Frame?=null
    var stopToIdleMillis=-1L
    var detachedTerminated=false
    try {
        frame=withContext(Dispatchers.Swing) {Frame("BiliPai isolated storage native fixture").apply {
            add(player.surface);pack();setLocation(-32000,-32000)
            // pack creates a real Canvas/HWND peer. Remain hidden and never take focus.
            verify(isDisplayable && !isVisible,"owned hidden Canvas peer is displayable")
        }}
        withTimeout(15_000) {player.state.first{it.ready}}
        val initialSession=checkNotNull(field(player,"session"))
        verify((field(initialSession,"thread") as Thread).isAlive,"actual same-player NativeSession worker alive")
        val source=PlaybackSource(videoUrl=clip.toAbsolutePath().toString(),title="isolated local media",startPaused=true)
        player.load(source)
        withTimeout(15_000) {player.state.first{it.ready && !it.loading && it.videoCodec!=null && it.paused}}
        verify(field(player,"session")===initialSession,"local media load ACK on original native session")
        val stopAt=System.nanoTime()
        player.stop()
        // No delay, native-state poll or artificial idle wait before this call.
        val stoppedVersion=player.currentSourceVersion
        player.withIdleCacheMaintenance({check(active.get())}) {
            stopToIdleMillis=(System.nanoTime()-stopAt)/1_000_000L
            verify(field(player,"session")===initialSession,"Stop immediately followed by real native idle barrier")
            withContext(Dispatchers.IO) {
                val rejected=runCatching{player.load(source)}.exceptionOrNull()
                verify(rejected is IllegalStateException,"central load rejects while real idle lease spans IO")
                verify(player.currentSourceVersion==stoppedVersion,"rejected load does not mutate source version")
                Files.writeString(work.resolve("owned-io-marker.txt"),"maintenance IO held by same native owner")
                verify(Files.exists(work.resolve("owned-io-marker.txt")),"owned IO completes under native maintenance token")
                verify(runCatching{player.addSubtitle(work.resolve("local-user-subtitle.srt"),"fixture")}.isFailure,"subtitle admission rejected under native maintenance token")
            }
        }
        player.load(source)
        withTimeout(15_000) {player.state.first{it.ready && !it.loading && it.videoCodec!=null && it.paused}}
        verify(field(player,"session")===initialSession,"release permits same native owner reload ACK")
        verify(player.currentSourceVersion==stoppedVersion+1,"release preserves normal single source version increment")
        player.stop()
        withContext(Dispatchers.Swing) {frame.dispose()}
        val termination=field(initialSession,"cacheTerminated") as CompletableDeferred<*>
        detachedTerminated=termination.isCompleted
        player.withIdleCacheMaintenance({check(active.get())}) {
            verify(termination.isCompleted,"detached owner clears only after actual native terminate acknowledgment")
            verify(field(player,"session")==null,"detached idle maintenance does not create a replacement session")
        }
        // Fresh HWND creation is delayed for the duration of the same idle lease.
        player.withIdleCacheMaintenance({check(active.get())}) {
            withContext(Dispatchers.Swing) {frame.pack()}
            verify(field(player,"session")==null,"Canvas reattach during maintenance remembers HWND without new native actor")
        }
        withTimeout(15_000) {player.state.first{it.ready}}
        player.load(source)
        withTimeout(15_000) {player.state.first{it.ready && !it.loading && it.videoCodec!=null && it.paused}}
        verify(field(player,"session")!==initialSession,"reattach starts one normal session after maintenance release")
        player.stop()

        val raw=byteArrayOf(71,73,70,56,57,97,1,0,1,0,-128,0,0,0,0,0,-1,-1,-1,33,-7,4,1,0,0,0,0,44,0,0,0,0,1,0,1,0,0,2,2,68,1,0,59)
        val pool=DesktopVideoShareFiles(work.resolve("share-cache"),{raw},active::get){block->check(active.get());block();true}
        val file=pool.publish("BiliPai_share_native.gif","image/gif"){Files.write(it,raw)}
        pool.mayExpose(file)
        verify(share.probeMediaAvailable(),"verified actual native share DLL probe")
        var nativeRetired=false
        verify(!share.shareMedia(file.path,"Storage fixture","isolated original GIF",active::get){safe->
            verify(safe,"actual native null-window prepare/retire reports safe unsupplied grant")
            nativeRetired=true
            // Retain conservatively until the explicit same-actor cleanup below.
            pool.retire(file,false)
        },"null-window share reports no receiver delivery")
        verify(nativeRetired && Files.exists(file.path),"same pool retains confirmed retired file for explicit clear")
        val foreign=file.path.parent.resolve("foreign-user-picture.gif");Files.write(foreign,raw)
        share.clearVideoShareFiles {
            check(active.get());pool.clearExplicit();check(active.get())
        }
        verify(!Files.exists(file.path) && Files.exists(foreign),"same real native actor drain clears only registered pool file")
        val next=pool.publish("BiliPai_share_reuse.gif","image/gif"){Files.write(it,raw)}
        verify(!share.shareMedia(next.path,"Storage fixture","same actor reusable",active::get){safe->pool.retire(next,safe)},"same native actor and pool remain reusable after clear")
        Files.writeString(result,"""{"passed":true,"assertions":$assertions,"sameNativePlayer":true,"actualCanvasPeer":true,"hiddenPeer":true,"immediateStopIdleBarrier":true,"stopToIdleMillis":$stopToIdleMillis,"sameOwnerReloadAck":true,"detachedActualTerminationAck":$detachedTerminated,"detachTimeoutBoundaryPassed":false,"realNativeSharePrepareRetireAndDrain":true,"RootMounted":false,"accountVideoPassed":false,"systemReceiverPassed":false,"longPathSupported":false}
""")
        println("StorageNativeOwnerFixture PASS $assertions assertions; real owners, no Root/account/system receiver claim")
    } finally {
        active.set(false)
        share.shutdown()
        withContext(NonCancellable+Dispatchers.Swing) {player.close();frame?.dispose()}
    }
}
