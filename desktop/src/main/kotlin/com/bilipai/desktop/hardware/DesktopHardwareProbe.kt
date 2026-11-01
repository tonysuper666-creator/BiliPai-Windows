package com.bilipai.desktop.hardware

import com.bilipai.desktop.nvidia.DesktopNvidiaSystemGpuProbe
import com.bilipai.desktop.nvidia.DesktopWindowsAdapterIdentity
import com.bilipai.desktop.player.DesktopWindowsDxgiAdapters
import com.sun.jna.*
import com.sun.jna.Function
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/** Uses the existing all-adapter inventory; no vendor/model based decoder guesses. */
internal class DesktopHardwareProbe(private val windowId: () -> Long = { WindowsHardwareFacts.ownWindow() }) {
    fun probe(): HardwareSnapshot {
        if (!Platform.isWindows() || Native.POINTER_SIZE != 8 || Native.WCHAR_SIZE != 2)
            return HardwareSnapshot(errors = listOf("unsupported_platform"))
        val errors = mutableListOf<String>()
        val inventory = DesktopNvidiaSystemGpuProbe().probe()
        val adapters = inventory.adapters.map { a ->
            val vendor = when {
                a.isSoftware -> HardwareVendor.SOFTWARE
                a.vendorId == 0x10de -> HardwareVendor.NVIDIA
                a.vendorId == 0x1002 -> HardwareVendor.AMD
                a.vendorId == 0x8086 -> HardwareVendor.INTEL
                else -> HardwareVendor.OTHER
            }
            val path = DesktopWindowsAdapterIdentity.adapterDevicePath(a.luidHigh, a.luidLow)
            val physical = path?.startsWith("\\\\?\\PCI#VEN_", ignoreCase = true) == true
            val video = if (a.isSoftware) null else DesktopWindowsDxgiAdapters.videoCapabilitiesFor(a.luidLow, a.luidHigh)
            if (video != null && !video.complete()) errors += "decoder_query_${a.luidHex}"
            HardwareAdapter(a.luidHex, vendor, a.vendorId, a.deviceId, a.description,
                a.umdVersion?.let { v -> (3 downTo 0).joinToString(".") { ((v ushr (it * 16)) and 65535).toString() } },
                a.dedicatedVideoMemoryBytes, a.dedicatedVideoMemoryBytes < 1024L * 1024 * 1024, physical,
                video?.decoders()?.map { d -> DecodeCapability(VideoCodec.valueOf(d.codec()), d.width(), d.height(), d.bitDepth(),
                    if (d.supported()) HardwareSupport.YES else HardwareSupport.NO, d.profile()) }.orEmpty(),
                stableId=path?.let {hardwareHash(it.lowercase())})
        }
        val displays = runCatching { WindowsHardwareFacts.displays(windowId()) }
            .getOrElse { errors += (it as? HardwareQueryFailure)?.code ?: "display_query_failed"; emptyList() }
        val audio = runCatching { WindowsHardwareFacts.audio() }
            .getOrElse { errors += "audio_query_failed"; emptyList<HardwareAudioEndpoint>() to null }
        val memory = runCatching { WindowsHardwareFacts.memoryBytes() }.getOrDefault(0L)
        return HardwareSnapshot(inventory.dxgiComplete && displays.isNotEmpty(), adapters, displays,
            runCatching {WindowsHardwareFacts.cpuName()}.getOrNull() ?: System.getenv("PROCESSOR_IDENTIFIER") ?: System.getProperty("os.arch"),
            Runtime.getRuntime().availableProcessors(), memory, audio.first, audio.second, errors)
    }
}
internal class HardwareQueryFailure(val code:String):RuntimeException(code)

