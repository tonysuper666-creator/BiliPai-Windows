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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
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
import com.android.purebilibili.feature.home.components.cards.ElegantVideoCard
import com.android.purebilibili.feature.home.resolveHomeFeedCardLayout
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import com.bilipai.desktop.plugins.asAndroidContext
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_MENU_PADDING_DP
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
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
import kotlin.test.*

/*
 * Slice K3f on the actual Home entry: DesktopOriginalDiscoveryVideoCard (the call of DiscoveryScreens) -> the generated
 * ElegantVideoCard, with the production arguments shape (onNotInterested and onWatchLater set, so the card has its menu and
 * the three-line content of K3e), between two focusable sentinels. Density 1, so 1 dp = 1 px.
 *
 * The card menu draws one separator line in front of 不感兴趣 (spec 4), and only there:
 *   * while card_interactions is ON and the menu has the negative row and at least one of the three rows above it
 *     (稍后再看 / 取消收藏 / 复制链接), the line sits between the last of them and 不感兴趣;
 *   * the line is the kit divider: the outlineVariant role of the theme on one physical pixel row, and it spans the
 *     content width of the menu panel (168 dp minus the 6 dp padding on each side);
 *   * it is neither focusable nor clickable: a click on it closes nothing and picks nothing, and the arrow keys of the
 *     menu walk 稍后再看 -> 复制链接 -> 不感兴趣 without stopping on it;
 *   * a menu that has no row above 不感兴趣 (a direct ElegantVideoCard with only onDismiss) has no line;
 *   * with card_interactions OFF the vendored menu keeps its two rows with their vendored labels and has no line.
 *
 * The pixel expectations come from the theme that the scene renders (outlineVariant and surface, read through
 * MaterialTheme in the same composition) and from the density, not from the code under test: the line is opaque in both
 * styles and both theme modes, so the row must equal its ARGB exactly at density 1.
 *
 * Not covered: a native Windows window, real AWT pointer and key events, high DPI, a screen reader. Escape is not covered
 * either: an ImageComposeScene has no BackNavigationEventInput, so no Popup closes on Escape here (K3 frame test note).
 * This is a headless ImageComposeScene.
 */
class DesktopHomeCardMenuSeparatorTest {
    /**
     * The tag that the card side puts on the line (DESKTOP_CARD_MENU_SEPARATOR_TAG of DesktopCardContextMenu.kt). Written out
     * here, not read from there, so that this same file also runs against the card before the slice, where the constant does not
     * exist yet: there the lookup finds no node and the cases fail on the missing line instead of on a compile error. The Python
     * suite pins the literal to the declaration, so the two cannot drift apart.
     */
    private companion object {
        const val SEPARATOR_TAG = "desktop_card_menu_separator"
    }

    @TempDir lateinit var directory: Path

    private fun cases(scenario: String, body: (AppUiStyle, AppThemeMode) -> Unit) = AppUiStyle.entries.flatMap { style ->
        listOf(AppThemeMode.LIGHT, AppThemeMode.DARK).map { mode ->
            DynamicTest.dynamicTest("$scenario / ${style.name} / ${mode.name}") { body(style, mode) }
        }
    }

    /** A real video id: "BV" and ten characters, so this video has the copy row of K3e and a predecessor for the line. */
    private val bvid = "BV1xx411c7mD"

    /** Not a video id, so the card has no copy row: the menu then holds only the rows whose callbacks exist. */
    private val noLink = "BV-k3f-fixture"

    private fun video(bvid: String) = VideoItem(aid = 11, bvid = bvid, cid = 71, title = "K3f fixture",
        pic = "https://example.invalid/k3f-cover.png", owner = Owner(mid = 9, name = "up"),
        stat = Stat(view = 1200, danmaku = 3))

    private class Theme(val line: Color, val surface: Color)

    /** The clipboard boundary of the card: records every write of the copy row, so a press can be told from the others. */
    private class RecordingClipboard : DesktopTextClipboard {
        val writes = mutableListOf<String>()
        override fun copyText(value: String): Boolean {
            writes += value; return true
        }
    }

    /** Which card the page mounts: the real Home entry, or the generated card with some callbacks left out. */
    private enum class Card { ENTRY, DISMISS_AND_WATCH_LATER, DISMISS_ONLY, NO_MENU }

