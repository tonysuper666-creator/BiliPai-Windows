@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import com.android.purebilibili.feature.home.components.BottomNavItem
import com.android.purebilibili.navigation.bottomPagerNavKeyForItem
import com.android.purebilibili.navigation3.BiliPaiNavKey
import java.awt.Component
import java.awt.KeyboardFocusManager
import javax.swing.text.JTextComponent

/*
 * The shell shortcut host (spec 6.1, slice S2): the pure resolver of DesktopShellShortcuts wired to the Root key handler.
 *
 * Wiring. The Root-reserved DesktopShell.kt creates one host, provides it as LocalDesktopShellShortcutHost, and asks it
 * from one branch of rootKeyHandler, after the F11 and Esc handling: host.onKey(event) { toggle fullscreen }. That handler
 * runs after the Compose scene declined the key (a focused control that consumes a key keeps it), and the window key
 * fallback hands it the keys that no Compose target below the surface received. Nothing here registers a second key
 * fallback slot and no global AWT or OS hook exists.
 *
 * What the host knows. It holds two bindings that the composition fills in and clears again: the Root stack lends the
 * commands that need the route owner and the chrome (the page, search, refresh, go to a destination, the help page), and the
 * shell frame lends the rail toggle and the pane focus. A key that no binding can act on is not claimed. The host itself
 * knows the way back (requestBack of the window navigation owner, given at construction) and, per key press, fullscreen.
 *
 * Rules, each one a pure function or a small class that DesktopShellShortcutHostTest and test_ux_shell_shortcut_host.py check:
 *   - a text editor owning focus suppresses everything (desktopShellTextEditorFocused);
 *   - the page decides what a key may do (desktopShellPageOf, then resolveDesktopShellKey); the onboarding route has none;
 *   - Back never leaves the app: on the Home tab at the root of the stack Alt+Left and the mouse Back button do nothing;
 *   - a held key acts once: the repeats of a claimed press and its release are consumed (DesktopShellHeldKeys);
 *   - F6 and Shift+F6 move the focus between the panes that exist (desktopShellNextPane), and do nothing with fewer than two.
 * Every method runs on the event dispatch thread, as the composition and the key input do.
 */

/** The panes F6 cycles, in cycle order (spec 6.1: rail, content, side panel). The side panel waits for the video page layout. */
internal enum class DesktopShellPane { RAIL, CONTENT, SIDE_PANEL }

/**
 * The pane that F6 (direction 1) or Shift+F6 (direction -1) moves to. available are the panes that exist now; current is the pane
 * that holds the focus, or null when none does (the native video surface, or no focus). With fewer than two panes there is
 * nothing to cycle between and the result is null. From outside every pane the first (forward) or the last (backward) is taken.
 */
internal fun desktopShellNextPane(available: List<DesktopShellPane>, current: DesktopShellPane?, direction: Int): DesktopShellPane? {
    val order = DesktopShellPane.entries.filter { it in available }
    if (order.size < 2) return null
    val index = if (current == null) -1 else order.indexOf(current)
    if (index < 0) return if (direction < 0) order.last() else order.first()
    val step = if (direction < 0) -1 else 1
    return order[(index + step + order.size) % order.size]
}

/** A repeat of a held key arrives within this time of the press or of the repeat before it; the first repeat waits for the keyboard delay. */
internal const val DESKTOP_SHELL_REPEAT_WINDOW_MS = 1500L

/**
 * The claimed presses that are still held. A key-down of the same chord within the repeat window of a claimed press is a repeat: it
 * is consumed and acts no more (a held Alt+Enter must not flip fullscreen over and over). The release of a claimed press is
 * consumed as well. A press whose release never arrived (the window lost the focus) stops counting after the window, and a
 * different chord on the same key is never taken for a repeat.
 */
internal class DesktopShellHeldKeys(private val nowMs: () -> Long) {
    private class Held(val chord: String, var lastMs: Long)

    private val held = HashMap<String, Held>()

