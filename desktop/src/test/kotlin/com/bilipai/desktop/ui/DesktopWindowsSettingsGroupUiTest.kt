@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_ROW_SETTINGS_COMPACT_DP
import com.bilipai.desktop.ui.kit.LocalDesktopKitReducedMotion
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ImageComposeScene test (CPU scene, headless JVM; no native window, no GPU, no network): the settings group of
 * DesktopWindowsSettingsControls.kt on real layout and real pixels, at three densities. It runs the rules that the Python
 * source needles only read: a child that is not a settings row keeps the width it asks for (a button is not stretched into
 * a full-width bar), the dividers are one-pixel lines, a choice nested in a wrapper adds no inset of its own, and a radio
 * row grows with a two-line label. The harness is the one of DesktopKitUiTest. Assumption only a compile run can disprove:
 * the semantics tree of the scene reports a testTag node with the bounds of the layout node that carries it.
 */
class DesktopWindowsSettingsGroupUiTest {
    private class GroupScene(density: Float, content: @Composable () -> Unit) : AutoCloseable {
        init {
            check(GraphicsEnvironment.isHeadless()) { "CPU component tests require the existing headless test JVM" }
        }

        private val scene = ImageComposeScene(WIDTH, HEIGHT, Density(density)) {
            DesktopAppearanceTheme(
                DesktopThemeSettings(uiStyle = AppUiStyle.MATERIAL3, themeMode = AppThemeMode.LIGHT, hapticFeedbackEnabled = false),
                systemLanguageTags = listOf("en"),
            ) {
                CompositionLocalProvider(LocalDesktopKitReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
                }
            }
        }
        private var nanos = 0L

        fun settle(frames: Int = 6) {
            repeat(frames) {
                nanos += 30_000_000L
                scene.render(nanos).close()
            }
        }

        /** The pixels of one more frame, row by row, as ARGB integers. */
        fun pixels(): IntArray {
            nanos += 30_000_000L
            return scene.render(nanos).use { image ->
                Bitmap().use { bitmap ->
                    check(bitmap.allocN32Pixels(image.width, image.height) && image.readPixels(bitmap))
                    IntArray(WIDTH * HEIGHT) { index -> bitmap.getColor(index % WIDTH, index / WIDTH) }
                }
            }
        }

        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }

