package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.SpecialDanmakuSource
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Collections
import kotlin.test.*

/** Real fixed IndexReader/Window through the production loader; no network/native player. */
class DesktopSpecialWindowIntegrationTest {
    @Test fun `future scenes remain unread while cold and backward seeks use their original lifetimes`(): Unit = runBlocking {
        val source=SpecialWindowFixtureSource(listOf(
            specialElement(1,0,"def text first {content=\"first\" duration=2s}"),
            specialElement(2,10_000,"def text next {content=\"next\" duration=2s}"),
            specialElement(3,50_000,"def text future {content=\"${"f".repeat(4_000)}\" duration=1s}")))
        DanmakuWindowLoader(source,42).use {loader->
            assertEquals(listOf("first"),loader.initial().document.bas.map {it.content})
            assertFalse(source.reads.any {it.second>1024},"Compact index must not fetch the future envelope")
            assertEquals(listOf("next"),assertNotNull(loader.move(10_000)).document.bas.map {it.content})
            assertEquals(listOf("first"),assertNotNull(loader.move(500)).document.bas.map {it.content})
            assertEquals(1,source.segmentReads)
        }
    }

    @Test fun `paused backward seek in the same refresh bucket and actual seek ACK recompute the short window`(): Unit = runBlocking {
        val source=SpecialWindowFixtureSource(listOf(specialElement(1,0,"def text long {content=\"long\" duration=10s}")))
        DanmakuWindowLoader(source,42).use {loader->
            assertEquals(1400L,loader.initial(1400).specialStartMs)
            val returned=assertNotNull(loader.move(1100))
            assertEquals(1100L,returned.specialStartMs)
            assertTrue(returned.specialOnly)
            assertNull(loader.move(1100))
            assertNotNull(loader.move(1110,seekCompletedId=7),"A small actual seek ACK is never lost in the time bucket")
        }
    }

    @Test fun `late indexes become usable independently without replaying ordinary segment reads`(): Unit = runBlocking {
        val late=CompletableDeferred<Unit>()
        val source=SpecialWindowFixtureSource(listOf(specialElement(1,0,"def text ready {content=\"ready\" duration=10s}")),late)
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            DanmakuWindowLoader(source,42,indexingScope=owner).use {loader->
                assertTrue(loader.initial().document.bas.isEmpty())
                source.openStarted.await();late.complete(Unit)
                withTimeout(3000) {while(loader.specialRevision.value==0L)yield()}
                val ready=assertNotNull(loader.move(0))
                assertEquals("ready",ready.document.bas.single().content)
                assertTrue(ready.specialOnly);assertEquals(1,source.segmentReads)
            }
        } finally {late.complete(Unit);owner.cancel()}
    }

    @Test fun `close cancels the original seekable source index task`(): Unit = runBlocking {
        val late=CompletableDeferred<Unit>()
        val source=SpecialWindowFixtureSource(emptyList(),late)
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val loader=DanmakuWindowLoader(source,42,indexingScope=owner)
        try {
            loader.initial();source.openStarted.await();loader.close()
            withTimeout(3000) {source.openCancelled.await()}
            assertEquals(0L,loader.specialRevision.value)
        } finally {loader.close();owner.cancel()}
    }

    @Test fun `all four original parse tasks and cached scenes share the combined document work budget`(): Unit = runBlocking {
        val script=(0 until 90).joinToString(" ") {"def text t$it {content=\"v$it\" duration=10s}"}
        val estimate=assertNotNull(DesktopBasParseBudget.estimate(script))
        val sceneCount=DesktopBasDocumentBudget.MAX_PARSE_WORK/estimate.workUnits+1
        assertTrue(sceneCount in 2..128,"Keep the real global-budget regression small")
        assertTrue(sceneCount*90<=DesktopBasDocumentBudget.MAX_ELEMENTS,"Exercise parse work before the retained element cap")
        val source=SpecialWindowFixtureSource((1L..sceneCount.toLong()).map {specialElement(it,0,script)})
        DanmakuWindowLoader(source,42).use {loader->
            val initial=loader.initial()
            assertTrue(initial.warning.orEmpty().contains("短窗口"))
            assertTrue(initial.document.bas.isEmpty(),"A resource-rejected window must not be partially published")
            assertEquals(listOf("ordinary"),initial.document.comments.map {it.text})
            val cached=assertNotNull(loader.move(1500))
            assertTrue(cached.warning.orEmpty().contains("短窗口"))
            assertTrue(cached.document.bas.isEmpty(),"Partially cached programs must not bypass the combined budget")
            assertEquals(initial.document.comments,cached.document.comments)
            assertEquals(1,source.segmentReads)
        }
    }

    @Test fun `offline protobuf uses indexed local ranges and retains mode7 independently of BAS`(): Unit = runBlocking {
        val file=Files.createTempFile("private-special-range-",".pb")
        try {
            Files.write(file,specialElement(1,0,"[0,0,\"1-1\",12,\"mode7\",0,0]",7)+
                specialElement(2,10_000,"def text later {content=\"later\" duration=2s}"))
            DanmakuWindowLoader(OfflineDanmakuSource(emptyList(),listOf(file)),42).use {loader->
                val initial=loader.initial();assertEquals("mode7",initial.document.advanced.single().content)
                assertTrue(initial.document.bas.isEmpty())
                val next=assertNotNull(loader.move(10_000))
                assertEquals("mode7",next.document.advanced.single().content)
                assertEquals("later",next.document.bas.single().content)
            }
        } finally {Files.deleteIfExists(file)}
    }
}

