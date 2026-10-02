package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.player.PlaylistItem

/** Read the exact composed source capability. A missing/stale owner aborts
 * navigation; it never resumes a different source or invents ordinary position 0.
 * readPositionMs is a required short real-native read with the same source and
 * Store/entry admission. It must not resolve, load, block or recapture latest.
 */
internal fun captureDesktopReadyNowPlayingPositionMs(
    audio: DesktopOriginalAudioNowPlayingBinding,
    expected: DesktopOriginalNowPlayingSnapshot?,
    item: PlaylistItem,
    rootOwns: () -> Boolean,
    readPositionMs: (DesktopOriginalNowPlayingSnapshot) -> Long?,
): Long? {
    if (expected == null || expected.item != item || !rootOwns() || !audio.ownsSnapshot(expected)) return null
    val position = readPositionMs(expected) ?: return null
    if (position < 0L || !rootOwns() || !audio.ownsSnapshot(expected)) return null
    return position
}
