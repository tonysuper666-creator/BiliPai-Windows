package com.bilipai.desktop.player

import com.bilipai.desktop.danmaku.*
import com.bilipai.desktop.danmaku.DanmakuSettings
import com.bilipai.desktop.ui.projectOriginalDanmakuRendererSettings
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopOriginalDanmakuPreferences
import com.bilipai.desktop.settings.DesktopDanmakuBlockPreferences
import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.video.danmaku.DanmakuViewport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.awt.Font
import java.nio.file.Files
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopMigratedDanmakuRangeTest {
    private val platform = object : DesktopOriginalDanmakuRenderPlatform {
        override fun resolveTypeface(fontWeight: Int) = Font("Dialog", Font.PLAIN, 15)
        override fun systemChromeInsetPx() = 0
        override fun maximumDisplayShortSidePx() = 1080f
    }

    @Test fun selectedRangeEndpointsReachActualSchedulerAndSeekLookback() {
        for (seconds in listOf(1f, 50f)) for (mode in listOf(1, 5)) {
            val settings = DanmakuSettings(fontScale=2.5f, lineHeight=3f, displayAreaRatio=1f,
                scrollDurationSeconds=seconds, staticDurationSeconds=seconds, mergeDuplicates=false).normalized()
            val config = settings.originalConfig(platform).resolveRenderConfig(DanmakuViewport(1080,1080,1f),false)
            assertEquals(37.5f, config.textSizePx)
            assertEquals(112.5f, config.lineHeightPx)
            assertEquals((seconds*1000).toLong(),config.scrollDurationMs)
            assertEquals((seconds*1000).toLong(),config.pinnedDurationMs)
            val scheduler = DanmakuScheduler(listOf(DanmakuComment(1,0.0,mode,25,0xffffff,"range")),settings,false)
            fun frame(t:Double) = scheduler.frame(t,1080,1080,config) { DesktopDanmakuTextMetrics(100,30.0) }
            assertEquals(1,frame(0.0).size)
            assertEquals(1,frame(seconds-0.01).size)
            assertTrue(frame(seconds.toDouble()).isEmpty())
            // Rewind must search the entire admitted lifetime, including the new 50-second range.
            assertEquals(1,frame(seconds/2.0).size)
        }
        val normalized = DanmakuSettings(displayAreaRatio=0.1f).normalized()
        assertEquals(0.1f,normalized.displayAreaRatio)
    }

    @Test fun portraitUsesCurrentLocalBoundsWhileCloudKeepsItsDistinctServerContract() {
        assertEquals(2.5f,com.android.purebilibili.feature.video.ui.pager.resolvePortraitDanmakuReadableFontScale(2.5f))
        assertEquals(1.18f,com.android.purebilibili.feature.video.ui.pager.resolvePortraitDanmakuReadableFontScale(1f))
        assertEquals(0.5f,com.android.purebilibili.feature.video.ui.pager.resolvePortraitDanmakuReadableFontScale(0.1f))
        assertEquals(1.6f,com.android.purebilibili.data.repository.mapDanmakuFontScaleToCloudFontSize(2.5f))
        assertEquals(0.51f,com.android.purebilibili.data.repository.mapDanmakuFontScaleToCloudFontSize(0.5f))
    }

    @Test fun readingAndProjectingLegacyDiskValuesDoesNotRewriteThem(): Unit = runBlocking {
        val dir = Files.createTempDirectory("danmaku-legacy-ranges")
        try {
            val key = "danmaku_${DanmakuSettingsScope.PORTRAIT.keyPrefix}_font_scale"
            val store = DesktopPluginStore(dir)
            store.update("settings",mapOf(key to JsonPrimitive(0.3f),"danmaku_defaults_version" to JsonPrimitive(5)))
            val file=dir.resolve("plugin-settings.json")
            val before=Files.readAllBytes(file)
            val reloaded=DesktopPluginStore(dir)
            val admit:((()->Unit)->Boolean)={ it();true }
            val preferences=DesktopOriginalDanmakuPreferences(reloaded,DesktopDanmakuBlockPreferences(reloaded,admit),admit)
            val effective=projectOriginalDanmakuRendererSettings(DanmakuSettings(),preferences.currentSettings(DanmakuSettingsScope.PORTRAIT))
            assertEquals(0.5f,effective.fontScale)
            assertEquals(7.5f,effective.originalConfig(platform).resolveRenderConfig(DanmakuViewport(1080,1080,1f),false).textSizePx)
            assertContentEquals(before,Files.readAllBytes(file))
            assertEquals(JsonPrimitive(0.3f),reloaded.snapshot("settings").value.json[key])
        } finally { dir.toFile().deleteRecursively() }
    }
}
