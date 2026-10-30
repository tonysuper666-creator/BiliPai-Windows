package com.bilipai.desktop.ui

import com.android.purebilibili.feature.home.components.BottomNavItem
import com.bilipai.desktop.appearance.DesktopWindowsUxRailMode
import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure rail model against the shared contract (rows, groups, thresholds, footprints, fixed destinations, badge cap).
 * Only railLabelsEqualTheVendoredBottomNavLabels and bottomNavNamesAreRealConstants touch the vendored BottomNavItem
 * enum. No window, no GPU, no network.
 */
class DesktopWindowsRailModelTest {
    private val rail = DesktopUxContract.section("rail")

    /** The BottomNavItem names the shell offers today: the vendored order without STORY. */
    private val shellItems = listOf("HOME", "DYNAMIC", "HISTORY", "LISTEN_VIDEO", "PROFILE", "FAVORITE", "LIVE", "WATCHLATER", "SETTINGS", "PLUGINS")

    private fun presentationNamed(name: String): DesktopRailPresentation =
        DesktopRailPresentation.entries.first { it.contractName == name }

    private fun walk(mode: DesktopWindowsUxRailMode, widths: IntProgression): List<Pair<Int, DesktopRailPresentation>> {
        val steps = ArrayList<Pair<Int, DesktopRailPresentation>>()
        var previous: DesktopRailPresentation? = null
        for (width in widths) {
            val now = desktopRailPresentation(mode, width, previous)
            steps.add(width to now)
            previous = now
        }
        return steps
    }

    private fun names(entries: List<DesktopRailEntry>): List<String> = entries.map { it.item?.name ?: ("?" + it.bottomNavName) }

    @Test fun footprintsAndTheBadgeCapAreTheContractNumbers() {
        val footprint = rail.getValue("footprint").jsonObject
        for (presentation in DesktopRailPresentation.entries) {
            assertEquals(DesktopUxContract.int(footprint, presentation.contractName), presentation.footprintDp, presentation.name)
        }
        assertEquals(setOf("expanded", "collapsed", "classic"), DesktopRailPresentation.entries.map { it.contractName }.toSet())
        assertEquals(DesktopUxContract.int(rail.getValue("metrics").jsonObject, "badge_cap"), DESKTOP_RAIL_BADGE_CAP)
        assertEquals(216, DesktopRailPresentation.EXPANDED.footprintDp)
        assertEquals(72, DesktopRailPresentation.COLLAPSED.footprintDp)
        assertEquals(172, DesktopRailPresentation.CLASSIC.footprintDp)
    }

    @Test fun thresholdsAreTheContractNumbers() {
        val thresholds = rail.getValue("thresholds").jsonObject
        val actual = mapOf("AUTO" to DESKTOP_RAIL_AUTO_THRESHOLDS, "EXPANDED" to DESKTOP_RAIL_EXPANDED_THRESHOLDS)
        assertEquals(actual.keys, thresholds.keys)
        for ((mode, limits) in actual) {
            val row = thresholds.getValue(mode).jsonObject
            assertEquals(DesktopUxContract.int(row, "expand_at"), limits.expandAt, mode)
            assertEquals(DesktopUxContract.int(row, "collapse_below"), limits.collapseBelow, mode)
            assertEquals(DesktopUxContract.int(row, "reexpand_at"), limits.reexpandAt, mode)
        }
        assertEquals(DesktopRailThresholds(1280, 1264, 1280), DESKTOP_RAIL_AUTO_THRESHOLDS)
        assertEquals(DesktopRailThresholds(1100, 1084, 1116), DESKTOP_RAIL_EXPANDED_THRESHOLDS)
    }

    @Test fun everyGoldenRowOfTheContract() {
        for (row in DesktopUxContract.objects(rail.getValue("golden"))) {
            val id = DesktopUxContract.string(row, "id")
            val mode = DesktopWindowsUxRailMode.valueOf(DesktopUxContract.string(row, "mode"))
            val previous = row["previous"]?.jsonPrimitive?.content?.let { presentationNamed(it) }
            val expect = row.getValue("expect").jsonObject
            val actual = desktopRailPresentation(mode, DesktopUxContract.int(row, "width_dp"), previous)
            assertEquals(DesktopUxContract.string(expect, "rail"), actual.contractName, id)
            assertEquals(DesktopUxContract.int(expect, "footprint"), actual.footprintDp, id)
        }
    }

