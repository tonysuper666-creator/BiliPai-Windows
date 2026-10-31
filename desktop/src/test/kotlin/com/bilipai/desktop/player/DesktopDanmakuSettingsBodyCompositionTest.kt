package com.bilipai.desktop.player

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.feature.video.ui.components.DanmakuSettingsPanelBody
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopDanmakuSettingsBodyCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)

    @Test fun scrollMovesOnlyContentAndDoneRemainsClickableAtNarrowAndWideWidths() = runBlocking {
        for (width in listOf(240, 800)) {
            val scroll = ScrollState(0)
            var dismisses = 0
            var firstY = Float.NaN
            val scene = ImageComposeScene(width, 320, Density(1f)) {
                MaterialTheme {
                    DanmakuSettingsPanelBody(
                        onDismiss = { dismisses++ },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        scrollState = scroll,
                        navigation = { AppText("窗口播放") },
                    ) {
                        repeat(20) { index ->
                            Box(Modifier.fillMaxWidth().height(48.dp).then(
                                if (index == 0) Modifier.onGloballyPositioned { firstY = it.positionInRoot().y }
                                else Modifier
                            )) { AppText("设置 $index") }
                        }
                    }
                }
            }
            fun settle() { repeat(5) { Snapshot.sendApplyNotifications(); scene.render().close() } }
            fun text(label: String) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true }
            try {
                settle()
                assertEquals(0, dismisses)
                val titleBounds = text("弹幕设置").boundsInRoot
                val doneBounds = text("完成").boundsInRoot
                val badgeBounds = text("窗口播放").boundsInRoot
                val initialY = firstY
                assertTrue(scroll.maxValue > 120)
                scroll.scrollTo(120)
                settle()
                assertEquals(120, scroll.value)
                assertEquals(initialY - 120f, firstY)
                assertEquals(titleBounds, text("弹幕设置").boundsInRoot)
                assertEquals(doneBounds, text("完成").boundsInRoot)
                assertEquals(badgeBounds, text("窗口播放").boundsInRoot)
                assertTrue(doneBounds.left >= 0f && doneBounds.right <= width)
                val point = doneBounds.center
                scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, point, buttons = PointerButtons())
                settle()
                assertEquals(1, dismisses)
            } finally { scene.close() }
        }
    }
}
