package com.bilipai.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Pointer

/** Fixed x64 client.h node layout; read only on the existing ready Session actor, including before first Load. */
internal fun readWindowsAudioDevices(native: MpvNative, handle: Pointer): DesktopWindowsAudioDeviceListState {
    check(com.sun.jna.Native.POINTER_SIZE == 8)
    fun text(pointer: Pointer?, limit: Int): String {
        checkNotNull(pointer)
        var count = 0
        while (count < limit && pointer.getByte(count.toLong()) != 0.toByte()) count++
        check(count < limit) { "音频设备字符串超过原生接口限制" }
        return String(pointer.getByteArray(0, count), Charsets.UTF_8)
    }
    Memory(16).use { node ->
        node.clear()
        val code = native.mpv_get_property(handle, "audio-device-list", 6, node)
        if (code < 0) return DesktopWindowsAudioDeviceListState(error = "原生音频设备目录不可用 ($code)")
        try {
            check(node.getInt(8) == 7)
            val array = checkNotNull(node.getPointer(0))
            val count = array.getInt(0); check(count in 0..512)
            val values = array.getPointer(8); check(count == 0 || values != null)
            val devices = mutableListOf<DesktopWindowsAudioDevice>()
            repeat(count) { index ->
                val item = checkNotNull(values).share(index * 16L); check(item.getInt(8) == 8)
                val map = checkNotNull(item.getPointer(0)); val fields = map.getInt(0); check(fields in 0..16)
                val fieldValues = map.getPointer(8); val fieldKeys = map.getPointer(16)
                check(fields == 0 || (fieldValues != null && fieldKeys != null))
                val strings = mutableMapOf<String, String>()
                repeat(fields) { field ->
                    val key = text(checkNotNull(fieldKeys).getPointer(field * 8L), 128)
                    if (key == "name" || key == "description") {
                        val value = checkNotNull(fieldValues).share(field * 16L); check(value.getInt(8) == 1)
                        check(!strings.containsKey(key))
                        strings[key] = text(value.getPointer(0), if (key == "name") 1025 else 4096)
                    }
                }
                val name = checkNotNull(strings["name"])
                if (name.startsWith("wasapi/")) {
                    DesktopWindowsAudioOutputPreferences(deviceId = name).requireValid()
                    devices += DesktopWindowsAudioDevice(name, strings["description"]?.takeIf { it.isNotBlank() } ?: name)
                }
            }
            check(devices.map { it.name }.distinct().size == devices.size)
            return DesktopWindowsAudioDeviceListState(java.util.Collections.unmodifiableList(devices.toList()), available = true)
        } catch (_: IllegalStateException) {
            return DesktopWindowsAudioDeviceListState(error = "原生音频设备目录格式不可用")
        } catch (_: IllegalArgumentException) {
            return DesktopWindowsAudioDeviceListState(error = "原生音频设备目录标识无效")
        } finally { native.mpv_free_node_contents(node) }
    }
}
