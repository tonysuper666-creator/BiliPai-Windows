package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig
import com.android.purebilibili.feature.video.danmaku.desktopOriginalLocalDanmakuItem
import java.awt.Font
import kotlin.test.*

class DanmakuLocalAppendTimelineTest {
    private fun ordinary(id:Int,time:Double,text:String="ordinary$id")=
        DanmakuComment(id,time,1,25,0xffffff,text)
    private fun local(id:Int,time:Double,phase:Any,text:String="own") : DanmakuComment {
        val item=desktopOriginalLocalDanmakuItem(text,0xffffff,1,25,(time*1000).toLong()-100,false)
        return DanmakuComment(id,item.showAtTime/1000.0,1,25,0xffffff,item.text.orEmpty(),
            originalLocalItem=item,originalLocalInjectionPhase=phase)
    }
    private fun config(lines:Int=4)=DanmakuRenderConfig(typeface=Font("Dialog",Font.PLAIN,20),
        lineHeightPx=32f,lineMarginPx=0f,lineCount=lines,topMarginPx=0f,bottomMarginPx=0f,scrollDurationMs=7000L)
    private fun scheduler(comments:List<DanmakuComment>,settings:DanmakuSettings=
        DanmakuSettings(mergeDuplicates=false,displayAreaRatio=1f))=DanmakuScheduler(comments,settings,false)
    private fun frame(scheduler:DanmakuScheduler,time:Double,lines:Int=4,measured:MutableList<Int> = mutableListOf())=
        scheduler.frame(time,640,300,config(lines)) {measured+=it.id;DesktopDanmakuTextMetrics(100,20.0)}

    @Test fun `append leaves an admitted trajectory and future ordinary arrivals intact`() {
        val scheduler=scheduler(listOf(ordinary(0,0.0),ordinary(1,0.7)))
        val measured=mutableListOf<Int>()
        val before=frame(scheduler,0.3,measured=measured).single()
        val phase=Any();val local=local(-10,0.4,phase)
        assertEquals(listOf(local),scheduler.appendOriginalLocalComments(listOf(local),phase))
        assertEquals(before,frame(scheduler,0.3,measured=measured).single())
        val later=frame(scheduler,0.5,measured=measured)
        val preserved=later.single {it.comment.id==0}
        assertEquals(640.0-740.0*0.5/7.0,preserved.x,0.000001)
        assertEquals(before.baseline,preserved.baseline,0.0)
        assertEquals(before.textWidth,preserved.textWidth)
        assertEquals(setOf(0,-10),later.map {it.comment.id}.toSet())
        frame(scheduler,0.7,measured=measured)
        assertEquals(listOf(0,-10,1),measured)
    }

    @Test fun `same paused timestamp repeated appends are each admitted once and bypass preprocessing`() {
        val settings=DanmakuSettings(mergeDuplicates=true,blockedKeywords=listOf("own"),displayAreaRatio=1f)
        val scheduler=scheduler(listOf(ordinary(0,0.0)),settings)
        val measured=mutableListOf<Int>()
        val before=frame(scheduler,0.3,measured=measured).single()
        val phase=Any();val first=local(-10,0.2,phase);val second=local(-9,0.2,phase)
        assertEquals(listOf(first),scheduler.appendOriginalLocalComments(listOf(first),phase))
        assertEquals(listOf(second),scheduler.appendOriginalLocalComments(listOf(first,second),phase))
        assertEquals(emptyList(),scheduler.appendOriginalLocalComments(listOf(first,second),phase))
        val after=frame(scheduler,0.3,measured=measured)
        assertEquals(before,after.single {it.comment.id==0})
        assertEquals(setOf(0,-10,-9),after.map {it.comment.id}.toSet())
        assertEquals(after,frame(scheduler,0.3,measured=measured))
        assertEquals(listOf(0,-10,-9),measured)
    }

    @Test fun `insertion before consumed cursor cannot replay collision dropped ordinary items`() {
        val scheduler=scheduler(listOf(ordinary(0,0.0),ordinary(1,0.1),ordinary(2,0.25),ordinary(3,0.8)))
        val measured=mutableListOf<Int>()
        val before=frame(scheduler,0.3,1,measured).single()
        assertEquals(listOf(0,1,2),measured)
        val phase=Any();val local=local(-10,0.2,phase)
        assertNotNull(scheduler.appendOriginalLocalComments(listOf(local),phase))
        assertEquals(before,frame(scheduler,0.3,1,measured).single()) // Existing occupant still blocks this local.
        frame(scheduler,0.3,1,measured)
        assertEquals(listOf(0,1,2,-10),measured)
        frame(scheduler,0.8,1,measured)
        assertEquals(listOf(0,1,2,-10,3),measured)
    }

    @Test fun `late insertion before cursor is visible once when a track is available`() {
        val scheduler=scheduler(listOf(ordinary(0,0.0),ordinary(1,0.25)))
        val measured=mutableListOf<Int>()
        val before=frame(scheduler,0.3,measured=measured)
        val phase=Any();val local=local(-10,0.2,phase)
        scheduler.appendOriginalLocalComments(listOf(local),phase)
        val after=frame(scheduler,0.3,measured=measured)
        assertEquals(before,after.filter {it.comment.id>=0})
        assertEquals(1,after.count {it.comment.id==-10})
        assertEquals(1,measured.count {it==-10})
        assertEquals(after,frame(scheduler,0.3,measured=measured))
        assertEquals(1,measured.count {it==-10})
    }