    @Test fun hysteresisEdgesAreTheSpecNumbers() {
        val auto = DesktopWindowsUxRailMode.AUTO
        assertEquals(DesktopRailPresentation.COLLAPSED, desktopRailPresentation(auto, 1279))
        assertEquals(DesktopRailPresentation.EXPANDED, desktopRailPresentation(auto, 1280))
        assertEquals(DesktopRailPresentation.EXPANDED, desktopRailPresentation(auto, 1264, DesktopRailPresentation.EXPANDED))
        assertEquals(DesktopRailPresentation.COLLAPSED, desktopRailPresentation(auto, 1263, DesktopRailPresentation.EXPANDED))
        assertEquals(DesktopRailPresentation.COLLAPSED, desktopRailPresentation(auto, 1279, DesktopRailPresentation.COLLAPSED))
        assertEquals(DesktopRailPresentation.EXPANDED, desktopRailPresentation(auto, 1280, DesktopRailPresentation.COLLAPSED))
        val wide = DesktopWindowsUxRailMode.EXPANDED
        assertEquals(DesktopRailPresentation.COLLAPSED, desktopRailPresentation(wide, 1099))
        assertEquals(DesktopRailPresentation.EXPANDED, desktopRailPresentation(wide, 1100))
        assertEquals(DesktopRailPresentation.EXPANDED, desktopRailPresentation(wide, 1084, DesktopRailPresentation.EXPANDED))
        assertEquals(DesktopRailPresentation.COLLAPSED, desktopRailPresentation(wide, 1083, DesktopRailPresentation.EXPANDED))
        assertEquals(DesktopRailPresentation.COLLAPSED, desktopRailPresentation(wide, 1115, DesktopRailPresentation.COLLAPSED))
        assertEquals(DesktopRailPresentation.EXPANDED, desktopRailPresentation(wide, 1116, DesktopRailPresentation.COLLAPSED))
    }

    @Test fun aClassicMemoryStartsAFreshDecision() {
        for (width in listOf(1000, 1263, 1264, 1279, 1280, 3000)) {
            assertEquals(
                desktopRailPresentation(DesktopWindowsUxRailMode.AUTO, width, null),
                desktopRailPresentation(DesktopWindowsUxRailMode.AUTO, width, DesktopRailPresentation.CLASSIC),
                width.toString(),
            )
        }
    }

    @Test fun collapsedAndClassicModesIgnoreTheWidthAndTheMemory() {
        for (width in listOf(0, 960, 1280, 4200, 100000)) {
            for (previous in listOf(null, DesktopRailPresentation.EXPANDED, DesktopRailPresentation.COLLAPSED)) {
                assertEquals(DesktopRailPresentation.COLLAPSED, desktopRailPresentation(DesktopWindowsUxRailMode.COLLAPSED, width, previous))
                assertEquals(DesktopRailPresentation.CLASSIC, desktopRailPresentation(DesktopWindowsUxRailMode.CLASSIC_TEXT, width, previous))
            }
        }
    }

    @Test fun wideningAndNarrowingSwitchAtDifferentWidths() {
        val sweep = DesktopUxContract.section("feed").getValue("sweep").jsonObject
        val lowest = DesktopUxContract.int(sweep, "from")
        val highest = DesktopUxContract.int(sweep, "to")
        for ((mode, expandsAt, collapsesAt) in listOf(Triple(DesktopWindowsUxRailMode.AUTO, 1280, 1263), Triple(DesktopWindowsUxRailMode.EXPANDED, 1116, 1083))) {
            val widening = walk(mode, lowest..highest)
            val narrowing = walk(mode, highest downTo lowest)
            assertEquals(expandsAt, widening.first { it.second == DesktopRailPresentation.EXPANDED }.first, mode.name)
            assertEquals(collapsesAt, narrowing.first { it.second == DesktopRailPresentation.COLLAPSED }.first, mode.name)
            assertEquals(1, widening.zipWithNext().count { (a, b) -> a.second != b.second }, mode.name)
            assertEquals(1, narrowing.zipWithNext().count { (a, b) -> a.second != b.second }, mode.name)
        }
    }

    @Test fun theContentFloorHoldsForAFreshEvaluationAndTheExpandedBandReachesTheOneBelow() {
        val floor = DesktopUxContract.int(DesktopUxContract.section("feed").getValue("rules").jsonObject, "min_content_width")
        for (mode in listOf(DesktopWindowsUxRailMode.AUTO, DesktopWindowsUxRailMode.EXPANDED, DesktopWindowsUxRailMode.COLLAPSED)) {
            for (width in 960..4200) {
                assertTrue(width - desktopRailPresentation(mode, width).footprintDp >= floor, "$mode $width")
            }
        }
        // With the collapse-below band an expanded rail survives down to 1084, leaving 868 for the content.
        val narrowest = walk(DesktopWindowsUxRailMode.EXPANDED, 4200 downTo 960).minOf { (width, presentation) -> width - presentation.footprintDp }
        assertEquals(1084 - DesktopRailPresentation.EXPANDED.footprintDp, narrowest)
        assertTrue(narrowest >= 840)
    }

    @Test fun rowsFollowTheContractGroupsAndOrder() {
        val groups = rail.getValue("groups").jsonObject
        fun listed(key: String) = DesktopUxContract.strings(groups.getValue(key))
        assertEquals(listed("browse"), DesktopRailItem.entries.filter { it.group == DesktopRailGroup.BROWSE }.map { it.name })
        assertEquals(listed("library"), DesktopRailItem.entries.filter { it.group == DesktopRailGroup.LIBRARY }.map { it.name })
        assertEquals(listed("footer"), DesktopRailItem.entries.filter { it.group == DesktopRailGroup.FOOTER }.map { it.name })
        assertEquals(listed("browse") + listed("library") + listed("footer"), DesktopRailItem.entries.map { it.name })
        assertEquals(listed("excluded").toSet(), DESKTOP_RAIL_EXCLUDED_BOTTOM_NAV)
        assertNull(DesktopRailItem.DOWNLOADS.bottomNavName)
        assertNull(DesktopRailItem.ACCOUNT_SWITCH.bottomNavName)
    }

