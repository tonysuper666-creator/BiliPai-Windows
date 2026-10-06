package com.bilipai.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.*

/** Real private Screenshot action + worker transaction, with a memory-only C ABI.
 * No DLL, HWND, background native thread, actual decoder or physical pixel acceptance. */
private class SoftwareScreenshotActor(val player: MpvPlayer) : AutoCloseable {
    val native = SoftwareScreenshotNative()
    private val type = MpvPlayer::class.java.declaredClasses.single { it.simpleName == "Session" }
    private val actionType = MpvPlayer::class.java.declaredClasses.single { it.simpleName == "Action" }
    private val source = assertNotNull(player.currentSourceSnapshot())
    private val revision = field("playbackRevision").getLong(player)
    private val actor = type.getDeclaredConstructor(MpvPlayer::class.java, java.lang.Long.TYPE,
        PlaybackSource::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, java.lang.Boolean::class.java,
        DesktopNativePresentationTransfer::class.java, DesktopNativeTerminalPresentation::class.java)
        .apply { isAccessible = true }.newInstance(player, 1L, source.source, source.sourceVersion, revision, null, null, null)
    private val session = field("session")
    private val perform = type.getDeclaredMethod("perform", MpvNative::class.java, Pointer::class.java, actionType)
        .apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private val queue = type.getDeclaredField("commands").apply { isAccessible = true }.get(actor) as LinkedBlockingQueue<Any>
    init {
        session.set(player, actor)
        // This test's current thread is the memory actor owner; no native worker is started.
        type.getDeclaredField("thread").apply { isAccessible = true }.set(actor, Thread.currentThread())
        type.getDeclaredField("fileLoaded").apply { isAccessible = true }.setBoolean(actor, true)
        type.getDeclaredField("activeEntry").apply { isAccessible = true }.set(actor, 7L)
        type.getDeclaredField("expectedEntry").apply { isAccessible = true }.set(actor, 7L)
        @Suppress("UNCHECKED_CAST")
        val state = field("mutableState").get(player) as MutableStateFlow<PlayerState>
        state.value = state.value.copy(ready = true, loading = false, firstVideoFrameReady = true,
            videoCodec = "memory-fixture", nativePaused = false)
    }
    fun screenshot() {
        while (true) {
            val action = assertNotNull(queue.poll(), "Expected real enqueued Screenshot")
            if (action.javaClass.simpleName == "Screenshot") {
                perform.invoke(actor, native, Pointer(1L), action)
                return
            }
        }
    }
    override fun close() { session.set(player, null); native.close() }
    private fun field(name: String) = MpvPlayer::class.java.getDeclaredField(name).apply { isAccessible = true }
}

private class SoftwareScreenshotNative(private val delegate: PremiumRecoveryNative = PremiumRecoveryNative()) :
    MpvNative by delegate, AutoCloseable {
    val values = mutableMapOf("options/screenshot-sw" to "no", "playlist/0/id" to "7", "playlist-count" to "1",
        "pause" to "no", "time-pos" to "1.5", "window-id" to "0x1234")
    val events = mutableListOf<String>()
    val writes = mutableListOf<Pair<String, String>>()
    var rejectYes = false
    var rejectRestore = false
    var rejectCapture = false
    var onCapture: () -> Unit = {}
    private val buffers = mutableSetOf<Memory>()
    override fun mpv_get_property_string(handle: Pointer, name: String): Pointer? {
        events += "read:$name"
        val text = values[name] ?: return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        return Memory(bytes.size + 1L).apply { setString(0, text, "UTF-8") }.also { buffers += it }
    }
    override fun mpv_free(data: Pointer) { (data as? Memory)?.let { buffers.remove(it); it.close() } }
    override fun mpv_set_property_string(handle: Pointer, name: String, value: String): Int {
        check(name == "screenshot-sw"); writes += name to value; events += "set:$value"
        if (value == "no" && rejectRestore) return -12
        values["options/screenshot-sw"] = value
        return if (value == "yes" && rejectYes) -12 else 0 // Even a rejected call may have changed native state.
    }
    override fun mpv_command(handle: Pointer, args: StringArray): Int {
        val command = args.getStringArray(0).toList()
        check(command.size == 3 && command.first() == "screenshot-to-file" && command.last() == "video")
        events += "capture:${values["options/screenshot-sw"]}"
        onCapture()
        if (rejectCapture) return -12
        // Transaction/file lifecycle only; this deliberately is not a claimed PNG or decoded frame.
        Files.write(Path.of(command[1]), byteArrayOf(1, 2, 3))
        return 0
    }
    override fun close() { buffers.forEach { it.close() }; buffers.clear(); delegate.close() }
}

