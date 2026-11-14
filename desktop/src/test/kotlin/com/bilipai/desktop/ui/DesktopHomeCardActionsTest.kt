@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class,
    coil3.annotation.DelicateCoilApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import com.android.purebilibili.core.util.WindowWidthSizeClass
import com.android.purebilibili.data.model.response.Owner
import com.android.purebilibili.data.model.response.Stat
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.home.components.cards.ElegantVideoCard
import com.android.purebilibili.feature.home.resolveHomeFeedCardLayout
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
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/*
 * Slice K3i on the generated Home card (the real Home entry DesktopOriginalDiscoveryVideoCard -> ElegantVideoCard), with a
 * recording port in LocalDesktopHomeCardActions, the way DesktopHomeCardActionsHost of the Root provides it on the active
 * Home page. Density 1.
 *   * ON with the port: the ordinary AV card menu is 稍后再看 / 收藏 / 小窗播放 / 复制链接 / line / 不感兴趣, and a card that
 *     also has the vendored 取消收藏 row keeps it in second place: seven items at most, in the slice order;
 *   * each row is one user action on the port, with the card's target (bvid, aid resolution, cid), and closes the menu;
 *   * without the port (every page except the Root's active Home) and with card_interactions OFF the menu has no new row;
 *   * a special card (no video link, collection, episode) gets no row; a card without aid and id gets no 收藏 but 小窗播放;
 *   * removing the card tells the port that this card left, with the same identity the 小窗播放 call carried.
 * Not covered: a native window, real AWT input, high DPI, a screen reader (headless ImageComposeScene).
 */
class DesktopHomeCardActionsTest {
    @TempDir lateinit var directory: Path

    private val bvid = "BV1xx411c7mD"

    private fun video(bvid: String = this.bvid, aid: Long = 11, id: Long = 0, collection: Boolean = false,
        episode: Long = 0) = VideoItem(id = id, aid = aid, bvid = bvid, cid = 71, title = "K3i fixture",
        pic = "https://example.invalid/k3i-cover.png", owner = Owner(mid = 9, name = "up"),
        stat = Stat(view = 1200, danmaku = 3), isCollectionResource = collection, episodeId = episode)

    private class RecordingPort : DesktopHomeCardActions {
        val calls = mutableListOf<String>()
        val miniCards = mutableListOf<Any>()
        val leftCards = mutableListOf<Any>()
        override fun favorite(target: DesktopHomeCardTarget) {
            calls += "favorite:${target.bvid}/${target.aid}"
        }
        override fun mini(target: DesktopHomeCardTarget, card: Any) {
            calls += "mini:${target.bvid}/${target.aid}/${target.cid}/${target.card.preferredCid}"; miniCards += card
        }
        override fun cardLeft(card: Any) { leftCards += card }
    }

    private class Clipboard : DesktopTextClipboard {
        val writes = mutableListOf<String>()
        override fun copyText(value: String): Boolean { writes += value; return true }
    }

    private enum class Card { ENTRY, FULL }

