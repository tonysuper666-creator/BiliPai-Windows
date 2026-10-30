package com.bilipai.desktop.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.normalizeDanmakuFontScale
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuPinnedDurationMillis
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuLayerLineHeightPx
import com.android.purebilibili.feature.video.ui.components.DanmakuSettingsSlider
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopDanmakuRangeSliderTest {
    private fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
    @Test fun pointerDraggingCommitsExactCurrentUpstreamEndpoints() {
        val ranges=listOf(
            normalizeDanmakuFontScale(0f)..normalizeDanmakuFontScale(Float.MAX_VALUE),
            (resolveDanmakuPinnedDurationMillis(0f)/1000f)..(resolveDanmakuPinnedDurationMillis(Float.MAX_VALUE)/1000f),
            resolveDanmakuLayerLineHeightPx(1f,0f)..resolveDanmakuLayerLineHeightPx(1f,Float.MAX_VALUE))
        for(range in ranges) {
            val commits=mutableListOf<Float>()
            val scene=ImageComposeScene(400,160,Density(1f)) {
                MaterialTheme { Box(Modifier.width(400.dp)) {
                    DanmakuSettingsSlider("范围",{it.toString()},(range.start+range.endInclusive)/2,
                        range,0,null,{}, {commits+=it},commitOnDispose=true)
                } }
            }
            try {
                fun render() {repeat(3){scene.render().close()}}
                render()
                val slider=scene.semanticsOwners.flatMap {nodes(it.unmergedRootSemanticsNode)}
                    .single {it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)!=null}
                val b=slider.boundsInRoot
                assertTrue(commits.isEmpty())
                for((point,expected) in listOf(Offset(b.right+40,b.center.y) to range.endInclusive,Offset(b.left-40,b.center.y) to range.start)) {
                    scene.sendPointerEvent(PointerEventType.Press,b.center,buttons=PointerButtons(isPrimaryPressed=true));render()
                    scene.sendPointerEvent(PointerEventType.Move,point,buttons=PointerButtons(isPrimaryPressed=true));render()
                    val before=commits.size
                    scene.sendPointerEvent(PointerEventType.Release,point,buttons=PointerButtons());render()
                    assertEquals(before+1,commits.size)
                    assertEquals(expected,commits.last())
                }
            } finally {scene.close()}
        }
    }
}
