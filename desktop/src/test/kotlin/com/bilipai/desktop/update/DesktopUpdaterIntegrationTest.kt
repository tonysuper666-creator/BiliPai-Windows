package com.bilipai.desktop.update

import com.sun.net.httpserver.HttpServer
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.download.DesktopDownloadQueueAuthority
import com.bilipai.desktop.download.DownloadMuxer
import com.bilipai.desktop.player.DesktopLocalPlaybackPublication
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
        val childEnvironment = PackagedUpdaterChildEnvironment(caseRoot)
        val childData = childEnvironment.localData
        val updateRoot = UpdateStorage.verifiedRoot(childData.resolve("BiliPai/updates"))
        val activeFile = updateRoot.resolve("active-install.json")
        val report = System.getProperty("bilipai.updateTestReport")?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).toAbsolutePath().normalize().resolve("updater-smoke.json") } ?: caseRoot.resolve("updater-smoke.json")
        val launched = Collections.synchronizedList(mutableListOf<Process>())
        // Capture while each direct parent is still alive, and retain the actual
        // fixture observation for cleanup even after a forwarding parent exits.
        val launchObservations = Collections.synchronizedMap(mutableMapOf<Process, StartupHealth.DescendantObservation>())
        fun recordLaunch(process: Process) {
            launchObservations[process] = StartupHealth.DescendantObservation(process)
            launched.add(process)
        }
        val observed = mutableMapOf<Long, ObservedChild>()
        val evidence = linkedMapOf<String, JsonElement>(
            "passed" to JsonPrimitive(false),
            "evidenceType" to JsonPrimitive("isolated-packaged-updater"),
            "caseRoot" to JsonPrimitive(caseRoot.toString()),
            "isolatedLocalAppData" to JsonPrimitive(childData.toString()),
            "isolatedRoamingAppData" to JsonPrimitive(childEnvironment.roamingData.toString()),
            "isolatedUserHome" to JsonPrimitive(childEnvironment.home.toString()),
            "isolatedJvmAndChildTemp" to JsonPrimitive(childEnvironment.temporary.toString()),
            "childMaximumHeap" to JsonPrimitive("2g"),
            "windowsKnownFoldersSandboxed" to JsonPrimitive(false),
            "updateRoot" to JsonPrimitive(updateRoot.toString()),
            "portableZip" to JsonPrimitive(packagePath.toString()),
            "realShortcutTested" to JsonPrimitive(false),
            "childBackgroundNetworkAllowed" to JsonPrimitive(true),
        )
        var failure: Throwable? = null
        try {
            require(Files.isRegularFile(packagePath, NOFOLLOW_LINKS))
            val config = readPackageConfig(packagePath)
            val previousPath = UpdateStorage.existingPathWithoutLinks(Path.of(requireNotNull(
                System.getProperty("bilipai.updatePreviousPackage")?.takeIf { it.isNotBlank() }) {
                "Provide the actual previous package for rooted updater/rollback testing"
            }))
            val previousConfig = readPackageConfig(previousPath)
            require(previousConfig.repository == config.repository && previousConfig.executable == config.executable)
            val baseline = previousConfig.version
            require(requireNotNull(DesktopVersion.parse(baseline)) < requireNotNull(DesktopVersion.parse(config.version)))
            require(DesktopUpdater.validRepository(config.repository))
            evidence["windowsVersion"] = JsonPrimitive(config.version)
            evidence["portableZipSha256"] = JsonPrimitive(sha256(packagePath))
            evidence["baselineVersion"] = JsonPrimitive(baseline)
            evidence["repository"] = JsonPrimitive(config.repository)

            LoopbackFixture(config.repository).use { fixture ->
                var downloads: DesktopDownloadManager? = null
                var damagedStageLease: UpdateStorage.StageLease? = null
                val updaters = mutableListOf<DesktopUpdater>()
                try {
                    fun updater(installed: String = baseline) = DesktopUpdater.forIntegrationTest(installed,
                        config.repository, updateRoot, fixture.client, childData, ::recordLaunch, config.executable,
                        processFactory = { builder -> childEnvironment.configure(builder).start() }).also(updaters::add)
                    suspend fun stop(process: Process) = StartupHealth.terminate(process, checkNotNull(launchObservations[process]))
                    val queueFile = childData.resolve("BiliPai/downloads.json")
                    fun releaseCommittedQueueTransfer() {
                        val lease = assertNotNull(DesktopDownloadQueueAuthority.tryAcquire(queueFile).lease,
                            "Retired committed child must leave recoverable download custody")
                        lease.close()
                    }

                    // Register a genuine older installation. Negative .122 candidates must be
                    // newer than this registry; equal-version guards remain fully enabled.
                    fixture.publish("baseline", baseline, previousPath)
                    val baselineUpdater = updater(precedingRevision(baseline))
                    val baselineUpdate = assertIs<UpdateState.Available>(baselineUpdater.check()).update
                    val baselinePrepared = assertNotNull(baselineUpdater.prepareUpdate(baselineUpdate))
                    assertTrue(baselineUpdater.activatePreparedUpdate(baselinePrepared))
                    val baselineProcess = launched.last()
                    assertTrue(baselineProcess.isAlive)
                    val baselineRegistry = Files.readAllBytes(activeFile)
                    evidence["baselineActivation"] = buildJsonObject {
                        put("passed", true); put("actualPreviousZipSha256", sha256(previousPath))
                        put("health", healthEvidence(baselinePrepared.stagingDirectory))
                    }
                    // The fixture parent now plays the old Root's queue-owner role. A second
                    // live EXE would retain the OS lease and confound every health rejection.
                    stop(baselineProcess)
                    assertFalse(baselineProcess.isAlive)
                    releaseCommittedQueueTransfer()
                    val queue = DesktopDownloadManager(fixture.client, queueFile,
                        DownloadMuxer { _, _, _ -> error("Idle updater fixture must not mux media") },
                        publication = DesktopLocalPlaybackPublication({ true }, { action -> action(); true }))
                    downloads = queue
                    assertTrue(queue.acceptsMutations())
                    assertTrue(queue.tasks.value.isEmpty())
                    var rollbacks = 0
                    var commits = 0
                    var lastLaunchToken: String? = null
                    suspend fun activate(owner: DesktopUpdater, prepared: PreparedUpdate): Boolean {
                        var ticket: DesktopDownloadManager.UpdateHandoff? = null
                        val activated = owner.activatePreparedUpdate(prepared,
                            beforeLaunch = { token ->
                                lastLaunchToken = token
                                ticket = queue.beginUpdateHandoff(token, owner.downloadHandoffTarget(prepared))
                                queue.awaitUpdateHandoff(checkNotNull(ticket))
                            },
                            onCommitted = { ticket?.let { queue.commitUpdateHandoff(it); commits++ } },
                            afterFailedLaunch = { ticket?.let {
                                assertTrue(queue.rollbackUpdateHandoff(it)); rollbacks++
                            } })
                        assertNotNull(ticket, "Admitted activation must exercise the real Root queue handoff")
                        assertEquals(!activated, queue.acceptsMutations())
                        return activated
                    }
                    fun assertBaselinePreserved() = assertContentEquals(baselineRegistry, Files.readAllBytes(activeFile))

                    fixture.publish("wrong-hash", config.version, packagePath, "00".repeat(32))
                    val hashUpdater = updater()
                    val beforeHashStages = ownedStages(updateRoot, config.repository)
                    val beforeHashProcesses = launched.size
                    val hashUpdate = assertIs<UpdateState.Available>(hashUpdater.check()).update
                    assertNull(hashUpdater.prepareUpdate(hashUpdate))
                    val hashFailure = assertIs<UpdateState.Failed>(hashUpdater.state.value)
                    assertTrue(hashFailure.message.contains("SHA-256"))
                    assertTrue(fixture.requestCount("/wrong-hash/package.sha256") > 0)
                    assertTrue(fixture.requestCount("/wrong-hash/package.zip") > 0)
                    assertEquals(beforeHashProcesses, launched.size)
                    assertEquals(beforeHashStages, ownedStages(updateRoot, config.repository))
                    assertBaselinePreserved()
                    assertTrue(queue.acceptsMutations())
                    evidence["wrongHashRejected"] = JsonPrimitive(true)

                    fixture.publish("version-mismatch", succeedingRevision(config.version), packagePath)
                    val versionUpdater = updater()
                    val versionUpdate = assertIs<UpdateState.Available>(versionUpdater.check()).update
                    val mismatched = assertNotNull(versionUpdater.prepareUpdate(versionUpdate))
                    assertFalse(activate(versionUpdater, mismatched))
                    assertFalse(launched.last().isAlive)
                    assertBaselinePreserved()
                    // A tokenless startup failure is not a version-mismatch test.
                    assertLaunchHealth(mismatched.stagingDirectory, checkNotNull(lastLaunchToken), config.version)
                    evidence["versionMismatchRejected"] = buildJsonObject {
                        put("passed", true); put("fixtureAdvertisedVersion", versionUpdate.version)
                        put("actualPackageVersion", config.version); put("healthyVersionMismatchObserved", true)
                        put("startup", captureStartupEvidence(mismatched.stagingDirectory, caseRoot, "version-mismatch"))
                    }
                    fixture.publish("after-version-failure", config.version, packagePath)
                    assertTrue(assertIs<UpdateState.Available>(versionUpdater.check()).update.assetName.contains("after-version-failure"))

                    val damagedZip = caseRoot.resolve("damaged-native-fixture.zip")
                    val damagedEntries = damageNativeLibrary(packagePath, damagedZip)
                    fixture.publish("damaged-native", config.version, damagedZip)
                    val damagedUpdater = updater()
                    val damagedUpdate = assertIs<UpdateState.Available>(damagedUpdater.check()).update
                    val damaged = assertNotNull(damagedUpdater.prepareUpdate(damagedUpdate))
                    val beforeDamageProcesses = launched.size
                    assertFalse(activate(damagedUpdater, damaged))
                    assertTrue(launched.size > beforeDamageProcesses)
                    assertFalse(launched.last().isAlive)
                    assertBaselinePreserved()
                    assertNoLaunchHealth(damaged.stagingDirectory)
                    evidence["damagedStartupRejected"] = buildJsonObject {
                        put("passed", true); put("fixtureZipSha256", sha256(damagedZip))
                        put("modifiedEntries", buildJsonArray { damagedEntries.forEach { add(JsonPrimitive(it)) } })
                        put("originalActiveBytesUnchanged", true)
                        put("startup", captureStartupEvidence(damaged.stagingDirectory, caseRoot, "damaged-native"))
                    }
                    // Failure retires its receipt. Hold the actual payload through pruning so
                    // the later registered-install fixture can use this same real damaged EXE.
                    damagedStageLease = assertNotNull(UpdateStorage.tryLeaseStage(updateRoot, damaged.stagingDirectory, config.repository))
                    val beforeStaleActivation = launched.size
                    assertFalse(damagedUpdater.activatePreparedUpdate(damaged))
                    assertEquals(beforeStaleActivation, launched.size)
                    fixture.publish("after-damaged-failure", config.version, packagePath)
                    assertTrue(assertIs<UpdateState.Available>(damagedUpdater.check()).update.assetName.contains("after-damaged-failure"))
                    evidence["failedPreparedCandidateRevoked"] = JsonPrimitive(true)

                    val damagedRootZip = caseRoot.resolve("damaged-root-fixture.zip")
                    val damagedRootEntries = damageRootWorkerCatalog(packagePath, damagedRootZip)
                    fixture.publish("damaged-root", config.version, damagedRootZip)
                    val damagedRootUpdater = updater()
                    val damagedRootUpdate = assertIs<UpdateState.Available>(damagedRootUpdater.check()).update
                    val damagedRoot = assertNotNull(damagedRootUpdater.prepareUpdate(damagedRootUpdate))
                    val beforeRootDamageProcesses = launched.size
                    assertFalse(activate(damagedRootUpdater, damagedRoot))
                    assertTrue(launched.size > beforeRootDamageProcesses)
                    assertFalse(launched.last().isAlive)
                    assertBaselinePreserved()
                    assertNoLaunchHealth(damagedRoot.stagingDirectory)
                    evidence["damagedRootStartupRejected"] = buildJsonObject {
                        put("passed", true); put("fixtureZipSha256", sha256(damagedRootZip))
                        put("modifiedEntries", buildJsonArray { damagedRootEntries.forEach { add(JsonPrimitive(it)) } })
                        put("originalActiveBytesUnchanged", true); put("rootHealthAcknowledged", false)
                        put("startup", captureStartupEvidence(damagedRoot.stagingDirectory, caseRoot, "damaged-root"))
                    }

                    fixture.publish("healthy", config.version, packagePath)
                    val healthyUpdater = updater()
                    val healthyUpdate = assertIs<UpdateState.Available>(healthyUpdater.check()).update
                    val healthy = assertNotNull(healthyUpdater.prepareUpdate(healthyUpdate))
                    assertTrue(activate(healthyUpdater, healthy))
                    val goodProcess = launched.last()
                    assertTrue(goodProcess.isAlive)
                    assertLaunchHealth(healthy.stagingDirectory, checkNotNull(lastLaunchToken), config.version)
                    val registered = json.parseToJsonElement(Files.readString(activeFile)).jsonObject
                    assertEquals(healthyUpdate.version, registered.getValue("version").jsonPrimitive.content)
                    assertEquals(healthy.executable.toString(), registered.getValue("executablePath").jsonPrimitive.content)
                    assertEquals(baselineUpdate.version, registered.getValue("previousVersion").jsonPrimitive.content)
                    assertEquals(baselinePrepared.executable.toString(), registered.getValue("previousExecutablePath").jsonPrimitive.content)
                    assertEquals(3, rollbacks); assertEquals(1, commits)
                    evidence["successActivation"] = buildJsonObject {
                        put("passed", true); put("processId", goodProcess.pid()); put("executable", healthy.executable.toString())
                        put("previousVersion", baseline); put("health", healthEvidence(healthy.stagingDirectory))
                    }
                    evidence["realDownloadHandoff"] = buildJsonObject {
                        put("passed", true); put("failedTransfersRolledBack", rollbacks); put("healthyTransfersCommitted", commits)
                        put("queueFile", queueFile.toString()); put("mediaTasksUsed", false)
                    }
                    queue.shutdownForApplicationExit(); downloads = null
                    stop(goodProcess)
                    // Clear committed custody using the real authority while its exact registry
                    // proof still exists. Do not delete transfer files or forge a queue receipt.
                    releaseCommittedQueueTransfer()

                    // Both payloads really carry .122. Replace only the explicit fixture registry,
                    // retaining the equal-version/different-path fallback regression.
                    Files.writeString(activeFile, json.encodeToString(JsonObject.serializer(), buildJsonObject {
                        put("repository", config.repository); put("version", healthyUpdate.version)
                        put("executablePath", damaged.executable.toString()); put("previousVersion", healthyUpdate.version)
                        put("previousExecutablePath", healthy.executable.toString())
                    }))
                    damagedStageLease?.close(); damagedStageLease = null
                    val beforeFallbackProcesses = launched.size
                    assertTrue(updater().launchRegisteredInstall(emptyArray()))
                    val fallbackProcesses = launched.drop(beforeFallbackProcesses)
                    assertEquals(2, fallbackProcesses.size)
                    assertFalse(fallbackProcesses.first().isAlive); assertTrue(fallbackProcesses.last().isAlive)
                    val fallbackRecord = json.parseToJsonElement(Files.readString(activeFile)).jsonObject
                    assertEquals(healthy.executable.toString(), fallbackRecord.getValue("executablePath").jsonPrimitive.content)
                    assertEquals(healthyUpdate.version, fallbackRecord.getValue("version").jsonPrimitive.content)
                    evidence["registeredFallback"] = buildJsonObject {
                        put("passed", true); put("registrySetupWasFixture", true); put("sameActualVersion", true)
                        put("failedProcessId", fallbackProcesses.first().pid()); put("healthyProcessId", fallbackProcesses.last().pid())
                        put("activeExecutable", healthy.executable.toString())
                    }
                    stop(fallbackProcesses.last())
                    releaseCommittedQueueTransfer()

                    val previousDirectory = Files.createDirectory(caseRoot.resolve("previous-launcher"))
                    val previousExecutable = SafeUpdateZip.extract(previousPath, previousDirectory, previousConfig.executable)
                    val beforeLaunchDirectories = launchDirectories(healthy.stagingDirectory)
                    val original = ProcessBuilder(previousExecutable.toString()).directory(previousExecutable.parent.toFile())
                        .redirectOutput(caseRoot.resolve("previous-output.log").toFile())
                        .redirectError(caseRoot.resolve("previous-error.log").toFile())
                    childEnvironment.configure(original)
                    val originalProcess = original.start(); recordLaunch(originalProcess)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                    while (originalProcess.isAlive && System.nanoTime() < deadline) {
                        checkNotNull(launchObservations[originalProcess]).observe()
                        observeOwnedDescendants(originalProcess, caseRoot, observed); delay(25)
                    }
                    assertFalse(originalProcess.isAlive, "Actual previous EXE did not delegate and exit")
                    assertEquals(0, originalProcess.exitValue())
                    val forwarded = observed.values.filter { it.executable == healthy.executable && it.handle.isAlive }
                    assertTrue(forwarded.isNotEmpty())
                    val forwardedLaunches = launchDirectories(healthy.stagingDirectory) - beforeLaunchDirectories
                    assertTrue(forwardedLaunches.isNotEmpty())
                    forwardedLaunches.forEach { directory ->
                        assertTrue(Files.isRegularFile(directory.resolve("startup-health.txt")))
                        assertEquals(config.version, Files.readString(directory.resolve("startup-version.txt")).trim())
                    }
                    assertEquals(healthy.executable.toString(),
                        json.parseToJsonElement(Files.readString(activeFile)).jsonObject.getValue("executablePath").jsonPrimitive.content)
                    evidence["actualPreviousExeForwarding"] = buildJsonObject {
                        put("tested", true); put("passed", true); put("previousVersion", previousConfig.version)
                        put("previousZipSha256", sha256(previousPath)); put("previousExecutable", previousExecutable.toString())
                        put("originalProcessId", originalProcess.pid())
                        put("newProcessIds", buildJsonArray { forwarded.forEach { add(JsonPrimitive(it.handle.pid())) } })
                        put("shortcutTested", false)
                    }
                } finally {
                    downloads?.shutdownForApplicationExit()
                    damagedStageLease?.close()
                    updaters.asReversed().forEach { it.close() }
                    evidence["fixtureHttp"] = fixture.evidence()
                }
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
                    runCatching { StartupHealth.terminate(process, checkNotNull(launchObservations[process])) }.onFailure { if (failure == null) failure = it }
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

    private fun assertLaunchHealth(stage: Path, token: String, actualVersion: String) {
        val directories = launchDirectories(stage)
        assertTrue(directories.isNotEmpty())
        directories.forEach { directory ->
            assertEquals(token, Files.readString(directory.resolve("startup-health.txt")).trim())
            assertEquals(actualVersion, Files.readString(directory.resolve("startup-version.txt")).trim())
        }
    }

    private fun assertNoLaunchHealth(stage: Path) {
        val directories = launchDirectories(stage)
        assertTrue(directories.isNotEmpty())
        directories.forEach { directory ->
            assertFalse(Files.exists(directory.resolve("startup-health.txt"), NOFOLLOW_LINKS))
            assertFalse(Files.exists(directory.resolve("startup-version.txt"), NOFOLLOW_LINKS))
        }
    }

    /** Preserve bounded owned-process evidence before the next check prunes a
     * retired stage. This stays in the temporary fixture, never a cloud upload. */
    private fun captureStartupEvidence(stage: Path, caseRoot: Path, label: String): JsonElement = buildJsonObject {
        put("health", healthEvidence(stage))
        put("logs", buildJsonArray {
            launchDirectories(stage).forEach { directory ->
                val destination = caseRoot.resolve("startup-evidence").resolve(label).resolve(directory.fileName)
                Files.createDirectories(destination)
                for (leaf in listOf("startup-output.log", "startup-error.log")) {
                    val source = directory.resolve(leaf)
                    if (!Files.isRegularFile(source, NOFOLLOW_LINKS)) continue
                    val target = destination.resolve(leaf)
                    val bytes = Files.newInputStream(source).use { it.readNBytes(2 * 1024 * 1024) }
                    Files.write(target, bytes)
                    add(buildJsonObject {
                        put("path", target.toString()); put("sha256", sha256(target))
                        put("bytes", bytes.size); put("truncated", Files.size(source) > bytes.size)
                    })
                }
            }
        })
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

        fun requestCount(path: String): Long = localRoutes[path]?.requests?.get() ?: 0L

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
