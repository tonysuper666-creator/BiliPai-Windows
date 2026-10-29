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
        val measurements=if(probeOnly)emptyList() else runCatching {DesktopHardwareSelfTest.forLibrary {Path.of(checkNotNull(options["--library"]))}.run(snapshot,::allowed)}.getOrElse {emptyList()}
        val learning=HardwareLearning(snapshot.fingerprint(),measurements,selfTestComplete=!probeOnly && allowed(),
            selfTestReason=if(probeOnly)"probe_only" else if(allowed())"completed" else "deferred")
        val state=HardwareOptimizationState(snapshot,learning,decideHardwarePolicy(snapshot,learning))
        val report=buildJsonObject {
            put("status",if(snapshot.complete)"probed" else "incomplete")
            put("optimization",Json.parseToJsonElement(hardwareDiagnosticJson(state)))
            put("perAdapterDecisions",JsonArray(snapshot.adapters.map {Json.encodeToJsonElement(decideHardwarePolicy(snapshot,learning,it.luid))}))
        }
        Files.writeString(output,report.toString())
        println("hardware_probe_complete=${snapshot.complete}, tests=${measurements.size}, passed=${measurements.count {it.passed}}")
    }
}
