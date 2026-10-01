package com.bilipai.desktop

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.player.ShuffleProgress
import com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta
import com.bilipai.desktop.data.*
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import com.bilipai.desktop.player.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs

/** Installed Controller/Repository/Store/MPV APIs. Only metadata/heartbeat/subtitle
 * IO is injected memory work; a real local clip is rendered by the same native DLL.
 * No full original VM/Holder, account, network, OS input or visual acceptance. */
object ControllerNativeDrainFixture {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val output=Path.of(args[0]); val clip=Path.of(args[1]); val checks=mutableListOf<String>()
        fun verify(ok:Boolean,label:String) { check(ok) { label }; checks+=label }
        suspend fun waitFor(label:String,predicate:()->Boolean) = withTimeout(15_000L) {
            while(!predicate()) delay(20L)
            verify(true,label)
        }
        fun privateValue(target:Any,name:String):Any? = target.javaClass.getDeclaredField(name).run { isAccessible=true; get(target) }
        fun setPrivate(target:Any,name:String,value:Any) = target.javaClass.getDeclaredField(name).run { isAccessible=true; set(target,value) }
        suspend fun ui(block:suspend ()->Unit) = withContext(Dispatchers.Swing) { block() }

        class Fixture(val name:String) : AutoCloseable {
            val directory=output.resolveSibling("library-$name").also { Files.createDirectories(it) }
            val store=DesktopSessionStore.temporary()
            val repository=DesktopRepository(store)
            val publication=DesktopRepositoryPlaybackPublication(repository)
            val rootScope=CoroutineScope(SupervisorJob()+Dispatchers.Swing)
            val player=MpvPlayer(useNullAudioOutput=true)
            val surface=player.surface
            val canvas=surface.getComponent(0)
            val library=DesktopLibrary(directory) { false }
            val token=Any()
            val alive=AtomicBoolean(true)
            val baseMuted=AtomicBoolean(false)
            val relatedStarted=CompletableDeferred<Unit>()
            val relatedRelease=CompletableDeferred<Unit>()
            val relatedExited=AtomicBoolean(false)
            val subtitleStarted=CompletableDeferred<Unit>()
            val subtitleExited=AtomicBoolean(false)
            val reports=Collections.synchronizedList(mutableListOf<DesktopHeartbeatReport>())
            val info=VideoDetails("BV-drain-local",170001,"Drain local fixture","","","Fixture",0,0,
                listOf(VideoPart(11,"P1",4)),raw=ViewInfo(bvid="BV-drain-local",aid=170001,cid=11,
                    title="Drain local fixture",pages=listOf(Page(cid=11,page=1,part="P1",duration=4))))
            val receipt=repository.capturePlaybackAuthorization(repository.sessionEpoch) { alive.get() }.receipt
            val resolved=ResolvedSource(clip.toUri().toString(),null,info.title,"https://www.bilibili.com/",
                quality=80,authorizationReceipt=receipt)
            val source=object:DesktopPlaybackDataSource {
                override val sessionEpoch get()=repository.sessionEpoch
                override suspend fun videoDetails(bvid:String)=info
                override suspend fun related(bvid:String):List<VideoCard> {
                    relatedStarted.complete(Unit)
                    try { withContext(NonCancellable) { relatedRelease.await() } }
                    finally { relatedExited.set(true) }
                    return emptyList()
                }
                override suspend fun playback(details:VideoDetails,index:Int,quality:Int,codecOverride:String?,forceRefresh:Boolean)=resolved
                override suspend fun reportHeartbeat(report:DesktopHeartbeatReport):Boolean { reports+=report; return true }
            }
            val subtitles=DesktopAutomaticSubtitles(object:DesktopAutomaticSubtitleDataSource {
                override suspend fun metadata(bvid:String,cid:Long):PlayerInfoData {
                    subtitleStarted.complete(Unit)
                    try { awaitCancellation() } finally { subtitleExited.set(true) }
                }
                override suspend fun import(track:SubtitleTrackMeta):Path = error("No subtitle HTTP/import permitted")
            },MpvAutomaticSubtitlePlayer(player),sessionEpoch={repository.sessionEpoch})
            val controller=DesktopPlaybackController(repository,player,null,null,library,{PlayerPreferences(muted=baseMuted.get())},rootScope,
                dataSource=source,automaticSubtitles=subtitles,publication=publication,
                currentDanmakuSettings={error("Fixture has no danmaku overlay")})
            val cards=listOf(VideoCard("BV-other-local","Other","","",0,4,preferredCid=22),
                VideoCard(info.bvid,info.title,"","Fixture",0,4,preferredCid=11))
            var frame:JFrame?=null
            suspend fun start() {
                ui { frame=JFrame("BiliPai Controller Native Drain 64 $name").apply {
                    defaultCloseOperation=JFrame.DISPOSE_ON_CLOSE; contentPane.add(surface)
                    setSize(740,480); setLocation(140,120); isVisible=true
                }; controller.openQueue(cards,1,token) }
                relatedStarted.await(); subtitleStarted.await()
                waitFor("$name actual local native source is ready") {
                    val p=player.state.value; !controller.state.value.opening && p.ready && !p.loading &&
                        !p.ended && p.error==null && p.videoCodec!=null && p.nativePaused!=null
                }
                ui { player.setPaused(true) }
                waitFor("$name native pause readback settles") { player.state.value.nativePaused==true }
            }
            fun controllerJob()=(privateValue(controller,"controllerScope") as CoroutineScope).coroutineContext[Job]!!
            override fun close() {
                relatedRelease.complete(Unit); alive.set(false)
                SwingUtilities.invokeAndWait { controller.close() }
                rootScope.cancel(); player.close(); subtitles.close()
                SwingUtilities.invokeAndWait { frame?.dispose() }
            }
        }

