package com.bilipai.desktop.danmaku

import com.android.purebilibili.feature.video.danmaku.DanmakuViewport
import com.android.purebilibili.feature.video.danmaku.resolveDesktopOriginalLiveDanmakuRenderConfig
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import java.awt.Font
import java.awt.geom.AffineTransform
import kotlin.test.*

class DesktopDanmakuPresentationTest {
    private val platform=object:DesktopOriginalDanmakuRenderPlatform {
        override fun resolveTypeface(fontWeight:Int)=Font("Dialog",Font.PLAIN,15)
        override fun systemChromeInsetPx()=0
        override fun maximumDisplayShortSidePx()=2160f
    }

    @Test fun `actual system density wins over legacy Section Ctrl zoom`() {
        for(zoom in listOf(1.25f,2f)) {
            val geometry=assertNotNull(DesktopDanmakuPaintGeometry.from(640,360,
                AffineTransform.getScaleInstance(1.5,1.5),2160f))
            val viewport=geometry.sectionViewport(DanmakuViewport(960,540,1.5f*zoom))
            assertEquals(1.5f,viewport.density)
            val config=DanmakuSettings().originalConfig(platform)
            assertEquals(22.5f,config.resolveRenderConfig(viewport,false).textSizePx)
            assertEquals(27f,config.resolveRenderConfig(viewport,true).textSizePx)
            assertEquals(2.25f,config.resolveRenderConfig(viewport,true).strokeWidthPx)
        }
    }

    @Test fun `monitor density changes once and stale Section dimensions are not adopted`() {
        val geometry=assertNotNull(DesktopDanmakuPaintGeometry.from(640,360,
            AffineTransform.getScaleInstance(2.0,2.0),2160f))
        val viewport=geometry.sectionViewport(DanmakuViewport(960,540,4f))
        assertEquals(DanmakuViewport(1280,720,2f),viewport)
        assertEquals(30f,DanmakuSettings().originalConfig(platform).resolveRenderConfig(viewport,false).textSizePx)
    }

    @Test fun `complete live constructor follows same original inline fullscreen and stroke rules`() {
        val settings=DanmakuSettings(fontScale=1.5f)
        val inline=resolveDesktopOriginalLiveDanmakuRenderConfig(settings,960,540,1f,1.5f,platform,false)
        val full=resolveDesktopOriginalLiveDanmakuRenderConfig(settings,960,540,1f,1.5f,platform,true)
        assertEquals(33.75f,inline.textSizePx)
        assertEquals(40.5f,full.textSizePx)
        assertEquals(2.25f,full.strokeWidthPx)
        assertEquals(0f,resolveDesktopOriginalLiveDanmakuRenderConfig(settings.copy(strokeEnabled=false),
            960,540,1f,1.5f,platform,true).strokeWidthPx)
    }

    @Test fun `actual full source presentation rejects old load and same version recovery`() {
        // No peer/native core is attached. This exercises the same production source guard used by Overlay.
        MpvPlayer().use { player ->
            val presentation=DesktopDanmakuSourcePresentation(player)
            val version=player.loadVersioned(PlaybackSource("file:///C:/danmaku-presentation-fixture.mp4"))
            val first=assertNotNull(player.currentSourceSnapshot())
            assertTrue(presentation.update(first,true));assertTrue(presentation.isFullscreen())
            assertTrue(presentation.update(first,false));assertFalse(presentation.isFullscreen()) // PiP/inline
            assertTrue(presentation.update(first,true))
            assertTrue(player.recoverSource(version,positionSeconds=2.0,paused=true))
            assertFalse(presentation.isFullscreen())
            assertFalse(presentation.update(first,true))
            val recovery=assertNotNull(player.currentSourceSnapshot())
            assertTrue(presentation.update(recovery,true));assertTrue(presentation.isFullscreen())
            player.loadVersioned(PlaybackSource("file:///C:/next-danmaku-presentation-fixture.mp4"))
            assertFalse(presentation.update(recovery,true));assertFalse(presentation.isFullscreen())
            player.stop();assertFalse(presentation.isFullscreen())
        }
    }
}