    @Test fun ctrlNumberGoesOverTheFixedDestinationsOnly() {
        val fixed = DesktopUxContract.strings(rail.getValue("fixed_destinations"))
        assertEquals(fixed, DESKTOP_RAIL_FIXED_DESTINATIONS.map { it.name })
        assertEquals(9, fixed.size)
        for (number in -2..12) {
            val expected = if (number in 1..9) fixed[number - 1] else null
            assertEquals(expected, desktopRailDestinationForNumber(number)?.name, number.toString())
        }
        for (item in DESKTOP_RAIL_FIXED_DESTINATIONS) {
            assertEquals(item, desktopRailDestinationForNumber(desktopRailNumberOf(item)!!))
        }
        for (item in listOf(DesktopRailItem.ACCOUNT_SWITCH, DesktopRailItem.PLUGINS, DesktopRailItem.SETTINGS)) {
            assertNull(desktopRailNumberOf(item), item.name)
        }
        assertEquals(DesktopRailItem.DOWNLOADS, desktopRailDestinationForNumber(8))
        assertEquals(DesktopRailItem.PROFILE, desktopRailDestinationForNumber(9))
    }

    @Test fun theDefaultRailIsTheSpecOrderWithoutAnAccountRow() {
        assertEquals(
            listOf("HOME", "DYNAMIC", "LIVE", "LISTEN_VIDEO", "HISTORY", "FAVORITE", "WATCHLATER", "DOWNLOADS", "PROFILE", "PLUGINS", "SETTINGS"),
            names(desktopRailEntries(shellItems, accountSwitcherAvailable = false)),
        )
    }

    @Test fun theAccountRowLeadsTheFooterAndNeverShiftsANumber() {
        val withAccount = desktopRailEntries(shellItems, accountSwitcherAvailable = true)
        assertEquals(
            listOf("HOME", "DYNAMIC", "LIVE", "LISTEN_VIDEO", "HISTORY", "FAVORITE", "WATCHLATER", "DOWNLOADS", "ACCOUNT_SWITCH", "PROFILE", "PLUGINS", "SETTINGS"),
            names(withAccount),
        )
        val fixed = DESKTOP_RAIL_FIXED_DESTINATIONS.map { it.name }
        for (entries in listOf(withAccount, desktopRailEntries(shellItems, accountSwitcherAvailable = false))) {
            assertEquals(fixed, names(entries).filter { it in fixed })
        }
    }

    @Test fun storyNeverGetsARowAndAnUnknownItemJoinsTheBrowseGroup() {
        assertFalse("?STORY" in names(desktopRailEntries(shellItems + "STORY", accountSwitcherAvailable = false)))
        val withNew = names(desktopRailEntries(shellItems + "NEW_ITEM", accountSwitcherAvailable = false))
        assertEquals(withNew.indexOf("LISTEN_VIDEO") + 1, withNew.indexOf("?NEW_ITEM"))
        assertTrue(withNew.indexOf("?NEW_ITEM") < withNew.indexOf("HISTORY"))
        val unknown = desktopRailEntries(listOf("NEW_ITEM"), accountSwitcherAvailable = false).first { it.item == null }
        assertEquals(DesktopRailGroup.BROWSE, unknown.group)
        assertEquals("NEW_ITEM", unknown.bottomNavName)
    }

    @Test fun rowsWithoutAVisibleItemVanishButTheDownloadsRowStays() {
        assertEquals(listOf("HOME", "DOWNLOADS"), names(desktopRailEntries(listOf("HOME"), accountSwitcherAvailable = false)))
        assertEquals(listOf("DOWNLOADS"), names(desktopRailEntries(emptyList(), accountSwitcherAvailable = false)))
    }

    @Test fun groupsComeOutInNonDecreasingOrder() {
        for (account in listOf(false, true)) {
            for (extra in listOf(emptyList<String>(), listOf("NEW_ITEM"))) {
                val ranks = desktopRailEntries(shellItems + extra, account).map { it.group.ordinal }
                assertEquals(ranks.sorted(), ranks)
            }
        }
    }

    @Test fun selectionRuleIsTheExistingOne() {
        for (onMainHost in listOf(false, true)) {
            for (sameItem in listOf(false, true)) {
                for (sameRoute in listOf(false, true)) {
                    val actual = desktopRailItemSelected(
                        onMainHost = onMainHost,
                        currentItemName = "HOME",
                        itemName = if (sameItem) "HOME" else "LIVE",
                        currentLegacyRoute = if (sameRoute) "route" else "other",
                        itemRoute = "route",
                    )
                    assertEquals(onMainHost && sameItem || sameRoute, actual, "$onMainHost $sameItem $sameRoute")
                }
            }
        }
    }

