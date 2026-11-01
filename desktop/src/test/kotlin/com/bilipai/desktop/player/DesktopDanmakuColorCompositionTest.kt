package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.android.purebilibili.feature.video.ui.components.DanmakuColorOptions
import com.android.purebilibili.feature.video.ui.components.danmakuSendColorOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopDanmakuColorCompositionTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun all(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
    private fun click(scene: ImageComposeScene, node: SemanticsNode) {
        val point = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
        scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons())
        scene.render().close()
    }

    @Test fun everyPresetHasAccessibleIdentityAndDispatchesItsExactColor() {
        val selected = mutableStateOf(0xFFFFFF)
        val events = mutableListOf<Int>()
        var custom = 0
        val scene = ImageComposeScene(1000, 80, Density(1f)) {
            MaterialTheme {
                DanmakuColorOptions(selected.value, { events += it; selected.value = it }, { custom++ })
            }
        }
        try {
            scene.render().close()
            assertTrue(events.isEmpty())
            for (option in danmakuSendColorOptions()) {
                val node = all(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(option.label) }
                assertEquals(Role.RadioButton, node.config.getOrNull(SemanticsProperties.Role))
                click(scene, node)
                assertEquals(option.value, events.last())
                val selectedNodes = all(scene).filter {
                    it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                        it.config.getOrNull(SemanticsProperties.Selected) == true
                }
                assertEquals(listOf(listOf(option.label)), selectedNodes.map { it.config.getOrNull(SemanticsProperties.ContentDescription) })
            }
            assertEquals(danmakuSendColorOptions().map { it.value }, events)
            assertEquals(0, custom)
        } finally { scene.close() }
    }

    @Test fun customColorKeepsPresetsUnselectedAndOpensOnlyTheCustomAction() {
        val events = mutableListOf<Int>()
        var custom = 0
        val scene = ImageComposeScene(1000, 80, Density(1f)) {
            MaterialTheme { DanmakuColorOptions(0x123456, { events += it }, { custom++ }) }
        }
        try {
            scene.render().close()
            assertTrue(all(scene).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
                .all { it.config.getOrNull(SemanticsProperties.Selected) == false })
            var node = all(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "自定义" } == true }
            while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = checkNotNull(node.parent)
            assertEquals(true, node.config.getOrNull(SemanticsProperties.Selected))
            click(scene, node)
            assertEquals(1, custom)
            assertTrue(events.isEmpty())
        } finally { scene.close() }
    }
}
