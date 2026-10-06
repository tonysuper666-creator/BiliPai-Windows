package com.bilipai.desktop.update

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.jar.JarInputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opt-in Windows UI/process test. It produces updater evidence, never a complete release gate. */
@Tag("packaged-updater")
class DesktopUpdaterIntegrationTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun packagedUpdaterEndToEnd() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val packageArgument = System.getProperty("bilipai.updateTestPackage")?.takeIf { it.isNotBlank() }
        assumeTrue(packageArgument != null, "Set bilipai.updateTestPackage to opt into real packaged EXE testing")
        val packagePath = UpdateStorage.existingPathWithoutLinks(Path.of(requireNotNull(packageArgument)))
        val caseRoot = Files.createTempDirectory("bp-u-").toRealPath(NOFOLLOW_LINKS)
        Files.writeString(caseRoot.resolve(".bilipai-updater-e2e"), "BiliPai updater integration fixture")
        val childData = Files.createDirectory(caseRoot.resolve("LocalAppData"))
        val updateRoot = UpdateStorage.verifiedRoot(childData.resolve("BiliPai/updates"))
        val activeFile = updateRoot.resolve("active-install.json")
        val report = System.getProperty("bilipai.updateTestReport")?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).toAbsolutePath().normalize().resolve("updater-smoke.json") } ?: caseRoot.resolve("updater-smoke.json")
        val launched = Collections.synchronizedList(mutableListOf<Process>())
        val observed = mutableMapOf<Long, ObservedChild>()
        val evidence = linkedMapOf<String, JsonElement>(
            "passed" to JsonPrimitive(false),
            "evidenceType" to JsonPrimitive("isolated-packaged-updater"),
            "caseRoot" to JsonPrimitive(caseRoot.toString()),
            "isolatedLocalAppData" to JsonPrimitive(childData.toString()),
            "updateRoot" to JsonPrimitive(updateRoot.toString()),
            "portableZip" to JsonPrimitive(packagePath.toString()),
            "realShortcutTested" to JsonPrimitive(false),
            "childBackgroundNetworkAllowed" to JsonPrimitive(true),
        )
        var failure: Throwable? = null
        try {
            require(Files.isRegularFile(packagePath, NOFOLLOW_LINKS))
            val config = readPackageConfig(packagePath)
            val previousPath = System.getProperty("bilipai.updatePreviousPackage")?.takeIf { it.isNotBlank() }
                ?.let { UpdateStorage.existingPathWithoutLinks(Path.of(it)) }
            val previousConfig = previousPath?.let(::readPackageConfig)
            val baseline = previousConfig?.version ?: precedingRevision(config.version)
            require(requireNotNull(DesktopVersion.parse(baseline)) < requireNotNull(DesktopVersion.parse(config.version)))
            require(DesktopUpdater.validRepository(config.repository))
            evidence["windowsVersion"] = JsonPrimitive(config.version)
            evidence["portableZipSha256"] = JsonPrimitive(sha256(packagePath))
            evidence["baselineVersion"] = JsonPrimitive(baseline)
            evidence["repository"] = JsonPrimitive(config.repository)

            LoopbackFixture(config.repository).use { fixture ->
                try {
                    fun updater() = DesktopUpdater.forIntegrationTest(baseline, config.repository, updateRoot,
                        fixture.client, childData, { launched.add(it) }, config.executable)

                    fixture.publish("healthy", config.version, packagePath)
                    val healthyUpdater = updater()
                    val healthyUpdate = assertIs<UpdateState.Available>(healthyUpdater.check()).update
                    val healthy = assertNotNull(healthyUpdater.prepareUpdate(healthyUpdate))
                    assertIs<UpdateState.Prepared>(healthyUpdater.state.value)
                    assertTrue(Files.isRegularFile(healthy.executable))
                    assertTrue(launched.isEmpty(), "Preparation must not start the new EXE")
                    assertFalse(Files.exists(activeFile), "Preparation must not register an installation")
                    assertTrue(healthyUpdater.activatePreparedUpdate(healthy))
                    val goodProcess = launched.last()
                    fun assertHealthyInstanceAlive(stage: String) {
                        assertTrue(goodProcess.isAlive, "Healthy EXE exited $stage: pid=${goodProcess.pid()}, " +
                            "exitCode=${runCatching { goodProcess.exitValue() }.getOrNull()}")
                    }
                    assertHealthyInstanceAlive("after activation")
                    val originalActive = Files.readAllBytes(activeFile)
                    val originalRecord = json.parseToJsonElement(originalActive.toString(Charsets.UTF_8)).jsonObject
                    assertEquals(healthyUpdate.version, originalRecord.getValue("version").jsonPrimitive.content)
                    assertEquals(healthy.executable.toString(), originalRecord.getValue("executablePath").jsonPrimitive.content)
                    evidence["successActivation"] = buildJsonObject {
                        put("passed", true)
                        put("processId", goodProcess.pid())
                        put("executable", healthy.executable.toString())
                        put("health", healthEvidence(healthy.stagingDirectory))
                    }

                    fixture.publish("wrong-hash", config.version, packagePath, "00".repeat(32))
                    val hashUpdater = updater()
                    val beforeHashStages = ownedStages(updateRoot, config.repository)
                    val beforeHashProcesses = launched.size
                    val hashUpdate = assertIs<UpdateState.Available>(hashUpdater.check()).update
                    assertNull(hashUpdater.prepareUpdate(hashUpdate))
                    assertIs<UpdateState.Failed>(hashUpdater.state.value)
                    assertEquals(beforeHashProcesses, launched.size)
                    assertContentEquals(originalActive, Files.readAllBytes(activeFile))
                    assertEquals(beforeHashStages, ownedStages(updateRoot, config.repository))
                    assertHealthyInstanceAlive("after rejecting the wrong hash")
                    evidence["wrongHashRejected"] = JsonPrimitive(true)

                    // This failure fixture deliberately advertises a different version from its real resource.
                    fixture.publish("version-mismatch", succeedingRevision(config.version), packagePath)
                    val versionUpdater = updater()
                    val versionUpdate = assertIs<UpdateState.Available>(versionUpdater.check()).update
                    val mismatched = assertNotNull(versionUpdater.prepareUpdate(versionUpdate))
                    assertFalse(versionUpdater.activatePreparedUpdate(mismatched))
                    assertFalse(launched.last().isAlive)
                    assertContentEquals(originalActive, Files.readAllBytes(activeFile))
                    evidence["versionMismatchRejected"] = buildJsonObject {
                        put("passed", true)
                        put("fixtureAdvertisedVersion", versionUpdate.version)
                        put("actualPackageVersion", config.version)
                        put("preparedSuccessfully", true)
                        put("health", healthEvidence(mismatched.stagingDirectory))
                    }
                    fixture.publish("after-version-failure", config.version, packagePath)
                    val recoveredVersion = assertIs<UpdateState.Available>(versionUpdater.check()).update
                    assertTrue(recoveredVersion.assetName.contains("after-version-failure"))

                    // Modify only a fixture copy; its ZIP hash is recalculated and native startup must fail.
                    val damagedZip = caseRoot.resolve("damaged-native-fixture.zip")
                    val damagedEntries = damageNativeLibrary(packagePath, damagedZip)
                    fixture.publish("damaged-native", config.version, damagedZip)
                    val damagedUpdater = updater()
                    val damagedUpdate = assertIs<UpdateState.Available>(damagedUpdater.check()).update
                    val damaged = assertNotNull(damagedUpdater.prepareUpdate(damagedUpdate))
                    val beforeDamageProcesses = launched.size
                    assertFalse(damagedUpdater.activatePreparedUpdate(damaged))
                    assertTrue(launched.size > beforeDamageProcesses, "The damaged fixture must reach actual EXE startup")
                    assertFalse(launched.last().isAlive)
                    assertContentEquals(originalActive, Files.readAllBytes(activeFile))
                    assertHealthyInstanceAlive("after rejecting damaged native startup")
                    evidence["damagedStartupRejected"] = buildJsonObject {
                        put("passed", true)
                        put("fixtureZipSha256", sha256(damagedZip))
                        put("modifiedEntries", buildJsonArray { damagedEntries.forEach { add(JsonPrimitive(it)) } })
                        put("originalActiveBytesUnchanged", true)
                        put("health", healthEvidence(damaged.stagingDirectory))
                    }

                    // Explicit fixture setup models a damaged registered active and its healthy previous install.
                    // Both carry the actual version: fallback must also update a changed path at the same version.
                    Files.writeString(activeFile, json.encodeToString(JsonObject.serializer(), buildJsonObject {
                        put("repository", config.repository)
                        put("version", healthyUpdate.version)
                        put("executablePath", damaged.executable.toString())
                        put("previousVersion", healthyUpdate.version)
                        put("previousExecutablePath", healthy.executable.toString())
                    }))
                    val beforeFallbackProcesses = launched.size
                    assertTrue(updater().launchRegisteredInstall(emptyArray()))
                    val fallbackProcesses = launched.drop(beforeFallbackProcesses)
                    assertEquals(2, fallbackProcesses.size)
                    assertFalse(fallbackProcesses.first().isAlive)
                    assertTrue(fallbackProcesses.last().isAlive)
                    val fallbackRecord = json.parseToJsonElement(Files.readString(activeFile)).jsonObject
                    assertEquals(healthy.executable.toString(), fallbackRecord.getValue("executablePath").jsonPrimitive.content)
                    assertEquals(healthyUpdate.version, fallbackRecord.getValue("version").jsonPrimitive.content)
                    evidence["registeredFallback"] = buildJsonObject {
                        put("passed", true)
                        put("registrySetupWasFixture", true)
                        put("failedProcessId", fallbackProcesses.first().pid())
                        put("healthyProcessId", fallbackProcesses.last().pid())
                        put("activeExecutable", healthy.executable.toString())
                    }
                    fixture.publish("after-damaged-failure", config.version, packagePath)
                    val recoveredDamage = assertIs<UpdateState.Available>(damagedUpdater.check()).update
                    assertTrue(recoveredDamage.assetName.contains("after-damaged-failure"))
                    evidence["failedPreparedCandidateRevoked"] = JsonPrimitive(true)

                    // Root composition must fail even though the EXE window and native player can start.
                    // The former window-only ACK incorrectly accepted this authenticated fixture ZIP.
                    val damagedRootZip = caseRoot.resolve("damaged-root-fixture.zip")
                    val damagedRootEntries = damageRootWorkerCatalog(packagePath, damagedRootZip)
                    fixture.publish("damaged-root", config.version, damagedRootZip)
                    val damagedRootUpdater = updater()
                    val damagedRootUpdate = assertIs<UpdateState.Available>(damagedRootUpdater.check()).update
                    val damagedRoot = assertNotNull(damagedRootUpdater.prepareUpdate(damagedRootUpdate))
                    val beforeRootDamageProcesses = launched.size
                    assertFalse(damagedRootUpdater.activatePreparedUpdate(damagedRoot))
                    assertTrue(launched.size > beforeRootDamageProcesses, "The broken Root fixture must reach actual EXE startup")
                    assertFalse(launched.last().isAlive)
                    assertContentEquals(originalActive, Files.readAllBytes(activeFile))
                    assertHealthyInstanceAlive("after rejecting damaged Root startup")
                    val failedRootLaunches = launchDirectories(damagedRoot.stagingDirectory)
                    assertTrue(failedRootLaunches.isNotEmpty())
                    failedRootLaunches.forEach { directory ->
                        assertFalse(Files.exists(directory.resolve("startup-health.txt"), NOFOLLOW_LINKS),
                            "A failed Root must never acknowledge startup")
                        assertFalse(Files.exists(directory.resolve("startup-version.txt"), NOFOLLOW_LINKS),
                            "Version publication must also wait for a real Root frame")
                    }
                    evidence["damagedRootStartupRejected"] = buildJsonObject {
                        put("passed", true)
                        put("fixtureZipSha256", sha256(damagedRootZip))
                        put("modifiedEntries", buildJsonArray { damagedRootEntries.forEach { add(JsonPrimitive(it)) } })
                        put("originalActiveBytesUnchanged", true)
                        put("rootHealthAcknowledged", false)
                        put("health", healthEvidence(damagedRoot.stagingDirectory))
                    }

                    if (previousPath != null && previousConfig != null) {
                        require(previousConfig.repository == config.repository)
                        launched.toList().asReversed().forEach { StartupHealth.terminate(it) }
                        val previousDirectory = Files.createDirectory(caseRoot.resolve("previous-launcher"))
                        val previousExecutable = SafeUpdateZip.extract(previousPath, previousDirectory, previousConfig.executable)
                        val beforeLaunchDirectories = launchDirectories(healthy.stagingDirectory)
                        val original = ProcessBuilder(previousExecutable.toString()).directory(previousExecutable.parent.toFile())
                            .redirectOutput(caseRoot.resolve("previous-output.log").toFile())
                            .redirectError(caseRoot.resolve("previous-error.log").toFile())
                        isolateChild(original, childData)
                        val originalProcess = original.start()
                        launched.add(originalProcess)
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                        while (originalProcess.isAlive && System.nanoTime() < deadline) {
                            observeOwnedDescendants(originalProcess, caseRoot, observed)
                            delay(25)
                        }
                        assertFalse(originalProcess.isAlive, "Actual previous EXE did not delegate and exit")
                        assertEquals(0, originalProcess.exitValue())
                        val forwarded = observed.values.filter { it.executable == healthy.executable && it.handle.isAlive }
                        assertTrue(forwarded.isNotEmpty(), "No live new EXE was observed beneath the actual previous EXE")
                        assertTrue((launchDirectories(healthy.stagingDirectory) - beforeLaunchDirectories).isNotEmpty())
                        assertEquals(healthy.executable.toString(),
                            json.parseToJsonElement(Files.readString(activeFile)).jsonObject.getValue("executablePath").jsonPrimitive.content)
                        evidence["actualPreviousExeForwarding"] = buildJsonObject {
                            put("tested", true)
                            put("passed", true)
                            put("previousVersion", previousConfig.version)
                            put("previousZipSha256", sha256(previousPath))
                            put("previousExecutable", previousExecutable.toString())
                            put("originalProcessId", originalProcess.pid())
                            put("newProcessIds", buildJsonArray { forwarded.forEach { add(JsonPrimitive(it.handle.pid())) } })
                            put("shortcutTested", false)
                        }
                    } else {
                        evidence["actualPreviousExeForwarding"] = buildJsonObject {
                            put("tested", false)
                            put("reason", "No actual previous package supplied; only the shared registry forwarding function was tested")
                        }
                    }
                } finally { evidence["fixtureHttp"] = fixture.evidence() }
            }
            evidence["passed"] = JsonPrimitive(true)
        } catch (error: Throwable) {
            failure = error
            evidence["error"] = JsonPrimitive("${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        } finally {
            evidence["directProcessesBeforeCleanup"] = buildJsonArray {
                launched.toList().forEach { process -> add(buildJsonObject {
                    put("processId", process.pid())
                    put("alive", process.isAlive)
                    runCatching { process.exitValue() }.getOrNull()?.let { put("exitCode", it) }
                }) }
            }
            withContext(NonCancellable) {
                launched.toList().asReversed().forEach { process ->
                    runCatching { StartupHealth.terminate(process) }.onFailure { if (failure == null) failure = it }
                }
                observed.values.forEach { child -> stopObservedChild(child) }
            }
            val stopped = launched.none { it.isAlive } && observed.values.none { stillSameLiveChild(it) }
            evidence["trackedProcessesStopped"] = JsonPrimitive(stopped)
            evidence["directProcessIds"] = buildJsonArray { launched.forEach { add(JsonPrimitive(it.pid())) } }
            if (!stopped || failure != null) evidence["passed"] = JsonPrimitive(false)
            report.parent?.let { Files.createDirectories(it) }
            Files.writeString(report, json.encodeToString(JsonObject.serializer(), JsonObject(evidence)))
            if (!stopped && failure == null) failure = AssertionError("A tracked fixture process survived cleanup")
        }
        failure?.let { throw it }
        Unit
    }

    private data class PackageConfig(val version: String, val repository: String, val executable: String)
    private data class ObservedChild(val handle: ProcessHandle, val started: Instant, val executable: Path)
    private data class FixtureRoute(val path: Path?, val text: ByteArray?, val size: Long,
        val requests: AtomicLong = AtomicLong(), val bytes: AtomicLong = AtomicLong())

    private fun readPackageConfig(packagePath: Path): PackageConfig {
        val configurations = mutableSetOf<PackageConfig>()
        ZipFile(packagePath.toFile()).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".jar") }.forEach { entry ->
                JarInputStream(zip.getInputStream(entry)).use { jar ->
                    var resource = jar.nextJarEntry
                    while (resource != null) {
                        if (resource.name == "windows-update.json") {
                            val bytes = jar.readNBytes(64 * 1024 + 1)
                            require(bytes.size <= 64 * 1024)
                            val objectValue = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                            configurations.add(PackageConfig(objectValue.getValue("version").jsonPrimitive.content,
                                objectValue.getValue("windowsReleaseRepository").jsonPrimitive.content,
                                objectValue.getValue("executable").jsonPrimitive.content))
                        }
                        resource = jar.nextJarEntry
                    }
                }
            }
        }
        return configurations.single().also { require(DesktopVersion.parse(it.version) != null) }
    }

    private fun precedingRevision(version: String): String = changeRevision(version, -1)
    private fun succeedingRevision(version: String): String = changeRevision(version, 1)
    private fun changeRevision(version: String, change: Int): String {
        require(Regex("^\\d+(?:\\.\\d+)+$").matches(version)) { "Fixture requires a numeric Windows resource version" }
        val revision = version.substringAfterLast('.').toLong() + change
        require(revision >= 0)
        return version.substringBeforeLast('.') + "." + revision
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun damageNativeLibrary(source: Path, destination: Path): List<String> {
        val changed = mutableListOf<String>()
        ZipFile(source.toFile()).use { zip ->
            ZipOutputStream(Files.newOutputStream(destination)).use { output ->
                output.setLevel(Deflater.BEST_SPEED)
                zip.entries().asSequence().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) {
                        if (entry.name.substringAfterLast('/').equals("libmpv-2.dll", ignoreCase = true)) {
                            changed.add(entry.name)
                            output.write("Intentionally damaged updater integration native fixture".toByteArray())
                        } else zip.getInputStream(entry).use { it.copyTo(output) }
                    }
                    output.closeEntry()
                }
            }
        }
        require(changed.isNotEmpty()) { "No bundled native player DLL was found to damage" }
        return changed
    }

    private fun damageRootWorkerCatalog(source: Path, destination: Path): List<String> {
        val changed = mutableListOf<String>()
        ZipFile(source.toFile()).use { zip ->
            ZipOutputStream(Files.newOutputStream(destination)).use { output ->
                output.setLevel(Deflater.BEST_SPEED)
                zip.entries().asSequence().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) {
                        if (entry.name.endsWith("/js-engine/classpath.json")) {
                            changed.add(entry.name)
                            output.write("{}".toByteArray(Charsets.UTF_8))
                        } else zip.getInputStream(entry).use { it.copyTo(output) }
                    }
                    output.closeEntry()
                }
            }
        }
        require(changed.size == 1) { "Expected exactly one bundled Root JS worker catalog to damage" }
        return changed
    }

    private fun ownedStages(root: Path, repository: String): Set<Path> = Files.list(root).use { stream ->
        stream.map { UpdateStorage.ownedStage(root, it, repository) }.filter { it != null }.toList().filterNotNull().toSet()
    }

    private fun launchDirectories(stage: Path): Set<Path> = Files.list(stage).use { stream ->
        stream.filter { Files.isDirectory(it, NOFOLLOW_LINKS) && it.fileName.toString().startsWith("launch-") }.toList().toSet()
    }

    private fun healthEvidence(stage: Path): JsonElement = buildJsonArray {
        launchDirectories(stage).sortedBy { it.toString() }.forEach { directory ->
            add(buildJsonObject {
                put("directory", directory.toString())
                put("tokenMarkerPresent", Files.isRegularFile(directory.resolve("startup-health.txt"), NOFOLLOW_LINKS))
                val version = directory.resolve("startup-version.txt")
                put("reportedVersion", if (Files.isRegularFile(version, NOFOLLOW_LINKS)) Files.readString(version).trim() else "")
            })
        }
    }

    private fun isolateChild(builder: ProcessBuilder, localData: Path) {
        builder.environment().apply {
            this["LOCALAPPDATA"] = localData.toString()
            listOf("BILIPAI_MPV_PATH", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forEach(::remove)
        }
    }

    private fun observeOwnedDescendants(process: Process, caseRoot: Path, observed: MutableMap<Long, ObservedChild>) {
        process.toHandle().descendants().use { children ->
            children.forEach { child ->
                val command = child.info().command().orElse(null)
                val started = child.info().startInstant().orElse(null)
                val executable = command?.let { runCatching { UpdateStorage.existingPathWithoutLinks(Path.of(it)) }.getOrNull() }
                if (started != null && executable?.startsWith(caseRoot) == true) {
                    observed[child.pid()] = ObservedChild(child, started, executable)
                }
            }
        }
    }

    private fun stillSameLiveChild(child: ObservedChild): Boolean =
        child.handle.isAlive && child.handle.info().startInstant().orElse(null) == child.started

    private fun stopObservedChild(child: ObservedChild) {
        if (!stillSameLiveChild(child)) return
        child.handle.destroy()
        runCatching { child.handle.onExit().get(1, TimeUnit.SECONDS) }
        if (stillSameLiveChild(child)) {
            child.handle.destroyForcibly()
            runCatching { child.handle.onExit().get(1, TimeUnit.SECONDS) }
        }
    }

    private inner class LoopbackFixture(private val repository: String) : Closeable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "BiliPai updater loopback fixture").apply { isDaemon = true }
        }
        private val localRoutes = ConcurrentHashMap<String, FixtureRoute>()
        private val allowedUrls = ConcurrentHashMap<String, String>()
        val client: OkHttpClient

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    val route = localRoutes[exchange.requestURI.rawPath]
                    if (exchange.requestMethod != "GET" || exchange.requestURI.rawQuery != null || route == null) {
                        exchange.sendResponseHeaders(404, -1)
                    } else {
                        route.requests.incrementAndGet()
                        exchange.sendResponseHeaders(200, route.size)
                        exchange.responseBody.use { output ->
                            if (route.path != null) {
                                Files.newInputStream(route.path).use { input ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                        route.bytes.addAndGet(count.toLong())
                                    }
                                }
                            } else {
                                output.write(requireNotNull(route.text))
                                route.bytes.addAndGet(route.size)
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Expected if the updater cancels a socket. Evidence retains the bytes actually sent.
                } finally { exchange.close() }
            }
            server.start()
            client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (hostname != "127.0.0.1") throw UnknownHostException("Fixture disallows external DNS")
                        return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                    }
                }).addInterceptor { chain ->
                    val request = chain.request()
                    val fixturePath = allowedUrls[request.url.toString()]
                        ?: throw IOException("Updater fixture rejected an unlisted request")
                    require(request.method == "GET")
                    val localUrl = "http://127.0.0.1:${server.address.port}$fixturePath"
                    chain.proceed(request.newBuilder().url(localUrl).build())
                }.build()
        }

        fun publish(case: String, version: String, zip: Path, checksum: String = sha256(zip)) {
            val asset = "BiliPai-Windows-$version-$case.zip"
            val prefix = "https://github.com/$repository/releases/download/windows-v$version"
            val checksumText = "$checksum  $asset\n"
            val zipUrl = "$prefix/$asset"
            val checksumUrl = "$zipUrl.sha256"
            val releaseUrl = "https://api.github.com/repos/$repository/releases?per_page=100&page=1"
            register(zipUrl, "/$case/package.zip", FixtureRoute(zip, null, Files.size(zip)))
            register(checksumUrl, "/$case/package.sha256", textRoute(checksumText))
            val releases = buildJsonArray {
                add(buildJsonObject {
                    put("id", 100)
                    put("tag_name", "windows-v$version")
                    put("html_url", "https://github.com/$repository/releases/tag/windows-v$version")
                    put("prerelease", true)
                    put("draft", false)
                    put("assets", buildJsonArray {
                        add(buildJsonObject {
                            put("id", 200)
                            put("name", asset)
                            put("size", Files.size(zip))
                            put("browser_download_url", zipUrl)
                        })
                        add(buildJsonObject {
                            put("id", 201)
                            put("name", "$asset.sha256")
                            put("size", checksumText.toByteArray().size)
                            put("browser_download_url", checksumUrl)
                        })
                    })
                })
            }
            register(releaseUrl, "/$case/releases.json", textRoute(releases.toString()))
        }

        private fun textRoute(text: String): FixtureRoute = text.toByteArray().let { FixtureRoute(null, it, it.size.toLong()) }
        private fun register(url: String, localPath: String, route: FixtureRoute) {
            localRoutes[localPath] = route
            allowedUrls[url] = localPath
        }

        fun evidence(): JsonElement = buildJsonObject {
            put("address", "127.0.0.1:${server.address.port}")
            put("unexpectedExternalRequestsAllowed", false)
            put("redirectsAllowed", false)
            put("routes", buildJsonArray {
                localRoutes.toSortedMap().forEach { (path, route) ->
                    add(buildJsonObject {
                        put("path", path)
                        put("requests", route.requests.get())
                        put("bytesSent", route.bytes.get())
                    })
                }
            })
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
