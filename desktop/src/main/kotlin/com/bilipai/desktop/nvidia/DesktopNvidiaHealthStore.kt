package com.bilipai.desktop.nvidia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

// Machine-local failure memory of the NVIDIA enhancement (design section 9.4).
//
// One small JSON file under the local application data directory. It is never part of the plugin store, and
// the backup and restore code never reads or writes it: a restored backup must not import the failures of
// another machine. The parser is tolerant: anything it cannot read means an empty memory, and a bad entry is
// skipped. Writes are atomic (temporary file, flush to disk, move), happen on a daemon thread, and are
// coalesced to at most one per five seconds. Nothing here ever throws into playback and nothing here is a
// presentation proof.
//
// Two deliberate deviations from design 9.4, recorded here for Root to confirm or reverse: a bad entry is skipped
// while the good entries around it are kept (the design says any parse error means empty), and a learned quality
// ceiling is written only after two distinct sources failed at the same level (the design also says "or on a hard
// code", which is not implemented).

// HEALTH-CONSTANTS-BEGIN
internal const val HEALTH_SCHEMA_VERSION: Int = 1

/** At most this many entries are kept; the least recently updated one goes first. */
internal const val HEALTH_MAX_ENTRIES: Int = 8

/** A file larger than this is not read, and a snapshot larger than this is trimmed before it is written. */
internal const val HEALTH_MAX_BYTES: Int = 16_384

/** At most one write per this interval. */
internal const val HEALTH_WRITE_MIN_INTERVAL_MILLIS: Long = 5_000L

/** A write that failed is tried again by itself this many times, one interval apart. A later change tries once more. */
internal const val HEALTH_WRITE_MAX_RETRIES: Int = 3

internal const val HEALTH_DIRECTORY_NAME: String = "BiliPaiWindows"
internal const val HEALTH_SUBDIRECTORY_NAME: String = "gpu"
internal const val HEALTH_FILE_NAME: String = "nvidia-enhancement-health.json"

/** The highest timestamp a file may carry: January 1 of the year 2100 in milliseconds. */
internal const val HEALTH_MAX_TIMESTAMP_MILLIS: Long = 4_102_444_800_000L
// HEALTH-CONSTANTS-END

// ---- Codec ---------------------------------------------------------------------------------

/** The exact text of one file. Keys are validated hex and every other field is a number, so nothing needs escaping. */
internal fun encodeNvidiaHealthFile(entries: List<NvidiaHealthEntry>): String {
    val builder = StringBuilder()
    builder.append("{\"version\":").append(HEALTH_SCHEMA_VERSION).append(",\"entries\":[")
    var first = true
    for (entry in entries) {
        if (!nvidiaHealthKeyValid(entry.key)) continue
        if (!first) builder.append(',')
        first = false
        builder.append("{\"key\":\"").append(entry.key).append('"')
        builder.append(",\"streak\":").append(entry.streak)
        builder.append(",\"remembered_until\":").append(entry.rememberedUntilMillis)
        builder.append(",\"learned_quality\":").append(entry.learnedQualityCeiling)
        builder.append(",\"learned_until\":").append(entry.learnedUntilMillis)
        builder.append(",\"failed_quality\":").append(entry.failedQualityLevel)
        builder.append(",\"failed_count\":").append(entry.failedQualityCount)
        builder.append(",\"extended_denied_until\":").append(entry.extendedRangeDeniedUntilMillis)
        builder.append(",\"updated\":").append(entry.updatedAtMillis)
        builder.append('}')
    }
    builder.append("]}")
    return builder.toString()
}

/**
 * Reads a file text. Never throws: a missing text, a text over the size bound, anything that is not JSON, a
 * wrong schema version and a wrong shape all give an empty list. A single bad or expired entry is skipped, a
 * repeated key keeps its first entry, and at most eight entries are returned.
 */
