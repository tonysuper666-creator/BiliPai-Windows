package com.bilipai.desktop.player

import com.sun.jna.Pointer
import java.awt.EventQueue
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal enum class MpvNativeWindowInputKind(val binding: String, val action: String, val keyCode: Int = 0) {
    REVEAL("MBTN_LEFT", "reveal"), MOTION("MOUSE_MOVE", "motion"), TOGGLE("SPACE", "toggle"),
    WHEEL_UP("WHEEL_UP", "wheel-up"), WHEEL_DOWN("WHEEL_DOWN", "wheel-down"),
    LEFT("LEFT", "left", java.awt.event.KeyEvent.VK_LEFT),
    RIGHT("RIGHT", "right", java.awt.event.KeyEvent.VK_RIGHT),
    UP("UP", "up", java.awt.event.KeyEvent.VK_UP), DOWN("DOWN", "down", java.awt.event.KeyEvent.VK_DOWN),
    FULLSCREEN("f", "fullscreen", java.awt.event.KeyEvent.VK_F),
    F11("F11", "f11", java.awt.event.KeyEvent.VK_F11),
    ESCAPE("ESC", "escape", java.awt.event.KeyEvent.VK_ESCAPE),
    MUTE("m", "mute", java.awt.event.KeyEvent.VK_M),
}
internal data class MpvNativeWindowInputEvent(
    val lease: MpvNativeWindowInputLease,
    val kind: MpvNativeWindowInputKind,
    val point: java.awt.Point? = runCatching { java.awt.MouseInfo.getPointerInfo()?.location }.getOrNull(),
    val timeMs: Long = System.nanoTime() / 1_000_000,
)

/** A new opaque binding for each actual native load, including same-file replay.
 * A queued input from a predecessor cannot acquire its successor's source.
 * This is MPV's own child-window/client-message API, not an OS input hook.
 */
internal class MpvNativeWindowInputLease(val source: OwnedPlaybackSourceSnapshot, val playbackRevision: Long) {
    val token: String = "bilipai-input-" + UUID.randomUUID().toString()
    val bindings: String = MpvNativeWindowInputKind.entries
        .joinToString("\n") { "${it.binding} nonrepeatable script-message $token ${it.action}" }

    /** Borrowed x64 mpv_event_client_message: int argc at 0, char** argv at 8.
     * Copy only our two bounded ASCII fields before the next mpv_wait_event.
     */
    fun decode(data: Pointer?): MpvNativeWindowInputEvent? {
        if (data == null || data.getInt(0) != 2) return null
        val args = data.getPointer(8) ?: return null
        if (ascii(args.getPointer(0), 64) != token) return null
        val action = ascii(args.getPointer(8), 16) ?: return null
        val kind = MpvNativeWindowInputKind.entries.firstOrNull { it.action == action } ?: return null
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
    fun emit(event: MpvNativeWindowInputEvent) {
        val registration = current.get() ?: return
        if (event.kind == MpvNativeWindowInputKind.MOTION) synchronized(registration) {
            if (registration.motionPending) return
            registration.motionPending = true
        }
        dispatch {
            if (event.kind == MpvNativeWindowInputKind.MOTION) synchronized(registration) { registration.motionPending = false }
            if (current.get() === registration) registration.listener(event)
        }
    }
}