    @Test fun `zero tracks consume a late local insertion without reviving it on restoration`() {
        val scheduler=scheduler(listOf(ordinary(0,0.0),ordinary(1,0.25)))
        val measured=mutableListOf<Int>()
        frame(scheduler,0.3,measured=measured)
        val phase=Any();val local=local(-10,0.2,phase)
        scheduler.appendOriginalLocalComments(listOf(local),phase)
        assertTrue(frame(scheduler,0.3,0,measured).isEmpty())
        val restored=frame(scheduler,0.3,measured=measured)
        assertEquals(setOf(0,1),restored.map {it.comment.id}.toSet())
        assertFalse(-10 in measured)
    }

    @Test fun `expired delayed addition does not become a fresh item`() {
        val scheduler=scheduler(listOf(ordinary(0,0.0),ordinary(1,9.0)))
        val measured=mutableListOf<Int>()
        frame(scheduler,8.0,measured=measured)
        val phase=Any();val local=local(-10,0.2,phase)
        scheduler.appendOriginalLocalComments(listOf(local),phase)
        assertTrue(frame(scheduler,8.0,measured=measured).none {it.comment.id==-10})
        frame(scheduler,8.1,measured=measured)
        assertEquals(1,measured.count {it==-10})
    }

    @Test fun `true seek and explicit reset restore appended entries from the same complete timeline`() {
        val scheduler=scheduler(listOf(ordinary(0,0.0),ordinary(1,0.25)))
        val measured=mutableListOf<Int>()
        frame(scheduler,0.3,measured=measured)
        val phase=Any();val local=local(-10,0.2,phase)
        scheduler.appendOriginalLocalComments(listOf(local),phase)
        frame(scheduler,0.3,measured=measured)
        frame(scheduler,0.1,measured=measured)
        assertEquals(1,frame(scheduler,0.3,measured=measured).count {it.comment.id==-10})
        assertEquals(2,measured.count {it==-10})
        scheduler.resetTimeline()
        assertEquals(1,frame(scheduler,0.3,measured=measured).count {it.comment.id==-10})
        assertEquals(3,measured.count {it==-10})
    }

    @Test fun `forward discontinuity does not double admit a pending inserted item`() {
        val scheduler=scheduler(listOf(ordinary(0,0.0),ordinary(1,0.25)))
        val measured=mutableListOf<Int>()
        frame(scheduler,0.3,measured=measured)
        val phase=Any();val local=local(-10,0.2,phase)
        scheduler.appendOriginalLocalComments(listOf(local),phase)
        val destination=frame(scheduler,2.0,measured=measured)
        assertEquals(1,destination.count {it.comment.id==-10})
        assertEquals(1,measured.count {it==-10})
    }

    @Test fun `phase retirement restores normal filters for the durable appended local`() {
        val settings=DanmakuSettings(mergeDuplicates=false,blockedKeywords=listOf("own"),displayAreaRatio=1f)
        val scheduler=scheduler(listOf(ordinary(0,0.0)),settings)
        val phase=Any();val local=local(-10,0.2,phase)
        frame(scheduler,0.3)
        scheduler.appendOriginalLocalComments(listOf(local),phase)
        assertTrue(frame(scheduler,0.3).any {it.comment.id==-10})
        scheduler.observeOriginalLocalInjectionPhase(Any())
        assertTrue(frame(scheduler,0.3).none {it.comment.id==-10})
        scheduler.resetTimeline()
        assertTrue(frame(scheduler,0.3).none {it.comment.id==-10})
    }

    @Test fun `new settings accepted local keeps bypass while late old phase local becomes ordinary`() {
        val scheduler=scheduler(listOf(ordinary(0,0.0)))
        frame(scheduler,0.3)
        val oldPhase=Any();val newPhase=Any()
        val old=local(-10,0.2,oldPhase);val fresh=local(-9,0.2,newPhase)
        val changed=DanmakuSettings(mergeDuplicates=false,displayAreaRatio=1f,blockedKeywords=listOf("own"))
        assertEquals(listOf(old,fresh),scheduler.appendOriginalLocalComments(listOf(old,fresh),newPhase,
            changed,allowPhaseRetirement=true))
        val beforeQueuedSettings=frame(scheduler,0.3)
        assertEquals(setOf(0,-9),beforeQueuedSettings.map {it.comment.id}.toSet())
        scheduler.applySettings(changed) // Delayed actual settings update must be a no-op.
        assertEquals(beforeQueuedSettings,frame(scheduler,0.3))
    }

    @Test fun `retired phase or conflicting item identity rejects mutation`() {
        val scheduler=scheduler(listOf(ordinary(0,0.0)))
        val phase=Any();val local=local(-10,0.2,phase)
        frame(scheduler,0.3)
        scheduler.appendOriginalLocalComments(listOf(local),phase)
        val before=frame(scheduler,0.3)
        assertNull(scheduler.appendOriginalLocalComments(listOf(local(-9,0.2,Any())),Any()))
        assertNull(scheduler.appendOriginalLocalComments(listOf(local.copy(text="replaced")),phase))
        assertNull(scheduler.appendOriginalLocalComments(listOf(ordinary(-9,0.2)),phase))
        assertEquals(before,frame(scheduler,0.3))
    }
}
