package com.bilipai.desktop.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.video.screen.videoContentAdditionalTopPadding
import com.android.purebilibili.feature.video.screen.videoContentTopPadding
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class DesktopDeferredVideoLayoutCompositionTest {
    private fun render(scene: ImageComposeScene) {
        Snapshot.sendApplyNotifications()
        repeat(2) { scene.render().close() }
    }

    @Test fun existingTopPaddingMovesTheMeasuredChildWithoutRecomposingThePage() {
        val top = mutableFloatStateOf(12f)
        var compositions = 0
        var y = -1f
        val scene = ImageComposeScene(200, 200, Density(1f)) {
            SideEffect { compositions++ }
            Box(Modifier.videoContentTopPadding { top.floatValue }) {
                Box(Modifier.size(20.dp).onGloballyPositioned { y = it.positionInRoot().y })
            }
        }
        try {
            render(scene)
            assertEquals(12f, y)
            val initialCompositions = compositions
            top.floatValue = 48f
            render(scene)
            assertEquals(48f, y)
            assertEquals(initialCompositions, compositions)
            top.floatValue = -20f
            render(scene)
            assertEquals(0f, y)
            assertEquals(initialCompositions, compositions)
        } finally { scene.close() }
    }

    @Test fun newPaddingProviderIsReadDuringLazyListMeasurementWithRealDensity() {
        val top = mutableFloatStateOf(40f)
        var compositions = 0
        var y = -1f
        val density = Density(2f)
        val scene = ImageComposeScene(400, 400, density) {
            SideEffect { compositions++ }
            val padding = remember { videoContentAdditionalTopPadding(PaddingValues(top = 3.dp, bottom = 7.dp), { top.floatValue }, density) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item { Box(Modifier.fillMaxWidth().height(20.dp).onGloballyPositioned { y = it.positionInRoot().y }) }
            }
        }
        try {
            render(scene)
            assertEquals(46f, y)
            val initialCompositions = compositions
            top.floatValue = 80f
            render(scene)
            assertEquals(86f, y)
            assertEquals(initialCompositions, compositions)
            top.floatValue = -10f
            render(scene)
            assertEquals(6f, y)
            assertEquals(initialCompositions, compositions)
        } finally { scene.close() }
    }
}
