package com.android.purebilibili.feature.video.ui.overlay

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopPlayerSideButtonCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
    @Test fun nativeAndGlassBranchesKeepExactButtonSizeAndIndependentCurrentCallbacks() {
        for (glass in listOf(false, true)) {
            val version = mutableIntStateOf(1)
            val calls = mutableListOf<Pair<Int, String>>()
            val scene = ImageComposeScene(160, 80, Density(1f)) {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalPlayerControlGlassEnabled provides glass,
                        LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = glass),
                    ) {
                        val current = version.intValue
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            PlayerSideButton(Icons.Outlined.Lock, "已锁定", { calls += current to "lock" })
                            PlayerSideButton(Icons.Outlined.Camera, "截图", { calls += current to "capture" })
                        }
                    }
                }
            }
            fun settle() { repeat(6) { Snapshot.sendApplyNotifications(); scene.render().close() } }
            fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun icon(label: String) = all().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
            }
            fun click(p: Offset) {
                scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons())
                settle()
            }
            try {
                settle(); assertTrue(calls.isEmpty())
                for (label in listOf("已锁定", "截图")) {
                    val image = icon(label)
                    assertEquals(20f, image.boundsInRoot.width)
                    assertEquals(20f, image.boundsInRoot.height)
                    var button = image
                    while (!button.config.contains(SemanticsActions.OnClick)) button = checkNotNull(button.parent)
                    assertEquals(34f, button.boundsInRoot.width)
                    assertEquals(34f, button.boundsInRoot.height)
                }
                click(icon("已锁定").boundsInRoot.center)
                assertEquals(listOf(1 to "lock"), calls)
                version.intValue = 2; settle()
                click(icon("截图").boundsInRoot.center)
                assertEquals(listOf(1 to "lock", 2 to "capture"), calls)
                click(Offset(140f, 70f))
                assertEquals(listOf(1 to "lock", 2 to "capture"), calls)
            } finally { scene.close() }
        }
    }
}
