package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.TopicFeedPage
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Real Compose scene and pointer click; no HWND, player surface, or network. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopStoryInitialFailureUiTest {
    @Test fun `owned playback error retry dispatches to held source and never fetches more feed`(): Unit = runBlocking {
        for (style in AppUiStyle.entries) {
            var feedRequests = 0; var retryRequests = 0
            var held: DesktopStoryOwner? = null
            val data = object : DesktopStoryTopicDataSource {
                override val sessionEpoch = MutableStateFlow(7L)
                override suspend fun homePage(index: Int): List<VideoItem> { feedRequests++; return emptyList() }
                override suspend fun isVerticalVideo(bvid: String, aid: Long) = false
                override suspend fun topicDetails(topicId: Long): TopicTopDetails = error("Not part of this fixture")
                override suspend fun topicFeed(topicId: Long, offset: String, sortBy: Int): TopicFeedPage = error("Not part of this fixture")
            }
            var snapshot by mutableStateOf(DesktopStoryPlaybackSnapshot())
            val scene = ImageComposeScene(700, 980, Density(1f)) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style)) {
                    ProvideAppThemeConfig(AppThemeConfig(hapticFeedbackEnabled = false)) {
                        DesktopStoryScreen(data, DesktopStorySeed("BV-fixture", 88, title = "Synthetic Story"), playback = snapshot,
                            onPlaybackRequest = { request ->
                                if (request.select) {
                                    held = request.owner
                                    snapshot = DesktopStoryPlaybackSnapshot(request.owner, request.queue[request.index].bvid,
                                        error = "Synthetic initial stream failure", queueIndex = request.index, cid = 88)
                                }
                            }, onReleasePlayback = {}, onBack = {}, onUser = {}, onSearch = {}, onRetryPlayback = { owner ->
                                assertSame(held, owner); retryRequests++
                                snapshot = snapshot.copy(error = null)
                            }) { _, _, modifier -> Box(modifier) }
                    }
                }
            }
            var nanos = 0L
            suspend fun settle() { repeat(12) { nanos += 40_000_000; scene.render(nanos).close(); delay(4) } }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun find(text: String) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
            try {
                settle()
                assertNotNull(find("Synthetic initial stream failure"), "$style displays held-source failure")
                val retry = assertNotNull(find("重试"))
                val feedBefore = feedRequests
                scene.sendPointerEvent(PointerEventType.Press, retry.boundsInRoot.center,
                    timeMillis = nanos / 1_000_000, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, retry.boundsInRoot.center,
                    timeMillis = nanos / 1_000_000 + 40, buttons = PointerButtons())
                settle()
                assertEquals(1, retryRequests, "$style click retries playback exactly once")
                assertEquals(feedBefore, feedRequests, "$style playback retry must not request another recommendation page")
                assertNull(find("Synthetic initial stream failure"), "$style successful retry clears owned error")
                snapshot = snapshot.copy(owner = DesktopStoryOwner("foreign", 7), error = "Foreign source failure")
                settle()
                assertNull(find("Foreign source failure"), "$style foreign error remains hidden")
            } finally { scene.close() }
        }
    }
}
