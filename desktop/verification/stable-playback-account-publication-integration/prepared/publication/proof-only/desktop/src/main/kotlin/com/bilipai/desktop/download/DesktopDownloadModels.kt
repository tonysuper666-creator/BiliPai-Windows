package com.bilipai.desktop.download

import com.android.purebilibili.feature.download.DownloadTask as UpstreamDownloadTask
import com.android.purebilibili.feature.download.DownloadStatus
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.security.MessageDigest

/** The upstream task is authoritative; these fields only describe Windows storage/HTTP context. */
@Serializable
data class DownloadTask(
    val item: UpstreamDownloadTask,
    val destinationRoot: String,
    val referer: String = "https://www.bilibili.com/",
    val userAgent: String = com.bilipai.desktop.player.PlaybackSource.DEFAULT_USER_AGENT,
    val seasonId: Long = 0,
    val episodeId: Long = 0,
    val isCourse: Boolean = false,
    val progressiveSegments: List<DownloadProgressiveSegment> = emptyList(),
    @kotlinx.serialization.Transient val authorizationReceipt: com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt? = null,
    @kotlinx.serialization.Transient val cookieHeader: String = "",
    @kotlinx.serialization.Transient val streamHeaders: Map<String, String> = emptyMap(),
) {
    override fun toString() = "DownloadTask(id=$id, status=$status, authorization=$authorizationReceipt)"
    internal fun playbackSource() = com.bilipai.desktop.player.PlaybackSource(item.videoUrl, item.audioUrl.takeIf { it.isNotBlank() },
        referer, userAgent, cookieHeader, item.title, progressiveSegments = progressiveSegments.map {
            com.bilipai.desktop.player.PlaybackSegment(it.url, it.durationSeconds) },
        streamHeaders = streamHeaders, authorizationReceipt = authorizationReceipt)
    val id: String get() = item.id
    val title: String get() = item.title
    val status: DownloadStatus get() = item.status
    val downloadedBytes: Long get() = item.downloadedSize
    val totalBytes: Long get() = item.assets.sumOf { it.totalBytes }
    val progress: Float get() = item.progress
    val error: String? get() = item.errorMessage
    val outputFile: String? get() = item.filePath
    val directory: String get() = Path.of(destinationRoot).resolve(directoryName(id)).toString()

    companion object {
        internal fun directoryName(id: String): String = "download-" + MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)
    }
}

@Serializable
data class DownloadProgressiveSegment(
    val url: String,
    val durationSeconds: Double? = null,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
)

data class DownloadMetadata(
    val bvid: String = "",
    val cid: Long = 0,
    val aid: Long = 0,
    val cover: String = "",
    val author: String = "",
    val durationSeconds: Int = 0,
    val quality: Int = 0,
    val qualityLabel: String = "",
    val episodeLabel: String? = null,
    val seasonId: Long = 0,
    val episodeId: Long = 0,
    val isCourse: Boolean = false,
    val audioOnly: Boolean = false,
    val downloadAllowed: Boolean = true,
    val includeCover: Boolean = true,
    val includeDanmaku: Boolean = true,
    val groupKey: String? = null,
    val groupTitle: String? = null,
    val episodeSortIndex: Int = 0,
    val episodeCount: Int = 1,
    val isVerticalVideo: Boolean = false,
)

data class OfflineDanmakuFiles(val standardSegments: List<Path>, val specialSegments: List<Path>)
