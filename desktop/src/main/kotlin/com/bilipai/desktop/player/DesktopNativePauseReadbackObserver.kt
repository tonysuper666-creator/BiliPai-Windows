package com.bilipai.desktop.player

/** Instance-only, default-null diagnostic observer. It receives no source URL,
 * account, permit or command port. Every callback runs outside MPV/publication locks.
 * The opt-in private native fixture uses latches to order an actual readback race.
 */
internal interface DesktopNativePauseReadbackObserver {
    fun beforeRead() {}
    fun afterRead(observation: DesktopNativePauseReadObservation) {}
    fun afterPublish(observation: DesktopNativePauseReadObservation, pauseFieldsPublished: Boolean) {}
}

internal data class DesktopNativePauseReadObservation(
    val sourceVersion: Long,
    val revision: Long,
    val intentSerial: Long,
    val pendingIntentAtCapture: Boolean,
    val nativePaused: Boolean?,
)
