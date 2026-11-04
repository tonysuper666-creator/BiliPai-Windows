@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class,
    coil3.annotation.DelicateCoilApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.android.purebilibili.app.newImageLoader
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.components.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import com.bilipai.desktop.ui.modal.*
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.lang.reflect.Proxy
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.Call
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

/** Actual DynamicCardV2 -> DrawGridV2 -> ImagePreviewDialog -> sole production overlay. */
class DesktopDynamicImagePreviewKeyboardTest {
    @TempDir lateinit var directory: Path
    private fun styles(scenario: String, body: (AppUiStyle) -> Unit) = AppUiStyle.entries.map { style ->
        DynamicTest.dynamicTest("$scenario / ${style.name}") { body(style) }
    }
    private val urls = (1..3).map { "https://example.invalid/image-$it.png" }
    private var text by mutableStateOf("reading")
    private var backgroundKeys = 0
    private val emotes = object : DesktopDynamicEmotes {
        override fun snapshot() = emptyMap<String, String>()
        override fun currentSessionKey(): Any = "image-fixture"
        override suspend fun ensureLoaded() = snapshot()
    }
    private fun platform(context: DesktopPluginContext): DesktopDynamicCardPlatform {
        return Proxy.newProxyInstance(DesktopDynamicCardPlatform::class.java.classLoader,
            arrayOf(DesktopDynamicCardPlatform::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ImageFixturePlatform"
                "isOwned" -> true
                "getContext" -> context
                "getEmotes" -> emotes
                else -> error("Unexpected platform call: ${method.name}")
            }
        } as DesktopDynamicCardPlatform
    }

    @Composable private fun Card() {
        val item = remember { DynamicItem(id_str = "synthetic", type = "DYNAMIC_TYPE_DRAW", modules = DynamicModules(
            module_dynamic = DynamicContentModule(major = DynamicMajor(type = "MAJOR_TYPE_DRAW",
                draw = DrawMajor(items = urls.map { DrawItem(src = it, width = 100, height = 100) }))))) }
        DynamicCardV2(item, SingletonImageLoader.get(PlatformContext.INSTANCE),
            DynamicCardActions(DynamicCardNavigationActions(onVideoClick = {}, onUserClick = {})))
    }
    @Composable private fun Page() {
        Column(Modifier.fillMaxSize().onKeyEvent { if (it.type == KeyEventType.KeyDown) backgroundKeys++; false }) {
            TextField(text, { text = it }, Modifier.testTag("background-input"))
            Card()
        }
    }

