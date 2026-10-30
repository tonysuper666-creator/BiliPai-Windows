package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.feature.video.screen.VideoDetailPlaybackErrorNotice
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopPlaybackErrorNoticeCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
    @Test fun retryExceptionsUseExactLabelsAndLatestCallback() {
        val state = mutableStateOf(VideoPlaybackUiState.Error(VideoLoadError.RateLimited(60000, "BV1"), false))
        val version = mutableIntStateOf(1)
        val calls = mutableListOf<Int>()
        val scene = ImageComposeScene(480, 320, Density(1f)) {
            MaterialTheme {
                val current = version.intValue
                VideoDetailPlaybackErrorNotice(state.value, { calls += current })
            }
        }
        fun settle() { repeat(4) { Snapshot.sendApplyNotifications(); scene.render().close() } }
        fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun label(text: String) = all().single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true }
        fun click(text: String) {
            val p = label(text).boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons())
            settle()
        }
        try {
            settle(); assertTrue(calls.isEmpty())
            label(state.value.error.toUserMessage())
            click("强制重试"); assertEquals(listOf(1), calls)
            state.value = VideoPlaybackUiState.Error(VideoLoadError.GlobalCooldown(60000, 3), false)
            version.intValue = 2; settle()
            label(state.value.error.toUserMessage())
            click("清除冷却并重试"); assertEquals(listOf(1, 2), calls)
            state.value = VideoPlaybackUiState.Error(VideoLoadError.PlayUrlEmpty, false); settle()
            click("重试"); assertEquals(listOf(1, 2, 2), calls)
        } finally { scene.close() }
    }

    @Test fun nonRetryableErrorHasNoActionUntilStateEnablesRetry() {
        val state = mutableStateOf(VideoPlaybackUiState.Error(VideoLoadError.VideoNotFound, false))
        val scene = ImageComposeScene(240, 320, Density(1f)) {
            MaterialTheme { VideoDetailPlaybackErrorNotice(state.value, { error("unexpected click") }) }
        }
        fun settle() { repeat(4) { Snapshot.sendApplyNotifications(); scene.render().close() } }
        fun actions() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            .filter { it.config.contains(SemanticsActions.OnClick) }
        try {
            settle(); assertTrue(actions().isEmpty())
            state.value = state.value.copy(canRetry = true); settle()
            assertEquals(1, actions().size)
        } finally { scene.close() }
    }
}
