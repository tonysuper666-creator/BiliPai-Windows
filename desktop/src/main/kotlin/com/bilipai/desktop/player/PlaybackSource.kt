package com.bilipai.desktop.player

/** Source values used by the app's authorized proxy; default logging deliberately contains no source or headers. */
internal class OwnedPlaybackSourceSnapshot(val sourceVersion: Long, val source: PlaybackSource) {
    override fun toString(): String = "OwnedPlaybackSourceSnapshot(sourceVersion=$sourceVersion)"
}

/** The two DASH streams are decoded together by one native mpv playback clock. */
data class PlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    val referer: String = "https://www.bilibili.com/",
    val userAgent: String = DEFAULT_USER_AGENT,
    val cookieHeader: String = "",
    val title: String = "BiliPai",
    val startPositionSeconds: Double = 0.0,
    val startPaused: Boolean = false,
    val progressiveSegments: List<PlaybackSegment> = emptyList(),
    /** Explicit stream request properties, separate from the app account Cookie jar. */
    val streamHeaders: Map<String, String> = emptyMap(),
    val authorizationReceipt: com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt? = null,
    val primaryAccountEpoch: Long? = null,
    val nativePublication: DesktopNativePlaybackPublication? = null,
    /** Native-only local byte ingress; original remote fields stay authoritative. */
    val nativeTransport: com.bilipai.desktop.player.cache.DesktopNativeMediaTransport? = null,
) {
    init {
        copyPlaybackStreamHeaders(streamHeaders)
        require(videoUrl.isNotBlank()) { "Video address is empty" }
        require(progressiveSegments.size <= 1_000) { "Too many progressive video segments" }
        require(progressiveSegments.isEmpty() || audioUrl == null) { "Progressive segments already contain their audio track" }
        require(startPositionSeconds.isFinite() && startPositionSeconds >= 0) { "Invalid playback start position" }
        // Header values must stay on one HTTP header line.
        require(listOf(referer, userAgent, cookieHeader).none { '\r' in it || '\n' in it }) {
            "Invalid playback HTTP header"
        }
        require(listOfNotNull(videoUrl, audioUrl, referer, userAgent, cookieHeader, title).none { '\u0000' in it }) {
            "Invalid playback source"
        }
    }

    override fun toString(): String = "PlaybackSource(separateAudio=${audioUrl != null}, progressiveSegments=${progressiveSegments.size}, startPositionSeconds=$startPositionSeconds, startPaused=$startPaused)"

    /** mpv EDL exposes all progressive segments as one continuous duration/seek clock. */
    internal val nativeLoadUrl: String
        get() = nativeTransport?.nativeVideo(this) ?: if (progressiveSegments.isEmpty()) videoUrl else "edl://" + progressiveSegments.joinToString(";") { segment ->
            val address = segment.nativeUrl
            val escaped = "%${address.toByteArray(Charsets.UTF_8).size}%$address"
            if (segment.durationSeconds == null) escaped else "$escaped,0,${segment.durationSeconds}"
        }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    }
}

/** An original durl entry; duration is seconds (the upstream API reports milliseconds). */
data class PlaybackSegment(val url: String, val durationSeconds: Double? = null) {
    /** java.io.File produces file:/C:/..., while mpv recognizes file URLs by the :// delimiter. */
    internal val nativeUrl: String
        get() = if (url.startsWith("file:/", ignoreCase = true) && !url.startsWith("file://", ignoreCase = true))
            "file://" + url.substringAfter(':') else url

    init {
        require(url.isNotBlank() && '\u0000' !in url && '\r' !in url && '\n' !in url) { "Invalid progressive segment address" }
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val windowsPath = Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(url)
        require(windowsPath || (uri != null && (uri.scheme?.lowercase() in setOf("http", "https", "file") || uri.scheme == null))) {
            "Unsupported progressive segment protocol"
        }
        require(durationSeconds == null || (durationSeconds.isFinite() && durationSeconds > 0 && durationSeconds <= 7 * 24 * 60 * 60)) {
            "Invalid progressive segment duration"
        }
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
    val muted: Boolean = false,
    val audioOnly: Boolean = false,
    val looping: Boolean = false,
    val subtitlesVisible: Boolean = true,
    val subtitleText: String? = null,
    val secondarySubtitleText: String? = null,
    val tracks: List<PlayerTrack> = emptyList(),
    val operationError: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val sourceTitle: String = "BiliPai",
    val failure: PlayerFailure? = null,
    val softwareDecodingRequested: Boolean = false,
    val hardwareDecoder: String? = null,
    /** Persistent user intent; softwareDecodingRequested is a separate per-source failure guard. */
    val hardwareDecodeEnabled: Boolean = true,
    val seekCompletedId: Long = 0,
    val seekCompletedPositionSeconds: Double? = null,
    /** Retained zoom intent, reapplied when the native surface is recreated. */
    val videoPanscan: Double = 0.0,
    /** Actual native readback is separate from retained intent. */
    val activeVideoPanscan: Double? = null,
    /** Playback-restart with an actual video output frame configured, rather than DLL initialization. */
    val firstVideoFrameReady: Boolean = false,
    val pausedForCache: Boolean = false,
    /** Unknown cache duration remains null and cannot trigger a zero-buffer recovery decision. */
    val bufferedForwardSeconds: Double? = null,
    /** Actual per-source pause readback, published together with the native playback position. */
    val nativePaused: Boolean? = null,
    /** Actual packet bitrate readback from the current MPV worker, bits/sec. */
    val videoBitrateBps: Long? = null,
    val audioBitrateBps: Long? = null,
)

data class PlayerTrack(
    val id: Int,
    /** mpv media type: audio, video or sub. */
    val type: String,
    val title: String? = null,
    val language: String? = null,
    val selected: Boolean = false,
    val external: Boolean = false,
    val mainSelection: Int? = null,
)
