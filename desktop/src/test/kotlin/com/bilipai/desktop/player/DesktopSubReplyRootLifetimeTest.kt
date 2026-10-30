package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.feature.video.ui.components.SubReplySheet
import com.android.purebilibili.feature.video.viewmodel.SubReplyUiState
import com.bilipai.desktop.ui.*
import com.bilipai.desktop.plugins.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopSubReplyRootLifetimeTest {
    @Test fun changingRootDisposesTheOldSheetEffectAndHidingDisposesTheNewOne() {
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
        val state = mutableStateOf(SubReplyUiState(visible = true, rootReply = ReplyItem(rpid = 11), isEnd = true))
        val callbackOwner = mutableStateOf("A")
        val events = mutableListOf<String>()
        val scene = ImageComposeScene(640, 800, Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopCommentBindings provides port) {
                    val owner = callbackOwner.value
                    SubReplySheet(state = state.value, emoteMap = emptyMap(),
                        onDismiss = { error("Unexpected dismiss") }, onLoadMore = { error("End of list") },
                        onRefresh = { error("refresh") }, onSortModeChange = { error("sort") },
                        onCoveredBlurProgressChange = { events += "$owner:$it" })
                }
            }
        }
        fun settle() { repeat(20) { Snapshot.sendApplyNotifications(); scene.render().close(); Thread.sleep(10) } }
        try {
            settle()
            assertTrue(events.isNotEmpty())
            events.clear()
            callbackOwner.value = "B"
            settle()
            assertTrue(events.isNotEmpty())
            assertEquals(listOf("B:1.0"), events, "A stationary mounted sheet fully covers the underlying page")
            events.clear()
            state.value = state.value.copy(rootReply = ReplyItem(rpid = 22))
            settle()
            assertEquals(1, events.count { it == "A:0.0" }, "Root change disposes the original effect exactly once")
            assertTrue(events.any { it == "B:1.0" })
            events.clear()
            state.value = state.value.copy(visible = false)
            settle()
            assertEquals(listOf("B:0.0"), events)
            events.clear()
            settle()
            assertTrue(events.isEmpty())
        } finally { scene.close(); folder.toFile().deleteRecursively() }
    }
}
