@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.appearance.DesktopWindowsUxRailMode
import com.bilipai.desktop.ui.kit.LocalDesktopKitReducedMotion
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ImageComposeScene test (a CPU scene, no native window, no GPU, no network) of the actual navigation rail: the accessible names
 * the Windows fixtures look for, one action per control with the tooltip wrappers adding none (clicks, long clicks and custom
 * actions are all counted, node by node), the two presentations, the single tab stop with its arrow keys, the right-click mode
 * menu, and that the hamburger and the collapse and expand entries hand their click to the toggle of the shell. The scene window
 * is reported as focused (the menu is a popup, as in the kit tests) and motion is reduced so no wash is in flight.
 *
 * Assumptions only a Linux run can disprove: a Tab key event of the scene moves the focus to the one focusable stop, a key
 * event reaches the key handler of the rail through the focused row, the LaunchedEffect that requests the focus runs inside
 * the settle frames, and a popup of the scene reports its semantics in window coordinates.
 */
class DesktopWindowsNavigationRailUiTest {
    private class RailScene(content: @Composable () -> Unit) : AutoCloseable {
        init {
            check(GraphicsEnvironment.isHeadless()) { "CPU component tests require the existing headless test JVM" }
        }

        private val scene = ImageComposeScene(360, 640, Density(1f)) {
            DesktopAppearanceTheme(
                DesktopThemeSettings(uiStyle = AppUiStyle.MATERIAL3, themeMode = AppThemeMode.LIGHT, hapticFeedbackEnabled = false),
                systemLanguageTags = listOf("en"),
            ) {
                val actualWindow = LocalWindowInfo.current
                val windowInfo = remember(actualWindow) {
                    object : WindowInfo by actualWindow {
                        override val isWindowFocused = true
                    }
                }
                CompositionLocalProvider(LocalDesktopKitReducedMotion provides true, LocalWindowInfo provides windowInfo) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
                }
            }
        }
        private var nanos = 0L

