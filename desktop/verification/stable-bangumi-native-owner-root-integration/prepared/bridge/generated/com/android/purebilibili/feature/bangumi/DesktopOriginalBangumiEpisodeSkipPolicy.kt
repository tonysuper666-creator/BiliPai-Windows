// GENERATED full original body; upstream 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589; LF 42626f5f61961e4aee797a413d9eab8c56f8032862fb112ec6e6bd0ceffc2d7d
package com.android.purebilibili.feature.bangumi

import com.android.purebilibili.data.model.response.EpisodeSkip
import com.android.purebilibili.data.model.response.SkipRange

internal enum class BangumiEpisodeSkipKind(val label: String) {
    OPENING("片头"),
    ENDING("片尾")
}

internal data class BangumiEpisodeSkipAction(
    val kind: BangumiEpisodeSkipKind,
    val seekToMs: Long
)

internal fun resolveBangumiEpisodeSkipAction(
    currentPositionMs: Long,
    durationMs: Long,
    skip: EpisodeSkip?,
    openingAlreadySkipped: Boolean,
    endingAlreadySkipped: Boolean
): BangumiEpisodeSkipAction? {
    val safePositionMs = currentPositionMs.coerceAtLeast(0L)
    resolveBangumiSkipRange(skip?.op, durationMs)?.let { range ->
        if (!openingAlreadySkipped && safePositionMs >= range.first && safePositionMs < range.last) {
            return BangumiEpisodeSkipAction(
                kind = BangumiEpisodeSkipKind.OPENING,
                seekToMs = range.endInclusive
            )
        }
    }
    resolveBangumiSkipRange(skip?.ed, durationMs)?.let { range ->
        if (!endingAlreadySkipped && safePositionMs >= range.first && safePositionMs < range.last) {
            return BangumiEpisodeSkipAction(
                kind = BangumiEpisodeSkipKind.ENDING,
                seekToMs = range.endInclusive
            )
        }
    }
    return null
}

private fun resolveBangumiSkipRange(range: SkipRange?, durationMs: Long): LongRange? {
    val startMs = range?.start?.toLong()?.coerceAtLeast(0L) ?: return null
    val rawEndMs = range.end.toLong()
    val endMs = if (durationMs > 0L) rawEndMs.coerceAtMost(durationMs) else rawEndMs
    return if (endMs > startMs) startMs..endMs else null
}