    /** True when this key-down is a repeat of the claimed press that is still held. */
    fun isRepeat(key: String, chord: String): Boolean {
        val press = held[key] ?: return false
        val now = nowMs()
        if (press.chord == chord && now - press.lastMs in 0L..DESKTOP_SHELL_REPEAT_WINDOW_MS) {
            press.lastMs = now
            return true
        }
        held.remove(key)
        return false
    }

    fun claim(key: String, chord: String) {
        held[key] = Held(chord, nowMs())
    }

    /** True when the release belongs to a claimed press, which then consumes it. */
    fun release(key: String): Boolean = held.remove(key) != null
}

/**
 * The shell page of a route (the key a destination or the stack top carries), or null where the shortcuts are off. A route without
 * the rail (video, audio, Bangumi and offline players) is PLAYER, the same set the Root chrome calls video. The onboarding route has
 * none: the shortcuts must not push a page over the first-run flow.
 */
internal fun desktopShellPageOf(destination: BiliPaiNavKey): DesktopShellPage? = when (destination) {
    BiliPaiNavKey.Onboarding -> null
    BiliPaiNavKey.Home -> DesktopShellPage.HOME
    BiliPaiNavKey.Dynamic -> DesktopShellPage.DYNAMIC
    is BiliPaiNavKey.Search, BiliPaiNavKey.Search, BiliPaiNavKey.SearchTrending -> DesktopShellPage.SEARCH
    is BiliPaiNavKey.VideoDetail, is BiliPaiNavKey.AudioMode, is BiliPaiNavKey.NativeMusic,
    is BiliPaiNavKey.BangumiPlayer, is BiliPaiNavKey.OfflineVideoPlayer -> DesktopShellPage.PLAYER
    else -> DesktopShellPage.OTHER
}

/**
 * A key event as the resolver reads it: the key by the chord name of its AWT virtual key code (the table of the player shortcuts
 * names every key of the shell table too), the four modifiers, and whether it is a press. Null for a key no chord names and for an
 * event that is neither a press nor a release.
 */
internal fun desktopShellKeyDataOf(event: KeyEvent): DesktopShellKeyData? {
    val keyDown = event.type == KeyEventType.KeyDown
    if (!keyDown && event.type != KeyEventType.KeyUp) return null
    val name = desktopPlayerKeyName(event.key.nativeKeyCode) ?: return null
    return DesktopShellKeyData(
        key = name, ctrl = event.isCtrlPressed, alt = event.isAltPressed, shift = event.isShiftPressed,
        meta = event.isMetaPressed, keyDown = keyDown,
    )
}

/**
 * True while a text editor owns the keyboard. A Swing text component is one. The Compose scene is one while one of its text fields is
 * focused: the layer component of the scene answers getInputMethodRequests with the request of the active text input session and with
 * null otherwise (it is what turns the input method on and off), so a non-null answer from the focus owner or one of its parents says
 * that a text field of the scene has the focus. Never throws.
 */
internal fun desktopShellTextEditorFocused(
    focusOwner: Component? = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner,
): Boolean = generateSequence(focusOwner) { it.parent }.any { it is JTextComponent || it.inputMethodRequests != null }

/**
 * What the Root stack lends the host: everything that needs the route owner and the chrome. owner identifies the binding, so a
 * predecessor cannot clear a successor. page is null while the shortcuts are off. Each effect answers whether it acted.
 */
internal class DesktopShellRootCommands(
    val owner: Any,
    val page: () -> DesktopShellPage?,
    val canGoBack: () -> Boolean,
    val openSearch: () -> Boolean,
    val refresh: (DesktopShellPage) -> Boolean,
    val goTo: (DesktopRailItem) -> Boolean,
    val openHelp: () -> Boolean,
)

/** What the shell frame lends the host: the rail toggle (false when there is none) and the panes with their focus. */
internal class DesktopShellFrameCommands(
    val owner: Any,
    val toggleRail: () -> Boolean,
    val availablePanes: () -> List<DesktopShellPane>,
    val focusPane: (DesktopShellPane) -> Boolean,
)

