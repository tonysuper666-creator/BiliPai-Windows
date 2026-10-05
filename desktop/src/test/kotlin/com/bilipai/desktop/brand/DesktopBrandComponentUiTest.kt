package com.bilipai.desktop.brand

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.BlueSnowMaidAnimation
import com.android.purebilibili.core.ui.EmptyState
import com.android.purebilibili.core.ui.ErrorState
import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.ui.DesktopDetailWindow
import com.bilipai.desktop.ui.DesktopHomePlatform
import com.bilipai.desktop.ui.DesktopHomeWindowBackgroundPort
import com.bilipai.desktop.ui.LocalDesktopHomePlatform
import java.awt.GraphicsEnvironment
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat

/** Whole original composables on ImageComposeScene's CPU raster, not an OS/native window.
 * The real headless reduced-motion binding is observed; no system preference or clock is replaced.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopBrandComponentUiTest {
    @Test fun wholeBlueSnowWaitsForVisibilityAndForegroundThenFinishesOncePerReplayIdentity(): Unit = runBlocking {
        val visible = mutableStateOf(false)
        val replay = mutableIntStateOf(0)
        val completions = mutableListOf<Int>()
        val background = Background()
        val fixture = Scene(background) {
            val identity = replay.intValue
            BlueSnowMaidAnimation(DesktopMaidAnimation.RETRY, Modifier.size(220.dp),
                isVisible = visible.value, replayKey = identity, onFinished = { completions += identity })
        }
        try {
            fixture.pumpFor(650)
            assertEquals(true, fixture.observedReducedMotion, "Actual headless accessibility binding")
            assertTrue(completions.isEmpty(), "Initially invisible identity must not finish")
            assertEquals(1, background.listenerCount)
            assertTrue(fixture.lifecycle.registry.observerCount > 0)

            background.setBackground(true)
            visible.value = true
            fixture.pumpFor(650)
            assertTrue(completions.isEmpty(), "A visible background identity must not finish")
            background.setBackground(false)
            fixture.await("foreground original completion") { completions == listOf(0) }
            fixture.assertArtwork(DesktopMaidAnimation.RETRY, "blue-snow-retry")
            fixture.pumpFor(350)
            assertEquals(listOf(0), completions, "Recomposition cannot repeat completion")

            background.setBackground(true)
            replay.intValue = 1
            fixture.pumpFor(650)
            assertEquals(listOf(0), completions, "New replay identity also waits for foreground")
            assertEquals(1, background.listenerCount, "Old keyed identity released its listener")
            assertTrue(background.removed > 0)
            fixture.lifecycle.registry.currentState = Lifecycle.State.STARTED
            background.setBackground(false)
            fixture.pumpFor(350)
            assertEquals(listOf(0), completions, "Foreground window still waits for original RESUMED lifecycle")
            fixture.lifecycle.registry.currentState = Lifecycle.State.RESUMED
            fixture.await("explicit replay completion") { completions == listOf(0, 1) }
            fixture.pumpFor(350)
            assertEquals(listOf(0, 1), completions)
        } finally { fixture.close() }
        assertEquals(0, background.listenerCount)
        assertEquals(background.added, background.removed)
        assertEquals(0, fixture.lifecycle.registry.observerCount)
        background.setBackground(true)
        assertEquals(listOf(0, 1), completions, "Disposed identity cannot publish a later finish")
    }

    @Test fun originalCleaningStillNeverFinishesAndDisposalRemovesActualListeners(): Unit = runBlocking {
        var finishes = 0
        val replay = mutableIntStateOf(0)
        val background = Background()
        val fixture = Scene(background) {
            BlueSnowMaidAnimation(DesktopMaidAnimation.CLEANING, Modifier.size(220.dp),
                replayKey = replay.intValue, onFinished = { finishes++ })
        }
        try {
            fixture.awaitArtwork(DesktopMaidAnimation.CLEANING)
            assertEquals(true, fixture.observedReducedMotion)
            fixture.pumpFor(700)
            assertEquals(0, finishes, "Original looping CLEANING never signals operation success")
            background.setBackground(true)
            fixture.pumpFor(350)
            background.setBackground(false)
            replay.intValue++
            fixture.awaitArtwork(DesktopMaidAnimation.CLEANING)
            fixture.pumpFor(700)
            assertEquals(0, finishes)
            assertEquals(1, background.listenerCount)
            fixture.assertArtwork(DesktopMaidAnimation.CLEANING, "blue-snow-cleaning")
        } finally { fixture.close() }
        assertEquals(0, background.listenerCount)
        assertEquals(background.added, background.removed)
        assertEquals(0, fixture.lifecycle.registry.observerCount)
        assertEquals(0, finishes)
    }

    @Test fun originalErrorAndEmptyRenderPackagedCharacterAndDispatchRealPointerControlsInBothUiStyles(): Unit = runBlocking {
        for (style in AppUiStyle.entries) {
            val error = mutableStateOf(true)
            var retries = 0
            var actions = 0
            val background = Background()
            val fixture = Scene(background, style) {
                if (error.value) ErrorState(message = "原加载失败验收", enableEasterEgg = false, onRetry = { retries++ })
                else EmptyState(message = "原空内容验收", subtitle = "私有 CPU 离屏组件",
                    enableEasterEgg = false, actionText = "重新加载内容", onAction = { actions++ })
            }
            try {
                fixture.awaitArtwork(DesktopMaidAnimation.RETRY)
                assertEquals(true, fixture.observedReducedMotion)
                fixture.assertArtwork(DesktopMaidAnimation.RETRY, "${style.name.lowercase()}-error")
                fixture.clickText("重试")
                fixture.await("$style original retry dispatch") { retries == 1 }
                assertEquals(0, actions)
                val addedBeforeReplay = background.added
                fixture.clickReplay()
                fixture.await("$style original character replay identity") { background.added > addedBeforeReplay }
                assertEquals(1, background.listenerCount)
                assertEquals(1, retries, "Character click must not trigger retry")

                error.value = false
                fixture.await("$style original empty content") { fixture.hasText("原空内容验收") }
                fixture.awaitArtwork(DesktopMaidAnimation.EMPTY)
                // Retain the current input state separately: the real character replay click
                // leaves the mouse over this same-size clickable region after switching UI.
                fixture.assertArtwork(DesktopMaidAnimation.EMPTY, "${style.name.lowercase()}-empty-hovered")
                fixture.movePointerAway()
                fixture.pumpFor(500)
                fixture.assertArtwork(DesktopMaidAnimation.EMPTY, "${style.name.lowercase()}-empty")
                fixture.clickText("重新加载内容")
                fixture.await("$style original empty action dispatch") { actions == 1 }
                assertEquals(1, retries)
                val emptyReplayBefore = background.added
                fixture.clickReplay()
                fixture.await("$style empty character replay") { background.added > emptyReplayBefore }
                assertEquals(1, background.listenerCount)
                assertEquals(1, actions, "Character click must not trigger empty action")
            } finally { fixture.close() }
            assertEquals(0, background.listenerCount, "$style disposal removes window observers")
            assertEquals(background.added, background.removed)
            assertEquals(0, fixture.lifecycle.registry.observerCount)
        }
    }

    private class Background : DesktopHomeWindowBackgroundPort {
        private val listeners = linkedSetOf<DesktopHomeWindowBackgroundPort.Listener>()
        override var isInBackground: Boolean = false
            private set
        var added = 0
            private set
        var removed = 0
            private set
        val listenerCount get() = listeners.size
        override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) { check(listeners.add(listener)); added++ }
        override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) { check(listeners.remove(listener)); removed++ }
        fun setBackground(value: Boolean) {
            isInBackground = value
            listeners.toList().forEach { if (value) it.onEnterBackground() else it.onEnterForeground() }
        }
    }

    private class ResumedLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private inner class Scene(background: Background, style: AppUiStyle = AppUiStyle.MATERIAL3, content: @Composable () -> Unit) : AutoCloseable {
        val lifecycle = ResumedLifecycle()
        var observedReducedMotion: Boolean? = null
            private set
        private var lastNanos = 0L
        private val platform = DesktopHomePlatform(false, false, false, false, false, false, background, 0f, false)
        private val scene: ImageComposeScene
        init {
            check(GraphicsEnvironment.isHeadless()) { "CPU component QA requires the existing headless test JVM" }
            scene = ImageComposeScene(640, 620, Density(1f)) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, hapticFeedbackEnabled = false), systemLanguageTags = listOf("en")) {
                    CompositionLocalProvider(LocalDesktopHomePlatform provides platform, LocalLifecycleOwner provides lifecycle) {
                        DesktopDetailWindow {
                            observedReducedMotion = rememberSystemReduceMotion()
                            Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() } }
                        }
                    }
                }
            }
        }
        private fun render(): org.jetbrains.skia.Image {
            lastNanos = maxOf(System.nanoTime(), lastNanos + 1)
            return scene.render(lastNanos)
        }
        suspend fun pumpFor(milliseconds: Long) {
            val end = System.nanoTime() + milliseconds * 1_000_000
            do { render().close(); delay(12) } while (System.nanoTime() < end)
        }
        suspend fun await(description: String, condition: () -> Boolean) {
            withTimeout(4_000) {
                do { render().close(); if (condition()) return@withTimeout; delay(12) } while (true)
            }
            assertTrue(condition(), description)
        }
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        private fun nodes() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        private fun text(node: SemanticsNode, value: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        fun hasText(value: String) = nodes().any { text(it, value) }
        private fun artworkNode() = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("蓝雪女仆") == true }
        private fun click(node: SemanticsNode) {
            check(node.config.getOrNull(SemanticsActions.OnClick) != null && node.config.getOrNull(SemanticsProperties.Disabled) == null)
            val point = node.boundsInRoot.center
            check(point.x in 0f..640f && point.y in 0f..620f)
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = lastNanos / 1_000_000,
                buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = lastNanos / 1_000_000 + 40, buttons = PointerButtons())
        }
        fun clickText(value: String) = click(nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null && nodes(node).any { text(it, value) }
        }.minBy { it.boundsInRoot.width * it.boundsInRoot.height })
        fun clickReplay() = click(nodes().single { it.config.getOrNull(SemanticsActions.OnClick)?.label == "重播蓝雪女仆动画" })
        fun movePointerAway() {
            // Real Mouse Move inside the CPU scene, outside the character/actions. This does
            // not change any indication, production preference, bitmap, or component state.
            scene.sendPointerEvent(PointerEventType.Move, Offset(8f, 8f), timeMillis = lastNanos / 1_000_000,
                buttons = PointerButtons())
        }
        suspend fun awaitArtwork(animation: DesktopMaidAnimation) {
            await("actual packaged ${animation.name} character") { runCatching { assertArtwork(animation); true }.getOrDefault(false) }
        }
        fun assertArtwork(animation: DesktopMaidAnimation, artifact: String? = null) {
            val bounds = artworkNode().boundsInRoot
            check(bounds.width > 80 && bounds.height > 80 && bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 640 && bounds.bottom <= 620)
            val encoded = render().use { requireNotNull(it.encodeToData(EncodedImageFormat.PNG)).use { data -> data.bytes } }
            val actual = requireNotNull(ImageIO.read(ByteArrayInputStream(encoded)))
            val original = requireNotNull(javaClass.getResourceAsStream("/brand-motion/${animation.asset.pngFileName}")).use { requireNotNull(ImageIO.read(it)) }
            val palette = mutableSetOf<Int>()
            var sourceCoverage = 0
            for (y in 0 until original.height) for (x in 0 until original.width) if (original.getRGB(x, y).ushr(24) >= 128) {
                sourceCoverage++; palette += bucket(original.getRGB(x, y))
            }
            val left = ceil(bounds.left).toInt(); val top = ceil(bounds.top).toInt()
            val right = floor(bounds.right).toInt(); val bottom = floor(bounds.bottom).toInt()
            val scenePalette = mutableSetOf<Int>()
            var retainedPixels = 0
            for (y in top until bottom) for (x in left until right) {
                val color = bucket(actual.getRGB(x, y))
                scenePalette += color
                if (color in palette) retainedPixels++
            }
            assertTrue(sourceCoverage > 1000 && palette.size > 8, "Fixed original PNG has meaningful artwork")
            assertTrue(scenePalette.intersect(palette).size >= min(16, palette.size / 2), "Actual character Canvas retains original PNG palette")
            assertTrue(retainedPixels > sourceCoverage.toDouble() * (right - left) * (bottom - top) / (512 * 512) / 8,
                "Actual character Canvas has substantial original-art pixels, not blank semantics")
            if (artifact != null) artifactRoot?.let { Files.write(it.resolve("$artifact.png"), encoded, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) }
        }
        override fun close() = scene.close()
    }

    private fun bucket(color: Int): Int = ((color ushr 19) and 31) shl 10 or (((color ushr 11) and 31) shl 5) or ((color ushr 3) and 31)
    private companion object {
        // JUnit creates one class instance per method: share only the test artifact directory,
        // never component resources/players, so optional evidence remains exclusive across tests.
        val artifactRoot: Path? by lazy {
            System.getenv("BILIPAI_BRAND_COMPONENT_QA_OUTPUT")?.takeIf { it.isNotBlank() }?.let { supplied ->
                val directory = Path.of(supplied).toAbsolutePath().normalize()
                check(!Files.exists(directory) && Files.isDirectory(directory.parent)) { "Optional CPU screenshot directory must be fresh with an existing parent" }
                Files.createDirectory(directory)
            }
        }
    }
}
