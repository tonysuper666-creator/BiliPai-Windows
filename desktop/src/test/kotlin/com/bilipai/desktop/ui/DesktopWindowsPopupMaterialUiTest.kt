@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.core.store.HomeWallpaperEffectMode
import com.android.purebilibili.core.store.HomeWallpaperEffectScope
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.AppPopupSurface
import com.android.purebilibili.core.ui.AppPopupSurfaceType
import com.android.purebilibili.core.ui.LocalAppPopupSurfaceRenderer
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

/** Actual AppPopupSurface + original FloatingDock/Lens on a CPU Compose scene.
 * Only wallpaper transport is synthetic. No native window/Canvas, screenshot
 * backdrop, user file, account, settings writer or second material renderer.
 */
class DesktopWindowsPopupMaterialUiTest {
    @Test fun bothStylesSampleTheirOwnRecordedWallpaperAndManualOffKeepsControlsMounted(): Unit = runBlocking {
        for (style in AppUiStyle.entries) {
            val fixture = Scene(style)
            try {
                fixture.await("own completed original background") {
                    fixture.popupMaterial?.sourceReady == true && fixture.popupMaterial?.sourceHasWallpaper == true
                }
                assertTrue(assertNotNull(fixture.popupMaterial).renderer.supported, "Actual CPU shader/blur preflight")
                assertNotSame(fixture.rootBackdrop, fixture.popupMaterial?.backdrop,
                    "A popup cannot inherit Root's differently positioned layer")
                assertSame(DesktopWindowsPopupSurfaceRenderer, fixture.popupRenderer)
                assertTrue(fixture.wallpaperDraws > 0)
                val mounted = assertNotNull(fixture.contentIdentity)
                val glass = fixture.image("${style.name.lowercase()}-glass")
                val glassContrast = contrast(glass)
                assertTrue(glassContrast > 12, "Actual glass must retain the two-part background rather than opaque fill")

                fixture.clickAction()
                fixture.await("original button pointer dispatch") { fixture.actions == 1 }
                fixture.theme.value = fixture.theme.value.copy(liquidGlassEnabled = false)
                fixture.pumpFor(100)
                val off = fixture.image("${style.name.lowercase()}-off")
                assertTrue(contrast(off) <= 4, "Explicit OFF uses original opaque AppSurface")
                assertTrue(glassContrast > contrast(off) + 8)
                assertSame(mounted, fixture.contentIdentity, "ACK/OFF cannot remount original controls")

                fixture.theme.value = fixture.theme.value.copy(liquidGlassEnabled = true)
                fixture.await("same-content glass enabled again") { contrast(fixture.image()) > 12 }
                assertSame(mounted, fixture.contentIdentity)
                fixture.clickAction()
                fixture.await("same original control after material changes") { fixture.actions == 2 }

                // Removing the renderer is a fixture-only switch to the other
                // original AppPopupSurface branch. Compare its pixels AFTER
                // checking production ON/OFF continuity; that branch remounts.
                fixture.theme.value = fixture.theme.value.copy(liquidGlassEnabled = false)
                fixture.removeRenderer.value = true
                fixture.pumpFor(80)
                val originalFallback = fixture.image()
                for ((x, y) in samplePoints) assertEquals(off.getRGB(x, y), originalFallback.getRGB(x, y),
                    "OFF retains the selected original AppSurface color/elevation behavior")
            } finally { fixture.close() }
        }
    }

    @Test fun animatedWallpaperAndRetiredOrUnsupportedSourceUseOriginalSafeSurface(): Unit = runBlocking {
        val fixture = Scene(AppUiStyle.MATERIAL3, initialUri = "fixture://original-wallpaper.mp4")
        try {
            fixture.await("completed original solid-color background") { fixture.popupMaterial?.sourceReady == true }
            assertFalse(assertNotNull(fixture.popupMaterial).sourceHasWallpaper)
            assertEquals(0, fixture.wallpaperDraws, "Animated wallpaper is not sampled or played by the popup")
            val mounted = assertNotNull(fixture.contentIdentity)
            fixture.forceUnsupported.value = true
            fixture.pumpFor(80)
            val unsupported = fixture.image("unsupported-solid")
            assertFalse(assertNotNull(fixture.popupMaterial).renderer.supported)
            assertSame(mounted, fixture.contentIdentity)
            fixture.forceUnsupported.value = false
            fixture.owned.value = false
            fixture.pumpFor(80)
            assertFalse(assertNotNull(fixture.popupMaterial).owns())
            val retired = fixture.image("retired-solid")
            assertSame(mounted, fixture.contentIdentity)
            fixture.removeRenderer.value = true
            fixture.pumpFor(80)
            val original = fixture.image()
            for ((x, y) in samplePoints) {
                assertEquals(original.getRGB(x, y), unsupported.getRGB(x, y))
                assertEquals(original.getRGB(x, y), retired.getRGB(x, y))
            }
        } finally { fixture.close() }
    }