        Fixture("adoption").use { f ->
            f.start()
            val version=f.player.currentSourceVersion
            val sourceBefore=checkNotNull(f.player.currentSourceSnapshot())
            verify(withContext(Dispatchers.Swing) { f.controller.drainForOriginalVideoOwner(f.repository.sessionEpoch,version,"wrong",11) }==null,
                "Wrong subject does not retire the actual queue")
            verify(f.controller.ownsQueue(f.token),"Rejected preflight retains the old queue admission")
            val seek=f.player.seekToTracked(1.0)!!
            waitFor("Real tracked seek settles before drain") { f.player.state.value.seekCompletedId==seek && abs(f.player.state.value.positionSeconds-1.0)<0.1 }
            f.player.setSpeed(1.25)
            val subtitle=f.directory.resolve("retained.srt")
            Files.writeString(subtitle,"1\n00:00:00,000 --> 00:00:04,000\nDrain retained subtitle\n")
            f.player.addSubtitle(subtitle,"Retained fixture","en")
            // Fault setup seeds only the old controller's private interval; native mute is real.
            ui { setPrivate(f.controller,"pluginMuteFromMs",500L); setPrivate(f.controller,"pluginMuteUntilMs",2500L); f.player.setMuted(true) }
            waitFor("Real native subtitle speed and mute readback settle") {
                val p=f.player.state.value; p.muted && abs(p.speed-1.25)<0.001 && p.tracks.any { it.type=="sub"&&it.external&&it.selected }
            }
            val before=f.player.state.value
            val subtitleVersion=f.player.currentSubtitleControlVersion
            val oldJob=f.controllerJob()
            val shuffle=privateValue(f.controller,"shuffle") as ShuffleProgress
            val selectedSource=privateValue(privateValue(f.controller,"current")!!,"source")
            val drain=async { f.controller.drainForOriginalVideoOwner(f.repository.sessionEpoch,version,f.info.bvid,11) }
            delay(80L)
            verify(!drain.isCompleted && !f.controller.ownsQueue(f.token),"Drain retires old queue admission and waits for the actual old child")
            verify(f.player.currentSourceVersion==version && f.player.state.value.nativePaused==true,
                "Draining old work issues no native pause stop or replacement")
            f.relatedRelease.complete(Unit)
            val handoff=checkNotNull(drain.await())
            verify(oldJob.isCompleted && f.relatedExited.get() && f.subtitleExited.get(),
                "Actual old Controller child tree and automatic subtitle work are joined")
            verify(handoff.details===f.info && handoff.details.raw===f.info.raw && handoff.resolvedSource===selectedSource,
                "Real original raw detail and selected metadata are transferred without reconstruction")
            verify(handoff.queue.cards==f.cards && handoff.queue.selectedIndex==1 && handoff.queue.owner===f.token && handoff.queue.shuffle==shuffle,
                "Original queue cards index token and shuffle are retained")
            verify(handoff.nativeSource.sourceVersion==version && handoff.nativeSource.source.nativePublication===sourceBefore.source.nativePublication,
                "Drained native snapshot keeps the actual source version and old publication identity")
            verify(handoff.pluginMute==DesktopOrdinaryPluginMuteHandoff(500,2500,false),
                "Typed native mute interval and captured restore value survive drain")
            verify(f.library.resumeCard(f.info.bvid)?.progressSeconds==1 && f.reports.count { !it.initial }==1,
                "Old history checkpoint and exactly one final heartbeat complete")
            verify(!handoff.nativeSource.source.nativePublication!!.admit { error("Retired old publication executed") },
                "Old native publication is retired after its scope joins")
            val entry=AtomicBoolean(true); val gate=Any(); var publications=0; var entryAdmissions=0
            val owner=DesktopOriginalVideoNativeOwner(f.player,f.publication,{f.repository.sessionEpoch},f.baseMuted::get,entry::get,
                { block -> synchronized(gate) { if(!entry.get()) false else { entryAdmissions++; block(); true } } },{publications++})
            try {
                val accepted=checkNotNull(owner.adopt(handoff))
                verify(accepted.sourceVersion==version && publications==1 && owner.current()===accepted,
                    "Same real Repository receipt admits one retained-source native owner")
                val admissionsBefore=entryAdmissions
                verify(accepted.nativeSource.source.nativePublication!!.admit {} && entryAdmissions==admissionsBefore+1,
                    "Retained publication re-enters actual required entry gate for every queued command")
                verify(f.player.state.value==before && f.player.currentSubtitleControlVersion==subtitleVersion &&
                    f.player.surface===f.surface && f.surface.getComponent(0)===f.canvas && Files.exists(subtitle),
                    "Actual adoption preserves clock pause speed subtitles and the same Canvas")
                verify(!owner.observeInheritedPluginMute() && f.player.state.value.muted,
                    "Existing native mute remains active within its actual clock interval")
                ui { f.controller.close(); f.controller.open(f.cards[0]); f.controller.stop(); f.controller.seekTo(0.0) }
                delay(80L)
                verify(f.player.currentSourceVersion==version && f.player.state.value.nativePaused==true && abs(f.player.state.value.positionSeconds-1.0)<0.1,
                    "Retired old close open stop and seek cannot affect the new owner")
                val later=f.player.seekToTracked(3.0)!!
                waitFor("Actual clock passes the inherited mute interval") { f.player.state.value.seekCompletedId==later && abs(f.player.state.value.positionSeconds-3.0)<0.1 }
                verify(!owner.observeInheritedPluginMute(),"Mute restoration waits for actual readback after queuing owned command")
                waitFor("Owned native restoration reads back captured original mute preference") { !f.player.state.value.muted }
                verify(owner.observeInheritedPluginMute() && owner.observeInheritedPluginMute(),
                    "Inherited mute is consumed once after real native readback")
                verify(!f.player.setMutedIfSourceVersion(version+1,true),"Foreign source version cannot queue inherited mute")
                f.store.logout()
                verify(owner.current()==null && !accepted.nativeSource.source.nativePublication!!.admit { error("Retired account command executed") },
                    "Actual Store epoch retirement rejects adopted native admission")
            } finally { owner.close() }
        }

