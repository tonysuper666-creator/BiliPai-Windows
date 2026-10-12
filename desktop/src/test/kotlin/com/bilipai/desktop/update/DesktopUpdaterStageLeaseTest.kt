package com.bilipai.desktop.update

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

/** Real updater owners, ZIP extraction and file locks; no server, native code or application process. */
class DesktopUpdaterStageLeaseTest {
    @Test
    fun delayedPreparedOwnerSurvivesAnotherUpdaterCheckAndPreparation() = runBlocking {
        withTimeout(10_000) {
            MemoryRelease().use { fixture ->
                val a = fixture.updater()
                val b = fixture.updater()
                val preparedA = fixture.prepare(a)
                val aBytes = Files.readAllBytes(preparedA.executable)
                assertBusy(fixture.root, preparedA.stagingDirectory)
                val preparedB = fixture.prepare(b) // Both check() and prepare() run their real prune path.
                assertNotEquals(preparedA.stagingDirectory, preparedB.stagingDirectory)
                assertSame(preparedA, assertIs<UpdateState.Prepared>(a.state.value).prepared)
                assertContentEquals(aBytes, Files.readAllBytes(preparedA.executable))
                assertBusy(fixture.root, preparedA.stagingDirectory)
                assertBusy(fixture.root, preparedB.stagingDirectory)
                assertEquals(6, fixture.requests.size)
                assertFalse(Files.exists(fixture.root.resolve("active-install.json")))

                a.close()
                // Once this owner explicitly retires, ordinary cleanup may reclaim its unregistered stage.
                UpdateStorage.prune(fixture.root, REPOSITORY, emptySet())
                assertFalse(Files.exists(preparedA.stagingDirectory))
                assertTrue(Files.isRegularFile(preparedB.executable))
                assertBusy(fixture.root, preparedB.stagingDirectory)
                assertEquals(0, fixture.launches.get())
            }
        }
    }

    @Test
    fun legacyV1RemainsUntouchedByGenericAndAlreadyLeasedCleanup() {
        MemoryRelease().use { fixture ->
            val stage = Files.createDirectory(fixture.root.resolve("staged-91-legacy"))
            val marker = """{"repository":"$REPOSITORY","owner":"BiliPai.Windows.Updater.v1"}"""
            Files.writeString(stage.resolve(".bilipai-updater.json"), marker)
            val payload = Files.writeString(stage.resolve("download.zip"), "legacy delayed Prepared")
            val payloadBytes = Files.readAllBytes(payload)
            assertEquals(stage, UpdateStorage.ownedStage(fixture.root, stage, REPOSITORY))
            assertFalse(UpdateStorage.deleteOwnedStage(fixture.root, stage, REPOSITORY))
            assertFalse(Files.exists(stage.resolve(".bilipai-updater.lock")))
            // Registered forward transition may acquire a legacy lease; that still cannot authorize deletion.
            val legacyLease = assertNotNull(UpdateStorage.tryLeaseStage(fixture.root, stage, REPOSITORY))
            assertFalse(UpdateStorage.deleteLeasedStage(fixture.root, legacyLease, REPOSITORY))
            assertFalse(legacyLease.holds(stage))
            UpdateStorage.prune(fixture.root, REPOSITORY, emptySet())
            assertEquals(marker, Files.readString(stage.resolve(".bilipai-updater.json")))
            assertContentEquals(payloadBytes, Files.readAllBytes(payload))
            assertNotNull(UpdateStorage.tryLeaseStage(fixture.root, stage, REPOSITORY)).close()
        }
    }

