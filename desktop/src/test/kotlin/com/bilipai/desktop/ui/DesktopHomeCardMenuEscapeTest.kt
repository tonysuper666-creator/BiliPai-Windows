@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class,
    coil3.annotation.DelicateCoilApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.OnBackCompletedFallback
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.android.purebilibili.app.newImageLoader
import com.android.purebilibili.core.store.DesktopOriginalHomeCardVisualSettings
import com.android.purebilibili.core.store.HomeFeedCardStyle
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.util.WindowWidthSizeClass
import com.android.purebilibili.data.model.response.Owner
import com.android.purebilibili.data.model.response.Stat
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.home.resolveHomeFeedCardLayout
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import com.bilipai.desktop.plugins.asAndroidContext
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/*
 * Slice K3h on the actual Home entry: DesktopOriginalDiscoveryVideoCard (the call of DiscoveryScreens) -> the generated
 * ElegantVideoCard -> the cover (home_video_cover) and its card menu. The keyboard sequence of the K3 card: Tab to the
 * cover, Shift+F10 or the Windows menu key (AWT VK_CONTEXT_MENU, 525) opens the menu of this card, Escape closes it and
 * the focus is back on the same cover; no video opens, no menu row runs, and Home does not navigate back.
 *
 * The Escape path is the one of the real window, rebuilt around the scene:
 *  - Main.kt gives the Window onKeyEvent = DesktopWindowKeyFallback.dispatch, which Compose calls only for a key the scene
 *    did not consume. DesktopShell registers the Root key handler there: a KeyDown of Escape (no search editor focused,
 *    not fullscreen) calls DesktopRootWindowNavigationOwner.requestBack(), which is DirectNavigationEventInput.backCompleted()
 *    on the one Root dispatcher (when that dispatcher is enabled).
 *  - The card menu is a Popup; Compose registers its back handler on LocalNavigationEventDispatcherOwner, which below Root is
 *    that Root owner. Root's dispatcher calls its onBackCompletedFallback (route back) when no handler took the event.
 * Here the scene content gets the same kind of owner (a NavigationEventDispatcher with a fallback counter and a
 * DirectNavigationEventInput), and key() feeds backCompleted() exactly when the real Escape KeyDown was not consumed by the
 * scene. So the menu is closed by the real key event through the production Popup handler, not by a direct call.
 *
 * Not covered: a native Windows window, AWT itself, the DesktopShell guards (fullscreen, search focus, closing), a screen
 * reader. This is a headless ImageComposeScene; it is not GUI evidence.
 */
class DesktopHomeCardMenuEscapeTest {
    @TempDir lateinit var directory: Path

    private fun cases(scenario: String, body: (AppUiStyle, AppThemeMode) -> Unit) = AppUiStyle.entries.flatMap { style ->
        listOf(AppThemeMode.LIGHT, AppThemeMode.DARK).map { mode ->
            DynamicTest.dynamicTest("$scenario / ${style.name} / ${mode.name}") { body(style, mode) }
        }
    }

    private val video = VideoItem(aid = 8, bvid = "BV-k3h-fixture", cid = 80, title = "K3h fixture",
        pic = "https://example.invalid/k3h-cover.png", owner = Owner(mid = 9, name = "up"), stat = Stat(view = 1200, danmaku = 3))

    /** The Root-shaped owner: one dispatcher, a completed-back input, and the route-back fallback that Root would run. */
    private class RootBack : NavigationEventDispatcherOwner, AutoCloseable {
        var fallbacks = 0
        override val navigationEventDispatcher = NavigationEventDispatcher(
            onBackCompletedFallback = object : OnBackCompletedFallback {
                override fun onBackCompletedFallback() { fallbacks++ }
            },
        )
        val input = DirectNavigationEventInput()
        init { navigationEventDispatcher.addInput(input) }
        /** DesktopRootWindowNavigationOwner.requestBack(), without the window checks. */
        fun requestBack(): Boolean {
            if (!navigationEventDispatcher.isEnabled) return false
            input.backCompleted(); return true
        }
        override fun close() { navigationEventDispatcher.removeInput(input); navigationEventDispatcher.dispose() }
    }