    private inner class Scene(
        private val item: VideoItem = video(),
        interactions: DesktopWindowsUxCardInteractions = DesktopWindowsUxCardInteractions.ON,
        withPort: Boolean = true,
        private val card: Card = Card.ENTRY,
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
        val port = RecordingPort()
        private val provided: DesktopHomeCardActions? = if (withPort) port else null
        val clipboard = Clipboard()
        val menuActions = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var shown by mutableStateOf(true)

        init { check(GraphicsEnvironment.isHeadless()); SingletonImageLoader.setUnsafe(loader) }

        private val scene = DesktopUpstreamCalls.withOwner(owner) {
            ImageComposeScene(520, 900, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) +
                DesktopUpstreamScope(owner) + CoroutineExceptionHandler { _, error -> errors += error }) {
                DesktopAppearanceTheme(DesktopThemeSettings(hapticFeedbackEnabled = false),
                    systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 520) {
                    val original = LocalWindowInfo.current
                    val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                    val ux = remember { DesktopWindowsUxMemory(DesktopWindowsUxSettings(cardInteractions = interactions)) }
                    CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopWindowsUx provides ux,
                        LocalDesktopTextClipboard provides clipboard,
                        LocalDesktopUpstreamAndroidContext provides context.asAndroidContext(),
                        LocalDesktopHomeCardActions provides provided) {
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
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
                Box(Modifier.width(320.dp)) {
                    if (shown) {
                        val dismiss = { menuActions += "not-interested"; Unit }
                        val watchLater = { menuActions += "watch-later"; Unit }
                        when (card) {
                            Card.ENTRY -> DesktopOriginalDiscoveryVideoCard(item, 0, layout, settings,
                                onVideo = { opened += it.bvid }, onUser = {}, onPreview = {},
                                onNotInterested = dismiss, onWatchLater = watchLater)
                            Card.FULL -> ElegantVideoCard(video = item, index = 0, animationEnabled = false,
                                onDismiss = dismiss, onWatchLater = watchLater,
                                onUnfavorite = { menuActions += "unfavorite" },
                                onClick = { id, _ -> opened += id })
                        }
                    }
                }
            }
        }

        fun advance(frames: Int = 40) { repeat(frames) {
            scheduler.runCurrent(); time += 30_000_000
            DesktopUpstreamCalls.withOwner(owner) { scene.render(time).close() }
            scheduler.runCurrent()
            errors.firstOrNull()?.let { throw AssertionError("Home card composition failed", it) }
        } }
        private fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun textOf(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        fun texts() = nodes().mapNotNull(::textOf)
        fun hasText(text: String) = texts().any { it == text }
        fun boundsOf(text: String): Rect = nodes().first { textOf(it) == text }.boundsInWindow
        fun separators() = nodes().filter {
            it.config.getOrNull(SemanticsProperties.TestTag) == "desktop_card_menu_separator"
        }
        fun click(point: Offset) {
            val ms = time / 1_000_000
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = ms)
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = ms + 1, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = ms + 41, buttons = PointerButtons())
            advance()
        }
        fun openMenu() {
            advance()
            click(nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("更多操作")
            }.boundsInWindow.center)
        }
        /** The menu rows from top to bottom, with the line where it is drawn. */
        fun menuOrder(labels: List<String>): List<String> {
            val rows = labels.filter(::hasText).map { it to boundsOf(it).top }
            val line = separators().map { "line" to it.boundsInWindow.top }
            return (rows + line).sortedBy { it.second }.map { it.first }
        }

        override fun close() {
            scene.close(); repository.logout()
            SingletonImageLoader.setUnsafe(previousLoader); loader.shutdown()
        }
    }

    private val sliceRows = listOf("稍后再看", "取消收藏", "收藏", "小窗播放", "复制链接（Ctrl C）", "不感兴趣")

    @Test fun theRealHomeEntryOffersFavoriteAndMiniBetweenWatchLaterAndCopyLink() {
        Scene().use { s ->
            s.openMenu()
            assertEquals(listOf("稍后再看", "收藏", "小窗播放", "复制链接（Ctrl C）", "line", "不感兴趣"),
                s.menuOrder(sliceRows), "The ON menu of an ordinary AV card: ${s.texts()}")
            assertTrue(s.port.calls.isEmpty(), "Opening the menu runs no action")
        }
    }

    @Test fun aCardWithTheVendoredUnfavoriteRowHasSevenItemsInTheSliceOrder() {
        Scene(card = Card.FULL).use { s ->
            s.openMenu()
            val order = s.menuOrder(sliceRows)
            assertEquals(listOf("稍后再看", "取消收藏", "收藏", "小窗播放", "复制链接（Ctrl C）", "line", "不感兴趣"), order,
                "The vendored 取消收藏 keeps its place: ${s.texts()}")
            assertTrue(order.size <= 7, "Seven items at most: $order")
        }
    }

    @Test fun favoriteRunsOnePortActionWithTheAidAndClosesTheMenu() {
        Scene().use { s ->
            s.openMenu(); s.click(s.boundsOf("收藏").center)
            assertEquals(listOf("favorite:$bvid/11"), s.port.calls)
            assertFalse(s.hasText("不感兴趣"), "Picking 收藏 closes the menu")
            assertTrue(s.menuActions.isEmpty() && s.opened.isEmpty() && s.clipboard.writes.isEmpty(),
                "收藏 neither plays, nor copies, nor runs another row")
        }
    }

    @Test fun miniRunsOnePortActionWithTheCardTargetAndClosesTheMenu() {
        Scene().use { s ->
            s.openMenu(); s.click(s.boundsOf("小窗播放").center)
            assertEquals(listOf("mini:$bvid/11/71/71"), s.port.calls)
            assertEquals(1, s.port.miniCards.size)
            assertFalse(s.hasText("不感兴趣"), "Picking 小窗播放 closes the menu")
            assertTrue(s.opened.isEmpty(), "The row does not take the ordinary click path of the card itself")
        }
    }

    @Test fun removingTheCardTellsThePortThatThisCardLeft() {
        Scene().use { s ->
            s.openMenu(); s.click(s.boundsOf("小窗播放").center)
            val card = s.port.miniCards.single()
            assertTrue(s.port.leftCards.isEmpty(), "The card is still there")
            s.shown = false; s.advance()
            assertTrue(s.port.leftCards.any { it === card }, "The card that started the mini wait left")
        }
    }

    @Test fun withoutThePortTheMenuHasNoNewRow() {
        Scene(withPort = false).use { s ->
            s.openMenu()
            assertEquals(listOf("稍后再看", "复制链接（Ctrl C）", "line", "不感兴趣"), s.menuOrder(sliceRows),
                "Outside the Root's active Home the K3f menu stays: ${s.texts()}")
        }
    }

    @Test fun withCardInteractionsOffTheVendoredMenuStaysEvenWithThePort() {
        Scene(interactions = DesktopWindowsUxCardInteractions.OFF).use { s ->
            s.openMenu()
            assertTrue(s.hasText("🕐 稍后再看") && s.hasText("🚫 不感兴趣"), "Vendored rows: ${s.texts()}")
            assertFalse(s.hasText("收藏") || s.hasText("小窗播放"), "No new row while OFF: ${s.texts()}")
            assertTrue(s.separators().isEmpty())
        }
    }

    @Test fun specialCardsGetNoRow() {
        for (item in listOf(video(bvid = "BV-k3i-fixture"), video(collection = true), video(episode = 5))) {
            Scene(item = item).use { s ->
                s.openMenu()
                assertTrue(s.hasText("稍后再看") && s.hasText("不感兴趣"), "${item.bvid}: ${s.texts()}")
                assertFalse(s.hasText("收藏") || s.hasText("小窗播放"),
                    "${item.bvid}/${item.isCollectionResource}/${item.episodeId}: no new row: ${s.texts()}")
            }
        }
    }

    @Test fun aCardWithoutAidHasNoFavoriteButKeepsMiniAndTheIdResolvesTheAid() {
        Scene(item = video(aid = 0, id = 0)).use { s ->
            s.openMenu()
            assertFalse(s.hasText("收藏"), "No aid, no favourite row: ${s.texts()}")
            assertTrue(s.hasText("小窗播放"), "The mini row needs no aid: ${s.texts()}")
        }
        Scene(item = video(aid = 0, id = 33)).use { s ->
            s.openMenu(); s.click(s.boundsOf("收藏").center)
            assertEquals(listOf("favorite:$bvid/33"), s.port.calls, "aid falls back to id like watch later")
        }
    }
}
