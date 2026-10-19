package com.bilipai.desktop.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure quality row states against the vendored quality gate and the spec 4.9 rules. No window, no GPU, no network. */
class DesktopPlayerQualityRowsTest {
    private val ids = listOf(127, 126, 125, 120, 116, 112, 100, 80, 74, 64, 32, 16)
    private val labels = listOf("8K", "Dolby", "HDR", "4K", "1080P60", "1080P+", "Repair", "1080P", "720P60", "720P", "480P", "360P")

    private fun menu(
        current: Int? = null,
        switchable: List<Int> = emptyList(),
        loggedIn: Boolean = true,
        vip: Boolean = true,
        switching: Boolean = false,
        auto: Boolean = false,
        qualityIds: List<Int> = ids,
        qualityLabels: List<String> = labels,
        hdr: Boolean = true,
        dolbyVision: Boolean = true,
    ) = resolveDesktopPlayerQualityMenu(qualityIds, qualityLabels, current, switchable, loggedIn, vip, hdr, dolbyVision, switching, auto)

    private fun stateOf(quality: Int, current: Int? = null, switchable: List<Int> = emptyList(), loggedIn: Boolean = true, vip: Boolean = true) =
        menu(current, switchable, loggedIn, vip, qualityIds = listOf(quality), qualityLabels = listOf(quality.toString())).rows.single().state

    /** The vendored QualitySelectionMenu, rewritten as a function of the same inputs: the oracle of the gate. */
    private fun vendoredState(quality: Int, current: Int?, switchable: List<Int>, loggedIn: Boolean, vip: Boolean): DesktopPlayerQualityRowState = when {
        quality == current -> DesktopPlayerQualityRowState.CURRENT
        (quality == 100 || quality >= 112) && !vip -> DesktopPlayerQualityRowState.NEEDS_VIP
        quality >= 80 && !loggedIn -> DesktopPlayerQualityRowState.NEEDS_LOGIN
        switchable.isNotEmpty() && quality !in switchable -> DesktopPlayerQualityRowState.UNAVAILABLE
        else -> DesktopPlayerQualityRowState.SELECTABLE
    }

    /**
     * The vendored resolveAutoHighestTargetQuality without its fallback: the best of the given ids under the account ceiling
     * (VIP 129, logged in 80, logged out 64) and without a Dolby Vision or HDR id the device cannot show. The oracle of the target.
     */
    private fun vendoredAutoPlayable(accept: List<Int>, loggedIn: Boolean, vip: Boolean, hdr: Boolean, dolbyVision: Boolean): Int? {
        val ceiling = when {
            vip -> 129
            loggedIn -> 80
            else -> 64
        }
        return accept.filter { qn ->
            when (qn) {
                126 -> dolbyVision
                125 -> hdr
                else -> true
            }
        }.filter { it <= ceiling }.maxOrNull()
    }

    @Test fun theThresholdsAreTheVendoredOnes() {
        assertEquals(100, DESKTOP_PLAYER_QUALITY_SMART_REPAIR_ID)
        assertEquals(112, DESKTOP_PLAYER_QUALITY_VIP_FROM_ID)
        assertEquals(80, DESKTOP_PLAYER_QUALITY_LOGIN_FROM_ID)
        assertEquals(129, DESKTOP_PLAYER_QUALITY_AUTO_CEILING_VIP_ID)
        assertEquals(80, DESKTOP_PLAYER_QUALITY_AUTO_CEILING_LOGGED_IN_ID)
        assertEquals(64, DESKTOP_PLAYER_QUALITY_AUTO_CEILING_GUEST_ID)
        assertEquals(126, DESKTOP_PLAYER_QUALITY_DOLBY_VISION_ID)
        assertEquals(125, DESKTOP_PLAYER_QUALITY_HDR_ID)
    }

    @Test fun theRowStatesEqualTheVendoredGateForEveryCombination() {
        var checked = 0
        for (current in listOf(null, 80, 112, 120, 127, 64)) {
            for (loggedIn in listOf(false, true)) {
                for (vip in listOf(false, true)) {
                    for (switchable in listOf(emptyList<Int>(), listOf(127, 120, 80, 64), listOf(16), ids)) {
                        val rows = menu(current, switchable, loggedIn, vip).rows
                        assertEquals(ids, rows.map { it.id })
                        assertEquals(labels, rows.map { it.label })
                        for (row in rows) {
                            assertEquals(vendoredState(row.id, current, switchable, loggedIn, vip), row.state, "${row.id} $current $loggedIn $vip $switchable")
                            assertEquals(row.state == DesktopPlayerQualityRowState.SELECTABLE, row.enabled)
                            checked++
                        }
                    }
                }
            }
        }
        assertEquals(6 * 4 * 4 * 12, checked)
    }

