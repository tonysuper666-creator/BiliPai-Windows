package com.bilipai.desktop.plugins.js

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.assertTrue

/** Executes the actual product adapters/VM with the linked worker image prepared by the build. */
@Tag("js-worker")
class DesktopJsPluginIntegrationTest {
    @TempDir lateinit var reports: Path
    private fun resources(): Path = Path.of(requireNotNull(System.getProperty("bilipai.js.workerResources")) {
        "Prepare the exclusive worker resources before running js-worker tests"
    }).toAbsolutePath().normalize()
    private fun classpath(): List<String> {
        val root = resources()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(java.nio.file.Files.readString(root.resolve("classpath.json")))
        return json.jsonObject["classpath"]!!.jsonArray.map { root.resolve(it.jsonObject["file"]!!.jsonPrimitive.content).toString() }
    }
    private fun assertReport(file: String, count: Int) {
        val report = Json.parseToJsonElement(reports.resolve(file).toFile().readText()).jsonObject
        assertTrue(report["passed"]!!.jsonPrimitive.boolean && report["caseCount"]!!.jsonPrimitive.int == count)
    }
    @Test fun actualOriginalScriptsAndWindowsHost(): Unit {
        val root = resources()
        runDesktopJsHostFixture(arrayOf(root.resolve("runtime/bin/javaw.exe").toString(), reports.toString(),
            requireNotNull(System.getProperty("bilipai.js.repo")), *classpath().toTypedArray()))
        assertReport("kotlin-host-report.json", 14)
    }
    @Test fun actualInstallationApprovalRecoveryAndShutdown(): Unit {
        val root = resources()
        runDesktopJsRepositoryFixture(arrayOf(root.resolve("runtime/bin/javaw.exe").toString(), reports.toString(), *classpath().toTypedArray()))
        assertReport("repository-report.json", 10)
    }
    @Test fun actualPinnedResourcesLaunchAndTamperRejection(): Unit {
        runDesktopJsResourcesFixture(arrayOf(resources().toString(), reports.toString()))
        assertReport("js-worker-resource-report.json", 4)
    }
}
