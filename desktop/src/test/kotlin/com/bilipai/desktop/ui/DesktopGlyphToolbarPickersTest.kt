package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.ProvideAppThemeConfig
import com.android.purebilibili.core.ui.appIconGlyphTextStyle
import com.android.purebilibili.core.ui.components.AppDurationPicker
import com.android.purebilibili.core.ui.components.AppFloatingToolbar
import com.android.purebilibili.core.ui.components.AppNumberPicker
import com.android.purebilibili.core.ui.components.AppWindowAction
import com.android.purebilibili.core.ui.components.AppWindowActionMenu
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.test.*

/**
 * Offscreen checks of the v0.4.6 glyph text style, number and duration pickers, floating toolbar and window action
 * menu: no native window, GPU, account or network. Popups are never opened; the cases cover composition, the picker
 * arithmetic and the Windows glyph style.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DesktopGlyphToolbarPickersTest {
    private val minus = "−"

    @Test fun `glyph text style is the shared app text style in both ui styles`() {
        for (style in AppUiStyle.entries) {
            var shared: TextStyle? = null
            var glyph: TextStyle? = null
            withScene(style) {
                shared = when (style) {
                    AppUiStyle.MATERIAL3 -> LocalTextStyle.current
                    AppUiStyle.MIUIX -> MiuixTheme.textStyles.main
                }
                glyph = appIconGlyphTextStyle()
                Text("glyph")
            }.use { }
            assertNotNull(glyph, style.name)
            assertEquals(shared, glyph, style.name)
        }
    }

    @Test fun `duration picker splits minutes into hour and minute fields`() {
        val cases = listOf(
            125 to listOf("2", "5"),
            0 to listOf("0", "0"),
            59 to listOf("0", "59"),
            60 to listOf("1", "0"),
            100_000 to listOf("999", "40"),
            -7 to listOf("0", "0"),
        )
        for ((minutes, expected) in cases) {
            withScene(AppUiStyle.MATERIAL3) {
                AppDurationPicker(minutes = minutes, onMinutesChange = {}, modifier = Modifier.width(360.dp))
            }.use { h -> assertEquals(expected, h.editableTexts(), "minutes=$minutes") }
        }
    }

    @Test fun `duration picker steps hours by sixty minutes and minutes by one`() {
        val seen = mutableListOf<Int>()
        withScene(AppUiStyle.MATERIAL3) {
            AppDurationPicker(minutes = 125, onMinutesChange = { seen += it }, modifier = Modifier.width(360.dp))
        }.use { h ->
            val plus = h.buttons("+")
            assertEquals(2, plus.size)
            h.click(plus[0])
            h.click(plus[1])
            assertEquals(listOf(185, 126), seen)
        }
    }

    @Test fun `number picker buttons step by one and the range ends disable them`() {
        val seen = mutableListOf<Int>()
        withScene(AppUiStyle.MATERIAL3) {
            AppNumberPicker(value = 5, onValueChange = { seen += it }, range = 0..9, label = "x")
        }.use { h ->
            h.click(h.buttons("+").single())
            h.click(h.buttons(minus).single())
            assertEquals(listOf(6, 4), seen)
        }
        withScene(AppUiStyle.MATERIAL3) {
            AppNumberPicker(value = 9, onValueChange = {}, range = 0..9, label = "x")
        }.use { h ->
            assertNotNull(h.buttons("+").single().config.getOrNull(SemanticsProperties.Disabled))
            assertNull(h.buttons(minus).single().config.getOrNull(SemanticsProperties.Disabled))
        }
        withScene(AppUiStyle.MATERIAL3) {
            AppNumberPicker(value = 0, onValueChange = {}, range = 0..9, label = "x")
        }.use { h ->
            assertNull(h.buttons("+").single().config.getOrNull(SemanticsProperties.Disabled))
            assertNotNull(h.buttons(minus).single().config.getOrNull(SemanticsProperties.Disabled))
        }
    }

    @Test fun `miuix number picker shows its label next to the native wheel`() {
        withScene(AppUiStyle.MIUIX) {
            AppNumberPicker(value = 3, onValueChange = {}, label = "小时", modifier = Modifier.width(160.dp))
        }.use { h -> h.text("小时") }
    }

    @Test fun `floating toolbar hosts its content in both ui styles`() {
        for (style in AppUiStyle.entries) {
            withScene(style) {
                AppFloatingToolbar { Text("toolbar-content") }
            }.use { h ->
                val node = h.text("toolbar-content")
                assertTrue(node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f, style.name)
            }
        }
    }

    @Test fun `window action menu composes its trigger without opening in every style and popup mode`() {
        for (style in AppUiStyle.entries) for (native in listOf(true, false)) {
            val expanded = mutableListOf<Boolean>()
            withScene(style, nativePopups = native) {
                AppWindowActionMenu(
                    groups = listOf(
                        listOf(
                            AppWindowAction("sort", children = listOf(AppWindowAction("latest"))),
                            AppWindowAction("refresh", onClick = {}),
                        ),
                    ),
                    onExpandedChange = { expanded += it },
                ) { Text("menu-trigger") }
            }.use { h ->
                h.text("menu-trigger")
                assertEquals(emptyList<Boolean>(), expanded.toList(), "$style native=$native")
            }
        }
    }

    private fun withScene(style: AppUiStyle, nativePopups: Boolean = true, content: @Composable () -> Unit): Scene {
        val scene = ImageComposeScene(700, 360, Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style)) {
                ProvideAppThemeConfig(
                    AppThemeConfig(hapticFeedbackEnabled = false, nativeMiuixPopupsEnabled = nativePopups),
                ) { content() }
            }
        }
        try {
            repeat(5) { scene.render((it + 1) * 40_000_000L).close() }
            return Scene(scene)
        } catch (failure: Throwable) {
            scene.close()
            throw failure
        }
    }

    private class Scene(private val scene: ImageComposeScene) : AutoCloseable {
        fun nodes(): List<SemanticsNode> {
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        }

        fun text(value: String): SemanticsNode = nodes().single { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        }

        /** Clickable nodes whose merged text is exactly [label], left to right. */
        fun buttons(label: String): List<SemanticsNode> = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }.sortedBy { it.boundsInRoot.left }

        fun click(node: SemanticsNode) {
            val action = assertNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action)
            assertTrue(action())
        }

        /** The text of every editable field, left to right. */
        fun editableTexts(): List<String> = nodes()
            .filter { it.config.getOrNull(SemanticsProperties.EditableText) != null }
            .sortedBy { it.boundsInRoot.left }
            .mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }

        override fun close() = scene.close()
    }
}