    @Test fun clickingTheSelectedHomeOrDynamicRowRefreshes() {
        assertEquals(DesktopRailClick.HOME_DOUBLE_TAP, desktopRailClick(DesktopRailItem.HOME, selected = true))
        assertEquals(DesktopRailClick.DYNAMIC_DOUBLE_TAP, desktopRailClick(DesktopRailItem.DYNAMIC, selected = true))
        for (item in listOf(DesktopRailItem.HOME, DesktopRailItem.DYNAMIC, DesktopRailItem.LIVE, DesktopRailItem.DOWNLOADS, null)) {
            assertEquals(DesktopRailClick.NAVIGATE, desktopRailClick(item, selected = false), item?.name ?: "null")
        }
        for (item in listOf(DesktopRailItem.LIVE, DesktopRailItem.HISTORY, DesktopRailItem.PROFILE, DesktopRailItem.SETTINGS, DesktopRailItem.DOWNLOADS, null)) {
            assertEquals(DesktopRailClick.NAVIGATE, desktopRailClick(item, selected = true), item?.name ?: "null")
        }
    }

    @Test fun badgeTextCapsAtNinetyNinePlus() {
        assertNull(desktopRailBadgeText(-5))
        assertNull(desktopRailBadgeText(0))
        assertEquals("1", desktopRailBadgeText(1))
        assertEquals("9", desktopRailBadgeText(9))
        assertEquals("10", desktopRailBadgeText(10))
        assertEquals("99", desktopRailBadgeText(99))
        assertEquals("99+", desktopRailBadgeText(100))
        assertEquals("99+", desktopRailBadgeText(100000))
    }

    @Test fun anExpandedRailShowsAPillAndACollapsedOneADot() {
        for (presentation in DesktopRailPresentation.entries) {
            assertEquals(DesktopRailBadgeKind.NONE, desktopRailBadgeKind(0, presentation))
            assertEquals(DesktopRailBadgeKind.NONE, desktopRailBadgeKind(-1, presentation))
        }
        assertEquals(DesktopRailBadgeKind.PILL, desktopRailBadgeKind(3, DesktopRailPresentation.EXPANDED))
        assertEquals(DesktopRailBadgeKind.DOT, desktopRailBadgeKind(3, DesktopRailPresentation.COLLAPSED))
        val metrics = rail.getValue("metrics").jsonObject
        assertEquals(18, DesktopUxContract.int(metrics, "badge"))
        assertEquals(8, DesktopUxContract.int(metrics, "badge_dot"))
    }

    /** The presentations a rail in this mode can show: every width and every memory. */
    private fun reachable(mode: DesktopWindowsUxRailMode): Set<DesktopRailPresentation> {
        val found = HashSet<DesktopRailPresentation>()
        for (previous in listOf<DesktopRailPresentation?>(null) + DesktopRailPresentation.entries) {
            for (width in 0..5000) found.add(desktopRailPresentation(mode, width, previous))
        }
        return found
    }

    @Test fun theToggleWritesTheOppositeExplicitModeOrNothing() {
        val auto = DesktopWindowsUxRailMode.AUTO
        val expanded = DesktopWindowsUxRailMode.EXPANDED
        val collapsed = DesktopWindowsUxRailMode.COLLAPSED
        val classic = DesktopWindowsUxRailMode.CLASSIC_TEXT
        assertEquals(collapsed, desktopRailToggleMode(auto, DesktopRailPresentation.EXPANDED))
        assertEquals(expanded, desktopRailToggleMode(auto, DesktopRailPresentation.COLLAPSED))
        assertEquals(collapsed, desktopRailToggleMode(expanded, DesktopRailPresentation.EXPANDED))
        assertNull(desktopRailToggleMode(expanded, DesktopRailPresentation.COLLAPSED)) // a window too narrow for the rail: nothing to write
        assertEquals(expanded, desktopRailToggleMode(collapsed, DesktopRailPresentation.COLLAPSED))
        assertNull(desktopRailToggleMode(classic, DesktopRailPresentation.CLASSIC)) // the classic block keeps its explicit choice
    }

    @Test fun theToggleIsTotalNeverWritesTheModeInForceAndNeverLeavesClassic() {
        for (mode in DesktopWindowsUxRailMode.entries) {
            for (presentation in DesktopRailPresentation.entries) {
                val written = desktopRailToggleMode(mode, presentation)
                assertTrue(written != mode, "$mode $presentation")
                assertTrue(written == null || written == DesktopWindowsUxRailMode.COLLAPSED || written == DesktopWindowsUxRailMode.EXPANDED, "$mode $presentation")
                if (presentation == DesktopRailPresentation.CLASSIC) assertNull(written, mode.name)
            }
        }
    }

    @Test fun twoTogglesComeBackToTheFirstPresentationWhenTheWindowIsWideEnough() {
        for (width in listOf(1116, 1280, 1500, 4200)) {
            val first = desktopRailToggleMode(DesktopWindowsUxRailMode.AUTO, DesktopRailPresentation.EXPANDED)
            assertEquals(DesktopWindowsUxRailMode.COLLAPSED, first, "$width")
            val shown = desktopRailPresentation(first!!, width, DesktopRailPresentation.EXPANDED)
            assertEquals(DesktopRailPresentation.COLLAPSED, shown, "$width")
            val second = desktopRailToggleMode(first, shown)
            assertEquals(DesktopWindowsUxRailMode.EXPANDED, second, "$width")
            assertEquals(DesktopRailPresentation.EXPANDED, desktopRailPresentation(second!!, width, shown), "$width")
        }
    }

