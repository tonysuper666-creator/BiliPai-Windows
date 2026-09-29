package com.bilipai.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object StartupHealth {
    suspend fun await(
        process: Process,
        healthFile: Path,
        token: String,
        timeoutMs: Long = DesktopUpdater.STARTUP_TIMEOUT_MS,
        stabilityMs: Long = DesktopUpdater.STARTUP_STABILITY_MS,
        pollMs: Long = 100,
        expectedVersion: String? = null,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var acknowledgedAt: Long? = null
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            // A marker left by a process that immediately crashed cannot activate that installation.
            if (!process.isAlive) return false
            val acknowledged = Files.isRegularFile(healthFile, NOFOLLOW_LINKS) && !Files.isSymbolicLink(healthFile) &&
                Files.size(healthFile) < 256 && Files.readString(healthFile).trim() == token
            if (acknowledged) {
                if (expectedVersion != null) {
                    val versionFile = healthFile.resolveSibling("startup-version.txt")
                    // New launchers write version first. A valid token without the expected version is not healthy.
                    if (!Files.isRegularFile(versionFile, NOFOLLOW_LINKS) || Files.isSymbolicLink(versionFile) || Files.size(versionFile) >= 256) return false
                    val actual = DesktopVersion.parse(Files.readString(versionFile).trim())
                    val expected = DesktopVersion.parse(expectedVersion)
                    if (actual == null || expected == null || actual.compareTo(expected) != 0) return false
                }
                val now = System.nanoTime()
                if (acknowledgedAt == null) acknowledgedAt = now
                if (now - acknowledgedAt >= TimeUnit.MILLISECONDS.toNanos(stabilityMs)) return process.isAlive
            } else acknowledgedAt = null
            delay(pollMs)
        }
        return false
    }

    suspend fun terminate(process: Process) = withContext(NonCancellable + Dispatchers.IO) {
        // Only descendants observed from this directly launched process are eligible. Never scan by name or title.
        val descendants = runCatching { process.toHandle().descendants().use { it.toList() } }.getOrDefault(emptyList())
        descendants.asReversed().forEach { child -> runCatching { if (child.isAlive) child.destroy() } }
        if (process.isAlive) process.destroy()
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }
            descendants.asReversed().forEach { child ->
                runCatching {
                    if (child.isAlive) child.destroyForcibly()
                    child.onExit().get(1, TimeUnit.SECONDS)
                }
            }
        } catch (_: InterruptedException) {
            descendants.forEach { child -> runCatching { if (child.isAlive) child.destroyForcibly() } }
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }
}
