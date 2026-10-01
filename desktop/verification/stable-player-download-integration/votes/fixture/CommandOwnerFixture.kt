package com.bilipai.desktop.votefixture

import com.bilipai.desktop.danmaku.*
import com.bilipai.desktop.player.MpvPlayer
import com.android.purebilibili.feature.video.danmaku.*
import java.io.ByteArrayOutputStream
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Real Overlay + unchanged WindowLoader/Proto decoder. The surface is never
 * displayable; no native window or mpv DLL runs. Source returns fixture bytes. */
fun commandOwnerMain(args:Array<String>): Unit = runBlocking {
    withTimeout(8000) {
        val source = OwnedSource()
        val player = MpvPlayer()
        val overlay = DanmakuOverlay(player,source=source)
        val genField=DanmakuOverlay::class.java.getDeclaredField("generation").apply { isAccessible=true }
        fun generation()=(genField.get(overlay) as AtomicLong).get()
        val publish=DanmakuOverlay::class.java.getDeclaredMethod("publish",DanmakuWindowResult::class.java,java.lang.Long.TYPE).apply { isAccessible=true }
        fun attemptedLate(version:Long, text:String) {
            val command=CommandDanmakuItem("late",CommandDanmakuType.VOTE,text,0,8000,voteId="9001")
            publish.invoke(overlay,DanmakuWindowResult(DanmakuDocument(),DanmakuFormat.PROTOBUF,commands=listOf(command)),version)
        }
        val checks=mutableListOf<String>()
        fun gate(name:String,value:Boolean) { check(value) { name };checks+=name }
        try {
            overlay.load(101,201)
            gate("real original metadata parser publishes only the admitted CID's command list",
                overlay.commandItemsFor(101).single().voteId=="1011" && overlay.commandItemsFor(102).isEmpty())
            val delayed=source.delayNext(101)
            val pending=launch { try { overlay.load(101,201) } catch (expected:CancellationException) { } }
            delayed.started.await()
            val retiredVersion=generation()
            gate("pending request admission clears its previous commands under the same lock",overlay.commandItemsFor(101).isEmpty())
            overlay.load(102,202)
            val current=overlay.commandItemsFor(102)
            gate("A→B binds the original metadata result to B only",current.single().voteId=="1021" && overlay.commandItemsFor(101).isEmpty())
            delayed.release.complete(Unit);pending.join()
            attemptedLate(retiredVersion,"stale-A")
            gate("late A transport completion and retired-generation publication cannot replace B",overlay.commandItemsFor(102)==current && overlay.commandItemsFor(101).isEmpty())
            val beforeLive=generation()
            overlay.enterLive();attemptedLate(beforeLive,"stale-VOD-after-live")
            gate("enterLive retires command CID and rejects old VOD publication",overlay.commandItemsFor(102).isEmpty() && overlay.commandItems.value.isEmpty())
            overlay.load(101,201)
            val beforeDocument=generation()
            overlay.setDocument(DanmakuDocument());attemptedLate(beforeDocument,"stale-after-document")
            gate("setDocument retires command CID and rejects old metadata publication",overlay.commandItemsFor(101).isEmpty() && overlay.commandItems.value.isEmpty())
            overlay.load(101,201)
            val sameCidOld=generation()
            overlay.load(101,201)
            val sameCidCurrent=overlay.commandItemsFor(101)
            attemptedLate(sameCidOld,"same-CID-old-request")
            gate("same CID does not revive an older request generation",overlay.commandItemsFor(101)==sameCidCurrent && sameCidCurrent.single().voteId=="1015")
            gate("actual surface is undisplayable and native player never initialized",!player.surface.isDisplayable && !player.state.value.ready && player.state.value.nativeVersion==null)
            overlay.close()
            gate("closed overlay never exposes even previously matching command CID",overlay.commandItemsFor(101).isEmpty())
        } finally { overlay.close();player.close() }
        val result=buildJsonObject {
            put("passed",true);put("preparedCandidateOnly",true);put("MainAcceptance",false)
            put("unchangedOriginalDecoder",true);put("actualOverlayAndWindowLoader",true)
            put("latePublishInvocation", "test-only reflection exercises the guarded real publish node")
            put("HTTP",false);put("HWND",false);put("mpvNative",false)
            put("checks",JsonArray(checks.map(::JsonPrimitive)))
        }
        Files.writeString(Path.of(args[0]),result.toString());println(result)
    }
}
object CommandOwnerLauncher { @JvmStatic fun main(args:Array<String>)=commandOwnerMain(args) }
private data class Delay(val started:CompletableDeferred<Unit> = CompletableDeferred(),val release:CompletableDeferred<Unit> = CompletableDeferred())
private class OwnedSource:DesktopDanmakuSource {
    private val sequence=ConcurrentHashMap<Long,AtomicInteger>()
    private val delay=AtomicReference<Pair<Long,Delay>?>(null)
    fun delayNext(cid:Long):Delay = Delay().also { delay.set(cid to it) }
    override suspend fun metadata(cid:Long,aid:Long):ByteArray {
        val serial=sequence.computeIfAbsent(cid){AtomicInteger()}.incrementAndGet()
        val pending=delay.get()?.takeIf{it.first==cid}
        if(pending!=null && delay.compareAndSet(pending,null)) {
            pending.second.started.complete(Unit)
            withContext(NonCancellable) { pending.second.release.await() }
        }
        val extra="""{"vote_id":"$cid$serial","title":"fixture-$cid-$serial","options":["A","B"]}"""
        val command=field(1,value=cid*100+serial)+field(2,value=cid)+field(4,bytes="#VOTE#".toByteArray())+
            field(6,value=0)+field(9,bytes=extra.toByteArray())
        return field(4,bytes=field(1,value=360000)+field(2,value=1))+field(9,bytes=command)
    }
    override suspend fun segment(cid:Long,index:Int)=byteArrayOf()
    override suspend fun xml(cid:Long)=error("Unexpected XML fallback; metadata fixture should decode")
    override suspend fun special(url:String)=error("Unexpected special request")
}
/** Fixture encoder only, mirroring existing DanmakuWindowLoaderTest utilities. */
private fun field(number:Int,value:Long=0,bytes:ByteArray?=null):ByteArray =
    if(bytes==null)varint(number*8L)+varint(value) else varint(number*8L+2)+varint(bytes.size.toLong())+bytes
private fun varint(value:Long):ByteArray {
    var rest=value;val output=ByteArrayOutputStream()
    do {val byte=rest and 0x7f;rest=rest ushr 7;output.write((byte or if(rest==0L)0 else 0x80).toInt())} while(rest!=0L)
    return output.toByteArray()
}
