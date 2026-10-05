package com.bilipai.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.*

/** Real private MPV actor, memory-only C ABI. No thread, HWND, native DLL or GPU is started. */
private class NvidiaWithdrawalActor(val player: MpvPlayer) : AutoCloseable {
    val native = NvidiaWithdrawalNative()
    private val type = MpvPlayer::class.java.declaredClasses.single { it.simpleName == "Session" }
    private val actionType = MpvPlayer::class.java.declaredClasses.single { it.simpleName == "Action" }
    private val snapshot = assertNotNull(player.currentSourceSnapshot())
    private val revision = field("playbackRevision").getLong(player)
    private val actor = type.getDeclaredConstructor(MpvPlayer::class.java, java.lang.Long.TYPE,
        PlaybackSource::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, java.lang.Boolean::class.java,
        DesktopNativePresentationTransfer::class.java, DesktopNativeTerminalPresentation::class.java)
        .apply { isAccessible = true }.newInstance(player, 1L, snapshot.source, snapshot.sourceVersion, revision, null, null, null)
    private val session = field("session")
    private val perform = type.getDeclaredMethod("perform", MpvNative::class.java, Pointer::class.java, actionType).apply { isAccessible = true }
    private val receive = type.getDeclaredMethod("receiveEvent", MpvNative::class.java, Pointer::class.java, Pointer::class.java).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private val queue = type.getDeclaredField("commands").apply { isAccessible = true }.get(actor) as LinkedBlockingQueue<Any>
    init {
        session.set(player, actor)
        type.getDeclaredField("fileLoaded").apply { isAccessible = true }.setBoolean(actor, true)
        @Suppress("UNCHECKED_CAST")
        val output = field("mutableVideoOutput").get(player) as MutableStateFlow<PlayerVideoOutputState>
        // These are fixture decoder/GPU metadata, not a claimed hardware acceptance.
        output.value = PlayerVideoOutputState(snapshot.sourceVersion, maximumTextureDimension = 16384,
            intermediateFormat = "rgba16hf", inputWidth = 640, inputHeight = 360, gamma = "bt.1886")
        @Suppress("UNCHECKED_CAST")
        val observed = field("mutableNvidiaVideo").get(player) as MutableStateFlow<NvidiaVideoState>
        observed.value = observed.value.copy(gpuVendorId = 0x10de, currentGpuContext = "d3d11")
    }
    fun nextNvidia(): Any {
        while (true) {
            val action = assertNotNull(queue.poll(), "Expected a real queued NVIDIA action")
            if (action.javaClass.simpleName == "NvidiaVideo") return action
        }
    }
    fun apply(action: Any = nextNvidia()) { perform.invoke(actor, native, Pointer(1L), action) }
    fun driverFailure() {
        fun text(value: String) = Memory(value.toByteArray(Charsets.UTF_8).size + 1L).apply { setString(0, value, "UTF-8") }
        text("d3d11vpp").use { prefix ->
            text("Failed to enable NVIDIA RTX Super Resolution: fixture failure").use { message ->
                Memory(32).use { data -> Memory(24).use { event ->
                    data.clear(); data.setPointer(0, prefix); data.setPointer(16, message); data.setInt(24, 50)
                    event.clear(); event.setInt(0, 2); event.setPointer(16, data)
                    receive.invoke(actor, native, Pointer(1L), event)
                } }
            }
        }
    }
    override fun close() { session.set(player, null); native.close() }
    private fun field(name: String) = MpvPlayer::class.java.getDeclaredField(name).apply { isAccessible = true }
}

private class NvidiaWithdrawalNative(private val delegate: PremiumRecoveryNative = PremiumRecoveryNative()) :
    MpvNative by delegate, AutoCloseable {
    val commands = mutableListOf<List<String>>()
    val filters = mutableListOf("scale" to "foreign-user-filter")
    var rejectRemoval = false
    private val nodeOwners = mutableListOf<MpvNodes>()
    private val noEvent = Memory(24).apply { clear() }
    override fun mpv_command(handle: Pointer, args: StringArray): Int {
        val command = args.getStringArray(0).toList(); commands += command
        check(command.take(2) in listOf(listOf("vf", "add"), listOf("vf", "remove")))
        if (command[1] == "add") filters += "d3d11vpp" to command[2].substringAfter('@').substringBefore(':')
        else {
            if (rejectRemoval) return -12
            filters.removeAll { it.second == command[2].removePrefix("@") }
        }
        return 0
    }
    override fun mpv_get_property(handle: Pointer, name: String, format: Int, data: Pointer): Int {
        check(name == "vf" && format == 6)
        val owner = MpvNodes(); nodeOwners += owner
        val node = owner.array(filters.map { mapOf("name" to it.first, "label" to it.second) })
        val bytes = node.getByteArray(0, 16); data.write(0, bytes, 0, bytes.size)
        return 0
    }
    override fun mpv_free_node_contents(node: Pointer) { nodeOwners.forEach { it.close() }; nodeOwners.clear() }
    override fun mpv_request_log_messages(handle: Pointer, level: String): Int { check(level == "v"); return 0 }
    override fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer { check(timeout == 0.0); return noEvent }
    fun delegatePropertiesAreEmpty(): Boolean = delegate.properties.isEmpty()
    override fun close() { nodeOwners.forEach { it.close() }; noEvent.close(); delegate.close() }
}