internal class DesktopShellShortcutHost(
    private val requestBack: () -> Boolean,
    private val textEditorFocused: () -> Boolean = { desktopShellTextEditorFocused() },
    nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val refreshTargets: DesktopRefreshTargets = DesktopRefreshRegistry.shared,
) {
    private var root: DesktopShellRootCommands? = null
    private var frame: DesktopShellFrameCommands? = null
    private var focusedPane: DesktopShellPane? = null
    private var backHeld = false
    private val held = DesktopShellHeldKeys(nowMs)

    fun bindRoot(commands: DesktopShellRootCommands) {
        root = commands
    }

    fun unbindRoot(owner: Any) {
        if (root?.owner === owner) root = null
    }

    fun bindFrame(commands: DesktopShellFrameCommands) {
        frame = commands
    }

    fun unbindFrame(owner: Any) {
        if (frame?.owner === owner) {
            frame = null
            focusedPane = null
        }
    }

    /** A pane reports its focus. Losing the focus clears the current pane only when that pane still is the current one. */
    fun paneFocusChanged(pane: DesktopShellPane, hasFocus: Boolean) {
        if (hasFocus) focusedPane = pane else if (focusedPane == pane) focusedPane = null
    }

    /** True when the shell consumed the event. toggleFullscreen is the fullscreen switch of the Root, which F11 already uses. */
    fun onKey(event: KeyEvent, toggleFullscreen: () -> Unit): Boolean {
        val data = desktopShellKeyDataOf(event) ?: return false
        return handleKeyOrRefreshFallback(data, toggleFullscreen)
    }

    /** The shell table first; a key that no table row claims on this page may still be the refresh key of a list page's button. */
    internal fun handleKeyOrRefreshFallback(data: DesktopShellKeyData, toggleFullscreen: () -> Unit): Boolean =
        handleKey(data, toggleFullscreen) || refreshFallback(data)

    internal fun handleKey(data: DesktopShellKeyData, toggleFullscreen: () -> Unit): Boolean {
        if (!data.keyDown) return held.release(data.key)
        val commands = root ?: return false
        val page = commands.page() ?: return false
        val chord = desktopShellChord(data)
        if (held.isRepeat(data.key, chord)) return true
        val action = resolveDesktopShellKey(data, DesktopShellKeyContext(page, textEditorFocused())) ?: return false
        if (!perform(action, page, commands, toggleFullscreen)) return false
        held.claim(data.key, chord)
        return true
    }

    private fun perform(
        action: DesktopShellAction,
        page: DesktopShellPage,
        commands: DesktopShellRootCommands,
        toggleFullscreen: () -> Unit,
    ): Boolean = when (action.kind) {
        DesktopShellActionKind.OPEN_SEARCH -> commands.openSearch()
        DesktopShellActionKind.BACK -> back(commands)
        DesktopShellActionKind.REFRESH -> commands.refresh(page)
        DesktopShellActionKind.GO_DESTINATION -> action.destination?.let { commands.goTo(it) } ?: false
        DesktopShellActionKind.RAIL_TOGGLE -> frame?.toggleRail?.invoke() ?: false
        DesktopShellActionKind.OPEN_SETTINGS -> commands.goTo(DesktopRailItem.SETTINGS)
        DesktopShellActionKind.OPEN_DOWNLOADS -> commands.goTo(DesktopRailItem.DOWNLOADS)
        DesktopShellActionKind.CYCLE_PANES -> cyclePanes(action.direction)
        DesktopShellActionKind.SHORTCUT_HELP -> commands.openHelp()
        DesktopShellActionKind.FULLSCREEN_TOGGLE -> {
            toggleFullscreen()
            true
        }
    }

    /** Back through the window navigation owner. At the first page it does nothing (and says it acted): Back must never close the app. */
    private fun back(commands: DesktopShellRootCommands): Boolean = if (commands.canGoBack()) requestBack() else true

    /**
     * TON-1026: the shell table gives F5 and Ctrl+R to the Home and Dynamic pages only (the contract stays as it is). On every other
     * list page the key goes to the refresh button that page shows, if one is showing; the player and the search page, and a focused
     * text editor, keep the key.
     */
    private fun refreshFallback(data: DesktopShellKeyData): Boolean {
        val page = root?.page?.invoke()
        if (page != DesktopShellPage.OTHER || !desktopRefreshChord(data) || textEditorFocused()) return false
        if (!refreshTargets.refreshTopmost(DESKTOP_REFRESH_SHORTCUT_SOURCE)) return false
        return true
    }

    private fun cyclePanes(direction: Int): Boolean {
        val commands = frame ?: return false
        val target = desktopShellNextPane(commands.availablePanes(), focusedPane, direction) ?: return false
        return commands.focusPane(target)
    }

    /**
     * The mouse Back button went down (backPressed is its state in the event). One physical press acts once: while the button stays
     * down another button's press does not repeat it. Answers whether Back was asked for and done.
     */
    fun onBackButtonPress(backPressed: Boolean): Boolean {
        val fire = backPressed && !backHeld
        backHeld = backPressed
        if (!fire) return false
        val commands = root ?: return false
        if (commands.page() == null) return false
        return back(commands)
    }

    fun onBackButtonRelease(backPressed: Boolean) {
        backHeld = backPressed
    }
}

