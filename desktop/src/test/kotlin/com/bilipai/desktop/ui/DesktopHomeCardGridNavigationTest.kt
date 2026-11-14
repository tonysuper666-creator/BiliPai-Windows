@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class,
    coil3.annotation.DelicateCoilApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.android.purebilibili.app.newImageLoader
import com.android.purebilibili.core.store.DesktopOriginalHomeCardVisualSettings
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.data.model.response.Owner
import com.android.purebilibili.data.model.response.Stat
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.settings.*
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

/** Real Grid + original generated cards, no new navigation symbols used: this file also runs on the fixed baseline.
 * Expected neighbours come from rendered cover bounds and video identity. No native window or network is used. */
class DesktopHomeCardGridNavigationTest {
    @TempDir lateinit var directory: Path
    private fun cases(name: String, body: (AppUiStyle, AppThemeMode) -> Unit) = AppUiStyle.entries.flatMap { style ->
        listOf(AppThemeMode.LIGHT, AppThemeMode.DARK).map { mode ->
            DynamicTest.dynamicTest("$name / $style / $mode") { body(style, mode) }
        }
    }
    private fun video(id: Int) = VideoItem(aid = id.toLong() + 11, bvid = "BV1xx411c7mD", cid = 71,
        title = if (id % 2 == 0) "Video $id" else "Video $id with a longer title to occupy the second line of the actual card",
        pic = "https://example.invalid/k3g.png", owner = Owner(mid = 9, name = "up"), stat = Stat(view = 1200, danmaku = 3))
    private class RecordingClipboard(private val accept: Boolean) : DesktopTextClipboard {
        val writes = mutableListOf<String>()
        override fun copyText(value: String): Boolean { writes += value; return accept }
    }
    private inner class Scene(
        columns: Int = 4,
        count: Int = 24,
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
        var width by mutableStateOf(if (columns == 4) 1280 else 1536)
        var ids by mutableStateOf((0 until count).toList())
        var edited by mutableStateOf("editor text")
        val grid = LazyStaggeredGridState()
        private val preferences = DesktopHomeCardPreferences(context)
        init {
            kotlinx.coroutines.runBlocking { preferences.setGridColumnCount(columns) }
        }
        private var last: Bitmap? = null
        var primary = 0; var background = 0
        val clipboard = RecordingClipboard(acceptWrites)
        val opened = mutableListOf<VideoCard>()
        val menuActions = mutableListOf<String>()

        init { check(GraphicsEnvironment.isHeadless()); SingletonImageLoader.setUnsafe(loader) }

        private val scene = DesktopUpstreamCalls.withOwner(owner) {
            ImageComposeScene(1600, 820, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) +
                DesktopUpstreamScope(owner) + CoroutineExceptionHandler { _, error -> errors += error }) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, themeMode = mode, hapticFeedbackEnabled = false),
                    systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 520) {
                    val original = LocalWindowInfo.current
                    val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true; override val containerSize get() = IntSize(width, 820) } }
                    val ux = remember { DesktopWindowsUxMemory(DesktopWindowsUxSettings(cardInteractions = interactions)) }
                    CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopWindowsUx provides ux,
                        LocalDesktopHomeCardPreferences provides preferences, LocalDesktopTextClipboard provides clipboard,
                        LocalDesktopUpstreamAndroidContext provides context.asAndroidContext()) {
                        Page()
                    }
                }
            }
        }

        @Composable private fun Page() {
            primary = MaterialTheme.colorScheme.primary.toArgb(); background = MaterialTheme.colorScheme.background.toArgb()
            val settings = remember {
                DesktopOriginalHomeCardVisualSettings.decode(DesktopPreferenceSnapshot(JsonObject(emptyMap())))
                    .copy(cardAnimationEnabled = false)
            }
            Column(Modifier.width(width.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
                BasicTextField(edited, { edited = it }, Modifier.height(40.dp).testTag("editor"))
                Box(Modifier.size(32.dp).testTag("before").focusable())
                DesktopHomeCardGrid(grid, Modifier.weight(1f)) { layout ->
                    itemsIndexed(ids, key = { _, id -> "video-$id" }) { index, id ->
                        Column(Modifier.testTag("item-$id")) {
                            DesktopOriginalDiscoveryVideoCard(video(id), index, layout, settings,
                                onVideo = { opened += it }, onUser = {}, onPreview = {},
                                onNotInterested = { menuActions += "not-interested" },
                                onWatchLater = { menuActions += "watch-later" })
                            // Different real item heights, just as titles/actions differ in Home.
                            Spacer(Modifier.height((id % 3 * 23).dp))
                        }
                    }
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
        fun coverNodes(): Map<Int, SemanticsNode> = nodes().filter {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("item-") == true
        }.mapNotNull { parent ->
            flatten(parent).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "home_video_cover" }
                ?.let { parent.config[SemanticsProperties.TestTag].removePrefix("item-").toInt() to it }
        }.toMap()
        fun covers() = coverNodes().mapValues { it.value.boundsInRoot }
        fun focusedId() = coverNodes().entries.firstOrNull {
            it.value.config.getOrNull(SemanticsProperties.Focused) == true
        }?.key
        fun focusCover(id: Int) {
            // Request the actual cover's focus target without invoking its click/navigation action.
            val node = coverNodes().getValue(id)
            assertTrue(node.config[androidx.compose.ui.semantics.SemanticsActions.RequestFocus].action!!.invoke())
            advance()
            assertEquals(id, focusedId())
        }
        fun ringPixel(id: Int): Int {
            val r = covers().getValue(id)
            return checkNotNull(last).getColor(r.center.x.toInt(), r.top.toInt() - 4)
        }
        fun keyDown(key: Key) { scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); advance() }
        fun keyUp(key: Key) { scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)); advance() }
        fun setColumns(columns: Int) {
            kotlinx.coroutines.runBlocking { preferences.setGridColumnCount(columns) }; advance()
        }
        override fun close() {
            last?.close(); scene.close(); repository.logout()
            SingletonImageLoader.setUnsafe(previousLoader); loader.shutdown()
        }
    }


    private fun row(covers: Map<Int, Rect>, from: Int): List<Int> {
        val r = covers.getValue(from)
        return covers.filterValues { kotlin.math.abs(it.center.y - r.center.y) < r.height / 2 }.keys.sortedBy { covers.getValue(it).left }
    }
    @TestFactory fun fourDirectionsBoundariesAndKeyUp() = cases("neighbours") { style, mode ->
        for (columns in listOf(4, 5)) Scene(columns = columns, style = style, mode = mode).use { s ->
            s.advance()
            val firstRow = row(s.covers(), 0)
            assertEquals(columns, firstRow.size, "Real rendered columns")
            s.focusCover(firstRow.first())
            s.key(Key.DirectionLeft); assertEquals(firstRow.first(), s.focusedId(), "Left edge stays on cover")
            s.key(Key.DirectionUp); assertEquals(firstRow.first(), s.focusedId(), "Top edge stays on cover")
            for (id in firstRow.drop(1)) {
                s.keyDown(Key.DirectionRight); assertEquals(id, s.focusedId(), "Right moves to next rendered cover")
                s.keyUp(Key.DirectionRight); assertEquals(id, s.focusedId(), "KeyUp cannot repeat")
                assertEquals(s.primary, s.ringPixel(id), "New cover draws the keyboard ring")
                assertEquals(s.background, s.ringPixel(firstRow[firstRow.indexOf(id) - 1]), "Old ring disappears")
            }
            s.key(Key.DirectionRight); assertEquals(firstRow.last(), s.focusedId(), "No row wrap")
            s.key(Key.DirectionLeft); assertEquals(firstRow[firstRow.lastIndex - 1], s.focusedId())
            val from = s.focusedId()!!; val bounds = s.covers(); val source = bounds.getValue(from)
            val next = bounds.filter { (id, r) -> id !in firstRow && r.center.y > source.center.y }
                .minWith(compareBy<Map.Entry<Int, Rect>> { kotlin.math.abs(it.value.center.x - source.center.x) }
                    .thenBy { it.value.top }).key
            s.key(Key.DirectionDown); assertEquals(next, s.focusedId(), "Down chooses nearest X of next row")
            s.key(Key.DirectionUp); assertEquals(from, s.focusedId(), "Up returns to original cover")
            assertTrue(s.opened.isEmpty()); assertTrue(s.menuActions.isEmpty()); assertTrue(s.clipboard.writes.isEmpty())
        }
    }
    @TestFactory fun scrollsToRealCoversAndStopsAtTheLastRow() = cases("lazy scroll") { style, mode ->
        Scene(style = style, mode = mode, count = 36).use { s ->
            s.advance(); s.focusCover(0)
            val laneX = s.covers().getValue(0).center.x
            repeat(6) {
                val before = s.focusedId()!!
                s.key(Key.DirectionDown)
                assertNotEquals(before, s.focusedId(), "Each next row exists, including outside the viewport")
                assertEquals(laneX, s.covers().getValue(s.focusedId()!!).center.x, "Keep nearest measured X lane")
            }
            assertTrue(s.grid.firstVisibleItemIndex > 0, "Original grid state scrolled")
            var additionalMoves = 0
            do {
                val before = s.focusedId()
                s.key(Key.DirectionDown)
                assertNotNull(s.focusedId(), "Scrolling never loses cover focus")
                assertTrue(additionalMoves++ < 36, "Navigation must reach a bottom boundary")
            } while (before != s.focusedId())
            val last = s.focusedId(); s.key(Key.DirectionDown); assertEquals(last, s.focusedId(), "Bottom boundary")
            s.key(Key.DirectionUp); assertNotEquals(last, s.focusedId())
            assertTrue(s.opened.isEmpty())
        }
    }
    @TestFactory fun resizeRemovalAndReorderUseLiveGeometry() = cases("live registry") { style, mode ->
        Scene(style = style, mode = mode, count = 16).use { s ->
            s.advance(); s.focusCover(1)
            s.width = 1536; s.setColumns(5)
            assertEquals(5, row(s.covers(), 0).size)
            s.key(Key.DirectionRight); assertEquals(2, s.focusedId())
            s.ids = s.ids.filter { it != 3 }; s.advance()
            val expected = row(s.covers(), 2).let { it[it.indexOf(2) + 1] }
            s.key(Key.DirectionRight); assertEquals(expected, s.focusedId(), "Removed target is disposed")
            s.ids = s.ids.reversed(); s.advance()
            val fresh = s.covers().entries.filter { it.value.top >= 0 }.minBy { it.value.top }.key
            s.focusCover(fresh)
            val ordered = row(s.covers(), fresh)
            assertTrue(ordered.size > 1, "Reordered fixture still contains adjacent covers")
            s.focusCover(ordered.first()); s.key(Key.DirectionRight); assertEquals(ordered[1], s.focusedId())
        }
    }
    @TestFactory fun editingButtonsMenusAndOffKeepTheirOwnKeys() = cases("focus boundaries") { style, mode ->
        Scene(style = style, mode = mode).use { s ->
            s.advance(); s.key(Key.Tab); assertEquals("editor", s.focusedTag())
            s.key(Key.DirectionRight); s.key(Key.DirectionDown); assertEquals("editor", s.focusedTag())
            assertNull(s.focusedId()); assertEquals("editor text", s.edited)
            s.focusCover(0); s.key(Key.Tab)
            assertNotEquals("home_video_cover", s.focusedTag(), "Tab still enters quick controls")
            s.key(Key.DirectionDown); assertNull(s.focusedId(), "Button arrows cannot run cover navigation")
            s.focusCover(0); s.key(Key.C, ctrl = true); assertEquals(1, s.clipboard.writes.size)
            s.key(Key.F10, shift = true); assertTrue(s.hasText("复制链接（Ctrl C）"))
            s.key(Key.DirectionDown); assertTrue(s.hasText("复制链接（Ctrl C）"), "Menu owns arrows")
            assertEquals(0, s.focusedId(), "Menu does not navigate the grid")
            s.click(s.row("复制链接（Ctrl C）")); assertEquals(2, s.clipboard.writes.size)
            assertEquals(0, s.focusedId())
            s.key(Key.Enter); assertEquals(1, s.opened.size)
        }
        Scene(style = style, mode = mode, interactions = DesktopWindowsUxCardInteractions.OFF).use { s ->
            s.advance(); assertTrue(s.coverNodes().values.all { it.config.getOrNull(SemanticsProperties.Focused) == null }, "OFF has no cover focus target")
            s.key(Key.Tab); s.key(Key.DirectionDown); assertNull(s.focusedId()); assertTrue(s.opened.isEmpty())
        }
    }
    @TestFactory fun existingPagingStillUsesTheGridState() = cases("paging") { style, mode ->
        Scene(style = style, mode = mode, count = 36).use { s ->
            s.advance(); s.focusCover(0)
            s.key(Key.PageDown)
            assertTrue(s.grid.firstVisibleItemIndex > 0, "PageDown reaches the existing Grid paging handler")
            s.key(Key.MoveHome); assertEquals(0, s.grid.firstVisibleItemIndex)
            s.key(Key.MoveEnd)
            assertTrue(s.grid.layoutInfo.visibleItemsInfo.any { it.index == 35 }, "End reaches the last real item")
            val end = s.grid.firstVisibleItemIndex
            s.key(Key.PageUp); assertTrue(s.grid.firstVisibleItemIndex < end)
            assertTrue(s.opened.isEmpty())
        }
    }
    @TestFactory fun raggedLastRowUsesNearestHorizontalCover() = cases("ragged row") { style, mode ->
        Scene(style = style, mode = mode, count = 7).use { s ->
            s.advance(); s.focusCover(3)
            val bounds = s.covers()
            val firstRow = row(bounds, 0)
            val source = bounds.getValue(3)
            val nearest = bounds.filterKeys { it !in firstRow }.minBy {
                kotlin.math.abs(it.value.center.x - source.center.x)
            }.key
            s.key(Key.DirectionDown); assertEquals(nearest, s.focusedId(), "Nearest X wins despite different card heights")
            s.key(Key.DirectionDown); assertEquals(nearest, s.focusedId(), "Ragged bottom stays put")
            val above = firstRow.minBy {
                kotlin.math.abs(bounds.getValue(it).center.x - bounds.getValue(nearest).center.x)
            }
            s.key(Key.DirectionUp); assertEquals(above, s.focusedId(), "Up chooses the measured lane above")
            assertTrue(s.opened.isEmpty())
        }
    }
}
