package com.bilipai.desktop

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

/** Real installed controls/Store/Repository/MPV, with a local native clip. A gate
 * pauses only the fixture's admission hook BEFORE Store/entry/native locks. */
object TypedSeekFixture {
    @JvmStatic fun main(args:Array<String>)=runBlocking {
        val output=Path.of(args[0]);val clip=Path.of(args[1]);val checks=mutableListOf<String>()
        fun verify(value:Boolean,name:String){check(value){name};checks+=name}
        suspend fun await(name:String,ready:()->Boolean)=withTimeout(15_000L){while(!ready())delay(20L);verify(true,name)}
        suspend fun ui(block:()->Unit)=withContext(Dispatchers.Swing){block()}
        class Gate {
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            fun stop(){entered.countDown();check(release.await(8,TimeUnit.SECONDS)){"Fixture gate timeout"}}
        }
        class Fixture(val name:String):AutoCloseable {
            val store=DesktopSessionStore.temporary();val repo=DesktopRepository(store)
            val publication=DesktopRepositoryPlaybackPublication(repo)
            val alive=AtomicBoolean(true);val entry=Any()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Swing)
            val player=MpvPlayer(useNullAudioOutput=true)
            val version=MutableStateFlow<Long?>(null)
            val receipt=repo.capturePlaybackAuthorization(repo.sessionEpoch){alive.get()}.receipt
            val base=PlaybackSource(clip.toUri().toString(),title="Typed seek $name",startPaused=true,authorizationReceipt=receipt)
            val nextGate=AtomicReference<Gate?>(null);val gates=mutableListOf<Gate>()
            val nativeAdmission=DesktopNativePlaybackPublication { command ->
                nextGate.getAndSet(null)?.stop()
                publication.attempt(base,{alive.get()}){synchronized(entry){if(alive.get())command()}}
            }
            val source=base.copy(nativePublication=nativeAdmission)
            fun commit(block:()->Unit):Boolean=publication.attempt(source,{alive.get()}){
                synchronized(entry){if(!alive.get())throw CancellationException("Entry retired");block()}
            }
            val control=DesktopOriginalMpvSectionControl(player,{version.value},{alive.get()&&publication.isCurrent(source)},
                ::commit,{false},{error("Fixture does not prepare another source")},{error("Fixture does not replay")},{_,_,_,_->},
                scope,version,::commit)
            val queued=Collections.synchronizedList(mutableListOf<DesktopOriginalNativeSeekSubmission>())
            val capture=ThreadLocal<DesktopOriginalNativeSeekSubmission?>()
            var captureExpected=false
            val listener=object:DesktopOriginalMpvOverlayControl.Listener {
                override fun onSeekQueued(submission:DesktopOriginalNativeSeekSubmission){
                    queued+=submission
                    if(captureExpected)capture.set(submission)
                    verify(player.state.value.seekCompletedId!=submission.operationId,"$name queue callback is not native completion")
                }
            }
            var frame:JFrame?=null
            suspend fun start(){
                ui {
                    frame=JFrame("BiliPai typed seek 66 $name").apply{defaultCloseOperation=JFrame.DISPOSE_ON_CLOSE
                        contentPane.add(player.surface);setSize(680,420);setLocation(150,130);isVisible=true}
                    check(commit{version.value=player.loadVersioned(source)})
                    control.addListener(listener)
                }
                await("$name actual native paused source ready"){
                    val p=player.state.value;p.ready&&!p.loading&&!p.ended&&p.error==null&&p.videoCodec!=null&&p.nativePaused==true
                }
            }
            fun hold():Gate=Gate().also{gates+=it;check(nextGate.compareAndSet(null,it))}
            suspend fun queue(target:Long):DesktopOriginalNativeSeekSubmission {
                var result:DesktopOriginalNativeSeekSubmission?=null
                ui {
                    captureExpected=true
                    try{result=control.seekToTracked(target);verify(capture.get()===result&&result!=null,"$name lexical callback captures this exact ticket")}
                    finally{captureExpected=false;capture.remove()}
                }
                return checkNotNull(result)
            }
            suspend fun blocked(gate:Gate){await("$name actor paused before actual admission"){gate.entered.count==0L}}
            override fun close(){
                gates.forEach{it.release.countDown()};alive.set(false)
                SwingUtilities.invokeAndWait{control.removeListener(listener)}
                scope.cancel();player.close();SwingUtilities.invokeAndWait{frame?.dispose()}
                verify(frame?.isDisplayable!=true,"$name owned window disposed")
            }
        }
        Fixture("success").use{f->
            verify(f.player.seekToTrackedIfSourceVersion(1,1.0)==null,"Unattached native core refuses owned seek")
            verify(f.control.seekToTracked(1000)==null&&f.queued.isEmpty(),"Loading entry emits no invented queue ticket")
            f.start();val version=f.version.value!!;val surface=f.player.surface
            val gate=f.hold();val ticket=f.queue(1500);f.blocked(gate)
            verify(ticket.sourceVersion==version&&ticket.targetPositionMs==1500L&&ticket.operationId>0,"Ticket binds real version ID and requested target")
            verify(f.player.state.value.seekCompletedId!=ticket.operationId,"Blocked command has no native success receipt")
            gate.release.countDown()
            await("Native playback restart confirms this exact admitted command"){
                val p=f.player.state.value;p.seekCompletedId==ticket.operationId&&abs((p.seekCompletedPositionSeconds?:-1.0)-1.5)<0.1
            }
            verify(f.player.currentSourceVersion==version&&f.player.surface===surface&&f.player.state.value.nativePaused==true,"Tracked seek retains source Canvas and pause")
            verify(f.player.seekToTrackedIfSourceVersion(version+1,2.0)==null&&f.player.seekToTrackedIfSourceVersion(version,Double.NaN)==null,"Wrong version and nonfinite native seek rejected at enqueue")
            ui{f.control.removeListener(f.listener)};val callbacks=f.queued.size
            var second:DesktopOriginalNativeSeekSubmission?=null;ui{second=f.control.seekToTracked(500)}
            await("Unregistered observer does not prevent a valid native command"){f.player.state.value.seekCompletedId==second?.operationId}
            verify(f.queued.size==callbacks,"Removed registration receives no queue callback")
            ui{f.control.addListener(f.listener)};val third=f.queue(-100)
            verify(third.targetPositionMs==0L&&third.operationId!=ticket.operationId,"Each command has its own ID and normalized target")
            await("Negative user position seeks to actual zero"){f.player.state.value.seekCompletedId==third.operationId&&f.player.state.value.positionSeconds<0.1}
        }
        for(kind in listOf("entry","account","publication","version","revision"))Fixture(kind).use{f->
            f.start();val before=f.player.state.value;val sourceBefore=f.player.currentSourceSnapshot()!!
            val gate=f.hold();val ticket=f.queue(2500);f.blocked(gate)
            when(kind){
                "entry"->f.alive.set(false)
                "account"->f.store.logout()
                "publication"->{
                    val replacement=f.source.copy(nativePublication=DesktopNativePlaybackPublication{command->
                        f.publication.attempt(f.base,{f.alive.get()}){synchronized(f.entry){if(f.alive.get())command()}}
                    })
                    verify(f.commit{check(f.player.adoptPublication(ticket.sourceVersion,sourceBefore.source,replacement))},"Actual same-version publication replacement succeeds")
                }
                "version"->ui{f.player.loadVersioned(f.source.copy(title="Replacement"))}
                "revision"->ui{check(f.player.recoverSource(ticket.sourceVersion,0.3,true))}
            }
            gate.release.countDown()
            if(kind=="version"||kind=="revision")await("$kind replacement ready"){
                val p=f.player.state.value;p.ready&&!p.loading&&p.nativePaused==true&&p.error==null
            }
            verify(f.player.drainSourceCommands(f.player.currentSourceVersion),"$kind actual actor drains after rejected captured seek")
            delay(150L)
            val after=f.player.state.value
            verify(after.seekCompletedId!=ticket.operationId,"$kind stale seek produces no completion or successful history basis")
            if(kind=="version")verify(f.player.currentSourceVersion!=ticket.sourceVersion&&after.sourceTitle=="Replacement","Foreign native version retained")
            else verify(f.player.currentSourceVersion==ticket.sourceVersion,"$kind rejection keeps actual source version")
            if(kind=="revision")verify(abs(after.positionSeconds-0.3)<0.12,"Recovery intent is not overwritten by stale revision seek")
            else if(kind!="version")verify(abs(after.positionSeconds-before.positionSeconds)<0.08&&after.nativePaused==true,"$kind rejected command does not move native clock or pause")
            if(kind=="entry"||kind=="account")verify(f.control.seekToTracked(1000)==null,"$kind retired owner cannot enqueue another ticket")
        }
        val classes=listOf(MpvPlayer::class.java,DesktopOriginalMpvOverlayControl::class.java,DesktopOriginalMpvSectionControl::class.java,DesktopOriginalNativeSeekSubmission::class.java)
        fun origin(type:Class<*>):String {
            val b=type.getResourceAsStream("/"+type.name.replace('.','/')+".class")!!.use{it.readBytes()}
            return "{\"class\":\"${type.name}\",\"codeSource\":\"${type.protectionDomain.codeSource.location}\",\"sha256ClassBytes\":\"${MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}}\"}"
        }
        fun q(v:String)="\""+v.replace("\\","\\\\").replace("\"","\\\"")+"\""
        Files.writeString(output,"{\"passed\":true,\"assertions\":${checks.size},\"checks\":[${checks.joinToString(","){q(it)}}],\"origins\":[${classes.joinToString(","){origin(it)}}],\"nativeExecuted\":true,\"realAccountUsed\":false,\"httpUsed\":false,\"independentVisualAcceptance\":false,\"fullOriginalOwnerMounted\":false}")
        println("PASS ${checks.size} typed native seek checks; owned windows closed")
    }
}
