@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.asAndroidContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopDynamicTimelinePreferences
import com.bilipai.desktop.settings.DesktopHomeCardPreferences
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences
import com.bilipai.desktop.settings.LocalDesktopHomeCardPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

/** The production panel and complete generated original skeletons are actually
 * measured and rasterized on the CPU. Content and retry slots model their public
 * composable boundary; this is not a native PhysicalLeaf/VM/network acceptance test.
 * No Window, HWND, player, image loader, user account, clipboard or GPU is created.
 */
class DesktopWindowsVideoDetailsPanelLayoutTest {
    @TempDir lateinit var root: Path

    @Test fun originalSkeletonsYieldToFailureRetryAndSelectedContentInRealFinitePanel(): Unit = runBlocking {
        val appearances = listOf(AppUiStyle.MATERIAL3 to AppThemeMode.LIGHT, AppUiStyle.MIUIX to AppThemeMode.DARK)
        for ((style, mode) in appearances) {
            Fixture(root.resolve("transition-${style.name}"), style, mode).use { f ->
                for (tab in DesktopWindowsVideoDetailsTab.entries) {
                    f.selected.value = tab
                    f.phase.value = Phase.LOADING
                    f.settle()
                    f.assertPanelBounds()
                    assertTrue(f.mountedSlots.isEmpty(), "Loading must not compose success content for $tab")
                    assertFalse(f.hasText(FAILURE))
                    assertFalse(f.hasText(tab.title), "Loading owns the original skeleton tab chrome")
                    f.assertSkeletonIsRendered()

                    f.phase.value = Phase.ERROR
                    f.settle()
                    assertTrue(f.hasText(FAILURE))
                    assertTrue(f.mountedSlots.isEmpty())
                    f.assertPanelBounds()
                    val previousRetries = f.retries
                    f.click("重试")
                    assertEquals(previousRetries + 1, f.retries, "Real pointer input reaches the supplied retry slot")
                    assertEquals(Phase.LOADING, f.phase.value)
                    assertFalse(f.hasText(FAILURE))

                    f.phase.value = Phase.CONTENT
                    f.settle()
                    assertEquals(setOf(tab), f.mountedSlots)
                    assertTrue(f.hasText(contentTitle(tab)))
                    assertFalse(f.hasText(FAILURE))
                    f.assertPanelBounds()
                    f.assertSelectedTab(tab)

                    val nextTab = DesktopWindowsVideoDetailsTab.entries[(tab.ordinal + 1) % 3]
                    val previousChanges = f.tabChanges
                    f.click(nextTab.title)
                    assertEquals(previousChanges + 1, f.tabChanges)
                    assertEquals(nextTab, f.selected.value)
                    assertEquals(setOf(nextTab), f.mountedSlots)
                    assertTrue(f.hasText(contentTitle(nextTab)))
                }
                f.clickDescription("关闭详情")
                assertEquals(1, f.closes)
                f.assertPreferencesUnchanged()
            }
        }
    }

    @Test fun originalPanesResizeAndZoomWithinSameCompositionAndFailureTakesPrecedence(): Unit = runBlocking {
        Fixture(root.resolve("resize"), AppUiStyle.MIUIX, AppThemeMode.DARK).use { f ->
            f.settle()
            val composition = f.compositionIdentity
            val sizes = listOf(
                IntSize(420, 640) to 1f,
                IntSize(1024, 900) to 1.25f,
                IntSize(1600, 1100) to 2f,
                IntSize(640, 720) to 1.5f,
            )
            for (tab in DesktopWindowsVideoDetailsTab.entries) {
                f.selected.value = tab
                for ((size, scale) in sizes) {
                    f.resize(size, scale)
                    f.phase.value = Phase.LOADING
                    f.settle()
                    assertSame(composition, f.compositionIdentity)
                    f.assertPanelBounds()
                    assertTrue(f.mountedSlots.isEmpty())
                    f.assertSkeletonIsRendered()
                    // loading=true and a failure slot may coexist during recovery.
                    // The actual panel must expose retry rather than hide the failure.
                    f.phase.value = Phase.ERROR_WHILE_LOADING
                    f.settle()
                    assertTrue(f.hasText(FAILURE))
                    assertTrue(f.mountedSlots.isEmpty())
                    f.assertPanelBounds()
                    f.phase.value = Phase.CONTENT
                    f.settle()
                    assertSame(composition, f.compositionIdentity)
                    assertEquals(setOf(tab), f.mountedSlots)
                    f.assertSelectedTab(tab)
                    f.assertPanelBounds()
                }
            }
            val selected = f.selected.value
            val changes = f.tabChanges
            f.owned.value = false
            f.click(DesktopWindowsVideoDetailsTab.entries[(selected.ordinal + 1) % 3].title)
            assertEquals(selected, f.selected.value, "Retired panel tab callback is rejected by actual current() guard")
            assertEquals(changes, f.tabChanges)
            f.assertPreferencesUnchanged()
        }
    }

