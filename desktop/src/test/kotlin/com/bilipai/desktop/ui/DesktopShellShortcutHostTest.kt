@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.awt.im.InputMethodRequests
import java.lang.reflect.Proxy
import javax.swing.JPanel
import javax.swing.JTextField
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shell shortcut host of slice S2 with recording fakes: the pane cycle, the held keys, the page of a route, the claim of every
 * table row, the page rules, the text editor rule, the Back guard, the mouse Back button, and the reduction of a key event to key data.
 * Pure: no window, no GPU, no network, no real focus owner. The composables that bind the host (DesktopShellRootBinding,
 * DesktopShellFrameBinding) and the focus groups are not run here; the Windows fixtures and the real window are their proof.
 */
class DesktopShellShortcutHostTest {
    private val host = DesktopUxContract.section("shell_host")

    @Test fun recoverablePlayerErrorUsesOriginalGlobalNavigationAndThenRestoresPlayerPolicy() {
        val rig = rig(DesktopShellPage.PLAYER)
        for (chord in listOf("Ctrl+K", "Ctrl+1", "Ctrl+B")) assertFalse(rig.down(chord), chord)
        val recovery = rig.host.admitPageErrorNavigation()
        for (chord in listOf("Ctrl+K", "Ctrl+1", "Ctrl+B", "Alt+Left", "F11")) {
            assertTrue(rig.down(chord), chord)
            assertTrue(rig.up(chord), chord)
        }
        assertEquals(listOf("openSearch", "goTo:HOME", "toggleRail", "requestBack", "fullscreen"), rig.calls)
        assertFalse(rig.down("F5"))
        rig.editor = true
        assertFalse(rig.down("Ctrl+K"))
        rig.editor = false
        recovery.close()
        assertFalse(rig.down("Ctrl+K"))
        assertFalse(rig.down("Ctrl+1"))
        assertFalse(rig.down("Ctrl+B"))
    }

    @Test fun aRetiredErrorCannotClearItsSuccessorOrKeepNavigationOnAReplacementRoot() {
        val rig = rig(DesktopShellPage.PLAYER)
        val retired = rig.host.admitPageErrorNavigation()
        val current = rig.host.admitPageErrorNavigation()
        retired.close()
        rig.bindRoot() // Rebinding the same owner keeps its mounted card.
        assertTrue(rig.down("Ctrl+K"))
        assertTrue(rig.up("Ctrl+K"))
        val replacementOwner = Any()
        rig.host.bindRoot(DesktopShellRootCommands(replacementOwner, { DesktopShellPage.PLAYER }, { true },
            { error("retired error leaked navigation") }, { false }, { true }, { false }))
        assertFalse(rig.down("Ctrl+K"))
        current.close()
        val replacement = rig.host.admitPageErrorNavigation()
        rig.host.unbindRoot(rig) // A stale owner cannot clear the current binding or its card.
        assertTrue(rig.down("Ctrl+1"))
        assertTrue(rig.up("Ctrl+1"))
        rig.host.unbindRoot(replacementOwner)
        assertFalse(rig.down("Ctrl+1"))
        replacement.close()
    }

    private companion object {
        const val K = 75
        const val L = 76
        const val DIGIT_5 = 53
        const val F5 = 116
        const val ENTER = 10
        const val LEFT = 37
        const val TAB = 9
        const val PLUS = 521
        const val NUMPAD_0 = 96
    }

    /** The host with its two bindings, recording what it asked for. */
    private class Rig(var page: DesktopShellPage? = DesktopShellPage.HOME) {
        val calls = mutableListOf<String>()
        var clock = 0L
        var editor = false
        var backAnswer = true
        var canGoBack = true
        var accepts = true
        val frameOwner = Any()
        val host = DesktopShellShortcutHost({ calls += "requestBack"; backAnswer }, { editor }, { clock })

        fun bindRoot() = host.bindRoot(
            DesktopShellRootCommands(
                owner = this,
                page = { page },
                canGoBack = { canGoBack },
                openSearch = { calls += "openSearch"; accepts },
                refresh = { shown -> calls += "refresh:$shown"; accepts },
                goTo = { item -> calls += "goTo:${item.name}"; accepts },
                openHelp = { calls += "openHelp"; accepts },
            ),
        )