    @Test fun theMenuTableOfTheSpec() {
        val auto = DesktopWindowsUxRailMode.AUTO
        val expanded = DesktopWindowsUxRailMode.EXPANDED
        val collapsed = DesktopWindowsUxRailMode.COLLAPSED
        assertEquals(listOf(DesktopRailMenuAction.COLLAPSE), desktopRailMenuActions(auto, DesktopRailPresentation.EXPANDED))
        assertEquals(listOf(DesktopRailMenuAction.EXPAND), desktopRailMenuActions(auto, DesktopRailPresentation.COLLAPSED))
        assertEquals(
            listOf(DesktopRailMenuAction.COLLAPSE, DesktopRailMenuAction.RESTORE_AUTO),
            desktopRailMenuActions(expanded, DesktopRailPresentation.EXPANDED),
        )
        assertEquals(listOf(DesktopRailMenuAction.RESTORE_AUTO), desktopRailMenuActions(expanded, DesktopRailPresentation.COLLAPSED))
        assertEquals(
            listOf(DesktopRailMenuAction.EXPAND, DesktopRailMenuAction.RESTORE_AUTO),
            desktopRailMenuActions(collapsed, DesktopRailPresentation.COLLAPSED),
        )
        assertEquals(
            emptyList<DesktopRailMenuAction>(),
            desktopRailMenuActions(DesktopWindowsUxRailMode.CLASSIC_TEXT, DesktopRailPresentation.CLASSIC),
        )
    }

    @Test fun theMenuActionsCarryTheExplicitModesOfTheSpec() {
        assertEquals(
            listOf(DesktopWindowsUxRailMode.COLLAPSED, DesktopWindowsUxRailMode.EXPANDED, DesktopWindowsUxRailMode.AUTO),
            DesktopRailMenuAction.entries.map { it.mode },
        )
        assertEquals(listOf("COLLAPSE", "EXPAND", "RESTORE_AUTO"), DesktopRailMenuAction.entries.map { it.name })
    }

    @Test fun everyMenuEntryChangesTheModeAndDoesWhatItsLabelSays() {
        for (mode in DesktopWindowsUxRailMode.entries) {
            for (presentation in reachable(mode)) {
                for (action in desktopRailMenuActions(mode, presentation)) {
                    assertTrue(action.mode != mode, "$mode $presentation $action")
                    if (action == DesktopRailMenuAction.COLLAPSE) {
                        assertEquals(DesktopRailPresentation.EXPANDED, presentation)
                        for (width in 0..5000 step 7) {
                            assertEquals(DesktopRailPresentation.COLLAPSED, desktopRailPresentation(action.mode, width, presentation), "$width")
                        }
                    }
                    if (action == DesktopRailMenuAction.EXPAND) {
                        assertEquals(DesktopRailPresentation.COLLAPSED, presentation)
                        val expandedFrom = DESKTOP_RAIL_EXPANDED_THRESHOLDS
                        assertEquals(DesktopRailPresentation.EXPANDED, desktopRailPresentation(action.mode, expandedFrom.reexpandAt, presentation))
                        assertEquals(DesktopRailPresentation.COLLAPSED, desktopRailPresentation(action.mode, expandedFrom.expandAt - 1, presentation))
                    }
                }
            }
        }
    }

    @Test fun theToggleAndTheFirstMenuEntryAreTheSameWrite() {
        for (mode in DesktopWindowsUxRailMode.entries) {
            for (presentation in DesktopRailPresentation.entries) {
                val written = desktopRailToggleMode(mode, presentation)
                val menu = desktopRailMenuActions(mode, presentation)
                if (written != null) {
                    assertEquals(written, menu.first().mode, "$mode $presentation")
                } else {
                    assertTrue(menu.all { it.mode == DesktopWindowsUxRailMode.AUTO }, "$mode $presentation")
                }
            }
        }
    }

    @Test fun theToggleIsEffectiveOnlyWhereItsWriteChangesWhatTheRailShows() {
        for (mode in DesktopWindowsUxRailMode.entries) {
            for (presentation in reachable(mode)) {
                for (width in 0..5000) {
                    val target = desktopRailToggleMode(mode, presentation)
                    val effective = desktopRailToggleEffective(mode, presentation, width)
                    if (target == null) {
                        assertFalse(effective, "$mode $presentation $width: nothing to write")
                    } else {
                        assertEquals(desktopRailPresentation(target, width, presentation) != presentation, effective, "$mode $presentation $width")
                    }
                    if (presentation == DesktopRailPresentation.CLASSIC) assertFalse(effective, "$mode $width: the classic block has no toggle")
                    if (presentation == DesktopRailPresentation.EXPANDED && target != null) assertTrue(effective, "$mode $width: collapsing always shows")
                    if (presentation == DesktopRailPresentation.COLLAPSED && target != null) {
                        assertEquals(width >= DESKTOP_RAIL_EXPANDED_THRESHOLDS.reexpandAt, effective, "$mode $width: expanding needs the re-expand width")
                    }
                }
            }
        }
    }

