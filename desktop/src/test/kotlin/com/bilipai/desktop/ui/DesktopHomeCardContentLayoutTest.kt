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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.abs
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
 * Slice K3b on the actual Home entry: DesktopOriginalDiscoveryVideoCard (the call of DiscoveryScreens) -> the generated
 * ElegantVideoCard, with the default Home settings, the BILIPAI two column layout and the production arguments shape
 * (onNotInterested and onWatchLater set, so the card has its menu). Density 1, so 1 dp = 1 px.
 *
 * With card_interactions ON the card is three lines of content under a 16:9 cover:
 *   * cover: width / height is 16:9, its corner is 12 dp (pixel test below), play, danmaku and duration are on the cover;
 *   * title: two lines, 14 sp / 20 sp Medium (read from the real text layout of the title);
 *   * owner row: the UP name and "· <time>" on one line, the overflow button (32 dp target) on the same line at its end.
 * Every text under the cover is one of those: no "发布于", no recommendation reason, no separate stat or publish row, no like
 * count. With card_interactions OFF the card is the vendored one (the cover ratio of the layout, separate stat row, "发布于"
 * row, reason badge, the vendored title type ramp).
 *
 * Corner pixels: the cover image is one opaque colour. For a corner radius r, the pixel (d, d) from the cover's top left
 * corner is inside the rounded rectangle only when d + 0.5 >= r (1 - 1/sqrt 2). Pixel (2, 2) is outside for r >= 9 and pixel
 * (4, 4) is fully inside for r <= 14, so "(2, 2) is not the cover colour and (4, 4) is" holds for 12 and fails for the
 * vendored 16 (MIUIX card) and for 8 or less.
 *
 * Behaviour: a click on the overflow button opens the card menu; a click on the title opens the video once; a click on the
 * UP name opens that UP. The keyboard path of slice K3 (Tab ring, Enter, Shift+F10, the menu key) is DesktopHomeCardFrameTest.
 *
 * Not covered: a native Windows window, real AWT events, the 5120x2160 / 150 % machine. This is a headless ImageComposeScene.
 */
class DesktopHomeCardContentLayoutTest {
    @TempDir lateinit var directory: Path

    private fun cases(scenario: String, body: (AppUiStyle) -> Unit) = AppUiStyle.entries.map { style ->
        DynamicTest.dynamicTest("$scenario / ${style.name}") { body(style) }
    }

    private val video = VideoItem(aid = 7, bvid = "BV-k3b-fixture", cid = 70,
        title = "K3b 三行布局夹具：一个足够长、在三百二十像素宽的卡片里一定会折到第二行再被省略号截断的视频标题，用来量两行标题",
        pic = "https://example.invalid/k3b-cover.png", owner = Owner(mid = 9, name = "夹具UP主"),
        stat = Stat(view = 12345, danmaku = 678, like = 4321), duration = 125, pubdate = 1_700_000_000L,
        recommendationReason = "关注的人赞过")

