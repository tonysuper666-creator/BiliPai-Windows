package com.bilipai.desktop

import com.bilipai.desktop.data.VideoCard
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
private data class LibraryEntry(val bvid: String, val title: String, val cover: String,
    val author: String, val playCount: Long, val duration: Int, val timestamp: Long)
@Serializable
private data class LibraryData(val history: List<LibraryEntry> = emptyList(),
    val favorites: List<LibraryEntry> = emptyList(), val dark: Boolean = false, val automaticUpdates: Boolean = true)

class DesktopLibrary {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val directory = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "BiliPaiWindows")
    private val file = directory.resolve("library.json")
    private var data = runCatching { json.decodeFromString<LibraryData>(Files.readString(file)) }.getOrDefault(LibraryData())
    val dark: Boolean get() = data.dark
    val automaticUpdates: Boolean get() = data.automaticUpdates
    fun history(): List<VideoCard> = data.history.map { it.toCard() }
    fun favorites(): List<VideoCard> = data.favorites.map { it.toCard() }
    fun isFavorite(bvid: String): Boolean = data.favorites.any { it.bvid == bvid }
    fun setDark(dark: Boolean) { data = data.copy(dark = dark); save() }
    fun setAutomaticUpdates(enabled: Boolean) { data = data.copy(automaticUpdates = enabled); save() }
    fun record(card: VideoCard) {
        data = data.copy(history = (listOf(card.toEntry()) + data.history.filter { it.bvid != card.bvid }).take(300))
        save()
    }
    fun toggleFavorite(card: VideoCard) {
        data = data.copy(favorites = if (isFavorite(card.bvid)) data.favorites.filter { it.bvid != card.bvid }
            else listOf(card.toEntry()) + data.favorites)
        save()
    }
    private fun save() {
        Files.createDirectories(directory)
        val temporary = directory.resolve("library.json.tmp")
        Files.writeString(temporary, json.encodeToString(data))
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private fun VideoCard.toEntry() = LibraryEntry(bvid, title, cover, author, playCount, duration, System.currentTimeMillis())
    private fun LibraryEntry.toCard() = VideoCard(bvid, title, cover, author, playCount, duration)
}
