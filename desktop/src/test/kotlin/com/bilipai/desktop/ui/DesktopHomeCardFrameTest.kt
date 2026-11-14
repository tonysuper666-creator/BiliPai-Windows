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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import kotlin.math.max
import kotlin.math.min
import kotlin.test.*

/*
 * Slice K3 on the actual Home entry: DesktopOriginalDiscoveryVideoCard (the call of DiscoveryScreens) -> the generated
 * ElegantVideoCard -> the cover Box (home_video_cover). Everything below goes through that entry, with the production
 * arguments shape (onNotInterested and onWatchLater set, so the card has its menu), between two focusable sentinels.
 *
 * Keyboard: Tab from the sentinel before the card focuses the cover; Shift+Tab leaves it; Enter opens the video through
 * the card's tap path (onVideo once, with this bvid); Shift+F10 and the Windows menu key (AWT VK_CONTEXT_MENU, 525) open the
 * card menu, where Down and Enter pick a row, the menu closes and the cover keeps the focus. With card_interactions OFF the
 * cover is not a focus target.
 *
 * Ring pixels (Density 1, so 1 dp = 1 px), read at the horizontal centre above the cover top edge: rows 1 and 2 above
 * the edge are the transparent gap, row 3 is the inner ring (MaterialTheme background), rows 4 and 5 are the outer ring
 * (MaterialTheme primary), row 6 is the page again. Before focus and after Shift+Tab, rows 3 to 5 show the page.
 *
 * Contrast evidence: for every AppUiStyle in LIGHT and DARK the test writes primary, background and surface and the
 * WCAG contrast ratios of the ring to build/reports/k3-home-cover-ring.txt. It does not assert a ratio: the ring colours
 * are the kit focus ring tokens (spec 2), not a choice of this slice.
 *
 * Not covered: a native Windows window, real AWT key events, a screen reader. This is a headless ImageComposeScene, and
 * Escape on an open menu is not covered either: Compose 1.12 turns Escape into a back event (BackNavigationEventInput)
 * only in the AWT ComposeSceneMediator, which an ImageComposeScene does not have, so no Popup closes on Escape here.
 */
class DesktopHomeCardFrameTest {
    @TempDir lateinit var directory: Path

    private fun cases(scenario: String, body: (AppUiStyle, AppThemeMode) -> Unit) = AppUiStyle.entries.flatMap { style ->
        listOf(AppThemeMode.LIGHT, AppThemeMode.DARK).map { mode ->
            DynamicTest.dynamicTest("$scenario / ${style.name} / ${mode.name}") { body(style, mode) }
        }
    }

    private val video = VideoItem(aid = 7, bvid = "BV-k3-fixture", cid = 70, title = "K3 fixture", pic = "https://example.invalid/k3-cover.png",
        owner = Owner(mid = 9, name = "up"), stat = Stat(view = 1200, danmaku = 3))

    private class Theme(val primary: Color, val background: Color, val surface: Color)

