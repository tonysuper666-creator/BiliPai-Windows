package com.bilipai.desktop.player

import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Window
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

enum class WindowsMediaCommand { PLAY, PAUSE, STOP, NEXT, PREVIOUS, FAST_FORWARD, REWIND }

data class WindowsMediaSnapshot(
    val title: String,
    val artist: String = "",
    val mediaId: String = "",
    val state: PlayerState,
    val isAudio: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val enabled: Boolean = true,
)

data class WindowsMediaSessionStatus(
    val available: Boolean = false,
    val error: String? = null,
    val publishedTitle: String = "",
    val publishedPlaybackStatus: Int = 0,
    val publishedPositionSeconds: Double = 0.0,
)

/**
 * Desktop SMTC, bound to the actual HWND through ISystemMediaTransportControlsInterop.
 * ABI method order and delegate IIDs follow Windows SDK 10.0.26100.0 windows.media.h.
 * COM work is confined to one MTA thread; transport callbacks always enter the Swing UI thread.
 */
class WindowsMediaSession(
    window: Window,
    private val onCommand: (WindowsMediaCommand) -> Unit,
    private val onSeek: (Double) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val snapshot = AtomicReference<WindowsMediaSnapshot?>()
    private val wake = LinkedBlockingQueue<Unit>(1)
    private val mutableStatus = MutableStateFlow(WindowsMediaSessionStatus())
    val status: StateFlow<WindowsMediaSessionStatus> = mutableStatus.asStateFlow()
    // Keep COM callbacks strongly reachable through shutdown, including calls already in flight.
    private var resources: NativeSession? = null
    private val hwnd: Pointer = run {
        check(window.isDisplayable) { "The media session needs an existing native window" }
        Native.getWindowPointer(window)
    }
    private val worker = Thread({ runNative() }, "BiliPai-Windows-SMTC").apply { isDaemon = true; start() }

    fun update(value: WindowsMediaSnapshot) {
        if (closed.get()) return
        snapshot.set(value.copy(title = value.title.replace("\u0000", "").take(2_000),
            artist = value.artist.replace("\u0000", "").take(2_000), mediaId = value.mediaId.replace("\u0000", "").take(2_000)))
        wake.offer(Unit)
    }

    private fun runNative() {
        var initialized = false
        try {
            check(System.getProperty("os.name").startsWith("Windows") && Native.POINTER_SIZE == 8) { "SMTC requires 64-bit Windows" }
            checkHr(WinRt.api.RoInitialize(1), "RoInitialize")
            initialized = true
            val native = NativeSession(hwnd)
            resources = native
            native.open()
            mutableStatus.value = WindowsMediaSessionStatus(available = true)
            while (!closed.get()) {
                wake.poll(1, TimeUnit.SECONDS)
                snapshot.get()?.let(native::update)
            }
        } catch (failure: Throwable) {
            if (!closed.get()) mutableStatus.value = WindowsMediaSessionStatus(error = failure.message ?: "Windows media controls failed")
        } finally {
            runCatching { resources?.close() }
            if (initialized) WinRt.api.RoUninitialize()
            if (closed.get()) mutableStatus.value = WindowsMediaSessionStatus()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            wake.offer(Unit)
            if (Thread.currentThread() !== worker) worker.join(3_000)
        }
    }

    private fun dispatch(action: () -> Unit) {
        if (!closed.get()) SwingUtilities.invokeLater { if (!closed.get()) action() }
    }

    private inner class NativeSession(private val hwnd: Pointer) : AutoCloseable {
        private var controls: Pointer? = null
        private var extended: Pointer? = null
        private var updater: Pointer? = null
        private var music: Pointer? = null
        private var video: Pointer? = null
        private var timeline: Pointer? = null
        private var buttonToken: Long? = null
        private var seekToken: Long? = null
        private var lastMetadata: List<Any>? = null
        private val buttonDelegate = ComDelegate("0557e996-7b23-5bae-aa81-ea0d671143a4") { args ->
            val value = IntByReference()
            checkHr(call(args, 6, value), "get_Button")
            val action = when (value.value) {
                0 -> WindowsMediaCommand.PLAY
                1 -> WindowsMediaCommand.PAUSE
                2 -> WindowsMediaCommand.STOP
                4 -> WindowsMediaCommand.FAST_FORWARD
                5 -> WindowsMediaCommand.REWIND
                6 -> WindowsMediaCommand.NEXT
                7 -> WindowsMediaCommand.PREVIOUS
                else -> null
            }
            if (action != null) dispatch { onCommand(action) }
        }
        private val seekDelegate = ComDelegate("44e34f15-bdc0-50a7-ace4-39e91fb753f1") { args ->
            val value = LongByReference()
            checkHr(call(args, 6, value), "get_RequestedPlaybackPosition")
            val seconds = (value.value / 10_000_000.0).coerceAtLeast(0.0)
            dispatch { onSeek(seconds) }
        }

        fun open() {
            val factory = activationFactory("Windows.Media.SystemMediaTransportControls", "ddb0472d-c911-4a1f-86d9-dc3d71a95f5a")
            try {
                val result = PointerByReference()
                checkHr(call(factory, 6, hwnd, guid("99fa3ff4-1742-42a6-902e-087d41f965ec"), result), "SMTC.GetForWindow")
                controls = requireNotNull(result.value)
            } finally { release(factory) }
            val control = requireNotNull(controls)
            extended = query(control, "ea98d2f6-7f3c-4af2-a586-72889808efb1")
            updater = getPointer(control, 8, "SMTC.DisplayUpdater")
            // Music/VideoProperties only exist after Type is set to that media type.
            // Asking for both during startup fails with ERROR_NOT_SUPPORTED.
            val activated = activate("Windows.Media.SystemMediaTransportControlsTimelineProperties")
            try { timeline = query(activated, "5125316a-c3a2-475b-8507-93534dc88f15") } finally { release(activated) }
            checkHr(call(control, 11, 0.toByte()), "SMTC.IsEnabled")
            val token = LongByReference()
            checkHr(call(control, 32, buttonDelegate.pointer, token), "SMTC.ButtonPressed")
            buttonToken = token.value
            val seek = LongByReference()
            checkHr(call(requireNotNull(extended), 13, seekDelegate.pointer, seek), "SMTC.PlaybackPositionChangeRequested")
            seekToken = seek.value
        }

        fun update(value: WindowsMediaSnapshot) {
            val control = requireNotNull(controls)
            val ext = requireNotNull(extended)
            val active = value.enabled && value.title.isNotBlank() && value.state.error == null
            checkHr(call(control, 11, if (active) 1.toByte() else 0.toByte()), "SMTC.IsEnabled")
            checkHr(call(control, 13, if (active) 1.toByte() else 0.toByte()), "SMTC.IsPlayEnabled")
            checkHr(call(control, 15, if (active) 1.toByte() else 0.toByte()), "SMTC.IsStopEnabled")
            checkHr(call(control, 17, if (active) 1.toByte() else 0.toByte()), "SMTC.IsPauseEnabled")
            checkHr(call(control, 21, if (active && value.state.durationSeconds > 0) 1.toByte() else 0.toByte()), "SMTC.IsFastForwardEnabled")
            checkHr(call(control, 23, if (active && value.state.durationSeconds > 0) 1.toByte() else 0.toByte()), "SMTC.IsRewindEnabled")
            checkHr(call(control, 25, if (active && value.hasPrevious) 1.toByte() else 0.toByte()), "SMTC.IsPreviousEnabled")
            checkHr(call(control, 27, if (active && value.hasNext) 1.toByte() else 0.toByte()), "SMTC.IsNextEnabled")
            val playbackStatus = when { !active -> 0; value.state.loading -> 1; value.state.ended -> 2; value.state.paused -> 4; else -> 3 }
            checkHr(call(control, 7, playbackStatus), "SMTC.PlaybackStatus")
            val metadata = listOf(value.title, value.artist, value.mediaId, value.isAudio)
            if (metadata != lastMetadata) {
                val display = requireNotNull(updater)
                checkHr(call(display, 7, if (value.isAudio) 1 else 2), "SMTC.MediaPlaybackType")
                putString(display, 9, value.mediaId, "SMTC.AppMediaId")
                music?.let(::release); music = null
                video?.let(::release); video = null
                val properties = if (value.isAudio) {
                    getPointer(display, 12, "SMTC.MusicProperties").also { music = it }
                } else {
                    getPointer(display, 13, "SMTC.VideoProperties").also { video = it }
                }
                putString(properties, 7, value.title, "SMTC.${if (value.isAudio) "Music" else "Video"}.Title")
                putString(properties, if (value.isAudio) 11 else 9, value.artist,
                    "SMTC.${if (value.isAudio) "Music.Artist" else "Video.Subtitle"}")
                checkHr(call(display, 17), "SMTC.Display.Update")
                lastMetadata = metadata
            }
            val speed = value.state.speed.takeIf { it.isFinite() && it > 0 } ?: 1.0
            checkHr(call(ext, 11, speed), "SMTC.PlaybackRate")
            val duration = value.state.durationSeconds.takeIf { it.isFinite() && it > 0 } ?: 0.0
            val position = value.state.positionSeconds.takeIf(Double::isFinite)?.coerceIn(0.0, duration) ?: 0.0
            val span = requireNotNull(timeline)
            checkHr(call(span, 7, 0L), "Timeline.StartTime")
            checkHr(call(span, 9, ticks(duration)), "Timeline.EndTime")
            checkHr(call(span, 11, 0L), "Timeline.MinSeekTime")
            checkHr(call(span, 13, ticks(duration)), "Timeline.MaxSeekTime")
            checkHr(call(span, 15, ticks(position)), "Timeline.Position")
            checkHr(call(ext, 12, span), "SMTC.UpdateTimelineProperties")
            val readStatus = IntByReference()
            checkHr(call(control, 6, readStatus), "SMTC read PlaybackStatus")
            val readPosition = LongByReference()
            checkHr(call(span, 14, readPosition), "SMTC read Timeline.Position")
            val readTitle = getString(if (value.isAudio) requireNotNull(music) else requireNotNull(video), 6,
                "SMTC read ${if (value.isAudio) "Music" else "Video"}.Title")
            mutableStatus.value = WindowsMediaSessionStatus(true, publishedTitle = readTitle,
                publishedPlaybackStatus = readStatus.value, publishedPositionSeconds = readPosition.value / 10_000_000.0)
        }

        override fun close() {
            seekToken?.let { token -> extended?.let { call(it, 14, token) } }; seekToken = null
            buttonToken?.let { token -> controls?.let { call(it, 33, token) } }; buttonToken = null
            controls?.let { call(it, 11, 0.toByte()); call(it, 7, 0) }
            listOf(timeline, video, music, updater, extended, controls).forEach { it?.let(::release) }
            timeline = null; video = null; music = null; updater = null; extended = null; controls = null
        }
    }

    /** IUnknown delegate with SDK's exact parameterized IID; no IInspectable slots. */
    private class ComDelegate(iid: String, action: (Pointer) -> Unit) {
        private val refs = AtomicInteger(1)
        private val supported = listOf(iid, "00000000-0000-0000-c000-000000000046", "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90")
            .map { guid(it).getByteArray(0, 16) }
        private val table = Memory((4 * Native.POINTER_SIZE).toLong())
        val pointer = Memory(Native.POINTER_SIZE.toLong())
        private val query = object : QueryCallback {
            override fun invoke(self: Pointer, iid: Pointer, result: Pointer): Int {
                result.setPointer(0, null)
                if (supported.none { it.contentEquals(iid.getByteArray(0, 16)) }) return 0x80004002.toInt()
                result.setPointer(0, pointer); refs.incrementAndGet(); return 0
            }
        }
        private val addRef = object : RefCallback { override fun invoke(self: Pointer): Int = refs.incrementAndGet() }
        private val release = object : RefCallback { override fun invoke(self: Pointer): Int = refs.decrementAndGet().coerceAtLeast(0) }
        private val invoke = object : InvokeCallback {
            override fun invoke(self: Pointer, sender: Pointer?, args: Pointer?): Int {
                return try { if (args != null) action(args); 0 } catch (_: Throwable) { 0x80004005.toInt() }
            }
        }
        init {
            listOf(query, addRef, release, invoke).forEachIndexed { index, callback ->
                table.setPointer((index * Native.POINTER_SIZE).toLong(), CallbackReference.getFunctionPointer(callback))
            }
            pointer.setPointer(0, table)
        }
    }

    private interface QueryCallback : StdCallLibrary.StdCallCallback { fun invoke(self: Pointer, iid: Pointer, result: Pointer): Int }
    private interface RefCallback : StdCallLibrary.StdCallCallback { fun invoke(self: Pointer): Int }
    private interface InvokeCallback : StdCallLibrary.StdCallCallback { fun invoke(self: Pointer, sender: Pointer?, args: Pointer?): Int }

    private interface WinRt : StdCallLibrary {
        fun RoInitialize(initType: Int): Int
        fun RoUninitialize()
        fun WindowsCreateString(value: WString, length: Int, result: PointerByReference): Int
        fun WindowsDeleteString(value: Pointer): Int
        fun WindowsGetStringRawBuffer(value: Pointer?, length: IntByReference): Pointer?
        fun RoGetActivationFactory(name: Pointer, iid: Pointer, result: PointerByReference): Int
        fun RoActivateInstance(name: Pointer, result: PointerByReference): Int
        companion object { val api: WinRt by lazy { Native.load("combase", WinRt::class.java) } }
    }

    companion object {
        private fun ticks(seconds: Double): Long = (seconds.coerceIn(0.0, 7 * 24 * 60 * 60.0) * 10_000_000).toLong()
        private fun guid(value: String): Memory {
            val uuid = UUID.fromString(value)
            return Memory(16).apply {
                setInt(0, (uuid.mostSignificantBits ushr 32).toInt())
                setShort(4, (uuid.mostSignificantBits ushr 16).toShort()); setShort(6, uuid.mostSignificantBits.toShort())
                (0..7).forEach { setByte(8 + it.toLong(), (uuid.leastSignificantBits ushr (56 - it * 8)).toByte()) }
            }
        }
        private fun call(instance: Pointer, slot: Int, vararg args: Any?): Int =
            Function.getFunction(instance.getPointer(0).getPointer((slot * Native.POINTER_SIZE).toLong()), Function.ALT_CONVENTION)
                .invokeInt(arrayOf(instance, *args))
        private fun checkHr(result: Int, operation: String) {
            check(result >= 0) { "$operation failed (0x${Integer.toUnsignedString(result, 16)})" }
        }
        private fun release(instance: Pointer) { call(instance, 2) }
        private fun query(instance: Pointer, iid: String): Pointer {
            val output = PointerByReference()
            checkHr(call(instance, 0, guid(iid), output), "QueryInterface $iid")
            return requireNotNull(output.value)
        }
        private fun getPointer(instance: Pointer, slot: Int, operation: String): Pointer {
            val result = PointerByReference(); checkHr(call(instance, slot, result), operation)
            return requireNotNull(result.value)
        }
        private inline fun <T> withString(value: String, block: (Pointer) -> T): T {
            val result = PointerByReference()
            checkHr(WinRt.api.WindowsCreateString(WString(value), value.length, result), "WindowsCreateString")
            // The empty HSTRING is represented by null, which is legal for Windows string APIs.
            val string = result.value ?: Pointer.NULL
            return try { block(string) } finally { if (result.value != null) WinRt.api.WindowsDeleteString(result.value) }
        }
        private fun putString(instance: Pointer, slot: Int, value: String, operation: String) {
            withString(value) { checkHr(call(instance, slot, it), operation) }
        }
        private fun getString(instance: Pointer, slot: Int, operation: String): String {
            val result = PointerByReference()
            checkHr(call(instance, slot, result), operation)
            return try {
                val length = IntByReference()
                val buffer = WinRt.api.WindowsGetStringRawBuffer(result.value, length)
                if (buffer == null || length.value == 0) "" else String(buffer.getCharArray(0, length.value.coerceIn(0, 4_000)))
            } finally { result.value?.let { WinRt.api.WindowsDeleteString(it) } }
        }
        private fun activationFactory(name: String, iid: String): Pointer = withString(name) { string ->
            val result = PointerByReference(); checkHr(WinRt.api.RoGetActivationFactory(string, guid(iid), result), "RoGetActivationFactory")
            requireNotNull(result.value)
        }
        private fun activate(name: String): Pointer = withString(name) { string ->
            val result = PointerByReference(); checkHr(WinRt.api.RoActivateInstance(string, result), "RoActivateInstance")
            requireNotNull(result.value)
        }
    }
}
