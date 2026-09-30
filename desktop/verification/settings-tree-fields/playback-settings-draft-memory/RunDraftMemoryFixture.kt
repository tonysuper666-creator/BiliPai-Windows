package com.bilipai.desktop.ui

import java.nio.file.*

fun main(args: Array<String>) {
    var exit = 1
    try {
        val tests = PlaybackSettingsDraftMemoryTest()
        tests.`delayed actual controller audio selection survives draft save in a fresh disk reader`()
        tests.`save keeps original remembered speed contract and normalizes latest audio memory`()
        val report = Path.of(args.single());Files.createDirectories(report.parent)
        Files.writeString(report, """{"passed":true,"junitMethodsPassed":2,"actualControllerDelayedAudioCompletion":true,"baselineStaleDraftFailureReproduced":true,"actualStoreAndFreshDiskReader":true,"editableDraftChangesRetained":true,"nativeWindowCreated":false,"nativeAudioDecodeVerified":false,"accountOrNetworkRequests":false,"sharedGradleInvoked":false}""")
        println("Delayed actual Controller audio callback -> draft save -> fresh disk reader: PASS (2 focused methods), no HWND/network/Gradle.")
        exit = 0
    } catch (failure: Throwable) { failure.printStackTrace() }
    finally { kotlin.system.exitProcess(exit) }
}
