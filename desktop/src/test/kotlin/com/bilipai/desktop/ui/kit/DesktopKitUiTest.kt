@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Actual kit controls on a CPU Compose scene (ImageComposeScene, the harness of DesktopWindowsPopupMaterialUiTest and
 * DesktopAppComponentsTest): real pointer and key events, real semantics. No native window, no GPU, no network.
 * Reduced motion is switched on so no wash animation is in flight. The segmented key test relies on the scene giving
 * keyboard focus after a click, which is the one assumption a Linux run can disprove.
 */
class DesktopKitUiTest {
    private class KitScene(style: AppUiStyle, content: @Composable () -> Unit) : AutoCloseable {
        init {
            check(GraphicsEnvironment.isHeadless()) { "CPU component tests require the existing headless test JVM" }
        }

        private val scene = ImageComposeScene(480, 320, Density(1f)) {
            DesktopAppearanceTheme(
                DesktopThemeSettings(uiStyle = style, themeMode = AppThemeMode.LIGHT, hapticFeedbackEnabled = false),
                systemLanguageTags = listOf("en"),
            ) {
                CompositionLocalProvider(LocalDesktopKitReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) { content() }
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

        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }

        fun tagged(tag: String): SemanticsNode = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

        fun withRole(role: Role): List<SemanticsNode> = nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == role }

        fun actions(): Int = nodes().count { it.config.getOrNull(SemanticsActions.OnClick) != null }

        fun hasText(text: String): Boolean =
            nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }

