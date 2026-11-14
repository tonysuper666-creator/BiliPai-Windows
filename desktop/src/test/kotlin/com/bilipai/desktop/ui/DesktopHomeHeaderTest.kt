@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.home.UserState
import com.android.purebilibili.feature.home.components.HomeHeader
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import com.bilipai.desktop.settings.DesktopLiquidTabSettings
import com.bilipai.desktop.settings.LocalDesktopLiquidTabSettings
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/*
 * Slice K3c (spec 2.3) on the generated HomeHeader, the one DesktopOriginalHomeScreen calls, with the home_layout flag of
 * LocalDesktopWindowsUx (desktopHomeFeedLayoutKey). Density 1, so 1 dp = 1 px.
 *
 * DESKTOP: the shell rail already carries the avatar (profile) and the settings entry, so the header carries neither: no
 * clickable node on the search row other than the search entry, no "个人中心" / "设置" node, and neither onAvatarClick nor
 * onSettingsClick can be reached from any click target of the header. The search entry is in the middle of the row (its
 * centre is the centre of the scene) and at most 560 dp wide; a click and the keyboard (Tab from the element before the
 * header, then Enter) both open the search, once. The folded branch (isBottomBarSearchEnabled: avatar | tabs | action
 * row) drops the avatar and the action as well.
 *
 * CLASSIC: the vendored header, the avatar and the settings entry on both sides of the search, both reachable.
 *
 * Every case runs in a wide (1600) and a narrow (480) scene, light and dark, MATERIAL3 and MIUIX.
 *
 * BILIPAI_HOME_HEADER_OUT, when set, receives one PNG per DESKTOP / CLASSIC, wide / narrow scene (MATERIAL3, light).
 *
 * Not covered: a native Windows window, real AWT events, the 5120x2160 / 150 % machine. This is a headless ImageComposeScene.
 */
class DesktopHomeHeaderTest {
    @TempDir lateinit var directory: Path