internal class SpecialWindowFixtureSource(elements:List<ByteArray>,private val late:CompletableDeferred<Unit>?=null):DesktopDanmakuSource {
    private val bytes=elements.fold(byteArrayOf()) {a,b->a+b}
    val reads=Collections.synchronizedList(mutableListOf<Pair<Long,Int>>())
    val openStarted=CompletableDeferred<Unit>();val openCancelled=CompletableDeferred<Unit>()
    @Volatile var segmentReads=0
    override suspend fun metadata(cid:Long,aid:Long)=specialField(4,specialField(1,360_000)+specialField(2,1))+
        specialField(6,"https://comment.bilibili.com/private.pb".toByteArray())
    override suspend fun segment(cid:Long,index:Int):ByteArray {segmentReads++;return specialElement(90,1000,"ordinary",1)}
    override suspend fun xml(cid:Long):ByteArray=error("No XML fallback")
    override suspend fun special(url:String):ByteArray=error("Whole special fetch is forbidden")
    override suspend fun openSpecial(url:String):SpecialDanmakuSource {
        openStarted.complete(Unit)
        try {late?.await()} catch(c:CancellationException) {openCancelled.complete(Unit);throw c}
        return object:SpecialDanmakuSource {
            override val byteLength=bytes.size.toLong()
            override suspend fun readRange(offset:Long,byteCount:Int):ByteArray {
                currentCoroutineContext().ensureActive();reads+=offset to byteCount
                return bytes.copyOfRange(offset.toInt(),offset.toInt()+byteCount)
            }
        }
    }
}
internal fun specialElement(id:Long,time:Long,text:String,mode:Int=9)=specialField(1,
    specialField(1,id)+specialField(2,time)+specialField(3,mode.toLong())+
    specialField(4,25)+specialField(5,0xffffff)+specialField(7,text.toByteArray()))
internal fun specialField(number:Int,bytes:ByteArray)=specialVarint(number*8L+2)+specialVarint(bytes.size.toLong())+bytes
internal fun specialField(number:Int,value:Long)=specialVarint(number*8L)+specialVarint(value)
private fun specialVarint(value:Long):ByteArray {
    var current=value;val out=ByteArrayOutputStream()
    do {val next=current and 127;current=current ushr 7;out.write((next or if(current==0L)0 else 128).toInt())}while(current!=0L)
    return out.toByteArray()
}
