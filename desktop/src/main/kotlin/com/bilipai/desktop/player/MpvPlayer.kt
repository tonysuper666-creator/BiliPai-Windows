package com.bilipai.desktop.player

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Color
import java.awt.Dimension
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel

/**
 * Windows native player. SwingPanel embeds [surface]; mpv creates a child HWND
 * inside its Canvas and renders with Direct3D 11. No Android runtime or browser
 * participates in video/audio decoding.
 *
 * All libmpv calls for a playback session run on one worker thread. Surface
 * removal tears down that session before AWT destroys the parent window.
 */
class MpvPlayer internal constructor(private val useNullAudioOutput: Boolean = false) : AutoCloseable {
    private val mutableState = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = mutableState.asStateFlow()
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    @Volatile private var session: Session? = null
    private var requestedSource: PlaybackSource? = null

    private val canvas = object : Canvas() {
        override fun addNotify() {
            super.addNotify()
            // Windows handles are cast to uint32_t, as required by mpv --wid.
            attach(Pointer.nativeValue(Native.getComponentPointer(this)) and 0xffff_ffffL)
        }

        override fun removeNotify() {
            detach()
            super.removeNotify()
        }
    }.apply {
        background = Color.BLACK
        preferredSize = Dimension(640, 360)
        isFocusable = false
    }

    val surface: JPanel = JPanel(BorderLayout()).apply {
        background = Color.BLACK
        add(canvas, BorderLayout.CENTER)
    }

    fun load(source: PlaybackSource) {
        synchronized(lock) {
            check(!closed.get()) { "Player is closed" }
            requestedSource = source
            mutableState.update {
                it.copy(loading = true, paused = false, positionSeconds = 0.0, durationSeconds = 0.0,
                    ended = false, error = null, videoCodec = null, audioCodec = null, avSyncSeconds = null)
            }
            session?.commands?.offer(Action.Load(source))
        }
    }

    fun setPaused(paused: Boolean) = send(Action.Property("pause", if (paused) "yes" else "no"))
    fun togglePause() = setPaused(!state.value.paused)
    fun seekTo(seconds: Double) {
        if (seconds.isFinite()) send(Action.Command(listOf("seek", seconds.coerceAtLeast(0.0).toString(), "absolute+exact")))
    }
    fun seekBy(seconds: Double) {
        if (seconds.isFinite()) send(Action.Command(listOf("seek", seconds.toString(), "relative+exact")))
    }
    fun setVolume(volume: Double) {
        if (!volume.isFinite()) return
        val value = volume.coerceIn(0.0, 100.0)
        mutableState.update { it.copy(volume = value) }
        send(Action.Property("volume", value.toString()))
    }
    fun setSpeed(speed: Double) {
        if (!speed.isFinite()) return
        val value = speed.coerceIn(0.25, 4.0)
        mutableState.update { it.copy(speed = value) }
        send(Action.Property("speed", value.toString()))
    }
    fun stop() {
        synchronized(lock) {
            requestedSource = null
            send(Action.Command(listOf("stop")))
            mutableState.update {
                it.copy(loading = false, paused = false, positionSeconds = 0.0, durationSeconds = 0.0,
                    ended = false, error = null, videoCodec = null, audioCodec = null, avSyncSeconds = null)
            }
        }
    }

    private fun send(action: Action) {
        if (!closed.get()) session?.commands?.offer(action)
    }

    private fun attach(windowId: Long) {
        synchronized(lock) {
            if (closed.get() || session != null) return
            if (windowId == 0L) {
                mutableState.update { it.copy(error = "Cannot attach native player to the Windows video surface.") }
                return
            }
            val next = Session(windowId, requestedSource)
            session = next
            next.thread.start()
        }
    }

    private fun detach() {
        val previous = synchronized(lock) {
            session.also { session = null; it?.closing?.set(true) }
        }
        // Shutdown must finish while the HWND is still alive. mpv does not
        // call the AWT event thread, so this wait cannot create an EDT cycle.
        previous?.thread?.join(5_000)
        mutableState.update { it.copy(ready = false, loading = false) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(lock) { requestedSource = null }
            detach()
        }
    }

    private sealed interface Action {
        data class Load(val source: PlaybackSource) : Action
        data class Property(val name: String, val value: String) : Action
        data class Command(val args: List<String>) : Action
    }

