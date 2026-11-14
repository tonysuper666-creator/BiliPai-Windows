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
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import kotlin.math.abs
import kotlin.test.*

/*
 * Slice K3d on the actual Home entry: DesktopOriginalDiscoveryVideoCard (the call of DiscoveryScreens) -> the generated
 * ElegantVideoCard, with the production arguments shape (onNotInterested and onWatchLater set), between two focusable
 * sentinels. The page sits in DesktopDetailWindow(precisePointerConnected = true), the input flags of a Windows window, so
 * the vendored adaptiveCardHoverEffect is live wherever it is still called: with card_interactions OFF it scales the card
 * by 1.025 on hover, and this test shows that it still does; with ON the card must keep its bounds.
 *
 * Pixels (Density 1, so 1 dp = 1 px), read on the page left of the card, at the vertical centre of the cover:
 * 4 px left of the card is inside the 8 dp outset of the hover wash (primary at 8 % over the page), 12 px left is outside it.
 * One pixel inside the top-left corner of the outset rectangle stays the page: the wash is rounded.
 *
 * Quick actions: found by their accessible names (稍后再看, 更多), 32 x 32, 8 dp from the top and right edges of the cover,
 * 4 dp apart. A click on either runs its callback and opens no video; 更多 opens the card's existing menu once. With a
 * callback missing (the generated ElegantVideoCard called directly) the button for it is not there.
 *
 * Not covered: a native Windows window, real AWT pointer and key events, the system cursor shape (the hand cursor is the
 * H2 desktopCardHandCursor, unchanged here), a screen reader, high DPI. This is a headless ImageComposeScene, so the two
 * PNGs it writes (build/reports/k3d-home-card-hover-*.png) are headless renders, not GUI evidence.
 */
class DesktopHomeCardHoverTest {
    @TempDir lateinit var directory: Path

    private fun cases(scenario: String, body: (AppUiStyle, AppThemeMode) -> Unit) = AppUiStyle.entries.flatMap { style ->
        listOf(AppThemeMode.LIGHT, AppThemeMode.DARK).map { mode ->
            DynamicTest.dynamicTest("$scenario / ${style.name} / ${mode.name}") { body(style, mode) }
        }
    }

    private val video = VideoItem(aid = 7, bvid = "BV-k3d-fixture", cid = 70, title = "K3d fixture",
        pic = "https://example.invalid/k3d-cover.png", owner = Owner(mid = 9, name = "up"), stat = Stat(view = 1200, danmaku = 3))

    private class Theme(val primary: Color, val background: Color)

    /** Which card the page mounts: the real Home entry, or the generated card with some callbacks left out. */
    private enum class Card { ENTRY, DISMISS_ONLY, WATCH_LATER_ONLY, NO_MENU }