        fun bindFrame(available: List<DesktopShellPane>, toggleable: Boolean = true, focusWorks: Boolean = true) = host.bindFrame(
            DesktopShellFrameCommands(
                owner = frameOwner,
                toggleRail = { if (toggleable) { calls += "toggleRail"; true } else false },
                availablePanes = { available },
                focusPane = { pane -> calls += "focus:${pane.name}"; focusWorks },
            ),
        )

        /** A key press, then (up) its release, as the root key handler delivers them. */
        fun down(chord: String): Boolean = host.handleKey(shellKeyData(chord, true)) { calls += "fullscreen" }

        fun up(chord: String): Boolean = host.handleKey(shellKeyData(chord, false)) { calls += "fullscreen" }
    }

    private fun rig(page: DesktopShellPage? = DesktopShellPage.HOME, frame: Boolean = true): Rig {
        val rig = Rig(page)
        rig.bindRoot()
        if (frame) rig.bindFrame(listOf(DesktopShellPane.RAIL, DesktopShellPane.CONTENT))
        return rig
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The contract numbers
    // ---------------------------------------------------------------------------------------------------------------

    @Test fun thePanesAndTheRepeatWindowAreTheContractNumbers() {
        assertEquals(DesktopUxContract.strings(host.getValue("panes")), DesktopShellPane.entries.map { it.name })
        assertEquals(DesktopUxContract.int(host, "repeat_window_ms").toLong(), DESKTOP_SHELL_REPEAT_WINDOW_MS)
        assertEquals(2, DesktopUxContract.int(host, "min_panes_to_cycle"))
        assertEquals(1500L, DESKTOP_SHELL_REPEAT_WINDOW_MS)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F6: the pane cycle
    // ---------------------------------------------------------------------------------------------------------------

    @Test fun f6MovesToTheNextPaneAndShiftF6ToThePreviousOneAndWraps() {
        val both = listOf(DesktopShellPane.RAIL, DesktopShellPane.CONTENT)
        assertEquals(DesktopShellPane.CONTENT, desktopShellNextPane(both, DesktopShellPane.RAIL, 1))
        assertEquals(DesktopShellPane.RAIL, desktopShellNextPane(both, DesktopShellPane.CONTENT, 1))
        assertEquals(DesktopShellPane.CONTENT, desktopShellNextPane(both, DesktopShellPane.RAIL, -1))
        assertEquals(DesktopShellPane.RAIL, desktopShellNextPane(both, DesktopShellPane.CONTENT, -1))
        val three = DesktopShellPane.entries
        assertEquals(DesktopShellPane.CONTENT, desktopShellNextPane(three, DesktopShellPane.RAIL, 1))
        assertEquals(DesktopShellPane.SIDE_PANEL, desktopShellNextPane(three, DesktopShellPane.CONTENT, 1))
        assertEquals(DesktopShellPane.RAIL, desktopShellNextPane(three, DesktopShellPane.SIDE_PANEL, 1))
        assertEquals(DesktopShellPane.SIDE_PANEL, desktopShellNextPane(three, DesktopShellPane.RAIL, -1))
    }

    @Test fun fromOutsideEveryPaneF6EntersTheFirstAndShiftF6TheLast() {
        val both = listOf(DesktopShellPane.RAIL, DesktopShellPane.CONTENT)
        assertEquals(DesktopShellPane.RAIL, desktopShellNextPane(both, null, 1))
        assertEquals(DesktopShellPane.CONTENT, desktopShellNextPane(both, null, -1))
        // A current pane that does not exist now (the side panel was closed) counts as being outside.
        assertEquals(DesktopShellPane.RAIL, desktopShellNextPane(both, DesktopShellPane.SIDE_PANEL, 1))
        assertEquals(DesktopShellPane.CONTENT, desktopShellNextPane(both, DesktopShellPane.SIDE_PANEL, -1))
    }

    @Test fun withFewerThanTwoPanesThereIsNothingToCycle() {
        assertNull(desktopShellNextPane(emptyList(), null, 1))
        assertNull(desktopShellNextPane(listOf(DesktopShellPane.CONTENT), null, 1))
        assertNull(desktopShellNextPane(listOf(DesktopShellPane.CONTENT), DesktopShellPane.CONTENT, -1))
    }

    @Test fun theCycleAgreesWithAnIndependentRingOverEverySubsetCurrentPaneAndDirection() {
        val ringOrder = listOf(DesktopShellPane.RAIL, DesktopShellPane.CONTENT, DesktopShellPane.SIDE_PANEL)
        var checked = 0
        for (mask in 0 until 8) {
            val available = ringOrder.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            for (current in listOf<DesktopShellPane?>(null) + ringOrder) {
                for (direction in listOf(-1, 0, 1, 7)) {
                    val expected = when {
                        available.size < 2 -> null
                        current == null || current !in available -> if (direction < 0) available.last() else available.first()
                        else -> available[Math.floorMod(available.indexOf(current) + (if (direction < 0) -1 else 1), available.size)]
                    }
                    assertEquals(expected, desktopShellNextPane(available, current, direction), "$available $current $direction")
                    checked++
                }
            }
        }
        assertEquals(8 * 4 * 4, checked)
    }

    @Test fun f6FollowsTheFocusedPaneAndTheOldPaneReportingLateChangesNothing() {
        val rig = rig()
        assertTrue(rig.down("F6"))
        assertEquals(listOf("focus:RAIL"), rig.calls)
        rig.up("F6")
        rig.host.paneFocusChanged(DesktopShellPane.RAIL, true)
        assertTrue(rig.down("F6"))
        assertEquals("focus:CONTENT", rig.calls.last())
        rig.up("F6")
        rig.host.paneFocusChanged(DesktopShellPane.CONTENT, true)
        rig.host.paneFocusChanged(DesktopShellPane.RAIL, false) // the old pane reports late: it is no longer the current one
        assertTrue(rig.down("Shift+F6"))
        assertEquals("focus:RAIL", rig.calls.last())
        rig.up("Shift+F6")
        // The focus left every pane (the native video surface): F6 enters the first pane again.
        rig.host.paneFocusChanged(DesktopShellPane.CONTENT, false)
        assertTrue(rig.down("F6"))
        assertEquals("focus:RAIL", rig.calls.last())
    }

    @Test fun f6IsNotClaimedWhenTheFocusCannotMoveTheFrameIsUnboundOrOnlyOnePaneExists() {
        val broken = rig(frame = false)
        broken.bindFrame(listOf(DesktopShellPane.RAIL, DesktopShellPane.CONTENT), focusWorks = false)
        assertFalse(broken.down("F6"))
        assertEquals(listOf("focus:RAIL"), broken.calls)
        val unbound = rig(frame = false)
        assertFalse(unbound.down("F6"))
        assertTrue(unbound.calls.isEmpty())
        val single = rig(frame = false)
        single.bindFrame(listOf(DesktopShellPane.CONTENT))
        assertFalse(single.down("F6"))
        assertTrue(single.calls.isEmpty())
    }

    @Test fun aFrameThatIsUnboundForgetsTheFocusAndAPredecessorCannotClearASuccessor() {
        val rig = rig()
        rig.host.paneFocusChanged(DesktopShellPane.RAIL, true)
        rig.host.unbindFrame(Any())
        assertTrue(rig.down("F6")) // the frame is still bound: a stranger's unbind changes nothing
        assertEquals("focus:CONTENT", rig.calls.last())
        rig.up("F6")
        rig.host.unbindFrame(rig.frameOwner)
        assertFalse(rig.down("F6"))
        val successor = Any()
        rig.host.bindFrame(
            DesktopShellFrameCommands(
                owner = successor,
                toggleRail = { true },
                availablePanes = { listOf(DesktopShellPane.RAIL, DesktopShellPane.CONTENT) },
                focusPane = { pane -> rig.calls += "successor:${pane.name}"; true },
            ),
        )
        rig.host.unbindFrame(rig.frameOwner)
        assertTrue(rig.down("F6")) // the old owner's late unbind did not clear the new binding
        assertEquals("successor:RAIL", rig.calls.last()) // and the focus memory of the old binding is gone
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Held keys
    // ---------------------------------------------------------------------------------------------------------------

    @Test fun aRepeatOfAClaimedPressWithinTheWindowIsConsumedAndItsReleaseToo() {
        var now = 0L
        val held = DesktopShellHeldKeys { now }
        assertFalse(held.isRepeat("K", "Ctrl+K"))
        held.claim("K", "Ctrl+K")
        now = 500
        assertTrue(held.isRepeat("K", "Ctrl+K"))
        now = 500 + DESKTOP_SHELL_REPEAT_WINDOW_MS // exactly the window after the last repeat: still one
        assertTrue(held.isRepeat("K", "Ctrl+K"))
        now += 30
        assertTrue(held.isRepeat("K", "Ctrl+K"))
        assertTrue(held.release("K"))
        assertFalse(held.release("K"))
        assertFalse(held.isRepeat("K", "Ctrl+K"))
    }

    @Test fun aPressAfterTheWindowADifferentChordAndAClockThatRunsBackwardsAreNotRepeats() {
        var now = 1_000L
        val held = DesktopShellHeldKeys { now }
        held.claim("K", "Ctrl+K")
        now = 1_000 + DESKTOP_SHELL_REPEAT_WINDOW_MS + 1
        assertFalse(held.isRepeat("K", "Ctrl+K")) // the release never came: the claim has gone stale
        assertFalse(held.release("K")) // and the stale claim was dropped by that look
        held.claim("K", "Ctrl+K")
        assertFalse(held.isRepeat("K", "K")) // the same key under other modifiers is a new press
        assertFalse(held.release("K"))
        held.claim("K", "Ctrl+K")
        now = 500
        assertFalse(held.isRepeat("K", "Ctrl+K")) // a clock that went back is not a repeat
    }

    @Test fun aHeldChordActsOnceItsRepeatsAndReleaseAreConsumedAndTheNextPressActsAgain() {
        val rig = rig()
        assertTrue(rig.down("Alt+Enter"))
        rig.clock = 600
        assertTrue(rig.down("Alt+Enter"))
        rig.clock = 630
        assertTrue(rig.down("Alt+Enter"))
        assertEquals(listOf("fullscreen"), rig.calls)
        assertTrue(rig.up("Alt+Enter"))
        rig.clock = 700
        assertTrue(rig.down("Alt+Enter"))
        assertEquals(listOf("fullscreen", "fullscreen"), rig.calls)
    }

    @Test fun theReleaseOfAKeyThatWasNeverClaimedIsLeftAlone() {
        val rig = rig()
        assertFalse(rig.up("K"))
        assertFalse(rig.up("Ctrl+K"))
        assertFalse(rig.down("K"))
        assertFalse(rig.up("K"))
    }

    @Test fun aPressThatNothingCouldActOnIsNotClaimedSoItIsTriedAgainAndItsRepeatsAreNotSwallowed() {
        val rig = rig(frame = false)
        assertFalse(rig.down("Ctrl+B"))
        rig.clock = 40
        assertFalse(rig.down("Ctrl+B"))
        rig.bindFrame(listOf(DesktopShellPane.RAIL, DesktopShellPane.CONTENT))
        rig.clock = 80
        assertTrue(rig.down("Ctrl+B"))
        assertEquals(listOf("toggleRail"), rig.calls)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The page of a route
    // ---------------------------------------------------------------------------------------------------------------

    private val routeSamples: Map<String, BiliPaiNavKey> = mapOf(
        "Home" to BiliPaiNavKey.Home,
        "Dynamic" to BiliPaiNavKey.Dynamic,
        "Search" to BiliPaiNavKey.Search(keyword = "x", openId = 5L),
        "SearchTrending" to BiliPaiNavKey.SearchTrending,
        "VideoDetail" to BiliPaiNavKey.VideoDetail("BV1xx411c7mD"),
        "AudioMode" to BiliPaiNavKey.AudioMode(),
        "NativeMusic" to BiliPaiNavKey.NativeMusic("title", "BV1xx411c7mD", 1L),
        "BangumiPlayer" to BiliPaiNavKey.BangumiPlayer(seasonId = 1L, epId = 2L),
        "OfflineVideoPlayer" to BiliPaiNavKey.OfflineVideoPlayer("task"),
        "Onboarding" to BiliPaiNavKey.Onboarding,
    )

    @Test fun everyRouteOfTheContractIsClassifiedAsTheContractSaysAndTheSamplesCoverTheContract() {
        val routes = host.getValue("page_routes").jsonObject
        val listed = HashSet<String>()
        for ((page, names) in routes) {
            for (name in DesktopUxContract.strings(names)) {
                listed += name
                val expected = if (page == "OFF") null else DesktopShellPage.valueOf(page)
                assertEquals(expected, desktopShellPageOf(routeSamples.getValue(name)), "$name is $page")
            }
        }
        assertEquals(listed, routeSamples.keys)
    }

    @Test fun theSearchCompanionObjectIsTheSearchPageToo() {
        assertEquals(DesktopShellPage.SEARCH, desktopShellPageOf(BiliPaiNavKey.Search))
        assertEquals(DesktopShellPage.SEARCH, desktopShellPageOf(BiliPaiNavKey.Search()))
    }

    @Test fun everyOtherRouteIsAShellPageWithoutTheRefreshKeys() {
        for (key in listOf(
            BiliPaiNavKey.MainHost, BiliPaiNavKey.Profile, BiliPaiNavKey.Settings, BiliPaiNavKey.TipsSettings, BiliPaiNavKey.DownloadList,
            BiliPaiNavKey.History, BiliPaiNavKey.Favorite, BiliPaiNavKey.WatchLater, BiliPaiNavKey.LiveList, BiliPaiNavKey.ListenVideo,
            BiliPaiNavKey.Login, BiliPaiNavKey.Story(),
        )) {
            assertEquals(DesktopShellPage.OTHER, desktopShellPageOf(key), key.toString())
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The claim
    // ---------------------------------------------------------------------------------------------------------------

    @Test fun everyRowOfTheTableIsClaimedOnAShellPageAndRunsItsOneEffect() {
        for (shortcut in DESKTOP_SHELL_SHORTCUTS) {
            val rig = rig(DesktopShellPage.HOME)
            assertTrue(rig.down(shortcut.chord), shortcut.chord)
            val action = shortcut.action
            val expected = when (action.kind) {
                DesktopShellActionKind.OPEN_SEARCH -> "openSearch"
                DesktopShellActionKind.BACK -> "requestBack"
                DesktopShellActionKind.REFRESH -> "refresh:HOME"
                DesktopShellActionKind.GO_DESTINATION -> "goTo:${action.destination?.name}"
                DesktopShellActionKind.RAIL_TOGGLE -> "toggleRail"
                DesktopShellActionKind.OPEN_SETTINGS -> "goTo:SETTINGS"
                DesktopShellActionKind.OPEN_DOWNLOADS -> "goTo:DOWNLOADS"
                DesktopShellActionKind.CYCLE_PANES -> if (action.direction < 0) "focus:CONTENT" else "focus:RAIL"
                DesktopShellActionKind.SHORTCUT_HELP -> "openHelp"
                DesktopShellActionKind.FULLSCREEN_TOGGLE -> "fullscreen"
            }
            assertEquals(listOf(expected), rig.calls, shortcut.chord)
            assertTrue(rig.up(shortcut.chord), shortcut.chord + " release")
        }
    }

    @Test fun ctrlNumberGoesToTheFixedDestinationsInOrder() {
        for (number in 1..9) {
            val rig = rig()
            assertTrue(rig.down("Ctrl+$number"))
            assertEquals(listOf("goTo:" + DESKTOP_RAIL_FIXED_DESTINATIONS[number - 1].name), rig.calls)
        }
        val rig = rig()
        assertFalse(rig.down("Ctrl+0"))
        assertFalse(rig.down("Ctrl+Shift+1"))
        assertTrue(rig.calls.isEmpty())
    }

    @Test fun theRefreshKeysNameThePageTheyRefresh() {
        for (page in listOf(DesktopShellPage.HOME, DesktopShellPage.DYNAMIC)) {
            for (chord in listOf("F5", "Ctrl+R")) {
                val rig = rig(page)
                assertTrue(rig.down(chord), "$chord on $page")
                assertEquals(listOf("refresh:$page"), rig.calls)
            }
        }
        for (page in listOf(DesktopShellPage.SEARCH, DesktopShellPage.OTHER, DesktopShellPage.PLAYER)) {
            val rig = rig(page)
            assertFalse(rig.down("F5"), page.name)
            assertFalse(rig.down("Ctrl+R"), page.name)
            assertTrue(rig.calls.isEmpty())
        }
    }

    @Test fun searchIsIgnoredOnTheSearchRouteAndOnAPlayerPage() {
        val search = rig(DesktopShellPage.SEARCH)
        assertFalse(search.down("Ctrl+K"))
        assertFalse(search.down("Ctrl+F"))
        val player = rig(DesktopShellPage.PLAYER)
        assertFalse(player.down("Ctrl+K"))
        val other = rig(DesktopShellPage.OTHER)
        assertTrue(other.down("Ctrl+F"))
        assertEquals(listOf("openSearch"), other.calls)
    }

    @Test fun aPlayerPageKeepsOnlyBackFullscreenTheHelpAndThePaneCycle() {
        val kept = setOf(
            DesktopShellActionKind.BACK, DesktopShellActionKind.FULLSCREEN_TOGGLE, DesktopShellActionKind.SHORTCUT_HELP,
            DesktopShellActionKind.CYCLE_PANES,
        )
        for (shortcut in DESKTOP_SHELL_SHORTCUTS) {
            val rig = rig(DesktopShellPage.PLAYER, frame = false)
            rig.bindFrame(DesktopShellPane.entries) // a player page with its side panel
            val claimed = rig.down(shortcut.chord)
            assertEquals(shortcut.action.kind in kept, claimed, shortcut.chord)
        }
    }

    @Test fun ctrlBTogglesTheRailOnlyWhereThereIsAToggle() {
        val classic = rig(frame = false)
        classic.bindFrame(listOf(DesktopShellPane.RAIL, DesktopShellPane.CONTENT), toggleable = false)
        assertFalse(classic.down("Ctrl+B"))
        assertTrue(classic.calls.isEmpty())
        val player = rig(DesktopShellPage.PLAYER)
        assertFalse(player.down("Ctrl+B")) // the side panel of the video page, not the rail
        val shell = rig()
        assertTrue(shell.down("Ctrl+B"))
        assertEquals(listOf("toggleRail"), shell.calls)
    }

    @Test fun settingsAndDownloadsAreTheRailRowsOfThoseNames() {
        val rig = rig()
        assertTrue(rig.down("Ctrl+Comma"))
        assertTrue(rig.up("Ctrl+Comma"))
        assertTrue(rig.down("Ctrl+J"))
        assertEquals(listOf("goTo:SETTINGS", "goTo:DOWNLOADS"), rig.calls)
    }

    @Test fun anEffectThatCouldNotActIsNotClaimed() {
        val rig = rig()
        rig.accepts = false
        for (chord in listOf("Ctrl+K", "F5", "Ctrl+3", "Ctrl+Comma", "Ctrl+J", "F1")) {
            assertFalse(rig.down(chord), chord)
            assertFalse(rig.up(chord), chord)
        }
        assertEquals(6, rig.calls.size)
    }

    @Test fun nothingIsClaimedWithoutTheRootBindingOrOnAPageWithoutShortcuts() {
        val unbound = Rig()
        assertFalse(unbound.down("Ctrl+K"))
        assertFalse(unbound.down("Alt+Enter"))
        assertTrue(unbound.calls.isEmpty())
        val onboarding = rig(page = null)
        for (shortcut in DESKTOP_SHELL_SHORTCUTS) assertFalse(onboarding.down(shortcut.chord), shortcut.chord)
        assertTrue(onboarding.calls.isEmpty())
        val gone = rig()
        gone.host.unbindRoot(Any())
        assertTrue(gone.down("Ctrl+K")) // a stranger's unbind changes nothing
        gone.host.unbindRoot(gone)
        assertFalse(gone.down("F1"))
    }

    @Test fun aTextEditorOwningTheFocusStopsEveryKeyAndEveryKeyReturnsWhenItLetsGo() {
        val rig = rig()
        rig.editor = true
        for (shortcut in DESKTOP_SHELL_SHORTCUTS) assertFalse(rig.down(shortcut.chord), shortcut.chord)
        assertTrue(rig.calls.isEmpty())
        rig.editor = false
        assertTrue(rig.down("Ctrl+K"))
    }

    @Test fun zoomKeysReservedChordsAndAltGrAreNeverClaimedWithEveryBindingPresent() {
        val rig = rig()
        for (chord in listOf(
            "Ctrl+Plus", "Ctrl+Equals", "Ctrl+Minus", "Ctrl+Add", "Ctrl+Subtract", "Ctrl+0", "Ctrl+Numpad0", "Ctrl+Shift+Equals",
            "Ctrl+C", "Ctrl+V", "Ctrl+X", "Ctrl+A", "Ctrl+Z", "Ctrl+Y", "Alt+F4", "Backspace", "Enter", "Ctrl+Alt+K", "Ctrl+Alt+1", "Meta+K",
        )) {
            assertFalse(rig.down(chord), chord)
        }
        assertTrue(rig.calls.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Back
    // ---------------------------------------------------------------------------------------------------------------

    @Test fun altLeftAsksTheWindowNavigationOwnerForBack() {
        val rig = rig()
        assertTrue(rig.down("Alt+Left"))
        assertEquals(listOf("requestBack"), rig.calls)
    }

    @Test fun atTheFirstPageBackDoesNothingAndSaysItActedSoTheAppIsNeverClosed() {
        val rig = rig()
        rig.canGoBack = false
        assertTrue(rig.down("Alt+Left"))
        assertTrue(rig.calls.isEmpty())
    }

    @Test fun aBackThatTheOwnerRefusesIsNotClaimed() {
        val rig = rig()
        rig.backAnswer = false
        assertFalse(rig.down("Alt+Left"))
        assertEquals(listOf("requestBack"), rig.calls)
    }

    @Test fun theMouseBackButtonActsOncePerPhysicalPressAndOnlyWithABoundPage() {
        val rig = rig()
        assertTrue(rig.host.onBackButtonPress(true))
        assertFalse(rig.host.onBackButtonPress(true)) // another button went down while Back is still held
        assertFalse(rig.host.onBackButtonPress(false)) // not a Back press at all
        rig.host.onBackButtonRelease(false)
        assertTrue(rig.host.onBackButtonPress(true))
        assertEquals(listOf("requestBack", "requestBack"), rig.calls)
        rig.host.onBackButtonRelease(true) // a release of another button leaves Back held
        assertFalse(rig.host.onBackButtonPress(true))
        rig.host.onBackButtonRelease(false)
        rig.page = null
        assertFalse(rig.host.onBackButtonPress(true))
        rig.host.onBackButtonRelease(false)
        assertFalse(Rig().host.onBackButtonPress(true))
    }

    @Test fun theMouseBackButtonDoesNothingAtTheFirstPageEitherAndFollowsTheOwnersAnswer() {
        val rig = rig()
        rig.canGoBack = false
        assertTrue(rig.host.onBackButtonPress(true))
        assertTrue(rig.calls.isEmpty())
        rig.host.onBackButtonRelease(false)
        rig.backAnswer = false
        rig.canGoBack = true
        assertFalse(rig.host.onBackButtonPress(true))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A key event as key data, and the text editor test
    // ---------------------------------------------------------------------------------------------------------------

    @Test fun aKeyEventIsReducedToTheChordNameOfItsKeyCodeAndTheFourModifiers() {
        assertEquals(
            DesktopShellKeyData("K", ctrl = true),
            desktopShellKeyDataOf(KeyEvent(Key(K), KeyEventType.KeyDown, isCtrlPressed = true)),
        )
        assertEquals(
            DesktopShellKeyData("Left", alt = true, shift = true, meta = true),
            desktopShellKeyDataOf(KeyEvent(Key(LEFT), KeyEventType.KeyDown, isAltPressed = true, isShiftPressed = true, isMetaPressed = true)),
        )
        assertEquals(DesktopShellKeyData("F5"), desktopShellKeyDataOf(KeyEvent(Key(F5), KeyEventType.KeyDown)))
        assertEquals(
            DesktopShellKeyData("5", ctrl = true, keyDown = false),
            desktopShellKeyDataOf(KeyEvent(Key(DIGIT_5), KeyEventType.KeyUp, isCtrlPressed = true)),
        )
        assertEquals(DesktopShellKeyData("Enter", alt = true), desktopShellKeyDataOf(KeyEvent(Key(ENTER), KeyEventType.KeyDown, isAltPressed = true)))
        assertEquals("Plus", desktopShellKeyDataOf(KeyEvent(Key(PLUS), KeyEventType.KeyDown))?.key)
        assertEquals("Numpad0", desktopShellKeyDataOf(KeyEvent(Key(NUMPAD_0), KeyEventType.KeyDown))?.key)
    }

    @Test fun aKeyNoChordNamesAndAnEventThatIsNeitherPressNorReleaseGiveNoKeyData() {
        assertNull(desktopShellKeyDataOf(KeyEvent(Key(TAB), KeyEventType.KeyDown))) // Tab has no chord name
        assertNull(desktopShellKeyDataOf(KeyEvent(Key(L), KeyEventType.Unknown)))
        assertNotNull(desktopShellKeyDataOf(KeyEvent(Key(L), KeyEventType.KeyUp)))
    }

    @Test fun everyKeyNameOfTheShellTableAndEveryZoomKeyHasAnAwtKeyCode() {
        val named = HashSet<String>()
        for (code in 0..2047) desktopPlayerKeyName(code)?.let { named += it }
        val needed = DESKTOP_SHELL_SHORTCUTS.map { it.chord.substringAfterLast("+") }.toSet() + DESKTOP_SHELL_ZOOM_KEYS
        for (name in needed) assertTrue(name in named, "no key code reduces to $name")
        // The zoom decoder's own codes: Compose names them so the resolver can refuse them.
        assertEquals("Plus", desktopPlayerKeyName(521))
        assertEquals("Equals", desktopPlayerKeyName(61))
        assertEquals("Minus", desktopPlayerKeyName(45))
        assertEquals("Add", desktopPlayerKeyName(107))
        assertEquals("Subtract", desktopPlayerKeyName(109))
        assertEquals("0", desktopPlayerKeyName(48))
        assertEquals("Numpad0", desktopPlayerKeyName(96))
    }

    private fun fakeRequests(): InputMethodRequests =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(InputMethodRequests::class.java)) { _, _, _ -> null } as InputMethodRequests

    /** A component that answers like the layer of the Compose scene: the request of the text input session while one is active, else null. */
    private inner class ScenePanel(private val textInputActive: Boolean) : JPanel() {
        override fun getInputMethodRequests(): InputMethodRequests? = if (textInputActive) fakeRequests() else null
    }

    @Test fun aSwingTextComponentIsATextEditor() {
        assertTrue(desktopShellTextEditorFocused(JTextField()))
    }

    @Test fun aSceneWithATextFieldInputSessionIsATextEditorThroughTheFocusOwnerOrAParent() {
        val active = ScenePanel(textInputActive = true)
        assertTrue(desktopShellTextEditorFocused(active))
        val child = JPanel()
        active.add(child)
        assertTrue(desktopShellTextEditorFocused(child))
    }

    @Test fun aSceneWithoutATextInputSessionNoFocusOwnerAndAPlainComponentAreNotTextEditors() {
        val idle = ScenePanel(textInputActive = false)
        assertFalse(desktopShellTextEditorFocused(idle))
        val child = JPanel()
        idle.add(child)
        assertFalse(desktopShellTextEditorFocused(child))
        assertFalse(desktopShellTextEditorFocused(JPanel()))
        assertFalse(desktopShellTextEditorFocused(null))
    }

    @Test fun theChordOfTheKeyDataIsTheChordOfTheContractRow() {
        val rows = DesktopUxContract.objects(DesktopUxContract.section("shortcuts").getValue("rows"))
            .filter { DesktopUxContract.string(it, "scope") == "shell" }
        for (row in rows) {
            val chord = DesktopUxContract.string(row, "chord")
            assertEquals(chord, desktopShellChord(shellKeyData(chord, true)))
        }
    }
}

/** "Ctrl+Shift+Left" as key data. */
private fun shellKeyData(chord: String, keyDown: Boolean): DesktopShellKeyData {
    val parts = chord.split("+")
    val modifiers = parts.dropLast(1).toSet()
    return DesktopShellKeyData(
        key = parts.last(), ctrl = "Ctrl" in modifiers, alt = "Alt" in modifiers, shift = "Shift" in modifiers,
        meta = "Meta" in modifiers, keyDown = keyDown,
    )
}
