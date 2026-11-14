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
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/*
 * Slice K3e on the actual Home entry: DesktopOriginalDiscoveryVideoCard (the call of DiscoveryScreens) -> the generated
 * ElegantVideoCard, with the production arguments shape (onNotInterested and onWatchLater set, so the card has its menu),
 * between two focusable sentinels. Density 1, so 1 dp = 1 px.
 *
 * The card writes the link of its video, https://www.bilibili.com/video/<bvid>, once through the clipboard boundary it is
 * given (LocalDesktopTextClipboard / DesktopTextClipboard.copyText), from two places and nowhere else:
 *   * the row "复制链接（Ctrl C）" of the card menu, after the vendored rows and before 不感兴趣: it closes the menu, the
 *     cover keeps the focus, and it plays nothing and calls neither watch later nor dismiss;
 *   * Ctrl+C on the cover while it has the keyboard focus, which is the same write with no menu involved.
 * A bvid that is not a video id ("BV" and ten characters) has no link: the row is not shown and Ctrl+C writes nothing.
 * A write the boundary refuses stays a refusal: one attempt, no "已复制" text, and the menu still closes.
 * The shortcut is read by the focused cover alone, so Ctrl+C on another focusable of the page and Ctrl+Shift+C and
 * Ctrl+Alt+C on the cover write nothing. With card_interactions OFF the vendored menu keeps its two rows and its
 * behaviour, has no copy row, and Ctrl+C writes nothing.
 *
 * The clipboard is a recording fake, so "once" is a count of calls to the boundary, not a read of the system clipboard.
 * Not covered: a native Windows window, the AWT clipboard of the real WindowsTextClipboard, a selection in a text field.
 * This is a headless ImageComposeScene.
 */
class DesktopHomeCardCopyLinkTest {
    @TempDir lateinit var directory: Path

    private fun cases(scenario: String, body: (AppUiStyle, AppThemeMode) -> Unit) = AppUiStyle.entries.flatMap { style ->
        listOf(AppThemeMode.LIGHT, AppThemeMode.DARK).map { mode ->
            DynamicTest.dynamicTest("$scenario / ${style.name} / ${mode.name}") { body(style, mode) }
        }
    }

    /** A real video id: "BV" and ten characters, so this video has a link. */
    private val bvid = "BV1xx411c7mD"

    private fun video(bvid: String) = VideoItem(aid = 11, bvid = bvid, cid = 71, title = "K3e fixture",
        pic = "https://example.invalid/k3e-cover.png", owner = Owner(mid = 9, name = "up"),
        stat = Stat(view = 1200, danmaku = 3))

    /** The clipboard boundary of the card: records every write and accepts or refuses it. */
    private class RecordingClipboard(private val accept: Boolean) : DesktopTextClipboard {
        val writes = mutableListOf<String>()
        override fun copyText(value: String): Boolean {
            writes += value
            return accept
        }
    }

