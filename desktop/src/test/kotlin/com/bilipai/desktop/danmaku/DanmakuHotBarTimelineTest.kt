package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuHotBarReservedHeightPx
import java.awt.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DanmakuHotBarTimelineTest {
    private fun comment(id:Int,time:Double)=DanmakuComment(id,time,1,25,0xffffff,"comment$id")
    private fun config(lines:Int=6,top:Float=0f)=DanmakuRenderConfig(
        typeface=Font("Dialog",Font.PLAIN,20),lineHeightPx=32f,lineMarginPx=0f,
        lineCount=lines,topMarginPx=top,bottomMarginPx=0f,scrollDurationMs=7000L,
    )
    private fun scheduler(comments:List<DanmakuComment>)=
        DanmakuScheduler(comments,DanmakuSettings(mergeDuplicates=false,displayAreaRatio=1f),liveAdmission=false)
    private fun frame(scheduler:DanmakuScheduler,time:Double,config:DanmakuRenderConfig,
                      measure:(DanmakuComment)->DesktopDanmakuTextMetrics={DesktopDanmakuTextMetrics(100,20.0)})=
        scheduler.frame(time,640,300,config,measure)

    @Test fun `paused bar appearance and disappearance preserve age x width and consumed items`() {
        val scheduler=scheduler(listOf(comment(0,0.0),comment(1,0.1)))
        val measured=mutableListOf<Int>()
        val measure:(DanmakuComment)->DesktopDanmakuTextMetrics={measured+=it.id;DesktopDanmakuTextMetrics(100,20.0)}
        frame(scheduler,0.0,config(1),measure)
        val before=frame(scheduler,0.1,config(1),measure).single()
        assertEquals(listOf(0,1),measured) // The second arrival was actually collision-rejected.
        val reserved=frame(scheduler,0.1,config(1,64f),measure).single()
        assertEquals(before.x,reserved.x,0.0)
        assertEquals(before.textWidth,reserved.textWidth)
        assertEquals(before.baseline+64.0,reserved.baseline,0.0)
        val restored=frame(scheduler,0.1,config(1),measure).single()
        assertEquals(before,restored)
        assertEquals(listOf(0,1),measured) // No cursor rebuild/re-measure/replay at either transition.
    }

    @Test fun `playing bar transition keeps the media time trajectory`() {
        val scheduler=scheduler(listOf(comment(0,0.0)))
        frame(scheduler,0.0,config())
        val reserved=frame(scheduler,0.5,config(3,64f)).single()
        assertEquals(640.0-740.0*0.5/7.0,reserved.x,0.000001)
        val restored=frame(scheduler,0.75,config()).single()
        assertEquals(640.0-740.0*0.75/7.0,restored.x,0.000001)
        assertEquals(20.0,restored.baseline,0.0)
    }

    @Test fun `zero tracks retain admitted life and consume new arrivals without replay on restore`() {
        val scheduler=scheduler(listOf(comment(0,0.0),comment(1,0.3)))
        val measured=mutableListOf<Int>()
        val measure:(DanmakuComment)->DesktopDanmakuTextMetrics={measured+=it.id;DesktopDanmakuTextMetrics(100,20.0)}
        frame(scheduler,0.0,config(),measure)
        assertTrue(frame(scheduler,0.2,config(0,300f),measure).isEmpty())
        assertTrue(frame(scheduler,0.4,config(0,300f),measure).isEmpty())
        val restored=frame(scheduler,0.5,config(),measure)
        assertEquals(listOf(0),restored.map{it.comment.id})
        assertEquals(listOf(0),measured)
        assertEquals(640.0-740.0*0.5/7.0,restored.single().x,0.000001)
    }

    @Test fun `hidden items expire on media time and cannot be revived by restoring space`() {
        val scheduler=scheduler(listOf(comment(0,0.0)))
        frame(scheduler,0.0,config())
        for(tick in 1..8)assertTrue(frame(scheduler,tick*0.9,config(0,300f)).isEmpty())
        assertTrue(frame(scheduler,7.2,config()).isEmpty())
    }

    @Test fun `reduced row budget hides but does not destroy surviving tracks`() {
        val scheduler=scheduler(listOf(comment(0,0.0),comment(1,0.0)))
        var measurements=0
        val measure:(DanmakuComment)->DesktopDanmakuTextMetrics={measurements++;DesktopDanmakuTextMetrics(100,20.0)}
        val before=frame(scheduler,0.5,config(2),measure)
        assertEquals(listOf(0,1),before.map{it.comment.id})
        val smaller=frame(scheduler,0.5,config(1,64f),measure)
        assertEquals(listOf(0),smaller.map{it.comment.id})
        assertEquals(before.first().x,smaller.single().x,0.0)
        val restored=frame(scheduler,0.5,config(2),measure)
        assertEquals(before,restored)
        assertEquals(2,measurements)
    }

    @Test fun `actual seek still rebuilds the destination and can replay previously dropped arrivals`() {
        val scheduler=scheduler(listOf(comment(0,0.0),comment(1,0.3)))
        frame(scheduler,0.0,config())
        frame(scheduler,0.4,config(0,300f))
        assertEquals(listOf(0),frame(scheduler,0.5,config()).map{it.comment.id})
        frame(scheduler,0.1,config()) // Backward seek is the real timeline-reset boundary.
        assertEquals(listOf(0,1),frame(scheduler,0.3,config()).map{it.comment.id})
    }

    @Test fun `render geometry remeasures admitted items without replaying consumed arrivals`() {
        val scheduler=scheduler(listOf(comment(0,0.0)))
        var measurements=0
        val measure:(DanmakuComment)->DesktopDanmakuTextMetrics={measurements++;DesktopDanmakuTextMetrics(100,20.0)}
        frame(scheduler,0.5,config(),measure)
        frame(scheduler,0.5,config().copy(lineHeightPx=40f),measure)
        assertEquals(2,measurements)
    }

    @Test fun `original massive policy has zero reservation and normal reservation is viewport bounded`() {
        for(height in listOf(0f,64f,128f,1000f))
            assertEquals(0f,resolveDanmakuHotBarReservedHeightPx(height,300,true),0f)
        assertEquals(64f,resolveDanmakuHotBarReservedHeightPx(64f,300,false),0f)
        assertEquals(32f,resolveDanmakuHotBarReservedHeightPx(64f,32,false),0f)
        assertEquals(0f,resolveDanmakuHotBarReservedHeightPx(Float.NaN,300,false),0f)
    }
}
