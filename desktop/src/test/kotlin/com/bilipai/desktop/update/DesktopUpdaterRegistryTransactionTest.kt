package com.bilipai.desktop.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Actual private snapshot/commit and real updater file locks on isolated disk.
 * Reflection selects the existing registry transaction; it does not substitute its policy.
 * No health child is started: this proves registry admission, not an installation/startup pass
 * or a separate-process lock/GUI/8.3 acceptance test. */
class DesktopUpdaterRegistryTransactionTest {
    @Test
    fun lateUpgradeCannotOverwriteNewerCommitOrNumericVersionFloor() {
        RegistryFixture().use { f ->
            val a = f.updater()
            val b = f.updater()
            val old = f.executable(44)
            val late = f.executable(45)
            val newer = f.executable(46)
            val lateHigher = f.executable(48)
            val byteUpgrade = f.executable(47)
            f.record("0.2.427.44", old.toString())
            val aObserved = f.snapshot(a)
            val bObserved = f.snapshot(b)
            assertTrue(f.commit(b, bObserved, f.installation("0.2.427.46", newer.toString()), upgrade = true))
            val newBytes = f.bytes()
            assertFalse(f.commit(a, aObserved, f.installation("0.2.427.48", lateHigher.toString()), upgrade = true))
            assertContentEquals(newBytes, f.bytes(), "Late owner must not replace the newer record")
            val fresh = f.snapshot(a)
            assertFalse(f.commit(a, fresh, f.installation("0.2.427.45", late.toString()), upgrade = true))
            assertFalse(f.commit(a, fresh, f.installation("0.2.427.46", newer.toString()), upgrade = true))
            assertContentEquals(newBytes, f.bytes(), "Matching CAS alone must not allow equal or older upgrades")
            // Preserve every decoded known field, but replace the exact observed registry bytes.
            val decodedBeforeRewrite = Json.parseToJsonElement(String(newBytes, Charsets.UTF_8)).jsonObject
            f.record("0.2.427.46", newer.toString(), unknown = true)
            val rewrittenBytes = f.bytes()
            assertFalse(newBytes.contentEquals(rewrittenBytes))
            val decodedAfterRewrite = Json.parseToJsonElement(String(rewrittenBytes, Charsets.UTF_8)).jsonObject
            assertEquals(decodedBeforeRewrite.toMap(), decodedAfterRewrite.filterKeys { it != "futureFormat" })
            assertFalse(f.commit(a, fresh, f.installation("0.2.427.47", byteUpgrade.toString()), upgrade = true))
            assertContentEquals(rewrittenBytes, f.bytes(), "A higher numeric target must still reject an exact-byte CAS conflict")
            assertEquals("0.2.427.46", f.field("version"))
            f.assertRegistryReleased()
        }
    }

    @Test
    fun oldPreviousFallbackCannotReplaceAnotherOwnersNewActive() {
        RegistryFixture().use { f ->
            val a = f.updater()
            val b = f.updater()
            val previous = f.executable(45)
            val active = f.executable(46)
            val newer = f.executable(47)
            f.record("0.2.427.46", active.toString(), "0.2.427.45", previous.toString())
            val oldFallbackSnapshot = f.snapshot(a)
            val newerSnapshot = f.snapshot(b)
            assertTrue(f.commit(b, newerSnapshot,
                f.installation("0.2.427.47", newer.toString(), "0.2.427.46", active.toString()), upgrade = true))
            val newBytes = f.bytes()
            assertFalse(f.commit(a, oldFallbackSnapshot, f.installation("0.2.427.45", previous.toString()),
                upgrade = false, registeredExecutable = previous))
            assertContentEquals(newBytes, f.bytes())
            // An unrelated regular executable is not selected merely by using the previous version.
            assertFailsWith<IllegalArgumentException> {
                f.commit(a, f.snapshot(a), f.installation("0.2.427.46", previous.toString()),
                    upgrade = false, registeredExecutable = previous)
            }
            assertContentEquals(newBytes, f.bytes())
            f.assertRegistryReleased()
        }
    }