    private val search = "搜索视频、UP主..."
    private val widths = listOf(1600, 480)
    private val modes = listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)

    private fun cases(scenario: String, body: (AppUiStyle, AppThemeMode, Int) -> Unit) =
        AppUiStyle.entries.flatMap { style -> modes.flatMap { mode -> widths.map { width ->
            DynamicTest.dynamicTest("$scenario / ${style.name} / ${mode.name} / $width") { body(style, mode, width) }
        } } }

    private inner class Scene(
        style: AppUiStyle,
        mode: AppThemeMode,
        val width: Int,
        layout: DesktopWindowsUxHomeLayout,
        settings: HomeSettings = HomeSettings(),
    ) : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        private var time = 0L
        private val errors = mutableListOf<Throwable>()
        private val repository = DesktopRepository(DesktopSessionStore.temporary())
        private val context = DesktopPluginContext(DesktopPluginStore(directory.resolve("plugins-${System.nanoTime()}")))
        private val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        private val background = object : DesktopHomeWindowBackgroundPort {
            override val isInBackground = false
            override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
            override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
        }
        /** The capabilities desktopHomeActualPlatform gives the Windows client, for this renderer. */
        private val platform = desktopDetailRenderEffectsSupported().let { effects ->
            DesktopHomePlatform(false, effects, effects, false, effects, false, background, 0f, false)
        }
        private val tabs = DesktopLiquidTabSettings(context.store)
        var avatar = 0
        var settingsClicks = 0
        var searches = 0
        private var png: ByteArray? = null

        init { check(GraphicsEnvironment.isHeadless()) }

        private val scene = DesktopUpstreamCalls.withOwner(owner) {
            ImageComposeScene(width, 360, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) +
                DesktopUpstreamScope(owner) + CoroutineExceptionHandler { _, error -> errors += error }) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, themeMode = mode, hapticFeedbackEnabled = false),
                    systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = minOf(width, 360)) {
                    val original = LocalWindowInfo.current
                    val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                    val ux = remember { DesktopWindowsUxMemory(DesktopWindowsUxSettings(homeLayout = layout)) }
                    CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopWindowsUx provides ux,
                        LocalDesktopUpstreamAndroidContext provides context.asAndroidContext(),
                        LocalDesktopHomePlatform provides platform, LocalDesktopLiquidTabSettings provides tabs) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            Box(Modifier.size(1.dp).testTag("before").focusable())
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                HomeHeader(
                                    headerOffsetProvider = { 0f },
                                    user = UserState(),
                                    onAvatarClick = { avatar++ },
                                    onSettingsClick = { settingsClicks++ },
                                    onSearchClick = { searches++ },
                                    categoryIndex = 0,
                                    onCategorySelected = {},
                                    homeSettings = settings,
                                )
                            }
                        }
                    }
                }
            }
        }

        fun advance(frames: Int = 40) { repeat(frames) {
            scheduler.runCurrent(); time += 30_000_000
            DesktopUpstreamCalls.withOwner(owner) { scene.render(time).use { png = it.encodeToData(EncodedImageFormat.PNG)?.bytes } }
            scheduler.runCurrent()
            errors.firstOrNull()?.let { throw AssertionError("Home header composition failed", it) }
        } }
        fun save(name: String) {
            val out = System.getenv("BILIPAI_HOME_HEADER_OUT")?.let { Path.of(it) } ?: return
            Files.createDirectories(out); Files.write(out.resolve("$name.png"), checkNotNull(png))
        }
        private fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun textOf(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        fun described(text: String) = nodes().filter {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d == text } == true
        }
        fun clickables() = nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null && it.boundsInWindow.width > 0f }
        /** The click target of the search entry: the smallest clickable node around the placeholder. */
        fun searchEntry(): SemanticsNode {
            val text = nodes().single { textOf(it) == search }.boundsInWindow
            return clickables().filter { it.boundsInWindow.contains(text.center) }.minBy { it.boundsInWindow.width * it.boundsInWindow.height }
        }
        /** Every clickable node whose vertical centre is on the search row. */
        fun searchRow(): List<Rect> {
            val row = searchEntry().boundsInWindow
            return clickables().map { it.boundsInWindow }.filter { it.center.y in row.top..row.bottom }
        }
        fun clickAll() = clickables().forEach { node ->
            DesktopUpstreamCalls.withOwner(owner) { assertNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action)() }
            advance(4)
        }
        fun key(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)); advance()
        }
        fun focused() = nodes().filter { it.config.getOrNull(SemanticsProperties.Focused) == true }
        fun focusBefore() {
            val node = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "before" }
            assertNotNull(node.config.getOrNull(SemanticsActions.RequestFocus)?.action)(); advance()
        }

        override fun close() { scene.close(); repository.logout() }
    }

    @TestFactory fun desktopHeaderHasNoAvatarOrSettingsEntry() = cases("DESKTOP dedup") { style, mode, width ->
        Scene(style, mode, width, DesktopWindowsUxHomeLayout.DESKTOP).use { s ->
            s.advance()
            assertEquals(listOf(s.searchEntry().boundsInWindow), s.searchRow(), "The search entry is the only click target of its row")
            assertTrue(s.described("个人中心").isEmpty() && s.described("设置").isEmpty(), "No profile or settings node")
            s.clickAll()
            assertEquals(0, s.avatar, "No click target of the header opens the profile")
            assertEquals(0, s.settingsClicks, "No click target of the header opens the settings")
            if (style == AppUiStyle.MATERIAL3 && mode == AppThemeMode.LIGHT) s.save("desktop-$width")
        }
    }

    @TestFactory fun desktopSearchIsCentredAndAtMost560Wide() = cases("DESKTOP centre") { style, mode, width ->
        Scene(style, mode, width, DesktopWindowsUxHomeLayout.DESKTOP).use { s ->
            s.advance()
            val entry = s.searchEntry().boundsInWindow
            assertTrue(abs(entry.center.x - width / 2f) <= 1f, "The search entry is centred in the $width wide row: $entry")
            assertTrue(entry.width <= 560f, "The search entry is at most 560 dp wide: $entry")
            if (width >= 1000) assertEquals(560f, entry.width, "A wide window gives the search entry its whole 560 dp")
            else assertTrue(entry.width >= width - 64f, "A narrow window gives the search entry the row less its padding: $entry")
        }
    }

    @TestFactory fun desktopSearchOpensOnClickAndFromTheKeyboard() = cases("DESKTOP search input") { style, mode, width ->
        Scene(style, mode, width, DesktopWindowsUxHomeLayout.DESKTOP).use { s ->
            s.advance()
            clickSearch(s)
            assertEquals(1, s.searches, "A click on the search entry opens the search once")
            s.focusBefore()
            assertEquals(1, s.searches)
            s.key(Key.Tab)
            val focused = s.focused().map { it.boundsInWindow }
            assertTrue(focused.any { it == s.searchEntry().boundsInWindow },
                "Tab from the element before the header lands on the search entry (no avatar before it): $focused")
            // The vendored AppSearchEntry opens the search when it takes the focus (openOnFocus), so Tab opens it once.
            assertEquals(2, s.searches, "Tab onto the search entry opens the search (vendored openOnFocus)")
            s.key(Key.Enter)
            assertEquals(3, s.searches, "Enter on the focused search entry opens the search once more")
            assertEquals(0, s.avatar + s.settingsClicks)
        }
    }

    private fun clickSearch(s: Scene) {
        assertNotNull(s.searchEntry().config.getOrNull(SemanticsActions.OnClick)?.action)(); s.advance()
    }

    @TestFactory fun desktopFoldedRowHasNoAvatarOrAction() = cases("DESKTOP folded") { style, mode, width ->
        Scene(style, mode, width, DesktopWindowsUxHomeLayout.DESKTOP, HomeSettings(isBottomBarSearchEnabled = true)).use { s ->
            s.advance()
            assertTrue(s.described("个人中心").isEmpty(), "The folded row has no profile node")
            assertTrue(s.described("设置").isEmpty(), "The folded row has no settings node")
            s.clickAll()
            assertEquals(0, s.avatar + s.settingsClicks, "No click target of the folded row opens the profile or the settings")
        }
    }

    @TestFactory fun classicKeepsTheAvatarAndTheSettingsEntry() = cases("CLASSIC") { style, mode, width ->
        Scene(style, mode, width, DesktopWindowsUxHomeLayout.CLASSIC).use { s ->
            s.advance()
            val entry = s.searchEntry().boundsInWindow
            val row = s.searchRow()
            assertEquals(3, row.size, "Avatar, search and the settings entry on the classic search row: $row")
            assertTrue(row.count { it.right <= entry.left } == 1 && row.count { it.left >= entry.right } == 1,
                "One click target on each side of the search entry: $row / $entry")
            assertEquals(1, s.described("设置").size, "The classic settings entry")
            s.clickAll()
            assertTrue(s.avatar >= 1, "The classic avatar opens the profile")
            assertTrue(s.settingsClicks >= 1, "The classic settings entry opens the settings")
            if (style == AppUiStyle.MATERIAL3 && mode == AppThemeMode.LIGHT) s.save("classic-$width")
        }
    }

    @TestFactory fun classicFoldedRowKeepsTheAvatarAndTheAction() = cases("CLASSIC folded") { style, mode, width ->
        Scene(style, mode, width, DesktopWindowsUxHomeLayout.CLASSIC, HomeSettings(isBottomBarSearchEnabled = true)).use { s ->
            s.advance()
            val profile = s.described("个人中心").single().boundsInWindow
            val settings = s.described("设置").map { it.boundsInWindow }
            assertTrue(settings.any { abs(it.center.y - profile.center.y) <= 1f && it.left > profile.right },
                "The classic folded row keeps the settings entry at the end of the profile row: $profile / $settings")
        }
    }
}
