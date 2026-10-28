package com.bilipai.desktop.hardware

import com.sun.jna.*
import java.nio.file.Files
import java.nio.file.Path
import com.bilipai.desktop.nvidia.luidMatches

/** Muted hardware decode in an unrealized-to-screen AWT host. Never shows it or requests SR/HDR.
 * A null VO with d3d11va-copy creates the default adapter in pinned mpv video/d3d.c;
 * a hidden D3D11 VO is needed to honor d3d11-adapter and observe the actual LUID. */
internal class DesktopHardwareSelfTest(private val libraryPath: () -> Path) {
    internal interface Api : Library {
        fun mpv_create(): Pointer?
        fun mpv_set_option_string(handle: Pointer, name: String, value: String): Int
        fun mpv_initialize(handle: Pointer): Int
        fun mpv_command(handle: Pointer, args: StringArray): Int
        fun mpv_get_property_string(handle: Pointer, name: String): Pointer?
        fun mpv_get_property(handle: Pointer, name: String, format: Int, data: Pointer): Int
        fun mpv_free(data: Pointer)
        fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer
        fun mpv_request_log_messages(handle: Pointer, level: String): Int
        fun mpv_terminate_destroy(handle: Pointer)
    }
    fun run(snapshot: HardwareSnapshot, allowed: () -> Boolean): List<DecodeMeasurement> {
        val deadline=System.nanoTime()+10_000_000_000L
        val adapters=snapshot.adapters.filter { it.physical && it.vendor != HardwareVendor.SOFTWARE }
        if(adapters.none {a->a.decoders.any {it.bitDepth==8 && it.support==HardwareSupport.YES}})return emptyList()
        val api=Native.load(libraryPath().toAbsolutePath().toString(),Api::class.java,mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        val result=mutableListOf<DecodeMeasurement>()
        // At most one sample per codec/adapter in the shared ten second budget.
        for (adapter in adapters) for(codec in VideoCodec.entries) {
          for(capability in adapter.decoders.filter {it.codec==codec && it.bitDepth==8 && it.support==HardwareSupport.YES}.sortedByDescending {it.height}) {
            if(!allowed() || Thread.currentThread().isInterrupted || System.nanoTime()>=deadline) return result
            // A virtual twin may carry the physical GPU's exact description. The name is
            // only a request; the logged LUID below must identify this exact physical adapter.
            val height=capability.height
            val fileName="${codec.name.lowercase()}-${height}.mp4"
            val bytes=javaClass.getResourceAsStream("/hardware-selftest/$fileName")?.use {it.readBytes()} ?: continue
            val expected=javaClass.getResourceAsStream("/hardware-selftest/SHA256SUMS")?.bufferedReader()?.use {it.readLines()}
                ?.firstOrNull {it.endsWith("  $fileName")}?.substringBefore("  ")
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            check(expected!=null && digest==expected) {"sample_integrity_failed"}
            val temporary=Files.createTempFile("bilipai-hw-", ".mp4")
            try {
                Files.write(temporary,bytes)
                val measurement=decode(api,adapter,codec,capability,temporary,minOf(deadline,System.nanoTime()+2_500_000_000L),allowed)
                result+=measurement.copy(adapterIdentity=adapter.stableId)
                if(measurement.passed)break
            } finally {Files.deleteIfExists(temporary)}
          }
        }
        return result
    }
    private fun decode(api:Api, adapter:HardwareAdapter, codec:VideoCodec, cap:DecodeCapability,
        sample:Path, deadline:Long, allowed:()->Boolean):DecodeMeasurement {
        val start=System.nanoTime();var frames=0L;var drops=0L;var decoder="";var ended=false;var failed=false;var observedLuid:String?=null
        var host:java.awt.Frame?=null
        javax.swing.SwingUtilities.invokeAndWait {host=java.awt.Frame().apply {isUndecorated=true;setSize(16,16);addNotify()}}
        val window=checkNotNull(host)
        val handle=api.mpv_create() ?: run {
            javax.swing.SwingUtilities.invokeAndWait {window.dispose()}
            return DecodeMeasurement(adapter.luid,codec,cap.width,cap.height,"",0,0,0,false,"create_failed")
        }
        try {
            val options=mapOf("config" to "no","load-scripts" to "no","terminal" to "no","msg-level" to "all=no",
                "audio" to "no","vo" to "gpu","gpu-api" to "d3d11","gpu-context" to "d3d11",
                "wid" to Pointer.nativeValue(Native.getWindowPointer(window)).toString(),
                "hwdec" to "d3d11va","hwdec-codecs" to "all","d3d11-flip" to "no",
                "vd-lavc-software-fallback" to "no","d3d11-adapter" to adapter.name,"keep-open" to "yes",
                "untimed" to "yes","network-timeout" to "1")
            for((key,value) in options) if(api.mpv_set_option_string(handle,key,value)<0) {
                failed=true;return DecodeMeasurement(adapter.luid,codec,cap.width,cap.height,"",0,0,0,false,"option_failed_$key")
            }
            check(api.mpv_request_log_messages(handle,"v")>=0)
            check(api.mpv_initialize(handle)>=0)
            check(api.mpv_command(handle,StringArray(arrayOf("loadfile",sample.toAbsolutePath().toString()),"UTF-8"))>=0)
            while(System.nanoTime()<deadline && allowed() && !Thread.currentThread().isInterrupted) {
                val event=api.mpv_wait_event(handle,0.01)
                if(event.getInt(0)==2) {
                    val data=event.getPointer(16)
                    val line=data?.getPointer(16)?.getString(0,"UTF-8")?.trim().orEmpty()
                    Regex("LUID: ([0-9a-fA-F]{16})").matchEntire(line)?.let {observedLuid=it.groupValues[1]}
                }
                if(event.getInt(0)==7) { val data=event.getPointer(16);failed=data==null || data.getInt(0)!=0;ended=true }
                text(api,handle,"hwdec-current")?.takeIf {it!="no"}?.let {decoder=it}
                frames=maxOf(frames,number(api,handle,"estimated-frame-number"))
                drops=maxOf(drops,number(api,handle,"decoder-frame-drop-count"))
                if(ended || text(api,handle,"eof-reached")=="yes") {ended=true;break}
            }
            val elapsed=((System.nanoTime()-start)/1_000_000).coerceAtLeast(1)
            // The generated sample is 42 frames at 60 fps. Include initialization in the speed budget.
            val adapterVerified=observedLuid?.let {luidMatches(adapter.luid,it)}==true
            val passed=ended && !failed && decoder=="d3d11va" && adapterVerified && frames>=40 && drops==0L && elapsed<=700
            return DecodeMeasurement(adapter.luid,codec,cap.width,cap.height,decoder,frames,drops,elapsed,passed,
                when { !allowed()->"deferred";!ended->"timeout";failed->"decode_failed";decoder!="d3d11va"->"not_hardware";!adapterVerified->"adapter_unverified";frames<40->"incomplete_frames";elapsed>700->"too_slow";drops>0->"dropped_frames";else->"passed" })
        } finally {try {api.mpv_terminate_destroy(handle)} finally {javax.swing.SwingUtilities.invokeAndWait {window.dispose()}}}
    }
    private fun text(api:Api, handle:Pointer, name:String):String? {
        val p=api.mpv_get_property_string(handle,name) ?: return null
        return try {p.getString(0,"UTF-8")} finally {api.mpv_free(p)}
    }
    private fun number(api:Api, handle:Pointer, name:String):Long=Memory(8).use { m ->
        m.clear();if(api.mpv_get_property(handle,name,4,m)>=0) m.getLong(0).coerceAtLeast(0) else 0L
    }
}