    private enum class Phase { LOADING, ERROR, ERROR_WHILE_LOADING, CONTENT }

    private class Fixture(directory: Path, style: AppUiStyle, mode: AppThemeMode) : AutoCloseable {
        val selected = mutableStateOf(DesktopWindowsVideoDetailsTab.INTRODUCTION)
        val phase = mutableStateOf(Phase.LOADING)
        val owned = mutableStateOf(true)
        private val viewport = mutableStateOf(IntSize(480, 700))
        private val scale = mutableStateOf(1f)
        val mountedSlots = linkedSetOf<DesktopWindowsVideoDetailsTab>()
        var retries = 0
        var closes = 0
        var tabChanges = 0
        var compositionIdentity: Any? = null
        private var panelBounds = Rect.Zero
        private var slotBounds = Rect.Zero
        private var nanos = 0L
        private val backing: Path
        private val initialBacking: ByteArray
        private val scene: ImageComposeScene

        init {
            check(GraphicsEnvironment.isHeadless()) { "This fixture requires CPU headless rendering" }
            Files.createDirectory(directory)
            val context = DesktopPluginContext(DesktopPluginStore(directory))
            context.store.update("settings", mapOf(
                "skeleton_breathing_enabled" to JsonPrimitive(true),
                "details_layout_fixture_marker" to JsonPrimitive("temporary fixture only"),
            ))
            val home = DesktopHomeCardPreferences(context)
            val dynamic = DesktopDynamicTimelinePreferences(context)
            backing = directory.resolve("plugin-settings.json")
            initialBacking = Files.readAllBytes(backing)
            scene = ImageComposeScene(1600, 1100, Density(1f)) {
                val actualWindow = LocalWindowInfo.current
                val currentWindow = remember(actualWindow) { object : WindowInfo by actualWindow {
                    override val containerSize: IntSize get() = viewport.value
                } }
                CompositionLocalProvider(
                    LocalDensity provides Density(scale.value),
                    LocalWindowInfo provides currentWindow,
                    LocalDesktopHomeCardPreferences provides home,
                    LocalDesktopDynamicTimelinePreferences provides dynamic,
                    // The window root provides this for upstream LocalContext reads (DesktopShell).
                    LocalDesktopUpstreamAndroidContext provides context.asAndroidContext(),
                ) {
                    DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, themeMode = mode,
                        hapticFeedbackEnabled = false), systemLanguageTags = listOf("zh-CN")) {
                        compositionIdentity = remember { Any() }
                        DesktopWindowsVideoDetailsPanel(
                            Modifier.fillMaxSize().onGloballyPositioned { panelBounds = it.boundsInRoot() },
                            selectedTab = selected.value,
                            onTabChange = { tabChanges++; selected.value = it },
                            onClose = { closes++ },
                            current = { owned.value },
                            related = { Content(DesktopWindowsVideoDetailsTab.RELATED) },
                            introduction = { Content(DesktopWindowsVideoDetailsTab.INTRODUCTION) },
                            comments = { Content(DesktopWindowsVideoDetailsTab.COMMENTS) },
                            loading = phase.value == Phase.LOADING || phase.value == Phase.ERROR_WHILE_LOADING,
                            failureContent = if (phase.value == Phase.ERROR || phase.value == Phase.ERROR_WHILE_LOADING) {{
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(FAILURE)
                                    TextButton(onClick = { retries++; phase.value = Phase.LOADING }) { Text("重试") }
                                }
                            }} else null,
                        )
                    }
                }
            }
            scene.constraints = Constraints.fixed(480, 700)
        }

        @Composable private fun Content(tab: DesktopWindowsVideoDetailsTab) {
            DisposableEffect(tab) {
                mountedSlots += tab
                onDispose { mountedSlots -= tab }
            }
            val measured = Modifier.fillMaxWidth().onGloballyPositioned { slotBounds = it.boundsInRoot() }
            if (tab == DesktopWindowsVideoDetailsTab.INTRODUCTION) {
                Column(measured) {
                    Text(contentTitle(tab))
                    Text("分P 1：临时测试条目")
                }
            } else {
                LazyColumn(measured.fillMaxHeight()) {
                    item { Text(contentTitle(tab)) }
                    items((1..12).toList()) { index -> Text("临时内容 $index", Modifier.padding(vertical = 8.dp)) }
                }
            }
        }

        fun resize(size: IntSize, density: Float) {
            viewport.value = size
            scale.value = density
            scene.constraints = Constraints.fixed(size.width, size.height)
        }

        suspend fun settle() {
            repeat(5) { nanos += 40_000_000; scene.render(nanos).close(); yield() }
        }

        private fun nodes(root: SemanticsNode): List<SemanticsNode> = listOf(root) + root.children.flatMap(::nodes)
        private fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun hasText(text: String) = all().any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
        }

        private suspend fun clickNode(node: SemanticsNode) {
            val bounds = node.boundsInRoot
            assertTrue(bounds.width > 0 && bounds.height > 0)
            assertTrue(bounds.center.x in 0f..viewport.value.width.toFloat())
            assertTrue(bounds.center.y in 0f..viewport.value.height.toFloat())
            scene.sendPointerEvent(PointerEventType.Press, bounds.center, timeMillis = nanos / 1_000_000,
                buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, bounds.center, timeMillis = nanos / 1_000_000 + 40,
                buttons = PointerButtons())
            settle()
        }

        suspend fun click(text: String) {
            val button = all().filter { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    nodes(node).any { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
            }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
            clickNode(assertNotNull(button, "Actual clickable semantic node for $text"))
        }

        suspend fun clickDescription(description: String) {
            val button = all().filter { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    nodes(node).any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }
            }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
            clickNode(assertNotNull(button, "Actual clickable semantic node for $description"))
        }

        fun assertSelectedTab(tab: DesktopWindowsVideoDetailsTab) {
            assertTrue(all().any { node ->
                node.config.getOrNull(SemanticsProperties.Selected) == true &&
                    nodes(node).any { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == tab.title } == true }
            }, "Actual selected tab must be $tab")
        }

        fun assertPanelBounds() {
            assertTrue(abs(panelBounds.width - viewport.value.width) < 0.5f)
            assertTrue(abs(panelBounds.height - viewport.value.height) < 0.5f)
            assertEquals(0f, panelBounds.left)
            assertEquals(0f, panelBounds.top)
            if (phase.value == Phase.CONTENT) {
                assertTrue(slotBounds.width > 0f && slotBounds.height > 0f)
                assertTrue(slotBounds.left >= 0f && slotBounds.right <= panelBounds.right + 0.5f)
                assertTrue(slotBounds.top > 0f && slotBounds.bottom <= panelBounds.bottom + 0.5f)
            }
            for (node in all().filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }) {
                val bounds = node.boundsInRoot
                assertTrue(bounds.width.isFinite() && bounds.height.isFinite())
                assertTrue(bounds.width > 0f && bounds.height > 0f)
                assertTrue(bounds.height < panelBounds.height, "Actual list receives finite remaining pane height")
            }
        }

        fun assertSkeletonIsRendered() {
            assertTrue(all().any { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null },
                "Complete original loading pane owns its actual LazyColumn")
            val image: BufferedImage = scene.render(nanos).use { skia ->
                skia.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    requireNotNull(ImageIO.read(ByteArrayInputStream(data.bytes)))
                }
            }
            // Below the unchanged header, a blank pane would be a single color.
            // Sample actual CPU pixels, without font-specific golden images.
            val d = scale.value
            val left = (16f * d).toInt()
            val right = viewport.value.width - left
            val top = (64f * d).toInt()
            val bottom = minOf(viewport.value.height - (16f * d).toInt(), (340f * d).toInt())
            val colors = linkedSetOf<Int>()
            for (y in top until bottom step 11) for (x in left until right step 13) colors += image.getRGB(x, y)
            assertTrue(colors.size > 1, "The real original skeleton must draw visible content below the header")
        }

        fun assertPreferencesUnchanged() = assertContentEquals(initialBacking, Files.readAllBytes(backing),
            "Rendering and zoom read the same temporary Root settings owner without writes")

        override fun close() = scene.close()
    }

    private companion object {
        const val FAILURE = "临时测试：视频信息加载失败"
        fun contentTitle(tab: DesktopWindowsVideoDetailsTab) = "已加载：${tab.title}"
    }
}
