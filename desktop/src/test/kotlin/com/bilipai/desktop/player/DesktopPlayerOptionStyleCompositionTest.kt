package com.android.purebilibili.feature.video.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopPlayerOptionStyleCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
    @Test fun actualPreviewKeepsCardGeometryAndSelectionAcrossThemeBranches() {
        val method = Class.forName("com.android.purebilibili.feature.video.ui.components.PagesSelectorKt")
            .declaredMethods.single { it.name == "PagesHorizontalPreviewRow" }.apply { isAccessible = true }
        for (style in listOf(AppUiStyle.MATERIAL3, AppUiStyle.MIUIX)) for (glass in listOf(false, true)) {
            val calls = mutableListOf<Int>()
            var selected = Color.Unspecified
            var unselected = Color.Unspecified
            val fallback = Color.Magenta
            val scene = ImageComposeScene(400, 70, Density(1f)) {
                MaterialTheme {
                    CompositionLocalProvider(LocalAppUiStyle provides style,
                        LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = glass)) {
                        selected = playerOptionContainerColor(true, fallback)
                        unselected = playerOptionContainerColor(false, fallback)
                        method.invoke(null, listOf("第一集", "第二集"), 0, false,
                            ({ index: Int -> calls += index } as (Int) -> Unit), Modifier, currentComposer, 0, 0)
                    }
                }
            }
            fun settle() { repeat(8) { Snapshot.sendApplyNotifications(); scene.render().close() } }
            try {
                settle()
                assertEquals(fallback, unselected)
                if (style == AppUiStyle.MIUIX && !glass) assertNotEquals(fallback, selected)
                else assertEquals(fallback, selected)
                assertTrue(calls.isEmpty())
                val labels = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                val second = labels.single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "第二集" } == true }
                var card = second
                while (!card.config.contains(SemanticsActions.OnClick)) card = checkNotNull(card.parent)
                assertEquals(150f, card.boundsInRoot.width)
                assertEquals(35f, card.boundsInRoot.height)
                val point = second.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons())
                settle()
                assertEquals(listOf(1), calls)
            } finally { scene.close() }
        }
    }
}
