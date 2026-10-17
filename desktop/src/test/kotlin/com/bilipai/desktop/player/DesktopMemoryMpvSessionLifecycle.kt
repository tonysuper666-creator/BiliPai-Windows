package com.bilipai.desktop.player

/** Lifecycle bridge for never-started private Session fixtures only. These actors
 * use in-memory C ABI ports; they never create an MPV handle, worker, HWND or AO.
 * Mirror startSession's attachment and run's no-created-handle teardown so a
 * fixture cannot leave an unknown core in the production process-wide broker.
 * This helper MUST NOT acknowledge destruction for a real/started native worker.
 */
internal class DesktopMemoryMpvSessionLifecycle(private val player: MpvPlayer, private val actor: Any) {
    private val type = actor.javaClass
    private val worker = type.getDeclaredField("thread").apply { isAccessible = true }.get(actor) as Thread
    private val focus = MpvPlayer::class.java.getDeclaredField("audioOutputFocus")
        .apply { isAccessible = true }.get(player) as DesktopWindowsAudioOutputFocus
    private val session = type.getDeclaredField("audioFocusSession").apply { isAccessible = true }
        .get(actor) as DesktopWindowsAudioOutputFocus.NativeSession?
    private val intent = MpvPlayer::class.java.getDeclaredField("windowsAudioFocusIntent").apply { isAccessible = true }
    private var closed = false
    init { check(worker.state == Thread.State.NEW) { "Only a never-started memory Session is allowed" } }

    fun attach() {
        check(!closed && worker.state == Thread.State.NEW)
        val native = session ?: return
        val staged = intent.get(player) as DesktopWindowsAudioOutputFocus.Intent?
        intent.set(player, staged?.let { focus.attach(it, native) })
    }

    fun close(releaseMemoryResources: () -> Unit) {
        if (closed) return
        check(worker.state == Thread.State.NEW) { "Real workers require actual native destruction" }
        type.getDeclaredMethod("beginClosing").apply { isAccessible = true }.invoke(actor)
        releaseMemoryResources() // If this fails, never forge a destruction acknowledgement.
        session?.let(focus::acknowledgeDestroyed) // No native handle was ever created.
        closed = true
    }
}
