package com.bilipai.desktop.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.feature.bangumi.BangumiDetailHeroBackdrop
import com.android.purebilibili.feature.bangumi.bangumiHeroCollapseFraction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopBangumiHeroCompositionTest {
    @Test fun disabledComponentMotionKeepsRealBannerContentAndScrollAcrossGlassModes() = runBlocking {
        for (glass in listOf(false, true)) for (density in listOf(1f, 2f)) {
            val state = LazyListState()
            var bounds = Rect.Zero
            val scene = ImageComposeScene((400*density).toInt(), (400*density).toInt(), Density(density)) {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = glass),
                        LocalComponentMotionEnabled provides false,
                    ) {
                        LazyColumn(state = state) {
                            item {
                                BangumiDetailHeroBackdrop(state, 200.dp, 24.dp, Modifier.fillMaxWidth().height(200.dp)) {
                                    Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() })
                                }
                            }
                            item { Spacer(Modifier.fillMaxWidth().height(800.dp)) }
                        }
                    }
                }
            }
            fun settle() { repeat(8) { Snapshot.sendApplyNotifications(); scene.render().close() } }
            try {
                settle()
                assertEquals(Rect(0f, 0f, 400*density, 200*density), bounds)
                state.scrollToItem(0, (40*density).toInt())
                settle()
                assertEquals(0, state.firstVisibleItemIndex)
                assertEquals((40*density).toInt(), state.firstVisibleItemScrollOffset)
                // boundsInRoot clips to the viewport: the visible bottom tracks the real list offset.
                assertEquals(160*density, bounds.bottom)
                assertEquals(400*density, bounds.width)
                assertEquals(0.2f, bangumiHeroCollapseFraction(0, (40*density).toInt(), 200*density))
                state.scrollToItem(1)
                settle()
                assertEquals(1f, bangumiHeroCollapseFraction(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, 200*density))
            } finally { scene.close() }
        }
    }
}
