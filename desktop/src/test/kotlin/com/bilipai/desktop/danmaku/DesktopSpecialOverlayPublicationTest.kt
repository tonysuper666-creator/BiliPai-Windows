package com.bilipai.desktop.danmaku

import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.PlayerState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.Font
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.*

/** Actual Loader/Overlay pipeline with a headless requested source. Position
 * flow is driven privately for window selection, not advertised as native seek. */
class DesktopSpecialOverlayPublicationTest {
    private fun <T> edt(block:()->T):T {
        if(SwingUtilities.isEventDispatchThread())return block()
        var value:Result<T>?=null;SwingUtilities.invokeAndWait {value=runCatching(block)}
        return checkNotNull(value).getOrThrow()
    }
    private fun field(target:Any,name:String)=target.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(target)
    private inner class Fixture(val source:SpecialWindowFixtureSource,processor:DanmakuPluginProcessor?=null,formal:Boolean=false,
        retireBeforeCollector:Boolean=false):AutoCloseable {
        val player=MpvPlayer();val alive=AtomicBoolean(true)
        val retiredAfterPublication=AtomicBoolean(false)
        val version=player.loadVersioned(PlaybackSource("private-special-publication"))
        val snapshot=assertNotNull(player.currentSourceSnapshot())
        val overlay=DanmakuOverlay(player,object:DesktopOriginalDanmakuRenderPlatform {
            override fun resolveTypeface(fontWeight:Int)=Font("Dialog",Font.PLAIN,15)
            override fun systemChromeInsetPx()=0
            override fun maximumDisplayShortSidePx()=1080f
        },source=source,basStandaloneAdmission={owned,action->
            if(alive.get() && player.ownsSourceSnapshot(owned)) {
                // Window publication is on requests/IO; BAS rendering admission
                // runs on EDT. Retire only after the real initial publish action,
                // before loadSource can register its window collector.
                val retire=retireBeforeCollector && !SwingUtilities.isEventDispatchThread() &&
                    retiredAfterPublication.compareAndSet(false,true)
                if(retire)runBlocking {withTimeout(3000) {source.openStarted.await()}}
                action()
                if(retire)alive.set(false)
                true
            } else false})
        var binding:AutoCloseable?=null
        init {
            overlay.applySettings(DanmakuSettings(mergeDuplicates=false,smartOcclusionEnabled=false))
            overlay.setPluginDanmakuProcessors(processor,null)
            if(formal)binding=edt {overlay.acquireBasActions(snapshot,42,
                owned={alive.get() && player.ownsSourceSnapshot(snapshot)},
                admit={publish->if(alive.get() && player.ownsSourceSnapshot(snapshot)) {publish();true}else false},activate={false})}
            runBlocking {overlay.load(42,0,100.0,version,null,alive::get)}
        }
        fun raw()=field(overlay,"rawDocument") as DanmakuDocument
        fun scheduler()=edt {field(overlay,"scheduler")}
        fun revision()=(field(overlay,"documentInstallRevision") as Long)
        @Suppress("UNCHECKED_CAST") fun position(seconds:Double) {
            val state=field(player,"mutableState") as MutableStateFlow<PlayerState>
            state.value=state.value.copy(positionSeconds=seconds)
        }
        fun await(reason:String,predicate:()->Boolean) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(System.nanoTime()<deadline) {edt {};if(predicate())return;Thread.sleep(5)}
            fail("Special publication did not reach $reason; count=${overlay.commentCount.value}")
        }
        fun drainIndex()=runBlocking {withTimeout(3000) {source.openCancelled.await()}}
        override fun close() {alive.set(false);edt {binding?.close()};overlay.close();player.close();edt {}}
    }

    @Test fun `special-only refresh preserves ordinary scheduler plugin styles and local additions`(): Unit {
        val gate=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val source=SpecialWindowFixtureSource(listOf(
            specialElement(1,0,"def text first {content=\"first\" duration=3s}"),
            specialElement(2,5000,"def text next {content=\"next\" duration=10s}")),gate)
        Fixture(source,{item->calls.incrementAndGet();item to null}).use {f->
            f.await("ordinary install") {f.overlay.commentCount.value==1}
            val scheduler=f.scheduler();val install=f.revision()
            assertTrue(f.overlay.addOriginalPortraitDanmaku(f.version,"local",0xffffff,1,25))
            f.await("actual local append") {f.overlay.commentCount.value==2}
            val ordinary=f.raw().comments
            gate.complete(Unit)
            f.await("first special scene") {f.raw().bas.singleOrNull()?.content=="first" && f.overlay.commentCount.value==3}
            f.position(5.0)
            f.await("next original short window") {f.raw().bas.singleOrNull()?.content=="next" && f.overlay.commentCount.value==3}
            assertSame(scheduler,f.scheduler());assertSame(ordinary,f.raw().comments)
            assertEquals(install,f.revision());assertEquals(1,calls.get());assertEquals(1,source.segmentReads)
            assertEquals(1,f.raw().comments.count {it.originalLocalItem!=null})
        }
    }

    @Test fun `pending ordinary plugin result merges the newest special window instead of overwriting it`(): Unit {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val gate=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val source=SpecialWindowFixtureSource(listOf(
            specialElement(1,0,"def text current {content=\"current\" duration=10s}"),
            specialElement(2,0,"[0,0,\"1-1\",10,\"advanced\",0,0]",7)),gate)
        Fixture(source,{item->calls.incrementAndGet();entered.countDown();check(release.await(5,TimeUnit.SECONDS));item to null}).use {f->
            try {
                assertTrue(entered.await(3,TimeUnit.SECONDS));gate.complete(Unit)
                f.await("latest raw special result") {f.raw().bas.size==1 && f.raw().advanced.size==1}
                assertTrue(f.overlay.addOriginalPortraitDanmaku(f.version,"late local",0xffffff,1,25))
                release.countDown()
                f.await("merged original install") {f.overlay.commentCount.value==4}
                assertEquals(1,calls.get());assertEquals("advanced",f.overlay.advancedItems.value.single().content)
                assertEquals(1,f.raw().comments.count {it.originalLocalItem!=null})
            } finally {release.countDown();gate.complete(Unit)}
        }
    }

    @Test fun `a retired formal Root binding never falls back to a standalone or native-only permit`(): Unit {
        val gate=CompletableDeferred<Unit>()
        val source=SpecialWindowFixtureSource(listOf(specialElement(1,0,"def text late {content=\"late\" duration=10s}")),gate)
        Fixture(source,formal=true).use {f->
            f.await("original row") {f.overlay.commentCount.value==1}
            edt {f.binding!!.close();f.binding=null};gate.complete(Unit)
            runBlocking {withTimeout(3000) {while((field(f.overlay,"windowLoader") as DanmakuWindowLoader).specialRevision.value==0L)yield()}}
            edt {};Thread.sleep(40);edt {}
            assertTrue(f.raw().bas.isEmpty());assertEquals(1,f.overlay.commentCount.value)
            assertTrue(f.player.ownsSourceSnapshot(f.snapshot)) // Native ownership alone is insufficient.
        }
    }

    @Test fun `same-version full source recovery retires an old index worker and closing preserves no late publication`(): Unit {
        val gate=CompletableDeferred<Unit>()
        val source=SpecialWindowFixtureSource(listOf(specialElement(1,0,"def text late {content=\"late\" duration=10s}")),gate)
        Fixture(source).use {f->
            runBlocking {source.openStarted.await()}
            assertTrue(f.player.recoverSource(f.version,positionSeconds=2.0,paused=true))
            f.drainIndex();gate.complete(Unit);edt {}
            assertFalse(f.player.ownsSourceSnapshot(f.snapshot));assertTrue(f.raw().bas.isEmpty())
            // Only the old window collector is retired; the Overlay request scope
            // must still accept a new explicit load for the recovered full source.
            runBlocking {f.overlay.load(43,0,100.0,f.version,null,f.alive::get)}
            f.await("fresh source load after retiring old index") {f.raw().bas.singleOrNull()?.content=="late"}
        }
    }

    @Test fun `owner retirement after initial publication closes an unregistered index and permits the next load`(): Unit {
        val gate=CompletableDeferred<Unit>()
        val source=SpecialWindowFixtureSource(listOf(specialElement(1,0,"def text next {content=\"next owner\" duration=10s}")),gate)
        Fixture(source,retireBeforeCollector=true).use {f->
            assertTrue(f.retiredAfterPublication.get())
            assertFalse(f.alive.get())
            val retiredLoader=field(f.overlay,"windowLoader") as DanmakuWindowLoader
            f.drainIndex()
            assertTrue((field(retiredLoader,"closed") as AtomicBoolean).get())
            assertTrue((field(retiredLoader,"indexOwner") as Job).isCancelled)
            assertNull(field(f.overlay,"windowJob"),"No collector was registered for the retired owner")
            assertEquals(listOf("ordinary"),f.raw().comments.map {it.text},"Retirement happens after initial publication")
            assertTrue(f.raw().bas.isEmpty())
            assertTrue(source.reads.isEmpty(),"The cancelled index must not fetch an envelope")
            assertTrue(f.player.ownsSourceSnapshot(f.snapshot),"Only the document owner retired")
            gate.complete(Unit);f.alive.set(true)
            runBlocking {f.overlay.load(43,0,100.0,f.version,null,f.alive::get)}
            f.await("successor publication after denied collector registration") {f.raw().bas.singleOrNull()?.content=="next owner"}
            assertNotSame(retiredLoader,field(f.overlay,"windowLoader"))
            assertTrue((field(retiredLoader,"closed") as AtomicBoolean).get())
        }
    }
}