    private inner class Scene(
        style: AppUiStyle,
        mode: AppThemeMode,
        interactions: DesktopWindowsUxCardInteractions = DesktopWindowsUxCardInteractions.ON,
        private val card: Card = Card.ENTRY,
        width: Int = 520,
        private val cardWidth: Int = 320,
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
        val opened = mutableListOf<String>()
        val menuActions = mutableListOf<String>()
        var theme: Theme? = null
        private var last: Bitmap? = null

        init { check(GraphicsEnvironment.isHeadless()); SingletonImageLoader.setUnsafe(loader) }

        private val scene = DesktopUpstreamCalls.withOwner(owner) {
            ImageComposeScene(width, 760, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) +
                DesktopUpstreamScope(owner) + CoroutineExceptionHandler { _, error -> errors += error }) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, themeMode = mode, hapticFeedbackEnabled = false),
                    systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = width) {
                    val original = LocalWindowInfo.current
                    val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                    val ux = remember { DesktopWindowsUxMemory(DesktopWindowsUxSettings(cardInteractions = interactions)) }
                    val colors = MaterialTheme.colorScheme
                    SideEffect { theme = Theme(colors.primary, colors.background) }
                    CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopWindowsUx provides ux,
                        LocalDesktopUpstreamAndroidContext provides context.asAndroidContext()) {
                        DesktopDetailWindow(precisePointerConnected = true) { Page() }
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
                Box(Modifier.width(cardWidth.dp)) {
                    val dismiss = { menuActions += "not-interested"; Unit }
                    val watchLater = { menuActions += "watch-later"; Unit }
                    val open = { bvid: String, _: Long -> opened += bvid; Unit }
                    when (card) {
                        Card.ENTRY -> DesktopOriginalDiscoveryVideoCard(video, 0, layout, settings,
                            onVideo = { opened += it.bvid }, onUser = {}, onPreview = {},
                            onNotInterested = dismiss, onWatchLater = watchLater)
                        Card.DISMISS_ONLY -> ElegantVideoCard(video = video, index = 0, animationEnabled = false,
                            onDismiss = dismiss, onWatchLater = null, onClick = open)
                        Card.WATCH_LATER_ONLY -> ElegantVideoCard(video = video, index = 0, animationEnabled = false,
                            onDismiss = null, onWatchLater = watchLater, onClick = open)
                        Card.NO_MENU -> ElegantVideoCard(video = video, index = 0, animationEnabled = false,
                            onDismiss = null, onWatchLater = null, onClick = open)
                    }
                }
                Box(Modifier.size(40.dp).testTag("after").focusable())
            }
        }

        private fun ms() = time / 1_000_000

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
        /** The nodes whose accessible name is exactly [label]: the quick action buttons. */
        fun named(label: String) = nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(label) }
        fun button(label: String): Rect = named(label).single().boundsInWindow
        fun hasButtons() = named(WATCH_LATER).isNotEmpty() || named(MORE).isNotEmpty()
        fun textCount(text: String) = nodes().count { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }
        private fun focused() = nodes().lastOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
        fun focusedTag() = focused()?.config?.getOrNull(SemanticsProperties.TestTag)
        fun focusedName() = focused()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()
        fun key(key: Key, shift: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift)); advance()
        }
        fun tabIntoCard() {
            key(Key.Tab)
            assertEquals("before", focusedTag(), "The first Tab must reach the sentinel before the card")
            key(Key.Tab)
        }
        fun move(point: Offset) { scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = ms()); advance() }
        fun click(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = ms())
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = ms() + 1, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = ms() + 41, buttons = PointerButtons())
            advance()
        }
        /** The page point left of the card at the vertical centre of the cover. The card starts at the cover's left edge. */
        fun leftOfCard(px: Int): Int {
            val c = cover()
            return pixel(c.left.toInt() - px, ((c.top + c.bottom) / 2f).toInt())
        }
        fun pixel(x: Int, y: Int) = checkNotNull(last).getColor(x, y)
        fun png(path: Path) = DesktopUpstreamCalls.withOwner(owner) {
            scene.render(time).use { image -> Files.write(path, image.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
        }

        override fun close() {
            last?.close(); scene.close(); repository.logout()
            SingletonImageLoader.setUnsafe(previousLoader); loader.shutdown()
        }
    }

    private fun hex(argb: Int) = "#%08X".format(argb)

    private fun assertNear(expected: Int, actual: Int, message: String) {
        val near = (0..24 step 8).all { shift -> abs((expected ushr shift and 0xFF) - (actual ushr shift and 0xFF)) <= 2 }
        assertTrue(near, "$message: expected ${hex(expected)} (each channel within 2), got ${hex(actual)}")
    }

    private val outside = Offset(4f, 4f)

    @TestFactory fun hoverWashesTheWholeCardWithAnEightDpRoundedOutsetAndKeepsItsBounds() = cases("hover wash") { style, mode ->
        Scene(style, mode).use { s ->
            s.advance()
            val theme = checkNotNull(s.theme)
            val page = theme.background.toArgb()
            val wash = theme.primary.copy(alpha = 0.08f).compositeOver(theme.background).toArgb()
            val before = s.cover()
            assertEquals(page, s.leftOfCard(4), "No wash before the pointer enters")
            s.move(before.center)
            assertEquals(before, s.cover(), "Hover neither moves nor scales the card")
            assertNear(wash, s.leftOfCard(4), "4 px left of the card is the wash, 8 % primary over the page")
            assertNear(wash, s.pixel(before.left.toInt() - 4, before.top.toInt() + 24), "The wash covers the card top too")
            assertEquals(page, s.leftOfCard(12), "12 px left of the card is outside the 8 dp outset")
            assertEquals(page, s.pixel(before.left.toInt() - 7, before.top.toInt() - 7), "The outset corner is rounded")
            s.move(outside)
            assertEquals(before, s.cover(), "Leaving moves nothing either")
            assertEquals(page, s.leftOfCard(4), "The wash goes when the pointer leaves")
            assertTrue(s.opened.isEmpty(), "Hovering opens nothing")
        }
    }

    @TestFactory fun hoverShowsTwoThirtyTwoDpQuickActionsAtTheTopRightOfTheCover() = cases("quick action geometry") { style, mode ->
        Scene(style, mode).use { s ->
            s.advance()
            assertFalse(s.hasButtons(), "No quick action before hover or focus")
            val cover = s.cover()
            s.move(cover.center)
            val more = s.button(MORE)
            val later = s.button(WATCH_LATER)
            assertEquals(Rect(cover.right - 8 - 32, cover.top + 8, cover.right - 8, cover.top + 8 + 32), more,
                "更多 is 32 x 32, 8 dp from the top and right of the cover")
            assertEquals(Rect(more.left - 4 - 32, more.top, more.left - 4, more.bottom), later,
                "稍后再看 is 32 x 32, 4 dp left of 更多")
            s.move(outside)
            assertFalse(s.hasButtons(), "The quick actions go with the pointer")
        }
    }

    @TestFactory fun clickingWatchLaterRunsTheOriginalCallbackOnceAndOpensNoVideo() = cases("click watch later") { style, mode ->
        Scene(style, mode).use { s ->
            s.advance(); s.move(s.cover().center)
            s.click(s.button(WATCH_LATER).center)
            assertEquals(listOf("watch-later"), s.menuActions, "稍后再看 runs onWatchLater once")
            assertTrue(s.opened.isEmpty(), "The click does not reach the cover's tap path")
            assertEquals(0, s.textCount("不感兴趣"), "稍后再看 does not open the menu")
        }
    }

    @TestFactory fun clickingMoreOpensTheExistingCardMenuOnceAndOpensNoVideo() = cases("click more") { style, mode ->
        Scene(style, mode).use { s ->
            s.advance(); s.move(s.cover().center)
            assertEquals(0, s.textCount("不感兴趣"))
            s.click(s.button(MORE).center)
            assertEquals(1, s.textCount("不感兴趣"), "更多 opens the card menu, once")
            assertTrue(s.opened.isEmpty(), "The click does not reach the cover's tap path")
            assertTrue(s.menuActions.isEmpty(), "Opening the menu picks no row")
        }
    }

    @TestFactory fun keyboardFocusOnTheCoverShowsTheQuickActionsAndTabReachesBoth() = cases("keyboard") { style, mode ->
        Scene(style, mode).use { s ->
            s.advance()
            assertFalse(s.hasButtons())
            s.tabIntoCard()
            assertEquals("home_video_cover", s.focusedTag(), "Tab from the sentinel before the card focuses the cover")
            assertTrue(s.named(WATCH_LATER).size == 1 && s.named(MORE).size == 1, "Cover focus shows both quick actions")
            s.key(Key.Tab)
            assertEquals(WATCH_LATER, s.focusedName(), "Tab from the cover reaches 稍后再看")
            assertTrue(s.named(WATCH_LATER).size == 1 && s.named(MORE).size == 1, "The cover lost the focus to its button: both stay")
            s.key(Key.Enter)
            assertEquals(listOf("watch-later"), s.menuActions, "Enter on 稍后再看 runs onWatchLater once")
            assertTrue(s.opened.isEmpty(), "Enter on a quick action does not open the video")
            s.key(Key.Tab)
            assertEquals(MORE, s.focusedName(), "Tab from 稍后再看 reaches 更多")
            s.key(Key.Tab)
            assertTrue(s.focusedName() !in listOf(WATCH_LATER, MORE) && s.focusedTag() != "home_video_cover",
                "Tab from 更多 leaves the quick actions and the cover")
            assertFalse(s.hasButtons(), "The quick actions go with the focus")
            // Some styles keep focusable rows of their own in the info area; Tab walks through them to the sentinel.
            repeat(4) { if (s.focusedTag() != "after") s.key(Key.Tab) }
            assertEquals("after", s.focusedTag(), "Tab goes on to the sentinel after the card")
            assertFalse(s.hasButtons())
        }
    }

    @TestFactory fun enterOnFocusedMoreOpensTheCardMenuOnce() = cases("keyboard more") { style, mode ->
        Scene(style, mode).use { s ->
            s.advance(); s.tabIntoCard(); s.key(Key.Tab); s.key(Key.Tab)
            assertEquals(MORE, s.focusedName())
            s.key(Key.Enter)
            assertEquals(1, s.textCount("不感兴趣"), "Enter on 更多 opens the card menu, once")
            assertTrue(s.opened.isEmpty() && s.menuActions.isEmpty(), "Enter on 更多 opens no video and picks no row")
        }
    }

    @Test fun aMissingCallbackShowsNoButtonForIt() {
        for ((card, expected) in listOf(
            Card.DISMISS_ONLY to listOf(MORE),
            Card.WATCH_LATER_ONLY to listOf(WATCH_LATER, MORE),
            Card.NO_MENU to emptyList(),
        )) Scene(AppUiStyle.entries.first(), AppThemeMode.LIGHT, card = card).use { s ->
            s.advance(); s.move(s.cover().center)
            assertEquals(expected, listOf(WATCH_LATER, MORE).filter { s.named(it).isNotEmpty() },
                "$card: only the actions with a callback are shown")
            s.move(outside); s.tabIntoCard()
            assertEquals(expected, listOf(WATCH_LATER, MORE).filter { s.named(it).isNotEmpty() },
                "$card: the same actions show on keyboard focus")
        }
    }

    @TestFactory fun withCardInteractionsOffTheVendoredHoverStaysAndNoQuickActionAppears() = cases("flag OFF") { style, mode ->
        Scene(style, mode, DesktopWindowsUxCardInteractions.OFF).use { s ->
            s.advance()
            val before = s.cover()
            s.move(before.center)
            assertFalse(s.hasButtons(), "No quick action while OFF")
            val hovered = s.cover()
            assertTrue(hovered.width > before.width, "OFF keeps the vendored hover scale (1.025): $before -> $hovered")
            assertEquals(1.025f, hovered.width / before.width, 0.002f, "The vendored scale factor")
            s.tabIntoCard()
            assertFalse(s.hasButtons(), "No quick action on keyboard focus while OFF")
        }
    }

    @Test fun headlessRendersAtADesktopAndAMobileWidthAreWritten() {
        val report = Path.of("build/reports")
        Files.createDirectories(report)
        for ((name, width, cardWidth) in listOf(Triple("desktop", 520, 320), Triple("mobile", 390, 342))) {
            Scene(AppUiStyle.entries.first(), AppThemeMode.LIGHT, width = width, cardWidth = cardWidth).use { s ->
                s.advance(); s.move(s.cover().center)
                assertTrue(s.named(MORE).isNotEmpty(), "The $name render shows the hovered card")
                s.png(report.resolve("k3d-home-card-hover-$name.png"))
            }
        }
    }

    private companion object {
        const val WATCH_LATER = "稍后再看"
        const val MORE = "更多"
    }
}