class PlayerNvidiaActorWithdrawalTest {
    @Test fun disablingActualQueuedEnhancementRemovesOnlyItsFilterAndKeepsSourceAndUserControls() {
        MpvPlayer().use { player ->
            player.setVolume(17.0); player.setMuted(true); player.setSpeed(1.25)
            val source = player.loadVersioned(PlaybackSource("file:///C:/nvidia-withdrawal.avi", startPaused = true))
            val before = assertNotNull(player.currentSourceSnapshot())
            NvidiaWithdrawalActor(player).use { actor ->
                val config = assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(2.0)))
                actor.apply()
                assertEquals(2, actor.native.filters.size)
                assertFalse(player.nvidiaVideoState.value.active, "A native command is not processed-frame evidence")
                assertTrue(player.clearNvidiaVideoEnhancementIfConfigurationVersion(config)); actor.apply()
                assertEquals(listOf("scale" to "foreign-user-filter"), actor.native.filters)
                assertEquals(listOf("vf", "remove", "@bilipai-nvidia-$config"), actor.native.commands.last())
                assertTrue(player.ownsSourceSnapshot(before))
                assertEquals(17.0, player.state.value.volume); assertTrue(player.state.value.muted)
                assertTrue(player.state.value.paused); assertEquals(1.25, player.state.value.speed)
                assertEquals("rgba16hf", player.videoOutput.value.intermediateFormat)
                assertTrue(actor.native.delegatePropertiesAreEmpty())
            }
        }
    }

    @Test fun actualDriverFailureWithdrawsOwnFilterWithoutTouchingForeignFiltersOrFabricatingSuccess() {
        MpvPlayer().use { player ->
            val source = player.loadVersioned(PlaybackSource("file:///C:/nvidia-error.avi"))
            val snapshot = assertNotNull(player.currentSourceSnapshot())
            NvidiaWithdrawalActor(player).use { actor ->
                assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(2.0)))
                actor.apply(); actor.driverFailure()
                assertEquals(listOf("scale" to "foreign-user-filter"), actor.native.filters)
                assertFalse(player.nvidiaVideoState.value.active); assertFalse(player.nvidiaVideoState.value.pending)
                assertNotNull(player.nvidiaVideoState.value.error)
                assertTrue(player.ownsSourceSnapshot(snapshot))
            }
        }
    }

    @Test fun aFailedNativeRemovalNeverClaimsThatTheOriginalOutputWasRestored() {
        MpvPlayer().use { player ->
            val source = player.loadVersioned(PlaybackSource("file:///C:/nvidia-remove-error.avi"))
            NvidiaWithdrawalActor(player).use { actor ->
                assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(2.0)))
                actor.apply(); actor.native.rejectRemoval = true; actor.driverFailure()
                assertEquals(2, actor.native.filters.size)
                assertFalse(player.nvidiaVideoState.value.active)
                assertTrue(assertNotNull(player.nvidiaVideoState.value.error).contains("无法撤回"))
            }
        }
    }

    @Test fun aQueuedOldSourceAndItsLateDriverFailureCannotAlterTheReplacement() {
        MpvPlayer().use { player ->
            val first = player.loadVersioned(PlaybackSource("file:///C:/nvidia-old.avi"))
            NvidiaWithdrawalActor(player).use { actor ->
                val oldConfig = assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(first, NvidiaVideoOptions(2.0)))
                val old = actor.nextNvidia()
                val replacement = player.loadVersioned(PlaybackSource("file:///C:/nvidia-new.avi"))
                val expected = player.nvidiaVideoState.value
                actor.apply(old); actor.driverFailure()
                assertEquals(expected, player.nvidiaVideoState.value)
                assertTrue(actor.native.commands.isEmpty())
                assertFalse(player.clearNvidiaVideoEnhancementIfConfigurationVersion(oldConfig))
                assertEquals(replacement, player.currentSourceVersion)
            }
        }
    }
}