    private inner class Scene(
        style: AppUiStyle,
        mode: AppThemeMode,
        interactions: DesktopWindowsUxCardInteractions = DesktopWindowsUxCardInteractions.ON,
        private val card: Card = Card.ENTRY,
        videoBvid: String? = null,
    ) : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        private var time = 0L
        private val errors = mutableListOf<Throwable>()
        private val repository = DesktopRepository(DesktopSessionStore.temporary())
        private val context = DesktopPluginContext(DesktopPluginStore(directory.resolve("plugins-${System.nanoTime()}")))
        private val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        private val previousLoader = SingletonImageLoader.get(PlatformContext.INSTANCE)
        private val imageFile = directory.resolve("fixture-${System.nanoTime()}.png").toFile().apply {
            ImageIO.write(BufferedImage(160, 100, BufferedImage.TYPE_INT_ARGB), "png", this)
        }
        private val loader = newImageLoader(PlatformContext.INSTANCE,
            Call.Factory { error("Image fixture must not access a socket") }, directory.resolve("cache").toFile())
            .newBuilder().components { add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                    chain.withRequest(chain.request.newBuilder().data(imageFile).build()).proceed()
            }) }.build()
        private val item = video(videoBvid ?: if (card == Card.ENTRY) bvid else noLink)
        private var last: Bitmap? = null
        val clipboard = RecordingClipboard()
        val opened = mutableListOf<String>()
        val menuActions = mutableListOf<String>()
        var theme: Theme? = null

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
                    SideEffect { theme = Theme(colors.outlineVariant, colors.surface) }
                    CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopWindowsUx provides ux,
                        LocalDesktopTextClipboard provides clipboard,
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
                    val dismiss = { menuActions += "not-interested"; Unit }
                    val watchLater = { menuActions += "watch-later"; Unit }
                    val open = { bvid: String, _: Long -> opened += bvid; Unit }
                    when (card) {
                        Card.ENTRY -> DesktopOriginalDiscoveryVideoCard(item, 0, layout, settings,
                            onVideo = { opened += it.bvid }, onUser = {}, onPreview = {},
                            onNotInterested = dismiss, onWatchLater = watchLater)
                        Card.DISMISS_AND_WATCH_LATER -> ElegantVideoCard(video = item, index = 0, animationEnabled = false,
                            onDismiss = dismiss, onWatchLater = watchLater, onClick = open)
                        Card.DISMISS_ONLY -> ElegantVideoCard(video = item, index = 0, animationEnabled = false,
                            onDismiss = dismiss, onWatchLater = null, onClick = open)
                        Card.NO_MENU -> ElegantVideoCard(video = item, index = 0, animationEnabled = false,
                            onDismiss = null, onWatchLater = null, onClick = open)
                    }
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
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun described(text: String) = nodes().first {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith(text) } == true
        }
        fun textOf(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        fun texts() = nodes().mapNotNull(::textOf)
        fun hasText(text: String) = texts().any { it == text }
        fun focusedTag() = nodes().lastOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            ?.config?.getOrNull(SemanticsProperties.TestTag)
        /** The nodes that carry the separator tag: empty when the menu draws no line. */
        fun separators() = nodes().filter {
            it.config.getOrNull(SemanticsProperties.TestTag) == SEPARATOR_TAG
        }
        /** The bounds of the one separator line: fails when the menu draws none, or more than one. */
        fun separator(): Rect = separators().single().boundsInWindow
        fun boundsOf(text: String): Rect = nodes().first { textOf(it) == text }.boundsInWindow
        /** The centre of the menu row with this exact text, the row of the open card menu. */
        fun row(text: String): Offset = boundsOf(text).center
        fun pixel(x: Int, y: Int) = checkNotNull(last).getColor(x, y)
        fun key(key: Key, shift: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift)); advance()
        }
        fun click(point: Offset) {
            val ms = time / 1_000_000
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = ms)
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = ms + 1, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = ms + 41, buttons = PointerButtons())
            advance()
        }
        /** Focuses the sentinel before the card, then presses Tab once. */
        fun tabIntoCard() {
            key(Key.Tab)
            assertEquals("before", focusedTag(), "The first Tab must reach the sentinel before the card")
            key(Key.Tab)
        }

        override fun close() {
            last?.close(); scene.close(); repository.logout()
            SingletonImageLoader.setUnsafe(previousLoader); loader.shutdown()
        }
    }

    private fun hex(argb: Int) = "#%08X".format(argb)

    /** The ARGB the single pixel row must have: the outlineVariant role, composited over the panel if it carries alpha. */
    private fun lineArgb(theme: Theme) = if (theme.line.alpha >= 1f) theme.line.toArgb()
        else theme.line.compositeOver(theme.surface).toArgb()

    @TestFactory fun theLineSitsBetweenTheCopyRowAndNotInterestedAndPaintsOnePhysicalRow() = cases("line geometry") { style, mode ->
        Scene(style = style, mode = mode).use { s ->
            s.advance(); s.tabIntoCard()
            assertEquals("home_video_cover", s.focusedTag())
            s.key(Key.F10, shift = true)
            assertTrue(s.hasText("稍后再看") && s.hasText("复制链接（Ctrl C）") && s.hasText("不感兴趣"),
                "The card menu has its three rows while ON: ${s.texts()}")
            assertEquals(1, s.separators().size, "The menu draws exactly one line: ${s.texts()}")

            val theme = checkNotNull(s.theme)
            val sep = s.separator()
            val watch = s.boundsOf("稍后再看")
            val copy = s.boundsOf("复制链接（Ctrl C）")
            val dismiss = s.boundsOf("不感兴趣")
            assertTrue(watch.top < copy.top, "稍后再看 is above the copy row: $watch / $copy")
            assertTrue(copy.bottom <= sep.top, "The line follows the copy row: $copy / $sep")
            assertTrue(sep.bottom <= dismiss.top, "不感兴趣 follows the line: $sep / $dismiss")

            assertEquals(1f, sep.height, "The line is one physical pixel at density 1: $sep")
            assertEquals((DESKTOP_CARD_MENU_WIDTH_DP - 2 * DESKTOP_KIT_MENU_PADDING_DP).toFloat(), sep.width,
                "The line spans the menu content width (168 dp panel minus 6 dp padding twice): $sep")

            val line = lineArgb(theme)
            val x = ((sep.left + sep.right) / 2f).toInt()
            val rows = (sep.top.toInt() until sep.bottom.toInt()).toList()
            assertEquals(1, rows.size, "The line covers one pixel row at density 1: $sep")
            for (y in rows) assertEquals(line, s.pixel(x, y),
                "Row $y of the line is the outlineVariant role ${hex(line)} in ${style.name}/${mode.name}: got ${hex(s.pixel(x, y))}")
            assertEquals(theme.surface.toArgb(), s.pixel(x, sep.top.toInt() - 1),
                "The row above the line is the menu panel: ${hex(s.pixel(x, sep.top.toInt() - 1))}")
            assertEquals(theme.surface.toArgb(), s.pixel(x, sep.bottom.toInt()),
                "The row below the line is the menu panel: ${hex(s.pixel(x, sep.bottom.toInt()))}")

            assertNull(s.separators().single().config.getOrNull(SemanticsProperties.Focused),
                "The line is not focusable")
            assertNull(s.separators().single().config.getOrNull(SemanticsActions.OnClick),
                "The line has no click action")

            s.click(sep.center)
            assertTrue(s.hasText("不感兴趣"), "A click on the line leaves the menu open")
            assertTrue(s.menuActions.isEmpty(), "A click on the line picks no row: ${s.menuActions}")
            assertTrue(s.clipboard.writes.isEmpty(), "A click on the line is not the copy row")
            assertTrue(s.opened.isEmpty(), "A click on the line opens no video")

            s.click(s.row("不感兴趣"))
            assertEquals(listOf("not-interested"), s.menuActions, "不感兴趣 runs its callback once")
            assertFalse(s.hasText("不感兴趣"), "Picking 不感兴趣 closes the menu")
            assertEquals("home_video_cover", s.focusedTag(), "After the menu closes the cover keeps the focus")
            assertTrue(s.clipboard.writes.isEmpty() && s.opened.isEmpty(), "Picking a row writes nothing and opens no video")
        }
    }

    @TestFactory fun aMenuWithARowAboveNotInterestedHasTheLineEvenWithoutTheCopyRow() = cases("watch later only") { style, mode ->
        Scene(style = style, mode = mode, card = Card.DISMISS_AND_WATCH_LATER).use { s ->
            s.advance()
            s.click(s.described("更多操作").boundsInWindow.center)
            assertTrue(s.hasText("稍后再看") && s.hasText("不感兴趣"),
                "The direct card menu has its two rows: ${s.texts()}")
            assertFalse(s.texts().any { "复制链接" in it }, "This card has no copy row: ${s.texts()}")
            val sep = s.separator()
            assertTrue(s.boundsOf("稍后再看").bottom <= sep.top, "The line follows 稍后再看")
            assertTrue(sep.bottom <= s.boundsOf("不感兴趣").top, "The line precedes 不感兴趣")
            assertTrue(s.menuActions.isEmpty(), "Opening the menu picks no row")
        }
    }

    @TestFactory fun aMenuWithoutARowAboveNotInterestedHasNoLine() = cases("no predecessor") { style, mode ->
        for (card in listOf(Card.DISMISS_ONLY, Card.NO_MENU)) {
            Scene(style = style, mode = mode, card = card).use { s ->
                s.advance()
                val more = s.nodes().filter {
                    it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("更多操作")
                }
                assertEquals(if (card == Card.NO_MENU) 0 else 1, more.size, "$card: the overflow button is there iff a row exists")
                if (more.isNotEmpty()) s.click(more.single().boundsInWindow.center)
                assertEquals(if (card == Card.NO_MENU) 0 else 1,
                    s.nodes().count { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "不感兴趣" } == true },
                    "$card: 不感兴趣 is drawn only when it has a callback")
                assertTrue(s.separators().isEmpty(), "$card: no line without a row above 不感兴趣: ${s.texts()}")
                assertTrue(s.menuActions.isEmpty(), "$card: no menu row was picked")
            }
        }
    }

    @TestFactory fun arrowKeysWalkTheRowsAndSkipTheLine() = cases("arrow keys") { style, mode ->
        Scene(style = style, mode = mode).use { s ->
            s.advance(); s.tabIntoCard()
            assertEquals("home_video_cover", s.focusedTag())

            s.key(Key.F10, shift = true)
            assertEquals(1, s.separators().size, "The line is in the menu the arrows walk")
            s.key(Key.DirectionDown); s.key(Key.Enter)
            assertEquals(listOf("watch-later"), s.menuActions, "Down then Enter reaches the first row, 稍后再看")
            assertTrue(s.clipboard.writes.isEmpty(), "The first row is not the copy row")
            assertFalse(s.hasText("不感兴趣"), "Picking it closes the menu")
            assertEquals("home_video_cover", s.focusedTag(), "The cover keeps the focus")

            s.key(Key.F10, shift = true); s.key(Key.DirectionDown); s.key(Key.DirectionDown); s.key(Key.Enter)
            assertEquals(listOf("https://www.bilibili.com/video/$bvid"), s.clipboard.writes,
                "Two Downs reach the copy row, one press past 稍后再看")
            assertEquals(listOf("watch-later"), s.menuActions, "The copy row calls neither watch later nor dismiss")
            assertFalse(s.hasText("不感兴趣"), "Picking the copy row closes the menu")
            assertEquals("home_video_cover", s.focusedTag(), "The cover keeps the focus after the copy row")

            s.key(Key.F10, shift = true)
            s.key(Key.DirectionDown); s.key(Key.DirectionDown); s.key(Key.DirectionDown); s.key(Key.Enter)
            assertEquals(listOf("watch-later", "not-interested"), s.menuActions,
                "Three Downs reach 不感兴趣: the line between the copy row and it is skipped")
            assertEquals(1, s.clipboard.writes.size, "The third row is not the copy row")
            assertFalse(s.hasText("不感兴趣"), "Picking 不感兴趣 closes the menu")
            assertEquals("home_video_cover", s.focusedTag(), "The cover keeps the focus after 不感兴趣")
            assertTrue(s.opened.isEmpty(), "The arrow keys and their rows open no video")
        }
    }

    @TestFactory fun withCardInteractionsOffTheVendoredMenuKeepsItsRowsAndHasNoLine() = cases("flag OFF") { style, mode ->
        Scene(style = style, mode = mode, interactions = DesktopWindowsUxCardInteractions.OFF).use { s ->
            s.advance()
            s.click(s.described("更多操作").boundsInWindow.center)
            assertTrue(s.hasText("🕐 稍后再看") && s.hasText("🚫 不感兴趣"),
                "The vendored menu keeps its two rows with their vendored labels: ${s.texts()}")
            assertTrue(s.separators().isEmpty(), "The vendored menu has no line: ${s.texts()}")
            s.click(s.row("🚫 不感兴趣"))
            assertEquals(listOf("not-interested"), s.menuActions, "The vendored row keeps its behaviour")
            assertFalse(s.hasText("不感兴趣"), "Picking it closes the vendored menu")
            assertTrue(s.opened.isEmpty(), "No playback")
        }
    }
}
