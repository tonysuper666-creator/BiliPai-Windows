// Generated from app/src/main/java/com/android/purebilibili/feature/video/player/PlaylistManager.kt; do not edit.
// LF-normalized SHA-256: a6a884fffc8d045609da0ac745d7376ec68c5237a2b4dae64e7a6c578240bd80
package com.android.purebilibili.feature.video.player

import kotlinx.serialization.Serializable

/**
 * 播放列表项
 */
@Serializable
data class PlaylistItem(
    val bvid: String,
    val cid: Long = 0L,
    val title: String,
    val cover: String,
    val owner: String,
    val ownerFace: String = "",
    val duration: Long = 0L,
    // 番剧专用
    val isBangumi: Boolean = false,
    val seasonId: Long? = null,
    val epId: Long? = null
)

/**
 * 播放模式
 */
@Serializable
enum class PlayMode {
    SEQUENTIAL,   // 顺序播放
    SHUFFLE,      // 随机播放  
    REPEAT_ONE,   // 单曲循环
    REPEAT_ALL    // 列表循环
}

@Serializable
enum class ExternalPlaylistSource {
    NONE,
    WATCH_LATER,
    SPACE,
    FAVORITE,
    UNKNOWN
}

data class PlaylistUiState(
    val playMode: PlayMode = PlayMode.SEQUENTIAL,
    val playlist: List<PlaylistItem> = emptyList(),
    val currentIndex: Int = -1,
    val isExternalPlaylist: Boolean = false,
    val externalPlaylistSource: ExternalPlaylistSource = ExternalPlaylistSource.NONE,
    val shuffleEnabled: Boolean = false
)

internal data class RestoredPlayTransport(
    val playMode: PlayMode,
    val shuffleEnabled: Boolean
)

internal fun resolveRestoredPlayTransport(
    storedPlayMode: PlayMode,
    storedShuffleEnabled: Boolean
): RestoredPlayTransport {
    val shuffleEnabled = storedShuffleEnabled || storedPlayMode == PlayMode.SHUFFLE
    val playMode = if (storedPlayMode == PlayMode.SHUFFLE) PlayMode.REPEAT_ALL else storedPlayMode
    return RestoredPlayTransport(playMode = playMode, shuffleEnabled = shuffleEnabled)
}

internal fun resolveLinearPlayNextIndex(
    playlistSize: Int,
    currentIndex: Int,
    wrap: Boolean
): Int? {
    if (playlistSize <= 0 || currentIndex !in 0 until playlistSize) return null
    if (currentIndex < playlistSize - 1) return currentIndex + 1
    return if (wrap) 0 else null
}

internal fun resolveLinearPlayPreviousIndex(
    playlistSize: Int,
    currentIndex: Int,
    wrap: Boolean
): Int? {
    if (playlistSize <= 0 || currentIndex !in 0 until playlistSize) return null
    if (currentIndex > 0) return currentIndex - 1
    return if (wrap) playlistSize - 1 else null
}

internal fun shouldWrapPlaylistCycle(playMode: PlayMode): Boolean =
    playMode == PlayMode.REPEAT_ALL || playMode == PlayMode.SHUFFLE

@JvmInline
value class PlaylistSession internal constructor(internal val generation: Long)

internal data class ShuffleProgress(
    val history: List<Int> = emptyList(),
    val historyIndex: Int = -1,
    val cyclePlayed: Set<Int> = emptySet()
)

internal data class ShuffleAdvanceResult(
    val nextIndex: Int?,
    val progress: ShuffleProgress
)

