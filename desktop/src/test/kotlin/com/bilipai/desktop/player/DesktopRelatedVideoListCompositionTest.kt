package com.bilipai.desktop.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.HomeFeedCardStyle
import com.android.purebilibili.feature.home.resolveHomeFeedCardLayout
import com.android.purebilibili.feature.video.ui.components.RelatedVideoList
import com.android.purebilibili.feature.video.ui.components.RelatedVideoCardLayout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class DesktopRelatedVideoListCompositionTest {
    @Test fun actualPaneAndContentPaddingDetermineEveryRowsPresentationAcrossResize() {
        for (scale in listOf(1f, 2f)) {
            val layout = resolveHomeFeedCardLayout(HomeFeedCardStyle.CURRENT).copy(outerPaddingDp = 12)
            val width = mutableStateOf(373.dp)
            val padding = mutableStateOf(15.dp)
            var presentation: RelatedVideoCardLayout? = null
            var rowWidth = 0
            var rowX = -1f
            val scene = ImageComposeScene((800*scale).toInt(), (400*scale).toInt(), Density(scale)) {
                RelatedVideoList(layout, rememberLazyListState(), Modifier.width(width.value).height(200.dp),
                    PaddingValues(horizontal = padding.value)) { resolved ->
                    item {
                        SideEffect { presentation = resolved }
                        Box(Modifier.fillMaxWidth().height(30.dp).onGloballyPositioned {
                            rowWidth = it.size.width; rowX = it.positionInRoot().x
                        })
                    }
                }
            }
            fun render() { Snapshot.sendApplyNotifications(); repeat(4) { scene.render().close() } }
            try {
                render() // 373 - 30 - 24 = 319 dp: stacked.
                assertEquals(RelatedVideoCardLayout.STACKED, presentation)
                assertEquals((343*scale).toInt(), rowWidth); assertEquals(15*scale, rowX)
                width.value = 374.dp; render() // Exact 320 dp boundary.
                assertEquals(RelatedVideoCardLayout.HORIZONTAL, presentation)
                assertEquals((344*scale).toInt(), rowWidth)
                padding.value = 16.dp; render() // Padding changes alone cross back to 318 dp.
                assertEquals(RelatedVideoCardLayout.STACKED, presentation)
                assertEquals((342*scale).toInt(), rowWidth); assertEquals(16*scale, rowX)
                width.value = 800.dp; render()
                assertEquals(RelatedVideoCardLayout.HORIZONTAL, presentation)
                assertEquals((768*scale).toInt(), rowWidth)
            } finally { scene.close() }
        }
    }
}
