@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.appearance.DesktopWindowsUxRailMode
import com.bilipai.desktop.ui.kit.LocalDesktopKitReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Real rail pointer input, Compose editor focus and post-scene shell dispatch, without a native window or network.
 * The editor stays mounted across tab changes, as content in the retained MainHost can do. Route effects are recorded;
 * actual Windows focus and the original pager remain part of GUI acceptance. */
class DesktopShellRailKeyboardUiTest {
    @Test fun keyboardActivationKeepsTheRailReadyForTheNextArrow() = runBlocking {
        Scene(DesktopRailPresentation.EXPANDED, coroutineContext).use { f ->
            f.ready()
            f.chord(Key.Tab)
            f.pump()
            f.chord(Key.DirectionDown)
            f.pump()
            f.chord(Key.Enter)
            f.await { f.destination == DesktopRailItem.LIVE }
            f.chord(Key.DirectionDown)
            f.pump()
            f.chord(Key.Enter)
            f.await { f.destination == DesktopRailItem.HISTORY }
        }
    }

    @Test fun sidebarClickThenImmediateBackReleasesRetainedEditor() = runBlocking {
        for (presentation in listOf(DesktopRailPresentation.COLLAPSED, DesktopRailPresentation.EXPANDED)) {
            for (item in listOf(DesktopRailItem.LIVE, DesktopRailItem.HISTORY)) {
                Scene(presentation, coroutineContext).use { f ->
                    f.ready()
                    f.clickEditor()
                    f.await { f.editorFocused }
                    f.clickRow(item)
                    f.await { f.destination == item }
                    // No click on the page, no synthetic focus reset and no timer between navigation and Back.
                    f.chord(Key.DirectionLeft, alt = true)
                    assertEquals(DesktopRailItem.HOME, f.destination, "$presentation / $item")
                    assertFalse(f.editorFocused, "Explicit sidebar navigation must retire the old editor focus")
                    f.chord(Key.DirectionLeft, alt = true)
                    assertEquals(DesktopRailItem.HOME, f.destination)
                    assertEquals(0, f.windowBackCalls)
                }
            }
        }
    }

    @Test fun ctrlNumbersUseFixedLiveAndHistoryDestinationsAfterPointerNavigation() = runBlocking {
        Scene(DesktopRailPresentation.COLLAPSED, coroutineContext).use { f ->
            f.ready()
            f.clickRow(DesktopRailItem.HOME)
            f.pump()
            for ((key, expected) in listOf(Key.Three to DesktopRailItem.LIVE, Key.Five to DesktopRailItem.HISTORY,
                Key.One to DesktopRailItem.HOME)) {
                f.chord(key, ctrl = true)
                assertEquals(expected, f.destination)
                f.pump()
            }
            assertEquals(0, f.windowBackCalls)
        }
    }

    @Test fun typingEditorKeepsItsKeysUntilTheUserExplicitlyNavigates() = runBlocking {
        Scene(DesktopRailPresentation.EXPANDED, coroutineContext).use { f ->
            f.ready()
            f.clickEditor()
            f.await { f.editorFocused }
            f.chord(Key.Three, ctrl = true)
            f.chord(Key.DirectionLeft, alt = true)
            assertEquals(DesktopRailItem.HOME, f.destination)
            assertTrue(f.editorFocused)
            f.clickRow(DesktopRailItem.LIVE)
            f.await { f.destination == DesktopRailItem.LIVE }
            f.chord(Key.Five, ctrl = true)
            assertEquals(DesktopRailItem.HISTORY, f.destination)
            assertEquals(0, f.windowBackCalls)
        }
    }

    private class Scene(presentation: DesktopRailPresentation, context: CoroutineContext) : AutoCloseable {
        var destination by mutableStateOf(DesktopRailItem.HOME)
        var editorFocused = false
        var windowBackCalls = 0
        private var text by mutableStateOf("")
        private val items = listOf(DesktopRailItem.HOME, DesktopRailItem.LIVE, DesktopRailItem.HISTORY)
        private val host = DesktopShellShortcutHost({ windowBackCalls++; false }, { editorFocused })
        private val rootHandler: (KeyEvent) -> Boolean = { host.onKey(it) {} }
        private var nanos = 0L
        private val scene: ImageComposeScene

        init {
            host.bindRoot(DesktopShellRootCommands(this, { if (destination == DesktopRailItem.HOME) DesktopShellPage.HOME else DesktopShellPage.OTHER },
                { destination != DesktopRailItem.HOME }, { false }, { false },
                { destination = it; true }, { false }, atNavigationRoot = { true }))
            scene = ImageComposeScene(700, 700, Density(1f), coroutineContext = context) {
                MaterialTheme {
                    val actualWindow = LocalWindowInfo.current
                    val windowInfo = remember(actualWindow) {
                        object : WindowInfo by actualWindow { override val isWindowFocused = true }
                    }
                    CompositionLocalProvider(LocalWindowInfo provides windowInfo, LocalDesktopKitReducedMotion provides true) {
                        Row(Modifier.fillMaxSize().onKeyEvent(rootHandler)) {
                            DesktopWindowsNavigationRail(
                                DesktopWindowsShellRail(DesktopWindowsUxRailMode.AUTO, presentation, {}, {}),
                                items.map { item -> DesktopRailRowState(DesktopRailEntry(item, item.bottomNavName),
                                    DesktopShellStrings.railLabel(item), destination == item, Icons.Outlined.Home, 0) },
                                onSearch = {}, onRowClick = { destination = assertNotNull(it.entry.item) },
                            )
                            BasicTextField(text, { text = it }, Modifier.padding(20.dp).width(300.dp).height(60.dp)
                                .onFocusChanged { editorFocused = it.isFocused })
                        }
                    }
                }
            }
        }

        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        private fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        private fun render() { nanos = maxOf(System.nanoTime(), nanos + 1); scene.render(nanos).close() }
        suspend fun pump() { render(); delay(16); render() }
        suspend fun await(condition: () -> Boolean) {
            withTimeout(4_000) { do { render(); if (condition()) return@withTimeout; delay(12) } while (true) }
        }
        suspend fun ready() = await { all().any { it.config.getOrNull(SemanticsActions.SetText) != null } }
        fun clickEditor() = click(all().first { it.config.getOrNull(SemanticsActions.SetText) != null }.boundsInRoot.center)
        fun clickRow(item: DesktopRailItem) {
            val label = DesktopShellStrings.railLabel(item)
            val node = all().first { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                (it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                    nodes(it).any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true }) }
            click(node.boundsInRoot.center)
        }
        private fun click(at: Offset) {
            val time = nanos / 1_000_000
            scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
            scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time + 1, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 41, buttons = PointerButtons())
        }
        fun chord(key: Key, ctrl: Boolean = false, alt: Boolean = false) {
            for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
                val event = KeyEvent(key, type, isCtrlPressed = ctrl, isAltPressed = alt)
                if (!scene.sendKeyEvent(event)) rootHandler(event) // Same post-scene fallback as the native Window.
            }
        }
        override fun close() { scene.close() }
    }
}
