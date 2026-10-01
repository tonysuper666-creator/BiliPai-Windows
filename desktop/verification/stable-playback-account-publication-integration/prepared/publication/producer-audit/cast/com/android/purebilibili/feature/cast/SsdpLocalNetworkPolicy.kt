// GENERATED from app/src/main/java/com/android/purebilibili/feature/cast/SsdpLocalNetworkPolicy.kt; Android platform bindings only.
package com.android.purebilibili.feature.cast

internal fun scoreLocalNetwork(
    hasWifi: Boolean,
    hasEthernet: Boolean,
    hasVpn: Boolean,
    notVpnCapability: Boolean,
): Int {
    if (!hasWifi && !hasEthernet) return -1
    var score = 0
    if (hasWifi) score += 10
    if (hasEthernet) score += 8
    if (!hasVpn) score += 5
    if (notVpnCapability) score += 3
    return score
}

internal fun isUsableSsdpDiscoveryMessage(firstLine: String, nts: String): Boolean {
    val line = firstLine.trim()
    if (line.startsWith("HTTP/1.", ignoreCase = true) && line.contains("200")) return true
    if (line.startsWith("NOTIFY", ignoreCase = true)) {
        return nts.isBlank() || nts.equals("ssdp:alive", ignoreCase = true)
    }
    return false
}