    @Test fun theGatePrecedenceIsVipThenLoginThenUnavailable() {
        assertEquals(DesktopPlayerQualityRowState.NEEDS_VIP, stateOf(120, loggedIn = false, vip = false)) // the VIP gate comes first
        assertEquals(DesktopPlayerQualityRowState.NEEDS_LOGIN, stateOf(80, loggedIn = false))
        assertEquals(DesktopPlayerQualityRowState.NEEDS_LOGIN, stateOf(100, loggedIn = false))
        assertEquals(DesktopPlayerQualityRowState.NEEDS_VIP, stateOf(100, vip = false))
        assertEquals(DesktopPlayerQualityRowState.SELECTABLE, stateOf(74, loggedIn = false, vip = false)) // below 80 needs nothing
        assertEquals(DesktopPlayerQualityRowState.SELECTABLE, stateOf(112))
        assertEquals(DesktopPlayerQualityRowState.NEEDS_VIP, stateOf(112, vip = false))
        assertEquals(DesktopPlayerQualityRowState.UNAVAILABLE, stateOf(64, switchable = listOf(80)))
        assertEquals(DesktopPlayerQualityRowState.SELECTABLE, stateOf(64, switchable = emptyList())) // an empty list means unknown
        assertEquals(DesktopPlayerQualityRowState.SELECTABLE, stateOf(64, switchable = listOf(64)))
        assertEquals(DesktopPlayerQualityRowState.CURRENT, stateOf(120, current = 120, loggedIn = false, vip = false, switchable = listOf(16)))
    }

    @Test fun thePlayingQualityIsTheCurrentRowAndNotSelectable() {
        val shown = menu(current = 80)
        assertEquals(1, shown.rows.count { it.state == DesktopPlayerQualityRowState.CURRENT })
        assertEquals(80, shown.rows.single { it.state == DesktopPlayerQualityRowState.CURRENT }.id)
        assertEquals("1080P", shown.resolvedLabel)
        assertFalse(shown.rows.single { it.id == 80 }.enabled)
        assertNull(menu(current = 999).resolvedLabel)
        assertNull(menu(current = null).resolvedLabel)
    }

    @Test fun theAutomaticRowTargetsTheBestSelectableQualityAboveThePlayingOne() {
        assertEquals(127, menu(current = 80).autoTargetId)
        assertEquals(127, menu(current = null).autoTargetId)
        assertNull(menu(current = 127).autoTargetId) // already the best
        assertEquals(126, menu(current = 125, switchable = listOf(126, 125, 80)).autoTargetId)
        assertEquals(80, menu(current = 64, vip = false).autoTargetId) // the VIP qualities are not obtainable
        assertNull(menu(current = 80, vip = false).autoTargetId) // everything above 1080P needs the VIP
        assertNull(menu(current = 64, vip = false, loggedIn = false).autoTargetId) // a guest is capped at 64, as the original resolver does
        assertEquals(64, menu(current = 32, vip = false, loggedIn = false).autoTargetId)
        assertEquals(DesktopPlayerQualityRowState.SELECTABLE, stateOf(74, loggedIn = false, vip = false)) // 74 stays selectable by hand
        assertEquals(120, menu(current = 116, switchable = listOf(120, 116)).autoTargetId)
        assertNull(menu(current = 120, switchable = listOf(120, 116)).autoTargetId)
        assertEquals(127, menu(current = 80, switching = true).autoTargetId) // still named while switching, the rows are disabled
    }

    @Test fun theAutomaticTargetSkipsAnHdrOrDolbyVisionQualityTheDeviceCannotShow() {
        val hdrIds = listOf(126, 125, 120, 80)
        fun target(hdr: Boolean, dolbyVision: Boolean, current: Int? = 80) =
            menu(current = current, qualityIds = hdrIds, qualityLabels = hdrIds.map { it.toString() }, hdr = hdr, dolbyVision = dolbyVision).autoTargetId
        assertEquals(120, target(hdr = false, dolbyVision = false))
        assertEquals(125, target(hdr = true, dolbyVision = false))
        assertEquals(126, target(hdr = false, dolbyVision = true))
        assertEquals(126, target(hdr = true, dolbyVision = true))
        assertNull(target(hdr = false, dolbyVision = false, current = 120))
        assertNull(target(hdr = true, dolbyVision = false, current = 125))
        assertNull(menu(current = 125, switchable = listOf(126, 125, 80), dolbyVision = false).autoTargetId)
        // The explicit rows never look at the device: a hand pick of 125 or 126 is the user's choice, as in the vendored menu.
        val capable = menu(current = 80, hdr = true, dolbyVision = true).rows
        val plain = menu(current = 80, hdr = false, dolbyVision = false).rows
        assertEquals(capable, plain)
    }

