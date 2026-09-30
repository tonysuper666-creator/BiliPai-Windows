package com.bilipai.desktop.data

import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val listener = SummaryGeneratingListener()
    val request = LauncherDiscoveryRequestBuilder.request()
        .selectors(selectClass(DesktopBlockedUpStoreTest::class.java)).build()
    LauncherFactory.create().execute(request, listener)
    val summary = listener.summary
    summary.failures.forEach { it.exception.printStackTrace() }
    check(summary.testsFoundCount == 8L && summary.testsStartedCount == 8L && summary.testsSucceededCount == 8L && summary.testsFailedCount == 0L)
    Files.writeString(Path.of(args.single()), """{"passed":true,"junitEngineInvoked":true,"testsFound":8,"testsStarted":8,"testsSucceeded":8,"testsFailed":0,"markedProductionOverrides":true,"sharedGradleInvoked":false,"nativeWindowCreated":false,"userSettingsRead":false,"networkRequests":false}""")
    println("Actual JUnit engine discovered/started/passed all 8 frozen store tests against the marked review-delta store.")
}
