package com.bilipai.desktop.ui

/** Identity of a producer borrowing the original shared Store/player. This has
 * no load counter, source slot, credentials or native actor. Replacement calls
 * retire outside admission, so actual Job/VM cleanup cannot invert gate order. */
internal interface DesktopOriginalBangumiSharedPlaybackPresenter {
    fun onSharedPlaybackReplaced()
}
