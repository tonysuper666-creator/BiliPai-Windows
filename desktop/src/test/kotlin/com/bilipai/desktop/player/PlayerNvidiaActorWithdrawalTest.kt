package com.bilipai.desktop.player

import com.bilipai.desktop.ui.desktopVideoEnhancementCompactLabel
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import kotlinx.coroutines.*
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
    private val refreshNvidia = type.getDeclaredMethod("refreshNvidiaVideo", MpvNative::class.java, Pointer::class.java).apply { isAccessible = true }
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
    fun recordPlayableOutput() {
        @Suppress("UNCHECKED_CAST")
        val output = field("mutableVideoOutput").get(player) as MutableStateFlow<PlayerVideoOutputState>
        output.value = output.value.copy(displayWidth = 1280, displayHeight = 720)
        @Suppress("UNCHECKED_CAST")
        val state = field("mutableState").get(player) as MutableStateFlow<PlayerState>
        // This memory-only loaded actor also needs the same-source track readback receipt.
        // READY and dimensions alone must never bypass the production load identity check.
        type.getDeclaredField("activeAttemptId").apply { isAccessible = true }.setLong(actor, 1L)
        for (name in listOf("activeEntry", "expectedEntry"))
            type.getDeclaredField(name).apply { isAccessible = true }.set(actor, 11L)
        val identity = PlayerNativeTrackIdentity(snapshot.sourceVersion, revision, 1L, 11L, snapshot.source)
        state.value = state.value.copy(ready = true, loading = false, firstVideoFrameReady = true,
            nativePaused = false, videoCodec = "fixture-codec", nativeTrackIdentity = identity)
        assertTrue(player.isNativeTrackIdentityCurrent(identity))
    }
    fun hasQueuedNvidia() = queue.any { it.javaClass.simpleName == "NvidiaVideo" }
    fun recordDevice(vendor: Int?, context: String?) {
        @Suppress("UNCHECKED_CAST")
        val observed = field("mutableNvidiaVideo").get(player) as MutableStateFlow<NvidiaVideoState>
        observed.value = observed.value.copy(gpuVendorId = vendor, currentGpuContext = context)
    }
    fun nextNvidia(): Any {
        while (true) {
            val action = assertNotNull(queue.poll(), "Expected a real queued NVIDIA action")
            if (action.javaClass.simpleName == "NvidiaVideo") return action
        }
    }
    fun apply(action: Any = nextNvidia()) { perform.invoke(actor, native, Pointer(1L), action) }
    fun driverFailure() = nativeMessage("d3d11vpp", "Failed to enable NVIDIA RTX Super Resolution: fixture failure")
    fun filterFailure(label: String) = nativeMessage("vf", "Disabling filter $label because it has failed.\n", 20)
    fun acceptedFrame() {
        native.processedOutput = true
        nativeMessage("d3d11vpp", "NVIDIA RTX Super Resolution enabled.")
        Memory(24).use { event ->
            event.clear(); event.setInt(0, 21) // The actual MPV playback-restart event, then ordinary refresh.
            receive.invoke(actor, native, Pointer(1L), event)
        }
        refreshNvidia.invoke(actor, native, Pointer(1L))
    }
    fun retireSession() { session.set(player, null) }
    private fun nativeMessage(prefixValue: String, value: String, level: Int = 50) {
        fun text(value: String) = Memory(value.toByteArray(Charsets.UTF_8).size + 1L).apply { setString(0, value, "UTF-8") }
        text(prefixValue).use { prefix ->
            text(value).use { message ->
                Memory(32).use { data -> Memory(24).use { event ->
                    data.clear(); data.setPointer(0, prefix); data.setPointer(16, message); data.setInt(24, level)
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
    var processedOutput = false
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
    override fun mpv_get_property_string(handle: Pointer, name: String): Pointer? {
        val value = if (processedOutput) when (name) {
            "current-gpu-context" -> "d3d11"
            "video-out-params/w" -> "1280"
            "video-out-params/h" -> "720"
            "video-out-params/gamma", "video-target-params/gamma" -> "bt.1886"
            "video-target-params/primaries" -> "bt.709"
            else -> null
        } else null
        if (value == null) return delegate.mpv_get_property_string(handle, name)
        // The normal property reader frees this memory through delegated mpv_free.
        return Memory(value.toByteArray(Charsets.UTF_8).size + 1L).apply { setString(0, value, "UTF-8") }
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
    @Test fun exactRuntimeDisableReachesRealSessionFromPendingOrActiveAndPreservesRemovalFailure() = runBlocking<Unit> {
        for (mode in listOf("pending", "active", "removal-error")) {
            val enabled = MutableStateFlow(true)
            MpvPlayer().use { player ->
                val source = player.loadVersioned(PlaybackSource("file:///C:/nvidia-runtime-failure.avi"))
                val snapshot = assertNotNull(player.currentSourceSnapshot())
                NvidiaWithdrawalActor(player).use { actor ->
                    actor.recordPlayableOutput()
                    DesktopVideoEnhancementSession(player, enabled, MutableStateFlow(true), MutableStateFlow(false), {
                        enabled.value = it; CompletableDeferred(Unit)
                    }).use { enhancement ->
                        awaitSession { enhancement.state.value.pending && actor.hasQueuedNvidia() }
                        actor.apply()
                        val configuration = player.nvidiaVideoState.value.configurationVersion
                        if (mode != "pending") {
                            actor.acceptedFrame()
                            awaitSession { enhancement.state.value.active }
                        } else assertTrue(enhancement.state.value.pending)
                        val playback = player.state.value
                        actor.native.rejectRemoval = mode == "removal-error"
                        // Simulates the pinned wrapper retaining its configured vf label while disabled.
                        assertTrue(actor.native.filters.any { it.second == "bilipai-nvidia-$configuration" })
                        actor.filterFailure("bilipai-nvidia-$configuration")
                        awaitSession { enhancement.state.value.error != null }
                        assertFalse(enhancement.state.value.active); assertFalse(enhancement.state.value.pending)
                        assertEquals("异常", desktopVideoEnhancementCompactLabel(enhancement.state.value, true, null))
                        assertNull(player.nvidiaVideoState.value.unavailableReason)
                        if (mode == "removal-error") {
                            assertTrue(assertNotNull(enhancement.state.value.error).contains("无法撤回"))
                            assertEquals(2, actor.native.filters.size)
                        } else {
                            assertTrue(assertNotNull(enhancement.state.value.error).contains("运行失败"))
                            assertEquals(listOf("scale" to "foreign-user-filter"), actor.native.filters)
                        }
                        assertEquals(listOf("vf", "remove", "@bilipai-nvidia-$configuration"), actor.native.commands.last())
                        assertEquals(playback, player.state.value); assertTrue(player.ownsSourceSnapshot(snapshot))
                        assertEquals(source, player.currentSourceVersion)
                    }
                }
            }
        }
    }

    @Test fun runtimeDisableCannotActOnForeignOldOrRetiredSourceAccountAndSession() {
        for (retirement in listOf("foreign", "configuration", "source", "revision", "account", "session")) {
            val admitted = java.util.concurrent.atomic.AtomicBoolean(true)
            MpvPlayer().use { player ->
                val source = player.loadVersioned(PlaybackSource("file:///C:/nvidia-runtime-retirement.avi",
                    nativePublication = DesktopNativePlaybackPublication { command ->
                        if (admitted.get()) { command(); true } else false
                    }))
                NvidiaWithdrawalActor(player).use { actor ->
                    val configuration = assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(2.0)))
                    actor.apply()
                    when (retirement) {
                        "configuration" -> {
                            assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(3.0)))
                            actor.apply()
                        }
                        "source" -> player.loadVersioned(PlaybackSource("file:///C:/nvidia-runtime-replacement.avi"))
                        "revision" -> assertTrue(player.recoverSource(source, positionSeconds = 3.0, paused = false))
                        "account" -> admitted.set(false)
                        "session" -> actor.retireSession()
                    }
                    // A new native owner may retain the same source/configuration token.
                    // Its old handle's receiver still cannot act because session !== this.
                    val successor = if (retirement == "session") NvidiaWithdrawalActor(player) else null
                    try {
                        if (successor != null) {
                            assertEquals(configuration, player.nvidiaVideoState.value.configurationVersion)
                            assertEquals(source, player.currentSourceVersion)
                        }
                        val before = player.nvidiaVideoState.value
                        val commands = actor.native.commands.toList()
                        val filters = actor.native.filters.toList()
                        val label = if (retirement == "foreign") "bilipai-nvidia-999999" else "bilipai-nvidia-$configuration"
                        actor.filterFailure(label)
                        actor.filterFailure("foreign-user-filter")
                        assertEquals(before, player.nvidiaVideoState.value, retirement)
                        assertEquals(commands, actor.native.commands, retirement)
                        assertEquals(filters, actor.native.filters, retirement)
                        successor?.let { assertTrue(it.native.commands.isEmpty()) }
                    } finally { successor?.close() }
                }
            }
        }
    }

    @Test fun realSessionKeepsWithdrawalErrorAcrossRepeatedBypassAndRetiresItForNewConfiguration() = runBlocking<Unit> {
        val enabled = MutableStateFlow(true)
        MpvPlayer().use { player ->
            val source = player.loadVersioned(PlaybackSource("file:///C:/nvidia-session-clear.avi"))
            NvidiaWithdrawalActor(player).use { actor ->
                actor.recordPlayableOutput()
                DesktopVideoEnhancementSession(player, enabled, MutableStateFlow(true), MutableStateFlow(false), {
                    enabled.value = it; CompletableDeferred(Unit)
                }).use { enhancement ->
                    awaitSession { enhancement.state.value.pending && actor.hasQueuedNvidia() }
                    actor.apply()
                    assertEquals(2, actor.native.filters.size)
                    val configured = player.nvidiaVideoState.value.configurationVersion
                    assertNotNull(enhancement.setCurrentVideoEnabled(false)).join()
                    awaitSession { !enhancement.state.value.requested &&
                        player.nvidiaVideoState.value.configurationVersion != configured && actor.hasQueuedNvidia() }
                    val withdrawal = actor.nextNvidia()
                    // A normal same-source bypass must not forget the queued clear.
                    enhancement.bindVideoIdentity("same-source-before-withdrawal", source)
                    awaitSession { enhancement.state.value.identity == "same-source-before-withdrawal" }
                    actor.native.rejectRemoval = true
                    actor.apply(withdrawal)
                    awaitSession { enhancement.state.value.error != null }
                    assertTrue(assertNotNull(enhancement.state.value.error).contains("无法撤回"))
                    assertEquals("异常", desktopVideoEnhancementCompactLabel(enhancement.state.value, false, null))
                    assertEquals(2, actor.native.filters.size)
                    enhancement.bindVideoIdentity("same-source-after-withdrawal", source)
                    awaitSession { enhancement.state.value.identity == "same-source-after-withdrawal" && enhancement.state.value.error != null }
                    assertNotNull(enhancement.state.value.error)
                    assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(3.0)))
                    awaitSession { enhancement.state.value.error == null }
                    assertFalse(enhancement.state.value.requested)
                }
            }
        }
    }

    @Test fun realSessionRejectsLateWithdrawalAfterSourceConfigurationAccountOrSessionRetirement() = runBlocking<Unit> {
        for (retirement in listOf("source", "configuration", "account", "session")) {
            val enabled = MutableStateFlow(true)
            val epoch = java.util.concurrent.atomic.AtomicLong(0L)
            MpvPlayer().use { player ->
                val source = player.loadVersioned(PlaybackSource("file:///C:/nvidia-session-late-clear.avi"))
                NvidiaWithdrawalActor(player).use { actor ->
                    actor.recordPlayableOutput()
                    DesktopVideoEnhancementSession(player, enabled, MutableStateFlow(true), MutableStateFlow(false), {
                        enabled.value = it; CompletableDeferred(Unit)
                    }, sessionEpoch = epoch::get).use { enhancement ->
                        awaitSession { enhancement.state.value.pending && actor.hasQueuedNvidia() }
                        actor.apply()
                        val configured = player.nvidiaVideoState.value.configurationVersion
                        assertNotNull(enhancement.setCurrentVideoEnabled(false)).join()
                        awaitSession { !enhancement.state.value.requested &&
                            player.nvidiaVideoState.value.configurationVersion != configured && actor.hasQueuedNvidia() }
                        val withdrawal = actor.nextNvidia()
                        when (retirement) {
                            "source" -> {
                                val replacement = player.loadVersioned(PlaybackSource("file:///C:/nvidia-session-replacement.avi"))
                                enhancement.bindVideoIdentity("replacement", replacement)
                                awaitSession { enhancement.state.value.sourceVersion == replacement }
                            }
                            "configuration" -> assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(3.0)))
                            "account" -> {
                                epoch.incrementAndGet()
                                enhancement.bindVideoIdentity("replacement-account", source)
                                awaitSession { enhancement.state.value.identity == "replacement-account" }
                            }
                            "session" -> enhancement.close()
                        }
                        // Observe receipt retirement under its existing lock; never
                        // call the projection or mutate the session's ownership.
                        val ownership = DesktopVideoEnhancementSession::class.java.getDeclaredField("ownershipLock").apply { isAccessible = true }.get(enhancement)
                        val clearing = DesktopVideoEnhancementSession::class.java.getDeclaredField("clearing").apply { isAccessible = true }
                        awaitSession { synchronized(ownership) { clearing.get(enhancement) == null } }
                        val before = enhancement.state.value
                        val nativeBefore = player.nvidiaVideoState.value
                        actor.native.rejectRemoval = true
                        actor.apply(withdrawal)
                        if (retirement == "source" || retirement == "configuration")
                            assertEquals(nativeBefore, player.nvidiaVideoState.value, retirement)
                        else assertNotNull(player.nvidiaVideoState.value.error, "The actual actor produced the late error: $retirement")
                        assertNull(enhancement.state.value.error, retirement)
                        if (retirement == "session") assertEquals(before, enhancement.state.value)
                    }
                }
            }
        }
    }

    private suspend fun awaitSession(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(10)
    }

    @Test fun confirmedUnsupportedOutputIsUnavailableWithoutAnErrorOrFilter() {
        // Includes the actual cloud vendor ID, another non-NVIDIA vendor, an unsupported
        // native context, and the existing CPU render target. No physical GPU is claimed.
        for ((vendor, context, software) in listOf(
            Triple(0x1414, "d3d11", false), Triple(0x1002, "d3d11", false),
            Triple(0x10de, "vulkan", false), Triple(0x10de, "d3d11", true))) {
            val player = if (software) MpvPlayer(MpvSoftwareTarget()) else MpvPlayer()
            player.use {
                val source = player.loadVersioned(PlaybackSource("file:///C:/nvidia-unavailable.avi"))
                val snapshot = assertNotNull(player.currentSourceSnapshot())
                NvidiaWithdrawalActor(player).use { actor ->
                    actor.recordDevice(vendor, context)
                    assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(2.0)))
                    actor.apply()
                    val native = player.nvidiaVideoState.value
                    assertNotNull(native.unavailableReason); assertNull(native.error)
                    assertFalse(native.active); assertFalse(native.pending)
                    val frame = NvidiaFrameObservation(640, 360, 1280, 720, "bt.1886",
                        "bt.1886", "bt.709", true, true, false)
                    val later = observeNvidiaVideo(native.copy(driverVsrAccepted = true), NvidiaVideoOptions(2.0), frame)
                    assertFalse(later.active); assertFalse(later.pending)
                    assertTrue(actor.native.commands.isEmpty())
                    assertEquals(listOf("scale" to "foreign-user-filter"), actor.native.filters)
                    assertTrue(player.ownsSourceSnapshot(snapshot))
                    val shown = DesktopVideoEnhancementState(requested = true).withNvidiaObservation(native)
                    assertFalse(shown.available); assertNull(shown.error); assertFalse(shown.pending)
                    assertEquals(native.unavailableReason, shown.unavailableReason)
                    assertTrue(shown.statusText.contains("不可用")); assertFalse(shown.statusText.contains("异常"))
                    assertEquals("不可用", desktopVideoEnhancementCompactLabel(shown, true, null))
                    assertEquals("关闭", desktopVideoEnhancementCompactLabel(shown, false, null))
                    assertEquals("异常", desktopVideoEnhancementCompactLabel(shown, true, "settings failed"))
                    player.clearNvidiaVideoEnhancementIfConfigurationVersion(native.configurationVersion)
                    actor.apply()
                    assertNull(player.nvidiaVideoState.value.unavailableReason)
                }
            }
        }
    }

    @Test fun unknownDeviceMetadataStaysPendingRatherThanUnavailableOrFailed() {
        MpvPlayer().use { player ->
            val source = player.loadVersioned(PlaybackSource("file:///C:/nvidia-unknown.avi"))
            NvidiaWithdrawalActor(player).use { actor ->
                actor.recordDevice(null, null)
                assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(2.0)))
                actor.apply()
                val native = player.nvidiaVideoState.value
                assertNull(native.unavailableReason); assertNull(native.error); assertTrue(native.pending)
                assertTrue(actor.native.commands.isEmpty())
                val shown = DesktopVideoEnhancementState(requested = true).withNvidiaObservation(native)
                assertEquals("处理中", desktopVideoEnhancementCompactLabel(shown, true, null))
            }
        }
    }

    @Test fun unavailableHardwareCannotHideAnExistingFilterWithdrawalFailure() {
        MpvPlayer().use { player ->
            val source = player.loadVersioned(PlaybackSource("file:///C:/nvidia-unavailable-remove-error.avi"))
            NvidiaWithdrawalActor(player).use { actor ->
                assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(2.0)))
                actor.apply()
                actor.recordDevice(0x1414, "d3d11")
                actor.native.rejectRemoval = true
                assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(source, NvidiaVideoOptions(3.0)))
                actor.apply()
                val native = player.nvidiaVideoState.value
                assertNull(native.unavailableReason)
                assertTrue(assertNotNull(native.error).contains("无法撤回"))
                assertEquals(2, actor.native.filters.size)
                val shown = DesktopVideoEnhancementState(requested = true).withNvidiaObservation(native)
                assertEquals(native.error, shown.error)
                assertEquals("异常", desktopVideoEnhancementCompactLabel(shown, true, null))
            }
        }
    }

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
                assertNull(player.nvidiaVideoState.value.unavailableReason)
                val shown = DesktopVideoEnhancementState(requested = true).withNvidiaObservation(player.nvidiaVideoState.value)
                assertEquals("异常", desktopVideoEnhancementCompactLabel(shown, true, null))
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
