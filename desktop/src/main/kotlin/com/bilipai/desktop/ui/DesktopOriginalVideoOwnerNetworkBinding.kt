package com.bilipai.desktop.ui

import kotlinx.coroutines.CancellationException

/** Per-entry view of Root's ONE existing native preferences platform.
 * The preferred real WinRT ConnectionProfile is queried for each decision.
 * Transport is distinct from InternetAccess/metering; no profile is a real false,
 * while a failed query preserves DesktopHomePreferenceCapabilityUnavailable.
 */
internal class DesktopOriginalVideoOwnerNetworkBinding(
    private val platform: DesktopHomeWindowsPreferencesPlatform,
    private val stillOwned: () -> Boolean,
) : DesktopOriginalVideoOwnerNetwork {
    private fun observation(): DesktopHomeNetworkObservation {
        if (!stillOwned()) throw CancellationException("Original video network owner retired")
        val observed = platform.currentNetwork()
        if (!stillOwned()) throw CancellationException("Original video network owner retired")
        return observed
    }

    override fun cdnNetwork(): com.bilipai.desktop.player.cache.DesktopCdnNetworkObservation {
        val current = observation()
        return com.bilipai.desktop.player.cache.DesktopCdnNetworkObservation(
            current.profilePresent && current.ianaInterfaceType == 71L, current.identityHash)
    }
    override fun isMobileData(): Boolean = observation().isMobileNetwork

    // Windows SDK shared/ipifcons.h IF_TYPE_IEEE80211 = 71 (IANA ifType).
    // Ethernet and absent profiles are not aliases for Wi-Fi.
    override fun isWifi(): Boolean = observation().let {
        it.profilePresent && it.ianaInterfaceType == 71L
    }
}
