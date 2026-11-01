package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.data.repository.SubReplySortMode
import com.android.purebilibili.feature.video.ui.components.SubReplyDetailContent
import com.bilipai.desktop.ui.*
import com.bilipai.desktop.plugins.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopSubReplyPaginationCompositionTest {
    @Test fun unchangedRowsReevaluatePaginationAndErrorStopsAllAutomaticRequests() {
        val folder = Files.createTempDirectory("sub-reply-pagination-")
        val context = DesktopPluginContext(DesktopPluginStore(folder))
        val loaded = MutableStateFlow(false)
        val port = Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
            arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "StrictCommentPort"
                "getContext" -> context
                "getSubReplyLoadedCountEnabled" -> loaded
                "isOwned" -> true
                else -> error("Unexpected comment port access: ${method.name}")
            }
        } as DesktopCommentPlatform
        val page = mutableIntStateOf(1)
        val failure = mutableStateOf<String?>(null)
        var requests = 0
        val scene = ImageComposeScene(640, 800, Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopCommentBindings provides port) {
                    SubReplyDetailContent(rootReply = ReplyItem(rpid = 11, count = 100),
                        subReplies = emptyList(), sortMode = SubReplySortMode.entries.first(),
                        error = failure.value, isLoading = false, isEnd = false, emoteMap = emptyMap(),
                        onLoadMore = { requests++ }, onRefresh = { error("refresh") },
                        onSortModeChange = { error("sort") }, onDismiss = { error("dismiss") },
                        remoteReplyCount = 100, targetReplyId = 999, paginationPage = page.intValue)
                }
            }
        }
        fun settle() { repeat(20) { Snapshot.sendApplyNotifications(); scene.render().close(); Thread.sleep(10) } }
        try {
            settle()
            val initial = requests
            assertTrue(initial > 0)
            settle()
            assertEquals(initial, requests, "Stable page must not continuously request")
            page.intValue = 2
            settle()
            assertEquals(initial + 3, requests, "All three eligible effects observe the actual new page")
            failure.value = "failed"
            settle()
            val beforeErrorPage = requests
            page.intValue = 3
            settle()
            assertEquals(beforeErrorPage, requests, "Error must prevent every automatic request")
            failure.value = null
            settle()
            assertEquals(beforeErrorPage + 3, requests)
        } finally { scene.close(); folder.toFile().deleteRecursively() }
    }
}
