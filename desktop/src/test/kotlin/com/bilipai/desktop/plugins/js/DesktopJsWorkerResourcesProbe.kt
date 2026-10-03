package com.bilipai.desktop.plugins.js

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Validates the actual prepared resource image and launches its exclusive native worker. */
internal fun runDesktopJsResourcesFixture(args: Array<String>) {
    val root = Path.of(args[0]).toAbsolutePath().normalize()
    val cases = mutableListOf<String>()
    fun rejects(block: () -> Unit) { check(runCatching(block).isFailure) }
    val trusted = DesktopJsWorkerResources(root)
    trusted.createProcess(root).use { process ->
        process.create().use { execution ->
            val request = buildJsonObject { put("callId", "resource-fixture"); put("executionScript", "BiliPaiNative.resolve('resource-fixture',JSON.stringify({ok:true}));") }.toString()
            val result = execution.execute(request, { DesktopJsWorkerProcess.FrameResult.complete(it) }, 15_000)
            check(Json.parseToJsonElement(result).jsonObject["type"]!!.jsonPrimitive.content == "resolved")
            check(execution.awaitStopped(java.time.Duration.ofSeconds(3)) && !execution.isAlive)
        }
    }
    cases += "trustedCatalogActualLinkedImageWorkerLaunchAndJoin"
    // Tamper checks own a complete copy; never alter the build's shared worker image.
    // The real configured image was launched and joined above. This second image
    // is verified by the same product gate, without launching its native EXE.
    val reports = Path.of(args[1]).toAbsolutePath().normalize().toRealPath()
    val tamperRoot = reports.resolve("js-worker-tamper-resources").normalize()
    check(tamperRoot.startsWith(reports) && !tamperRoot.startsWith(root) && !root.startsWith(tamperRoot))
    check(!Files.exists(tamperRoot))
    Files.createDirectory(tamperRoot)
    Files.walk(root).use { files ->
        files.forEach { source ->
            check(!Files.isSymbolicLink(source) && source.toRealPath().startsWith(root.toRealPath()))
            val target = tamperRoot.resolve(root.relativize(source)).normalize()
            check(target.startsWith(tamperRoot))
            if (Files.isDirectory(source)) Files.createDirectories(target)
            else {
                check(Files.isRegularFile(source))
                Files.createDirectories(target.parent)
                Files.copy(source, target)
            }
        }
    }
    DesktopJsWorkerResources(tamperRoot).createProcess(tamperRoot).use { }
    val catalog = tamperRoot.resolve("classpath.json")
    val bytes = Files.readAllBytes(catalog)
    try {
        Files.write(catalog, bytes + byteArrayOf(10))
        rejects { DesktopJsWorkerResources(tamperRoot).createProcess(tamperRoot) }
        cases += "changedCatalogRejectedBeforeLaunch"
    } finally { Files.write(catalog, bytes) }
    val java = tamperRoot.resolve("runtime/bin/javaw.exe")
    val command = Files.readAllBytes(java)
    try {
        Files.write(java, command.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        rejects { DesktopJsWorkerResources(tamperRoot).createProcess(tamperRoot) }
        cases += "changedNativeLauncherRejectedBeforeLaunch"
    } finally { Files.write(java, command) }
    val extra = tamperRoot.resolve("unreviewed.jar")
    try {
        Files.write(extra, byteArrayOf(1, 2, 3))
        rejects { DesktopJsWorkerResources(tamperRoot).createProcess(tamperRoot) }
        cases += "extraClasspathResourceRejectedBeforeLaunch"
    } finally { Files.deleteIfExists(extra) }
    val report = buildJsonObject { put("passed", cases.size == 4); put("caseCount", cases.size); put("cases", JsonArray(cases.map(::JsonPrimitive))) }
    Path.of(args[1]).resolve("js-worker-resource-report.json").toFile().writeText(report.toString())
    println("Actual packaged JS worker resources PASS: ${cases.size}")
}