internal val LocalDesktopShellShortcutHost = staticCompositionLocalOf<DesktopShellShortcutHost?> { null }

/** The mouse Back button of the window asks the host for Back. Without a host the modifier changes nothing. */
internal fun Modifier.desktopShellBackButton(host: DesktopShellShortcutHost?): Modifier =
    if (host == null) {
        this
    } else {
        this
            .onPointerEvent(PointerEventType.Press) { event -> host.onBackButtonPress(event.buttons.isBackPressed) }
            .onPointerEvent(PointerEventType.Release) { event -> host.onBackButtonRelease(event.buttons.isBackPressed) }
    }

/** A pane of the shell: a focus group that F6 can enter through its requester and that tells the host while the focus is inside it. */
internal fun Modifier.desktopShellPane(host: DesktopShellShortcutHost?, pane: DesktopShellPane, requester: FocusRequester): Modifier =
    this.focusRequester(requester).onFocusChanged { host?.paneFocusChanged(pane, it.hasFocus) }.focusGroup()

/** The focus goes to the first focusable inside the pane. A pane that is not attached yet is "not now", never a failure. */
private fun desktopShellFocusPane(pane: DesktopShellPane, rail: FocusRequester, content: FocusRequester): Boolean = try {
    when (pane) {
        DesktopShellPane.RAIL -> rail.requestFocus()
        DesktopShellPane.CONTENT -> content.requestFocus()
        DesktopShellPane.SIDE_PANEL -> false
    }
} catch (_: IllegalStateException) {
    false
}

/**
 * The frame lends the host its rail toggle and its two panes. railToggleable is false for the classic text block, which has no
 * toggle; the toggle itself is the frame's (it writes the opposite mode, or says the window is too narrow).
 */
@Composable
internal fun DesktopShellFrameBinding(
    railVisible: Boolean,
    railToggleable: Boolean,
    toggle: () -> Unit,
    rail: FocusRequester,
    content: FocusRequester,
) {
    val host = LocalDesktopShellShortcutHost.current ?: return
    val commands = DesktopShellFrameCommands(
        owner = rail,
        toggleRail = {
            if (railVisible && railToggleable) {
                toggle()
                true
            } else {
                false
            }
        },
        availablePanes = { if (railVisible) listOf(DesktopShellPane.RAIL, DesktopShellPane.CONTENT) else listOf(DesktopShellPane.CONTENT) },
        focusPane = { pane -> desktopShellFocusPane(pane, rail, content) },
    )
    SideEffect { host.bindFrame(commands) }
    DisposableEffect(host, rail) { onDispose { host.unbindFrame(rail) } }
}

/**
 * The Root stack lends the host the commands that need the route owner and the chrome. Every effect keeps the owner guard of the
 * rail clicks and goes through the same admitted push entry, so there is no second navigation path. The page is read from the
 * destination the stack shows (the pager tab on the main host), so it is current at the moment of the key.
 */