    @Test fun theDefaultWindowCannotExpandTheRailSoTheToggleWritesNothingThereAndNeverGetsStuck() {
        // Main.kt opens the window 1360 dp wide, which is 1088 dp of content at the 125% default zoom (about 1075 with the frame).
        for (width in listOf(1088, 1075)) {
            val auto = DesktopWindowsUxRailMode.AUTO
            assertEquals(DesktopRailPresentation.COLLAPSED, desktopRailPresentation(auto, width), "$width: the default mode shows the collapsed rail")
            assertEquals(DesktopWindowsUxRailMode.EXPANDED, desktopRailToggleMode(auto, DesktopRailPresentation.COLLAPSED))
            assertFalse(desktopRailToggleEffective(auto, DesktopRailPresentation.COLLAPSED, width), "$width: the toggle must not write EXPANDED here")
            // What the toggle used to do: EXPANDED is still the collapsed rail at this width, and then the toggle has no target at all.
            val stuck = DesktopWindowsUxRailMode.EXPANDED
            assertEquals(DesktopRailPresentation.COLLAPSED, desktopRailPresentation(stuck, width, DesktopRailPresentation.COLLAPSED))
            assertNull(desktopRailToggleMode(stuck, DesktopRailPresentation.COLLAPSED))
            assertFalse(desktopRailToggleEffective(stuck, DesktopRailPresentation.COLLAPSED, width), "$width: and the stuck state reports it too")
        }
        // Wide enough, the same toggle expands the rail, from the explicit collapsed mode as well.
        val reexpandAt = DESKTOP_RAIL_EXPANDED_THRESHOLDS.reexpandAt
        assertFalse(desktopRailToggleEffective(DesktopWindowsUxRailMode.AUTO, DesktopRailPresentation.COLLAPSED, reexpandAt - 1))
        assertTrue(desktopRailToggleEffective(DesktopWindowsUxRailMode.AUTO, DesktopRailPresentation.COLLAPSED, reexpandAt))
        assertTrue(desktopRailToggleEffective(DesktopWindowsUxRailMode.COLLAPSED, DesktopRailPresentation.COLLAPSED, reexpandAt))
        assertEquals("窗口太窄，无法展开导航栏", DesktopShellStrings.RAIL_TOO_NARROW)
    }

    @Test fun theReachablePresentationsOfEachMode() {
        assertEquals(setOf(DesktopRailPresentation.EXPANDED, DesktopRailPresentation.COLLAPSED), reachable(DesktopWindowsUxRailMode.AUTO))
        assertEquals(setOf(DesktopRailPresentation.EXPANDED, DesktopRailPresentation.COLLAPSED), reachable(DesktopWindowsUxRailMode.EXPANDED))
        assertEquals(setOf(DesktopRailPresentation.COLLAPSED), reachable(DesktopWindowsUxRailMode.COLLAPSED))
        assertEquals(setOf(DesktopRailPresentation.CLASSIC), reachable(DesktopWindowsUxRailMode.CLASSIC_TEXT))
    }

    @Test fun bottomNavNamesAreRealConstants() {
        val vendored = BottomNavItem.entries.map { it.name }
        for (item in DesktopRailItem.entries) {
            val name = item.bottomNavName ?: continue
            assertEquals(item.name, name)
            assertTrue(name in vendored, name)
        }
        assertEquals(vendored.filter { it !in DESKTOP_RAIL_EXCLUDED_BOTTOM_NAV }.toSet(), DesktopRailItem.entries.mapNotNull { it.bottomNavName }.toSet())
        assertEquals(shellItems.toSet(), vendored.filter { it != "STORY" }.toSet())
        assertEquals(shellItems, vendored.filter { it != "STORY" })
    }

    @Test fun railLabelsKeepTheUpstreamNamesExceptTheDesktopHomeEntry() {
        for (item in DesktopRailItem.entries) {
            val name = item.bottomNavName ?: continue
            val expected = if (item == DesktopRailItem.HOME) "首页" else BottomNavItem.valueOf(name).label
            assertEquals(expected, DesktopShellStrings.railLabel(item), item.name)
        }
        assertEquals("下载与离线", DesktopShellStrings.railLabel(DesktopRailItem.DOWNLOADS))
        assertEquals("切换账号", DesktopShellStrings.railLabel(DesktopRailItem.ACCOUNT_SWITCH))
    }

    @Test fun stringsAreTheSpecTexts() {
        assertEquals("界面缩放 150%", DesktopShellStrings.zoomToast(150))
        assertEquals("动态，3 条未读", DesktopShellStrings.unreadAnnouncement("动态", 3))
        assertEquals("首页", DesktopShellStrings.railLabel(DesktopRailItem.HOME))
        assertEquals("首页　Ctrl+1", DesktopShellStrings.collapsedTooltip(DesktopShellStrings.railLabel(DesktopRailItem.HOME), 1))
        assertEquals("设置", DesktopShellStrings.collapsedTooltip("设置", null))
        assertEquals("插件", DesktopShellStrings.collapsedTooltip("插件", null))
        assertEquals("切换账号", DesktopShellStrings.collapsedTooltip("切换账号", null))
        assertEquals("Ctrl+1", DesktopShellStrings.expandedTooltip(1))
        assertNull(DesktopShellStrings.expandedTooltip(null))
        assertEquals("浏览", DesktopShellStrings.groupTitle(DesktopRailGroup.BROWSE))
        assertEquals("媒体库", DesktopShellStrings.groupTitle(DesktopRailGroup.LIBRARY))
        assertNull(DesktopShellStrings.groupTitle(DesktopRailGroup.FOOTER))
        for (item in DesktopRailItem.entries) {
            assertTrue(DesktopShellStrings.railLabel(item).isNotBlank(), item.name)
        }
        assertEquals(DesktopRailItem.entries.size, DesktopRailItem.entries.map { DesktopShellStrings.railLabel(it) }.toSet().size)
    }

