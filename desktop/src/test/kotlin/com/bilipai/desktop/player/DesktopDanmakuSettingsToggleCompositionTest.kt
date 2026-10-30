package com.bilipai.desktop.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.*
import com.android.purebilibili.feature.video.ui.components.DanmakuSettingsToggle
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopDanmakuSettingsToggleCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)

    @Test fun rowAndSwitchEachDispatchOnceWithCurrentStateAtNarrowAndWideWidths() {
        for (width in listOf(240, 800)) {
            val checked = mutableStateOf(false)
            val version = mutableIntStateOf(1)
            val calls = mutableListOf<Pair<Int, Boolean>>()
            val scene = ImageComposeScene(width, 120, Density(1f)) {
                MaterialTheme {
                    val currentVersion = version.intValue
                    Box(Modifier.width(width.dp)) {
                        DanmakuSettingsToggle("屏蔽滚动弹幕", checked.value, {
                            calls += currentVersion to it
                            checked.value = it
                        })
                    }
                }
            }
            fun settle() { repeat(4) { Snapshot.sendApplyNotifications(); scene.render().close() } }
            fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun click(n: SemanticsNode) {
                scene.sendPointerEvent(PointerEventType.Press, n.boundsInRoot.center, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, n.boundsInRoot.center, buttons = PointerButtons())
                settle()
            }
            try {
                settle()
                assertTrue(calls.isEmpty())
                val label = all().single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "屏蔽滚动弹幕" } == true }
                var row = label
                while (!row.config.contains(SemanticsActions.OnClick)) row = checkNotNull(row.parent)
                assertEquals(width.toFloat(), row.boundsInRoot.width)
                assertTrue(row.boundsInRoot.height >= 48f)
                click(label)
                assertEquals(listOf(1 to true), calls)
                val toggle = all().single { it.config.contains(SemanticsProperties.ToggleableState) }
                assertEquals(ToggleableState.On, toggle.config[SemanticsProperties.ToggleableState])
                version.intValue = 2
                settle()
                click(all().single { it.config.contains(SemanticsProperties.ToggleableState) })
                assertEquals(listOf(1 to true, 2 to false), calls)
                assertEquals(ToggleableState.Off, all().single { it.config.contains(SemanticsProperties.ToggleableState) }.config[SemanticsProperties.ToggleableState])
            } finally { scene.close() }
        }
    }
}
