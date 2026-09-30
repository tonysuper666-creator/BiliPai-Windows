package com.bilipai.desktop.enhancementfixture
import org.junit.platform.launcher.core.*
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import java.io.PrintWriter
import java.nio.file.*
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.engine.TestExecutionResult
import kotlinx.serialization.json.*

fun main(args: Array<String>) {
    val listener = SummaryGeneratingListener()
    val launcher = LauncherFactory.create()
    launcher.registerTestExecutionListeners(listener)
    val completed = mutableListOf<String>()
    launcher.registerTestExecutionListeners(object : TestExecutionListener {
        override fun executionFinished(testIdentifier: TestIdentifier, result: TestExecutionResult) {
            if (testIdentifier.isTest && result.status == TestExecutionResult.Status.SUCCESSFUL) completed += testIdentifier.displayName
        }
    })
    val classes = when (args.singleOrNull()) {
        "ui" -> listOf(UiProof::class.java)
        "runtime" -> listOf(RuntimeProof::class.java)
        else -> listOf(ConfigurationProof::class.java, SessionProof::class.java)
    }
    launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(classes.map(::selectClass)).build())
    listener.summary.printTo(PrintWriter(System.out, true)); listener.summary.printFailuresTo(PrintWriter(System.out, true))
    listener.summary.failures.forEach { it.exception.printStackTrace(System.out) }
    val expected = if (args.singleOrNull() in setOf("ui", "runtime")) 1L else 14L
    check(listener.summary.testsFoundCount == expected)
    check(listener.summary.testsFailedCount == 0L)
    Files.writeString(Path.of(System.getProperty("enhancement.proof.dir")).resolve("${args.singleOrNull() ?: "logic"}-result.json"),
        buildJsonObject {
            put("passed", true); put("tests", expected); put("nativeWindowOpened", false); put("accountHttpRequested", false)
            put("completed", JsonArray(completed.map(::JsonPrimitive)))
        }.toString())
}
