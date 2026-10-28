package com.android.purebilibili.feature.video.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

internal enum class VideoDetailEntryWorkPhase {
    Content,
    Playback,
    Comments,
    Related,
}

/** Give each entry phase a frame before starting media, comments and recommendation cards. */
internal suspend fun runVideoDetailEntryWorkHandoff(
    onPlaybackReady: () -> Unit,
    onCommentsReady: () -> Unit,
    onRelatedReady: () -> Unit,
) {
    // A frame callback runs before composition/draw. Two boundaries give the preceding
    // phase a frame to render; a single boundary can batch both phases into one frame.
    repeat(2) { withFrameNanos { } }
    onPlaybackReady()
    repeat(2) { withFrameNanos { } }
    onCommentsReady()
    repeat(2) { withFrameNanos { } }
    onRelatedReady()
}

@Composable
internal fun rememberVideoDetailEntryWorkPhase(
    staggerWork: Boolean,
    contentReady: Boolean,
    isVisible: Boolean,
): VideoDetailEntryWorkPhase {
    // Entry lifetime, not BVID lifetime: changing episode or returning from a child must
    // not close an already-open playback gate or replay the initial handoff.
    var phase by remember {
        mutableStateOf(if (staggerWork) VideoDetailEntryWorkPhase.Content else VideoDetailEntryWorkPhase.Related)
    }
    LaunchedEffect(staggerWork, contentReady, isVisible) {
        if (!staggerWork) {
            phase = VideoDetailEntryWorkPhase.Related
            return@LaunchedEffect
        }
        if (!contentReady || !isVisible || phase == VideoDetailEntryWorkPhase.Related) {
            return@LaunchedEffect
        }
        runVideoDetailEntryWorkHandoff(
            onPlaybackReady = {
                if (phase < VideoDetailEntryWorkPhase.Playback) phase = VideoDetailEntryWorkPhase.Playback
            },
            onCommentsReady = {
                if (phase < VideoDetailEntryWorkPhase.Comments) phase = VideoDetailEntryWorkPhase.Comments
            },
            onRelatedReady = { phase = VideoDetailEntryWorkPhase.Related },
        )
    }
    return phase
}
