@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.ui.DesktopNoopToaster
import com.bilipai.desktop.ui.DesktopToastController
import com.bilipai.desktop.ui.DesktopToastHost
import com.bilipai.desktop.ui.DesktopToaster
import com.bilipai.desktop.ui.LocalDesktopToaster
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    /**
     * focusedWindow reports the scene window as focused (the popup tests need it, as the moderation menu test does) and
     * inset is the padding around the content: the popup tests use 0 dp so the window coordinates are the Box coordinates.
     */
    private class KitScene(
        style: AppUiStyle,
        focusedWindow: Boolean = false,
        inset: Dp = 16.dp,
        content: @Composable () -> Unit,
    ) : AutoCloseable {
        init {
            check(GraphicsEnvironment.isHeadless()) { "CPU component tests require the existing headless test JVM" }
        }

        private val scene = ImageComposeScene(480, 320, Density(1f)) {
            DesktopAppearanceTheme(
                DesktopThemeSettings(uiStyle = style, themeMode = AppThemeMode.LIGHT, hapticFeedbackEnabled = false),
                systemLanguageTags = listOf("en"),
            ) {
                val actualWindow = LocalWindowInfo.current
                val windowInfo = remember(actualWindow, focusedWindow) {
                    if (focusedWindow) {
                        object : WindowInfo by actualWindow {
                            override val isWindowFocused = true
                        }
                    } else {
                        actualWindow
                    }
                }
                CompositionLocalProvider(LocalDesktopKitReducedMotion provides true, LocalWindowInfo provides windowInfo) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(inset)) { content() }
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

        /** Real-time wait for something that a timer decides (the toast lifetime). State written by a timer thread needs the apply. */
        fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
            val end = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < end) {
                Snapshot.sendApplyNotifications()
                settle(1)
                if (condition()) return true
                Thread.sleep(16)
            }
            return condition()
        }

        /** Window bounds of the node that carries this content description (a popup or a menu surface). */
        fun boundsOfDescription(description: String): Rect =
            nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(description) }.boundsInWindow

        /** A primary-button drag in equal steps; the pointer is released at the end. */
        fun drag(from: Offset, to: Offset, steps: Int = 6) {
            val at = nanos / 1_000_000
            val held = PointerButtons(isPrimaryPressed = true)
            scene.sendPointerEvent(PointerEventType.Move, from, timeMillis = at)
            scene.sendPointerEvent(PointerEventType.Press, from, timeMillis = at + 1, buttons = held)
            for (step in 1..steps) {
                val point = Offset(from.x + (to.x - from.x) * step / steps, from.y + (to.y - from.y) * step / steps)
                scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = at + 1 + step * 8L, buttons = held)
            }
            scene.sendPointerEvent(PointerEventType.Release, to, timeMillis = at + 2 + steps * 8L, buttons = PointerButtons())
            settle()
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

    // ---- Slice X2b: menus, overlay scrollbar, toast and the player dark theme ----
    // Assumptions only a compile run can disprove: a Popup of the scene reports its semantics in window coordinates (as the
    // moderation menu test relies on), the popup is positioned from the Box that declares it, a drag on the scrollbar strip
    // reaches the scroll state through the CPU scene, and clearAndSetSemantics hides the decorative shortcut text.

    @Test fun contextMenuRowsAreButtonsThatTheCallerClosesAndACollapsedMenuComposesNothing() {
        val picked = mutableListOf<String>()
        var expanded by mutableStateOf(false)
        val fixture = KitScene(AppUiStyle.MATERIAL3, focusedWindow = true, inset = 0.dp) {
            Box(Modifier.fillMaxSize()) {
                DesktopContextMenu(expanded, { expanded = false }, IntOffset(40, 50), width = 200.dp, label = "Actions") {
                    DesktopMenuRow("Open", { picked += "open" }, shortcut = "Enter")
                    DesktopMenuRow("Copy link", { picked += "copy" })
                    DesktopMenuRow("Remove", { picked += "remove" }, enabled = false)
                }
            }
        }
        try {
            fixture.settle()
            assertTrue(fixture.withRole(Role.Button).isEmpty(), "a collapsed menu composes nothing")
            expanded = true
            fixture.settle()
            val rows = fixture.withRole(Role.Button)
            assertEquals(3, rows.size, "one button per row, nothing else with a button role")
            assertEquals(listOf(32f, 32f, 32f), rows.map { it.boundsInWindow.height }, "rows are 32 dp")
            assertNull(rows[0].config.getOrNull(SemanticsProperties.Disabled))
            assertNotNull(rows[2].config.getOrNull(SemanticsProperties.Disabled), "a disabled row stays visible and is inert")
            assertTrue(fixture.hasText("Open") && fixture.hasText("Copy link") && fixture.hasText("Remove"))
            assertFalse(fixture.hasText("Enter"), "the shortcut hint is decorative: the name of a row is its label")
            fixture.click(rows[1].boundsInWindow.center)
            assertEquals(listOf("copy"), picked)
            fixture.click(rows[2].boundsInWindow.center)
            assertEquals(listOf("copy"), picked, "a disabled row ignores a click")
            assertTrue(expanded, "the caller closes the menu: a row click alone does not")
            expanded = false
            fixture.settle()
            assertTrue(fixture.withRole(Role.Button).isEmpty(), "closing removes the popup")
        } finally {
            fixture.close()
        }
    }

    /** The window bounds of a three-row, 200 dp wide context menu opened at a pointer position (a scene per position). */
    private fun menuBoundsAt(pointer: IntOffset): Rect {
        val fixture = KitScene(AppUiStyle.MATERIAL3, focusedWindow = true, inset = 0.dp) {
            Box(Modifier.fillMaxSize()) {
                DesktopContextMenu(true, {}, pointer, width = 200.dp, label = "Actions") {
                    DesktopMenuRow("One", {})
                    DesktopMenuRow("Two", {})
                    DesktopMenuRow("Three", {})
                }
            }
        }
        try {
            fixture.settle()
            return fixture.boundsOfDescription("Actions")
        } finally {
            fixture.close()
        }
    }

    @Test fun contextMenuOpensAtThePointerFlipsAtTheWindowEdgesAndKeepsTheGap() {
        val gap = DESKTOP_KIT_MENU_GAP_DP.toFloat()
        val atPointer = menuBoundsAt(IntOffset(40, 50))
        assertEquals(40f, atPointer.left)
        assertEquals(50f, atPointer.top)
        assertEquals(200f, atPointer.width)
        val menuHeight = atPointer.height
        assertTrue(menuHeight in 100f..120f, "three 32 dp rows and the 6 dp padding: $menuHeight")
        val cornered = menuBoundsAt(IntOffset(470, 310))
        assertEquals(470f, cornered.right, "flipped to the left of the pointer")
        assertEquals(310f, cornered.bottom, "flipped above the pointer")
        val origin = menuBoundsAt(IntOffset(0, 0))
        assertEquals(gap, origin.left, "clamped into the gap, not at the window edge")
        assertEquals(gap, origin.top)
        assertEquals(480f - gap, menuBoundsAt(IntOffset(272, 100)).right, "an exact fit does not flip")
        assertEquals(73f, menuBoundsAt(IntOffset(273, 100)).left, "one pixel over flips to the left of the pointer")
    }

    @Test fun popoverOpensUnderItsAnchorAlignedToTheEndAndFlipsAboveWhenThereIsNoRoomBelow() {
        val gap = DESKTOP_KIT_MENU_GAP_DP.toFloat()
        for ((top, below) in listOf(40 to true, 280 to false)) {
            val fixture = KitScene(AppUiStyle.MATERIAL3, focusedWindow = true, inset = 0.dp) {
                Box(Modifier.offset(100.dp, top.dp).size(150.dp, 32.dp)) {
                    DesktopPopover(true, {}, label = "Panel", width = 220.dp) {
                        DesktopMenuRow("Only row", {})
                    }
                }
            }
            try {
                fixture.settle()
                val panel = fixture.boundsOfDescription("Panel")
                assertEquals(250f - 220f, panel.left, "the end edges are aligned")
                if (below) assertEquals(top + 32f + gap, panel.top, "one gap under the anchor")
                else assertEquals(top - gap, panel.bottom, "flipped: one gap above the anchor")
            } finally {
                fixture.close()
            }
        }
    }

    @Test fun scrollbarHitStripIsTwelveDpWideSoADragOutsideTheEightDpThumbStillScrolls() {
        val state = ScrollState(0)
        val fixture = KitScene(AppUiStyle.MATERIAL3, inset = 0.dp) {
            Box(Modifier.size(200.dp, 100.dp)) {
                Column(Modifier.verticalScroll(state)) {
                    repeat(20) { Box(Modifier.fillMaxWidth().height(30.dp)) }
                }
                DesktopKitVerticalScrollbar(state)
            }
        }
        try {
            fixture.settle()
            assertTrue(state.maxValue > 0, "the content is taller than the viewport")
            assertEquals(0, state.value)
            // The strip is x 188..200 and the visible thumb x 190..198, centred: x = 188.5 is inside the strip only.
            fixture.drag(Offset(188.5f, 10f), Offset(188.5f, 60f))
            assertTrue(state.value > 0, "a drag on the part of the strip outside the visible thumb scrolls: ${state.value}")
        } finally {
            fixture.close()
        }
    }

    @Test fun toastHostShowsOneToastAtATimeRunsItsActionOnceAndExpires() {
        val controller = DesktopToastController()
        var undone = 0
        val fixture = KitScene(AppUiStyle.MATERIAL3, inset = 0.dp) {
            Box(Modifier.fillMaxSize()) { DesktopToastHost(controller, lifetimeMillis = { 1500L }) }
        }
        try {
            fixture.settle()
            assertFalse(fixture.hasText("Saved"), "nothing shows before the first toast")
            controller.show("Saved")
            fixture.settle()
            assertTrue(fixture.hasText("Saved"))
            assertTrue(fixture.withRole(Role.Button).isEmpty(), "a plain toast has no button")
            controller.show("Removed", "Undo") { undone++ }
            fixture.settle()
            assertFalse(fixture.hasText("Saved"), "one toast at a time: the new one replaced the shown one")
            assertTrue(fixture.hasText("Removed") && fixture.hasText("Undo"))
            val buttons = fixture.withRole(Role.Button)
            assertEquals(1, buttons.size, "the action is the one button")
            fixture.click(buttons[0].boundsInWindow.center)
            assertEquals(1, undone, "the action runs once")
            assertFalse(fixture.hasText("Removed"), "pressing the action closes the toast")
            controller.show("Later")
            fixture.settle()
            assertTrue(fixture.hasText("Later"))
            assertTrue(fixture.awaitUntil { !fixture.hasText("Later") }, "the lifetime ends the toast")
            assertEquals(1, undone)
        } finally {
            fixture.close()
        }
    }

    @Test fun theDefaultToasterIsAHarmlessNoOpAndTheControllerIgnoresBlankText() {
        // A caller without a provider reads the no-op default and may call it freely.
        var seen: DesktopToaster? = null
        val fixture = KitScene(AppUiStyle.MATERIAL3) { seen = LocalDesktopToaster.current }
        try {
            fixture.settle()
            assertTrue(seen === DesktopNoopToaster)
            DesktopNoopToaster.show("anything", "Undo") { error("the no-op toaster never runs an action") }
        } finally {
            fixture.close()
        }
        val controller = DesktopToastController()
        controller.show("   ")
        assertNull(controller.state.current)
        controller.show("Hello")
        val first = assertNotNull(controller.state.current)
        controller.dismiss(first.id + 1)
        assertEquals(first, controller.state.current, "a stale id closes nothing")
        controller.invokeAction(first.id)
        assertNull(controller.state.current, "an action id closes the toast even when it carries no callback")
    }

    @Test fun playerDarkThemeInstallsTheFixedPaletteInsideOnlyAndKeepsTheAppTypography() {
        var outside: Color? = null
        var inside: Color? = null
        var insideText: Color? = null
        var outsideTypography: Any? = null
        var insideTypography: Any? = null
        val fixture = KitScene(AppUiStyle.MATERIAL3) {
            outside = MaterialTheme.colorScheme.surface
            outsideTypography = MaterialTheme.typography
            DesktopPlayerDarkTheme {
                inside = MaterialTheme.colorScheme.surface
                insideText = MaterialTheme.colorScheme.onSurface
                insideTypography = MaterialTheme.typography
                Box(Modifier.size(10.dp))
            }
        }
        try {
            fixture.settle()
            assertEquals(Color(DESKTOP_PLAYER_DARK_SURFACE), inside)
            assertEquals(Color(DESKTOP_PLAYER_DARK_ON_SURFACE), insideText)
            assertEquals(desktopPlayerDarkColorScheme().primary, Color(DESKTOP_PLAYER_DARK_PRIMARY))
            assertNotEquals(inside, outside, "the scene theme is light: the player palette is not the app palette")
            assertEquals(outsideTypography, insideTypography, "typography is the app's")
        } finally {
            fixture.close()
        }
    }
}