    private inner class Session(private val windowId: Long, private val initialSource: PlaybackSource?) {
        val commands = LinkedBlockingQueue<Action>()
        val closing = AtomicBoolean(false)
        val thread = Thread(::run, "BiliPai-native-player").apply { isDaemon = true }
        private var activeEntry: Long? = null
        private var fileLoaded = false

        private fun run() {
            var native: MpvNative? = null
            var handle: Pointer? = null
            try {
                native = MpvNative.load()
                handle = native.mpv_create() ?: error("Unable to create the native player.")
                val options = mapOf(
                    "config" to "no",
                    "load-scripts" to "no",
                    "ytdl" to "no",
                    "terminal" to "no",
                    "osc" to "no",
                    "osd-level" to "0",
                    "input-default-bindings" to "no",
                    "input-vo-keyboard" to "no",
                    "input-cursor" to "no",
                    "idle" to "yes",
                    "keep-open" to "no",
                    "wid" to windowId.toString(),
                    "vo" to "gpu",
                    "gpu-api" to "d3d11",
                    "hwdec" to "auto-safe",
                    "audio-client-name" to "BiliPai",
                    "network-timeout" to "20",
                    "demuxer-max-bytes" to "64MiB",
                    "volume" to state.value.volume.toString(),
                    "speed" to state.value.speed.toString(),
                ) + if (useNullAudioOutput) mapOf("ao" to "null") else emptyMap()
                options.forEach { (name, value) -> checkResult(native, native.mpv_set_option_string(handle, name, value), name) }
                checkResult(native, native.mpv_initialize(handle), "initialize")
                mutableState.update { it.copy(ready = true, error = null, nativeVersion = property(native, handle, "mpv-version")) }
                initialSource?.let { perform(native, handle, Action.Load(it)) }
                var lastPoll = 0L
                while (!closing.get()) {
                    var action = commands.poll()
                    while (action != null && !closing.get()) {
                        perform(native, handle, action)
                        action = commands.poll()
                    }
                    if (closing.get()) break
                    val event = native.mpv_wait_event(handle, 0.05)
                    receiveEvent(native, event)
                    val now = System.nanoTime()
                    if (now - lastPoll >= 200_000_000L) {
                        refreshState(native, handle)
                        lastPoll = now
                    }
                }
            } catch (failure: Throwable) {
                if (!closing.get()) mutableState.update {
                    it.copy(ready = false, loading = false, error = failure.message ?: "Native player failed.")
                }
            } finally {
                if (native != null && handle != null) native.mpv_terminate_destroy(handle)
            }
        }

        private fun perform(native: MpvNative, handle: Pointer, action: Action) {
            try {
                when (action) {
                    is Action.Load -> {
                        activeEntry = null
                        fileLoaded = false
                        MpvNodes().use { nodes ->
                            // Per-file options prevent old audio tracks or cookies
                            // from leaking into the next video, even on rapid loads.
                            val args = nodes.array(listOf("loadfile", action.source.videoUrl, "replace", "-1", action.source.mpvFileOptions()))
                            checkResult(native, native.mpv_command_node(handle, args, null), "loadfile")
                        }
                    }
                    is Action.Property -> checkResult(native, native.mpv_set_property_string(handle, action.name, action.value), action.name)
                    is Action.Command -> {
                        if (action.args.first() == "stop") { activeEntry = null; fileLoaded = false }
                        checkResult(native, native.mpv_command(handle, StringArray(action.args.toTypedArray(), "UTF-8")), action.args.first())
                    }
                }
            } catch (failure: Exception) {
                mutableState.update { it.copy(loading = false, error = failure.message ?: "Playback operation failed.") }
            }
        }

        private fun receiveEvent(native: MpvNative, event: Pointer) {
            // mpv_event x64 ABI: int event, int error, uint64 userdata, void* data.
            when (event.getInt(0)) {
                1 -> closing.set(true) // MPV_EVENT_SHUTDOWN
                6 -> { // MPV_EVENT_START_FILE
                    activeEntry = event.getPointer(16)?.getLong(0)
                    fileLoaded = false
                    mutableState.update { it.copy(loading = true, ended = false, error = null) }
                }
                8 -> { // MPV_EVENT_FILE_LOADED
                    fileLoaded = true
                    mutableState.update { it.copy(loading = false, ended = false, error = null) }
                }
                7 -> { // MPV_EVENT_END_FILE
                    val data = event.getPointer(16) ?: return
                    val entry = data.getLong(8)
                    if (activeEntry != entry) return
                    fileLoaded = false
                    when (data.getInt(0)) {
                        0 -> mutableState.update { it.copy(loading = false, ended = true, paused = true) }
                        4 -> mutableState.update {
                            it.copy(loading = false, error = "Playback failed: ${native.mpv_error_string(data.getInt(4))}")
                        }
                    }
                }
            }
        }

        private fun refreshState(native: MpvNative, handle: Pointer) {
            val paused = property(native, handle, "pause") == "yes"
            val buffering = property(native, handle, "paused-for-cache") == "yes"
            val position = property(native, handle, "time-pos")?.toDoubleOrNull()
            val duration = property(native, handle, "duration")?.toDoubleOrNull()
            val videoCodec = property(native, handle, "video-codec")
            val audioCodec = property(native, handle, "audio-codec")
            val avSync = property(native, handle, "avsync")?.toDoubleOrNull()
            val volume = property(native, handle, "volume")?.toDoubleOrNull()
            val speed = property(native, handle, "speed")?.toDoubleOrNull()
            mutableState.update {
                it.copy(
                    loading = if (fileLoaded) buffering else it.loading,
                    paused = if (it.ended) true else paused,
                    positionSeconds = position ?: it.positionSeconds,
                    durationSeconds = duration ?: it.durationSeconds,
                    volume = volume ?: it.volume,
                    speed = speed ?: it.speed,
                    videoCodec = if (fileLoaded) videoCodec else null,
                    audioCodec = if (fileLoaded) audioCodec else null,
                    avSyncSeconds = if (fileLoaded) avSync else null,
                )
            }
        }
    }

    private fun property(native: MpvNative, handle: Pointer, name: String): String? {
        val value = native.mpv_get_property_string(handle, name) ?: return null
        return try { value.getString(0, "UTF-8") } finally { native.mpv_free(value) }
    }

    private fun checkResult(native: MpvNative, result: Int, operation: String) {
        check(result >= 0) { "Native player $operation: ${native.mpv_error_string(result)}" }
    }
}
