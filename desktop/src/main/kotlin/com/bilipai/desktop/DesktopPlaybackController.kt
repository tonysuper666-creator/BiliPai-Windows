package com.bilipai.desktop

import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.data.VideoDetails
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.player.resolveNextPlaybackIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DesktopPlaybackState(
    val details: VideoDetails? = null,
    val opening: Boolean = false,
    val currentPart: Int = 0,
    val quality: Int = 80,
    val effectiveQuality: Int = 0,
    val availableQualities: List<com.bilipai.desktop.data.PlaybackQuality> = emptyList(),
    val related: List<VideoCard> = emptyList(),
    val error: String? = null,
)

/** Keeps the native session and progress independent of the visible content page. */
class DesktopPlaybackController(
    private val repository: DesktopRepository,
    private val player: MpvPlayer?,
    private val playerError: String?,
    private val danmaku: DanmakuOverlay?,
    private val library: DesktopLibrary,
    private val preferences: () -> PlayerPreferences,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(DesktopPlaybackState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L

    fun open(card: VideoCard) {
        checkpoint()
        request?.cancel()
        val expected = ++generation
        player?.stop()
        danmaku?.enabled = false
        val resume = card.takeIf { it.progressSeconds != null || it.preferredCid > 0 }
            ?: library.resumeCard(card.bvid)
        mutableState.value = DesktopPlaybackState(opening = true, quality = state.value.quality)
        request = scope.launch {
            try {
                val info = repository.videoDetails(card.bvid)
                require(info.pages.isNotEmpty()) { "此视频没有可播放的分集" }
                val index = info.pages.indexOfFirst { it.cid == resume?.preferredCid }.takeIf { it >= 0 }
                    ?: (resume?.pageIndex ?: 0).coerceIn(info.pages.indices)
                if (expected != generation) return@launch
                library.record(info.toLibraryCard())
                mutableState.update { it.copy(details = info, opening = false, currentPart = index) }
                launch {
                    try {
                        val related = repository.related(info.bvid)
                        if (expected == generation) mutableState.update { it.copy(related = related) }
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                }
                val duration = info.pages[index].duration
                val position = (resume?.progressSeconds ?: 0).toDouble().takeIf {
                    it >= 0 && (duration <= 0 || it < duration - 2)
                } ?: 0.0
                load(info, index, position, false, expected)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (expected == generation) mutableState.update { it.copy(opening = false, error = failure.message ?: "无法打开视频") }
            }
        }
    }

    fun open(bvid: String) = open(VideoCard(bvid, "", "", "", 0, 0))

    fun playPart(index: Int, position: Double = 0.0, paused: Boolean = false) {
        val info = state.value.details ?: return
        if (index !in info.pages.indices) return
        checkpoint()
        request?.cancel()
        val expected = ++generation
        mutableState.update { it.copy(currentPart = index, error = null) }
        request = scope.launch {
            try { load(info, index, position, paused, expected)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (expected == generation) mutableState.update { it.copy(error = failure.message ?: "播放失败") }
            }
        }
    }

    private suspend fun load(info: VideoDetails, index: Int, position: Double, paused: Boolean, expected: Long) = coroutineScope {
        launch {
            danmaku?.applySettings(preferences().danmaku)
            danmaku?.load(info.pages[index].cid, info.aid, info.pages[index].duration.toDouble())
        }
        val source = repository.playback(info, index, state.value.quality)
        if (expected != generation) return@coroutineScope
        mutableState.update { it.copy(effectiveQuality = source.quality, availableQualities = source.availableQualities) }
        val initialized = player ?: throw IllegalStateException(playerError ?: "播放器未能初始化")
        initialized.applyPreferences(preferences().let { it.copy(speed = it.preferredSpeed) })
        initialized.load(com.bilipai.desktop.player.PlaybackSource(
            videoUrl = source.videoUrl, audioUrl = source.audioUrl, title = source.title, referer = source.referer,
            cookieHeader = source.cookieHeader, startPositionSeconds = position, startPaused = paused,
            progressiveSegments = source.progressiveSegments))
    }

    fun switchQuality(quality: Int) {
        mutableState.update { it.copy(quality = quality) }
        val current = player?.state?.value
        playPart(state.value.currentPart, current?.positionSeconds ?: 0.0, current?.paused ?: false)
    }

    fun seek(cid: Long, seconds: Double) {
        val info = state.value.details ?: return
        val index = info.pages.indexOfFirst { it.cid == cid }
        if (index < 0) return
        if (index == state.value.currentPart) player?.seekTo(seconds) else playPart(index, seconds)
    }

    fun nextAtEnd() {
        val current = state.value
        val info = current.details ?: return
        val next = resolveNextPlaybackIndex(preferences().playbackMode, current.currentPart, info.pages.size) ?: return
        playPart(next)
    }

    fun checkpoint() {
        val current = state.value
        val info = current.details ?: return
        val native = player?.state?.value ?: return
        if (native.loading || native.error != null || native.durationSeconds <= 0) return
        val cid = info.pages.getOrNull(current.currentPart)?.cid ?: return
        library.checkpoint(info.bvid, cid, current.currentPart, native.positionSeconds)
    }

    fun stop() {
        checkpoint()
        generation++
        request?.cancel()
        request = null
        mutableState.update { DesktopPlaybackState(quality = it.quality) }
        danmaku?.enabled = false
        player?.stop()
    }

    fun pause() {
        checkpoint()
        generation++
        request?.cancel()
        request = null
        mutableState.update { it.copy(opening = false) }
        player?.setPaused(true)
    }

    fun dismissError() { mutableState.update { it.copy(error = null) } }
}

private fun VideoDetails.toLibraryCard() = VideoCard(bvid, title, cover, author, playCount,
    pages.firstOrNull()?.duration?.toInt() ?: 0, authorMid = authorMid)
