package com.bilipai.desktop.settings

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

import com.bilipai.desktop.brand.DesktopMaidAnimation
import com.android.purebilibili.feature.settings.CacheClearProgress
import com.android.purebilibili.feature.settings.CacheClearAnimationDialog
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/** Whole fixed original UI on CPU ImageComposeScene; synthetic progress only.
 * No storage owner, cache manager, profile, OS/native window or clear operation is constructed.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopCacheClearComponentUiTest {
    @Test fun originalCleaningShowsActualProgressAndCharacterButNeverCompletesBusinessInEitherUiStyle(): Unit = runBlocking {
        for (style in AppUiStyle.entries) {
            val progress = mutableStateOf(CacheClearProgress(1, 4, clearedSize = "1.0 MB"))
            var dismissals = 0
            val background = Background()
            val fixture = Scene(background, style) {
                CacheClearAnimationDialog(progress = progress.value, onDismiss = { dismissals++ })
            }
            try {
                fixture.await("$style original progress labels") {
                    fixture.hasText("正在清理") && fixture.hasText("25%") && fixture.hasText("已清理 1.0 MB")
                }
                fixture.awaitArtwork(DesktopMaidAnimation.CLEANING)
                assertEquals(true, fixture.observedReducedMotion)
                fixture.awaitSettledText("正在清理", "25%", "已清理 1.0 MB")
                fixture.assertArtwork(DesktopMaidAnimation.CLEANING, "${style.name.lowercase()}-cache-cleaning")
                fixture.pumpFor(2_100)
                assertEquals(0, dismissals, "Looping artwork cannot mark cleanup complete or close an in-flight operation")
                assertFalse(progress.value.isComplete)
                progress.value = CacheClearProgress(0, 0)
                fixture.await("$style unknown-total original state") {
                    fixture.hasText("正在清理") && fixture.hasText("准备中…") && !fixture.hasText("25%")
                }
                assertEquals(0, dismissals)
            } finally { fixture.close() }
            assertEquals(0, background.listenerCount)
            assertEquals(background.added, background.removed)
            assertEquals(0, fixture.lifecycle.registry.observerCount)
        }
    }

    @Test fun onlyPublishedCompleteProgressShowsOriginalSuccessAndDispatchesOneDelayedDismissInBothUiStyles(): Unit = runBlocking {
        for (style in AppUiStyle.entries) {
            val show = mutableStateOf(true)
            val completed = CacheClearProgress(1, 1, isComplete = true, clearedSize = "2.0 MB")
            var dismissals = 0
            val background = Background()
            val fixture = Scene(background, style) {
                if (show.value) CacheClearAnimationDialog(progress = completed, onDismiss = {
                    dismissals++
                    show.value = false
                })
            }
            try {
                fixture.await("$style complete operation labels") {
                    fixture.hasText("清理完成") && fixture.hasText("共释放 2.0 MB") && fixture.hasText("即将自动关闭…")
                }
                fixture.awaitArtwork(DesktopMaidAnimation.CLEAN_COMPLETE)
                fixture.awaitSettledText("清理完成", "共释放 2.0 MB", "即将自动关闭…")
                fixture.assertArtwork(DesktopMaidAnimation.CLEAN_COMPLETE, "${style.name.lowercase()}-cache-complete")
                fixture.await("$style original 2000ms completion dismissal") { dismissals == 1 && !show.value }
                fixture.pumpFor(350)
                assertEquals(1, dismissals)
                assertTrue(completed.isComplete, "Dismiss is presentation state, not mutation of the supplied progress")
                assertEquals(0, background.listenerCount)
            } finally { fixture.close() }
            assertEquals(background.added, background.removed)
            assertEquals(0, fixture.lifecycle.registry.observerCount)
        }
    }

    @Test fun retiredCompleteTimerCannotDismissNewPendingProgressAndHostUsesCurrentOwnerAndPublication(): Unit = runBlocking {
        val completed = CacheClearProgress(1, 1, isComplete = true)
        val pending = CacheClearProgress(0, 1)
        val progress = mutableStateOf(completed)
        var dismissals = 0
        var owned = true
        val background = Background()
        val fixture = Scene(background) {
            CacheClearAnimationDialog(progress = progress.value, onDismiss = { dismissals++ })
        }
        try {
            fixture.await("first complete original content") { fixture.hasText("清理完成") }
            assertTrue(canDismissDesktopCacheClearProgress(completed, { owned }, { progress.value === completed }))
            progress.value = pending
            fixture.await("replacement pending original content") { fixture.hasText("正在清理") }
            assertFalse(canDismissDesktopCacheClearProgress(completed, { owned }, { progress.value === completed }),
                "Late native close/finish from an old publication cannot dismiss the successor")
            assertFalse(canDismissDesktopCacheClearProgress(pending, { owned }, { progress.value === pending }))
            fixture.pumpFor(2_150)
            assertEquals(0, dismissals, "The actual original LaunchedEffect retired its complete timer")
            owned = false
            assertFalse(canDismissDesktopCacheClearProgress(completed, { owned }, { true }))
            assertFalse(canDismissDesktopCacheClearProgress(completed, { true }, { false }))
        } finally { fixture.close() }
        assertEquals(0, background.listenerCount)
        assertEquals(background.added, background.removed)
        assertEquals(0, fixture.lifecycle.registry.observerCount)
        assertEquals(0, dismissals)
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
        private val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        private val scene: ImageComposeScene
        init {
            check(GraphicsEnvironment.isHeadless()) { "CPU component QA requires the existing headless test JVM" }
            scene = ImageComposeScene(640, 620, Density(1f)) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, hapticFeedbackEnabled = false), systemLanguageTags = listOf("en")) {
                    CompositionLocalProvider(LocalDesktopHomePlatform provides platform, LocalLifecycleOwner provides lifecycle, LocalNavigationEventDispatcherOwner provides navigation) {
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
        suspend fun awaitSettledText(vararg values: String) {
            // The original sheet slides into view. Semantics and character
            // pixels alone can be ready while its final status line is clipped.
            var previous: List<androidx.compose.ui.geometry.Rect>? = null
            var stableFrames = 0
            await("complete original sheet labels are visible after entrance") {
                val labels = values.map { value -> nodes().firstOrNull { text(it, value) } }
                if (labels.any { it == null }) {
                    previous = null; stableFrames = 0; false
                } else {
                    val actual = labels.filterNotNull()
                    val bounds = actual.map { it.boundsInRoot }
                    val fullyVisible = actual.zip(bounds).all { (node, rect) ->
                        rect.width > 0 && rect.height > 0 && rect.left >= 0 && rect.top >= 0 &&
                            rect.right <= 640 && rect.bottom <= 620 &&
                            rect.width >= node.size.width - 0.5f && rect.height >= node.size.height - 0.5f
                    }
                    stableFrames = if (fullyVisible && bounds == previous) stableFrames + 1 else 0
                    previous = bounds
                    stableFrames >= 3
                }
            }
        }
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
        override fun close() {
            scene.close()
            navigation.navigationEventDispatcher.dispose()
        }
    }

    private fun bucket(color: Int): Int = ((color ushr 19) and 31) shl 10 or (((color ushr 11) and 31) shl 5) or ((color ushr 3) and 31)
    private companion object {
        // JUnit creates one class instance per method: share only the test artifact directory,
        // never component resources/players, so optional evidence remains exclusive across tests.
        val artifactRoot: Path? by lazy {
            System.getenv("BILIPAI_CACHE_CLEAR_COMPONENT_QA_OUTPUT")?.takeIf { it.isNotBlank() }?.let { supplied ->
                val directory = Path.of(supplied).toAbsolutePath().normalize()
                check(!Files.exists(directory) && Files.isDirectory(directory.parent)) { "Optional CPU screenshot directory must be fresh with an existing parent" }
                Files.createDirectory(directory)
            }
        }
    }
}
