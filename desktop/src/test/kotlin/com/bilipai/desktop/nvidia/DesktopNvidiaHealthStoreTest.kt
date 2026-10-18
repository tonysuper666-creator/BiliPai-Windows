package com.bilipai.desktop.nvidia

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A wall clock that only moves when a test moves it. */
private class HealthStoreTestClock(var now: Long = 1_800_000_000_000L)

class DesktopNvidiaHealthStoreTest {
    @TempDir
    lateinit var root: Path

    private val day = 86_400_000L
    private val keyA = "00000000000000a1"
    private val keyB = "00000000000000b2"

    private fun entry(key: String, now: Long, streak: Int = 1, rememberedUntil: Long = 0L) =
        NvidiaHealthEntry(key, streak = streak, rememberedUntilMillis = rememberedUntil, updatedAtMillis = now)

    private fun healthFile(): Path = root.resolve("gpu").resolve(HEALTH_FILE_NAME)

    /** Polls the file until the condition holds for its text, for at most the timeout. The writer runs on its own thread. */
    private fun awaitFileText(path: Path, timeoutMillis: Long = 5_000L, condition: (String) -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            val text = try {
                if (Files.isRegularFile(path)) String(Files.readAllBytes(path), Charsets.UTF_8) else null
            } catch (_: Exception) {
                null
            }
            if (text != null && condition(text)) return true
            Thread.sleep(10)
        }
        return false
    }

    // ---- Codec ----------------------------------------------------------------------------

    @Test fun theFileIsVersionOneJsonWithAtMostEightEntriesAndRoundTrips() {
        val now = 1_800_000_000_000L
        val entries = listOf(
            NvidiaHealthEntry(keyA, streak = 2, rememberedUntilMillis = now + day, learnedQualityCeiling = 3,
                learnedUntilMillis = now + 7 * day, failedQualityLevel = 4, failedQualityCount = 2,
                extendedRangeDeniedUntilMillis = now + 3 * day, updatedAtMillis = now),
            NvidiaHealthEntry(keyB, streak = 1, updatedAtMillis = now - 1_000L),
        )
        val text = encodeNvidiaHealthFile(entries)
        assertTrue(text.startsWith("{\"version\":1,\"entries\":["), text)
        assertEquals(1, HEALTH_SCHEMA_VERSION)
        assertEquals(8, HEALTH_MAX_ENTRIES)
        assertEquals(16_384, HEALTH_MAX_BYTES)
        assertEquals(entries, parseNvidiaHealthFile(text, now))
        assertTrue(text.length < HEALTH_MAX_BYTES)
        // Eight full entries stay far below the size bound.
        val many = (0 until HEALTH_MAX_ENTRIES).map { index ->
            NvidiaHealthEntry("%016x".format(index + 1), streak = 100, rememberedUntilMillis = now + day,
                learnedQualityCeiling = 3, learnedUntilMillis = now + day, failedQualityLevel = 4,
                failedQualityCount = 1_000, extendedRangeDeniedUntilMillis = now + day, updatedAtMillis = now)
        }
        assertTrue(encodeNvidiaHealthFile(many).length < HEALTH_MAX_BYTES)
    }

    @Test fun theParserNeverThrowsAndAnythingUnreadableIsAnEmptyMemory() {
        val now = 1_800_000_000_000L
        val garbage = listOf(
            null, "", " ", "null", "[]", "42", "\"text\"", "{", "{\"version\":1", "not json at all",
            "{\"version\":2,\"entries\":[]}", "{\"version\":\"1\",\"entries\":[]}", "{\"version\":1.5,\"entries\":[]}",
            "{\"version\":null,\"entries\":[]}", "{\"entries\":[]}", "{\"version\":1}", "{\"version\":1,\"entries\":{}}",
            "{\"version\":1,\"entries\":null}", "{\"version\":1,\"entries\":\"x\"}",
            "[" .repeat(5_000), "{\"version\":1,\"entries\":" + "[".repeat(5_000) + "]".repeat(5_000) + "}",
        )
        for (text in garbage) assertEquals(emptyList(), parseNvidiaHealthFile(text, now), text?.take(40) ?: "null")
        // A file over the size bound is not read at all, even when it would be valid: the very same text parses to its
        // one entry without the padding, so only the size bound can make the padded text empty.
        val valid = "{\"version\":1,\"entries\":[{\"key\":\"$keyA\",\"streak\":1,\"updated\":$now}]}"
        assertEquals(listOf(keyA), parseNvidiaHealthFile(valid, now).map { it.key })
        assertTrue(valid.length <= HEALTH_MAX_BYTES)
        val padded = valid + " ".repeat(HEALTH_MAX_BYTES)
        assertTrue(padded.length > HEALTH_MAX_BYTES)
        assertEquals(emptyList(), parseNvidiaHealthFile(padded, now))
    }

    @Test fun aBadEntryIsSkippedAndTheGoodOnesAroundItAreKept() {
        val now = 1_800_000_000_000L
        val good = "{\"key\":\"$keyA\",\"streak\":1,\"updated\":$now}"
        val goodTwo = "{\"key\":\"$keyB\",\"streak\":2,\"updated\":$now}"
        val bad = listOf(
            "7", "null", "\"entry\"", "[]", "{}", "{\"streak\":1,\"updated\":$now}",
            "{\"key\":\"SHORT\",\"streak\":1,\"updated\":$now}",
            "{\"key\":\"00000000000000AA\",\"streak\":1,\"updated\":$now}",
            "{\"key\":\"00000000000000zz\",\"streak\":1,\"updated\":$now}",
            "{\"key\":12345678901234567,\"streak\":1,\"updated\":$now}",
            "{\"key\":\"00000000000000c3\",\"streak\":\"1\",\"updated\":$now}",
            "{\"key\":\"00000000000000c3\",\"streak\":-1,\"updated\":$now}",
            "{\"key\":\"00000000000000c3\",\"streak\":1.5,\"updated\":$now}",
            "{\"key\":\"00000000000000c3\",\"streak\":101,\"updated\":$now}",
            "{\"key\":\"00000000000000c3\",\"streak\":1,\"learned_quality\":5,\"updated\":$now}",
            "{\"key\":\"00000000000000c3\",\"streak\":1,\"failed_count\":1001,\"updated\":$now}",
            "{\"key\":\"00000000000000c3\",\"streak\":1,\"updated\":-5}",
            "{\"key\":\"00000000000000c3\",\"streak\":1,\"updated\":4102444800001}",
            "{\"key\":\"00000000000000c3\",\"streak\":null,\"updated\":$now}",
            "{\"key\":\"00000000000000c3\",\"streak\":true,\"updated\":$now}",
        )
        for (item in bad) {
            val text = "{\"version\":1,\"entries\":[$good,$item,$goodTwo]}"
            val parsed = parseNvidiaHealthFile(text, now)
            assertEquals(listOf(keyA, keyB), parsed.map { it.key }, item)
        }
    }

    @Test fun unknownFieldsAreIgnoredAndAMissingFieldIsZero() {
        val now = 1_800_000_000_000L
        val text = "{\"version\":1,\"future\":{\"a\":1},\"entries\":[{\"key\":\"$keyA\",\"streak\":3,\"updated\":$now," +
            "\"someday\":[1,2,3]}]}"
        val parsed = parseNvidiaHealthFile(text, now)
        assertEquals(1, parsed.size)
        assertEquals(3, parsed.single().streak)
        assertEquals(0L, parsed.single().rememberedUntilMillis)
        assertEquals(0, parsed.single().learnedQualityCeiling)
    }

    @Test fun aRepeatedKeyKeepsTheFirstEntryAndOnlyEightEntriesAreKept() {
        val now = 1_800_000_000_000L
        val items = (1..12).map { "{\"key\":\"%016x\",\"streak\":1,\"updated\":$now}".format(it) }
        val twice = "{\"version\":1,\"entries\":[{\"key\":\"$keyA\",\"streak\":1,\"updated\":$now}," +
            "{\"key\":\"$keyA\",\"streak\":9,\"updated\":$now}]}"
        val parsedTwice = parseNvidiaHealthFile(twice, now)
        assertEquals(1, parsedTwice.size)
        assertEquals(1, parsedTwice.single().streak)
        val parsed = parseNvidiaHealthFile("{\"version\":1,\"entries\":[" + items.joinToString(",") + "]}", now)
        assertEquals(HEALTH_MAX_ENTRIES, parsed.size)
        assertEquals((1..8).map { "%016x".format(it) }, parsed.map { it.key })
    }

    @Test fun expiredAndFutureEntriesAreDroppedWhileReading() {
        val now = 1_800_000_000_000L
        val fresh = "{\"key\":\"$keyA\",\"streak\":1,\"updated\":$now}"
        val old = "{\"key\":\"$keyB\",\"streak\":1,\"updated\":${now - 15 * day}}"
        val future = "{\"key\":\"00000000000000c3\",\"streak\":1,\"updated\":${now + day}}"
        val parsed = parseNvidiaHealthFile("{\"version\":1,\"entries\":[$fresh,$old,$future]}", now)
        assertEquals(listOf(keyA), parsed.map { it.key })
    }

    // ---- In-memory store ------------------------------------------------------------------

    @Test fun anInMemoryStoreRemembersAfterTwoFailuresAndForgetsAKeyOnRequest() {
        val store = DesktopNvidiaHealthStore.inMemory()
        assertEquals(NvidiaHealthView.None, store.view(keyA))
        store.recordDeviceFailure(keyA, atLowestRung = false, failedQuality = 4)
        assertFalse(store.view(keyA).remembered)
        store.recordDeviceFailure(keyA, atLowestRung = false, failedQuality = 4)
        assertTrue(store.view(keyA).remembered)
        assertEquals(3, store.view(keyA).learnedQualityCeiling)
        // Another key is untouched: a foreign key never matches.
        assertEquals(NvidiaHealthView.None, store.view(keyB))
        store.clearKey(keyB)
        assertTrue(store.view(keyA).remembered)
        store.clearKey(keyA)
        assertEquals(NvidiaHealthView.None, store.view(keyA))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test fun theOtherRecordsAndTheHealthyStreakAreVisibleInTheView() {
        val store = DesktopNvidiaHealthStore.inMemory()
        store.recordExtendedRangeDenied(keyA)
        assertTrue(store.view(keyA).extendedRangeDenied)
        assertFalse(store.view(keyA).remembered)
        store.recordRemembered(keyB)
        assertTrue(store.view(keyB).remembered)
        // A healthy stream clears the streak only: the remembered failure stays, an entry with no streak is untouched.
        store.recordDeviceFailure(keyA, atLowestRung = false, failedQuality = 2)
        assertEquals(1, store.snapshot().first { it.key == keyA }.streak)
        store.recordHealthy(keyA)
        assertEquals(0, store.snapshot().first { it.key == keyA }.streak)
        store.recordHealthy(keyB)
        assertTrue(store.view(keyB).remembered)
        // A healthy stream with nothing recorded creates nothing.
        store.recordHealthy("00000000000000c3")
        assertEquals(2, store.snapshot().size)
    }

    @Test fun anInvalidKeyIsIgnoredEverywhere() {
        val store = DesktopNvidiaHealthStore.inMemory()
        for (bad in listOf("", "short", "00000000000000A1", "00000000000000a1x")) {
            store.recordDeviceFailure(bad, atLowestRung = true, failedQuality = 4)
            store.recordRemembered(bad)
            store.recordExtendedRangeDenied(bad)
            store.recordHealthy(bad)
            store.clearKey(bad)
            assertEquals(NvidiaHealthView.None, store.view(bad), bad)
        }
        assertTrue(store.snapshot().isEmpty())
    }

    @Test fun theLeastRecentlyUpdatedEntryGoesFirstWhenMoreThanEightArrive() {
        val clock = HealthStoreTestClock()
        val store = DesktopNvidiaHealthStore.inMemory { clock.now }
        val keys = (1..9).map { "%016x".format(it) }
        for (key in keys) {
            clock.now += 1_000L
            store.recordRemembered(key)
        }
        val kept = store.snapshot().map { it.key }
        assertEquals(HEALTH_MAX_ENTRIES, kept.size)
        assertFalse(keys.first() in kept, "the oldest key was dropped")
        assertEquals(keys.drop(1), kept)
        // Updating an old entry makes it the newest.
        clock.now += 1_000L
        store.recordRemembered(keys[1])
        clock.now += 1_000L
        store.recordRemembered("00000000000000ff")
        val after = store.snapshot().map { it.key }
        assertTrue(keys[1] in after)
        assertFalse(keys[2] in after)
    }

    // ---- File store -----------------------------------------------------------------------

    @Test fun theFileIsWrittenAtomicallyAndAFreshStoreReadsItBack() {
        val clock = HealthStoreTestClock()
        val path = healthFile()
        val store = DesktopNvidiaHealthStore.forFile(path, { clock.now })
        assertFalse(Files.exists(path), "nothing is read or written before the first record")
        store.recordDeviceFailure(keyA, atLowestRung = true, failedQuality = 1)
        assertTrue(store.flushNow())
        assertTrue(Files.isRegularFile(path))
        // The temporary file of the atomic write never stays behind.
        val leftovers = Files.list(path.parent).use { stream ->
            stream.map { it.fileName.toString() }.collect(java.util.stream.Collectors.toList())
        }
        assertEquals(listOf(HEALTH_FILE_NAME), leftovers)
        val text = String(Files.readAllBytes(path), Charsets.UTF_8)
        assertTrue(text.startsWith("{\"version\":1,"), text)
        assertTrue(text.length <= HEALTH_MAX_BYTES)
        assertFalse(text.contains('\r'))

        val second = DesktopNvidiaHealthStore.forFile(path, { clock.now })
        assertTrue(second.view(keyA).remembered)
        assertEquals(store.snapshot(), second.snapshot())
        // Nothing left to write: a second flush is a no-op that still succeeds.
        assertTrue(store.flushNow())
    }

    @Test fun aMissingDirectoryIsCreatedAndAMissingFileIsAnEmptyMemory() {
        val clock = HealthStoreTestClock()
        val path = root.resolve("a").resolve("b").resolve("c").resolve(HEALTH_FILE_NAME)
        val store = DesktopNvidiaHealthStore.forFile(path, { clock.now })
        assertEquals(NvidiaHealthView.None, store.view(keyA))
        store.recordRemembered(keyA)
        assertTrue(store.flushNow())
        assertTrue(Files.isRegularFile(path))
    }

    @Test fun aCorruptOrOversizedFileIsAnEmptyMemoryAndIsReplacedByTheNextWrite() {
        val clock = HealthStoreTestClock()
        // The oversized file is a valid one with a remembered failure plus padding: only the size bound keeps it unread.
        val validOversized = ("{\"version\":1,\"entries\":[{\"key\":\"$keyA\",\"streak\":2," +
            "\"remembered_until\":${clock.now + day},\"updated\":${clock.now}}]}" + " ".repeat(HEALTH_MAX_BYTES)).toByteArray()
        assertTrue(validOversized.size > HEALTH_MAX_BYTES)
        for (content in listOf("this is not json".toByteArray(), ByteArray(HEALTH_MAX_BYTES + 100) { ' '.code.toByte() },
            validOversized, ByteArray(0), byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00))) {
            val path = healthFile()
            Files.createDirectories(path.parent)
            Files.write(path, content)
            val store = DesktopNvidiaHealthStore.forFile(path, { clock.now })
            assertEquals(NvidiaHealthView.None, store.view(keyA))
            assertTrue(store.snapshot().isEmpty())
            store.recordRemembered(keyA)
            assertTrue(store.flushNow())
            assertTrue(DesktopNvidiaHealthStore.forFile(path, { clock.now }).view(keyA).remembered)
            Files.delete(path)
        }
    }

    @Test fun aFileOfAnotherMachineWithAForeignKeyChangesNothingForThisKey() {
        val clock = HealthStoreTestClock()
        val path = healthFile()
        Files.createDirectories(path.parent)
        val foreign = "{\"version\":1,\"entries\":[{\"key\":\"$keyB\",\"streak\":2," +
            "\"remembered_until\":${clock.now + day},\"updated\":${clock.now}}]}"
        Files.write(path, foreign.toByteArray())
        val store = DesktopNvidiaHealthStore.forFile(path, { clock.now })
        assertEquals(NvidiaHealthView.None, store.view(keyA))
        assertTrue(store.view(keyB).remembered)
    }

    @Test fun aWriteThatCannotHappenNeverThrowsAndTheMemoryStillWorksInProcess() {
        val clock = HealthStoreTestClock()
        // The parent of the health file is a regular file, so the directory cannot be created.
        val blocker = root.resolve("blocker")
        Files.write(blocker, byteArrayOf(1))
        // A long interval keeps the automatic retry of the failed write from firing after the temporary directory is gone.
        val store = DesktopNvidiaHealthStore.forFile(blocker.resolve("gpu").resolve(HEALTH_FILE_NAME), { clock.now },
            writeIntervalMillis = 3_600_000L)
        store.recordRemembered(keyA)
        assertFalse(store.flushNow(), "the write failed, and said so")
        assertTrue(store.view(keyA).remembered)
        store.recordDeviceFailure(keyB, atLowestRung = false, failedQuality = 3)
        store.clearKey(keyB)
        assertEquals(1, store.snapshot().size)
    }

    @Test fun writesAreCoalescedOffThreadAndAtMostOneRunsPerInterval() {
        val clock = HealthStoreTestClock()
        val path = healthFile()
        val store = DesktopNvidiaHealthStore.forFile(path, { clock.now }, writeIntervalMillis = 60_000L)
        // The first record is scheduled at once on the daemon thread; the caller never writes.
        store.recordRemembered(keyA)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!Files.isRegularFile(path) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(Files.isRegularFile(path), "the first write happens without a flush")
        val first = String(Files.readAllBytes(path), Charsets.UTF_8)
        // Later records inside the interval wait for it: the file does not change until a flush.
        store.recordRemembered(keyB)
        Thread.sleep(200)
        assertEquals(first, String(Files.readAllBytes(path), Charsets.UTF_8))
        assertTrue(store.flushNow())
        val flushed = String(Files.readAllBytes(path), Charsets.UTF_8)
        assertTrue(flushed.contains(keyA) && flushed.contains(keyB))
    }

    @Test fun aClockThatStepsBackAfterAWriteDoesNotPostponeTheNextWriteBeyondOneInterval() {
        val clock = HealthStoreTestClock()
        val path = healthFile()
        val store = DesktopNvidiaHealthStore.forFile(path, { clock.now }, writeIntervalMillis = 50L)
        store.recordRemembered(keyA)
        assertTrue(awaitFileText(path) { it.contains(keyA) }, "the first write happens without a flush")
        // The wall clock jumps back a day. Unclamped, the next write would wait the old time plus the interval, a whole day.
        clock.now -= day
        store.recordRemembered(keyB)
        assertTrue(awaitFileText(path) { it.contains(keyB) }, "the write after the jump still happens within about one interval")
    }

    @Test fun aChangeThatMeetsARunningWriteIsWrittenByTheWriterWithoutAFlush() {
        val clock = HealthStoreTestClock()
        val path = healthFile()
        val store = DesktopNvidiaHealthStore.forFile(path, { clock.now }, writeIntervalMillis = 1L)
        val keys = (1..HEALTH_MAX_ENTRIES).map { "%016x".format(it) }
        for (key in keys) {
            store.recordRemembered(key)
            Thread.sleep(1)
        }
        // No flush: whichever change arrived while a write was running must still bring the file to the final state.
        assertTrue(awaitFileText(path) { text -> keys.all { text.contains(it) } }, "every change reached the file")
    }

    @Test fun aFailedWriteIsTriedAgainByItselfAndSucceedsOnceTheDirectoryCanBeCreated() {
        val clock = HealthStoreTestClock()
        // A regular file sits where the directory of the health file should be, so the first write cannot happen.
        val blocker = root.resolve("gpu")
        Files.write(blocker, byteArrayOf(1))
        val path = blocker.resolve(HEALTH_FILE_NAME)
        val store = DesktopNvidiaHealthStore.forFile(path, { clock.now }, writeIntervalMillis = 300L)
        store.recordRemembered(keyA)
        Thread.sleep(50)
        Files.delete(blocker)
        assertTrue(awaitFileText(path) { it.contains(keyA) }, "the retry wrote the file without another change or a flush")
        assertTrue(store.flushNow(), "nothing is left to write")
    }

    // ---- Expiry through the store -----------------------------------------------------------

    @Test fun aSkewedOrAdvancedClockExpiresEntriesWithoutAnError() {
        val clock = HealthStoreTestClock()
        val store = DesktopNvidiaHealthStore.forFile(healthFile(), { clock.now })
        store.recordRemembered(keyA)
        assertTrue(store.view(keyA).remembered)
        // A clock that went backwards: the entry now carries a future timestamp, which counts as expired.
        clock.now -= day
        assertEquals(NvidiaHealthView.None, store.view(keyA))
        assertTrue(store.snapshot().isEmpty())
        // A clock far ahead: the cooldown and the whole entry are over.
        clock.now += 40 * day
        store.recordRemembered(keyB)
        clock.now += 2 * day
        assertFalse(store.view(keyB).remembered)
        // Nothing may be left for a timer to write after the temporary directory of the test is gone.
        store.flushNow()
    }

    @Test fun theCooldownEndsAndTheEntryExpiresAfterFourteenDays() {
        val clock = HealthStoreTestClock()
        val store = DesktopNvidiaHealthStore.forFile(healthFile(), { clock.now })
        store.recordDeviceFailure(keyA, atLowestRung = true, failedQuality = 1)
        clock.now += day - 1L
        assertTrue(store.view(keyA).remembered)
        clock.now += 1L
        assertFalse(store.view(keyA).remembered)
        // The streak outlives the cooldown until the entry's own fourteen days are over.
        assertEquals(1, assertNotNull(store.snapshot().singleOrNull()).streak)
        clock.now += 14 * day
        assertNull(store.snapshot().singleOrNull())
        store.flushNow()
    }
}