    @Test fun theRailMenuEntriesHaveTheSpecLabels() {
        // Spec 3.2 joins the last two words of the third label directly; that run is forbidden in the sources (see the
        // strings object), so the label carries one connecting character.
        assertEquals("折叠导航栏", DesktopShellStrings.railMenuLabel(DesktopRailMenuAction.COLLAPSE))
        assertEquals("展开导航栏", DesktopShellStrings.railMenuLabel(DesktopRailMenuAction.EXPAND))
        assertEquals("导航栏恢复为自动", DesktopShellStrings.railMenuLabel(DesktopRailMenuAction.RESTORE_AUTO))
        assertEquals(
            DesktopRailMenuAction.entries.size,
            DesktopRailMenuAction.entries.map { DesktopShellStrings.railMenuLabel(it) }.toSet().size,
        )
        assertEquals("无法保存导航栏设置", DesktopShellStrings.RAIL_SAVE_FAILED)
        assertEquals("主导航", DesktopShellStrings.RAIL_NAME)
    }

    // ---- Slice S1: the shell frame and the rail composables ----

    @Test fun thePanelMetricsAreTheContractNumbersAndAFootprintIsThePanelPlusItsMargins() {
        val metrics = rail.getValue("metrics").jsonObject
        assertEquals(DesktopUxContract.int(rail, "panel_expanded"), DESKTOP_RAIL_PANEL_EXPANDED_DP)
        assertEquals(DesktopUxContract.int(rail, "panel_collapsed"), DESKTOP_RAIL_PANEL_COLLAPSED_DP)
        assertEquals(DesktopUxContract.int(rail, "margin"), DESKTOP_RAIL_MARGIN_DP)
        assertEquals(DesktopUxContract.int(rail, "panel_radius"), DESKTOP_RAIL_PANEL_RADIUS_DP)
        assertEquals(DesktopUxContract.int(metrics, "header"), DESKTOP_RAIL_HEADER_DP)
        assertEquals(DesktopUxContract.int(metrics, "search_row"), DESKTOP_RAIL_SEARCH_ROW_DP)
        assertEquals(DesktopUxContract.int(metrics, "search_collapsed"), DESKTOP_RAIL_SEARCH_COLLAPSED_DP)
        assertEquals(DesktopUxContract.int(metrics, "now_playing_bar"), DESKTOP_RAIL_NOW_PLAYING_BAR_DP)
        assertEquals(DesktopRailPresentation.EXPANDED.footprintDp, DESKTOP_RAIL_PANEL_EXPANDED_DP + 2 * DESKTOP_RAIL_MARGIN_DP)
        assertEquals(DesktopRailPresentation.COLLAPSED.footprintDp, DESKTOP_RAIL_PANEL_COLLAPSED_DP + 2 * DESKTOP_RAIL_MARGIN_DP)
        assertEquals(200, DESKTOP_RAIL_PANEL_EXPANDED_DP)
        assertEquals(56, DESKTOP_RAIL_PANEL_COLLAPSED_DP)
    }

    @Test fun rovingTargetsStopAtBothEndsAndNeverWrap() {
        val up = DesktopRailRovingKey.UP
        val down = DesktopRailRovingKey.DOWN
        assertEquals(0, desktopRailRovingTarget(5, 0, up))
        assertEquals(2, desktopRailRovingTarget(5, 3, up))
        assertEquals(4, desktopRailRovingTarget(5, 4, down))
        assertEquals(1, desktopRailRovingTarget(5, 0, down))
        assertEquals(0, desktopRailRovingTarget(5, 3, DesktopRailRovingKey.HOME))
        assertEquals(4, desktopRailRovingTarget(5, 1, DesktopRailRovingKey.END))
        assertEquals(0, desktopRailRovingTarget(1, 0, down))
        assertEquals(0, desktopRailRovingTarget(1, 0, up))
        // A current stop outside the list is pulled in: Down from nothing lands on the first stop.
        assertEquals(0, desktopRailRovingTarget(5, -1, down))
        assertEquals(4, desktopRailRovingTarget(5, 99, down))
        assertEquals(4, desktopRailRovingTarget(5, 99, up))
        for (key in DesktopRailRovingKey.entries) {
            assertEquals(-1, desktopRailRovingTarget(0, 0, key), key.name)
            assertEquals(-1, desktopRailRovingTarget(-3, 0, key), key.name)
        }
    }

    @Test fun rovingStaysInsideTheStopsAndMovesTheWayTheKeySays() {
        for (count in 1..14) {
            for (current in -3..count + 3) {
                for (key in DesktopRailRovingKey.entries) {
                    val target = desktopRailRovingTarget(count, current, key)
                    assertTrue(target in 0 until count, "$count $current $key")
                    val inside = current.coerceIn(0, count - 1)
                    when (key) {
                        DesktopRailRovingKey.UP -> assertTrue(target <= inside, "$count $current")
                        DesktopRailRovingKey.DOWN -> assertTrue(target >= inside || current < 0, "$count $current")
                        DesktopRailRovingKey.HOME -> assertEquals(0, target)
                        DesktopRailRovingKey.END -> assertEquals(count - 1, target)
                    }
                }
            }
        }
    }

