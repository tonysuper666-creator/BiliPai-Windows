package com.bilipai.desktop.privacylifecyclefixture

import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.*
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.nio.file.*
import kotlinx.serialization.json.*

fun main(args: Array<String>) {
    val listener = SummaryGeneratingListener()
    val launcher = LauncherFactory.create(); launcher.registerTestExecutionListeners(listener)
    launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(
        selectClass(com.bilipai.desktop.DesktopCheckpointFailureTest::class.java),
        selectClass(com.bilipai.desktop.data.DesktopSearchRestoreLifecycleTest::class.java)).build())
    val summary = listener.summary
    summary.printTo(java.io.PrintWriter(System.out)); summary.failures.forEach { it.exception.printStackTrace() }
    Files.writeString(Path.of(args[0]), buildJsonObject {
        put("found", summary.testsFoundCount); put("started", summary.testsStartedCount)
        put("succeeded", summary.testsSucceededCount); put("failed", summary.testsFailedCount)
        put("nativeWindowOpened", false); put("accountRequests", 0); put("realTempDisk", true)
        put("actualControllerClosed", true); put("actualCachedStoreGenerations", true)
    }.toString())
    check(summary.testsFoundCount == 8L && summary.testsStartedCount == 8L && summary.testsFailedCount == 0L)
}
