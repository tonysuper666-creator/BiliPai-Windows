package com.bilipai.desktop.player

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.android.purebilibili.feature.video.screen.VideoCommentTab
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class DesktopVideoCommentPageCompositionTest {
    @Test fun realListReevaluatesNewPageWithoutRowsChangingAndHonorsErrorRefreshAndEnd() {
        val folder = Files.createTempDirectory("video-comment-page")
        val context = DesktopPluginContext(DesktopPluginStore(folder))
        val platform = Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
            arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "StrictVideoCommentPort"
                "getContext" -> context
                "isOwned" -> true
                else -> error("Unexpected port call: ${method.name}")
            }
        } as DesktopCommentPlatform
        val page = mutableIntStateOf(1)
        val failure = mutableStateOf<String?>(null)
        val refreshing = mutableStateOf(false)
        val ended = mutableStateOf(false)
        var loads = 0
        val scene = ImageComposeScene(640, 800, Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopCommentBindings provides platform) {
                    VideoCommentTab(
                        listState = rememberLazyListState(), modifier = Modifier,
                        upMid = 0, maxTimestampMs = null, replies = emptyList(), replyCount = 100,
                        emoteMap = emptyMap(), isRepliesLoading = false,
                        isRepliesRefreshing = refreshing.value, repliesError = failure.value,
                        isRepliesEnd = ended.value, voteCard = null,
                        onUpClick = { error("up") }, onSubReplyClick = { _, _ -> error("subreply") },
                        onCommentReplyClick = { error("reply") }, onLoadMoreReplies = { loads++ },
                        onRefreshReplies = { error("refresh") }, onImagePreview = { _, _, _, _ -> error("image") },
                        onTimestampClick = null, contentPadding = PaddingValues(), currentMid = 0,
                        showUpFlag = false, dissolvingIds = emptySet(), onDeleteComment = { error("delete") },
                        onDissolveStart = { error("dissolve") }, onCommentLike = { error("like") },
                        onCommentHate = { error("hate") }, likedComments = emptySet(), hatedComments = emptySet(),
                        onCommentUrlClick = { error("url") }, onReportComment = { _, _ -> error("report") },
                        onToggleTopComment = { error("top") }, onCheckCommentFraud = { error("fraud") },
                        showIdentityDecorations = false, lightweightCommentRendering = false,
                        showHeader = false, paginationPage = page.intValue,
                    )
                }
            }
        }
        fun settle() { repeat(20) { Snapshot.sendApplyNotifications(); scene.render().close(); Thread.sleep(10) } }
        try {
            settle(); assertEquals(1, loads)
            settle(); assertEquals(1, loads)
            page.intValue = 2; settle(); assertEquals(2, loads)
            failure.value = "failed"; settle()
            page.intValue = 3; settle(); assertEquals(2, loads)
            failure.value = null; settle(); assertEquals(3, loads)
            refreshing.value = true; settle()
            page.intValue = 4; settle(); assertEquals(3, loads)
            refreshing.value = false; settle(); assertEquals(4, loads)
            ended.value = true; settle()
            page.intValue = 5; settle(); assertEquals(4, loads)
        } finally { scene.close(); folder.toFile().deleteRecursively() }
    }
}