internal fun parseNvidiaHealthFile(text: String?, nowMillis: Long): List<NvidiaHealthEntry> {
    if (text == null || text.length > HEALTH_MAX_BYTES) return emptyList()
    try {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val version = root["version"] as? JsonPrimitive ?: return emptyList()
        if (version.isString || version.longOrNull != HEALTH_SCHEMA_VERSION.toLong()) return emptyList()
        val array = root["entries"] as? JsonArray ?: return emptyList()
        val result = ArrayList<NvidiaHealthEntry>()
        for (element in array) {
            val item = element as? JsonObject ?: continue
            val entry = parseNvidiaHealthEntry(item) ?: continue
            val normalized = nvidiaHealthNormalize(entry, nowMillis) ?: continue
            if (result.any { it.key == normalized.key }) continue
            result.add(normalized)
            if (result.size >= HEALTH_MAX_ENTRIES) break
        }
        return result
    } catch (_: Throwable) {
        return emptyList()
    }
}

private fun parseNvidiaHealthEntry(item: JsonObject): NvidiaHealthEntry? {
    val keyElement = item["key"] as? JsonPrimitive ?: return null
    if (!keyElement.isString) return null
    val key = keyElement.content
    if (!nvidiaHealthKeyValid(key)) return null

    // A missing field is zero; a field of the wrong type or outside its bounds rejects the entry.
    fun number(name: String, high: Long): Long? {
        val element = item[name] ?: return 0L
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val value = primitive.longOrNull ?: return null
        return if (value in 0L..high) value else null
    }

    val streak = number("streak", GUARD_MAX_STREAK.toLong()) ?: return null
    val remembered = number("remembered_until", HEALTH_MAX_TIMESTAMP_MILLIS) ?: return null
    val learnedQuality = number("learned_quality", 4L) ?: return null
    val learnedUntil = number("learned_until", HEALTH_MAX_TIMESTAMP_MILLIS) ?: return null
    val failedQuality = number("failed_quality", 4L) ?: return null
    val failedCount = number("failed_count", GUARD_MAX_SOURCE_COUNT.toLong()) ?: return null
    val deniedUntil = number("extended_denied_until", HEALTH_MAX_TIMESTAMP_MILLIS) ?: return null
    val updated = number("updated", HEALTH_MAX_TIMESTAMP_MILLIS) ?: return null
    return NvidiaHealthEntry(
        key = key,
        streak = streak.toInt(),
        rememberedUntilMillis = remembered,
        learnedQualityCeiling = learnedQuality.toInt(),
        learnedUntilMillis = learnedUntil,
        failedQualityLevel = failedQuality.toInt(),
        failedQualityCount = failedCount.toInt(),
        extendedRangeDeniedUntilMillis = deniedUntil,
        updatedAtMillis = updated,
    )
}

// ---- Store ---------------------------------------------------------------------------------

/**
 * The failure memory. The machine-local instance reads its file lazily, once, and writes it on a daemon thread
 * named BiliPai-gpu-health. The in-memory instance (the default of a monitor, and what tests get) never touches
 * the disk. Every method answers "nothing remembered" or does nothing when something goes wrong.
 */