        fun settle(frames: Int = 8) {
            repeat(frames) {
                nanos += 30_000_000L
                scene.render(nanos).close()
            }
        }

        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }

        fun withRole(role: Role): List<SemanticsNode> = nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == role }

        /** What makes a node something to activate: a click, a long click, or any custom action. */
        private fun SemanticsNode.activations(): Int =
            listOf(SemanticsActions.OnClick, SemanticsActions.OnLongClick).count { config.getOrNull(it) != null } +
                (config.getOrNull(SemanticsActions.CustomActions)?.size ?: 0)

        fun actions(): Int = nodes().sumOf { it.activations() }

        /**
         * The rule the Windows fixtures rely on (accessibleActionCount == 1 on the search row and the rows): every Tab and every
         * Button node has exactly one activation, and no other node has any, so a tooltip wrapper that added a long-click or a
         * custom action would fail here.
         */
        fun assertOneActivationPerControl() {
            for (node in nodes()) {
                val role = node.config.getOrNull(SemanticsProperties.Role)
                val expected = if (role == Role.Tab || role == Role.Button) 1 else 0
                val name = node.config.getOrNull(SemanticsProperties.ContentDescription)
                assertEquals(expected, node.activations(), "node ${node.id} (role $role, description $name) has the wrong number of actions")
            }
        }

        fun hasText(text: String): Boolean =
            nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }

        fun described(description: String): List<SemanticsNode> =
            nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(description) }

        /** The button whose child text is the label: a menu row, whose name is its text. */
        fun buttonShowing(label: String): SemanticsNode = withRole(Role.Button).first { button ->
            button.children.any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
        }

        fun focused(): List<SemanticsNode> = nodes().filter { it.config.getOrNull(SemanticsProperties.Focused) == true }

        fun click(point: Offset, secondary: Boolean = false) {
            val at = nanos / 1_000_000
            val held = PointerButtons(isPrimaryPressed = !secondary, isSecondaryPressed = secondary)
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = at)
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = at + 1, buttons = held)
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = at + 41, buttons = PointerButtons())
            settle()
        }

        fun key(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            settle()
        }

        override fun close() = scene.close()
    }

    private fun row(item: DesktopRailItem, selected: Boolean = false, unread: Int = 0) = DesktopRailRowState(
        entry = DesktopRailEntry(item, item.bottomNavName),
        label = DesktopShellStrings.railLabel(item),
        selected = selected,
        icon = Icons.Outlined.Menu,
        unreadCount = unread,
    )

    private val rows = listOf(
        row(DesktopRailItem.HOME, selected = true),
        row(DesktopRailItem.DYNAMIC, unread = 120),
        row(DesktopRailItem.LIVE),
        row(DesktopRailItem.HISTORY),
        row(DesktopRailItem.DOWNLOADS),
        row(DesktopRailItem.PROFILE),
        row(DesktopRailItem.SETTINGS),
    )

    private fun shell(
        presentation: DesktopRailPresentation,
        mode: DesktopWindowsUxRailMode = DesktopWindowsUxRailMode.EXPANDED,
        written: MutableList<DesktopWindowsUxRailMode> = mutableListOf(),
        onToggle: () -> Unit = {},
    ) = DesktopWindowsShellRail(mode, presentation, setMode = { written += it }, toggle = onToggle)

    @Test fun anExpandedRailShowsTheFixtureNamesAndEveryRowIsOneAction() {
        val clicked = mutableListOf<DesktopRailItem?>()
        var searches = 0
        val fixture = RailScene {
            DesktopWindowsNavigationRail(shell(DesktopRailPresentation.EXPANDED), rows, { searches++ }, { clicked += it.entry.item })
        }
        try {
            fixture.settle()
            for (name in listOf("BiliPai", "搜索", "下载与离线")) assertTrue(fixture.hasText(name), name)
            // The hint is drawn only while DESKTOP_RAIL_SHORTCUT_HINTS is true (slice S2 turns it on with the shortcut host); from then
            // on this line proves that the drawn hint stays out of the semantics, and until then it proves that no hint is drawn.
            assertFalse(fixture.hasText(DesktopShellStrings.SEARCH_HINT), "the Ctrl K hint is not part of the semantics")
            assertEquals(1, fixture.described(DesktopShellStrings.RAIL_NAME).size, "the container is named as the main navigation")
            assertEquals(rows.size, fixture.withRole(Role.Tab).size, "one tab node per row")
            assertEquals(listOf(true) + List(rows.size - 1) { false }, fixture.withRole(Role.Tab).map { it.config.getOrNull(SemanticsProperties.Selected) })
            assertEquals(2 + rows.size, fixture.actions(), "the toggle, the search row and one action per row: the tooltip wrappers add none")
            fixture.assertOneActivationPerControl()
            assertEquals(1, fixture.described(DesktopShellStrings.unreadAnnouncement(DesktopShellStrings.railLabel(DesktopRailItem.DYNAMIC), 120)).size, "the unread row is named with the exact count")
            val tabs = fixture.withRole(Role.Tab)
            assertEquals(184f, tabs[0].boundsInRoot.width, "an expanded row is 200 dp less the 8 dp padding on both sides")
            assertEquals(40f, tabs[0].boundsInRoot.height, "a row is 40 dp")
            fixture.click(tabs[2].boundsInRoot.center)
            assertEquals(listOf<DesktopRailItem?>(DesktopRailItem.LIVE), clicked)
            fixture.click(tabs[0].boundsInRoot.center)
            assertEquals(listOf<DesktopRailItem?>(DesktopRailItem.LIVE, DesktopRailItem.HOME), clicked, "the selected row reports its click too")
            val search = fixture.withRole(Role.Button).single { it.config.getOrNull(SemanticsProperties.ContentDescription) == null }
            fixture.click(search.boundsInRoot.center)
            assertEquals(1, searches)
        } finally {
            fixture.close()
        }
    }

    @Test fun aCollapsedRailKeepsTheNamesAsDescriptionsAndTheRowsAsOneNodeEach() {
        val fixture = RailScene {
            DesktopWindowsNavigationRail(shell(DesktopRailPresentation.COLLAPSED), rows, {}, {})
        }
        try {
            fixture.settle()
            assertFalse(fixture.hasText(DesktopShellStrings.railLabel(DesktopRailItem.HOME)), "a collapsed row shows no label")
            for (name in listOf("BiliPai", "搜索", "下载与离线", DesktopShellStrings.railLabel(DesktopRailItem.HOME))) {
                assertTrue(fixture.described(name).isNotEmpty(), name)
            }
            assertEquals(rows.size, fixture.withRole(Role.Tab).size)
            assertEquals(2 + rows.size, fixture.actions())
            fixture.assertOneActivationPerControl()
            val tabs = fixture.withRole(Role.Tab)
            assertEquals(48f, tabs[0].boundsInRoot.width, "a collapsed row is 56 dp less the 4 dp padding on both sides")
            assertEquals(56f, fixture.described(DesktopShellStrings.RAIL_NAME).single().boundsInRoot.width, "the collapsed panel is 56 dp")
            val search = fixture.described(DesktopShellStrings.SEARCH).single()
            assertEquals(40f, search.boundsInRoot.width, "the collapsed search is a 40 dp button")
            assertEquals(Role.Button, search.config.getOrNull(SemanticsProperties.Role))
        } finally {
            fixture.close()
        }
    }

    @Test fun theRailIsOneTabStopAndTheArrowKeysMoveIt() {
        val fixture = RailScene {
            DesktopWindowsNavigationRail(shell(DesktopRailPresentation.EXPANDED), rows, {}, {})
        }
        try {
            fixture.settle()
            fixture.key(Key.Tab)
            val first = fixture.focused()
            assertEquals(1, first.size, "one node has the focus after Tab")
            assertEquals(Role.Tab, first[0].config.getOrNull(SemanticsProperties.Role))
            assertEquals(true, first[0].config.getOrNull(SemanticsProperties.Selected), "Tab lands on the selected row")
            fixture.key(Key.DirectionDown)
            val next = fixture.focused().single()
            assertEquals(
                listOf(DesktopShellStrings.unreadAnnouncement(DesktopShellStrings.railLabel(DesktopRailItem.DYNAMIC), 120)),
                next.config.getOrNull(SemanticsProperties.ContentDescription), "Down moves to the next row",
            )
            fixture.key(Key.MoveEnd)
            assertEquals(fixture.withRole(Role.Tab).last().id, fixture.focused().single().id, "End moves to the last row")
            fixture.key(Key.MoveHome)
            val head = fixture.focused().single()
            assertEquals(Role.Button, head.config.getOrNull(SemanticsProperties.Role), "Home moves to the first stop, the toggle")
            assertEquals(listOf(DesktopShellStrings.RAIL_COLLAPSE), head.config.getOrNull(SemanticsProperties.ContentDescription))
        } finally {
            fixture.close()
        }
    }

    @Test fun aRightClickOpensTheModeMenuTheCollapseEntryIsTheShellsToggleAndTheWayBackToAutomaticIsAnExplicitWrite() {
        val written = mutableListOf<DesktopWindowsUxRailMode>()
        var toggles = 0
        val fixture = RailScene {
            DesktopWindowsNavigationRail(
                shell(DesktopRailPresentation.EXPANDED, DesktopWindowsUxRailMode.EXPANDED, written) { toggles++ }, rows, {}, {},
            )
        }
        try {
            fixture.settle()
            assertFalse(fixture.hasText(DesktopShellStrings.RAIL_COLLAPSE), "no menu before the click")
            fixture.click(Offset(100f, 450f), secondary = true)
            assertTrue(fixture.hasText(DesktopShellStrings.RAIL_COLLAPSE), "the first entry is the collapse of the expanded rail")
            assertTrue(fixture.hasText(DesktopShellStrings.RAIL_AUTO), "the way back to automatic follows")
            assertFalse(fixture.hasText(DesktopShellStrings.RAIL_EXPAND), "an entry that does not apply is left out")
            fixture.click(fixture.buttonShowing(DesktopShellStrings.RAIL_COLLAPSE).boundsInWindow.center)
            assertEquals(1, toggles, "the collapse entry is the toggle of the shell")
            assertEquals(emptyList(), written, "the rail writes no mode of its own for it")
            assertFalse(fixture.hasText(DesktopShellStrings.RAIL_COLLAPSE), "choosing an entry closes the menu")
            fixture.click(Offset(100f, 450f), secondary = true)
            fixture.click(fixture.buttonShowing(DesktopShellStrings.RAIL_AUTO).boundsInWindow.center)
            assertEquals(listOf(DesktopWindowsUxRailMode.AUTO), written, "the way back to automatic writes AUTO")
            assertEquals(1, toggles, "and is not a toggle")
        } finally {
            fixture.close()
        }
    }

    @Test fun theHamburgerAndTheExpandEntryHandTheirClickToTheShellsToggleAndWriteNothingThemselves() {
        val written = mutableListOf<DesktopWindowsUxRailMode>()
        var toggles = 0
        val fixture = RailScene {
            DesktopWindowsNavigationRail(
                shell(DesktopRailPresentation.COLLAPSED, DesktopWindowsUxRailMode.COLLAPSED, written) { toggles++ }, rows, {}, {},
            )
        }
        try {
            fixture.settle()
            val hamburger = fixture.withRole(Role.Button).single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(DesktopShellStrings.RAIL_EXPAND) }
            assertEquals(Role.Button, hamburger.config.getOrNull(SemanticsProperties.Role), "the collapsed rail offers the expand")
            fixture.click(hamburger.boundsInRoot.center)
            assertEquals(1, toggles, "the hamburger hands the click to the toggle of the shell, which decides what it means")
            fixture.click(Offset(30f, 450f), secondary = true)
            assertTrue(fixture.hasText(DesktopShellStrings.RAIL_EXPAND), "the first entry is the expand of the collapsed rail")
            fixture.click(fixture.buttonShowing(DesktopShellStrings.RAIL_EXPAND).boundsInWindow.center)
            assertEquals(2, toggles, "the expand entry is the toggle of the shell too")
            assertEquals(emptyList(), written, "neither writes a mode by itself: a window too narrow for the rail is the frame's to answer")
        } finally {
            fixture.close()
        }
    }
}
