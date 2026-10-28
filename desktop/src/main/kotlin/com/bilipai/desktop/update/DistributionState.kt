package com.bilipai.desktop.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID

/** A random local installation ID and monotonic signed metadata generations. No account data. */
internal class DistributionState(private val root: Path, private val channel: String) {
    @Serializable private data class Seen(val sequence: Long, val digest: String)
    @Serializable private data class State(val installationId: String, val seen: Map<String, Seen> = emptyMap())
    private val json = Json

    private fun <T> locked(action: (State) -> Pair<State, T>): T {
        require(channel in setOf("dev", "stable"))
        val verified = UpdateStorage.verifiedRoot(root)
        val lock = verified.resolve("distribution.lock")
        if (Files.exists(lock, NOFOLLOW_LINKS)) UpdateStorage.existingPathWithoutLinks(lock)
        FileChannel.open(lock, CREATE, WRITE, NOFOLLOW_LINKS).use { handle -> handle.lock().use {
            val file = verified.resolve("distribution-state.json")
            val state = if (Files.exists(file, NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 16 * 1024)
                UpdateStorage.existingPathWithoutLinks(file)
                json.decodeFromString<State>(Files.readString(file))
            } else State(UUID.randomUUID().toString())
            require(UUID.fromString(state.installationId).toString() == state.installationId)
            val (next, result) = action(state)
            if (next != state || !Files.exists(file, NOFOLLOW_LINKS)) {
                val temp = Files.createTempFile(verified, "distribution-", ".tmp")
                try {
                    Files.writeString(temp, json.encodeToString(next))
                    FileChannel.open(temp, WRITE).use { it.force(true) }
                    Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
                } finally { Files.deleteIfExists(temp) }
            }
            return result
        } }
    }

    fun installationId(): String = locked { it to it.installationId }

    fun accept(kind: String, sequence: Long, raw: String) = locked { state ->
        require(kind in setOf("manifest", "config") && sequence > 0)
        val key = "$channel/$kind"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val old = state.seen[key]
        require(old == null || sequence > old.sequence || sequence == old.sequence && digest == old.digest) {
            "更新元数据被回退或同一序号内容被替换"
        }
        state.copy(seen = state.seen + (key to Seen(sequence, digest))) to Unit
    }
}