    @Test
    fun registeredCanonicalAndDotSpellingsPreserveSameActiveBytesAndAllowOnlySelectedPrevious() {
        RegistryFixture().use { f ->
            val owner = f.updater()
            val active = f.executable(46)
            val previous = f.executable(45)
            for (dotSpelling in listOf(false, true)) {
                val activeField = if (dotSpelling) active.parent.resolve(".").resolve(EXECUTABLE).toString() else active.toString()
                val previousField = if (dotSpelling) previous.parent.resolve(".").resolve(EXECUTABLE).toString() else previous.toString()
                if (dotSpelling) {
                    assertNotEquals(active.toString(), activeField)
                    assertEquals(active, UpdateStorage.existingPathWithoutLinks(Path.of(activeField)))
                    assertEquals(previous, UpdateStorage.existingPathWithoutLinks(Path.of(previousField)))
                }
                f.record("0.2.427.46", activeField, "0.2.427.45", previousField, unknown = true)
                val originalBytes = f.bytes()
                assertTrue(f.commit(owner, f.snapshot(owner), f.installation("0.2.427.46", activeField),
                    upgrade = false, registeredExecutable = active))
                assertContentEquals(originalBytes, f.bytes(), "Same active must preserve whitespace, unknown fields and previous")
                assertEquals(previousField, f.field("previousExecutablePath"))
                assertTrue(f.field("futureFormat").contains("retained"))
                assertFailsWith<IllegalArgumentException> {
                    f.commit(owner, f.snapshot(owner), f.installation("0.2.427.46", activeField),
                        upgrade = false, registeredExecutable = previous)
                }
                assertContentEquals(originalBytes, f.bytes(), "Raw field identity is insufficient for a different actual executable")
                assertTrue(f.commit(owner, f.snapshot(owner), f.installation("0.2.427.45", previousField),
                    upgrade = false, registeredExecutable = previous))
                assertEquals("0.2.427.45", f.field("version"))
                assertEquals(previousField, f.field("executablePath"))
                assertFalse("previousExecutablePath" in Json.parseToJsonElement(Files.readString(f.registry)).jsonObject)
                assertEquals(previous, UpdateStorage.existingPathWithoutLinks(Path.of(f.field("executablePath"))))
                f.assertRegistryReleased()
            }
        }
    }

    @Test
    fun realBusyLockAndUnknownRecordsRejectWithoutWritingAndAlwaysReleaseTransaction() {
        RegistryFixture().use { f ->
            val owner = f.updater()
            val old = f.executable(44)
            val newer = f.executable(45)
            val upgrade = f.installation("0.2.427.45", newer.toString())
            f.record("0.2.427.44", old.toString())
            val observed = f.snapshot(owner)
            val before = f.bytes()
            val lease = assertNotNull(UpdateStorage.tryLeaseActiveRegistry(f.root))
            lease.use {
                assertTrue(it.holds(f.root))
                // A real channel owns the OS FileLock; same-JVM overlapping acquisition must fail closed.
                val unexpected = UpdateStorage.tryLeaseActiveRegistry(f.root)
                try { assertNull(unexpected) } finally { unexpected?.close() }
                assertFalse(f.commit(owner, observed, upgrade, upgrade = true))
                assertContentEquals(before, f.bytes())
            }
            assertFalse(lease.holds(f.root))
            assertTrue(f.commit(owner, observed, upgrade, upgrade = true))
            assertEquals("0.2.427.45", f.field("version"))
            f.assertRegistryReleased()
            assertEquals(0L, Files.size(f.lockFile))
            // A foreign/invalid lock file is not silently reset or accepted.
            val installedBytes = f.bytes()
            Files.write(f.lockFile, byteArrayOf(1))
            assertFalse(f.commit(owner, f.snapshot(owner), f.installation("0.2.427.46", newer.toString()), upgrade = true))
            assertContentEquals(installedBytes, f.bytes())
            assertContentEquals(byteArrayOf(1), Files.readAllBytes(f.lockFile))
            Files.write(f.lockFile, byteArrayOf())
            f.assertRegistryReleased()
            val invalid = listOf(
                "malformed" to "{incomplete".toByteArray(),
                "invalid UTF8" to byteArrayOf(0xc3.toByte(), 0x28),
                "oversized" to ByteArray(64 * 1024 + 1) { ' '.code.toByte() },
                "wrong repository" to buildJsonObject {
                    put("repository", "other/Windows"); put("version", "0.2.427.44"); put("executablePath", old.toString())
                }.toString().toByteArray(),
            )
            for ((label, badBytes) in invalid) {
                f.record("0.2.427.44", old.toString())
                val expected = f.snapshot(owner)
                Files.write(f.registry, badBytes)
                assertFailsWith<Exception>(label) { f.snapshot(owner) }
                assertFailsWith<Exception>(label) { f.commit(owner, expected, upgrade, upgrade = true) }
                assertContentEquals(badBytes, f.bytes(), label)
                f.assertRegistryReleased()
                assertTrue(Files.list(f.root).use { entries -> entries.noneMatch { it.fileName.toString().startsWith("active-install-") } })
            }
            Files.delete(f.registry)
            val absent = f.snapshot(owner) // Only actual absence may become the initial snapshot.
            assertTrue(f.commit(owner, absent, upgrade, upgrade = true))
            assertEquals("0.2.427.45", f.field("version"))
            val directoryExpected = f.snapshot(owner)
            Files.delete(f.registry)
            Files.createDirectory(f.registry)
            try {
                assertFailsWith<IllegalArgumentException> { f.snapshot(owner) }
                assertFailsWith<IllegalArgumentException> { f.commit(owner, directoryExpected, upgrade, upgrade = true) }
                assertTrue(Files.isDirectory(f.registry, NOFOLLOW_LINKS))
                f.assertRegistryReleased()
            } finally { Files.delete(f.registry) }
        }
    }