        for(mode in listOf("settings","newload","eof")) Fixture(mode).use { f ->
            f.start(); val version=f.player.currentSourceVersion
            ui { setPrivate(f.controller,"pluginMuteFromMs",0L); setPrivate(f.controller,"pluginMuteUntilMs",if(mode=="eof") 10000L else 2500L); f.player.setMuted(true) }
            waitFor("$mode actual inherited native mute settles") { f.player.state.value.muted }
            f.relatedRelease.complete(Unit)
            val handoff=checkNotNull(f.controller.drainForOriginalVideoOwner(f.repository.sessionEpoch,version,f.info.bvid,11))
            val entry=AtomicBoolean(true); val gate=Any()
            val owner=DesktopOriginalVideoNativeOwner(f.player,f.publication,{f.repository.sessionEpoch},f.baseMuted::get,entry::get,
                { block -> synchronized(gate) { if(!entry.get()) false else { block(); true } } },{})
            try {
                checkNotNull(owner.adopt(handoff))
                verify(handoff.pluginMute?.restoreMuted==false,"$mode handoff records the original base mute")
                if(mode=="settings") {
                    f.baseMuted.set(true)
                    val seek=f.player.seekToTracked(3.0)!!
                    waitFor("Settings case reaches the real inherited interval end") { f.player.state.value.seekCompletedId==seek && abs(f.player.state.value.positionSeconds-3.0)<0.1 }
                    verify(owner.observeInheritedPluginMute() && f.player.state.value.muted,
                        "Current user mute overrides captured handoff preference at actual restoration")
                } else if(mode=="eof") {
                    f.player.setPaused(false)
                    waitFor("Actual EOF arrives inside the inherited native mute interval") { f.player.state.value.ended && !f.player.state.value.loading }
                    verify(!owner.observeInheritedPluginMute(),"EOF queues owned restoration even before inherited interval end")
                    waitFor("Actual idle native mute restores at EOF") { !f.player.state.value.muted }
                    verify(owner.observeInheritedPluginMute(),"EOF consumes the inherited interval after native readback")
                } else {
                    val request=com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV-new-local",170002,12)
                    val callingJob=Job().apply { complete() }
                    val accepted=owner.publish(request,handoff.nativeSource.source.copy(nativePublication=null,title="New local fixture",startPaused=true),version,callingJob,{true})
                    waitFor("Actual new video load explicitly resets temporary mute to current user preference") {
                        f.player.currentSourceVersion==accepted.sourceVersion && !f.player.state.value.loading &&
                            f.player.state.value.videoCodec!=null && f.player.state.value.nativePaused==true && !f.player.state.value.muted
                    }
                    verify(accepted.sourceVersion==version+1 && owner.observeInheritedPluginMute(),
                        "A true new load consumes old interval after applying native base mute")
                }
                ui { synchronized(gate) { entry.set(false) }; owner.close() }
                verify(owner.current()==null && !f.player.currentSourceSnapshot()!!.source.nativePublication!!.admit { error("Closed entry command executed") },
                    "$mode closed entry refuses retained native commands")
            } finally { owner.close() }
        }

