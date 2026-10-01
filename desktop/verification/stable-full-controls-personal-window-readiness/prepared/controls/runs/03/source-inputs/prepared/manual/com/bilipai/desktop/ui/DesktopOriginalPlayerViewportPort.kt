package com.bilipai.desktop.ui

/** Same native Canvas/source owner. Root binds remeasure to actual AWT/Compose frame lifecycle.
 * This is a required viewport transport; it is not an ExoPlayer/PlayerView substitute. */
internal interface DesktopOriginalPlayerViewportPort {
    var resizeMode: Int
    val width: Int
    val height: Int
    fun requestLayout()
    fun requestContentLayout()
    fun invalidate()
    fun post(block: () -> Unit)
    fun postOnFrame(block: () -> Unit)
}
