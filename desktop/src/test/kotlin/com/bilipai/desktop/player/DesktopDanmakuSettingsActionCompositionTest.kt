package com.bilipai.desktop.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.android.purebilibili.feature.video.ui.components.DanmakuSettingsAction
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopDanmakuSettingsActionCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
    @Test fun fullWidthActionUsesLatestCallbackExactlyOnce() {
        for (width in listOf(240, 800)) {
            val version = mutableIntStateOf(1)
            val calls = mutableListOf<Int>()
            val scene = ImageComposeScene(width, 100, Density(1f)) {
                MaterialTheme {
                    val current = version.intValue
                    Box(Modifier.width(width.dp)) {
                        DanmakuSettingsAction("查看弹幕列表", { calls += current })
                    }
                }
            }
            fun settle() { repeat(4) { Snapshot.sendApplyNotifications(); scene.render().close() } }
            fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun button() = all().single { it.config.contains(SemanticsActions.OnClick) }
            fun click() {
                val point = button().boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons())
                settle()
            }
            try {
                settle(); assertTrue(calls.isEmpty())
                assertEquals(width.toFloat(), button().boundsInRoot.width)
                assertTrue(button().boundsInRoot.height >= 44f)
                assertTrue(all().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "查看弹幕列表" } == true })
                click(); assertEquals(listOf(1), calls)
                version.intValue = 2; settle()
                click(); assertEquals(listOf(1, 2), calls)
            } finally { scene.close() }
        }
    }
}