    private inner class Scene(style: AppUiStyle, mode: AppThemeMode) : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        private var time = 0L
        private val errors = mutableListOf<Throwable>()
        private val repository = DesktopRepository(DesktopSessionStore.temporary())
        private val context = DesktopPluginContext(DesktopPluginStore(directory.resolve("plugins-${System.nanoTime()}")))
        private val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        private val previousLoader = SingletonImageLoader.get(PlatformContext.INSTANCE)
        private val imageFile = directory.resolve("fixture.png").toFile().apply {
            if (!exists()) ImageIO.write(BufferedImage(160, 100, BufferedImage.TYPE_INT_ARGB), "png", this)
        }
        private val loader = newImageLoader(PlatformContext.INSTANCE,
            Call.Factory { error("Image fixture must not access a socket") }, directory.resolve("cache").toFile())
            .newBuilder().components { add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                    chain.withRequest(chain.request.newBuilder().data(imageFile).build()).proceed()
            }) }.build()
        val root = RootBack()
        val opened = mutableListOf<VideoCard>()
        val menuActions = mutableListOf<String>()
        /** Escape KeyDowns the scene did not consume, which the real window hands to Root's requestBack(). */
        var unconsumedEscapes = 0

        init { check(GraphicsEnvironment.isHeadless()); SingletonImageLoader.setUnsafe(loader) }

        private val scene = DesktopUpstreamCalls.withOwner(owner) {
            ImageComposeScene(520, 760, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) +
                DesktopUpstreamScope(owner) + CoroutineExceptionHandler { _, error -> errors += error }) {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides root) {
                    DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, themeMode = mode, hapticFeedbackEnabled = false),
                        systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 520) {
                        val original = LocalWindowInfo.current
                        val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                        val ux = remember { DesktopWindowsUxMemory(DesktopWindowsUxSettings(cardInteractions = DesktopWindowsUxCardInteractions.ON)) }
                        CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopWindowsUx provides ux,
                            LocalDesktopUpstreamAndroidContext provides context.asAndroidContext()) {
                            Page()
                        }
                    }
                }
            }
        }

        @Composable private fun Page() {
            val settings = remember {
                DesktopOriginalHomeCardVisualSettings.decode(DesktopPreferenceSnapshot(JsonObject(emptyMap())))
                    .copy(cardAnimationEnabled = false)
            }
            val layout = remember { resolveHomeFeedCardLayout(HomeFeedCardStyle.BILIPAI, 2, WindowWidthSizeClass.Expanded) }
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.size(40.dp).testTag("before").focusable())
                Box(Modifier.width(320.dp)) {
                    DesktopOriginalDiscoveryVideoCard(video, 0, layout, settings,
                        onVideo = { opened += it }, onUser = {}, onPreview = {},
                        onNotInterested = { menuActions += "not-interested" }, onWatchLater = { menuActions += "watch-later" })
                }
                Box(Modifier.size(40.dp).testTag("after").focusable())
            }
        }

        fun advance(frames: Int = 40) { repeat(frames) {
            scheduler.runCurrent(); time += 30_000_000
            DesktopUpstreamCalls.withOwner(owner) { scene.render(time).close() }
            scheduler.runCurrent()
            errors.firstOrNull()?.let { throw AssertionError("Home card composition failed", it) }
        } }
        private fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        private fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun focusedTag() = nodes().lastOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            ?.config?.getOrNull(SemanticsProperties.TestTag)
        fun hasText(text: String) = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }
        fun menuOpen() = hasText("稍后再看") && hasText("不感兴趣")

        /** One key press as the real window delivers it: the scene first; an unconsumed Escape KeyDown goes to Root's back. */
        fun key(key: Key, shift: Boolean = false) {
            val consumed = scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
            if (!consumed && key == Key.Escape) { unconsumedEscapes++; root.requestBack() }
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift)); advance()
        }
        fun tabIntoCard() {
            key(Key.Tab)
            assertEquals("before", focusedTag(), "The first Tab must reach the sentinel before the card")
            key(Key.Tab)
            assertEquals("home_video_cover", focusedTag(), "Tab from the sentinel focuses the cover of the card")
        }

        override fun close() {
            scene.close(); root.close(); repository.logout()
            SingletonImageLoader.setUnsafe(previousLoader); loader.shutdown()
        }
    }

    private val openKeys = listOf<Pair<String, (Scene) -> Unit>>(
        "Shift+F10" to { s -> s.key(Key.F10, shift = true) },
        "menu key (VK_CONTEXT_MENU 525)" to { s -> s.key(Key(525)) },
    )

    @TestFactory fun escapeClosesTheCardMenuAndTheFocusIsBackOnTheSameCover() = cases("Esc closes") { style, mode ->
        Scene(style, mode).use { s ->
            s.advance(); s.tabIntoCard()
            for ((name, open) in openKeys) {
                open(s)
                assertTrue(s.menuOpen(), "$name opens the menu of the focused card")
                val escapesBefore = s.unconsumedEscapes
                s.key(Key.Escape)
                assertEquals(escapesBefore + 1, s.unconsumedEscapes,
                    "The menu does not swallow Escape; the window hands it to Root's back ($name)")
                assertFalse(s.menuOpen(), "Escape closes the menu opened by $name")
                assertEquals("home_video_cover", s.focusedTag(), "After Escape the focus is back on the same cover ($name)")
                assertEquals(0, s.root.fallbacks, "The open menu took that back event; Root does not navigate back ($name)")
            }
            assertTrue(s.menuActions.isEmpty(), "Escape runs no menu row")
            assertTrue(s.opened.isEmpty(), "Opening and closing the menu opens no video")
        }
    }

    @TestFactory fun afterEscapeTheMenuOpensAgainAndAKeyboardRowStillRunsOnce() = cases("Esc then row") { style, mode ->
        Scene(style, mode).use { s ->
            s.advance(); s.tabIntoCard()
            s.key(Key.F10, shift = true)
            assertTrue(s.menuOpen())
            s.key(Key.Escape)
            assertFalse(s.menuOpen())
            s.key(Key.F10, shift = true)
            assertTrue(s.menuOpen(), "Shift+F10 opens the menu again after Escape closed it")
            s.key(Key.DirectionDown)
            s.key(Key.Enter)
            assertEquals(1, s.menuActions.size, "Down then Enter runs one row of the reopened menu: ${s.menuActions}")
            assertFalse(s.menuOpen(), "Picking the row closes the menu")
            assertEquals("home_video_cover", s.focusedTag(), "The cover keeps the focus after the row")
            assertTrue(s.opened.isEmpty(), "No video opens")
            assertEquals(0, s.root.fallbacks, "Only the one Escape was pressed, and the menu took it")
        }
    }

    @TestFactory fun aSecondEscapeWithTheMenuClosedIsRootBackNotTheCard() = cases("second Esc") { style, mode ->
        Scene(style, mode).use { s ->
            s.advance(); s.tabIntoCard()
            s.key(Key.F10, shift = true)
            assertTrue(s.menuOpen())
            s.key(Key.Escape)
            assertEquals(0, s.root.fallbacks, "The first Escape closes the menu only")
            s.key(Key.Escape)
            assertEquals(1, s.root.fallbacks, "With the menu closed its back handler is gone; Escape reaches Root's back")
            assertFalse(s.menuOpen(), "The second Escape does not reopen the menu")
            assertEquals("home_video_cover", s.focusedTag(), "The cover keeps the focus")
            assertTrue(s.menuActions.isEmpty() && s.opened.isEmpty(), "No row and no video")
        }
    }
}