/** Read-only Windows APIs. Raw device names/endpoint IDs stay local and are never diagnostic fields. */
internal object WindowsHardwareFacts {
    private interface User32 : StdCallLibrary {
        fun GetDisplayConfigBufferSizes(flags: Int, paths: IntByReference, modes: IntByReference): Int
        fun QueryDisplayConfig(flags: Int, paths: IntByReference, pathBuffer: Pointer, modes: IntByReference, modeBuffer: Pointer, topology: Pointer?): Int
        fun DisplayConfigGetDeviceInfo(packet: Pointer): Int
        fun MonitorFromWindow(window: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: Pointer): Int
        fun EnumDisplayMonitors(dc: Pointer?, clip: Pointer?, callback: MonitorCallback, data: Pointer?): Int
        fun EnumWindows(callback: WindowCallback, data: Pointer?): Int
        fun IsWindowVisible(window: Pointer): Int
        fun GetWindowThreadProcessId(window: Pointer, pid: IntByReference): Int
    }
    private interface MonitorCallback : StdCallLibrary.StdCallCallback { fun invoke(monitor: Pointer, dc: Pointer?, rect: Pointer?, data: Pointer?): Int }
    private interface WindowCallback : StdCallLibrary.StdCallCallback { fun invoke(window: Pointer, data: Pointer?): Int }
    private interface Shcore : StdCallLibrary { fun GetScaleFactorForMonitor(monitor: Pointer, scale: IntByReference): Int }
    private interface Kernel32 : StdCallLibrary { fun GlobalMemoryStatusEx(status: Pointer): Int }
    private interface Advapi32 : StdCallLibrary {
        fun RegGetValueW(key:Pointer,subkey:WString,value:WString,flags:Int,type:IntByReference,data:Pointer,size:IntByReference):Int
    }
    private interface Ole32 : StdCallLibrary {
        fun CoInitializeEx(reserved: Pointer?, flags: Int): Int
        fun CoUninitialize()
        fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, result: PointerByReference): Int
        fun CoTaskMemFree(pointer: Pointer)
    }
    private val user by lazy { Native.load("user32", User32::class.java) }
    private val ole by lazy { Native.load("ole32", Ole32::class.java) }
    private fun wide(pointer: Pointer, offset: Long, bytes: Int) = String(pointer.getByteArray(offset, bytes), Charsets.UTF_16LE).substringBefore('\u0000')
    private fun header(packet: Memory, type: Int, path: Pointer, offset: Long) {
        packet.clear(); packet.setInt(0, type); packet.setInt(4, packet.size().toInt())
        packet.setLong(8, path.getLong(offset)); packet.setInt(16, path.getInt(offset + 8))
    }
    fun ownWindow(): Long {
        if (!Platform.isWindows()) return 0L
        var result = 0L
        val pid = ProcessHandle.current().pid().toInt()
        user.EnumWindows(object : WindowCallback {
            override fun invoke(window: Pointer, data: Pointer?): Int {
                val owner = IntByReference(); user.GetWindowThreadProcessId(window, owner)
                if (owner.value == pid && user.IsWindowVisible(window) != 0) { result = Pointer.nativeValue(window); return 0 }
                return 1
            }
        }, null)
        return result
    }
    fun memoryBytes(): Long = Memory(64).use { m ->
        m.clear(); m.setInt(0, 64)
        if (Native.load("kernel32", Kernel32::class.java).GlobalMemoryStatusEx(m) != 0) m.getLong(8) else 0L
    }
    fun cpuName():String?=Memory(1024).use { m ->
        m.clear();val size=IntByReference(1024)
        val status=Native.load("advapi32",Advapi32::class.java).RegGetValueW(Pointer(-2147483646L),
            WString("HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0"),WString("ProcessorNameString"),2,IntByReference(),m,size)
        if(status==0 && size.value in 2..1024) wide(m,0,size.value).trim().takeIf{it.isNotEmpty()} else null
    }
    fun displays(windowId: Long): List<HardwareDisplay> {
        val scales = mutableMapOf<String, Int?>()
        user.EnumDisplayMonitors(null, null, object : MonitorCallback {
            override fun invoke(monitor: Pointer, dc: Pointer?, rect: Pointer?, data: Pointer?): Int {
                Memory(104).use { info ->
                    info.clear(); info.setInt(0, 104)
                    if (user.GetMonitorInfoW(monitor, info) != 0) {
                        val scale = IntByReference()
                        scales[wide(info, 40, 64)] = runCatching {
                            if (Native.load("shcore", Shcore::class.java).GetScaleFactorForMonitor(monitor, scale) == 0) scale.value else null
                        }.getOrNull()
                    }
                }
                return 1
            }
        }, null)
        val windowDevice = if (windowId == 0L) null else user.MonitorFromWindow(Pointer(windowId), 2)?.let { monitor ->
            Memory(104).use { m -> m.clear(); m.setInt(0,104); if (user.GetMonitorInfoW(monitor,m) != 0) wide(m,40,64) else null }
        }
        repeat(3) {
            val pc = IntByReference(); val mc = IntByReference()
            val sizes=user.GetDisplayConfigBufferSizes(2, pc, mc)
            if(sizes!=0)throw HardwareQueryFailure("display_buffer_$sizes")
            val pathsCapacity = pc.value; val modesCapacity = mc.value
            check(pathsCapacity in 1..128 && modesCapacity in 0..512)
            Memory(pathsCapacity * 72L).use { paths -> Memory((modesCapacity * 64L).coerceAtLeast(64)).use { modes ->
                val status = user.QueryDisplayConfig(2, pc, paths, mc, modes, null)
                if (status == 122) return@repeat
                if(status!=0)throw HardwareQueryFailure("display_paths_$status")
                check(pc.value in 0..pathsCapacity && mc.value in 0..modesCapacity)
                return (0 until pc.value).map { index ->
                    val p = paths.share(index * 72L)
                    val source = Memory(84).use { m -> header(m,1,p,0); if (user.DisplayConfigGetDeviceInfo(m)==0) wide(m,20,64) else "unknown" }
                    val mi = p.getInt(12)
                    val mode = if (mi in 0 until mc.value) modes.share(mi * 64L) else null
                    val width = mode?.takeIf { it.getInt(0) == 1 }?.getInt(16) ?: 0
                    val height = mode?.takeIf { it.getInt(0) == 1 }?.getInt(20) ?: 0
                    val hdr = Memory(36).use { m ->
                        header(m,15,p,20)
                        if (user.DisplayConfigGetDeviceInfo(m) != 0) HardwareSupport.UNKNOWN to HardwareSupport.UNKNOWN
                        else {
                            val f = m.getInt(20)
                            val supported = f and 0x10 != 0
                            val enabled = supported && f and 0x20 != 0 && f and 2 != 0 && m.getInt(32) == 2
                            (if(supported) HardwareSupport.YES else HardwareSupport.NO) to (if(enabled) HardwareSupport.YES else HardwareSupport.NO)
                        }
                    }
                    val monitorId=Memory(420).use {m->
                        header(m,2,p,20)
                        if(user.DisplayConfigGetDeviceInfo(m)==0)wide(m,164,256).takeIf{it.isNotEmpty()}?.let {hardwareHash(it.lowercase())} else null
                    }
                    HardwareDisplay("%08x%08x".format(p.getInt(4),p.getInt(0)), p.getInt(28), width,height,
                        p.getInt(48), p.getInt(52), scales[source], hdr.first,hdr.second,source,source==windowDevice,monitorId)
                }
            } }
        }
        error("topology_changed")
    }
    private fun method(instance: Pointer, slot: Int): Function = Function.getFunction(instance.getPointer(0).getPointer(slot * Native.POINTER_SIZE.toLong()), Function.ALT_CONVENTION)
    private fun release(instance: Pointer?) { if(instance!=null) method(instance,2).invokeInt(arrayOf(instance)) }
    private fun guid(text: String): Memory {
        val uuid=java.util.UUID.fromString(text); val high=uuid.mostSignificantBits; val low=uuid.leastSignificantBits
        return Memory(16).also { m -> m.setInt(0,(high ushr 32).toInt()); m.setShort(4,(high ushr 16).toShort());m.setShort(6,high.toShort())
            repeat(8){m.setByte(8L+it,(low ushr (56-it*8)).toByte())} }
    }
    fun audio(): Pair<List<HardwareAudioEndpoint>,String?> {
        val initialized=ole.CoInitializeEx(null,0)
        check(initialized>=0)
        var enumerator:Pointer?=null; var collection:Pointer?=null
        try {
            guid("bcde0395-e52f-467c-8e3d-c4579291692e").use { cls -> guid("a95664d2-9614-4f35-a746-de8db63617e6").use { iid ->
                val out=PointerByReference();check(ole.CoCreateInstance(cls,null,1,iid,out)>=0);enumerator=out.value
            } }
            val e=checkNotNull(enumerator)
            val default=PointerByReference()
            val defaultId=if(method(e,4).invokeInt(arrayOf(e,0,1,default))>=0 && default.value!=null)
                try {endpointId(default.value)} finally {release(default.value)} else null
            val found=PointerByReference();check(method(e,3).invokeInt(arrayOf(e,0,1,found))>=0);collection=found.value
            val c=checkNotNull(collection);val count=IntByReference();check(method(c,3).invokeInt(arrayOf(c,count))>=0 && count.value in 0..128)
            return (0 until count.value).map { index ->
                val out=PointerByReference();check(method(c,4).invokeInt(arrayOf(c,index,out))>=0)
                val device=checkNotNull(out.value)
                try { HardwareAudioEndpoint(endpointId(device),exclusive(device)) } finally {release(device)}
            } to defaultId
        } finally {release(collection);release(enumerator);ole.CoUninitialize()}
    }
    private fun endpointId(device:Pointer):String {
        val out=PointerByReference();check(method(device,5).invokeInt(arrayOf(device,out))>=0)
        val text=checkNotNull(out.value)
        return try { hardwareHash(text.getWideString(0)) } finally {ole.CoTaskMemFree(text)}
    }
    private fun exclusive(device:Pointer):HardwareSupport = guid("1cb9ad4c-dbfa-4c32-b178-c2f568a703b2").use { iid ->
        val out=PointerByReference()
        if(method(device,3).invokeInt(arrayOf(device,iid,1,null,out))<0 || out.value==null) return@use HardwareSupport.UNKNOWN
        val client=out.value
        try { Memory(18).use { f ->
            f.clear();f.setShort(0,1);f.setShort(2,2);f.setInt(4,48000);f.setInt(8,192000);f.setShort(12,4);f.setShort(14,16)
            when(method(client,7).invokeInt(arrayOf(client,1,f,null))) {
                0 -> HardwareSupport.YES
                0x88890008.toInt(), 0x8889000e.toInt() -> HardwareSupport.NO
                else -> HardwareSupport.UNKNOWN
            }
        } } finally {release(client)}
    }
}
