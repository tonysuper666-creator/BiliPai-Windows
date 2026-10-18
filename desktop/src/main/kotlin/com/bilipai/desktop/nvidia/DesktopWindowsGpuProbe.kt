package com.bilipai.desktop.nvidia

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary

// Read-only Windows adapter identity for the NVIDIA auto-detection. JNA core only (no jna-platform),
// the same style as the display state reader of the player package. No device is created and no
// setting is changed; every failure is "unknown", never an exception into the caller.
//
// The adapter name request is DISPLAYCONFIG_ADAPTER_NAME: a 20 byte header followed by the
// 256 byte WCHAR adapterDevicePath, 276 bytes in all.

/**
 * Maps a DXGI adapter LUID to its PnP device path, which is the only thing that tells a physical
 * card from a virtual twin carrying its exact name, memory and driver. A path that starts with the
 * PCI NVIDIA prefix and carries the same device id is physical (see classifyNvidiaAdapterPath).
 */
internal object DesktopWindowsAdapterIdentity {
    /** DISPLAYCONFIG_DEVICE_INFO_GET_ADAPTER_NAME. */
    const val DEVICE_INFO_GET_ADAPTER_NAME: Int = 4

    /** Header (type, size, LUID, id) is 20 bytes; the path is a WCHAR[128]. */
    const val ADAPTER_NAME_PACKET_SIZE: Int = 276
    const val ADAPTER_NAME_LUID_LOW_OFFSET: Long = 8L
    const val ADAPTER_NAME_LUID_HIGH_OFFSET: Long = 12L
    const val ADAPTER_NAME_PATH_OFFSET: Long = 20L
    const val ADAPTER_NAME_PATH_BYTES: Int = 256

    private interface IdentityApi : StdCallLibrary {
        fun DisplayConfigGetDeviceInfo(packet: Pointer): Int
    }

    private val api: IdentityApi by lazy { Native.load("user32", IdentityApi::class.java) }

    /** Fills the request header for one LUID. The caller clears nothing: this clears the packet. */
    internal fun encodeAdapterNameRequest(packet: Memory, luidHigh: Int, luidLow: Int) {
        packet.clear()
        packet.setInt(0, DEVICE_INFO_GET_ADAPTER_NAME)
        packet.setInt(4, ADAPTER_NAME_PACKET_SIZE)
        packet.setInt(ADAPTER_NAME_LUID_LOW_OFFSET, luidLow)
        packet.setInt(ADAPTER_NAME_LUID_HIGH_OFFSET, luidHigh)
        packet.setInt(16, 0)
    }

    /** The path text up to the first NUL, or null when it is empty. Pure over the packet memory. */
    internal fun decodeAdapterNamePath(packet: Pointer): String? {
        val text = String(packet.getByteArray(ADAPTER_NAME_PATH_OFFSET, ADAPTER_NAME_PATH_BYTES), Charsets.UTF_16LE)
            .substringBefore('\u0000')
        return if (text.isEmpty()) null else text
    }

    /** The PnP device path of the adapter with this LUID, or null when Windows could not say. */
    fun adapterDevicePath(luidHigh: Int, luidLow: Int): String? {
        if (!Platform.isWindows() || Native.POINTER_SIZE != 8 || Native.WCHAR_SIZE != 2) return null
        return try {
            Memory(ADAPTER_NAME_PACKET_SIZE.toLong()).use { packet ->
                encodeAdapterNameRequest(packet, luidHigh, luidLow)
                if (api.DisplayConfigGetDeviceInfo(packet) != 0) return@use null
                decodeAdapterNamePath(packet)
            }
        } catch (_: LinkageError) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** PHYSICAL, VIRTUAL, or UNKNOWN when the query itself failed. */
    fun physicalVerdict(luidHigh: Int, luidLow: Int, dxgiDeviceId: Int): NvidiaPhysicalVerdict =
        classifyNvidiaAdapterPath(adapterDevicePath(luidHigh, luidLow), dxgiDeviceId)
}