internal class DesktopNvidiaHealthStore private constructor(
    private val file: Path?,
    private val clock: () -> Long,
    private val writeIntervalMillis: Long,
    private val executor: ScheduledThreadPoolExecutor?,
) {
    // Lock order: writeLock, then lock. Only the writer takes writeLock; every other method takes lock alone.
    private val writeLock = Any()
    private val lock = Any()
    private val entries = LinkedHashMap<String, NvidiaHealthEntry>()
    private var loaded = false
    private var dirty = false
    private var lastWriteAtMillis: Long? = null
    private var scheduled: ScheduledFuture<*>? = null
    private var failedWrites = 0

    /** What is known about this key right now, with expiry applied. */
    fun view(key: String): NvidiaHealthView {
        if (!nvidiaHealthKeyValid(key)) return NvidiaHealthView.None
        return try {
            synchronized(lock) {
                ensureLoadedLocked()
                val now = clock()
                val entry = entries[key]
                if (entry != null && nvidiaHealthNormalize(entry, now) == null) {
                    entries.remove(key)
                    markDirtyLocked()
                }
                nvidiaHealthViewOf(entries[key], now)
            }
        } catch (_: Throwable) {
            NvidiaHealthView.None
        }
    }

    /** A hard device failure ended a source. Two of them, or one at the lowest rung, remember the failure. */
    fun recordDeviceFailure(key: String, atLowestRung: Boolean, failedQuality: Int) {
        mutate(key) { previous, now -> nvidiaHealthAfterDeviceFailure(previous, key, now, atLowestRung, failedQuality) }
    }

    /** A component contract failure: remembered at once. */
    fun recordRemembered(key: String) {
        mutate(key) { previous, now -> nvidiaHealthAfterRemember(previous, key, now) }
    }

    /** A failure beyond the super resolution box: the extended range is denied. Never a remembered failure. */
    fun recordExtendedRangeDenied(key: String) {
        mutate(key) { previous, now -> nvidiaHealthAfterExtendedRange(previous, key, now) }
    }

    /** A stream was shown for 30 seconds: the failure streak is over. */
    fun recordHealthy(key: String) {
        mutate(key) { previous, now -> nvidiaHealthAfterHealthy(previous, now) }
    }

    /** The user asked for a new detection, or switched the feature off: forget this key. */
    fun clearKey(key: String) {
        if (!nvidiaHealthKeyValid(key)) return
        try {
            synchronized(lock) {
                ensureLoadedLocked()
                if (entries.remove(key) != null) markDirtyLocked()
            }
        } catch (_: Throwable) {
            // Forgetting is best effort; the memory simply stays as it is.
        }
    }

    /** The entries as they are now, expired ones removed. For tests and diagnostics. */
    fun snapshot(): List<NvidiaHealthEntry> = try {
        synchronized(lock) {
            ensureLoadedLocked()
            val now = clock()
            val result = ArrayList<NvidiaHealthEntry>()
            for (entry in entries.values) {
                val normalized = nvidiaHealthNormalize(entry, now)
                if (normalized != null) result.add(normalized)
            }
            result
        }
    } catch (_: Throwable) {
        emptyList()
    }

    /** Reads the file now if that has not happened yet, so the first record or lookup finds the memory loaded. Never throws. */
    fun warmUp() {
        try {
            synchronized(lock) { ensureLoadedLocked() }
        } catch (_: Throwable) {
            // The memory then loads on first use, as before.
        }
    }

    /** Writes the pending snapshot now, on the calling thread, whatever the interval. True when nothing is left to write. */
    fun flushNow(): Boolean = persistNow()

    private fun mutate(key: String, transform: (NvidiaHealthEntry?, Long) -> NvidiaHealthEntry?) {
        if (!nvidiaHealthKeyValid(key)) return
        try {
            synchronized(lock) {
                ensureLoadedLocked()
                val now = clock()
                val stored = entries[key]
                val previous = if (stored == null) null else nvidiaHealthNormalize(stored, now)
                val updated = transform(previous, now)
                // Nothing changed (both absent, or the very same entry): no write is needed.
                if (updated == stored) return
                entries.remove(key)
                if (updated != null) {
                    // Re-inserting puts the entry last, so the first entry is always the least recently updated.
                    entries[key] = updated
                    while (entries.size > HEALTH_MAX_ENTRIES) removeOldestLocked()
                }
                markDirtyLocked()
            }
        } catch (_: Throwable) {
            // A failed update is lost; playback never depends on the memory.
        }
    }

    private fun removeOldestLocked() {
        val iterator = entries.keys.iterator()
        if (!iterator.hasNext()) return
        iterator.next()
        iterator.remove()
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val path = file ?: return
        val text = try {
            if (Files.isRegularFile(path) && Files.size(path) <= HEALTH_MAX_BYTES.toLong()) {
                String(Files.readAllBytes(path), Charsets.UTF_8)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
        for (entry in parseNvidiaHealthFile(text, clock())) entries[entry.key] = entry
    }

    private fun markDirtyLocked() {
        dirty = true
        if (file == null) return
        val pool = executor ?: return
        val waiting = scheduled
        if (waiting != null && !waiting.isDone) return
        val last = lastWriteAtMillis
        // The wait never exceeds one interval. While a write waits, later changes do not schedule their own, so a wall
        // clock that went backwards must not push that one write far into the future and leave every change unwritten.
        val wait = if (last == null) 0L else (last + writeIntervalMillis - clock()).coerceIn(0L, maxOf(0L, writeIntervalMillis))
        scheduled = try {
            pool.schedule(Runnable { runScheduledWrite() }, wait, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The task on the writer thread: one write, then, when something changed while it ran (or the write failed and
     * retries are left), the next one is scheduled. A change that arrives while a write is running schedules nothing
     * itself, so without this it would wait for the next change or the flush on close.
     */
    private fun runScheduledWrite() {
        try {
            persistNow()
        } catch (_: Throwable) {
            // persistNow answers failures itself; this only keeps the executor thread alive.
        }
        try {
            synchronized(lock) {
                scheduled = null
                if (dirty && failedWrites <= HEALTH_WRITE_MAX_RETRIES) markDirtyLocked()
            }
        } catch (_: Throwable) {
            // The next change or the flush on close writes the memory.
        }
    }

    /** The snapshot text, trimmed from the oldest entry until it fits the size bound. */
    private fun snapshotTextLocked(): String {
        val values = ArrayList<NvidiaHealthEntry>(entries.values)
        var text = encodeNvidiaHealthFile(values)
        while (values.size > 1 && text.length > HEALTH_MAX_BYTES) {
            values.removeAt(0)
            text = encodeNvidiaHealthFile(values)
        }
        return text
    }

    /**
     * One write at a time: a flush waits for a write that is already running, so it never returns before the file
     * is complete. The snapshot is taken under the state lock; the disk is written outside it.
     */
    private fun persistNow(): Boolean {
        val path = file ?: return true
        return synchronized(writeLock) {
            val text: String? = try {
                synchronized(lock) {
                    if (!dirty) {
                        null
                    } else {
                        dirty = false
                        lastWriteAtMillis = clock()
                        snapshotTextLocked()
                    }
                }
            } catch (_: Throwable) {
                null
            }
            if (text == null) {
                true
            } else {
                try {
                    writeAtomically(path, text.toByteArray(Charsets.UTF_8))
                    try {
                        synchronized(lock) { failedWrites = 0 }
                    } catch (_: Throwable) {
                        // The counter only limits retries.
                    }
                    true
                } catch (_: Throwable) {
                    try {
                        synchronized(lock) {
                            failedWrites++
                            dirty = true
                        }
                    } catch (_: Throwable) {
                        // Nothing more can be done for a failed write.
                    }
                    false
                }
            }
        }
    }

    companion object {
        /** Nothing is read or written. This is what a monitor and every test gets unless a file is asked for. */
        fun inMemory(clock: () -> Long = { System.currentTimeMillis() }): DesktopNvidiaHealthStore =
            DesktopNvidiaHealthStore(null, clock, HEALTH_WRITE_MIN_INTERVAL_MILLIS, null)

        /**
         * The file under the local application data directory of this machine. Without that directory the memory
         * stays in process: the failures of one run are then forgotten at exit, which is the safe direction.
         */
        fun machineLocal(): DesktopNvidiaHealthStore {
            val base = System.getenv("LOCALAPPDATA")
            if (base == null || base.isBlank()) return inMemory()
            val path = try {
                Path.of(base, HEALTH_DIRECTORY_NAME, HEALTH_SUBDIRECTORY_NAME, HEALTH_FILE_NAME)
            } catch (_: Throwable) {
                return inMemory()
            }
            return forFile(path)
        }

        /** A store backed by this file. The path is given by the caller, which tests use with a temporary directory. */
        internal fun forFile(
            path: Path,
            clock: () -> Long = { System.currentTimeMillis() },
            writeIntervalMillis: Long = HEALTH_WRITE_MIN_INTERVAL_MILLIS,
        ): DesktopNvidiaHealthStore {
            val factory = ThreadFactory { task ->
                val thread = Thread(task, "BiliPai-gpu-health")
                thread.isDaemon = true
                thread
            }
            return DesktopNvidiaHealthStore(path, clock, writeIntervalMillis, ScheduledThreadPoolExecutor(1, factory))
        }

        private fun writeAtomically(path: Path, bytes: ByteArray) {
            val parent = path.toAbsolutePath().normalize().parent ?: throw IllegalStateException("The health file has no directory.")
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, "nvidia-health-", ".tmp")
            try {
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
