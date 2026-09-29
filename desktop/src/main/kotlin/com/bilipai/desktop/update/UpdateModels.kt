package com.bilipai.desktop.update

import java.nio.file.Path

data class WindowsUpdate(
    val version: String,
    val releaseId: Long,
    val assetId: Long,
    val assetName: String,
    val size: Long,
    val downloadUrl: String,
    val checksumUrl: String,
    val releaseUrl: String,
)

/** A verified installation owned by the updater that prepared it. No process is started yet. */
class PreparedUpdate internal constructor(
    val update: WindowsUpdate,
    internal val stagingDirectory: Path,
    internal val executable: Path,
)

sealed interface UpdateState {
    data class Disabled(val reason: String) : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val update: WindowsUpdate) : UpdateState
    data class Downloading(val receivedBytes: Long, val totalBytes: Long) : UpdateState
    data object Verifying : UpdateState
    data class Prepared(val prepared: PreparedUpdate) : UpdateState
    data object Launching : UpdateState
    data object Launched : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Numeric desktop revisions are significant even when the upstream Android tag is unchanged. */
internal data class DesktopVersion private constructor(
    val numbers: List<Long>,
    val prerelease: List<String>,
    val revision: List<Long>,
) : Comparable<DesktopVersion> {
    override fun compareTo(other: DesktopVersion): Int {
        compareNumbers(numbers, other.numbers).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() && other.prerelease.isNotEmpty()) return 1
        if (prerelease.isNotEmpty() && other.prerelease.isEmpty()) return -1
        for (index in 0 until maxOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease.getOrNull(index) ?: return -1
            val b = other.prerelease.getOrNull(index) ?: return 1
            val numberA = a.toLongOrNull()
            val numberB = b.toLongOrNull()
            val comparison = when {
                numberA != null && numberB != null -> numberA.compareTo(numberB)
                numberA != null -> -1
                numberB != null -> 1
                else -> a.compareTo(b, ignoreCase = true)
            }
            if (comparison != 0) return comparison
        }
        return compareNumbers(revision, other.revision)
    }

    companion object {
        private val versionPattern = Regex("^(?:windows[-_])?v?(\\d+(?:\\.\\d+)+)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$", RegexOption.IGNORE_CASE)
        fun parse(raw: String): DesktopVersion? {
            val match = versionPattern.matchEntire(raw.trim()) ?: return null
            val numbers = match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
            val prerelease = match.groupValues[2].takeIf { it.isNotBlank() }?.split('.', '-') ?: emptyList()
            val revision = match.groupValues[3].split('.', '-').mapNotNull { it.toLongOrNull() }
            return DesktopVersion(numbers, prerelease, revision)
        }
        private fun compareNumbers(a: List<Long>, b: List<Long>): Int {
            for (index in 0 until maxOf(a.size, b.size)) {
                val comparison = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
                if (comparison != 0) return comparison
            }
            return 0
        }
    }
}
