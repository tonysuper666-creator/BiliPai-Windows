package com.bilipai.desktop.player

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Color
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Path
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
    private var sourceVersion = 0L
    private val externalSubtitles = mutableListOf<ExternalSubtitle>()

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

    fun load(source: PlaybackSource) = load(source, preserveSubtitles = false)

    val currentSourceVersion: Long get() = synchronized(lock) { sourceVersion }

    /** A source ownership token lets a screen dispose only the stream that it actually loaded. */
    fun loadVersioned(source: PlaybackSource): Long = synchronized(lock) {
        load(source)
        sourceVersion
    }

    fun stopIfSourceVersion(version: Long): Boolean = synchronized(lock) {
        if (closed.get() || requestedSource == null || sourceVersion != version) false
        else { stop(); true }
    }

    private fun load(source: PlaybackSource, preserveSubtitles: Boolean) {
        synchronized(lock) {
            check(!closed.get()) { "Player is closed" }
            if (!preserveSubtitles) sourceVersion++
            if (!preserveSubtitles) externalSubtitles.clear()
            requestedSource = source
            mutableState.update {
                it.copy(loading = true, paused = source.startPaused, positionSeconds = source.startPositionSeconds, durationSeconds = 0.0,
                    sourceTitle = source.title, videoWidth = 0, videoHeight = 0,
                    ended = false, error = null, operationError = null, videoCodec = null, audioCodec = null, avSyncSeconds = null)
                    .copy(tracks = emptyList(), subtitleText = null, secondarySubtitleText = null)
            }
            session?.commands?.offer(Action.Load(source, sourceVersion))
        }
    }

    fun setPaused(paused: Boolean) {
        mutableState.update { it.copy(paused = paused) }
        send(Action.Property("pause", if (paused) "yes" else "no"))
    }
    fun togglePause() {
        if (state.value.ended) replay() else setPaused(!state.value.paused)
    }
    /** keep-open=no unloads a finished stream, so restart the retained source instead of seeking an idle player. */
    fun replay() {
        synchronized(lock) {
            requestedSource?.let { load(it.copy(startPositionSeconds = 0.0, startPaused = false), preserveSubtitles = true) }
        }
    }
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
        val value = speed.coerceIn(0.1, 8.0)
        mutableState.update { it.copy(speed = value) }
        send(Action.Property("speed", value.toString()))
    }
    fun setMuted(muted: Boolean) {
        mutableState.update { it.copy(muted = muted) }
        send(Action.Property("mute", if (muted) "yes" else "no"))
    }
    fun toggleMuted() = setMuted(!state.value.muted)
    fun setAudioOnly(audioOnly: Boolean) {
        mutableState.update { it.copy(audioOnly = audioOnly) }
        send(Action.Property("vid", if (audioOnly) "no" else "auto"))
    }
    fun setLoop(looping: Boolean) {
        mutableState.update { it.copy(looping = looping) }
        send(Action.Property("loop-file", if (looping) "inf" else "no"))
    }
    fun applyPreferences(preferences: PlayerPreferences) {
        val normalized = preferences.normalized()
        setVolume(normalized.volume)
        setSpeed(normalized.speed)
        setMuted(normalized.muted)
        setAudioOnly(normalized.audioOnly)
        setLoop(normalized.playbackMode == PlaybackMode.REPEAT_ONE)
    }
    fun setSubtitlesVisible(visible: Boolean) {
        mutableState.update { it.copy(subtitlesVisible = visible) }
        send(Action.Property("sub-visibility", if (visible) "yes" else "no"))
        send(Action.Property("secondary-sub-visibility", if (visible) "yes" else "no"))
    }
    fun selectAudioTrack(id: Int?) {
        require(id == null || id > 0) { "Invalid audio track ID." }
        send(Action.Property("aid", id?.toString() ?: "no"))
    }
    fun selectSubtitleTrack(id: Int?) {
        require(id == null || id > 0) { "Invalid subtitle track ID." }
        retainSubtitleSelection(id, 0)
        send(Action.Property("sid", id?.toString() ?: "no"))
    }
    fun selectSecondarySubtitleTrack(id: Int?) {
        require(id == null || id > 0) { "Invalid secondary subtitle track ID." }
        retainSubtitleSelection(id, 1)
        send(Action.Property("secondary-sid", id?.toString() ?: "no"))
    }
    /** Local SRT/ASS/VTT documents, including converted Bilibili subtitle JSON. */
    fun addSubtitle(file: Path, title: String = file.fileName.toString(), language: String = "", select: Boolean = true) {
        require(Files.isRegularFile(file)) { "Subtitle file does not exist." }
        require(listOf(title, language).none { '\u0000' in it }) { "Invalid subtitle metadata." }
        synchronized(lock) {
            check(!closed.get() && requestedSource != null) { "No video selected." }
            val path = file.toAbsolutePath().normalize()
            if (select) externalSubtitles.replaceAll { if (it.selection == 0) it.copy(selection = null) else it }
            externalSubtitles.removeAll { it.path == path }
            externalSubtitles += ExternalSubtitle(path, title, language, if (select) 0 else null)
            session?.commands?.offer(Action.Subtitles(sourceVersion))
        }
    }

    private fun retainSubtitleSelection(id: Int?, slot: Int) = synchronized(lock) {
        externalSubtitles.replaceAll {
            when {
                id != null && it.nativeId == id -> it.copy(selection = slot)
                it.selection == slot -> it.copy(selection = null)
                else -> it
            }
        }
    }
    /** Captures the decoded frame through libmpv, without screen occlusion or desktop chrome. */
    suspend fun captureScreenshot(destination: Path, includeSubtitles: Boolean = true): Path {
        check(!closed.get()) { "Player is closed" }
        val completion = CompletableDeferred<Path>()
        synchronized(lock) {
            val current = session
            check(current != null && state.value.ready && !state.value.ended && !state.value.audioOnly && state.value.videoCodec != null) {
                "播放器未显示视频，无法截图。"
            }
            current.commands.offer(Action.Screenshot(destination.toAbsolutePath().normalize(), includeSubtitles, completion))
        }
        try { return withTimeout(15_000) { completion.await() } }
        finally { if (!completion.isCompleted) completion.cancel() }
    }
    fun stop() {
        synchronized(lock) {
            requestedSource = null
            externalSubtitles.clear()
            sourceVersion++
            send(Action.Command(listOf("stop")))
            mutableState.update {
                it.copy(loading = false, paused = false, positionSeconds = 0.0, durationSeconds = 0.0,
                    sourceTitle = "BiliPai", videoWidth = 0, videoHeight = 0,
                    ended = false, error = null, operationError = null, videoCodec = null, audioCodec = null, avSyncSeconds = null)
                    .copy(tracks = emptyList(), subtitleText = null, secondarySubtitleText = null)
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
            val next = Session(windowId, requestedSource, sourceVersion)
            session = next
            next.thread.start()
        }
    }

    private fun detach() {
        val previous = synchronized(lock) {
            val snapshot = state.value
            if (snapshot.ready && !snapshot.loading && !snapshot.ended && snapshot.error == null) {
                requestedSource = requestedSource?.copy(startPositionSeconds = snapshot.positionSeconds.coerceAtLeast(0.0),
                    startPaused = snapshot.paused)
            }
            session.also { session = null; it?.closing?.set(true) }
        }
        // Shutdown must finish while the HWND is still alive. mpv does not
        // call the AWT event thread, so this wait cannot create an EDT cycle.
        previous?.thread?.join(5_000)
        mutableState.update { it.copy(ready = false, loading = false) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(lock) { requestedSource = null; externalSubtitles.clear() }
            detach()
        }
    }

    private sealed interface Action {
        data class Load(val source: PlaybackSource, val version: Long) : Action
        data class Subtitles(val version: Long) : Action
        data class Property(val name: String, val value: String) : Action
        data class Command(val args: List<String>) : Action
        data class Screenshot(val destination: Path, val includeSubtitles: Boolean, val completion: CompletableDeferred<Path>) : Action
    }

    private data class ExternalSubtitle(val path: Path, val title: String, val language: String, val selection: Int?, val nativeId: Int? = null)

    private inner class Session(private val windowId: Long, private val initialSource: PlaybackSource?, private val initialVersion: Long) {
        val commands = LinkedBlockingQueue<Action>()
        val closing = AtomicBoolean(false)
        val thread = Thread(::run, "BiliPai-native-player").apply { isDaemon = true }
        private var activeEntry: Long? = null
        private var fileLoaded = false
        private var lastTrackPoll = 0L
        private var tracks = emptyList<PlayerTrack>()
        private var activeSourceVersion = initialVersion
        private val loadedSubtitlePaths = mutableSetOf<Path>()

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
                    "mute" to if (state.value.muted) "yes" else "no",
                    "vid" to if (state.value.audioOnly) "no" else "auto",
                    "loop-file" to if (state.value.looping) "inf" else "no",
                    "sub-visibility" to if (state.value.subtitlesVisible) "yes" else "no",
                    "secondary-sub-visibility" to if (state.value.subtitlesVisible) "yes" else "no",
                    "screenshot-format" to "png",
                ) + if (useNullAudioOutput) mapOf("ao" to "null") else emptyMap()
                options.forEach { (name, value) -> checkResult(native, native.mpv_set_option_string(handle, name, value), name) }
                checkResult(native, native.mpv_initialize(handle), "initialize")
                mutableState.update { it.copy(ready = true, error = null, nativeVersion = property(native, handle, "mpv-version")) }
                initialSource?.let { perform(native, handle, Action.Load(it, initialVersion)) }
                var lastPoll = 0L
                while (!closing.get()) {
                    var action = commands.poll()
                    while (action != null) {
                        if (closing.get()) {
                            if (action is Action.Screenshot) action.completion.completeExceptionally(IllegalStateException("Player stopped before the screenshot was saved."))
                            break
                        }
                        perform(native, handle, action)
                        action = commands.poll()
                    }
                    if (closing.get()) break
                    val event = native.mpv_wait_event(handle, 0.05)
                    receiveEvent(native, handle, event)
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
                while (true) {
                    val pending = commands.poll() ?: break
                    if (pending is Action.Screenshot) pending.completion.completeExceptionally(IllegalStateException("Player stopped before the screenshot was saved."))
                }
                if (native != null && handle != null) native.mpv_terminate_destroy(handle)
            }
        }

        private fun perform(native: MpvNative, handle: Pointer, action: Action) {
            try {
                if (action !is Action.Load && action !is Action.Screenshot) mutableState.update { it.copy(operationError = null) }
                when (action) {
                    is Action.Load -> {
                        activeEntry = null
                        fileLoaded = false
                        tracks = emptyList()
                        activeSourceVersion = action.version
                        loadedSubtitlePaths.clear()
                        lastTrackPoll = 0L
                        MpvNodes().use { nodes ->
                            // Per-file options prevent old audio tracks or cookies
                            // from leaking into the next video, even on rapid loads.
                            val args = nodes.array(listOf("loadfile", action.source.nativeLoadUrl, "replace", "-1", action.source.mpvFileOptions()))
                            checkResult(native, native.mpv_command_node(handle, args, null), "loadfile")
                        }
                    }
                    is Action.Subtitles -> if (fileLoaded && activeSourceVersion == action.version) restoreSubtitles(native, handle)
                    is Action.Property -> checkResult(native, native.mpv_set_property_string(handle, action.name, action.value), action.name)
                    is Action.Command -> {
                        if (action.args.first() == "stop") { activeEntry = null; fileLoaded = false }
                        checkResult(native, native.mpv_command(handle, StringArray(action.args.toTypedArray(), "UTF-8")), action.args.first())
                        lastTrackPoll = 0L
                    }
                    is Action.Screenshot -> saveScreenshot(native, handle, action)
                }
            } catch (failure: Exception) {
                if (action is Action.Screenshot) {
                    action.completion.completeExceptionally(failure)
                    return
                }
                if (action is Action.Load) mutableState.update { it.copy(loading = false, error = failure.message ?: "Playback operation failed.") }
                else mutableState.update { it.copy(operationError = failure.message ?: "Playback control failed.") }
            }
        }

        private fun restoreSubtitles(native: MpvNative, handle: Pointer) {
            val assets = synchronized(lock) { if (sourceVersion == activeSourceVersion) externalSubtitles.toList() else emptyList() }
            if (assets.isEmpty()) return
            assets.forEach { asset ->
                if (asset.path in loadedSubtitlePaths) return@forEach
                try {
                    checkResult(native, native.mpv_command(handle, StringArray(arrayOf("sub-add", asset.path.toString(), "auto", asset.title, asset.language), "UTF-8")), "sub-add")
                    loadedSubtitlePaths.add(asset.path)
                } catch (failure: Exception) {
                    mutableState.update { it.copy(operationError = failure.message ?: "字幕加载失败。") }
                }
            }
            val nativeTracks = readTracks(native, handle)
            synchronized(lock) {
                if (sourceVersion != activeSourceVersion) return
                externalSubtitles.replaceAll { asset ->
                    asset.copy(nativeId = nativeTracks.firstOrNull { it.external && it.type == "sub" && it.title == asset.title && it.language.orEmpty() == asset.language }?.id)
                }
                for (slot in 0..1) {
                    val selected = externalSubtitles.firstOrNull { it.selection == slot }
                    if (selected != null && selected.nativeId == null) continue
                    val propertyName = if (slot == 0) "sid" else "secondary-sid"
                    val result = native.mpv_set_property_string(handle, propertyName, selected?.nativeId?.toString() ?: "no")
                    if (result < 0) mutableState.update { it.copy(operationError = "Native player $propertyName: ${native.mpv_error_string(result)}") }
                }
            }
            lastTrackPoll = 0L
        }

        private fun saveScreenshot(native: MpvNative, handle: Pointer, action: Action.Screenshot) {
            if (!action.completion.isActive) return
            val destination = action.destination
            require(destination.fileName.toString().endsWith(".png", ignoreCase = true)) { "截图请使用 PNG 文件名。" }
            check(!Files.exists(destination)) { "截图目标文件已存在。" }
            val directory = requireNotNull(destination.parent)
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, ".bilipai-screenshot-", ".png")
            try {
                checkResult(native, native.mpv_command(handle, StringArray(arrayOf(
                    "screenshot-to-file", temporary.toString(), if (action.includeSubtitles) "subtitles" else "video",
                ), "UTF-8")), "screenshot")
                check(Files.size(temporary) > 0) { "原生播放器未写出截图。" }
                if (!action.completion.isActive) return
                Files.move(temporary, destination)
                action.completion.complete(destination)
            } finally { Files.deleteIfExists(temporary) }
        }

        private fun receiveEvent(native: MpvNative, handle: Pointer, event: Pointer) {
            // mpv_event x64 ABI: int event, int error, uint64 userdata, void* data.
            when (event.getInt(0)) {
                1 -> closing.set(true) // MPV_EVENT_SHUTDOWN
                6 -> { // MPV_EVENT_START_FILE
                    activeEntry = event.getPointer(16)?.getLong(0)
                    fileLoaded = false
                    loadedSubtitlePaths.clear()
                    mutableState.update { it.copy(loading = true, ended = false, error = null) }
                }
                8 -> { // MPV_EVENT_FILE_LOADED
                    fileLoaded = true
                    mutableState.update { it.copy(loading = false, ended = false, error = null) }
                    restoreSubtitles(native, handle)
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
            val muted = property(native, handle, "mute") == "yes"
            val subtitlesVisible = property(native, handle, "sub-visibility") == "yes"
            val subtitleText = if (fileLoaded && subtitlesVisible) property(native, handle, "sub-text")?.takeIf(String::isNotBlank) else null
            val secondarySubtitleText = if (fileLoaded && subtitlesVisible) property(native, handle, "secondary-sub-text")?.takeIf(String::isNotBlank) else null
            val now = System.nanoTime()
            if (fileLoaded && now - lastTrackPoll > 1_000_000_000L) {
                tracks = readTracks(native, handle)
                lastTrackPoll = now
            }
            mutableState.update {
                it.copy(
                    loading = if (fileLoaded) buffering else it.loading,
                    paused = if (it.ended) true else paused,
                    positionSeconds = position ?: it.positionSeconds,
                    durationSeconds = duration ?: it.durationSeconds,
                    volume = volume ?: it.volume,
                    speed = speed ?: it.speed,
                    videoCodec = if (fileLoaded) videoCodec else null,
                    videoWidth = if (fileLoaded) property(native, handle, "video-params/dw")?.toIntOrNull() ?: 0 else 0,
                    videoHeight = if (fileLoaded) property(native, handle, "video-params/dh")?.toIntOrNull() ?: 0 else 0,
                    audioCodec = if (fileLoaded) audioCodec else null,
                    avSyncSeconds = if (fileLoaded) avSync else null,
                    muted = muted,
                    subtitlesVisible = subtitlesVisible,
                    subtitleText = subtitleText,
                    secondarySubtitleText = secondarySubtitleText,
                    tracks = if (fileLoaded) tracks else emptyList(),
                )
            }
        }

        private fun readTracks(native: MpvNative, handle: Pointer): List<PlayerTrack> {
            val count = property(native, handle, "track-list/count")?.toIntOrNull()?.coerceIn(0, 200) ?: return emptyList()
            return (0 until count).mapNotNull { index ->
                val prefix = "track-list/$index"
                val id = property(native, handle, "$prefix/id")?.toIntOrNull() ?: return@mapNotNull null
                val type = property(native, handle, "$prefix/type") ?: return@mapNotNull null
                PlayerTrack(id, type, property(native, handle, "$prefix/title"), property(native, handle, "$prefix/lang"),
                    property(native, handle, "$prefix/selected") == "yes", property(native, handle, "$prefix/external") == "yes",
                    property(native, handle, "$prefix/main-selection")?.toIntOrNull())
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