    private inner class Scene(
        style: AppUiStyle,
        mode: AppThemeMode,
        interactions: DesktopWindowsUxCardInteractions = DesktopWindowsUxCardInteractions.ON,
    ) : AutoCloseable {
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
        val opened = mutableListOf<VideoCard>()
        val menuActions = mutableListOf<String>()
        var theme: Theme? = null
        private var last: Bitmap? = null

        init { check(GraphicsEnvironment.isHeadless()); SingletonImageLoader.setUnsafe(loader) }

        private val scene = DesktopUpstreamCalls.withOwner(owner) {
            ImageComposeScene(520, 760, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) +
                DesktopUpstreamScope(owner) + CoroutineExceptionHandler { _, error -> errors += error }) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, themeMode = mode, hapticFeedbackEnabled = false),
                    systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 520) {
                    val original = LocalWindowInfo.current
                    val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                    val ux = remember { DesktopWindowsUxMemory(DesktopWindowsUxSettings(cardInteractions = interactions)) }
                    val colors = MaterialTheme.colorScheme
                    SideEffect { theme = Theme(colors.primary, colors.background, colors.surface) }
                    CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopWindowsUx provides ux,
                        LocalDesktopUpstreamAndroidContext provides context.asAndroidContext()) {
                        Page()
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
            DesktopUpstreamCalls.withOwner(owner) {
                scene.render(time).use { image -> last?.close(); last = Bitmap.makeFromImage(image) }
            }
            scheduler.runCurrent()
            errors.firstOrNull()?.let { throw AssertionError("Home card composition failed", it) }
        } }
        private fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        private fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun cover(): Rect = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "home_video_cover" }.boundsInWindow
        fun focusedTag() = nodes().lastOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            ?.config?.getOrNull(SemanticsProperties.TestTag)
        fun hasText(text: String) = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }
        fun key(key: Key, shift: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift)); advance()
        }
        /** Focuses the sentinel before the card, then presses Tab once. */
        fun tabIntoCard() {
            key(Key.Tab)
            assertEquals("before", focusedTag(), "The first Tab must reach the sentinel before the card")
            key(Key.Tab)
        }
        /** The pixel colour (ARGB) at the horizontal centre of the cover, `rows` pixels above its top edge. */
        fun above(rows: Int): Int {
            val c = cover()
            return checkNotNull(last).getColor(((c.left + c.right) / 2f).toInt(), c.top.toInt() - rows)
        }
        fun ringRows() = (1..6).map(::above)

        override fun close() {
            last?.close(); scene.close(); repository.logout()
            SingletonImageLoader.setUnsafe(previousLoader); loader.shutdown()
        }
    }

    private fun hex(argb: Int) = "#%08X".format(argb)

    @TestFactory fun tabFocusesTheCoverAndShowsTheRingShiftTabRemovesIt() = cases("Tab ring") { style, mode ->
        Scene(style, mode).use { s ->
            s.advance()
            val theme = checkNotNull(s.theme)
            val page = theme.background.toArgb()
            assertEquals(List(6) { page }, s.ringRows(), "No ring before focus")
            s.tabIntoCard()
            assertEquals("home_video_cover", s.focusedTag(), "Tab from the sentinel before the card focuses the cover")
            val rows = s.ringRows()
            assertEquals(page, rows[0], "Row 1 above the cover is the transparent gap: ${rows.map(::hex)}")
            assertEquals(page, rows[1], "Row 2 above the cover is the transparent gap: ${rows.map(::hex)}")
            assertEquals(theme.background.toArgb(), rows[2], "Row 3 is the 1 dp inner ring (background): ${rows.map(::hex)}")
            assertEquals(theme.primary.toArgb(), rows[3], "Row 4 is the 2 dp outer ring (primary): ${rows.map(::hex)}")
            assertEquals(theme.primary.toArgb(), rows[4], "Row 5 is the 2 dp outer ring (primary): ${rows.map(::hex)}")
            assertEquals(page, rows[5], "Row 6 is the page again: the ring is 3 + 2 dp: ${rows.map(::hex)}")
            s.key(Key.Tab, shift = true)
            assertEquals("before", s.focusedTag(), "Shift+Tab leaves the cover for the sentinel before the card")
            assertEquals(List(6) { page }, s.ringRows(), "The ring goes with the focus")
            assertTrue(s.opened.isEmpty(), "Moving the focus opens nothing")
        }
    }

    @TestFactory fun enterOnTheFocusedCoverOpensTheVideoOnce() = cases("Enter") { style, mode ->
        Scene(style, mode).use { s ->
            s.advance(); s.tabIntoCard()
            assertEquals("home_video_cover", s.focusedTag())
            s.key(Key.Enter)
            assertEquals(listOf(video.bvid), s.opened.map { it.bvid }, "Enter opens this video once, through the tap path")
            assertEquals(video.cid, s.opened.single().preferredCid)
        }
    }

    @TestFactory fun shiftF10AndTheMenuKeyOpenTheCardMenuARowPickedByKeyboardClosesItAndTheCoverKeepsFocus() =
        cases("menu keys") { style, mode ->
        Scene(style, mode).use { s ->
            s.advance(); s.tabIntoCard()
            assertEquals("home_video_cover", s.focusedTag())
            assertFalse(s.hasText("稍后再看"))
            for ((name, press) in listOf<Pair<String, () -> Unit>>(
                "Shift+F10" to { s.key(Key.F10, shift = true) },
                "menu key (VK_CONTEXT_MENU 525)" to { s.key(Key(525)) },
            )) {
                val before = s.menuActions.size
                press()
                assertTrue(s.hasText("稍后再看") && s.hasText("不感兴趣"), "$name opens the card menu with its two rows")
                s.key(Key.DirectionDown)
                s.key(Key.Enter)
                assertEquals(before + 1, s.menuActions.size, "Down then Enter picks one row of the menu opened by $name")
                assertFalse(s.hasText("稍后再看"), "Picking a row closes the menu opened by $name")
                assertEquals("home_video_cover", s.focusedTag(), "After the menu closes the cover keeps the focus ($name)")
            }
            assertTrue(s.opened.isEmpty(), "The menu keys and the menu rows open no video")
        }
    }

    @TestFactory fun withCardInteractionsOffTheCoverIsNotAFocusTarget() = cases("flag OFF") { style, mode ->
        Scene(style, mode, DesktopWindowsUxCardInteractions.OFF).use { s ->
            s.advance(); s.tabIntoCard()
            assertNotEquals("home_video_cover", s.focusedTag(), "The vendored card has no cover focus")
            assertEquals(List(6) { checkNotNull(s.theme).background.toArgb() }, s.ringRows(), "No ring while OFF")
        }
    }

    @TestFactory fun ringColoursAndContrastPerThemeAreRecorded(): List<DynamicTest> {
        val report = Path.of("build/reports/k3-home-cover-ring.txt")
        Files.createDirectories(report.parent)
        Files.writeString(report, "style mode primary background surface primary:background primary:surface background:surface\n")
        return cases("ring contrast") { style, mode ->
            Scene(style, mode).use { s ->
                s.advance()
                val t = checkNotNull(s.theme)
                fun ratio(a: Color, b: Color): Double {
                    val (la, lb) = a.luminance().toDouble() to b.luminance().toDouble()
                    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
                }
                val line = "%s %s %s %s %s %.2f %.2f %.2f\n".format(style.name, mode.name, hex(t.primary.toArgb()),
                    hex(t.background.toArgb()), hex(t.surface.toArgb()), ratio(t.primary, t.background),
                    ratio(t.primary, t.surface), ratio(t.background, t.surface))
                synchronized(report) { Files.writeString(report, line, java.nio.file.StandardOpenOption.APPEND) }
                assertEquals(1f, t.primary.alpha, "The outer ring colour is opaque")
            }
        }
    }
}