    @Test
    fun realBusyFileLockProtectsV2AndChannelClosureAllowsReclaim() {
        MemoryRelease().use { fixture ->
            val stage = UpdateStorage.createStage(fixture.root, REPOSITORY, 92)
            val payload = Files.writeString(stage.resolve("download.zip"), "unregistered v2 payload")
            val channel = FileChannel.open(stage.resolve(".bilipai-updater.lock"), WRITE, NOFOLLOW_LINKS)
            val osLock = channel.lock()
            try {
                assertTrue(osLock.isValid)
                assertBusy(fixture.root, stage)
                assertFalse(UpdateStorage.deleteOwnedStage(fixture.root, stage, REPOSITORY))
                assertEquals("unregistered v2 payload", Files.readString(payload))
            } finally { channel.close() }
            // Closing the owning OS channel invalidates its lock; no StageLease.close() was involved.
            // This exercises the released-lock state, not an observed child-process crash.
            assertFalse(osLock.isValid)
            assertTrue(UpdateStorage.deleteOwnedStage(fixture.root, stage, REPOSITORY))
            assertFalse(Files.exists(stage))
        }
    }

    @Test
    fun stalePreservedSnapshotIsRecheckedAgainstActualRegistryAfterLeaseAdmission() = runBlocking {
        withTimeout(10_000) {
            MemoryRelease().use { fixture ->
                val a = fixture.updater()
                val b = fixture.updater()
                val prepared = fixture.prepare(a)
                val previous = UpdateStorage.createStage(fixture.root, REPOSITORY, 93)
                val previousExe = Files.writeString(Files.createDirectory(previous.resolve("app"))
                    .resolve(EXECUTABLE), "previous fixture")
                val obsolete = UpdateStorage.createStage(fixture.root, REPOSITORY, 94)
                val stale = actualPreservedStages(b)
                assertFalse(prepared.stagingDirectory in stale)
                assertFalse(previous in stale)
                // Deterministically place the registry commit between the stale snapshot and OS lease admission.
                // Registry setup is a fixture, not a claim that an application passed startup health.
                fixture.register(prepared.executable, previousExe)
                a.close() // The committing owner's real lease must be released before the cleaner can enter.
                val inspected = mutableSetOf<Path>()
                UpdateStorage.prune(fixture.root, REPOSITORY, stale) { stage ->
                    inspected.add(stage)
                    assertBusy(fixture.root, stage) // Callback executes while the actual candidate lease is held.
                    stage in actualPreservedStages(b)
                }
                assertTrue(prepared.stagingDirectory in inspected)
                assertTrue(previous in inspected)
                assertTrue(obsolete in inspected)
                assertTrue(Files.isRegularFile(prepared.executable))
                assertTrue(Files.isRegularFile(previousExe))
                assertFalse(Files.exists(obsolete))
                assertIs<UpdateState.Available>(b.check()) // The actual updater cleanup also retains the registry pair.
                assertTrue(Files.isRegularFile(prepared.executable))
                assertTrue(Files.isRegularFile(previousExe))
                assertEquals(0, fixture.launches.get())
            }
        }
    }

    @Test
    fun unreadableRegistryAndUnknownLeaseNeverAuthorizeAutomaticDeletion() = runBlocking {
        withTimeout(10_000) {
            MemoryRelease().use { fixture ->
                val b = fixture.updater()
                val stage = UpdateStorage.createStage(fixture.root, REPOSITORY, 95)
                val payload = Files.writeString(stage.resolve("download.zip"), "keep on unknown registry")
                Files.writeString(fixture.root.resolve("active-install.json"), "{incomplete")
                assertIs<UpdateState.Available>(b.check())
                assertEquals("keep on unknown registry", Files.readString(payload))
                assertFalse(UpdateStorage.deleteOwnedStage(fixture.root, stage, REPOSITORY) {
                    error("fresh preservation could not be determined")
                })
                assertTrue(Files.isRegularFile(payload))
                Files.delete(stage.resolve(".bilipai-updater.lock"))
                assertNull(UpdateStorage.tryLeaseStage(fixture.root, stage, REPOSITORY))
                assertFalse(UpdateStorage.deleteOwnedStage(fixture.root, stage, REPOSITORY))
                assertTrue(Files.isRegularFile(payload))
            }
        }
    }