        run {
            val store=DesktopSessionStore.temporary(); val repository=DesktopRepository(store)
            val publication=DesktopRepositoryPlaybackPublication(repository)
            val player=MpvPlayer(useNullAudioOutput=true); val entry=AtomicBoolean(true)
            val baseMuted=AtomicBoolean(false); val gate=Any(); var frame:JFrame?=null
            val owner=DesktopOriginalVideoNativeOwner(player,publication,{repository.sessionEpoch},baseMuted::get,entry::get,
                { block -> synchronized(gate) { if(!entry.get()) false else { block(); true } } },{})
            try {
                val receipt=repository.capturePlaybackAuthorization(repository.sessionEpoch,entry::get).receipt
                val request=com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV-before-attach",170003,13)
                val accepted=owner.publish(request,PlaybackSource(clip.toUri().toString(),startPaused=true,authorizationReceipt=receipt),
                    player.currentSourceVersion,Job().apply { complete() },{true})
                baseMuted.set(true); player.setMuted(true)
                verify(player.state.value.loading && player.state.value.muted,"User mute changes after publication while native Canvas is unattached")
                ui { frame=JFrame("BiliPai Native User Mute Before Attach 64").apply {
                    defaultCloseOperation=JFrame.DISPOSE_ON_CLOSE; contentPane.add(player.surface)
                    setSize(740,480); setLocation(140,120); isVisible=true
                } }
                waitFor("Actual first native attach honors the latest pending user mute") {
                    val p=player.state.value; p.ready && !p.loading && p.videoCodec!=null && p.nativePaused==true && p.muted
                }
                verify(owner.current()===accepted && player.currentSourceVersion==accepted.sourceVersion,
                    "First attach retains the one accepted source and successful request publication")
            } finally {
                ui { synchronized(gate) { entry.set(false) }; owner.close() }
                player.close(); ui { frame?.dispose() }
            }
        }

