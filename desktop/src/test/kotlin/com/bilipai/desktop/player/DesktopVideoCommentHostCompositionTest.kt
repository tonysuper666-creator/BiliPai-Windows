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
import com.android.purebilibili.feature.video.ui.components.VideoCommentSheetHost
import androidx.compose.ui.geometry.Offset
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.ui.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopVideoCommentHostCompositionTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    @Test fun completeHostKeepsSortAndComposerActionsSeparateFromBackdropDismiss() = runBlocking {
        val folder = Files.createTempDirectory("bangumi-panel")
        val store = DesktopPluginStore(folder)
        val context = DesktopPluginContext(store)
        var owned = true
        val scheduler = TestCoroutineScheduler()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, t -> errors += t })
        val requests = Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
            arrayOf(DesktopVideoCommentRequests::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NoNetworkCommentRequests"
                "currentMid" -> 0L
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
        var dismisses = 0
        val visible = mutableStateOf(true)
        val scene = ImageComposeScene(800, 800, Density(1f), coroutineContext = scope.coroutineContext) {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopCommentBindings provides comment,
                    LocalDesktopOriginalHomeSettingsRootServices provides root,
                    LocalDesktopHomePlatform provides platform,
                    LocalDesktopBackToTopBindings provides backToTop,
                    LocalDesktopLiquidTabSettings provides tabSettings,
                    LocalAppThemeConfig provides AppThemeConfig(headerBlurEnabled = false, liquidGlassEnabled = false, progressiveTopFadeEnabled = true)) {
                    VideoCommentSheetHost(mainSheetVisible = visible.value,
                        onDismiss = { dismisses++; visible.value = false },
                        commentViewModel = vm, aid = 0,
                        onRootCommentClick = { comments++ },
                        onUserClick = { error("user") }, handleFraudEvents = false)
                }
            }
        }
        fun settle() { repeat(40) {
            Snapshot.sendApplyNotifications()
            scheduler.advanceTimeBy(16); scheduler.runCurrent()
            scene.render(scheduler.currentTime * 1_000_000L).close()
            scheduler.runCurrent(); Thread.sleep(5)
            assertTrue(errors.isEmpty(), errors.toString())
        } }
        fun click(label: String) {
            var target = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true ||
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
            while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = checkNotNull(target.parent)
            scene.sendPointerEvent(PointerEventType.Press, target.boundsInRoot.center, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, target.boundsInRoot.center, buttons = PointerButtons())
            settle()
        }
        try {
            settle(); assertEquals(CommentSortMode.HOT, vm.commentState.value.sortMode); assertEquals(0, comments)
            click("切换评论排序")
            assertEquals(CommentSortMode.NEWEST, vm.commentState.value.sortMode)
            assertEquals(CommentSortMode.NEWEST.apiMode, DesktopOriginalReplySettings.getCommentDefaultSortMode(context).first())
            click("说点什么，直接评论 UP 主和大家")
            assertEquals(1, comments)
            assertEquals(0, dismisses, "Content clicks must not bubble to the backdrop")
            scene.sendPointerEvent(PointerEventType.Press, Offset(10f, 10f), buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, Offset(10f, 10f), buttons = PointerButtons())
            settle()
            assertEquals(1, dismisses)
            assertEquals(1, comments)
            settle()
            assertEquals(1, dismisses, "Exit animation must not dismiss again")
        } finally { scene.close(); scope.cancel(); scheduler.runCurrent(); folder.toFile().deleteRecursively() }
    }
}
