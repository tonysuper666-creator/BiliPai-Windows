package com.android.purebilibili.feature.video.ui.overlay

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopLandscapeDanmakuInputCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)

    @Test fun inputKeepsLoginLabelTouchBoundsAndLatestCallback() {
        for (width in listOf(160, 800)) {
            val loggedIn = mutableStateOf(false)
            val calls = mutableListOf<Boolean>()
            val scene = ImageComposeScene(width, 100, Density(1f)) {
                MaterialTheme {
                    val current = loggedIn.value
                    Box(Modifier.fillMaxSize()) {
                        LandscapeDanmakuInput(
                            onClick = { calls += current },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            placeholder = if (current) "发弹幕" else "登录发弹幕",
                        )
                    }
                }
            }
            fun settle() { repeat(6) { Snapshot.sendApplyNotifications(); scene.render().close() } }
            fun button(): SemanticsNode = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .single { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
            fun label(value: String) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
            fun click(point: Offset) {
                scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons())
                settle()
            }
            try {
                settle()
                assertTrue(calls.isEmpty())
                assertEquals(width.toFloat(), button().boundsInRoot.width)
                assertEquals(48f, button().boundsInRoot.height)
                assertTrue(label("登录发弹幕").boundsInRoot.right <= width)
                click(button().boundsInRoot.center)
                assertEquals(listOf(false), calls)
                loggedIn.value = true; settle()
                label("发弹幕")
                click(button().boundsInRoot.center)
                assertEquals(listOf(false, true), calls)
                click(Offset(width / 2f, 85f))
                assertEquals(listOf(false, true), calls)
            } finally { scene.close() }
        }
    }
}