    private class Scene(style: AppUiStyle, initialUri: String = "fixture://original-wallpaper.png") : AutoCloseable {
        val theme = mutableStateOf(DesktopThemeSettings(uiStyle = style, liquidGlassEnabled = true,
            hapticFeedbackEnabled = false, themeMode = com.android.purebilibili.feature.settings.AppThemeMode.LIGHT))
        val owned = mutableStateOf(true)
        val forceUnsupported = mutableStateOf(false)
        val removeRenderer = mutableStateOf(false)
        private val rootOwner = Any()
        private val popupOwner = Any()
        private val uri = initialUri
        var rootBackdrop: Any? = null
        var popupMaterial: DesktopWindowsGlassMaterialBinding? = null
        var popupRenderer: Any? = null
        var contentIdentity: Any? = null
        var wallpaperDraws = 0
        var actions = 0
        private var lastNanos = 0L
        private val media = DesktopHomeMediaPorts(MutableStateFlow(false),
            previewSurface = { _, _, _, _ -> error("Popup must not acquire native preview") },
            feedback = {}, wallpaper = object : DesktopHomeWallpaperPort {
                @Composable override fun wallpaperSurface(uri: String, imageModel: Any, playbackEnabled: Boolean, modifier: Modifier) {
                    check(uri == this@Scene.uri && !playbackEnabled)
                    Canvas(modifier) {
                        wallpaperDraws++
                        drawRect(Color(0xFF00C8DC), size = Size(size.width / 2, size.height))
                        drawRect(Color(0xFFEC30BE), topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height))
                    }
                }
            })
        private val scene: ImageComposeScene
        init {
            check(GraphicsEnvironment.isHeadless())
            scene = ImageComposeScene(600, 360, Density(1f)) {
                DesktopAppearanceTheme(theme.value, systemLanguageTags = listOf("en")) {
                    CompositionLocalProvider(LocalDesktopHomeMediaPorts provides media) {
                        DesktopDetailWindow {
                            DesktopWindowsGlassBackgroundHost(rootOwner, uri,
                                HomeSettings(homeWallpaperEffectMode = HomeWallpaperEffectMode.ORIGINAL,
                                    homeWallpaperEffectScope = HomeWallpaperEffectScope.GLOBAL),
                                showHomeWallpaper = false, isDataSaverActive = false, owns = { owned.value }) {
                                rootBackdrop = LocalDesktopWindowsGlassMaterial.current?.backdrop
                                DesktopWindowsPopupMaterialHost(popupOwner, owns = { owned.value }) {
                                    val actual = checkNotNull(LocalDesktopWindowsGlassMaterial.current)
                                    val projected = if (forceUnsupported.value) actual.copy(
                                        renderer = DesktopWindowsGlassRendererSupport(false, "Synthetic unsupported capability")) else actual
                                    CompositionLocalProvider(LocalDesktopWindowsGlassMaterial provides projected,
                                        LocalAppPopupSurfaceRenderer provides if (removeRenderer.value) null else DesktopWindowsPopupSurfaceRenderer) {
                                        popupMaterial = LocalDesktopWindowsGlassMaterial.current
                                        popupRenderer = LocalAppPopupSurfaceRenderer.current
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            AppPopupSurface(type = AppPopupSurfaceType.DIALOG,
                                                shape = RoundedCornerShape(18.dp), containerColor = MaterialTheme.colorScheme.surface,
                                                contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 2.dp,
                                                modifier = Modifier.size(280.dp, 180.dp)) {
                                                contentIdentity = remember { Any() }
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                                                    AppTextButton(onClick = { actions++ }, modifier = Modifier.padding(bottom = 12.dp)) {
                                                        AppText("原弹窗操作")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
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
        fun image(artifact: String? = null): BufferedImage {
            val bytes = render().use { image -> requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes } }
            artifact?.let { name -> artifacts?.let { Files.write(it.resolve("$name.png"), bytes, CREATE_NEW, WRITE) } }
            return requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        }
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun clickAction() {
            val all = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            val button = all.filter { node -> node.config.getOrNull(SemanticsActions.OnClick) != null &&
                nodes(node).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "原弹窗操作" } == true }
            }.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
            assertNull(button.config.getOrNull(SemanticsProperties.Disabled))
            val point = button.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = lastNanos / 1_000_000,
                buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = lastNanos / 1_000_000 + 40,
                buttons = PointerButtons())
            scene.sendPointerEvent(PointerEventType.Move, Offset(8f, 8f), timeMillis = lastNanos / 1_000_000 + 41,
                buttons = PointerButtons())
        }
        override fun close() = scene.close()
    }
    private companion object {
        val samplePoints = listOf(240 to 140, 360 to 140, 240 to 165, 360 to 165)
        fun contrast(image: BufferedImage): Int {
            val left = java.awt.Color(image.getRGB(240, 140))
            val right = java.awt.Color(image.getRGB(360, 140))
            return maxOf(abs(left.red - right.red), abs(left.green - right.green), abs(left.blue - right.blue))
        }
        val artifacts: Path? by lazy {
            System.getenv("BILIPAI_POPUP_MATERIAL_QA_OUTPUT")?.takeIf { it.isNotBlank() }?.let { supplied ->
                val path = Path.of(supplied).toAbsolutePath().normalize()
                check(!Files.exists(path) && Files.isDirectory(path.parent))
                Files.createDirectory(path)
            }
        }
    }
}
