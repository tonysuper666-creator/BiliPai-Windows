package com.bilipai.desktop.diagnostics

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener

fun main(args: Array<String>) {
    val rows = mutableListOf<JsonObject>()
    args.drop(1).forEach { name ->
        val type = Class.forName(name)
        val methods = type.declaredMethods.filter { method ->
            method.annotations.any { it.annotationClass.java.name == "org.junit.jupiter.api.Test" }
        }.sortedBy { it.name }
        check(methods.isNotEmpty())
        methods.forEach { method ->
            check(method.parameterCount == 0 && method.returnType == Void.TYPE) { "Test must be executable Unit: ${method.name}" }
        }
    }
    val summary = SummaryGeneratingListener()
    val listener = object : TestExecutionListener {
        override fun executionFinished(identifier: TestIdentifier, result: TestExecutionResult) {
            if (!identifier.isTest) return
            val passed = result.status == TestExecutionResult.Status.SUCCESSFUL
            rows.add(buildJsonObject { put("test", identifier.displayName); put("passed", passed) })
            println("${if (passed) "PASS" else "FAIL"} ${identifier.displayName}")
            result.throwable.ifPresent { it.printStackTrace() }
        }
    }
    val request = LauncherDiscoveryRequestBuilder.request().selectors(args.drop(1).map(::selectClass)).build()
    LauncherFactory.create().execute(request, summary, listener)
    val actual = summary.summary
    check(actual.testsFoundCount > 0 && actual.testsFoundCount == actual.testsSucceededCount && actual.testsFailedCount == 0L)
    val report = buildJsonObject {
        put("passed", true); put("actualAnnotatedMethods", actual.testsSucceededCount); put("mainEdited", false)
        put("junitDiscoveredTests", actual.testsFoundCount); put("junitSkippedTests", actual.testsSkippedCount)
        put("sharedGradleInvoked", false); put("headless", true); put("liveAccountRequests", false)
        put("tests", JsonArray(rows))
    }
    Files.writeString(Path.of(args[0]), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
}
