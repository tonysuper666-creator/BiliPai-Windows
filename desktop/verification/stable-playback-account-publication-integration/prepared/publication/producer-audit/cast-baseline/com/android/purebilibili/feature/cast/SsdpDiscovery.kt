// GENERATED from app/src/main/java/com/android/purebilibili/feature/cast/SsdpDiscovery.kt; Android platform bindings only.
package com.android.purebilibili.feature.cast

import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.cast.DesktopSsdpNetwork

object SsdpDiscovery {
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val DEFAULT_TIMEOUT_MS = 8_000
    private const val RESEND_INTERVAL_MS = 1_500L
    private const val RECEIVE_SLICE_MS = 800

    private fun buildSearchPayload(searchTarget: String): String = """
        M-SEARCH * HTTP/1.1
        HOST: 239.255.255.250:1900
        MAN: "ssdp:discover"
        MX: 2
        ST: $searchTarget

    """.trimIndent().replace("\n", "\r\n")

    data class SsdpDevice(
        val location: String,
        val server: String,
        val usn: String,
        val st: String
    )

    internal fun resolveSsdpSearchPayloads(): List<String> = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "upnp:rootdevice",
        "ssdp:all"
    ).map(::buildSearchPayload).distinct()

    internal fun parseResponse(response: String): SsdpDevice? {
        val lines = response.split("\r\n", "\n")
        if (lines.isEmpty()) return null

        var location = ""
        var server = ""
        var usn = ""
        var st = ""
        var nt = ""
        var nts = ""

        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            when {
                key.equals("LOCATION", ignoreCase = true) -> location = value
                key.equals("SERVER", ignoreCase = true) -> server = value
                key.equals("USN", ignoreCase = true) -> usn = value
                key.equals("ST", ignoreCase = true) -> st = value
                key.equals("NT", ignoreCase = true) -> nt = value
                key.equals("NTS", ignoreCase = true) -> nts = value
            }
        }

        if (!isUsableSsdpDiscoveryMessage(lines.first(), nts)) return null
        if (location.isEmpty()) return null
        if (usn.isEmpty()) {
            // Some broken renderers omit USN; still usable if LOCATION is present.
            usn = location
        }
        val type = st.ifBlank { nt }
        return SsdpDevice(location, server, usn, type)
    }

    suspend fun discover(context: Context, timeoutMs: Int = DEFAULT_TIMEOUT_MS): List<SsdpDevice> = DesktopSsdpNetwork.discover(context, timeoutMs, SSDP_ADDRESS, SSDP_PORT, RESEND_INTERVAL_MS, RECEIVE_SLICE_MS, resolveSsdpSearchPayloads(), ::parseResponse)
}
