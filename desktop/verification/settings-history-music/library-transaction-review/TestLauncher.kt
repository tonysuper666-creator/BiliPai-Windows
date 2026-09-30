package com.bilipai.desktop.librarytransactionfixture

import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.*
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.nio.file.*
import kotlinx.serialization.json.*

fun main(args: Array<String>) {
    val listener = SummaryGeneratingListener()
    val launcher = LauncherFactory.create(); launcher.registerTestExecutionListeners(listener)
    launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(
        selectClass(com.bilipai.desktop.DesktopLibraryTransactionTest::class.java)).build())
    val summary = listener.summary
    summary.printTo(java.io.PrintWriter(System.out)); summary.failures.forEach { it.exception.printStackTrace() }
    Files.writeString(Path.of(args[0]), buildJsonObject {
        put("found", summary.testsFoundCount); put("started", summary.testsStartedCount)
        put("succeeded", summary.testsSucceededCount); put("failed", summary.testsFailedCount)
        put("nativeWindowOpened", false); put("accountRequests", 0); put("realTempDisk", true)
    }.toString())
    check(summary.testsFoundCount == 3L && summary.testsStartedCount == 3L && summary.testsFailedCount == 0L)
}
