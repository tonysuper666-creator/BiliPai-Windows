package com.bilipai.desktop.ui
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.nio.file.*
fun main(args:Array<String>){
    val listener=SummaryGeneratingListener();val launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener)
    launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(DesktopDynamicTimelineSettingsTest::class.java)).build())
    listener.summary.printTo(java.io.PrintWriter(System.out,true));listener.summary.printFailuresTo(java.io.PrintWriter(System.out,true))
    Files.writeString(Path.of(args[0]),"""{"tests":${listener.summary.testsFoundCount},"passed":${listener.summary.testsSucceededCount},"failed":${listener.summary.testsFailedCount}}""")
    check(listener.summary.testsFoundCount==13L&&listener.summary.testsSucceededCount==13L&&listener.summary.testsFailedCount==0L)
}
