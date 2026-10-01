package com.bilipai.desktop.ui

/** Original Media3 Player phase identities consumed by source-preserved pure UI policies.
 * The native facade must project its real MPV state; these constants create no player.
 */
internal object DesktopOriginalPlaybackStates {
    const val STATE_IDLE = 1
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
    const val STATE_ENDED = 4
}
