@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.components.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Exercises the production DynamicCardV2 and its ForwardedContent child in real Compose layout. */
class DesktopDynamicCardTextLayoutTest {
    @TempDir lateinit var directory: Path
    private val emotes = object : DesktopDynamicEmotes {
        override fun snapshot() = emptyMap<String, String>()
        override fun currentSessionKey(): Any = "layout-fixture"
        override suspend fun ensureLoaded() = snapshot()
    }
    private fun platform(context: DesktopPluginContext) = Proxy.newProxyInstance(
        DesktopDynamicCardPlatform::class.java.classLoader, arrayOf(DesktopDynamicCardPlatform::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "LayoutFixturePlatform"
            "isOwned" -> true
            "getContext" -> context
            "getEmotes" -> emotes
            else -> error("Unexpected platform call: ${method.name}")
        }
    } as DesktopDynamicCardPlatform

    private inner class Scene(text: String, forwarded: Boolean, initialWidth: Dp) : AutoCloseable {
        var width by mutableStateOf(initialWidth)
        private val scheduler = TestCoroutineScheduler()
        private var time = 0L
        private val errors = mutableListOf<Throwable>()
        private val repository = DesktopRepository(DesktopSessionStore.temporary())
        private val context = DesktopPluginContext(DesktopPluginStore(directory))
        private val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        private val body = DynamicItem(id_str = "original", type = "DYNAMIC_TYPE_WORD", modules = DynamicModules(
            module_dynamic = DynamicContentModule(desc = DynamicDesc(text = text))))
        private val item = if (forwarded) DynamicItem(id_str = "forward", type = "DYNAMIC_TYPE_FORWARD", orig = body) else body
        private val scene = DesktopUpstreamCalls.withOwner(owner) {
            ImageComposeScene(1400, 2000, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) +
                DesktopUpstreamScope(owner) + CoroutineExceptionHandler { _, error -> errors += error }) {
                DesktopAppearanceTheme(DesktopThemeSettings(hapticFeedbackEnabled = false),
                    systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 700) {
                    val original = LocalWindowInfo.current
                    val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                    val bindings = remember { platform(context) }
                    CompositionLocalProvider(LocalWindowInfo provides window,
                        LocalDesktopUpstreamAndroidContext provides context.asAndroidContext(),
                        LocalDesktopDynamicCardBindings provides bindings) {
                        Box(Modifier.width(width)) {
                            DynamicCardV2(item, SingletonImageLoader.get(PlatformContext.INSTANCE),
                                DynamicCardActions(DynamicCardNavigationActions(onVideoClick = {}, onUserClick = {})))
                        }
                    }
                }
            }
        }
        fun advance() { repeat(12) {
            scheduler.runCurrent(); time += 30_000_000
            DesktopUpstreamCalls.withOwner(owner) { scene.render(time).close() }; scheduler.runCurrent()
            errors.firstOrNull()?.let { throw AssertionError("Dynamic card composition failed", it) }
        } }
        private fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        private fun node(text: String) = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            .firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
        fun has(text: String) = node(text) != null
        fun layout(text: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            val action = checkNotNull(checkNotNull(node(text)).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
            assertTrue(action(results))
            return results.single()
        }
        fun click(text: String) {
            val semantics = checkNotNull(node(text)) { "Missing $text" }
            assertTrue(checkNotNull(semantics.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            advance()
        }
        override fun close() { scene.close(); repository.logout() }
    }

    @Test fun normalAndForwardedTextReactToMeasuredWidthAndExpandCollapse() {
        val content = "你好世界🙂".repeat(14)
        assertTrue(content.length < 108, "Regression fixture must defeat the old character threshold")
        for (forwarded in listOf(false, true)) {
            Scene(content, forwarded, 180.dp).use { s ->
                s.advance(); assertTrue(s.has("展开更多"), "narrow forwarded=$forwarded")
                s.click("展开更多"); assertTrue(s.has("收起"))
                s.click("收起"); assertTrue(s.has("展开更多"))
                s.width = 1200.dp; s.advance(); assertFalse(s.has("展开更多"), "wide forwarded=$forwarded")
                s.width = 180.dp; s.advance(); assertTrue(s.has("展开更多"), "resized forwarded=$forwarded")
            }
        }
    }

    @Test fun explicitLineBreaksOverflowButShortTextDoesNot() {
        for (forwarded in listOf(false, true)) {
            Scene("一🙂\n二\n三\n四\n五\n六\n七", forwarded, 900.dp).use { s ->
                s.advance(); assertTrue(s.has("展开更多"), "line breaks forwarded=$forwarded")
            }
            Scene("短文本🙂", forwarded, 180.dp).use { s ->
                s.advance(); assertFalse(s.has("展开更多"), "short forwarded=$forwarded")
            }
        }
    }

    @Test fun expandedTextRemainsControllableAcrossResize() {
        val content = "你好世界🙂".repeat(14)
        for (forwarded in listOf(false, true)) {
            Scene(content, forwarded, 180.dp).use { s ->
                s.advance()
                assertTrue(s.has("展开更多"), "initial narrow forwarded=$forwarded")
                s.click("展开更多")
                assertTrue(s.has("收起"))
                assertFalse(s.layout(content).hasVisualOverflow)
                s.width = 1200.dp; s.advance()
                assertFalse(s.has("展开更多"))
                assertFalse(s.has("收起"))
                assertTrue(s.layout(content).lineCount <= 6, "wide forwarded=$forwarded")
                assertFalse(s.layout(content).hasVisualOverflow)
                s.width = 180.dp; s.advance()
                assertTrue(s.has("收起"), "expanded after narrowing forwarded=$forwarded")
                assertFalse(s.has("展开更多"))
                assertTrue(s.layout(content).lineCount > 6)
                assertFalse(s.layout(content).hasVisualOverflow)
                s.click("收起")
                assertTrue(s.has("展开更多"))
                assertEquals(6, s.layout(content).lineCount)
                assertTrue(s.layout(content).hasVisualOverflow)
                s.click("展开更多")
                assertTrue(s.has("收起"))
                assertFalse(s.layout(content).hasVisualOverflow)
            }
        }
    }
}
