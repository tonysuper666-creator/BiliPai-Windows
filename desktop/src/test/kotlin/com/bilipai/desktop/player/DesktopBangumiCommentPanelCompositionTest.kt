package com.bilipai.desktop.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.feature.bangumi.ui.player.BangumiCommentPanel
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.ui.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class DesktopBangumiCommentPanelCompositionTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    @Test fun completePanelWritesOwnedSortAndRejectsRetiredOwner() = runBlocking {
        val folder = Files.createTempDirectory("bangumi-panel")
        val store = DesktopPluginStore(folder)
        val context = DesktopPluginContext(store)
        var owned = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requests = Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
            arrayOf(DesktopVideoCommentRequests::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NoNetworkCommentRequests"
                "isOwned" -> owned
                else -> error("Unexpected request: ${method.name}")
            }
        } as DesktopVideoCommentRequests
        val vm = VideoCommentViewModel(scope, requests)
        val comment = Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
            arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "OwnedPanelCommentPort"
                "getContext" -> context
                "isOwned" -> owned
                else -> error("Unexpected comment port: ${method.name}")
            }
        } as DesktopCommentPlatform
        val home = MutableStateFlow(HomeSettings(androidNativeLiquidGlassEnabled = false, isBottomBarBlurEnabled = false))
        val navigation = MutableStateFlow(AppNavigationSettings())
        val settings = Proxy.newProxyInstance(DesktopHomeSettingsPort::class.java.classLoader,
            arrayOf(DesktopHomeSettingsPort::class.java)) { _, method, _ ->
            when (method.name) {
                "getHomeSettings" -> home
                "getNavigation" -> navigation
                else -> error("Unexpected home settings: ${method.name}")
            }
        } as DesktopHomeSettingsPort
        val preferences = DesktopFavoritePreferences(store)
        val backToTop = DesktopBackToTopBindings(preferences.backToTopEnabled, preferences.initialBackToTopEnabled(),
            preferences.backToTopOffset, preferences.initialBackToTopOffset(), preferences::setBackToTopOffset,
            preferences::updateBackToTopOffset, DesktopFavoriteViewport(800, 800))
        val root = DesktopOriginalHomeSettingsRootServices(settings, preferences,
            { error("notice") }, { _, _, _, _ -> error("profile") })
        val background = object : DesktopHomeWindowBackgroundPort {
            override val isInBackground = false
            override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) {}
            override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) {}
        }
        val platform = DesktopHomePlatform(false, true, false, false, false, false, background, 0f, false)
        val tabSettings = DesktopLiquidTabSettings(store)
        var comments = 0
        val scene = ImageComposeScene(800, 800, Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopCommentBindings provides comment,
                    LocalDesktopOriginalHomeSettingsRootServices provides root,
                    LocalDesktopHomePlatform provides platform,
                    LocalDesktopBackToTopBindings provides backToTop,
                    LocalDesktopLiquidTabSettings provides tabSettings,
                    LocalAppThemeConfig provides AppThemeConfig(headerBlurEnabled = false, liquidGlassEnabled = false, progressiveTopFadeEnabled = true)) {
                    BangumiCommentPanel(vm, rememberLazyListState(), false, false, null,
                        onRootCommentClick = { comments++ }, onReplyClick = { error("reply") },
                        onUserClick = { error("user") }, onCommentUrlClick = { error("url") },
                        onTimestampClick = null, onImagePreview = { _, _, _, _ -> error("image") },
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
        fun settle() { repeat(20) { Snapshot.sendApplyNotifications(); scene.render().close(); Thread.sleep(10) } }
        fun click(label: String) {
            var target = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true }
            while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = checkNotNull(target.parent)
            scene.sendPointerEvent(PointerEventType.Press, target.boundsInRoot.center, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, target.boundsInRoot.center, buttons = PointerButtons())
            settle()
        }
        try {
            settle(); assertEquals(CommentSortMode.HOT, vm.commentState.value.sortMode); assertEquals(0, comments)
            click(CommentSortMode.NEWEST.label)
            assertEquals(CommentSortMode.NEWEST, vm.commentState.value.sortMode)
            assertEquals(CommentSortMode.NEWEST.apiMode, DesktopOriginalReplySettings.getCommentDefaultSortMode(context).first())
            click("发评论"); assertEquals(1, comments)
            owned = false
            click(CommentSortMode.HOT.label)
            assertEquals(CommentSortMode.NEWEST, vm.commentState.value.sortMode)
            assertEquals(CommentSortMode.NEWEST.apiMode, DesktopOriginalReplySettings.getCommentDefaultSortMode(context).first())
        } finally { scene.close(); scope.cancel(); folder.toFile().deleteRecursively() }
    }
}
