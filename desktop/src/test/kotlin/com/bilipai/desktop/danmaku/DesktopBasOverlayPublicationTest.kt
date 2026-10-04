package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.bas.BasDanmaku
import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.Font
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.*

/** Real Overlay XML/Mode9 loader, captured BAS plugin worker and EDT publication.
 * No display, Skia paint, native decoder, HTTP or account profile is created.
 * This proves document publication and retirement, not Windows pixels or clicks. */
class DesktopBasOverlayPublicationTest {
    private val settings=DanmakuSettings(mergeDuplicates=false,smartOcclusionEnabled=false,displayAreaRatio=1f)
    private fun entry(id:Long,mode:Int,text:String,color:Int=0xffffff)=
        """<d p="0,$mode,25,$color,0,0,private,$id">$text</d>"""
    private fun bas(id:Long,text:String,color:Int=0xffffff)=
        entry(id,9,"""def text item$id {content="$text" duration=10s} """,color)
    private fun xml(vararg entries:String)="<i>"+entries.joinToString("")+"</i>"

    private fun <T> edt(block:()->T):T {
        if(SwingUtilities.isEventDispatchThread())return block()
        var result:Result<T>?=null
        SwingUtilities.invokeAndWait {result=runCatching(block)}
        return checkNotNull(result).getOrThrow()
    }
    private fun field(receiver:Any,name:String):Any? = receiver.javaClass.getDeclaredField(name)
        .apply {isAccessible=true}.get(receiver)

