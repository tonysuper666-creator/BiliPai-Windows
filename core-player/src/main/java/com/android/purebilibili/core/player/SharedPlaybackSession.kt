@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.android.purebilibili.core.player

import android.content.Context
import android.media.MediaCodecList
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.player.dash.buildLocalDashManifest
import com.android.purebilibili.core.store.TokenManager
import com.android.purebilibili.data.repository.HistoryRepository
import com.android.purebilibili.data.model.response.PlayUrlData
import com.android.purebilibili.data.model.response.getBestAudio
import com.android.purebilibili.data.model.response.getBestVideo
import com.android.purebilibili.data.model.response.ViewInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

enum class PlaybackStatus { Idle, Loading, Ready, Ended, Failed }

data class SharedPlaybackState(
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val info: ViewInfo? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val actualQuality: Int = 0,
    val qualities: List<Pair<Int, String>> = emptyList(),
    val error: String? = null,
    val realPlayedMs: Long = 0,
    val startTsSec: Long = 0,
)

/** Owned by a screen/service. Caller owns loading coroutines, ticker, and close(). */
class SharedPlaybackSession(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(SharedPlaybackState())
    val state = mutableState.asStateFlow()
    val player: ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        setHandleAudioBecomingNoisy(true)
    }
    private val mediaSession = MediaSession.Builder(appContext, player).build()
    private var closed = false
    private var generation = 0L
    private var lastTickMs = android.os.SystemClock.elapsedRealtime()
    private var progress: PlaybackProgressManager? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            tick()
            if (player.playbackState == Player.STATE_ENDED) {
                persistPosition()
                mutableState.update { it.copy(status = PlaybackStatus.Ended) }
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            mutableState.update { it.copy(status = PlaybackStatus.Failed, playing = false,
                error = "播放失败，可重试或选择较低画质（${error.errorCodeName}）") }
        }
    }

    init { player.addListener(listener) }

    suspend fun load(request: SharedPlaybackRequest, playWhenReady: Boolean = true) {
        check(!closed)
        persistPosition()
        val ticket = ++generation
        player.stop()
        player.clearMediaItems()
        mutableState.update { SharedPlaybackState(status = PlaybackStatus.Loading) }
        lastTickMs = android.os.SystemClock.elapsedRealtime()
        val manager = withContext(Dispatchers.IO) { PlaybackProgressManager.getInstance(appContext) }
        val result = SharedPlaybackRepository.load(request)
        if (closed || ticket != generation) return
        progress = manager
        val loaded = result.getOrElse { error ->
            mutableState.update { it.copy(status = PlaybackStatus.Failed, error = error.message ?: "加载失败，请重试") }
            return
        }
        try {
            val streams = loaded.streams
            val info = loaded.info
            val prepared = createMediaSource(streams, request.quality)
            val qualityIds = streams.dash?.video.orEmpty().map { it.id }.distinct()
                .ifEmpty { streams.acceptQuality }
            val qualities = qualityIds.map { id ->
                id to (streams.acceptQuality.indexOf(id).takeIf { it >= 0 }
                    ?.let { streams.acceptDescription.getOrNull(it) }
                    ?: com.android.purebilibili.data.model.VideoQuality.fromCode(id)?.description ?: "$id")
            }
            val start = resolvePlaybackResumePosition(
                explicitMs = request.startPositionMs,
                localMs = manager.getCachedPosition(info.bvid, info.cid),
                serverMs = streams.lastPlayTime?.toLong()?.takeIf { streams.lastPlayCid == info.cid } ?: 0,
                durationMs = streams.timelength,
            )
            mutableState.update { SharedPlaybackState(
                status = PlaybackStatus.Ready, info = info, actualQuality = prepared.quality,
                qualities = qualities, durationMs = streams.timelength, positionMs = start,
                startTsSec = System.currentTimeMillis() / 1000L,
            ) }
            player.setMediaSource(prepared.source)
            player.seekTo(start)
            player.prepare()
            player.playWhenReady = playWhenReady
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(status = PlaybackStatus.Failed, playing = false,
                error = error.message ?: "无法建立播放源，请重试") }
        }
    }

    private data class PreparedSource(val source: androidx.media3.exoplayer.source.MediaSource, val quality: Int)

    private fun createMediaSource(data: PlayUrlData, quality: Int): PreparedSource {
        val upstream = OkHttpDataSource.Factory(NetworkModule.playbackOkHttpClient).setDefaultRequestProperties(mapOf(
            "Referer" to "https://www.bilibili.com/", "User-Agent" to "Mozilla/5.0",
        ))
        val dash = data.dash
        val supported = dash?.let { it.copy(video = it.video.filter { track ->
            val mime = MimeTypes.getMediaMimeType(track.codecs) ?: track.mimeType.ifBlank { MimeTypes.VIDEO_H264 }
            decoderMimeTypes.contains(mime)
        }) }
        val video = supported?.getBestVideo(quality, preferCodec = "avc1", secondPreferCodec = "hev1",
            isHevcSupported = decoderMimeTypes.contains(MimeTypes.VIDEO_H265),
            isAv1Supported = decoderMimeTypes.contains(MimeTypes.VIDEO_AV1))
        val audio = dash?.getBestAudio()
        if (video != null) {
            if (!video.segmentBase?.initialization.isNullOrBlank() && !video.segmentBase?.indexRange.isNullOrBlank()
                && (audio == null || !audio.segmentBase?.initialization.isNullOrBlank() && !audio.segmentBase?.indexRange.isNullOrBlank())) {
                val manifest = buildLocalDashManifest(data.timelength, (requireNotNull(dash).minBufferTime * 1000).toLong(),
                    listOf(video), listOfNotNull(audio)).toByteArray()
                val manifestUri = android.net.Uri.parse("memory://bilipai/manifest.mpd")
                val manifestFactory = DataSource.Factory { ByteArrayDataSource(manifest) }
                val sourceFactory = DataSource.Factory {
                    val remote = upstream.createDataSource()
                    val local = manifestFactory.createDataSource()
                    object : DataSource {
                        private var selected: DataSource = remote
                        override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {
                            remote.addTransferListener(listener); local.addTransferListener(listener)
                        }
                        override fun open(dataSpec: DataSpec): Long {
                            selected = if (dataSpec.uri == manifestUri) local else remote
                            return selected.open(dataSpec)
                        }
                        override fun read(buffer: ByteArray, offset: Int, length: Int) = selected.read(buffer, offset, length)
                        override fun getUri() = selected.uri
                        override fun getResponseHeaders() = selected.responseHeaders
                        override fun close() = selected.close()
                    }
                }
                return PreparedSource(DashMediaSource.Factory(sourceFactory).createMediaSource(MediaItem.Builder()
                    .setUri(manifestUri).setMimeType(MimeTypes.APPLICATION_MPD).build()), video.id)
            }
            val factory = ProgressiveMediaSource.Factory(upstream)
            val videoSource = factory.createMediaSource(MediaItem.fromUri(video.getValidUrl()))
            val source = audio?.let { MergingMediaSource(videoSource, factory.createMediaSource(MediaItem.fromUri(it.getValidUrl()))) }
                ?: videoSource
            return PreparedSource(source, video.id)
        }
        val segments = data.durl.orEmpty().filter { it.url.isNotBlank() }.sortedBy { it.order }
        check(segments.isNotEmpty()) { "没有兼容的播放流" }
        val factory = ProgressiveMediaSource.Factory(upstream)
        val sources = segments.map { factory.createMediaSource(MediaItem.fromUri(it.url)) }
        return PreparedSource(if (sources.size == 1) sources.first() else ConcatenatingMediaSource(*sources.toTypedArray()), data.quality)
    }

    private val decoderMimeTypes by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filterNot { it.isEncoder }.flatMap { it.supportedTypes.toList() }.toSet()
        }.getOrDefault(setOf(MimeTypes.VIDEO_H264))
    }

    fun togglePlayPause() {
        if (closed) return
        when {
            player.playbackState == Player.STATE_ENDED -> replay()
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }
    fun replay() {
        mutableState.update { it.copy(status = PlaybackStatus.Ready) }
        player.seekTo(0)
        player.play()
    }
    fun pause() { if (!closed) { player.pause(); persistPosition() } }
    fun seekTo(positionMs: Long) {
        if (closed) return
        val duration = player.duration.takeIf { it > 0 } ?: mutableState.value.durationMs
        if (duration > 0) {
            if (mutableState.value.status == PlaybackStatus.Ended) mutableState.update { it.copy(status = PlaybackStatus.Ready) }
            player.seekTo(positionMs.coerceIn(0, duration))
        }
    }
    fun setSpeed(speed: Float) { if (!closed) player.setPlaybackSpeed(speed.coerceIn(0.5f, 2f)) }

    fun tick() {
        if (closed) return
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = (now - lastTickMs).coerceIn(0, 1_000)
        lastTickMs = now
        mutableState.update { it.copy(realPlayedMs = it.realPlayedMs + if (it.playing) elapsed else 0,
            playing = player.isPlaying, buffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0), durationMs = player.duration.takeIf { it > 0 } ?: it.durationMs) }
    }

    fun persistPosition() {
        if (closed) return
        val info = mutableState.value.info ?: return
        progress?.savePosition(info.bvid, info.cid, player.currentPosition, player.duration)
    }

    suspend fun reportProgress() {
        val snapshot = mutableState.value
        val info = snapshot.info ?: return
        if (TokenManager.sessDataCache.isNullOrBlank()) return
        HistoryRepository.reportPlayback(info.bvid, info.cid, snapshot.positionMs / 1000,
            snapshot.realPlayedMs / 1000, snapshot.startTsSec, info.aid)
    }

    override fun close() {
        if (closed) return
        persistPosition()
        closed = true
        generation++
        player.removeListener(listener)
        mediaSession.release()
        player.release()
    }
}
