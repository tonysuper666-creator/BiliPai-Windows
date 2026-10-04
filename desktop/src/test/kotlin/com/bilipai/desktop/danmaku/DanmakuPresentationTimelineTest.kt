package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig
import com.android.purebilibili.feature.video.danmaku.desktopOriginalLocalDanmakuItem
import java.awt.Font
import kotlin.test.*

class DanmakuPresentationTimelineTest {
    private val settings=DanmakuSettings(mergeDuplicates=false,displayAreaRatio=1f)
    private fun item(id:Int,time:Double,mode:Int=1)=DanmakuComment(id,time,mode,25,0xffffff,"item$id")
    private fun config(lines:Int=1)=DanmakuRenderConfig(typeface=Font("Dialog",Font.PLAIN,15),
        textSizePx=15f,lineHeightPx=24f,lineMarginPx=0f,lineCount=lines,
        topMarginPx=0f,bottomMarginPx=0f,scrollDurationMs=7000L,pinnedDurationMs=4000L)
    private fun frame(s:DanmakuScheduler,time:Double,c:DanmakuRenderConfig=config(),width:Int=640,height:Int=300,
        measured:MutableList<Int> = mutableListOf(),textWidth:Int=100,ascent:Double=15.0)=
        s.frame(time,width,height,c) {measured+=it.id;DesktopDanmakuTextMetrics(textWidth,ascent)}

    @Test fun `paused font fullscreen and opacity changes do not revive collision drops`() {
        val s=DanmakuScheduler(listOf(item(0,0.0),item(1,0.1)),settings,false)
        val measured=mutableListOf<Int>()
        val before=frame(s,0.3,measured=measured).single()
        assertEquals(listOf(0,1),measured)
        s.applySettings(settings.copy(fontScale=1.2f,fontWeight=7,opacity=0.5f))
        val resized=frame(s,0.3,config().copy(textSizePx=18f,lineHeightPx=28.8f),
            measured=measured,textWidth=120,ascent=18.0).single()
        assertSame(before.comment,resized.comment)
        assertEquals(640.0-760.0*0.3/7.0,resized.x,0.000001)
        assertEquals(18.0,resized.baseline)
        assertEquals(listOf(0,1,0),measured) // Only the admitted item was remeasured.
        frame(s,0.3,config().copy(textSizePx=18f,lineHeightPx=28.8f,alpha=90),
            measured=measured,textWidth=120,ascent=18.0)
        assertEquals(listOf(0,1,0),measured)
    }

    @Test fun `resize preserves age lifetime cursor and rejected arrivals`() {
        val s=DanmakuScheduler(listOf(item(0,0.0),item(1,0.1)),settings,false)
        val measured=mutableListOf<Int>()
        frame(s,0.5,measured=measured)
        val next=frame(s,0.5,config().copy(scrollDurationMs=14000L),width=1280,height=600,measured=measured).single()
        assertEquals(0,next.comment.id)
        assertEquals(1280.0-1380.0*0.5/7.0,next.x,0.000001) // Original admitted lifetime, not a new 14s item.
        assertEquals(listOf(0,1),measured)
        for(t in 1..7)frame(s,0.5+t*0.9,config().copy(scrollDurationMs=14000L),1280,600,measured)
        assertTrue(frame(s,7.1,config().copy(scrollDurationMs=14000L),1280,600,measured).isEmpty())
    }

    @Test fun `bottom pinned baseline follows new height while track and lifetime remain`() {
        val s=DanmakuScheduler(listOf(item(0,0.0,4)),settings,false)
        val before=frame(s,0.5,config(6)).single()
        val next=frame(s,0.5,config(6),width=800,height=500).single()
        assertSame(before.comment,next.comment)
        assertEquals(before.baseline+200.0,next.baseline)
        assertEquals(350.0,next.x)
    }

    @Test fun `hidden retained tracks remeasure but elapsed items never revive`() {
        val s=DanmakuScheduler(listOf(item(0,0.0),item(1,0.0),item(2,0.4)),settings,false)
        frame(s,0.0,config(2))
        assertTrue(frame(s,0.5,config(0).copy(textSizePx=18f,lineHeightPx=28.8f),textWidth=120).isEmpty())
        val restored=frame(s,0.5,config(2).copy(textSizePx=18f,lineHeightPx=28.8f),textWidth=120)
        assertEquals(listOf(0,1),restored.map {it.comment.id})
        for(t in 1..8)frame(s,0.5+t*0.9,config(0))
        assertTrue(frame(s,7.7,config(2)).isEmpty())
    }

    @Test fun `pending local append remains once across font remeasure`() {
        val s=DanmakuScheduler(listOf(item(0,0.0),item(1,0.3)),settings,false)
        frame(s,0.5,config(3))
        val phase=Any()
        val raw=desktopOriginalLocalDanmakuItem("own",0xffffff,1,25,100,false)
        val local=DanmakuComment(-10,raw.showAtTime/1000.0,1,25,0xffffff,"own",
            originalLocalItem=raw,originalLocalInjectionPhase=phase)
        assertNotNull(s.appendOriginalLocalComments(listOf(local),phase))
        s.applySettings(settings.copy(fontScale=1.2f))
        val next=frame(s,0.5,config(3).copy(textSizePx=18f,lineHeightPx=28.8f),textWidth=120)
        assertEquals(1,next.count {it.comment.id==-10})
        assertEquals(next,frame(s,0.5,config(3).copy(textSizePx=18f,lineHeightPx=28.8f),textWidth=120))
    }

    @Test fun `font change retains local bypass but a real rule phase change does not`() {
        val blocked=settings.copy(blockedKeywords=listOf("own"))
        val s=DanmakuScheduler(listOf(item(0,0.0)),blocked,false)
        frame(s,0.5,config(3))
        val phase=Any();val raw=desktopOriginalLocalDanmakuItem("own",0xffffff,1,25,100,false)
        val local=DanmakuComment(-10,raw.showAtTime/1000.0,1,25,0xffffff,"own",
            originalLocalItem=raw,originalLocalInjectionPhase=phase)
        s.appendOriginalLocalComments(listOf(local),phase)
        s.applySettings(blocked.copy(fontScale=1.2f));s.observeOriginalLocalInjectionPhase(phase)
        assertTrue(frame(s,0.5,config(3)).any {it.comment.id==-10})
        s.applySettings(blocked.copy(fontScale=1.2f,blockedKeywords=listOf("own","new")))
        assertFalse(frame(s,0.5,config(3)).any {it.comment.id==-10})
    }

    @Test fun `true backward seek and explicit reset can replay formerly consumed items`() {
        val s=DanmakuScheduler(listOf(item(0,0.0),item(1,0.1)),settings,false)
        frame(s,0.5)
        assertEquals(listOf(0),frame(s,0.5,config(2),width=800).map {it.comment.id})
        frame(s,0.0,config(2),width=800)
        assertEquals(listOf(0,1),frame(s,0.1,config(2),width=800).map {it.comment.id})
        s.resetTimeline()
        assertEquals(listOf(0,1),frame(s,0.5,config(2),width=800).map {it.comment.id})
    }
}
