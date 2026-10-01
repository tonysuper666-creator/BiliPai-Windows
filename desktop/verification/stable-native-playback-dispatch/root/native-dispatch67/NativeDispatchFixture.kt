package com.bilipai.desktop

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.dash.AdaptiveDashPlaybackSource
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs

/** Installed NativeOwner/controls/Repository/Store and a real local MPV source.
 * Fixture delays only a selected publication call BEFORE all admission locks. */
object NativeDispatchFixture {
    @JvmStatic fun main(args:Array<String>)=runBlocking {
        val output=Path.of(args[0]);val clip=Path.of(args[1]);val checks=mutableListOf<String>()
        fun verify(ok:Boolean,name:String){check(ok){name};checks+=name}
        suspend fun await(name:String,ready:()->Boolean)=withTimeout(15_000L){while(!ready())delay(20L);verify(true,name)}
        suspend fun ui(block:()->Unit)=withContext(Dispatchers.Swing){block()}
        class Gate(val matches:(Thread)->Boolean) {
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            fun stop(){entered.countDown();check(release.await(8,TimeUnit.SECONDS)){"Fixture gate timeout"}}
        }
        class Fixture(val name:String):AutoCloseable {
            val store=DesktopSessionStore.temporary();val repository=DesktopRepository(store)
            val delegate=DesktopRepositoryPlaybackPublication(repository)
            val nextGate=AtomicReference<Gate?>(null);val gates=mutableListOf<Gate>()
            val publication=object:DesktopPlaybackPublication by delegate {
                override fun <T> admit(source:PlaybackSource,stillOwned:()->Boolean,block:()->T):T {
                    nextGate.get()?.let { gate ->
                        if(gate.matches(Thread.currentThread())&&nextGate.compareAndSet(gate,null))gate.stop()
                    }
                    return delegate.admit(source,stillOwned,block)
                }
            }
            val alive=AtomicBoolean(true);val accepts=AtomicBoolean(true);val entry=Any()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Swing);val requestJob=Job()
            val player=MpvPlayer(useNullAudioOutput=true);val surface=player.surface
            val version=MutableStateFlow<Long?>(null);val publications=mutableListOf<DesktopOriginalVideoAcceptedPublication>()
            val receipt=repository.capturePlaybackAuthorization(repository.sessionEpoch){alive.get()}.receipt
            val base=PlaybackSource(clip.toUri().toString(),title="Native dispatch $name",startPaused=true,authorizationReceipt=receipt)
            val request=PlaybackRequest.create("BV-native-dispatch-$name",170001,11)
            val owner=DesktopOriginalVideoNativeOwner(player,publication,{repository.sessionEpoch},{false},alive::get,
                {block->synchronized(entry){if(!alive.get()||!accepts.get())false else {block();true}}},
                {accepted->version.value=accepted.sourceVersion;publications+=accepted})
            fun commit(block:()->Unit):Boolean=owner.current()?.let{owner.admitPlaybackDispatch(it,block)}?:false
            val control=DesktopOriginalMpvSectionControl(player,{version.value},{owner.current()!=null},::commit,
                {false},{error("Fixture does not prepare new controls media")},{error("Fixture does not replay")},{_,_,_,_->},
                scope,version,::commit)
            val queued=Collections.synchronizedList(mutableListOf<DesktopOriginalNativeSeekSubmission>())
            val listener=object:DesktopOriginalMpvOverlayControl.Listener {
                override fun onSeekQueued(submission:DesktopOriginalNativeSeekSubmission){queued+=submission}
            }
            var frame:JFrame?=null
            lateinit var accepted:DesktopOriginalVideoAcceptedPublication
            suspend fun start(){
                verify(owner.current()==null,"$name no accepted authority before publication")
                ui {
                    frame=JFrame("BiliPai native dispatch 67 $name").apply{defaultCloseOperation=JFrame.DISPOSE_ON_CLOSE
                        contentPane.add(surface);setSize(680,420);setLocation(150,130);isVisible=true}
                    accepted=owner.publish(request,base,player.currentSourceVersion,requestJob,{true})
                    control.addListener(listener)
                }
                ready("$name actual native source ready")
                requestJob.complete()
                verify(owner.current()===accepted&&owner.isCurrent(accepted),"$name actual load ACK retains accepted lease after request completes")
            }
            suspend fun ready(label:String)=await(label){
                val p=player.state.value;p.ready&&!p.loading&&!p.ended&&p.error==null&&p.videoCodec!=null&&p.nativePaused==true
            }
            fun hold(matches:(Thread)->Boolean)=Gate(matches).also{gates+=it;check(nextGate.compareAndSet(null,it))}
            suspend fun queue(lease:DesktopOriginalVideoAcceptedPublication,target:Long):DesktopOriginalNativeSeekSubmission {
                var ticket:DesktopOriginalNativeSeekSubmission?=null
                ui {verify(owner.admitPlaybackDispatch(lease){ticket=control.seekToTracked(target)},"$name captured lease admits this synchronous seek")}
                return checkNotNull(ticket).also {verify(queued.last()===it,"$name existing listener receives the exact queue object")}
            }
            override fun close(){
                gates.forEach{it.release.countDown()};alive.set(false);owner.close()
                SwingUtilities.invokeAndWait{control.removeListener(listener)}
                requestJob.cancel();scope.cancel();player.close();SwingUtilities.invokeAndWait{frame?.dispose()}
                verify(frame?.isDisplayable!=true,"$name owned fixture window disposed")
            }
        }
        Fixture("completed").use { f ->
            f.start();val lease=f.accepted
            val gate=f.hold{it.name=="BiliPai-native-player"};val ticket=f.queue(lease,1500)
            await("Actual native command waits before publication locks"){gate.entered.count==0L}
            verify(f.owner.completedSeekPositionMs(lease,ticket)==null,"Queue admission alone has no completed receipt")
            gate.release.countDown()
            await("Exact native playback restart becomes an owned completed seek"){
                f.owner.completedSeekPositionMs(lease,ticket)?.let{abs(it-1500)<100}==true
            }
            verify(f.player.surface===f.surface&&f.player.currentSourceVersion==lease.sourceVersion&&f.player.state.value.nativePaused==true,
                "Dispatch retains the real source Canvas and pause")
            verify(f.owner.completedSeekPositionMs(lease,ticket.copy(sourceVersion=lease.sourceVersion+1))==null,
                "Foreign ticket source version has no completion")
            verify(f.owner.completedSeekPositionMs(lease,ticket.copy(operationId=ticket.operationId+100))==null&&
                f.owner.completedSeekPositionMs(lease,ticket.copy(operationId=0))==null,"Unconfirmed and zero operation IDs are refused")
            val impostor=DesktopOriginalVideoAcceptedPublication(lease.request,lease.nativeSource)
            var touched=false
            verify(!f.owner.isCurrent(impostor)&&!f.owner.admitPlaybackDispatch(impostor){touched=true}&&!touched,
                "Copied metadata cannot impersonate the canonical accepted object")
            verify(f.owner.completedSeekPositionMs(impostor,ticket)==null,"Impostor cannot read a successful native history basis")
            f.accepts.set(false)
            verify(!f.owner.admitPlaybackDispatch(lease){touched=true}&&!touched&&f.owner.completedSeekPositionMs(lease,ticket)==null,
                "Required entry admission can decline even while an accepted object exists")
            f.accepts.set(true)
            verify(!f.owner.admitPlaybackDispatch(lease){throw CancellationException("Fixture cancellation")},"Canceled dispatch reports no admission")
            verify(runCatching{f.owner.admitPlaybackDispatch(lease){error("Fixture ordinary failure")}}.exceptionOrNull() is IllegalStateException,
                "Non-cancellation action failures are not swallowed")
            val second=f.queue(lease,500)
            await("Second exact operation completes"){f.owner.completedSeekPositionMs(lease,second)!=null}
            verify(f.owner.completedSeekPositionMs(lease,ticket)==null,"A later native operation does not acknowledge the older ticket")
            val media=f.owner.acceptedMedia { object:DesktopOriginalVideoMediaPort {
                override fun prepareLegacyDash(videoUrl:String,audioUrl:String?,cdnCacheKeysByUrl:Map<String,String>)=f.base
                override fun prepareAdaptiveDash(source:AdaptiveDashPlaybackSource,cdnCacheKeysByUrl:Map<String,String>):PlaybackSource?=null
                override fun prepareProgressive(url:String)=f.base
                override fun accept(source:PlaybackSource)=error("NativeOwner performs acceptance")
            } }
            ui {media.accept(lease.nativeSource.source.copy(startPositionSeconds=0.3,startPaused=true))}
            val next=checkNotNull(f.owner.current());f.ready("Accepted recovery actually loads and pauses")
            verify(next!==lease&&next.sourceVersion==lease.sourceVersion&&f.owner.isCurrent(next),"Same-version recovery publishes the new canonical source lease")
            verify(!f.owner.isCurrent(lease)&&f.owner.completedSeekPositionMs(lease,second)==null,
                "Old completed seek is retired by accepted recovery")
            verify(abs(f.player.state.value.positionSeconds-0.3)<0.12,"Accepted recovery preserves its own requested clock")
        }
        for(kind in listOf("entry","account","publication","version","payload","closed"))Fixture(kind).use { f ->
            f.start();val lease=f.accepted;val ticket=f.queue(lease,1000)
            await("$kind prior exact native operation completes"){f.owner.completedSeekPositionMs(lease,ticket)!=null}
            val caller=AtomicReference<Thread?>();val gate=f.hold{it===caller.get()};var actions=0
            val waiting=async(Dispatchers.Default){caller.set(Thread.currentThread());f.owner.admitPlaybackDispatch(lease){actions++}}
            await("$kind dispatch waits outside admission locks"){gate.entered.count==0L}
            when(kind){
                "entry"->synchronized(f.entry){f.alive.set(false)}
                "account"->f.store.logout()
                "publication"->{val snapshot=f.player.currentSourceSnapshot()!!
                    val replacement=snapshot.source.copy(nativePublication=DesktopNativePlaybackPublication{command->f.delegate.tryAdmit(f.base,{f.alive.get()}){command();true}})
                    verify(f.player.adoptPublication(lease.sourceVersion,snapshot.source,replacement),"Actual same-version foreign publication adopted")}
                "version"->ui{f.player.loadVersioned(PlaybackSource(clip.toUri().toString(),title="Foreign native source",startPaused=true))}
                "payload"->ui{check(f.player.recoverSource(lease.sourceVersion,positionSeconds=0.3,paused=true))}
                "closed"->f.owner.close()
            }
            gate.release.countDown()
            verify(!waiting.await()&&actions==0,"$kind stale captured dispatch makes no mutation after waiting")
            verify(!f.owner.isCurrent(lease)&&f.owner.current()==null&&f.owner.completedSeekPositionMs(lease,ticket)==null,
                "$kind prior native success is not usable by a retired lease")
            if(kind=="version"){f.ready("Foreign native version becomes ready");verify(f.player.currentSourceVersion!=lease.sourceVersion,"Foreign version remains intact")}
            else verify(f.player.currentSourceVersion==lease.sourceVersion,"$kind retirement does not replace the native version")
            if(kind=="payload")verify(f.player.currentSourceSnapshot()!!.source.nativePublication===lease.nativeSource.source.nativePublication,
                "Same-publication recovery changes payload and is still rejected")
            if(kind=="closed")verify(f.player.ownsSourceVersion(lease.sourceVersion),"Closing owner only retires admission and preserves the shared MPV")
        }
        val classes=listOf(MpvPlayer::class.java,DesktopOriginalVideoNativeOwner::class.java,
            DesktopOriginalVideoAcceptedPublication::class.java,DesktopOriginalNativeSeekSubmission::class.java)
        fun origin(type:Class<*>):String {
            val bytes=type.getResourceAsStream("/"+type.name.replace('.','/')+".class")!!.use{it.readBytes()}
            return "{\"class\":\"${type.name}\",\"codeSource\":\"${type.protectionDomain.codeSource.location}\",\"sha256ClassBytes\":\"${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}}\"}"
        }
        fun q(value:String)="\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\""
        Files.writeString(output,"{\"passed\":true,\"assertions\":${checks.size},\"checks\":[${checks.joinToString(","){q(it)}}],\"origins\":[${classes.joinToString(","){origin(it)}}],\"nativeExecuted\":true,\"realAccountUsed\":false,\"httpUsed\":false,\"independentVisualAcceptance\":false,\"fullOriginalOwnerMounted\":false,\"sponsorHistoryWritten\":false}")
        println("PASS ${checks.size} native captured dispatch checks; owned windows closed")
    }
}