        fun tagged(tag: String): Rect =
            nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }.boundsInRoot

        fun withRole(role: Role): List<SemanticsNode> = nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == role }

        fun textBounds(text: String): Rect = nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }.boundsInRoot

        override fun close() = scene.close()
    }

    private fun px(density: Float, dp: Int): Int = Density(density).run { dp.dp.roundToPx() }

    private fun withGroup(density: Float, content: @Composable () -> Unit, inspect: (GroupScene) -> Unit) {
        val scene = GroupScene(density) { Box(Modifier.width(CONTAINER_DP.dp)) { content() } }
        try {
            scene.settle()
            inspect(scene)
        } finally {
            scene.close()
        }
    }

    @Test fun aChildThatIsNotARowKeepsItsOwnWidthStartAlignedAtTheGroupInsetWhileARowSpansTheContainer() {
        for (density in DENSITIES) {
            withGroup(density, {
                DesktopWindowsSettingsGroup("Group", Modifier.testTag("group")) {
                    Box(Modifier.testTag("wrap").size(100.dp, 20.dp))
                    DesktopWindowsSettingsSwitch("Switch", true) {}
                    Box(Modifier.testTag("second").size(60.dp, 20.dp))
                }
            }) { scene ->
                val group = scene.tagged("group")
                val wrap = scene.tagged("wrap")
                val second = scene.tagged("second")
                assertEquals(px(density, CONTAINER_DP).toFloat(), group.width, "$density: the group is as wide as its container")
                assertEquals(px(density, 100).toFloat(), wrap.width, "$density: a child that is not a row keeps its own width")
                assertEquals(px(density, 60).toFloat(), second.width, "$density: so does the next one")
                assertTrue(wrap.width < group.width, "$density: and it is narrower than the container, not a full-width bar")
                assertEquals(px(density, DESKTOP_SETTINGS_ROW_PADDING_DP).toFloat(), wrap.left - group.left, "$density: start aligned at the inset")
                val row = assertNotNull(scene.withRole(Role.Switch).maxByOrNull { it.boundsInRoot.width }, "$density: the switch row is there")
                assertEquals(group.left, row.boundsInRoot.left, "$density: a row starts at the container edge")
                assertEquals(group.right, row.boundsInRoot.right, "$density: and spans it to the other edge")
            }
        }
    }

    @Test fun theDividersAreExactlyOnePixelLinesBetweenTheChildrenAtEveryDensity() {
        for (density in DENSITIES) {
            withGroup(density, {
                DesktopWindowsSettingsGroup("Group", Modifier.testTag("group")) {
                    Box(Modifier.testTag("a").size(100.dp, 20.dp))
                    Box(Modifier.testTag("b").size(60.dp, 20.dp))
                    Box(Modifier.testTag("c").size(80.dp, 20.dp))
                }
            }) { scene ->
                val group = scene.tagged("group")
                val a = scene.tagged("a")
                val b = scene.tagged("b")
                val c = scene.tagged("c")
                val vertical = px(density, DESKTOP_SETTINGS_ROW_PADDING_VERTICAL_DP)
                val pixels = scene.pixels()
                // A column to the right of every child, so only the background and the dividers are painted on it.
                val x = (group.left + group.width * 0.8f).toInt()
                val background = pixels[a.top.toInt() * WIDTH + x]
                // From just under the top border row to just above the bottom one.
                val scanned = (a.top.toInt() - vertical + 2)..(c.bottom.toInt() + vertical - 2)
                val lines = scanned.filter { pixels[it * WIDTH + x] != background }
                assertEquals(listOf(a.bottom.toInt() + vertical, b.bottom.toInt() + vertical), lines,
                    "$density: two dividers, each one pixel row, one under each of the first two children")
                for (y in lines) {
                    val line = pixels[y * WIDTH + x]
                    assertEquals(line, pixels[y * WIDTH + group.left.toInt() + 3], "$density: the line reaches the left edge")
                    assertEquals(line, pixels[y * WIDTH + group.right.toInt() - 4], "$density: and the right edge")
                }
            }
        }
    }

    @Test fun aChoiceNestedInAWrapperOfTheGroupAddsNoInsetOfItsOwn() {
        val choices = listOf("a" to "A", "b" to "B")
        for (density in DENSITIES) {
            withGroup(density, {
                DesktopWindowsSettingsGroup("Group", Modifier.testTag("group")) {
                    Column(Modifier.testTag("wrapper")) {
                        DesktopWindowsSettingsChoice("Nested", "a", choices) {}
                    }
                    DesktopWindowsSettingsChoice("Direct", "a", choices) {}
                }
            }) { scene ->
                val group = scene.tagged("group")
                val wrapper = scene.tagged("wrapper")
                val nested = scene.textBounds("Nested")
                val direct = scene.textBounds("Direct")
                val inset = px(density, DESKTOP_SETTINGS_ROW_PADDING_DP).toFloat()
                assertEquals(group.left + inset, wrapper.left, "$density: the group pads the wrapper once")
                assertEquals(wrapper.left, nested.left, "$density: the nested choice adds no second inset")
                assertEquals(wrapper.top, nested.top, "$density: and no second vertical gap")
                assertEquals(nested.left, direct.left, "$density: it lines up with a choice that is a direct child")
            }
        }
    }

    @Test fun aRadioRowGrowsWithATwoLineLabelInsteadOfClippingIt() {
        val label = "A rather long radio label that needs two lines at this width"
        val choices = listOf("a" to "Alpha", "b" to label, "c" to "Gamma", "d" to "Delta", "e" to "Epsilon")
        for (density in DENSITIES) {
            val scene = GroupScene(density) {
                Box(Modifier.width(180.dp)) {
                    DesktopWindowsSettingsGroup("Group") { DesktopWindowsSettingsChoice("Pick", "a", choices) {} }
                }
            }
            try {
                scene.settle()
                val rows = scene.withRole(Role.RadioButton)
                assertEquals(5, rows.size, "$density: more than four choices are stacked radio rows")
                val compact = px(density, DESKTOP_KIT_ROW_SETTINGS_COMPACT_DP).toFloat()
                assertEquals(compact, rows[0].boundsInRoot.height, "$density: a one-line row is the compact height")
                val long = rows[1].boundsInRoot
                assertTrue(long.height > compact, "$density: a two-line label makes its row taller than the compact height")
                val text = scene.textBounds(label)
                assertTrue(text.top >= long.top && text.bottom <= long.bottom, "$density: the label is inside its row, not clipped")
            } finally {
                scene.close()
            }
        }
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val CONTAINER_DP = 300
        val DENSITIES = listOf(1f, 1.25f, 1.5f)
    }
}
