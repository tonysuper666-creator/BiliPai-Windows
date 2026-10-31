package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.onGloballyPositioned
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
class DesktopSubReplyListModifierCompositionTest {
    @Test fun callerModifierReachesActualThreadListAndUpdatesItsMeasuredWidth() {
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
        val inset = mutableIntStateOf(17)
        var width = -1
        val page = mutableIntStateOf(1)
        val failure = mutableStateOf<String?>(null)
        var requests = 0
        val scene = ImageComposeScene(640, 800, Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopCommentBindings provides port) {
                    SubReplyDetailContent(rootReply = ReplyItem(rpid = 11, count = 100),
                        subReplies = emptyList(), sortMode = SubReplySortMode.entries.first(),
                        error = failure.value, isLoading = false, isEnd = true, emoteMap = emptyMap(),
                        onLoadMore = { requests++ }, onRefresh = { error("refresh") },
                        onSortModeChange = { error("sort") }, onDismiss = { error("dismiss") },
                        remoteReplyCount = 100, paginationPage = page.intValue,
                        listModifier = Modifier.padding(horizontal = inset.intValue.dp).onGloballyPositioned { width = it.size.width })
                }
            }
        }
        fun settle() { repeat(20) { Snapshot.sendApplyNotifications(); scene.render().close(); Thread.sleep(10) } }
        try {
            settle()
            assertEquals(606, width)
            assertEquals(0, requests)
            inset.intValue = 33
            settle()
            assertEquals(574, width)
            assertEquals(0, requests)
        } finally { scene.close(); folder.toFile().deleteRecursively() }
    }
}