@Composable
internal fun DesktopShellRootBinding(
    routes: DesktopOriginalRootRouteAssembly,
    chrome: DesktopOriginalRootChromeBindings,
    visibleItems: List<BottomNavItem>,
) {
    val host = LocalDesktopShellShortcutHost.current ?: return
    val commands = DesktopShellRootCommands(
        owner = routes,
        page = { if (routes.owns()) desktopShellPageOf(routes.loginReadDestination()) else null },
        canGoBack = { !(routes.stack.size <= 1 && routes.loginReadDestination() == BiliPaiNavKey.Home) },
        openSearch = {
            if (routes.owns()) {
                chrome.actions.searchClick()
                true
            } else {
                false
            }
        },
        refresh = { page -> desktopShellRefresh(routes, chrome, page) },
        goTo = { item -> desktopShellGoTo(routes, visibleItems, item) },
        openHelp = { desktopShellOpenHelp(routes) },
    )
    SideEffect { host.bindRoot(commands) }
    DisposableEffect(host, routes) { onDispose { host.unbindRoot(routes) } }
    // The refresh buttons promise F5 only on the pages where the key reaches them.
    DisposableEffect(commands.owner) {
        val provider: () -> DesktopShellPage? = { commands.page() }
        DesktopRefreshRegistry.pageProvider = provider
        onDispose { if (DesktopRefreshRegistry.pageProvider === provider) DesktopRefreshRegistry.pageProvider = { null } }
    }
}

/**
 * F5 and Ctrl+R: the scroll to the top and refresh of a second click on the selected recommend row, and for the dynamic page
 * the same scroll with an unconditional refresh (the second click on its row only refreshes when the list is already at the top).
 * The corner refresh button of the page does the same two steps (TON-1026).
 */
private fun desktopShellRefresh(
    routes: DesktopOriginalRootRouteAssembly,
    chrome: DesktopOriginalRootChromeBindings,
    page: DesktopShellPage,
): Boolean {
    if (!routes.owns()) return false
    // The button the page shows is the first answer: the key and the click then run the very same refresh.
    if ((page == DesktopShellPage.HOME || page == DesktopShellPage.DYNAMIC) &&
        DesktopRefreshRegistry.shared.refreshTopmost(DESKTOP_REFRESH_SHORTCUT_SOURCE)) return true
    return when (page) {
        DesktopShellPage.HOME -> {
            chrome.actions.homeDoubleTap()
            true
        }
        DesktopShellPage.DYNAMIC -> {
            chrome.actions.dynamicRefresh()
            true
        }
        else -> false
    }
}

/**
 * Ctrl+number, Ctrl+comma and Ctrl+J: the tab push of the rail click, or the downloads route. A destination that is already shown is
 * left alone (the key is claimed, nothing is pushed twice). An item the shell does not offer (no BottomNavItem) is not claimed.
 */
private fun desktopShellGoTo(
    routes: DesktopOriginalRootRouteAssembly,
    visibleItems: List<BottomNavItem>,
    item: DesktopRailItem,
): Boolean {
    if (!routes.owns()) return false
    if (item == DesktopRailItem.DOWNLOADS) {
        if (routes.currentKey != BiliPaiNavKey.DownloadList) routes.push(BiliPaiNavKey.DownloadList)
        return true
    }
    val bottom = item.bottomNavName?.let { name -> visibleItems.firstOrNull { it.name == name } } ?: return false
    val target = bottomPagerNavKeyForItem(bottom)
    if (routes.loginReadDestination() != target) routes.push(target)
    return true
}

/** F1: the shortcut list is the Tips page. */
private fun desktopShellOpenHelp(routes: DesktopOriginalRootRouteAssembly): Boolean {
    if (!routes.owns()) return false
    if (routes.currentKey != BiliPaiNavKey.TipsSettings) routes.push(BiliPaiNavKey.TipsSettings)
    return true
}