    @Test fun theAutomaticTargetEqualsTheVendoredResolverForEveryCombination() {
        var checked = 0
        for (current in listOf(null, 16, 64, 74, 80, 112, 120, 125, 126, 127)) {
            for (loggedIn in listOf(false, true)) {
                for (vip in listOf(false, true)) {
                    for (hdr in listOf(false, true)) {
                        for (dolbyVision in listOf(false, true)) {
                            for (switchable in listOf(emptyList<Int>(), listOf(127, 126, 125, 120, 80, 64), ids)) {
                                val shown = menu(current, switchable, loggedIn, vip, hdr = hdr, dolbyVision = dolbyVision)
                                val above = shown.rows.filter { it.state == DesktopPlayerQualityRowState.SELECTABLE && (current == null || it.id > current) }
                                assertEquals(
                                    vendoredAutoPlayable(above.map { it.id }, loggedIn, vip, hdr, dolbyVision),
                                    shown.autoTargetId,
                                    "$current $loggedIn $vip $hdr $dolbyVision $switchable",
                                )
                                checked++
                            }
                        }
                    }
                }
            }
        }
        assertEquals(10 * 2 * 2 * 2 * 2 * 3, checked)
    }

    @Test fun switchingDisablesTheRowsAndTheAutomaticRowAndKeepsTheirStates() {
        val busy = menu(current = 80, switching = true, auto = true)
        val idle = menu(current = 80, switching = false, auto = true)
        assertEquals(idle.rows.map { it.state }, busy.rows.map { it.state })
        assertTrue(busy.rows.none { it.enabled })
        assertTrue(idle.rows.any { it.enabled })
        assertFalse(busy.autoEnabled)
        assertTrue(idle.autoEnabled)
        assertTrue(busy.autoSelected && idle.autoSelected)
        assertTrue(busy.switching)
        assertFalse(idle.switching)
    }

    @Test fun labelsFallBackToTheIdAndTheRowsKeepTheInputOrder() {
        val shown = menu(qualityIds = listOf(80, 64, 32), qualityLabels = listOf("1080P"))
        assertEquals(listOf(80 to "1080P", 64 to "64", 32 to "32"), shown.rows.map { it.id to it.label })
        assertTrue(menu(current = 80, qualityIds = emptyList(), qualityLabels = emptyList()).rows.isEmpty())
    }

    @Test fun theHintsAndThePillOfTheStrings() {
        assertEquals("需登录", DesktopPlayerStrings.qualityHint(DesktopPlayerQualityRowState.NEEDS_LOGIN))
        assertEquals("大会员", DesktopPlayerStrings.qualityHint(DesktopPlayerQualityRowState.NEEDS_VIP))
        assertEquals("不可用", DesktopPlayerStrings.qualityHint(DesktopPlayerQualityRowState.UNAVAILABLE))
        assertNull(DesktopPlayerStrings.qualityHint(DesktopPlayerQualityRowState.CURRENT))
        assertNull(DesktopPlayerStrings.qualityHint(DesktopPlayerQualityRowState.SELECTABLE))
        assertEquals("4K", DesktopPlayerStrings.qualityPill("4K", false))
        assertEquals("切换中", DesktopPlayerStrings.qualityPill("4K", true))
        assertEquals("画质", DesktopPlayerStrings.qualityPill(null, false))
        assertEquals("切换中", DesktopPlayerStrings.qualityPill(null, true))
        assertEquals("最高可用（自动）", DesktopPlayerStrings.QUALITY_AUTO)
    }

    @Test fun theOriginalQualityChainIsDescendingByIdSoAHigherIdIsBetter() {
        val chain = listOf(129, 127, 126, 125, 120, 116, 112, 100, 80, 74, 64, 32, 16)
        assertEquals(chain.sortedDescending(), chain)
    }
}
