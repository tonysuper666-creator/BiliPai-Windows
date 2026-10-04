package com.bilipai.desktop.player

internal data class DesktopNativeMuteIntent(val serial: Long, val muted: Boolean, val sourceOwned: Boolean)
internal data class DesktopNativeMuteReadStamp(val serial: Long, val pending: Boolean)

/** Used only under the owning player's lock. Native reads run outside that lock;
 * their captured stamp cannot overwrite a subsequently accepted mute intent. */
internal class DesktopNativeMuteIntentPolicy {
    private var serial = 0L
    private var pending: DesktopNativeMuteIntent? = null
    val hasPending: Boolean get() = pending != null

    fun request(muted: Boolean, sourceOwned: Boolean = false): DesktopNativeMuteIntent =
        DesktopNativeMuteIntent(++serial, muted, sourceOwned).also { pending = it }

    fun isCurrent(intent: DesktopNativeMuteIntent): Boolean = intent.serial == serial && pending == intent
    fun applied(intent: DesktopNativeMuteIntent): Boolean {
        if (!isCurrent(intent)) return false
        pending = null
        return true
    }
    fun reject(intent: DesktopNativeMuteIntent): Boolean {
        if (!isCurrent(intent)) return false
        retire()
        return true
    }
    fun retire() { ++serial; pending = null }
    fun capture(): DesktopNativeMuteReadStamp = DesktopNativeMuteReadStamp(serial, hasPending)
    fun canPublish(stamp: DesktopNativeMuteReadStamp): Boolean =
        stamp.serial == serial && !stamp.pending && pending == null
}
