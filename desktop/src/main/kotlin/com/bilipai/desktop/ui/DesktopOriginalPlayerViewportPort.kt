package com.bilipai.desktop.ui

/** Same native Canvas/source owner. Root binds remeasure to actual AWT/Compose frame lifecycle.
 * This is a required viewport transport; it is not an ExoPlayer/PlayerView substitute. */
internal interface DesktopOriginalPlayerViewportPort {
    var resizeMode: Int
    val width: Int
    val height: Int
    val isAttached: Boolean
    val playbackIdentity: Any?
    var refreshController: DesktopOriginalViewportRefreshCancellation?
    fun addAttachmentObserver(observer: DesktopOriginalViewportAttachmentObserver)
    fun requestLayout()
    fun requestContentLayout()
    fun invalidate()
    fun post(block: () -> Unit)
    fun postOnFrame(block: () -> Unit)
    fun postOnFrame(callback: Runnable)
    fun cancelFrameCallback(callback: Runnable)
}

internal interface DesktopOriginalViewportAttachmentObserver {
    fun onViewportAttached(viewport: DesktopOriginalPlayerViewportPort)
    fun onViewportDetached(viewport: DesktopOriginalPlayerViewportPort)
}

internal interface DesktopOriginalViewportRefreshCancellation {
    fun cancel()
}