        fun click(point: Offset) {
            val at = nanos / 1_000_000
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = at)
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = at + 1, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = at + 41, buttons = PointerButtons())
            settle()
        }

        fun key(key: Key, alt: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isAltPressed = alt))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isAltPressed = alt))
            settle()
        }

        override fun close() = scene.close()
    }

    private val letters = listOf(
        DesktopKitSegment("a", "Alpha"),
        DesktopKitSegment("b", "Bravo"),
        DesktopKitSegment("c", "Charlie", enabled = false),
    )

    @Test fun segmentedControlSelectsTheClickedSegmentAndIgnoresADisabledOne() {
        for (style in AppUiStyle.entries) {
            var selected by mutableStateOf("a")
            val fixture = KitScene(style) {
                DesktopSegmentedControl(letters, selected, { selected = it }, Modifier.width(300.dp), label = "Letters")
            }
            try {
                fixture.settle()
                val radios = fixture.withRole(Role.RadioButton)
                assertEquals(3, radios.size, "$style: one radio per segment")
                assertEquals(listOf(true, false, false), radios.map { it.config.getOrNull(SemanticsProperties.Selected) }, style.name)
                assertNotNull(radios[2].config.getOrNull(SemanticsProperties.Disabled), "$style: the disabled segment is disabled")
                fixture.click(radios[1].boundsInRoot.center)
                assertEquals("b", selected, "$style: a click selects the segment")
                assertEquals(listOf(false, true, false), fixture.withRole(Role.RadioButton).map { it.config.getOrNull(SemanticsProperties.Selected) }, style.name)
                fixture.click(radios[2].boundsInRoot.center)
                assertEquals("b", selected, "$style: a disabled segment ignores a click")
                assertEquals(32f, radios[0].boundsInRoot.height + 4f, "$style: the track is 32 dp with a 2 dp inset")
            } finally {
                fixture.close()
            }
        }
    }

    @Test fun segmentedControlMovesTheSelectionWithUnmodifiedArrowKeysOnly() {
        var selected by mutableStateOf("a")
        val fixture = KitScene(AppUiStyle.MATERIAL3) {
            DesktopSegmentedControl(letters, selected, { selected = it }, Modifier.width(300.dp))
        }
        try {
            fixture.settle()
            fixture.click(fixture.withRole(Role.RadioButton)[0].boundsInRoot.center)
            assertEquals("a", selected, "clicking the selected segment only focuses the group")
            fixture.key(Key.DirectionRight)
            assertEquals("b", selected)
            fixture.key(Key.DirectionRight)
            assertEquals("a", selected, "Right skips the disabled segment and wraps")
            fixture.key(Key.DirectionLeft)
            assertEquals("b", selected, "Left wraps and skips the disabled segment")
            fixture.key(Key.MoveHome)
            assertEquals("a", selected)
            fixture.key(Key.DirectionRight, alt = true)
            assertEquals("a", selected, "Alt+Right is not consumed (Alt+Left stays the back shortcut)")
        } finally {
            fixture.close()
        }
    }

    @Test fun toggleRowIsInertWhileLoadingThenTogglesOnceThroughItsSingleAction() {
        for (style in AppUiStyle.entries) {
            var value by mutableStateOf<Boolean?>(null)
            val changes = mutableListOf<Boolean>()
            val fixture = KitScene(style) {
                DesktopToggleRow(
                    "Autoplay", value, { changes += it; value = it }, Modifier.testTag("toggle"),
                    description = "Play the next video",
                )
            }
            try {
                fixture.settle()
                val loading = fixture.tagged("toggle")
                assertNotNull(loading.config.getOrNull(SemanticsProperties.Disabled), "$style: a loading row is disabled")
                assertTrue(fixture.hasText(DesktopKitStrings.LOADING_SETTING), "$style: the loading text is shown")
                fixture.click(loading.boundsInRoot.center)
                assertTrue(changes.isEmpty(), "$style: a loading row ignores a click")

                value = false
                fixture.settle()
                val loaded = fixture.tagged("toggle")
                assertFalse(fixture.hasText(DesktopKitStrings.LOADING_SETTING), "$style: the loading text goes away")
                assertNull(loaded.config.getOrNull(SemanticsProperties.Disabled), "$style: a loaded row is enabled")
                assertEquals(Role.Switch, loaded.config.getOrNull(SemanticsProperties.Role), style.name)
                assertEquals(ToggleableState.Off, loaded.config.getOrNull(SemanticsProperties.ToggleableState), style.name)
                assertEquals(1, fixture.actions(), "$style: the row is the only action, the vendored switch inside is display only")
                fixture.click(loaded.boundsInRoot.center)
                assertEquals(listOf(true), changes, "$style: one click is one change")
                assertEquals(ToggleableState.On, fixture.tagged("toggle").config.getOrNull(SemanticsProperties.ToggleableState), style.name)
            } finally {
                fixture.close()
            }
        }
    }

    @Test fun iconButtonIsExactlyOneButtonClickableAndTheTooltipAddsNoAction() {
        var clicks = 0
        var enabled by mutableStateOf(true)
        val fixture = KitScene(AppUiStyle.MATERIAL3) {
            DesktopIconButton(onClick = { clicks++ }, label = "Play", enabled = enabled, shortcut = "Space") {
                Box(Modifier.size(20.dp).background(Color.Red))
            }
        }
        try {
            fixture.settle()
            val clickable = fixture.nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertEquals(1, clickable.size, "exactly one clickable node, tooltip wrapper included")
            val button = clickable.single()
            assertEquals(Role.Button, button.config.getOrNull(SemanticsProperties.Role))
            assertEquals(listOf("Play"), button.config.getOrNull(SemanticsProperties.ContentDescription))
            assertNull(button.config.getOrNull(SemanticsActions.OnLongClick), "no long-click action")
            assertEquals(32f, button.boundsInRoot.width, "a 32 dp hit")
            assertEquals(32f, button.boundsInRoot.height, "a 32 dp hit")
            fixture.click(button.boundsInRoot.center)
            assertEquals(1, clicks)
            enabled = false
            fixture.settle()
            assertNotNull(fixture.nodes().first { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
                .config.getOrNull(SemanticsProperties.Disabled), "a disabled button is disabled")
            fixture.click(button.boundsInRoot.center)
            assertEquals(1, clicks, "a disabled button ignores a click")
        } finally {
            fixture.close()
        }
    }

    @Test fun navRowIsOneSelectedTabAndKeepsItsNameWhenCollapsed() {
        var clicks = 0
        var collapsed by mutableStateOf(false)
        val fixture = KitScene(AppUiStyle.MATERIAL3) {
            Box(Modifier.width(220.dp)) {
                DesktopNavRow("Home", selected = true, onClick = { clicks++ }, collapsed = collapsed, icon = { Box(Modifier.size(20.dp)) })
            }
        }
        try {
            fixture.settle()
            val expanded = fixture.withRole(Role.Tab)
            assertEquals(1, expanded.size, "one tab node")
            assertEquals(true, expanded[0].config.getOrNull(SemanticsProperties.Selected))
            assertTrue(fixture.hasText("Home"), "the expanded row shows its label")
            assertNull(expanded[0].config.getOrNull(SemanticsProperties.ContentDescription), "the label is the name, not a second description")
            assertEquals(1, fixture.actions())
            collapsed = true
            fixture.settle()
            val folded = fixture.withRole(Role.Tab)
            assertEquals(1, folded.size, "collapsing does not remount into a second node")
            assertFalse(fixture.hasText("Home"), "the collapsed row shows no label")
            assertEquals(listOf("Home"), folded[0].config.getOrNull(SemanticsProperties.ContentDescription))
            fixture.click(folded[0].boundsInRoot.center)
            assertEquals(1, clicks)
        } finally {
            fixture.close()
        }
    }
}
