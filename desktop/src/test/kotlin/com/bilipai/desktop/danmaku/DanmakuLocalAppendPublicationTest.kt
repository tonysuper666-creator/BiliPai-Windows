package com.bilipai.desktop.danmaku

import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.runBlocking
import java.awt.Font
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.*

/** Actual Overlay, existing plugin worker and real headless requested-source owner.
 * These tests create no display/native decoder, HTTP requests or account profile. */
class DanmakuLocalAppendPublicationTest {
    private fun <T> edt(block:()->T):T {
        if(SwingUtilities.isEventDispatchThread())return block()
        var result:Result<T>?=null
        SwingUtilities.invokeAndWait {result=runCatching(block)}
        return checkNotNull(result).getOrThrow()
    }
    private inner class Fixture(processor:DanmakuPluginProcessor?=null):AutoCloseable {
        val player=MpvPlayer()
        val alive=AtomicBoolean(true)
        val version=player.loadVersioned(PlaybackSource("private-local-append-fixture"))
        private val source=object:DesktopDanmakuSource {
            override suspend fun metadata(cid:Long,aid:Long):ByteArray=throw IOException("Private XML fixture")
            override suspend fun segment(cid:Long,index:Int):ByteArray=throw IOException("Private XML fixture")
            override suspend fun special(url:String):ByteArray=error("No special/network source")
            override suspend fun xml(cid:Long)=
                "<i><d p=\"0,1,25,16777215,0,0,private,9001\">ordinary</d><d p=\"8,1,25,16777215,0,0,private,9002\">future</d></i>".toByteArray()
        }
        private val platform=object:DesktopOriginalDanmakuRenderPlatform {
            override fun resolveTypeface(fontWeight:Int)=Font("Dialog",Font.PLAIN,20)
            override fun systemChromeInsetPx()=0
            override fun maximumDisplayShortSidePx()=1080f
        }
        val overlay=DanmakuOverlay(player,platform,source=source)
        init {
            overlay.applySettings(DanmakuSettings(mergeDuplicates=false,smartOcclusionEnabled=false,displayAreaRatio=1f))
            overlay.setPluginDanmakuProcessor(processor)
            runBlocking {overlay.load(42L,aid=0L,durationSeconds=0.0,expectedSourceVersion=version,maskSource=null,stillOwned=alive::get)}
        }
        fun scheduler():DanmakuScheduler=edt {
            DanmakuOverlay::class.java.getDeclaredField("scheduler").apply {isAccessible=true}.get(overlay) as DanmakuScheduler
        }
        fun awaitInstalled(count:Int) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(System.nanoTime()<deadline) {
                edt { }
                if(overlay.commentCount.value==count)return
                Thread.sleep(5)
            }
            fail("Actual Overlay did not publish $count items; current=${overlay.commentCount.value}")
        }
        fun render(time:Double,measured:MutableList<Int> = mutableListOf())=edt {
            scheduler().frame(time,640,300,
                com.android.purebilibili.danmaku.engine.DanmakuRenderConfig(
                    typeface=Font("Dialog",Font.PLAIN,20),lineHeightPx=32f,lineMarginPx=0f,
                    lineCount=4,topMarginPx=0f,bottomMarginPx=0f,scrollDurationMs=7000L),
                {measured+=it.id;DesktopDanmakuTextMetrics(100,20.0)})
        }
        fun append(text:String)=overlay.addOriginalPortraitDanmaku(version,text,0xffffff,1,25)
        override fun close() {
            alive.set(false);overlay.close();player.close();edt { }
        }
    }

    @Test fun `installed local appends retain actual scheduler and do not reprocess ordinary plugin items`() {
        val processed=mutableListOf<String>()
        val processor:DanmakuPluginProcessor={item->synchronized(processed){processed+=item.content};item to null}
        Fixture(processor).use {f->
            f.awaitInstalled(2)
            val scheduler=f.scheduler()
            val measured=mutableListOf<Int>()
            val before=f.render(0.2,measured).single()
            assertTrue(f.append("first"));assertTrue(f.append("second"))
            f.awaitInstalled(4)
            assertSame(scheduler,f.scheduler())
            val after=f.render(0.2,measured)
            assertEquals(before,after.single {it.comment.id>=0})
            assertEquals(2,after.count {it.comment.originalLocalItem!=null})
            assertEquals(listOf("ordinary","future"),synchronized(processed){processed.toList()})
            assertEquals(1,measured.count {it==0})
            assertEquals(3,measured.size)
            f.render(0.2,measured)
            assertEquals(3,measured.size)
        }
    }

    @Test fun `multiple appends during actual blocked plugin document publish once without restarting plugin`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val processed=mutableListOf<String>()
        val processor:DanmakuPluginProcessor={item->
            synchronized(processed){processed+=item.content}
            if(item.content=="ordinary") {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS)) {"Plugin fixture was not released"}
            }
            item to null
        }
        Fixture(processor).use {f->
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                assertTrue(f.append("first"));assertTrue(f.append("second"));edt { }
                assertEquals(listOf("ordinary"),synchronized(processed){processed.toList()})
            } finally {release.countDown()}
            f.awaitInstalled(4)
            val measured=mutableListOf<Int>()
            val result=f.render(0.2,measured)
            assertEquals(2,result.count {it.comment.originalLocalItem!=null})
            assertEquals(2,result.filter {it.comment.originalLocalItem!=null}.map {it.comment.id}.distinct().size)
            assertEquals(listOf("ordinary","future"),synchronized(processed){processed.toList()})
            f.render(0.2,measured)
            assertEquals(3,measured.size)
        }
    }

    @Test fun `owner retirement before queued append rejects installed timeline mutation`() {
        Fixture().use {f->
            f.awaitInstalled(2)
            val scheduler=f.scheduler();val before=f.render(0.2)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            SwingUtilities.invokeLater {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                assertTrue(f.append("old owner"))
                f.alive.set(false)
            } finally {release.countDown()}
            edt { }
            assertSame(scheduler,f.scheduler())
            assertEquals(before,f.render(0.2))
            assertEquals(2,f.overlay.commentCount.value)
            assertFalse(f.append("retired"))
        }
    }

    @Test fun `actual source replacement rejects queued old source insertion`() {
        Fixture().use {f->
            f.awaitInstalled(2)
            val scheduler=f.scheduler();val before=f.render(0.2)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            SwingUtilities.invokeLater {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                assertTrue(f.append("old source"))
                assertTrue(f.player.loadVersioned(PlaybackSource("private-new-source"))>f.version)
            } finally {release.countDown()}
            edt { }
            assertSame(scheduler,f.scheduler())
            assertEquals(before,f.render(0.2))
            assertEquals(2,f.overlay.commentCount.value)
            assertFalse(f.append("replaced"))
        }
    }

    @Test fun `genuine settings retirement during pending publication filters retained local normally`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val first=AtomicBoolean(true)
        val processor:DanmakuPluginProcessor={item->
            if(item.content=="ordinary" && first.compareAndSet(true,false)) {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS))
            }
            item to null
        }
        Fixture(processor).use {f->
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                // Do not drain the queued append before the real settings phase changes.
                edt {
                    assertTrue(f.append("own"))
                    f.overlay.applySettings(DanmakuSettings(mergeDuplicates=false,smartOcclusionEnabled=false,
                        displayAreaRatio=1f,blockedKeywords=listOf("own")))
                    assertTrue(f.append("own fresh"))
                }
            } finally {release.countDown()}
            f.awaitInstalled(4)
            assertEquals(listOf("『 own fresh 』"),f.render(0.2).filter {it.comment.originalLocalItem!=null}.map {it.comment.text})
            edt {f.scheduler().resetTimeline()}
            assertEquals(listOf("『 own fresh 』"),f.render(0.2).filter {it.comment.originalLocalItem!=null}.map {it.comment.text})
            assertEquals(2,assertNotNull(f.overlay.poolSourceFor(42L,f.version)).comments.count {it.originalLocalItem!=null})
        }
    }

    @Test fun `actual final append before queued settings update preserves newly accepted local phase`() {
        Fixture().use {f->
            f.awaitInstalled(2)
            edt {
                f.overlay.applySettings(DanmakuSettings(mergeDuplicates=false,smartOcclusionEnabled=false,
                    displayAreaRatio=1f,blockedKeywords=listOf("own")))
                assertTrue(f.append("own fresh"))
                // Exercise the real guarded final method in the interleaving where
                // raw settings/phase are published but the old scheduler update is queued.
                val generation=DanmakuOverlay::class.java.getDeclaredField("generation").apply {isAccessible=true}
                    .get(f.overlay) as java.util.concurrent.atomic.AtomicLong
                val revision=DanmakuOverlay::class.java.getDeclaredField("documentInstallRevision").apply {isAccessible=true}
                    .getLong(f.overlay)
                DanmakuOverlay::class.java.getDeclaredMethod("installOriginalLocalAppend",java.lang.Long.TYPE,
                    java.lang.Long.TYPE).apply {isAccessible=true}.invoke(f.overlay,generation.get(),revision)
                assertEquals(1,f.render(0.2).count {it.comment.originalLocalItem!=null})
            }
            f.awaitInstalled(3)
            val measured=mutableListOf<Int>()
            val before=f.render(0.2,measured)
            assertEquals(1,before.count {it.comment.originalLocalItem!=null})
            assertEquals(before,f.render(0.2,measured))
            assertTrue(measured.isEmpty()) // Queued duplicate publish/update did not restart admission.
        }
    }

    @Test fun `fresh append cannot resurrect a local already dropped by genuine plugin rebuild`() {
        val dropOld=AtomicBoolean(false);val processed=mutableListOf<String>()
        val processor:DanmakuPluginProcessor={item->
            synchronized(processed){processed+=item.content}
            if(dropOld.get() && item.content.contains("old local"))null else item to null
        }
        Fixture(processor).use {f->
            f.awaitInstalled(2)
            assertTrue(f.append("old local"));f.awaitInstalled(3)
            assertEquals(1,f.render(0.2).count {it.comment.originalLocalItem!=null})
            dropOld.set(true)
            f.overlay.setPluginDanmakuProcessor(processor) // Real ordinary preprocessing retirement.
            f.awaitInstalled(2)
            assertTrue(f.render(0.2).none {it.comment.originalLocalItem!=null})
            val afterRebuild=synchronized(processed){processed.toList()}
            assertEquals(1,afterRebuild.count {it.contains("old local")})
            assertTrue(f.append("fresh local"));f.awaitInstalled(3)
            val visible=f.render(0.2).filter {it.comment.originalLocalItem!=null}
            assertEquals(listOf("『 fresh local 』"),visible.map {it.comment.text})
            assertEquals(afterRebuild,synchronized(processed){processed.toList()})
            edt {f.scheduler().resetTimeline()}
            assertEquals(listOf("『 fresh local 』"),f.render(0.2).filter {it.comment.originalLocalItem!=null}.map {it.comment.text})
            assertEquals(2,assertNotNull(f.overlay.poolSourceFor(42L,f.version)).comments.count {it.originalLocalItem!=null})
        }
    }
}