internal fun advanceShuffleProgress(
    playlistSize: Int,
    currentIndex: Int,
    progress: ShuffleProgress,
    chooseCandidate: (List<Int>) -> Int
): ShuffleAdvanceResult {
    if (playlistSize <= 0 || currentIndex !in 0 until playlistSize) {
        return ShuffleAdvanceResult(nextIndex = null, progress = ShuffleProgress())
    }

    val validHistory = progress.history.filter { it in 0 until playlistSize }
    val traversedHistory = when {
        validHistory.isEmpty() -> listOf(currentIndex)
        progress.historyIndex < 0 -> listOf(currentIndex)
        else -> validHistory.take((progress.historyIndex + 1).coerceAtMost(validHistory.size))
    }

    val baseHistory = if (traversedHistory.lastOrNull() == currentIndex) {
        traversedHistory
    } else {
        traversedHistory + currentIndex
    }
    val baseHistoryIndex = baseHistory.lastIndex
    val baseCyclePlayed = progress.cyclePlayed
        .filter { it in 0 until playlistSize }
        .toSet() + currentIndex

    if (baseHistoryIndex < validHistory.lastIndex) {
        val nextIndex = validHistory[baseHistoryIndex + 1]
        return ShuffleAdvanceResult(
            nextIndex = nextIndex,
            progress = ShuffleProgress(
                history = validHistory,
                historyIndex = baseHistoryIndex + 1,
                cyclePlayed = baseCyclePlayed + nextIndex
            )
        )
    }

    val candidatesExcludingCurrent = (0 until playlistSize).filter { it != currentIndex }
    if (candidatesExcludingCurrent.isEmpty()) {
        return ShuffleAdvanceResult(
            nextIndex = null,
            progress = ShuffleProgress(
                history = baseHistory,
                historyIndex = baseHistoryIndex,
                cyclePlayed = setOf(currentIndex)
            )
        )
    }

    var cyclePlayed = baseCyclePlayed
    var candidates = candidatesExcludingCurrent.filter { it !in cyclePlayed }
    if (candidates.isEmpty()) {
        cyclePlayed = setOf(currentIndex)
        candidates = candidatesExcludingCurrent
    }

    val nextIndex = chooseCandidate(candidates)
    val nextHistory = baseHistory + nextIndex
    return ShuffleAdvanceResult(
        nextIndex = nextIndex,
        progress = ShuffleProgress(
            history = nextHistory,
            historyIndex = nextHistory.lastIndex,
            cyclePlayed = cyclePlayed + nextIndex
        )
    )
}

internal fun reconcileShuffleProgressForPlaylistUpdate(
    previousPlaylist: List<PlaylistItem>,
    newPlaylist: List<PlaylistItem>,
    currentIndex: Int,
    progress: ShuffleProgress
): ShuffleProgress {
    if (newPlaylist.isEmpty() || currentIndex !in newPlaylist.indices) {
        return ShuffleProgress()
    }

    val newIndexByBvid = newPlaylist.mapIndexed { index, item -> item.bvid to index }.toMap()
    val mappedHistory = progress.history
        .take((progress.historyIndex + 1).coerceAtLeast(0))
        .mapNotNull { oldIndex ->
            previousPlaylist.getOrNull(oldIndex)?.bvid?.let(newIndexByBvid::get)
        }
    val normalizedHistory = if (mappedHistory.lastOrNull() == currentIndex) {
        mappedHistory
    } else {
        mappedHistory + currentIndex
    }
    val mappedCyclePlayed = progress.cyclePlayed
        .mapNotNull { oldIndex ->
            previousPlaylist.getOrNull(oldIndex)?.bvid?.let(newIndexByBvid::get)
        }
        .toSet() + currentIndex

    return ShuffleProgress(
        history = normalizedHistory,
        historyIndex = normalizedHistory.lastIndex,
        cyclePlayed = mappedCyclePlayed
    )
}

internal fun resolvePlaylistUiState(
    playMode: PlayMode,
    playlist: List<PlaylistItem>,
    currentIndex: Int,
    isExternalPlaylist: Boolean,
    externalPlaylistSource: ExternalPlaylistSource,
    shuffleEnabled: Boolean = false
): PlaylistUiState {
    return PlaylistUiState(
        playMode = playMode,
        playlist = playlist,
        currentIndex = currentIndex,
        isExternalPlaylist = isExternalPlaylist,
        externalPlaylistSource = externalPlaylistSource,
        shuffleEnabled = shuffleEnabled
    )
}