    @Test
    fun closingDuringPreparationRemovesOnlyOwnIncompleteStageAndReleasesLease() = runBlocking {
        withTimeout(10_000) {
            MemoryRelease().use { fixture ->
                val a = fixture.updater()
                val b = fixture.updater()
                val preparedA = fixture.prepare(a)
                val updateB = assertIs<UpdateState.Available>(b.check()).update
                val entered = CountDownLatch(1)
                val proceed = CountDownLatch(1)
                fixture.beforeResponse = { request ->
                    if (request.url.toString() == fixture.checksumUrl) {
                        entered.countDown()
                        check(proceed.await(5, TimeUnit.SECONDS)) { "Fixture checksum gate timed out" }
                    }
                }
                val preparation = async { b.prepareUpdate(updateB) }
                try {
                    withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                    val incomplete = stages(fixture.root).filterNot { it == preparedA.stagingDirectory }.single()
                    assertBusy(fixture.root, incomplete)
                    b.close()
                    proceed.countDown()
                    assertFailsWith<CancellationException> { preparation.await() }
                    assertFalse(Files.exists(incomplete))
                    assertTrue(Files.isRegularFile(preparedA.executable))
                    assertBusy(fixture.root, preparedA.stagingDirectory)
                    assertEquals(setOf(preparedA.stagingDirectory), stages(fixture.root))
                    assertNull(b.prepareUpdate(updateB))
                    assertFalse(Files.exists(fixture.root.resolve("active-install.json")))
                    assertEquals(0, fixture.launches.get())
                } finally {
                    proceed.countDown()
                    preparation.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun cancellingAtActualLaunchingPublicationRetainsOpenOwnersPreparedLeaseWithoutStartingProcess() = runBlocking {
        withTimeout(10_000) {
            MemoryRelease().use { fixture ->
                val a = fixture.updater()
                val b = fixture.updater()
                val prepared = fixture.prepare(a)
                val activation = async(start = CoroutineStart.LAZY) { a.activatePreparedUpdate(prepared) }
                val observed = CompletableDeferred<Unit>()
                // Real StateFlow publication resumes an Unconfined collector inline, as in existing monitoring tests.
                // Cancellation then reaches launchInstallation's existing entry checkpoint before ProcessBuilder.start.
                val observation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    a.state.first { state ->
                        if (state == UpdateState.Launching) {
                            activation.cancel()
                            observed.complete(Unit)
                            true
                        } else false
                    }
                }
                try {
                    activation.start()
                    observed.await()
                    assertFailsWith<CancellationException> { activation.await() }
                    assertSame(prepared, assertIs<UpdateState.Prepared>(a.state.value).prepared)
                    assertBusy(fixture.root, prepared.stagingDirectory)
                    assertIs<UpdateState.Available>(b.check())
                    assertTrue(Files.isRegularFile(prepared.executable))
                    assertSame(prepared, a.prepareUpdate(prepared.update))
                    assertFalse(Files.exists(fixture.root.resolve("active-install.json")))
                    assertEquals(0, fixture.launches.get())
                    assertTrue(Files.list(prepared.stagingDirectory).use { paths ->
                        paths.noneMatch { it.fileName.toString().startsWith("launch-") }
                    }, "The pre-launch cancellation must not even create a launch directory")
                } finally {
                    observation.cancelAndJoin()
                    activation.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun beforeLaunchCancellationRestoresExactCapturedManagerWithoutStartingProcess() = runBlocking {
        MemoryRelease().use { f ->
            val updater = f.updater()
            val prepared = f.prepare(updater)
            val queue = f.queue()
            var ticket: com.bilipai.desktop.download.DesktopDownloadManager.UpdateHandoff? = null
            val rollback = AtomicInteger()
            assertFailsWith<CancellationException> {
                updater.activatePreparedUpdate(prepared, beforeLaunch = {
                    ticket = queue.beginUpdateHandoff()
                    throw CancellationException("cancel after the exact ticket capture")
                }, onCommitted = { error("Canceled transfer cannot commit") }, afterFailedLaunch = {
                    assertFalse(queue.acceptsMutations())
                    assertTrue(queue.rollbackUpdateHandoff(checkNotNull(ticket)))
                    rollback.incrementAndGet()
                })
            }
            assertEquals(1, rollback.get())
            assertTrue(queue.acceptsMutations())
            assertSame(prepared, assertIs<UpdateState.Prepared>(updater.state.value).prepared)
            assertBusy(f.root, prepared.stagingDirectory)
            assertEquals(0, f.launches.get())
            assertFalse(Files.exists(f.root.resolve("active-install.json")))
        }
    }

    @Test
    fun actualHealthFailureRetiresControlledChildThenRereadsItsRealQueueBeforeRollback() = runBlocking {
        MemoryRelease().use { f ->
            val parent = f.queue()
            var child: com.bilipai.desktop.download.DesktopDownloadManager? = null
            var controlled: StartupHealthTest.ControlledProcess? = null
            var addedId: String? = null
            val updater = f.updater(processFactory = { builder ->
                assertFalse(parent.acceptsMutations())
                val successor = f.queue()
                child = successor
                addedId = successor.enqueue(com.bilipai.desktop.player.PlaybackSource("https://cdn.example/child", title = "child-written"),
                    f.mediaRoot, com.bilipai.desktop.download.DownloadMetadata(bvid = "BVchild", cid = 20L,
                        includeCover = false, includeDanmaku = false))
                successor.pause(checkNotNull(addedId))
                f.health(builder, "0.0.0") // Actual token, deliberately wrong packaged version.
                StartupHealthTest.ControlledProcess(onDestroy = { runBlocking { successor.shutdownForApplicationExit() } })
                    .also { controlled = it }
            })
            val prepared = f.prepare(updater)
            var ticket: com.bilipai.desktop.download.DesktopDownloadManager.UpdateHandoff? = null
            val rollback = AtomicInteger()
            assertFalse(updater.activatePreparedUpdate(prepared, beforeLaunch = {
                ticket = parent.beginUpdateHandoff()
                parent.awaitUpdateHandoff(checkNotNull(ticket))
            }, onCommitted = { error("Wrong health version cannot reach registry commit") }, afterFailedLaunch = {
                val process = checkNotNull(controlled)
                assertFalse(process.alive)
                assertTrue(process.destroyed)
                assertFalse(checkNotNull(child).acceptsMutations(), "Controlled process teardown joined the actual child manager")
                assertTrue(parent.rollbackUpdateHandoff(checkNotNull(ticket)))
                rollback.incrementAndGet()
            }))
            assertEquals(1, rollback.get())
            assertEquals(1, f.launches.get())
            assertTrue(parent.acceptsMutations())
            assertEquals("child-written", parent.tasks.value.single { it.id == addedId }.title)
            assertEquals(com.android.purebilibili.feature.download.DownloadStatus.PAUSED,
                parent.tasks.value.single { it.id == addedId }.status)
            assertFalse(Files.exists(f.root.resolve("active-install.json")))
            assertIs<UpdateState.Failed>(updater.state.value)
        }
    }

    @Test
    fun cancellationAfterActualControlledStartConfirmsRetirementAndRestoresPreparedOwner() = runBlocking {
        MemoryRelease().use { f ->
            val queue = f.queue()
            val controlled = StartupHealthTest.ControlledProcess()
            var activation: Deferred<Boolean>? = null
            val updater = f.updater(processFactory = { builder ->
                f.health(builder, "0.2.427.39")
                controlled
            }, afterStarted = { checkNotNull(activation).cancel() })
            val prepared = f.prepare(updater)
            var ticket: com.bilipai.desktop.download.DesktopDownloadManager.UpdateHandoff? = null
            val rollback = AtomicInteger()
            val pending = async(start = CoroutineStart.LAZY) {
                updater.activatePreparedUpdate(prepared, beforeLaunch = {
                    ticket = queue.beginUpdateHandoff()
                    queue.awaitUpdateHandoff(checkNotNull(ticket))
                }, onCommitted = { error("Canceled child cannot commit") }, afterFailedLaunch = {
                    assertFalse(controlled.alive)
                    assertTrue(controlled.destroyed)
                    assertTrue(queue.rollbackUpdateHandoff(checkNotNull(ticket)))
                    rollback.incrementAndGet()
                })
            }
            activation = pending
            pending.start()
            assertFailsWith<CancellationException> { withTimeout(5_000) { pending.await() } }
            assertEquals(1, rollback.get())
            assertEquals(1, f.launches.get())
            assertTrue(queue.acceptsMutations())
            assertSame(prepared, assertIs<UpdateState.Prepared>(updater.state.value).prepared)
            assertBusy(f.root, prepared.stagingDirectory)
            assertFalse(Files.exists(f.root.resolve("active-install.json")))
        }
    }

    @Test
    fun healthyActualActivationCommitsBeforeOldQueueShutdownAndPreservesSuccessorDisk() = runBlocking {
        MemoryRelease().use { f ->
            val parent = f.queue()
            var child: com.bilipai.desktop.download.DesktopDownloadManager? = null
            var controlled: StartupHealthTest.ControlledProcess? = null
            val updater = f.updater(processFactory = { builder ->
                assertFalse(parent.acceptsMutations())
                val successor = f.queue()
                child = successor
                val id = successor.enqueue(com.bilipai.desktop.player.PlaybackSource("https://cdn.example/child", title = "healthy-child"),
                    f.mediaRoot, com.bilipai.desktop.download.DownloadMetadata(bvid = "BVchild", cid = 20L,
                        includeCover = false, includeDanmaku = false))
                successor.pause(id)
                f.health(builder, "0.2.427.39")
                StartupHealthTest.ControlledProcess(onDestroy = { runBlocking { successor.shutdownForApplicationExit() } })
                    .also { controlled = it }
            })
            val prepared = f.prepare(updater)
            var ticket: com.bilipai.desktop.download.DesktopDownloadManager.UpdateHandoff? = null
            val committed = AtomicInteger()
            val rollback = AtomicInteger()
            assertTrue(withTimeout(5_000) { updater.activatePreparedUpdate(prepared, beforeLaunch = {
                ticket = parent.beginUpdateHandoff()
                parent.awaitUpdateHandoff(checkNotNull(ticket))
            }, onCommitted = {
                assertTrue(checkNotNull(controlled).alive)
                assertTrue(Files.isRegularFile(f.root.resolve("active-install.json")))
                parent.commitUpdateHandoff(checkNotNull(ticket))
                committed.incrementAndGet()
            }, afterFailedLaunch = { rollback.incrementAndGet() }) })
            assertEquals(1, committed.get())
            assertEquals(0, rollback.get())
            assertIs<UpdateState.Launched>(updater.state.value)
            val successorDisk = Files.readAllBytes(f.queueFile)
            parent.shutdownForApplicationExit()
            parent.close()
            assertContentEquals(successorDisk, Files.readAllBytes(f.queueFile))
            assertEquals("healthy-child", checkNotNull(child).tasks.value.single().title)
            assertTrue(checkNotNull(controlled).alive)
        }
    }

    @Test
    fun unknownChildCustodyNeverRollsBackOrReleasesPreparedLeaseEvenAfterOwnerClose() = runBlocking {
        MemoryRelease().use { f ->
            val queue = f.queue()
            val controlled = StartupHealthTest.ControlledProcess(knownDescendants = false)
            val updater = f.updater(processFactory = { builder ->
                f.health(builder, "0.0.0")
                controlled
            })
            val prepared = f.prepare(updater)
            var ticket: com.bilipai.desktop.download.DesktopDownloadManager.UpdateHandoff? = null
            val rollback = AtomicInteger()
            try {
                assertFailsWith<IllegalStateException> {
                    updater.activatePreparedUpdate(prepared, beforeLaunch = {
                        ticket = queue.beginUpdateHandoff()
                        queue.awaitUpdateHandoff(checkNotNull(ticket))
                    }, onCommitted = { error("Unknown failed child cannot commit") },
                        afterFailedLaunch = { rollback.incrementAndGet() })
                }
                assertFalse(controlled.alive) // Best-effort direct stop is distinct from custody confirmation.
                assertEquals(0, rollback.get())
                assertFalse(queue.acceptsMutations())
                assertBusy(f.root, prepared.stagingDirectory)
                val retryBeforeLaunch = AtomicInteger()
                assertFailsWith<IllegalStateException> {
                    updater.activatePreparedUpdate(prepared,
                        beforeLaunch = { retryBeforeLaunch.incrementAndGet(); error("Unknown custody must reject before a new queue ticket") },
                        onCommitted = { error("Unknown custody cannot commit") },
                        afterFailedLaunch = { rollback.incrementAndGet() })
                }
                assertEquals(0, retryBeforeLaunch.get())
                assertEquals(1, f.launches.get())
                assertEquals(0, rollback.get())
                assertFalse(queue.acceptsMutations())
                assertBusy(f.root, prepared.stagingDirectory)
                updater.close()
                assertBusy(f.root, prepared.stagingDirectory)
                assertTrue(Files.isRegularFile(prepared.executable))
                assertFalse(Files.exists(f.root.resolve("active-install.json")))
            } finally {
                // Teardown only: this test owns the controlled memory Process and the
                // fresh Temp lease. Never infer this release in production policy.
                assertFalse(controlled.alive)
                val field = DesktopUpdater::class.java.getDeclaredField("retainedLease").apply { isAccessible = true }
                (field.get(updater) as? UpdateStorage.StageLease)?.close()
            }
        }
    }

    private fun assertBusy(root: Path, stage: Path) {
        assertEquals(stage, UpdateStorage.ownedStage(root, stage, REPOSITORY))
        assertTrue(Files.isRegularFile(stage.resolve(".bilipai-updater.lock"), NOFOLLOW_LINKS))
        assertEquals(0L, Files.size(stage.resolve(".bilipai-updater.lock")))
        val unexpected = UpdateStorage.tryLeaseStage(root, stage, REPOSITORY)
        try { assertNull(unexpected, "Another owner must not acquire $stage") }
        finally { unexpected?.close() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun actualPreservedStages(updater: DesktopUpdater): Set<Path> =
        DesktopUpdater::class.java.getDeclaredMethod("preservedInstallationStages").apply { isAccessible = true }
            .invoke(updater) as Set<Path>

    private fun stages(root: Path): Set<Path> = Files.list(root).use { paths ->
        paths.filter { UpdateStorage.ownedStage(root, it, REPOSITORY) != null }.toList().toSet()
    }

    private class MemoryRelease : AutoCloseable {
        private val localData = Files.createTempDirectory("bp-stage-lease-").toRealPath(NOFOLLOW_LINKS)
        val root: Path = UpdateStorage.verifiedRoot(localData.resolve("BiliPai/updates"))
        val launches = AtomicInteger()
        val requests = Collections.synchronizedList(mutableListOf<String>())
        private val owners = mutableListOf<DesktopUpdater>()
        val queueFile: Path = localData.resolve("queue.json")
        val mediaRoot: Path = localData.resolve("media")
        private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val queueGate = Any()
        private val queues = mutableListOf<com.bilipai.desktop.download.DesktopDownloadManager>()
        private val processes = mutableListOf<StartupHealthTest.ControlledProcess>()
        private val queueClient = OkHttpClient.Builder().addInterceptor {
            throw IOException("Headless queue disallows media transport")
        }.build()
        private val prefix = "https://github.com/$REPOSITORY/releases/download/windows-v0.2.427.39"
        private val zipUrl = "$prefix/BiliPai-Windows-lease.zip"
        val checksumUrl = "$zipUrl.sha256"
        @Volatile var beforeResponse: (Request) -> Unit = {}
        private val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("fixture/$EXECUTABLE"))
                zip.write("not an executable; no test may launch this file".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        private val checksum = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            requests += request.url.toString()
            beforeResponse(request)
            val body = when (request.url.toString()) {
                "https://api.github.com/repos/$REPOSITORY/releases?per_page=100&page=1" ->
                    """[{"id":41,"tag_name":"windows-v0.2.427.39","html_url":"https://github.com/$REPOSITORY/releases/tag/windows-v0.2.427.39","assets":[{"id":42,"name":"BiliPai-Windows-lease.zip","size":${archive.size},"browser_download_url":"$zipUrl"},{"id":43,"name":"BiliPai-Windows-lease.zip.sha256","size":100,"browser_download_url":"$checksumUrl"}]}]""".toByteArray()
                checksumUrl -> "$checksum  BiliPai-Windows-lease.zip\n".toByteArray()
                zipUrl -> archive
                else -> error("Unexpected memory HTTP request: ${request.url}")
            }
            // The interceptor always returns a bounded in-memory response, never chain.proceed().
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory fixture")
                .body(body.toResponseBody()).build()
        }.build()

        fun updater(processFactory: ((ProcessBuilder) -> Process)? = null,
            afterStarted: (Process) -> Unit = {}): DesktopUpdater =
            DesktopUpdater.forIntegrationTest("0.2.427.38", REPOSITORY, root, client, localData, {
                launches.incrementAndGet()
                check(processFactory != null) { "No test may launch a process" }
                afterStarted(it)
            }, processFactory = processFactory?.let { factory -> { builder ->
                factory(builder).also { process ->
                    processes.add(assertIs<StartupHealthTest.ControlledProcess>(process))
                }
            } }).also(owners::add)

        fun queue(): com.bilipai.desktop.download.DesktopDownloadManager {
            val owner = queueScope.coroutineContext.job
            val publication = com.bilipai.desktop.player.DesktopLocalPlaybackPublication({ owner.isActive }, { action ->
                synchronized(queueGate) { if (!owner.isActive) false else { action(); true } }
            })
            return com.bilipai.desktop.download.DesktopDownloadManager(queueClient, queueFile,
                com.bilipai.desktop.download.DownloadMuxer { _, _, _ -> error("Sealed media cannot reach muxing") },
                publication = publication).also(queues::add)
        }

        fun health(builder: ProcessBuilder, version: String) {
            val command = builder.command()
            assertEquals(EXECUTABLE, Path.of(command.first()).fileName.toString())
            assertEquals(localData.toString(), builder.environment()["LOCALAPPDATA"])
            val markerIndex = command.indexOf("--update-health-file")
            val tokenIndex = command.indexOf("--update-health-token")
            assertTrue(markerIndex >= 0 && tokenIndex >= 0)
            val marker = Path.of(command[markerIndex + 1]).toAbsolutePath().normalize()
            assertTrue(marker.startsWith(root))
            assertTrue(Files.isDirectory(marker.parent, NOFOLLOW_LINKS))
            Files.writeString(marker.resolveSibling("startup-version.txt"), version,
                java.nio.file.StandardOpenOption.CREATE_NEW)
            Files.writeString(marker, command[tokenIndex + 1], java.nio.file.StandardOpenOption.CREATE_NEW)
        }

        suspend fun prepare(updater: DesktopUpdater): PreparedUpdate =
            assertNotNull(updater.prepareUpdate(assertIs<UpdateState.Available>(updater.check()).update))

        fun register(active: Path, previous: Path) {
            Files.writeString(root.resolve("active-install.json"), buildJsonObject {
                put("repository", REPOSITORY); put("version", "windows-v0.2.427.39")
                put("executablePath", active.toString()); put("previousVersion", "windows-v0.2.427.38")
                put("previousExecutablePath", previous.toString())
            }.toString())
        }

        override fun close() {
            runBlocking { withContext(NonCancellable) {
                queues.asReversed().forEach { it.shutdownForApplicationExit() }
                processes.asReversed().filter { it.alive }.forEach { it.destroy() }
                queueScope.coroutineContext.job.cancelAndJoin()
            } }
            queueClient.dispatcher.executorService.shutdownNow()
            queueClient.connectionPool.evictAll()
            owners.asReversed().forEach(DesktopUpdater::close)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            // Only the fresh fixture root is removed; no launcher, user config or real LOCALAPPDATA is read.
            Files.walk(localData).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }

    private companion object {
        const val REPOSITORY = "owner/BiliPai-Windows"
        const val EXECUTABLE = "BiliPai Windows.exe"
    }
}