class PlayerSoftwareScreenshotDiagnosticTest {
    private suspend fun withActor(test: suspend CoroutineScope.(MpvPlayer, SoftwareScreenshotActor, Path) -> Unit) = coroutineScope {
        val directory = Files.createTempDirectory("native-software-diagnostic-")
        try {
            MpvPlayer().use { player ->
                player.loadVersioned(PlaybackSource("file:///C:/memory-software-diagnostic.avi"))
                SoftwareScreenshotActor(player).use { actor -> test(player, actor, directory.resolve("cpu.png")) }
            }
        } finally { Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun sameActorSwitchesCapturesRestoresBeforePublishingReceiptWithoutPlaybackMutation() = runBlocking {
        withActor { player, actor, path ->
            val state = player.state.value
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                player.captureSoftwareScreenshotForSource(assertNotNull(player.currentSourceSnapshot()), path)
            }
            actor.screenshot()
            val receipt = request.await()
            assertTrue(receipt.captured && receipt.restored && receipt.sourceRetained)
            assertEquals(listOf("screenshot-sw" to "yes", "screenshot-sw" to "no"), actor.native.writes)
            assertEquals(listOf("no", "yes", "no"), listOf("screenshotSwBefore", "screenshotSwDuring", "screenshotSwAfter").map { receipt.fields[it] })
            assertEquals("4660", receipt.fields["windowIdBefore"])
            assertEquals("4660", receipt.fields["windowIdAfter"])
            assertEquals("true", receipt.fields["sameActorWorker"])
            assertTrue(actor.native.events.indexOf("capture:yes") < actor.native.events.indexOf("set:no"))
            assertEquals(state, player.state.value)
            assertTrue(Files.isRegularFile(path))
        }
    }

    @Test fun existingOrdinaryScreenshotNeverReadsOrChangesSoftwareSelection() = runBlocking {
        withActor { player, actor, path ->
            val request = async(start = CoroutineStart.UNDISPATCHED) { player.captureScreenshot(path, includeSubtitles = false) }
            actor.screenshot()
            assertEquals(path, request.await())
            assertEquals(listOf("capture:no"), actor.native.events)
            assertTrue(actor.native.writes.isEmpty())
        }
    }

    @Test fun oldSourceQueuedCaptureIsRejectedBeforeAnyNativeMutation() = runBlocking {
        withActor { player, actor, path ->
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                player.captureSoftwareScreenshotForSource(assertNotNull(player.currentSourceSnapshot()), path)
            }
            player.loadVersioned(PlaybackSource("file:///C:/successor.avi"))
            val state = player.state.value
            actor.screenshot()
            assertFailsWith<CancellationException> { request.await() }
            assertTrue(actor.native.events.isEmpty())
            assertFalse(Files.exists(path))
            assertEquals(state, player.state.value)
        }
    }

    @Test fun cancelledInFlightCaptureStillRestoresAndNeverPublishesDestination() = runBlocking {
        withActor { player, actor, path ->
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                player.captureSoftwareScreenshotForSource(assertNotNull(player.currentSourceSnapshot()), path)
            }
            actor.native.onCapture = { request.cancel() }
            actor.screenshot()
            assertFailsWith<CancellationException> { request.await() }
            assertEquals("no", actor.native.values["options/screenshot-sw"])
            assertEquals(listOf("screenshot-sw" to "yes", "screenshot-sw" to "no"), actor.native.writes)
            assertFalse(Files.exists(path))
            assertEquals(listOf<Path>(), Files.list(path.parent).use { it.toList() })
        }
    }

    @Test fun captureErrorAndRejectedSelectionEachRestoreWithoutTouchingPlayerError() = runBlocking {
        for (selectionFailure in listOf(false, true)) withActor { player, actor, path ->
            actor.native.rejectCapture = !selectionFailure
            actor.native.rejectYes = selectionFailure
            val state = player.state.value
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                player.captureSoftwareScreenshotForSource(assertNotNull(player.currentSourceSnapshot()), path)
            }
            actor.screenshot()
            val receipt = request.await()
            assertFalse(receipt.captured)
            assertTrue(receipt.restored && receipt.sourceRetained)
            assertEquals("no", receipt.fields["screenshotSwAfter"])
            assertNotNull(receipt.fields["captureErrorType"])
            assertFalse(Files.exists(path))
            assertEquals(state, player.state.value)
        }
    }

    @Test fun restoreFailureCannotDeliverSuccessfulCaptureOrDestination() = runBlocking {
        withActor { player, actor, path ->
            actor.native.rejectRestore = true
            val state = player.state.value
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                player.captureSoftwareScreenshotForSource(assertNotNull(player.currentSourceSnapshot()), path)
            }
            actor.screenshot()
            val receipt = request.await()
            assertFalse(receipt.captured || receipt.restored)
            assertNotNull(receipt.fields["restoreErrorType"])
            assertFalse(Files.exists(path))
            assertEquals(state, player.state.value)
        }
    }

    @Test fun changedNativeEntryOrPauseAfterCaptureRestoresButCannotAuthorizeGpuFollowup() = runBlocking {
        for (property in listOf("playlist/0/id", "pause")) withActor { player, actor, path ->
            actor.native.onCapture = { actor.native.values[property] = if (property == "pause") "yes" else "8" }
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                player.captureSoftwareScreenshotForSource(assertNotNull(player.currentSourceSnapshot()), path)
            }
            actor.screenshot()
            val receipt = request.await()
            assertTrue(receipt.restored)
            assertFalse(receipt.captured || receipt.sourceRetained)
            assertFalse(Files.exists(path))
        }
    }
}