    private inner class Scene(style: AppUiStyle, interactions: DesktopWindowsUxCardInteractions) : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        private var time = 0L
        private val errors = mutableListOf<Throwable>()
        private val repository = DesktopRepository(DesktopSessionStore.temporary())
        private val context = DesktopPluginContext(DesktopPluginStore(directory.resolve("plugins-${System.nanoTime()}")))
        private val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        private val previousLoader = SingletonImageLoader.get(PlatformContext.INSTANCE)
        private val imageFile = directory.resolve("k3b-cover.png").toFile().apply {
            if (!exists()) ImageIO.write(BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB).apply {
                createGraphics().run { color = java.awt.Color(0xE5, 0x39, 0x35); fillRect(0, 0, 320, 180); dispose() }
            }, "png", this)
        }
        private val loader = newImageLoader(PlatformContext.INSTANCE,
            Call.Factory { error("Image fixture must not access a socket") }, directory.resolve("cache").toFile())
            .newBuilder().components { add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                    chain.withRequest(chain.request.newBuilder().data(imageFile).build()).proceed()
            }) }.build()
        val opened = mutableListOf<VideoCard>()
        val users = mutableListOf<Long>()
        private var last: Bitmap? = null

        init { check(GraphicsEnvironment.isHeadless()); SingletonImageLoader.setUnsafe(loader) }

        private val scene = DesktopUpstreamCalls.withOwner(owner) {
            ImageComposeScene(520, 760, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) +
                DesktopUpstreamScope(owner) + CoroutineExceptionHandler { _, error -> errors += error }) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, themeMode = AppThemeMode.LIGHT,
                    hapticFeedbackEnabled = false), systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 520) {
                    val original = LocalWindowInfo.current
                    val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                    val ux = remember { DesktopWindowsUxMemory(DesktopWindowsUxSettings(cardInteractions = interactions)) }
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
                Box(Modifier.width(320.dp).testTag("card")) {
                    DesktopOriginalDiscoveryVideoCard(video, 0, layout, settings,
                        onVideo = { opened += it }, onUser = { users += it }, onPreview = {},
                        onNotInterested = {}, onWatchLater = {})
                }
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
        fun tagged(tag: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun described(text: String) = nodes().single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith(text) } == true
        }
        fun cover(): Rect = tagged("home_video_cover").boundsInWindow
        fun card(): Rect = tagged("card").boundsInWindow
        fun pixel(x: Float, y: Float) = checkNotNull(last).getColor(x.toInt(), y.toInt())
        fun textOf(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        /** Every text drawn under the cover, inside the card, in reading order. */
        fun textsUnderCover(): List<String> {
            val cover = cover(); val card = card()
            return nodes().filter { node ->
                val b = node.boundsInWindow
                textOf(node) != null && b.height > 0f && b.top >= cover.bottom && b.left >= card.left && b.right <= card.right + 1f
            }.sortedWith(compareBy({ it.boundsInWindow.top }, { it.boundsInWindow.left })).mapNotNull(::textOf)
        }
        /** Every text drawn on the cover. */
        fun textsOnCover(): List<String> {
            val cover = cover()
            return nodes().filter { node -> textOf(node) != null && cover.contains(node.boundsInWindow.center) }.mapNotNull(::textOf)
        }
        fun layoutOf(node: SemanticsNode): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(assertNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(layouts))
            return layouts.single()
        }
        fun click(point: Offset) {
            val ms = time / 1_000_000
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = ms)
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = ms + 1, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = ms + 41, buttons = PointerButtons())
            advance()
        }
        fun key(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)); advance()
        }
        fun hasText(text: String) = nodes().any { textOf(it) == text }

        override fun close() {
            last?.close(); scene.close(); repository.logout()
            SingletonImageLoader.setUnsafe(previousLoader); loader.shutdown()
        }
    }

    private fun hex(argb: Int) = "#%08X".format(argb)
    private fun ownerTime(s: Scene) = assertNotNull(s.textOf(s.tagged("home_video_owner_time")))

    @TestFactory fun coverIs16By9WithA12DpCornerAndCarriesPlayDanmakuAndDuration() = cases("cover ON") { style ->
        Scene(style, DesktopWindowsUxCardInteractions.ON).use { s ->
            s.advance()
            val c = s.cover()
            assertTrue(abs(c.width / c.height - 16f / 9f) < 0.02f, "The cover is 16:9: ${c.width} x ${c.height}")
            val inside = s.pixel((c.left + c.right) / 2f, c.top + 4f)
            assertEquals(inside, s.pixel(c.left + 4f, c.top + 4f),
                "Pixel (4, 4) of the cover is inside a 12 dp corner: ${hex(inside)}")
            assertNotEquals(inside, s.pixel(c.left + 2f, c.top + 2f),
                "Pixel (2, 2) of the cover is cut by a 12 dp corner: ${hex(inside)}")
            assertNotEquals(inside, s.pixel(c.right - 3f, c.top + 2f), "The top right corner is rounded too")
            val onCover = s.textsOnCover()
            assertTrue(onCover.any { "1.2" in it || "12345" in it }, "Play count is on the cover: $onCover")
            assertTrue(onCover.any { "678" in it }, "Danmaku count is on the cover: $onCover")
            assertTrue(onCover.any { "02:05" in it }, "Duration is on the cover: $onCover")
        }
    }

    @TestFactory fun titleIsTwoLinesOf14On20Medium() = cases("title ON") { style ->
        Scene(style, DesktopWindowsUxCardInteractions.ON).use { s ->
            s.advance()
            val node = s.described("视频标题:")
            val layout = s.layoutOf(node)
            assertEquals(14.sp, layout.layoutInput.style.fontSize, "Title size")
            assertEquals(20.sp, layout.layoutInput.style.lineHeight, "Title line height")
            assertEquals(FontWeight.Medium, layout.layoutInput.style.fontWeight, "Title weight")
            assertEquals(2, layout.lineCount, "A long title takes two lines, then ellipsis")
            assertEquals(40f, node.boundsInWindow.height, "Two lines of 20 sp")
        }
    }

    @TestFactory fun underTheCoverThereAreOnlyTheTitleAndOneOwnerRowWithTheOverflowButton() = cases("owner row ON") { style ->
        Scene(style, DesktopWindowsUxCardInteractions.ON).use { s ->
            s.advance()
            val time = ownerTime(s)
            assertTrue(time.startsWith("· ") && time.length > 2, "The owner row ends with \"· <time>\": $time")
            val under = s.textsUnderCover()
            assertEquals(listOf(video.title, video.owner.name, time, "⋮").sorted(), under.sorted(),
                "Under the cover: the title, the UP name, \"· <time>\" and ⋮ only")
            assertTrue(under.none { "发布于" in it || "关注的人赞过" in it || "4321" in it || "678" in it || "02:05" in it },
                "No \"发布于\", recommendation reason, like count, separate stat row or duration row: $under")
            val name = s.nodes().first { s.textOf(it) == video.owner.name }.boundsInWindow
            val timeBounds = s.tagged("home_video_owner_time").boundsInWindow
            assertTrue(abs(name.center.y - timeBounds.center.y) <= 1f, "The UP name and the time are one line: $name / $timeBounds")
            assertTrue(timeBounds.left >= name.right, "The time follows the UP name")
            val overflow = s.described("更多操作").boundsInWindow
            assertEquals(32f, overflow.width, "The overflow target is 32 dp wide (contract over the 24 dp glyph)")
            assertEquals(32f, overflow.height, "The overflow target is 32 dp high")
            assertTrue(overflow.top < timeBounds.bottom && overflow.bottom > timeBounds.top,
                "The overflow button is on the owner row: $overflow / $timeBounds")
            assertTrue(overflow.left >= timeBounds.right, "The overflow button is at the end of the owner row")
            val title = s.described("视频标题:").boundsInWindow
            assertTrue(title.top >= s.cover().bottom && name.top >= title.bottom, "Cover, then title, then the owner row")
        }
    }

    @TestFactory fun withCardInteractionsOffTheCardIsTheVendoredOne() = cases("flag OFF") { style ->
        Scene(style, DesktopWindowsUxCardInteractions.OFF).use { s ->
            s.advance()
            val c = s.cover()
            val vendored = resolveHomeFeedCardLayout(HomeFeedCardStyle.BILIPAI, 2, WindowWidthSizeClass.Expanded).coverAspectRatio
            assertTrue(abs(c.width / c.height - vendored) < 0.02f,
                "The OFF cover keeps the layout ratio $vendored: ${c.width} x ${c.height}")
            val under = s.textsUnderCover()
            assertTrue(under.any { it.startsWith("发布于 ") }, "The vendored publish row: $under")
            assertTrue(under.any { "678" in it }, "The vendored separate stat row: $under")
            assertTrue(s.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "home_video_owner_time" })
            // The vendored title is bodyMedium (14sp) with the 1.38 compact line height and the default weight.
            val vendoredTitle = s.layoutOf(s.described("视频标题:")).layoutInput.style
            assertNotEquals(20.sp, vendoredTitle.lineHeight, "The vendored title keeps its 1.38 line height")
            assertNotEquals(FontWeight.Medium, vendoredTitle.fontWeight, "The vendored title keeps its own weight")
            assertTrue(s.hasText("关注的人赞过"), "The vendored recommendation reason badge")
        }
    }

    @TestFactory fun overflowTitleAndOwnerClicksStillWork() = cases("clicks ON") { style ->
        Scene(style, DesktopWindowsUxCardInteractions.ON).use { s ->
            s.advance()
            assertFalse(s.hasText("稍后再看"))
            s.click(s.described("更多操作").boundsInWindow.center)
            assertTrue(s.hasText("稍后再看") && s.hasText("不感兴趣"), "The overflow button opens the card menu")
            s.key(Key.DirectionDown); s.key(Key.Enter)
            assertFalse(s.hasText("稍后再看"), "Picking a row closes the menu")
            assertTrue(s.opened.isEmpty(), "The menu opens no video")
            s.click(s.described("视频标题:").boundsInWindow.center)
            assertEquals(listOf(video.bvid), s.opened.map { it.bvid }, "A click on the title opens this video once")
            s.click(s.nodes().first { s.textOf(it) == video.owner.name }.boundsInWindow.center)
            assertEquals(listOf(video.owner.mid), s.users, "A click on the UP name opens this UP once")
        }
    }
}