    private inner class Scene(
        videoBvid: String? = null,
        style: AppUiStyle,
        mode: AppThemeMode,
        interactions: DesktopWindowsUxCardInteractions = DesktopWindowsUxCardInteractions.ON,
        acceptWrites: Boolean = true,
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
        private val item = video(videoBvid ?: bvid)
        private var last: Bitmap? = null
        val clipboard = RecordingClipboard(acceptWrites)
        val opened = mutableListOf<VideoCard>()
        val menuActions = mutableListOf<String>()

        init { check(GraphicsEnvironment.isHeadless()); SingletonImageLoader.setUnsafe(loader) }

        private val scene = DesktopUpstreamCalls.withOwner(owner) {
            ImageComposeScene(520, 760, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) +
                DesktopUpstreamScope(owner) + CoroutineExceptionHandler { _, error -> errors += error }) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, themeMode = mode, hapticFeedbackEnabled = false),
                    systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 520) {
                    val original = LocalWindowInfo.current
                    val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                    val ux = remember { DesktopWindowsUxMemory(DesktopWindowsUxSettings(cardInteractions = interactions)) }
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
                    DesktopOriginalDiscoveryVideoCard(item, 0, layout, settings,
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
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun described(text: String) = nodes().first {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith(text) } == true
        }
        fun textOf(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        fun texts() = nodes().mapNotNull(::textOf)
        fun hasText(text: String) = texts().any { it == text }
        fun focusedTag() = nodes().lastOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            ?.config?.getOrNull(SemanticsProperties.TestTag)
        /** The centre of the menu row with this exact text, the row of the open card menu. */
        fun row(text: String): Offset = nodes().first { textOf(it) == text }.boundsInWindow.center
        fun key(key: Key, ctrl: Boolean = false, shift: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl, isShiftPressed = shift))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl, isShiftPressed = shift)); advance()
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

    private fun link(bvid: String) = "https://www.bilibili.com/video/$bvid"

    @TestFactory fun theMenuRowWritesTheLinkOnceClosesAndTheCoverKeepsTheFocus() = cases("menu row") { style, mode ->
        Scene(style = style, mode = mode).use { s ->
            s.advance(); s.tabIntoCard()
            assertEquals("home_video_cover", s.focusedTag())
            assertFalse(s.texts().any { "复制链接" in it }, "The copy row is not drawn before the menu opens")
            s.key(Key.F10, shift = true)
            assertTrue(s.hasText("复制链接（Ctrl C）"), "The card menu has the copy row while ON: ${s.texts()}")
            s.click(s.row("复制链接（Ctrl C）"))
            assertEquals(listOf(link(bvid)), s.clipboard.writes, "The row writes this video's link exactly once")
            assertFalse(s.hasText("复制链接（Ctrl C）"), "Picking the copy row closes the menu")
            assertTrue(s.opened.isEmpty(), "The copy row opens no video")
            assertTrue(s.menuActions.isEmpty(), "The copy row calls neither watch later nor dismiss")
            assertEquals("home_video_cover", s.focusedTag(), "After the menu closes the cover keeps the focus")
        }
    }

    @TestFactory fun theOverflowButtonOpensTheSameRowAndItWritesOnce() = cases("menu by pointer") { style, mode ->
        Scene(style = style, mode = mode).use { s ->
            s.advance()
            s.click(s.described("更多操作").boundsInWindow.center)
            assertTrue(s.hasText("复制链接（Ctrl C）") && s.hasText("稍后再看"),
                "The overflow button opens the card menu with the copy row: ${s.texts()}")
            s.click(s.row("复制链接（Ctrl C）"))
            assertEquals(listOf(link(bvid)), s.clipboard.writes, "A click on the copy row writes the link once")
            assertFalse(s.hasText("复制链接（Ctrl C）"), "The menu closes")
            assertTrue(s.opened.isEmpty(), "No playback")
            assertTrue(s.menuActions.isEmpty(), "No watch later and no dismiss")
        }
    }

    @TestFactory fun ctrlCOnTheFocusedCoverWritesTheLinkOnceAndPlaysNothing() = cases("cover Ctrl+C") { style, mode ->
        Scene(style = style, mode = mode).use { s ->
            s.advance(); s.tabIntoCard()
            assertEquals("home_video_cover", s.focusedTag())
            s.key(Key.C, ctrl = true)
            assertEquals(listOf(link(bvid)), s.clipboard.writes, "Ctrl+C on the focused cover writes the link once")
            assertFalse(s.hasText("稍后再看"), "Ctrl+C opens no menu")
            assertTrue(s.opened.isEmpty(), "Ctrl+C opens no video")
            assertTrue(s.menuActions.isEmpty(), "Ctrl+C calls neither watch later nor dismiss")
            s.key(Key.C, ctrl = true)
            assertEquals(2, s.clipboard.writes.size, "A second Ctrl+C writes once more, not twice")
        }
    }

    @TestFactory fun ctrlCOutsideTheCardAndModifiedCtrlCDoNotWrite() = cases("Ctrl+C elsewhere") { style, mode ->
        Scene(style = style, mode = mode).use { s ->
            s.advance()
            s.key(Key.Tab)
            assertEquals("before", s.focusedTag(), "The first Tab reaches the sentinel before the card")
            s.key(Key.C, ctrl = true)
            assertTrue(s.clipboard.writes.isEmpty(), "Ctrl+C on a focusable outside the card writes nothing")
            s.key(Key.Tab)
            assertEquals("home_video_cover", s.focusedTag())
            s.key(Key.C, ctrl = true, shift = true)
            s.key(Key.C, ctrl = true)
            assertEquals(listOf(link(bvid)), s.clipboard.writes, "Ctrl+Shift+C is not the shortcut; plain Ctrl+C is")
            s.key(Key.C)
            assertEquals(1, s.clipboard.writes.size, "C alone writes nothing")
        }
    }

    @TestFactory fun aBvidThatIsNotAVideoIdHasNoRowAndCtrlCWritesNothing() = cases("no link") { style, mode ->
        for (bad in listOf("BV-k3e-bad", "BV123", "bv1xx411c7mD", "")) {
            Scene(videoBvid = bad, style = style, mode = mode).use { s ->
                s.advance(); s.tabIntoCard()
                assertEquals("home_video_cover", s.focusedTag())
                s.key(Key.C, ctrl = true)
                assertTrue(s.clipboard.writes.isEmpty(), "Ctrl+C writes nothing for the bvid \"$bad\"")
                s.key(Key.F10, shift = true)
                assertTrue(s.hasText("稍后再看"), "The card still has its menu for the bvid \"$bad\"")
                assertFalse(s.texts().any { "复制链接" in it }, "No copy row for the bvid \"$bad\": ${s.texts()}")
            }
        }
    }

    @TestFactory fun aRefusedWriteStaysOneAttemptAndIsNotReportedAsSuccess() = cases("refused write") { style, mode ->
        Scene(style = style, mode = mode, acceptWrites = false).use { s ->
            s.advance(); s.tabIntoCard()
            s.key(Key.C, ctrl = true)
            assertEquals(listOf(link(bvid)), s.clipboard.writes, "One attempt through the boundary")
            assertFalse(s.texts().any { "已复制" in it || "复制成功" in it },
                "A refused write is not shown as a success: ${s.texts()}")
            s.key(Key.F10, shift = true)
            s.click(s.row("复制链接（Ctrl C）"))
            assertEquals(2, s.clipboard.writes.size, "The row is a second, single attempt")
            assertFalse(s.hasText("复制链接（Ctrl C）"), "The menu closes even when the write is refused")
            assertFalse(s.texts().any { "已复制" in it || "复制成功" in it }, "Still no success text")
        }
    }

    @TestFactory fun withCardInteractionsOffTheVendoredMenuHasNoCopyRow() = cases("flag OFF") { style, mode ->
        Scene(style = style, mode = mode, interactions = DesktopWindowsUxCardInteractions.OFF).use { s ->
            s.advance()
            s.key(Key.Tab)
            s.key(Key.C, ctrl = true)
            assertTrue(s.clipboard.writes.isEmpty(), "While OFF the cover is no copy target")
            s.click(s.described("更多操作").boundsInWindow.center)
            assertTrue(s.hasText("🕐 稍后再看"), "The vendored menu keeps its rows: ${s.texts()}")
            assertFalse(s.texts().any { "复制链接" in it }, "The vendored menu has no copy row")
            s.click(s.row("🕐 稍后再看"))
            assertEquals(listOf("watch-later"), s.menuActions, "The vendored row keeps its behaviour")
            assertTrue(s.clipboard.writes.isEmpty(), "While OFF nothing writes the clipboard")
            assertTrue(s.opened.isEmpty(), "No playback")
        }
    }
}
