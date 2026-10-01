package com.android.purebilibili.data.repository
import kotlinx.serialization.Serializable
object DesktopOriginalExternalPlaylistSchema {
    enum class Source(val label: String) {
        NETEASE("网易云歌单"),
        QQ("QQ音乐歌单")
    }

    @Serializable
    data class ExternalTrack(
        val title: String,
        val artists: List<String> = emptyList(),
        val album: String = "",
        val durationMs: Long = 0L,
        val coverUrl: String = "",
        val translatedTitle: String? = null
    )

    @Serializable
    data class ExternalPlaylistMeta(
        val source: Source,
        val playlistId: String,
        val name: String,
        val coverUrl: String = "",
        val author: String = "",
        val tracks: List<ExternalTrack> = emptyList()
    )

    @Serializable
    data class MatchedVideo(
        val bvid: String,
        val title: String,
        val cover: String = "",
        val author: String = "",
        val durationSec: Long = 0L
    )

    data class MatchOutcome(
        val track: ExternalTrack,
        val video: MatchedVideo?
    )

    data class ImportCheckpoint(
        val playlist: ExternalPlaylistMeta,
        val outcomes: List<MatchOutcome>,
        val completedCount: Int,
    )

    fun parsePlaylistInput(input: String): Pair<Source, String>? {
        val text = input.trim()
        if (text.isEmpty()) return null
        return when {
            text.contains("163.com") || text.contains("music.163") -> {
                extractId(text, listOf("id=", "/playlist/", "#/playlist"))?.let { Source.NETEASE to it }
                    ?: text.filter(Char::isDigit).takeIf { it.isNotEmpty() }?.let { Source.NETEASE to it }
            }
            text.contains("qq.com") || text.contains("y.qq.com") -> {
                extractId(text, listOf("dissid=", "id=", "/playlist/"))?.let { Source.QQ to it }
                    ?: text.filter(Char::isDigit).takeIf { it.isNotEmpty() }?.let { Source.QQ to it }
            }
            text.matches(Regex("\\d{4,}")) -> null // 纯数字无法判断来源，交给调用方指定
            else -> null
        }
    }

    private fun extractId(text: String, markers: List<String>): String? {
        for (marker in markers) {
            val index = text.indexOf(marker)
            if (index < 0) continue
            val rest = text.substring(index + marker.length)
            val id = rest.takeWhile { it.isDigit() }
            if (id.isNotEmpty()) return id
        }
        return null
    }

    // ---------------------------------------------------------------- 歌单拉取

    fun buildSearchQueryForManualMatch(track: ExternalTrack): String {
        return buildString {
            append(track.title)
            val artists = track.artists.joinToString(" ")
            if (artists.isNotBlank()) append(' ').append(artists)
        }
    }

}
