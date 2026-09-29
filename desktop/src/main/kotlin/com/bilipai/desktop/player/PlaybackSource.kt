package com.bilipai.desktop.player

/** The two DASH streams are decoded together by one native mpv playback clock. */
data class PlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    val referer: String = "https://www.bilibili.com/",
    val userAgent: String = DEFAULT_USER_AGENT,
    val cookieHeader: String = "",
    val title: String = "BiliPai",
) {
    init {
        require(videoUrl.isNotBlank()) { "Video address is empty" }
        // Header values must stay on one HTTP header line.
        require(listOf(referer, userAgent, cookieHeader).none { '\r' in it || '\n' in it }) {
            "Invalid playback HTTP header"
        }
        require(listOfNotNull(videoUrl, audioUrl, referer, userAgent, cookieHeader, title).none { '\u0000' in it }) {
            "Invalid playback source"
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    }
}

data class PlayerState(
    val ready: Boolean = false,
    val loading: Boolean = false,
    val paused: Boolean = false,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val volume: Double = 75.0,
    val speed: Double = 1.0,
    val ended: Boolean = false,
    val error: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val avSyncSeconds: Double? = null,
    val nativeVersion: String? = null,
)
