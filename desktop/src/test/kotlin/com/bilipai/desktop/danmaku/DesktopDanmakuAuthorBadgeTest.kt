package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig
import com.android.purebilibili.feature.video.danmaku.DanmakuCloudRuleSyncPolicy
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import kotlin.test.*

/** CPU raster + same actual MPV admission, without attaching a native core or window. */
class DesktopDanmakuAuthorBadgeTest {
    @Test fun exactSourceAndTokenRejectSameVersionRecoveryAndStaleRelease() {
        MpvPlayer().use { player ->
            val version=player.loadVersioned(PlaybackSource("file:///C:/author-fixture.avi"))
            val first=assertNotNull(player.currentSourceSnapshot())
            val author=DesktopDanmakuAuthorPresentation(player)
            val a=Any(); assertTrue(author.bind(first,a,42) {true})
            assertEquals(DanmakuCloudRuleSyncPolicy.crc32Hex("42"),author.capture().userHash)
            val b=Any(); assertTrue(author.bind(first,b,43) {true})
            assertFalse(author.release(a)); assertEquals(DanmakuCloudRuleSyncPolicy.crc32Hex("43"),author.capture().userHash)
            val oldRevision=author.capture().measurementRevision
            assertTrue(player.recoverSource(version,positionSeconds=2.0,paused=true))
            assertNull(author.capture().userHash); assertNotEquals(oldRevision,author.capture().measurementRevision)
            assertFalse(author.bind(first,a,42) {true})
            assertTrue(author.bind(assertNotNull(player.currentSourceSnapshot()),a,42) {true})
            assertFalse(author.release(b)); assertTrue(author.release(a)); assertNull(author.capture().userHash)
        }
    }
    @Test fun metadataIsSourceOwnerNotViewerAndRetiredEntryDoesNotTag() {
        MpvPlayer().use { player ->
            player.loadVersioned(PlaybackSource("file:///C:/author-fixture.avi"))
            val source=assertNotNull(player.currentSourceSnapshot());val author=DesktopDanmakuAuthorPresentation(player)
            var owned=true;val token=Any()
            assertTrue(author.bind(source,token,0) {owned});assertNull(author.capture().userHash)
            assertTrue(author.bind(source,token,42) {owned})
            assertTrue(author.capture().matches(DanmakuCloudRuleSyncPolicy.crc32Hex("42")))
            assertFalse(author.capture().matches(DanmakuCloudRuleSyncPolicy.crc32Hex("43")))
            owned=false;assertNull(author.capture().userHash);assertFalse(author.bind(source,Any(),43) {owned})
        }
    }
    @Test fun originalBadgeReservesPaintedWidthAndDrawsPinkWhiteCpuPixels() {
        val image=BufferedImage(300,100,BufferedImage.TYPE_INT_ARGB);val g=image.createGraphics()
        try {
            val badge=DesktopOriginalUpDanmakuBadge(g);val font=Font("Dialog",Font.PLAIN,40)
            val plain=badge.measure("plain",font,false);val tagged=badge.measure("plain",font,true)
            assertEquals(plain.pureWidth,tagged.pureWidth);assertTrue(tagged.width>plain.width)
            assertTrue(tagged.width>=tagged.pureWidth+tagged.badgeAdvance)
            badge.paint(20.0,60.0,tagged)
            val colors=(0 until image.height).flatMap {y->(0 until image.width).map {x->image.getRGB(x,y)}}
            assertTrue(colors.count {it==0xfffb7299.toInt()}>30)
            assertTrue(colors.any {it==Color.WHITE.rgb})
            assertTrue((tagged.width until image.width).all {x->image.getRGB(x,50)==0})
        } finally {g.dispose()}
    }
    @Test fun physicalSizeScalesAndInheritedOpacityIsAppliedOnce() {
        val image=BufferedImage(300,100,BufferedImage.TYPE_INT_ARGB);val g=image.createGraphics()
        try {
            g.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.5f)
            val badge=DesktopOriginalUpDanmakuBadge(g)
            val small=badge.measure("text",Font("Dialog",Font.PLAIN,20),true)
            val large=badge.measure("text",Font("Dialog",Font.PLAIN,40),true)
            assertTrue(large.badgeAdvance>small.badgeAdvance*1.8f)
            badge.paint(20.0,60.0,large)
            val pink=(0 until image.height).flatMap {y->(0 until image.width).map {x->image.getRGB(x,y)}}
                .filter {kotlin.math.abs(((it ushr 16) and 255)-251)<=2 &&
                    kotlin.math.abs(((it ushr 8) and 255)-114)<=2 && kotlin.math.abs((it and 255)-153)<=2}
            assertTrue(pink.size>30);assertTrue(pink.all {(it ushr 24) in 127..128})
            val empty=badge.measure("",Font("Dialog",Font.PLAIN,20),true)
            assertEquals(0f,empty.badgeAdvance)
        } finally {g.dispose()}
    }
    private val config=DanmakuRenderConfig(typeface=Font("Dialog",Font.PLAIN,15),textSizePx=15f,lineHeightPx=24f,lineCount=1,topMarginPx=0f,
        bottomMarginPx=0f,scrollDurationMs=7000,pinnedDurationMs=4000)
    private val settings=DanmakuSettings(mergeDuplicates=false,displayAreaRatio=1f)
    private fun item(id:Int,time:Double,mode:Int=1)=DanmakuComment(id,time,mode,25,0xffffff,"item$id")
    @Test fun lateAuthorMeasurementRetainsCursorCollisionDropAndAdmittedLifetime() {
        val s=DanmakuScheduler(listOf(item(0,0.0),item(1,0.1)),settings,false)
        val measured=mutableListOf<Int>()
        val initial=s.frame(0.3,640,300,config,0L) {measured+=it.id;DesktopDanmakuTextMetrics(100,15.0)}.single()
        val changed=s.frame(0.3,640,300,config,1L) {measured+=it.id;DesktopDanmakuTextMetrics(125,15.0)}.single()
        assertSame(initial.comment,changed.comment);assertEquals(listOf(0,1,0),measured)
        assertEquals(640.0-765.0*0.3/7.0,changed.x,0.000001)
        assertEquals(125,changed.textWidth)
        for(t in 1..7)s.frame(0.3+t*0.9,640,300,config,1L) {DesktopDanmakuTextMetrics(125,15.0)}
        assertTrue(s.frame(7.1,640,300,config,1L) {DesktopDanmakuTextMetrics(125,15.0)}.isEmpty())
    }
    @Test fun newBadgeExtentParticipatesInOrdinaryCollisionAdmission() {
        val source=listOf(item(0,0.0),item(1,1.0))
        fun result(width:Int):List<Int> {
            val s=DanmakuScheduler(source,settings,false)
            s.frame(0.0,640,300,config) {DesktopDanmakuTextMetrics(width,15.0)}
            return s.frame(1.0,640,300,config) {DesktopDanmakuTextMetrics(width,15.0)}.map {it.comment.id}
        }
        assertEquals(listOf(0,1),result(50));assertEquals(listOf(0),result(110))
    }
    @Test fun pinnedAndReversePlacementUseTotalExtentAfterRevision() {
        for(mode in listOf(5,6)) {
            val s=DanmakuScheduler(listOf(item(0,0.0,mode)),settings,false)
            // The original engine reserves no pinned tracks at four lines or fewer.
            s.frame(0.3,640,300,config.copy(lineCount=6),0L) {DesktopDanmakuTextMetrics(100,15.0)}
            val item=s.frame(0.3,640,300,config.copy(lineCount=6),1L) {DesktopDanmakuTextMetrics(125,15.0)}.single()
            val expected=if(mode==5)(640.0-125)/2 else -125.0+765.0*0.3/7.0
            assertEquals(expected,item.x,0.000001)
        }
    }
}
