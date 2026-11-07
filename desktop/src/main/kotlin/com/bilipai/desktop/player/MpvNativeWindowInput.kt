package com.bilipai.desktop.player

import com.sun.jna.Pointer
import java.awt.EventQueue
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal enum class MpvNativeWindowInputKind { REVEAL, MOTION, TOGGLE, ESCAPE }
internal data class MpvNativeWindowInputEvent(val lease: MpvNativeWindowInputLease, val kind: MpvNativeWindowInputKind,
    val receivedAtNanos: Long = System.nanoTime())

/** A new opaque binding for each actual native load, including same-file replay.
 * A queued input from a predecessor cannot acquire its successor's source.
 * This is MPV's own child-window/client-message API, not an OS input hook.
 */
internal class MpvNativeWindowInputLease(val source: OwnedPlaybackSourceSnapshot, val playbackRevision: Long, val windowId: Long = 0L) {
    val token: String = "bilipai-input-" + UUID.randomUUID().toString()
    val bindings: String = listOf("MBTN_LEFT" to "reveal", "MOUSE_MOVE" to "motion", "SPACE" to "toggle", "ESC" to "escape")
        .joinToString("\n") { (key, action) -> "$key nonrepeatable script-message $token $action" }

    /** Borrowed x64 mpv_event_client_message: int argc at 0, char** argv at 8.
     * Copy only our two bounded ASCII fields before the next mpv_wait_event.
     */
    fun decode(data: Pointer?): MpvNativeWindowInputEvent? {
        if (data == null || data.getInt(0) != 2) return null
        val args = data.getPointer(8) ?: return null
        if (ascii(args.getPointer(0), 64) != token) return null
        val kind = when (ascii(args.getPointer(8), 16)) {
            "reveal" -> MpvNativeWindowInputKind.REVEAL
            "motion" -> MpvNativeWindowInputKind.MOTION
            "toggle" -> MpvNativeWindowInputKind.TOGGLE
            "escape" -> MpvNativeWindowInputKind.ESCAPE
            else -> return null
        }
        return MpvNativeWindowInputEvent(this, kind)
    }

    private fun ascii(pointer: Pointer?, limit: Int): String? {
        if (pointer == null) return null
        val result = StringBuilder()
        for (index in 0 until limit) {
            val value = pointer.getByte(index.toLong()).toInt() and 255
            if (value == 0) return result.toString()
            if (value !in 32..126) return null
            result.append(value.toChar())
        }
        return null
    }
}

/** One scoped recipient on this same player; disposal also rejects already queued
 * EDT delivery. Mouse motion is coalesced; click/key events keep their ordering.
 */
internal class MpvNativeWindowInputDelivery(private val dispatch: (() -> Unit) -> Unit = { EventQueue.invokeLater(it) }) {
    private class Registration(val listener: (MpvNativeWindowInputEvent) -> Unit) {
        var motionPending = false
    }
    private val current = AtomicReference<Registration?>()
    fun register(listener: (MpvNativeWindowInputEvent) -> Unit): AutoCloseable {
        val registration = Registration(listener)
        check(current.compareAndSet(null, registration)) { "Native window input already has an owner" }
        return AutoCloseable { current.compareAndSet(registration, null); Unit }
    }
    // Ordinary video can own Escape without stealing pointer/offline shortcuts.
    // A captured event never falls through to a successor or to the other channel.
    private val escapeCurrent = AtomicReference<Registration?>()
    fun registerEscape(listener: (MpvNativeWindowInputEvent) -> Unit): AutoCloseable {
        val registration = Registration(listener)
        check(escapeCurrent.compareAndSet(null, registration)) { "Native Escape already has an owner" }
        return AutoCloseable { escapeCurrent.compareAndSet(registration, null); Unit }
    }
    fun emit(event: MpvNativeWindowInputEvent) {
        val channel = if (event.kind == MpvNativeWindowInputKind.ESCAPE && escapeCurrent.get() != null) escapeCurrent else current
        val registration = channel.get() ?: return
        if (event.kind == MpvNativeWindowInputKind.MOTION) synchronized(registration) {
            if (registration.motionPending) return
            registration.motionPending = true
        }
        dispatch {
            if (event.kind == MpvNativeWindowInputKind.MOTION) synchronized(registration) { registration.motionPending = false }
            if (channel.get() === registration) registration.listener(event)
        }
    }
}
