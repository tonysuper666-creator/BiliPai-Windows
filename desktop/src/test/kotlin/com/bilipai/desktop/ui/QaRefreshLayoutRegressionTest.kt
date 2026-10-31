@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AdaptivePullToRefreshBox
import com.android.purebilibili.core.ui.components.AppLazyVerticalGrid
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Production Category grid structure: AppLazyVerticalGrid with explicit 8dp vertical padding, independent rows. */
class QaRefreshLayoutRegressionTest {
    @Test fun lastRowActionMustScrollClearOfRefreshButtonWithCategoryGridPadding() = runBlocking {
        lateinit var grid: LazyGridState
        var actionBounds = Rect.Zero
        val scene = ImageComposeScene(520, 420, Density(1f)) {
            val actualWindow = LocalWindowInfo.current
            val focused = object : WindowInfo by actualWindow { override val isWindowFocused = true }
            CompositionLocalProvider(LocalWindowInfo provides focused) {
                MaterialTheme {
                    grid = rememberLazyGridState()
                    AdaptivePullToRefreshBox(isRefreshing = false, onRefresh = {}, modifier = Modifier.fillMaxSize()) {
                        AppLazyVerticalGrid(columns = GridCells.Fixed(1), state = grid,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                            items(120) { index ->
                                Box(Modifier.fillMaxWidth().height(80.dp)) {
                                    Text("row $index")
                                    if (index == 119) Box(Modifier.align(Alignment.BottomEnd).size(44.dp)
                                        .onGloballyPositioned { actionBounds = it.boundsInRoot() }) { Text("menu") }
                                }
                            }
                        }
                    }
                }
            }
        }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun buttonBounds(): Rect? = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("刷新") == true
                && it.config.getOrNull(SemanticsProperties.Role) != null }?.boundsInRoot
        suspend fun await(condition: () -> Boolean) = withTimeout(5000) {
            while (true) {
                scene.render(System.nanoTime()).close()
                if (condition()) break
                delay(12)
            }
        }
        try {
            await { buttonBounds() != null }
            grid.scrollToItem(119)
            await { actionBounds.width > 0f }
            val button = buttonBounds()!!
            assertFalse(actionBounds.overlaps(button), "last card menu=$actionBounds overlaps refresh=$button at scroll end")
        } finally { scene.close() }
    }
}
