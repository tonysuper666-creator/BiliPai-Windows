package com.bilipai.desktop.player

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.feature.video.screen.VideoDetailRouteCommentEffect
import com.android.purebilibili.feature.video.viewmodel.CommentUiState
import com.android.purebilibili.feature.video.viewmodel.SubReplyUiState
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopRouteCommentEffectTest {
    private fun settle(scene: ImageComposeScene) {
        repeat(12) { Snapshot.sendApplyNotifications(); scene.render().close(); Thread.sleep(5) }
    }

    @Test fun arrivingRootUsesLatestCallbackAndHandledStopsFurtherOpening() {
        val comments = mutableStateOf(CommentUiState(isRepliesLoading = true))
        val thread = mutableStateOf(SubReplyUiState())
        val handled = mutableStateOf(false)
        val callbackVersion = mutableIntStateOf(1)
        val events = mutableListOf<String>()
        val root = ReplyItem(rpid = 42L)
        val scene = ImageComposeScene(80, 80, Density(1f)) {
            val version = callbackVersion.intValue
            VideoDetailRouteCommentEffect(42, 9001, handled.value, comments, thread,
                onOpenReply = { reply, target -> events += "$version:${reply.rpid}:$target" },
                onOpenReplyFromRoute = { _, _ -> error("Loading or known root must not use fallback") },
                onHandled = { events += "handled"; handled.value = true })
        }
        try {
            settle(scene); assertTrue(events.isEmpty())
            callbackVersion.intValue = 2; settle(scene); assertTrue(events.isEmpty())
            comments.value = comments.value.copy(replies = persistentListOf(root), isRepliesLoading = false)
            settle(scene); assertEquals(listOf("2:42:9001", "handled"), events)
            comments.value = comments.value.copy(replies = persistentListOf(root, ReplyItem(rpid = 43L)))
            settle(scene); assertEquals(2, events.size)
        } finally { scene.close() }
    }

    @Test fun visibleThreadBlocksFallbackAndFailedFallbackRetriesWhenListChanges() {
        val comments = mutableStateOf(CommentUiState())
        val thread = mutableStateOf(SubReplyUiState(visible = true))
        val accepted = mutableStateOf(false)
        val handled = mutableStateOf(false)
        val rootId = mutableLongStateOf(42L)
        val requests = mutableListOf<Pair<Long, Long>>()
        var marks = 0
        val scene = ImageComposeScene(80, 80, Density(1f)) {
            val result = accepted.value
            VideoDetailRouteCommentEffect(rootId.longValue, 9001, handled.value, comments, thread,
                onOpenReply = { _, _ -> error("No matching root in this fixture") },
                onOpenReplyFromRoute = { root, target -> requests += root to target; result },
                onHandled = { marks++; handled.value = true })
        }
        try {
            settle(scene); assertTrue(requests.isEmpty())
            thread.value = thread.value.copy(visible = false); settle(scene)
            assertEquals(listOf(42L to 9001L), requests); assertEquals(0, marks)
            accepted.value = true; settle(scene)
            assertEquals(1, requests.size, "Callback replacement alone must not launch a request")
            comments.value = comments.value.copy(replies = persistentListOf(ReplyItem(rpid = 7L)))
            settle(scene); assertEquals(listOf(42L to 9001L, 42L to 9001L), requests); assertEquals(1, marks)
            handled.value = false; rootId.longValue = 0; settle(scene)
            assertEquals(2, requests.size); assertEquals(1, marks)
        } finally { scene.close() }
    }
}
