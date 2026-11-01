package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.android.purebilibili.feature.video.interaction.InteractiveChoicePanelUiState
import com.android.purebilibili.feature.video.interaction.InteractiveChoiceUiModel
import com.android.purebilibili.feature.video.ui.components.InteractiveChoiceOverlay
import com.android.purebilibili.feature.video.ui.section.VideoSupplementStatsActions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopVideoSupplementInteractionCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
    private fun all(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
    private fun click(scene: ImageComposeScene, node: SemanticsNode) {
        var target = node
        while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = checkNotNull(target.parent)
        val point = target.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
        scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons())
        scene.render().close()
    }

    @Test fun supplementEntriesDispatchSeparateActionsAndDisappearWithTheirFlags() {
        val ai = mutableStateOf(true)
        val notes = mutableStateOf(true)
        val events = mutableListOf<String>()
        val scene = ImageComposeScene(320, 100, Density(1f)) {
            MaterialTheme { VideoSupplementStatsActions(ai.value, notes.value, { events += "ai" }, { events += "note" }) }
        }
        try {
            scene.render().close()
            for (label in listOf("AI 总结", "视频笔记")) {
                click(scene, all(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(label) })
            }
            assertEquals(listOf("ai", "note"), events)
            ai.value = false
            scene.render().close()
            assertTrue(all(scene).none { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("AI 总结") })
            notes.value = false
            scene.render().close()
            assertTrue(all(scene).none { it.config.getOrNull(SemanticsActions.OnClick) != null })
        } finally { scene.close() }
    }

    @Test fun interactiveChoicePreservesExactBranchIdentityAndIndependentDismiss() {
        val state = mutableStateOf(InteractiveChoicePanelUiState(visible = true,
            choices = listOf(InteractiveChoiceUiModel(42, 9001, "向左", isDefault = true))))
        val choices = mutableListOf<Pair<Long, Long>>()
        var dismisses = 0
        val scene = ImageComposeScene(640, 480, Density(1f)) {
            MaterialTheme { InteractiveChoiceOverlay(state.value, { edge, cid -> choices += edge to cid }, { dismisses++ }) }
        }
        fun text(value: String) = all(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.any { s -> s.text == value } == true }
        try {
            scene.render().close()
            click(scene, text("向左（默认）"))
            assertEquals(listOf(42L to 9001L), choices)
            assertEquals(0, dismisses)
            click(scene, text("关闭"))
            assertEquals(1, dismisses)
            assertEquals(1, choices.size)
            state.value = state.value.copy(visible = false)
            scene.render().close()
            assertTrue(all(scene).none { it.config.getOrNull(SemanticsActions.OnClick) != null })
        } finally { scene.close() }
    }
}
