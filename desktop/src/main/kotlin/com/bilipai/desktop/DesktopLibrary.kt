package com.bilipai.desktop

import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.data.DesktopSearchPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
private data class LibraryEntry(val bvid: String, val title: String, val cover: String,
    val author: String, val playCount: Long, val duration: Int, val timestamp: Long,
    val progressSeconds: Int? = null, val preferredCid: Long = 0, val pageIndex: Int = 0, val authorMid: Long = 0)
@Serializable
private data class LibraryData(val history: List<LibraryEntry> = emptyList(),
    val favorites: List<LibraryEntry> = emptyList(), val dark: Boolean = false, val automaticUpdates: Boolean = true)

class DesktopLibrary(private val directory: Path = Path.of(
    System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "BiliPaiWindows"),
    private val privacyModeEnabled: () -> Boolean = { DesktopSearchPreferences.readPrivacyModeEnabledSync() }) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = directory.resolve("library.json")
    private var data = runCatching { json.decodeFromString<LibraryData>(Files.readString(file)) }.getOrDefault(LibraryData())
    /** An absent old preference must keep the original FOLLOW_SYSTEM default. */
    internal val storedDark: Boolean? = runCatching {
        ((json.parseToJsonElement(Files.readString(file)) as? JsonObject)?.get("dark") as? JsonPrimitive)?.booleanOrNull
    }.getOrNull()
    val dark: Boolean get() = data.dark
    val automaticUpdates: Boolean get() = data.automaticUpdates
    @Synchronized fun history(): List<VideoCard> = data.history.map { it.toCard() }
    @Synchronized fun favorites(): List<VideoCard> = data.favorites.map { it.toCard() }
    @Synchronized fun resumeCard(bvid: String): VideoCard? = data.history.firstOrNull { it.bvid == bvid }?.toCard()
    @Synchronized fun isFavorite(bvid: String): Boolean = data.favorites.any { it.bvid == bvid }
    @Synchronized fun setDark(dark: Boolean) { save(data.copy(dark = dark)) }
    @Synchronized fun setAutomaticUpdates(enabled: Boolean) { save(data.copy(automaticUpdates = enabled)) }
    @Synchronized fun record(card: VideoCard) {
        if (privacyModeEnabled()) return
        val previous = data.history.firstOrNull { it.bvid == card.bvid }
        val entry = card.toEntry().let {
            if (card.progressSeconds == null && previous != null) it.copy(progressSeconds = previous.progressSeconds,
                preferredCid = previous.preferredCid, pageIndex = previous.pageIndex) else it
        }
        save(data.copy(history = (listOf(entry) + data.history.filter { it.bvid != card.bvid }).take(300)))
    }
    @Synchronized fun checkpoint(bvid: String, cid: Long, part: Int, positionSeconds: Double) {
        if (!positionSeconds.isFinite() || positionSeconds < 0 || cid <= 0 || part < 0) return
        if (privacyModeEnabled()) return
        if (data.history.none { it.bvid == bvid }) return
        save(data.copy(history = data.history.map {
            if (it.bvid == bvid) it.copy(progressSeconds = positionSeconds.toInt(), preferredCid = cid, pageIndex = part) else it
        }))
    }
    @Synchronized fun toggleFavorite(card: VideoCard) {
        save(data.copy(favorites = if (isFavorite(card.bvid)) data.favorites.filter { it.bvid != card.bvid }
            else listOf(card.toEntry()) + data.favorites))
    }
    /** Commit the immutable candidate only after persistence succeeds under the caller's lock. */
    private fun save(candidate: LibraryData) {
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "library-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(candidate))
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Exception) {
            try { Files.deleteIfExists(temporary) }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
        data = candidate
    }
    private fun VideoCard.toEntry() = LibraryEntry(bvid, title, cover, author, playCount, duration, System.currentTimeMillis(),
        progressSeconds, preferredCid, pageIndex, authorMid)
    private fun LibraryEntry.toCard() = VideoCard(bvid, title, cover, author, playCount, duration,
        progressSeconds, preferredCid, pageIndex, timestamp, authorMid = authorMid)
    companion object {
        fun directoryForAccount(mid: Long?): Path {
            val base = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "BiliPaiWindows")
            return if (mid != null && mid > 0) base.resolve("accounts").resolve(mid.toString()) else base
        }
    }
}
