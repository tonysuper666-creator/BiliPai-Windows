package com.bilipai.desktop.hardware

import kotlinx.serialization.json.*
import java.nio.file.*

/** Foreground, account-free verifier. Run on Windows through the authorized test launcher.
 * This is the real product probe/self-test, not a second diagnostic implementation. */
internal object DesktopHardwareVerification {
    @JvmStatic fun main(args:Array<String>) {
        val options=args.associate {it.substringBefore('=') to it.substringAfter('=',"")}
        val output=Path.of(checkNotNull(options["--output"]))
        val pause=options["--pause"]?.let(Path::of)
        fun allowed()=pause==null || !Files.exists(pause)
        if(!allowed()) {Files.writeString(output,"{\"status\":\"deferred_pause\"}");return}
        val snapshot=DesktopHardwareProbe().probe()
        val probeOnly=options["--probe-only"]=="1"
        val state=verifyHardwareSnapshot(snapshot,probeOnly,::allowed) {s,a ->
            DesktopHardwareSelfTest.forLibrary {Path.of(checkNotNull(options["--library"]))}.run(s,a)
        }
        val learning=state.learning
        val measurements=learning?.decodes.orEmpty()
        val samples=buildJsonArray {
            for(codec in VideoCodec.entries) for(height in listOf(1080,2160)) {
                val attempts=measurements.filter {it.codec==codec && it.height==height}
                val status=when {attempts.isEmpty()->"not_attempted";attempts.any {it.passed}->"passed";else->"failed"}
                val reason=if(attempts.isEmpty())learning?.selfTestReason ?: "unavailable" else attempts.joinToString(",") {it.reason}
                add(buildJsonObject {put("codec",codec.name);put("height",height);put("status",status);put("reason",reason)})
                println("sample=${codec.name.lowercase()}-$height status=$status reason=$reason")
            }
        }
        val report=buildJsonObject {
            put("status",if(snapshot.complete)"probed" else "incomplete")
            put("optimization",Json.parseToJsonElement(hardwareDiagnosticJson(state)))
            put("perAdapterDecisions",JsonArray(snapshot.adapters.map {Json.encodeToJsonElement(decideHardwarePolicy(snapshot,learning,it.luid))}))
            put("samples",samples)
        }
        Files.writeString(output,report.toString())
        println("hardware_probe_complete=${snapshot.complete}, tests=${measurements.size}, passed=${measurements.count {it.passed}}")
    }
}

/** Use the retained owner's completion semantics: no candidates or native failures are unavailable,
 * never successful playback. A memory-only cache keeps verification out of the user's profile. */
internal fun verifyHardwareSnapshot(snapshot:HardwareSnapshot,probeOnly:Boolean,allowed:()->Boolean,
    selfTest:(HardwareSnapshot,()->Boolean)->List<DecodeMeasurement>):HardwareOptimizationState {
    if(probeOnly) {
        val learning=HardwareLearning(snapshot.fingerprint(),selfTestReason="probe_only")
        return HardwareOptimizationState(snapshot,learning,decideHardwarePolicy(snapshot,learning))
    }
    return DesktopHardwareOptimizer({snapshot},selfTest,DesktopHardwareCache(null),allowed).use {
        it.refreshOnce()
        it.state.value
    }
}
