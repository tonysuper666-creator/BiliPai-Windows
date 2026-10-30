package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
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
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.feature.video.ui.section.VideoDetailSecondaryButton
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopVideoSecondaryButtonCompositionTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)

    private fun verify(style: AppUiStyle) {
        val enabled = mutableStateOf(true)
        var clicks = 0
        val scene = ImageComposeScene(320, 100, Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalAppUiStyle provides style) {
                    VideoDetailSecondaryButton(onClick = { clicks++ }, enabled = enabled.value) { Text("重试") }
                }
            }
        }
        fun button() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            .single { it.config.getOrNull(SemanticsActions.OnClick) != null }
        fun click() {
            val point = button().boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons())
            scene.render().close()
        }
        try {
            scene.render().close()
            assertEquals(0, clicks)
            click()
            assertEquals(1, clicks)
            enabled.value = false
            scene.render().close()
            assertTrue(button().config.contains(SemanticsProperties.Disabled))
            click()
            assertEquals(1, clicks)
            enabled.value = true
            scene.render().close()
            click()
            assertEquals(2, clicks)
        } finally { scene.close() }
    }

    @Test fun materialButtonRetainsEnabledAdmissionAcrossRecomposition() = verify(AppUiStyle.MATERIAL3)
    @Test fun miuixButtonRetainsEnabledAdmissionAcrossRecomposition() = verify(AppUiStyle.MIUIX)
}