        for (mode in listOf("foreign","epoch","timeout")) Fixture(mode).use { f ->
            f.start(); val version=f.player.currentSourceVersion
            val drain=async { f.controller.drainForOriginalVideoOwner(f.repository.sessionEpoch,version,f.info.bvid,11) }
            delay(80L)
            verify(!drain.isCompleted,"$mode drain waits for its confirmed live old child")
            when(mode) {
                "foreign" -> f.player.loadVersioned(PlaybackSource(clip.toUri().toString(),title="Foreign fixture",startPaused=true))
                "epoch" -> f.store.logout()
            }
            if(mode!="timeout") f.relatedRelease.complete(Unit)
            verify(drain.await()==null,"$mode retirement produces no handoff")
            verify(!f.controller.ownsQueue(f.token),"$mode retired queue cannot resume old admission")
            ui { f.controller.close() }
            if(mode=="foreign") waitFor("Foreign replacement remains ready after old drain rejection") { f.player.state.value.sourceTitle=="Foreign fixture" && f.player.state.value.videoCodec!=null }
            else verify(f.player.currentSourceVersion==version && f.player.state.value.videoCodec!=null,
                "$mode failed drain does not stop the native source")
            if(mode=="timeout") {
                verify(!f.controllerJob().isCompleted,"Timed out join does not pretend uncooperative work has completed")
                f.relatedRelease.complete(Unit)
                verify(f.controller.awaitRetiredOrdinaryProducers() && f.controllerJob().isCompleted,
                    "Root can confirm same old producers quiescent after their actual blocker releases")
            }
        }
        val origins=listOf(DesktopPlaybackController::class.java,DesktopOriginalVideoNativeOwner::class.java,
            DesktopRepositoryPlaybackPublication::class.java,MpvPlayer::class.java).joinToString(",") { type ->
            val bytes=type.getResourceAsStream("/"+type.name.replace('.','/')+".class")!!.use { it.readBytes() }
            val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            "{\"class\":\"${type.name}\",\"codeSource\":\"${type.protectionDomain.codeSource.location.toURI()}\",\"sha256ClassBytes\":\"$sha\"}"
        }
        Files.writeString(output,"{\"passed\":true,\"assertions\":${checks.size},\"checks\":[${checks.joinToString(",") { "\"$it\"" }}],\"origins\":[$origins],\"scope\":\"Actual Controller drain and MPV local adoption; injected memory transport, synthetic mute interval, no full VM/Holder/account/network/visual acceptance\"}\n")
        println("PASS ${checks.size} installed Controller/native drain assertions")
    }
}