    private inner class Fixture(document:String,ordinary:DanmakuPluginProcessor?=null,
        basProcessor:DesktopBasPluginProcessor?=null,standaloneAdmission:Boolean=true):AutoCloseable {
        val player=MpvPlayer()
        val alive=AtomicBoolean(true)
        val version=player.loadVersioned(PlaybackSource("private-bas-overlay-fixture"))
        private val source=object:DesktopDanmakuSource {
            override suspend fun metadata(cid:Long,aid:Long):ByteArray=throw IOException("Private XML fixture")
            override suspend fun segment(cid:Long,index:Int):ByteArray=throw IOException("Private XML fixture")
            override suspend fun special(url:String):ByteArray=error("No special/network source")
            override suspend fun xml(cid:Long)=document.toByteArray()
        }
        private val platform=object:DesktopOriginalDanmakuRenderPlatform {
            override fun resolveTypeface(fontWeight:Int)=Font("Dialog",Font.PLAIN,15)
            override fun systemChromeInsetPx()=0
            override fun maximumDisplayShortSidePx()=1080f
        }
        val overlay=DanmakuOverlay(player,platform,source=source,
            basStandaloneAdmission=if(standaloneAdmission) {snapshot,action->
                if(alive.get() && player.ownsSourceSnapshot(snapshot)) {action();true} else false
            } else null)
        init {
            overlay.applySettings(settings)
            overlay.setPluginDanmakuProcessors(ordinary,basProcessor)
            runBlocking {overlay.load(42L,aid=0L,durationSeconds=0.0,expectedSourceVersion=version,
                maskSource=null,stillOwned=alive::get)}
        }
        fun installation():Any?=edt {field(overlay,"basInstallation")}
        @Suppress("UNCHECKED_CAST")
        fun items():List<BasDanmaku> = edt {
            field(overlay,"basInstallation")?.let {field(it,"items") as List<BasDanmaku>} ?: emptyList()
        }
        @Suppress("UNCHECKED_CAST")
        fun rawItems():List<BasDanmaku> = edt {
            (field(overlay,"rawDocument") as DanmakuDocument).bas
        }
        fun installationCurrent():Boolean=edt {
            val installed=field(overlay,"basInstallation") ?: return@edt false
            DanmakuOverlay::class.java.getDeclaredMethod("basCurrent",installed.javaClass)
                .apply {isAccessible=true}.invoke(overlay,installed) as Boolean
        }
        fun scheduler():DanmakuScheduler=edt {field(overlay,"scheduler") as DanmakuScheduler}
        fun currentBasJob():Job=assertNotNull(field(overlay,"basFilterJob") as Job?)
        fun drain(job:Job) {
            runBlocking {withTimeout(5_000L) {job.join()}}
            edt { }
        }
        fun await(reason:String,predicate:()->Boolean) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(System.nanoTime()<deadline) {
                edt { }
                if(predicate())return
                Thread.sleep(5)
            }
            fail("Actual BAS publication did not reach $reason; count=${overlay.commentCount.value}")
        }
        fun awaitInstalled(count:Int,basCount:Int)=await("$count total / $basCount BAS") {
            overlay.commentCount.value==count && installationCurrent() && items().size==basCount
        }
        fun render(time:Double,measured:MutableList<Int>):List<PositionedDanmaku> = edt {
            scheduler().frame(time,640,300,DanmakuRenderConfig(typeface=Font("Dialog",Font.PLAIN,15),
                textSizePx=15f,lineHeightPx=24f,lineMarginPx=0f,lineCount=4,topMarginPx=0f,
                bottomMarginPx=0f,scrollDurationMs=7000L),
                {measured+=it.id;DesktopDanmakuTextMetrics(100,15.0)})
        }
        override fun close() {
            alive.set(false);overlay.close();player.close();edt { }
        }
    }

    @Test fun `BAS-only XML publishes a genuine installation and contributes to visible document count`() {
        Fixture(xml(bas(91,"BAS-only"))).use {f->
            f.awaitInstalled(1,1)
            val item=f.items().single()
            assertEquals(91L,item.id)
            assertEquals("BAS-only",item.content)
            assertEquals(1,item.program.elements.size)
            assertTrue(f.rawItems().single()===item) // No plugin: the actual parsed source object survives.
            assertTrue(f.render(0.2,mutableListOf()).isEmpty()) // Mode9 is never an ordinary lane.
            assertTrue(assertNotNull(f.overlay.poolSourceFor(42L,f.version)).comments.isEmpty())
        }
    }

    @Test fun `ordinary Root mode without a binding or standalone port cannot publish BAS`() {
        Fixture(xml(bas(91,"unbound Root document")),standaloneAdmission=false).use {f->
            f.drain(f.currentBasJob())
            assertEquals(1,f.rawItems().size)
            assertNull(f.installation());assertEquals(0,f.overlay.commentCount.value)
            assertFalse(f.installationCurrent())
            val source=assertNotNull(f.player.currentSourceSnapshot())
            val binding=edt {f.overlay.acquireBasActions(source,cid=42L,
                owned={f.alive.get() && f.player.ownsSourceSnapshot(source)},
                admit={publish->
                    if(f.alive.get() && f.player.ownsSourceSnapshot(source)) {publish();true} else false
                },activate={false})}
            try {
                f.awaitInstalled(1,1)
                assertEquals("unbound Root document",f.items().single().content)
            } finally {edt {binding.close()}}
        }
    }

    @Test fun `BAS refilters raw source without replaying ordinary plugins or replacing its scheduler`() {
        val ordinaryCalls=AtomicInteger()
        val basCalls=AtomicInteger()
        val ordinary:DanmakuPluginProcessor={item->ordinaryCalls.incrementAndGet();item to null}
        val processor=DesktopBasPluginProcessor(filter={item->
            basCalls.incrementAndGet();item.copy(content=item.content.replace("raw","raw processed"))
        },style={null})
        Fixture(xml(entry(90,1,"ordinary"),bas(91,"first raw"),bas(92,"second raw")),ordinary,processor).use {f->
            f.awaitInstalled(3,2)
            val scheduler=f.scheduler()
            assertEquals(listOf("first raw processed","second raw processed"),f.items().map {it.content})
            assertEquals(listOf("first raw","second raw"),f.rawItems().map {it.content})
            assertEquals(1,ordinaryCalls.get())
            assertEquals(2,basCalls.get())

            f.overlay.applySettings(settings.copy(blockedKeywords=listOf("first")))
            f.awaitInstalled(2,1)
            assertSame(scheduler,f.scheduler())
            assertEquals(1,ordinaryCalls.get())
            assertEquals(listOf("second raw processed"),f.items().map {it.content})
            assertEquals(listOf("first raw","second raw"),f.rawItems().map {it.content})
            f.overlay.applySettings(settings)
            f.awaitInstalled(3,2)
            assertEquals(listOf("first raw processed","second raw processed"),f.items().map {it.content})
            assertEquals(1,ordinaryCalls.get())
        }
    }

    @Test fun `render-only font opacity and lane geometry retain BAS output without replaying either plugin`() {
        val ordinaryCalls=AtomicInteger();val basCalls=AtomicInteger()
        val ordinary:DanmakuPluginProcessor={item->ordinaryCalls.incrementAndGet();item to null}
        val processor=DesktopBasPluginProcessor(filter={item->basCalls.incrementAndGet();item},style={null})
        Fixture(xml(entry(90,1,"ordinary"),bas(91,"render-only BAS")),ordinary,processor).use {f->
            f.awaitInstalled(2,1)
            val installation=f.installation();val raw=f.rawItems();val output=f.items();val scheduler=f.scheduler()
            val measured=mutableListOf<Int>();val before=f.render(0.2,measured).single()
            f.overlay.applySettings(settings.copy(fontScale=1.3f,fontWeight=7,opacity=0.4f,
                lineHeight=2f,strokeWidth=2f,displayAreaRatio=0.75f))
            edt { }
            assertTrue(f.installationCurrent())
            assertSame(installation,f.installation())
            assertSame(raw,f.rawItems());assertSame(output,f.items());assertSame(scheduler,f.scheduler())
            assertEquals(before,f.render(0.2,measured).single())
            assertEquals(1,measured.size)
            assertEquals(1,ordinaryCalls.get());assertEquals(1,basCalls.get())
            assertEquals(2,f.overlay.commentCount.value)
        }
    }

    @Test fun `same-version recovery retires the old full-source BAS publication`() {
        Fixture(xml(bas(91,"old full source"))).use {f->
            f.awaitInstalled(1,1)
            val first=assertNotNull(f.player.currentSourceSnapshot())
            assertTrue(f.player.recoverSource(f.version,positionSeconds=2.0,paused=true))
            val next=assertNotNull(f.player.currentSourceSnapshot())
            assertEquals(first.sourceVersion,next.sourceVersion)
            assertNotEquals(first.source,next.source)
            assertFalse(f.player.ownsSourceSnapshot(first))
            assertFalse(f.installationCurrent())
            f.await("old full source retired") {f.installation()==null && f.overlay.commentCount.value==0}
        }
    }

    @Test fun `same-version valid document recovery retickets BAS under the fresh binding`() {
        val ordinaryCalls=AtomicInteger();val admissions=AtomicInteger()
        val ordinary:DanmakuPluginProcessor={item->ordinaryCalls.incrementAndGet();item to null}
        Fixture(xml(entry(90,1,"ordinary"),bas(91,"recovered BAS")),ordinary).use {f->
            f.awaitInstalled(2,1)
            val prior=f.installation();val raw=f.rawItems();val scheduler=f.scheduler()
            val first=assertNotNull(f.player.currentSourceSnapshot())
            assertTrue(f.player.recoverSource(f.version,positionSeconds=2.0,paused=true))
            val recovered=assertNotNull(f.player.currentSourceSnapshot())
            assertEquals(first.sourceVersion,recovered.sourceVersion)
            assertNotEquals(first.source,recovered.source)
            assertFalse(f.installationCurrent())
            val binding=edt {f.overlay.acquireBasActions(recovered,cid=42L,
                owned={f.alive.get() && f.player.ownsSourceSnapshot(recovered)},
                admit={publish->
                    admissions.incrementAndGet()
                    if(f.alive.get() && f.player.ownsSourceSnapshot(recovered)) {publish();true} else false
                },activate={false})}
            try {
                f.awaitInstalled(2,1)
                assertNotSame(prior,f.installation())
                assertSame(raw,f.rawItems());assertSame(scheduler,f.scheduler())
                assertEquals("recovered BAS",f.items().single().content)
                assertEquals(1,ordinaryCalls.get());assertEquals(1,admissions.get())
                assertFalse(f.player.ownsSourceSnapshot(first))
            } finally {edt {binding.close()}}
        }
    }

    @Test fun `a fresh binding cannot revive a retired document owner`() {
        val basCalls=AtomicInteger()
        val processor=DesktopBasPluginProcessor(filter={item->basCalls.incrementAndGet();item},style={null})
        Fixture(xml(bas(91,"retired document")),basProcessor=processor).use {f->
            f.awaitInstalled(1,1)
            f.alive.set(false)
            assertTrue(f.player.recoverSource(f.version,positionSeconds=2.0,paused=true))
            val recovered=assertNotNull(f.player.currentSourceSnapshot())
            val admissions=AtomicInteger()
            val binding=edt {f.overlay.acquireBasActions(recovered,cid=42L,
                owned={f.player.ownsSourceSnapshot(recovered)},
                admit={publish->admissions.incrementAndGet();publish();true},activate={false})}
            try {
                f.await("retired document stays absent") {f.installation()==null && f.overlay.commentCount.value==0}
                assertEquals(1,basCalls.get());assertEquals(0,admissions.get())
                assertTrue(f.player.ownsSourceSnapshot(recovered)) // Binding alone is still current.
                assertEquals(1,f.rawItems().size) // Retained raw data is not new authority.
                assertFalse(f.installationCurrent())
            } finally {edt {binding.close()}}
        }
    }

    @Test fun `rejected final presentation admission cannot publish a processed BAS result`() {
        val basCalls=AtomicInteger();val admissions=AtomicInteger()
        val processor=DesktopBasPluginProcessor(filter={item->
            item.copy(content=item.content.replace("admission BAS", "admission BAS processed-${basCalls.incrementAndGet()}"))
        },style={null})
        Fixture(xml(bas(91,"admission BAS")),basProcessor=processor).use {f->
            f.awaitInstalled(1,1)
            assertEquals("admission BAS processed-1", f.items().single().content)
            val prior=f.installation()
            val source=assertNotNull(f.player.currentSourceSnapshot())
            val binding=edt {f.overlay.acquireBasActions(source,cid=42L,owned={true},
                admit={_->admissions.incrementAndGet();false},activate={false})}
            try {
                f.overlay.applySettings(settings.copy(blockedKeywords=listOf("unmatched filter")))
                f.drain(f.currentBasJob())
                assertEquals(2,basCalls.get());assertEquals(1,admissions.get())
                val after=f.installation()
                assertTrue(after==null || after===prior)
                assertFalse(f.items().any {it.content.endsWith("processed-2")})
                assertFalse(f.installationCurrent())
                f.await("rejected result contributes no BAS count") {f.overlay.commentCount.value==0}
            } finally {edt {binding.close()}}
        }
    }

    @Test fun `invalid plugin DSL publishes only safe rejection enum counts`() {
        val styleCalls=AtomicInteger()
        val processor=DesktopBasPluginProcessor(filter={item->
            item.copy(content="def text invalid { content = }")
        },style={styleCalls.incrementAndGet();null})
        Fixture(xml(bas(91,"private raw script")),basProcessor=processor).use {f->
            f.awaitInstalled(0,0)
            assertEquals(mapOf(DesktopBasRejection.INVALID_SCRIPT to 1),f.overlay.basRejections.value)
            assertEquals(0,styleCalls.get())
            assertEquals("private raw script",f.rawItems().single().content)
            assertTrue(f.items().isEmpty())
            assertTrue(f.installationCurrent())
        }
    }

    @Test fun `blocked old BAS worker cannot publish after a different load wins`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val processor=DesktopBasPluginProcessor(filter={item->
            entered.countDown();check(release.await(5,TimeUnit.SECONDS));item
        },style={null})
        Fixture(xml(bas(91,"late old source")),basProcessor=processor).use {f->
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                val job=f.currentBasJob()
                assertTrue(f.player.loadVersioned(PlaybackSource("private-next-bas-source"))>f.version)
                release.countDown();f.drain(job)
                f.await("old worker rejected") {f.installation()==null && f.overlay.commentCount.value==0}
                assertTrue(f.items().isEmpty())
                assertFalse(f.installationCurrent())
            } finally {release.countDown()}
        }
    }

    @Test fun `actual local append keeps BAS count and the installed BAS and ordinary objects`() {
        val calls=AtomicInteger()
        val ordinary:DanmakuPluginProcessor={item->calls.incrementAndGet();item to null}
        Fixture(xml(entry(90,1,"ordinary"),bas(91,"retained BAS")),ordinary).use {f->
            f.awaitInstalled(2,1)
            val bas=f.installation();val scheduler=f.scheduler()
            assertTrue(f.overlay.addOriginalPortraitDanmaku(f.version,"local",0xffffff,1,25))
            f.awaitInstalled(3,1)
            assertSame(bas,f.installation())
            assertSame(scheduler,f.scheduler())
            assertEquals(1,calls.get())
            assertEquals(1,f.rawItems().size)
            assertEquals(1,assertNotNull(f.overlay.poolSourceFor(42L,f.version)).comments.count {it.originalLocalItem!=null})
        }
    }

    @Test fun `actual idle cache clear drains BAS work and retires its installed scene`() {
        Fixture(xml(bas(91,"cached BAS"))).use {f->
            f.awaitInstalled(1,1)
            runBlocking {f.overlay.clearIdleCache {check(f.alive.get())}}
            f.await("cache retirement") {f.installation()==null && f.overlay.commentCount.value==0}
            assertTrue(f.rawItems().isEmpty())
            assertFalse(f.installationCurrent())
        }
    }

    @Test fun `close rejects a blocked BAS result before its queued EDT publication`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val processor=DesktopBasPluginProcessor(filter={item->
            entered.countDown();check(release.await(5,TimeUnit.SECONDS));item
        },style={null})
        Fixture(xml(bas(91,"late closed source")),basProcessor=processor).use {f->
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                val job=f.currentBasJob()
                f.overlay.close()
                release.countDown();f.drain(job)
                assertNull(f.installation())
                assertTrue(f.items().isEmpty())
                assertEquals(0,f.overlay.commentCount.value)
            } finally {release.countDown()}
        }
    }
}