    @Test fun theTabStopIsTheRovingStopThenTheSelectedRowThenTheFirstStop() {
        assertEquals(0, desktopRailTabStop(5, null, null))
        assertEquals(3, desktopRailTabStop(5, null, 3))
        assertEquals(2, desktopRailTabStop(5, 2, 3))
        assertEquals(0, desktopRailTabStop(5, 0, 3))
        assertEquals(4, desktopRailTabStop(5, 9, null))
        assertEquals(0, desktopRailTabStop(5, -4, null))
        assertEquals(4, desktopRailTabStop(5, null, 40))
        assertEquals(-1, desktopRailTabStop(0, 0, 0))
        assertEquals(-1, desktopRailTabStop(-1, null, null))
        for (count in 1..14) {
            for (roving in listOf<Int?>(null, -2, 0, count - 1, count + 5)) {
                for (selected in listOf<Int?>(null, 0, count - 1, count + 5)) {
                    assertTrue(desktopRailTabStop(count, roving, selected) in 0 until count, "$count $roving $selected")
                }
            }
        }
    }

    @Test fun theZoomPercentIsReadBackFromTheScaledAndTheSystemDensity() {
        // desktopWindowsScaledDensity multiplies the system density by the percent in Float: do the same here.
        for (system in listOf(1.0f, 1.25f, 1.5f, 1.75f, 1.875f, 2.0f, 2.25f, 2.5f)) {
            for (percent in 50..200 step 5) {
                val scaled = system * percent / 100f
                assertEquals(percent, desktopZoomPercent(scaled, system), "$system $percent")
            }
        }
        assertNull(desktopZoomPercent(1.5f, null))
        assertNull(desktopZoomPercent(1.5f, 0f))
        assertNull(desktopZoomPercent(1.5f, -1f))
        assertNull(desktopZoomPercent(1.5f, Float.NaN))
        assertNull(desktopZoomPercent(1.5f, Float.POSITIVE_INFINITY))
        assertNull(desktopZoomPercent(0f, 1.5f))
        assertNull(desktopZoomPercent(-1f, 1.5f))
        assertNull(desktopZoomPercent(Float.NaN, 1.5f))
        assertNull(desktopZoomPercent(Float.POSITIVE_INFINITY, 1.5f))
        assertEquals(100, desktopZoomPercent(1f, 1f))
        assertEquals(125, desktopZoomPercent(1.25f, 1f))
    }

    @Test fun theZoomToastShowsOnlyAChangeBetweenTwoKnownReads() {
        assertNull(desktopZoomToastPercent(null, 125))
        assertNull(desktopZoomToastPercent(125, 125))
        assertEquals(150, desktopZoomToastPercent(125, 150))
        assertEquals(50, desktopZoomToastPercent(125, 50))
        assertNull(desktopZoomToastPercent(150, null))
        assertNull(desktopZoomToastPercent(null, null))
        assertEquals("界面缩放 150%", DesktopShellStrings.zoomToast(desktopZoomToastPercent(125, 150) ?: 0))
    }

    @Test fun aWriteErrorIsTheRailsOwnOnlyJustAfterTheRailWrote() {
        assertEquals(5000L, DESKTOP_RAIL_SAVE_WINDOW_MS)
        assertEquals(750L, DESKTOP_RAIL_SAVE_RECHECK_MS)
        assertTrue(DESKTOP_RAIL_SAVE_RECHECK_MS < DESKTOP_RAIL_SAVE_WINDOW_MS, "the second look at a standing error lands inside the save window")
        assertFalse(desktopRailWriteFailureShown(null, 10_000L, true), "the rail never wrote")
        assertFalse(desktopRailWriteFailureShown(1000L, 1500L, false), "no error")
        assertTrue(desktopRailWriteFailureShown(1000L, 1000L, true))
        assertTrue(desktopRailWriteFailureShown(1000L, 6000L, true), "the window is inclusive")
        assertFalse(desktopRailWriteFailureShown(1000L, 6001L, true), "later errors belong to something else")
        assertFalse(desktopRailWriteFailureShown(1000L, 999L, true), "an error before the write is not a result of it")
    }

    @Test fun theToggleAndSearchTooltipsAndTheShortcutsAreTheSpecTexts() {
        assertEquals("搜索 (Ctrl+K)", DesktopShellStrings.searchTooltip())
        assertEquals("Ctrl+K", DesktopShellStrings.SEARCH_SHORTCUT)
        assertEquals("Ctrl+B", DesktopShellStrings.RAIL_TOGGLE_SHORTCUT)
        assertEquals("折叠导航栏", DesktopShellStrings.RAIL_COLLAPSE)
        assertEquals("展开导航栏", DesktopShellStrings.RAIL_EXPAND)
        assertEquals("Ctrl K", DesktopShellStrings.SEARCH_HINT)
    }
}