    private class RegistryFixture : AutoCloseable {
        private val localData = Files.createTempDirectory("bp-registry-transaction-").toRealPath(NOFOLLOW_LINKS)
        val root = UpdateStorage.verifiedRoot(localData.resolve("BiliPai/updates"))
        val registry: Path = root.resolve("active-install.json")
        val lockFile: Path = root.resolve(".bilipai-active-install.lock")
        private val owners = mutableListOf<DesktopUpdater>()
        private val leases = mutableListOf<UpdateStorage.StageLease>()
        private val requests = AtomicInteger()
        private val launches = AtomicInteger()
        private val client = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            throw IOException("Registry fixture forbids every HTTP request")
        }.build()
        private val installationClass = DesktopUpdater::class.java.declaredClasses.single { it.simpleName == "ActiveInstallation" }
        private val constructor = installationClass.getDeclaredConstructor(
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
        ).apply { isAccessible = true }
        private val readSnapshot = DesktopUpdater::class.java.getDeclaredMethod("readActiveInstallationSnapshot").apply { isAccessible = true }
        private val commitSnapshot = DesktopUpdater::class.java.declaredMethods.single {
            it.name == "commitActiveInstallation" && it.parameterCount == 4
        }.apply { isAccessible = true }

        fun updater(): DesktopUpdater = DesktopUpdater.forIntegrationTest("0.2.427.44", REPOSITORY,
            root, client, localData, {
                launches.incrementAndGet()
                error("Registry fixture must not start a process")
            }).also(owners::add)

        fun executable(asset: Long): Path {
            val lease = UpdateStorage.createLeasedStage(root, REPOSITORY, asset).also(leases::add)
            return Files.writeString(Files.createDirectory(lease.stage.resolve("app")).resolve(EXECUTABLE),
                "not an executable; registry tests never launch this file").toRealPath(NOFOLLOW_LINKS)
        }

        fun record(version: String, executable: String, previousVersion: String? = null,
            previousExecutable: String? = null, unknown: Boolean = false) {
            val objectValue = buildJsonObject {
                put("repository", REPOSITORY); put("version", version); put("executablePath", executable)
                previousVersion?.let { put("previousVersion", it) }
                previousExecutable?.let { put("previousExecutablePath", it) }
                if (unknown) put("futureFormat", "retained unknown field")
            }
            Files.writeString(registry, " \r\n$objectValue\r\n")
        }

        fun installation(version: String, executable: String, previousVersion: String? = null,
            previousExecutable: String? = null): Any = constructor.newInstance(REPOSITORY, version,
                executable, previousVersion, previousExecutable, null)

        fun snapshot(owner: DesktopUpdater): Any = requireNotNull(invoke(readSnapshot, owner))
        fun commit(owner: DesktopUpdater, observed: Any, installation: Any, upgrade: Boolean,
            registeredExecutable: Path? = null): Boolean = invoke(commitSnapshot, owner, observed,
                installation, upgrade, registeredExecutable) as Boolean

        private fun invoke(method: Method, owner: DesktopUpdater, vararg args: Any?): Any? =
            try { method.invoke(owner, *args) }
            catch (error: InvocationTargetException) { throw error.targetException }

        fun bytes(): ByteArray = Files.readAllBytes(registry)
        fun field(name: String): String = Json.parseToJsonElement(Files.readString(registry)).jsonObject.getValue(name).jsonPrimitive.content
        fun assertRegistryReleased() {
            val lease = assertNotNull(UpdateStorage.tryLeaseActiveRegistry(root))
            lease.use { assertTrue(it.holds(root)); assertEquals(0L, Files.size(lockFile)) }
            // Also reacquire the actual OS channel lock, not just a Boolean lease flag.
            FileChannel.open(lockFile, WRITE, NOFOLLOW_LINKS).use { channel ->
                assertNotNull(channel.tryLock()).use { assertTrue(it.isValid) }
            }
        }

        override fun close() {
            try {
                assertEquals(0, requests.get(), "No HTTP request belongs to a registry transaction")
                assertEquals(0, launches.get(), "No child startup is exercised by this suite")
            } finally {
                try { owners.asReversed().forEach(DesktopUpdater::close) }
                finally {
                    try { leases.asReversed().forEach(UpdateStorage.StageLease::close) }
                    finally {
                        client.dispatcher.executorService.shutdownNow()
                        client.connectionPool.evictAll()
                        check(root.startsWith(localData))
                        Files.walk(localData).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                            check(it.startsWith(localData)); Files.delete(it)
                        } }
                    }
                }
            }
        }
    }

    private companion object {
        const val REPOSITORY = "owner/BiliPai-Windows"
        const val EXECUTABLE = "BiliPai Windows.exe"
    }
}
