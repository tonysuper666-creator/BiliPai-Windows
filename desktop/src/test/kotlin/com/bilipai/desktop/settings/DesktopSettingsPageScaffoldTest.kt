package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.LocalBottomBarContentPadding
import com.android.purebilibili.core.ui.LocalSetBottomBarVisible
import com.android.purebilibili.core.util.*
import com.android.purebilibili.feature.settings.SettingsPageScrollHost
import com.android.purebilibili.feature.settings.ui.*
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Runs the original shared scaffold; the complete playback Content remains a separate contract. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopSettingsPageScaffoldTest {
    private class Pane(style: AppUiStyle, width: Int, content: @Composable () -> Unit) : AutoCloseable {
        val backgroundListeners = mutableSetOf<DesktopHomeWindowBackgroundPort.Listener>()
        private val lifecycle = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        private val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        private val background = object : DesktopHomeWindowBackgroundPort {
            override val isInBackground = false
            override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) { backgroundListeners += listener }
            override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) { backgroundListeners -= listener }
        }
        private val page = DesktopOriginalPlayerSettingsContext(
            DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bp-scaffold-"))),
            { true }, { it(); true },
        )
        private val platform = DesktopHomePlatform(false, false, false, false, false, false, background, 0f, false)
        val scene = ImageComposeScene(width, 420, Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, hapticFeedbackEnabled = false)) {
                CompositionLocalProvider(
                    LocalDesktopHomePlatform provides platform,
                    LocalLifecycleOwner provides lifecycle,
                    LocalNavigationEventDispatcherOwner provides navigation,
                    LocalDesktopUpstreamAndroidContext provides page.androidContext,
                ) {
                    DesktopDetailWindow { Surface(Modifier.fillMaxSize()) { content() } }
                }
            }
        }
        private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        fun nodes() = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
        fun tagged(tag: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun clickBack() {
            val icon = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("scaffold-back") == true }
            val button = generateSequence(icon) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertEquals(true, button.config[SemanticsActions.OnClick].action?.invoke())
        }
        suspend fun await(condition: () -> Boolean) {
            withTimeout(7_000) {
                do {
                    scene.render(System.nanoTime()).close()
                    if (condition()) return@withTimeout
                    delay(12)
                } while (true)
            }
        }
        override fun close() {
            scene.close()
            lifecycle.registry.currentState = Lifecycle.State.DESTROYED
            navigation.navigationEventDispatcher.dispose()
        }
    }

    private fun window(width: Int) = WindowSizeClass(
        if (width < 840) WindowWidthSizeClass.Compact else WindowWidthSizeClass.Expanded,
        WindowHeightSizeClass.Compact, width.dp, 420.dp,
    )

    @Test fun originalHeaderBlurFollowsDesktopCapabilityAndReleasesWindowSubscription(): Unit = runBlocking {
        for (style in AppUiStyle.entries) {
            var blur by mutableStateOf(false)
            var backs = 0
            val setVisible: (Boolean) -> Unit = {}
            val pane = Pane(style, 560) {
                CompositionLocalProvider(
                    LocalWindowSizeClass provides window(560),
                    LocalSetBottomBarVisible provides setVisible,
                ) {
                    SettingsPageScaffold(
                        title = "Blur scaffold", onBack = { backs++ }, backContentDescription = "scaffold-back",
                        topBarBlurEnabled = blur, bottomContentPadding = 0.dp,
                        lazyListContent = { item { Text("blur-content", Modifier.testTag("blur-content")) } },
                    )
                }
            }
            pane.use {
                pane.await { pane.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "blur-content" } }
                assertTrue(pane.backgroundListeners.isEmpty(), "$style: disabled blur owns no haze subscription")
                blur = true
                val expectedSubscriptions = if (desktopDetailRenderEffectsSupported()) 1 else 0
                pane.await { pane.backgroundListeners.size == expectedSubscriptions }
                pane.backgroundListeners.toList().forEach { it.onEnterBackground() }
                pane.scene.render(System.nanoTime()).close()
                pane.backgroundListeners.toList().forEach { it.onEnterForeground() }
                pane.clickBack()
                assertEquals(1, backs, "$style: window transitions keep navigation usable")
                blur = false
                pane.await { pane.backgroundListeners.isEmpty() }
                blur = true
                pane.await { pane.backgroundListeners.size == expectedSubscriptions }
            }
            assertTrue(pane.backgroundListeners.isEmpty(), "$style: disposal releases window ownership")
        }
    }

    @Test fun originalLazyScaffoldScrollsRestoresBottomBarAndPreservesBackAndInsets(): Unit = runBlocking {
        for (style in AppUiStyle.entries) for (width in listOf(560, 1100)) {
            val state = LazyListState()
            val visibility = mutableListOf<Boolean>()
            val setVisible: (Boolean) -> Unit = { visibility += it }
            var backs = 0
            Pane(style, width) {
                CompositionLocalProvider(
                    LocalWindowSizeClass provides window(width),
                    LocalSetBottomBarVisible provides setVisible,
                    LocalBottomBarContentPadding provides 72.dp,
                ) {
                    SettingsPageScaffold(
                        title = "Original scaffold", onBack = { backs++ }, backContentDescription = "scaffold-back",
                        bottomContentPadding = 16.dp, listState = state,
                        header = { Text("header", Modifier.height(40.dp)) },
                        lazyListContent = {
                            items(40) { Text("row-$it", Modifier.fillMaxWidth().height(100.dp).testTag("row-$it")) }
                        },
                    )
                }
            }.use { pane ->
                pane.await { state.layoutInfo.totalItemsCount == 41 && visibility.lastOrNull() == true }
                assertEquals(72, state.layoutInfo.afterContentPadding, "$style/$width: shell bottom padding wins")
                assertTrue(state.layoutInfo.beforeContentPadding > 0, "$style/$width: content avoids top chrome")
                assertEquals(minOf(width, 760).toFloat(), pane.tagged("row-0").boundsInRoot.width)
                pane.clickBack()
                assertEquals(1, backs)
                val down = launch { state.scrollToItem(20) }
                pane.await { state.firstVisibleItemIndex == 20 && visibility.lastOrNull() == false }
                down.join()
                val up = launch { state.scrollToItem(0) }
                pane.await { state.firstVisibleItemIndex == 0 && visibility.lastOrNull() == true }
                up.join()
                val downAgain = launch { state.scrollToItem(20) }
                pane.await { state.firstVisibleItemIndex == 20 && visibility.lastOrNull() == false }
                downAgain.join()
            }
            assertEquals(true, visibility.last(), "$style/$width: disposal restores the shell bottom bar")
        }
    }

    @Test fun originalExternalScaffoldAllocatesTopPaddingOnceWithOrWithoutAHeader(): Unit = runBlocking {
        for (style in AppUiStyle.entries) for (withHeader in listOf(false, true)) {
            val visibility = mutableListOf<Boolean>()
            val setVisible: (Boolean) -> Unit = { visibility += it }
            var measuredTop = 0.dp
            var backs = 0
            Pane(style, 1100) {
                CompositionLocalProvider(
                    LocalWindowSizeClass provides window(1100),
                    LocalSetBottomBarVisible provides setVisible,
                ) {
                    SettingsPageScaffold(
                        title = "External scaffold", onBack = { backs++ }, backContentDescription = "scaffold-back",
                        bottomContentPadding = 16.dp, scrollHost = SettingsPageScrollHost.External,
                        header = if (withHeader) ({ Text("external-header", Modifier.testTag("external-header")) }) else null,
                    ) {
                        val padding = settingsScrollContentPadding(
                            extraTop = 3.dp, extraBottom = 5.dp, extraHorizontal = 7.dp, extraVertical = 11.dp,
                        )
                        measuredTop = padding.calculateTopPadding()
                        assertEquals(16.dp, padding.calculateBottomPadding())
                        assertEquals(7.dp, padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))
                        Box(Modifier.fillMaxSize().testTag("external-content"))
                    }
                }
            }.use { pane ->
                pane.await { pane.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "external-content" } }
                assertEquals(760f, pane.tagged("external-content").boundsInRoot.width)
                if (withHeader) {
                    assertEquals(14.dp, measuredTop, "$style: header owns the top chrome inset")
                    assertTrue(pane.tagged("external-header").boundsInRoot.top > 0)
                } else assertTrue(measuredTop > 14.dp, "$style: external content receives the top chrome inset")
                pane.clickBack()
                assertEquals(1, backs)
            }
            assertTrue(visibility.isEmpty(), "$style/$withHeader: externally scrolling content owns its visibility policy")
        }
    }
}