    private inner class Scene(style: AppUiStyle, content: @Composable () -> Unit = { Page() }) : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        private var time = 0L
        private val errors = mutableListOf<Throwable>()
        private val repository = DesktopRepository(DesktopSessionStore.temporary())
        private val context = DesktopPluginContext(DesktopPluginStore(directory))
        private val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        private val previousLoader = SingletonImageLoader.get(PlatformContext.INSTANCE)
        val imageRequests = mutableSetOf<String>()
        private val imageFile = directory.resolve("fixture.png").toFile().apply {
            ImageIO.write(BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB), "png", this)
        }
        private val loader = newImageLoader(PlatformContext.INSTANCE,
            Call.Factory { error("Image fixture must not access a socket") }, directory.resolve("cache").toFile())
            .newBuilder().components { add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    synchronized(imageRequests) { imageRequests += chain.request.data.toString() }
                    return chain.withRequest(chain.request.newBuilder().data(imageFile).build()).proceed()
                }
            }) }.build()
        init { check(GraphicsEnvironment.isHeadless()); SingletonImageLoader.setUnsafe(loader) }
        private val scene = DesktopUpstreamCalls.withOwner(owner) {
            ImageComposeScene(800, 700, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) +
                DesktopUpstreamScope(owner) + CoroutineExceptionHandler { _, error -> errors += error }) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, hapticFeedbackEnabled = false),
                    systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 700) {
                    val original = LocalWindowInfo.current
                    val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                    val bindings = remember { platform(context) }
                    CompositionLocalProvider(LocalWindowInfo provides window,
                        LocalDesktopUpstreamAndroidContext provides context.asAndroidContext(),
                        LocalDesktopDynamicCardBindings provides bindings) {
                        Box(Modifier.fillMaxSize()) { content(); ImagePreviewOverlayHost() }
                    }
                }
            }
        }
        fun advance(frames: Int = 30) { repeat(frames) {
            scheduler.runCurrent(); time += 30_000_000
            DesktopUpstreamCalls.withOwner(owner) { scene.render(time).close() }; scheduler.runCurrent()
            errors.firstOrNull()?.let { throw AssertionError("Image composition failed", it) }
        } }
        private fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun tagged(tag: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun exists(tag: String) = nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun focusedTag() = nodes().lastOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            ?.config?.getOrNull(SemanticsProperties.TestTag)
        fun page() = tagged("desktop-image-viewer").config[SemanticsProperties.StateDescription]
        fun caret() = tagged("background-input").config[SemanticsProperties.TextSelectionRange].start
        fun select(position: Int) {
            assertTrue(tagged("background-input").config[SemanticsActions.SetSelection].action!!(position, position, false))
            advance()
        }
        fun hasText(text: String) = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }
        fun key(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)); advance()
        }
        fun click(tag: String, long: Boolean = false) {
            val p = tagged(tag).boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Move, p)
            scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true))
            if (long) {
                advance(1)
                scheduler.advanceTimeBy(750)
                advance()
            }
            scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons()); advance()
        }
        override fun close() {
            scene.close(); repository.logout()
            SingletonImageLoader.setUnsafe(previousLoader); loader.shutdown()
        }
    }

    @TestFactory fun clickedMiddleImageUsesTheOriginalListAndBoundedArrowPagingThenReturnsToThatImage() = styles("original list, bounds and return target") { style ->
        Scene(style).use { s ->
            s.advance(); s.click("dynamic-image-1")
            assertEquals("2/3", s.page()); assertEquals("desktop-image-viewer", s.focusedTag())
            val before = backgroundKeys
            s.key(Key.DirectionRight); assertEquals("3/3", s.page())
            s.key(Key.DirectionRight); assertEquals("3/3", s.page())
            s.key(Key.DirectionLeft); assertEquals("2/3", s.page())
            s.key(Key.DirectionLeft); assertEquals("1/3", s.page())
            s.key(Key.DirectionLeft); assertEquals("1/3", s.page())
            assertEquals(before, backgroundKeys, "Viewer arrows must not reach its underlying card")
            s.key(Key.Escape); assertFalse(s.exists("desktop-image-viewer"))
            assertEquals("dynamic-image-1", s.focusedTag(), "Return to the clicked source, even after paging")
            assertTrue(s.imageRequests.containsAll(urls), "Retain the source image identities")
            s.key(Key.Enter); assertEquals("2/3", s.page(), "Keyboard reopens the same live source")
        }
    }

    @TestFactory fun imageEscapeLeavesTheParentReadingLayerOpenAndRestoresTheSource() = styles("topmost image before parent") { style ->
        var parent by mutableStateOf(true)
        Scene(style) {
            if (parent) DesktopPurposeModal(DesktopModalPurpose.CONFIRM, { parent = false }) { Card() }
        }.use { s ->
            s.advance(); s.click("dynamic-image-2"); assertEquals("3/3", s.page())
            s.key(Key.Escape); assertFalse(s.exists("desktop-image-viewer")); assertTrue(parent)
            assertEquals("dynamic-image-2", s.focusedTag())
            s.key(Key.Escape); assertFalse(parent)
        }
    }

    @TestFactory fun backgroundTextEditingAndClosedViewerDoNotOwnArrowKeys() = styles("background editor owns arrows") { style ->
        Scene(style).use { s ->
            s.advance(); s.click("background-input")
            s.select(3)
            s.key(Key.DirectionLeft); assertEquals(2, s.caret())
            s.key(Key.DirectionRight); assertEquals(3, s.caret())
            assertEquals("reading", text); assertFalse(s.exists("desktop-image-viewer"))
            assertEquals("background-input", s.focusedTag())
            s.click("dynamic-image-0"); s.key(Key.Escape)
            s.click("background-input"); s.select(3); s.key(Key.DirectionLeft)
            assertEquals("background-input", s.focusedTag()); assertEquals("reading", text)
            assertEquals(2, s.caret(), "A closed viewer must not intercept editor arrows")
        }
    }

    @TestFactory fun escapeClosesTheImageActionMenuBeforeTheImage() = styles("image actions before image") { style ->
        Scene(style).use { s ->
            s.advance(); s.click("dynamic-image-1"); s.click("desktop-image-viewer", long = true)
            assertTrue(s.hasText("复制链接"), "Long press opens the original image action menu")
            assertEquals("desktop-image-actions", s.focusedTag())
            s.key(Key.Escape)
            assertFalse(s.hasText("复制链接")); assertEquals("2/3", s.page())
            s.key(Key.Escape); assertFalse(s.exists("desktop-image-viewer"))
            assertEquals("dynamic-image-1", s.focusedTag())
        }
    }
}
