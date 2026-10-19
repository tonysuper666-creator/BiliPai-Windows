package com.bilipai.desktop.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure value rules for the related-list gate. No WinRT, socket or window is opened.
 * Windows has no downstream capacity ABI, so "no estimate" must not by itself hold
 * the related list back on a desktop; mobile links and a measured slow link still do. */
class DesktopOriginalVideoRelatedNetworkObservationTest {
    private val ethernet = DesktopHomeNetworkObservation(true, 3, 6L, false, "ethernet")
    private val wifi = DesktopHomeNetworkObservation(true, 3, 71L, false, "wifi")
    private val wwanProfile = DesktopHomeNetworkObservation(true, 3, 71L, true, "wwan-profile")
    private val ifTypeWwan = DesktopHomeNetworkObservation(true, 3, 243L, false, "iftype-wwan")
    private val ifTypeWwan2 = DesktopHomeNetworkObservation(true, 3, 244L, false, "iftype-wwan2")
    private val localAccessOnly = DesktopHomeNetworkObservation(true, 2, 6L, false, "local-access")
    private val noProfile = DesktopHomeNetworkObservation(false, 0, 0L, false, "none")

    private fun constrained(connection: DesktopHomeNetworkObservation, kbps: Int?) =
        DesktopOriginalVideoRelatedNetworkObservation(connection, kbps).constrainedOrUnknown

    @Test
    fun noEstimateOnUnmeteredEthernetAndWifiIsNotConstrained() {
        assertFalse(constrained(ethernet, null))
        assertFalse(constrained(wifi, null))
    }

    @Test
    fun mobileLinksAreConstrainedWithOrWithoutAnEstimate() {
        for (mobile in listOf(wwanProfile, ifTypeWwan, ifTypeWwan2)) {
            assertTrue(constrained(mobile, null), mobile.identityHash)
            assertTrue(constrained(mobile, 50_000), mobile.identityHash)
        }
    }

    @Test
    fun missingInternetOrProfileIsConstrained() {
        assertTrue(constrained(localAccessOnly, null))
        assertTrue(constrained(localAccessOnly, 100_000))
        assertTrue(constrained(noProfile, null))
    }

    @Test
    fun measuredRateAtOrBelowTheUpstreamThresholdIsConstrained() {
        assertTrue(constrained(ethernet, 1_500))
        assertTrue(constrained(ethernet, 1))
        assertFalse(constrained(ethernet, 1_501))
        assertFalse(constrained(ethernet, 10_000))
    }
}
