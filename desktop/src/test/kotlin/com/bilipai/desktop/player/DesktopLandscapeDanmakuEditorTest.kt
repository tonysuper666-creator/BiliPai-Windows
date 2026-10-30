package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.android.purebilibili.feature.video.ui.components.LandscapeDanmakuComposerEditor
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopLandscapeDanmakuEditorTest {
    @Test fun actualEditorSendsExactDraftAndRejectsBusyAndHiddenInput() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        val visible = mutableStateOf(true)
        val busy = mutableStateOf(false)
        val sent = mutableListOf<List<Any>>()
        val drafts = mutableListOf<Pair<String, Boolean>>()
        val scene = ImageComposeScene(800, 600, Density(1f), coroutineContext = scope.coroutineContext) {
            MaterialTheme {
                LandscapeDanmakuComposerEditor(
                    visible.value, { text, color, mode, size, attention -> sent += listOf(text, color, mode, size, attention) },
                    busy.value, 0xFFFFFF, 1, 25, "  hello  ", false,
                    { text, attention -> drafts += text to attention }, { _, _, _ -> },
                )
            }
        }
        fun frame() { Snapshot.sendApplyNotifications(); scheduler.runCurrent(); scene.render(scheduler.currentTime * 1_000_000).close(); scheduler.runCurrent() }
        fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
        fun sendNode(): SemanticsNode = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            .first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("发送弹幕") == true }
        var center = androidx.compose.ui.geometry.Offset.Zero
        fun click() {
            scene.sendPointerEvent(PointerEventType.Press, center, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, center, buttons = PointerButtons())
            frame()
        }
        try {
            repeat(3) { frame() }
            assertTrue(sendNode().boundsInRoot.right <= 450f)
            val input = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .first { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(requireNotNull(input.config[SemanticsActions.SetText].action).invoke(androidx.compose.ui.text.AnnotatedString(" hello ")))
            frame()
            assertEquals(" hello " to false, drafts.last())
            center = sendNode().boundsInRoot.center
            click()
            assertEquals(listOf(listOf<Any>("hello", 0xFFFFFF, 1, 25, false)), sent)
            busy.value = true; repeat(2) { frame() }; click(); assertEquals(1, sent.size)
            busy.value = false; visible.value = false; repeat(2) { frame() }; click(); assertEquals(1, sent.size)
            assertEquals(listOf(" hello " to false), drafts)
        } finally { scene.close(); scope.cancel(); scheduler.runCurrent() }
    }
}
