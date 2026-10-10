package com.bilipai.desktop.player

import com.bilipai.desktop.ui.desktopVideoEnhancementCompactLabel
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopVideoEnhancementConfiguration
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
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
    fun recordOutput(transform: (PlayerVideoOutputState) -> PlayerVideoOutputState) {
        @Suppress("UNCHECKED_CAST")
        val output = field("mutableVideoOutput").get(player) as MutableStateFlow<PlayerVideoOutputState>
        output.value = transform(output.value)
    }
    fun recordDriverNativePatch() {
        type.getDeclaredField("nativeResolutionPatchAvailable").apply { isAccessible = true }.setBoolean(actor, true)
        @Suppress("UNCHECKED_CAST")
        val observed = field("mutableNvidiaVideo").get(player) as MutableStateFlow<NvidiaVideoState>
        observed.value = observed.value.copy(nativeResolutionPatchAvailable = true)
    }
    fun recordVeyra(binding: DesktopVeyraVerifiedBinding, sourceVersion: Long = snapshot.sourceVersion) {
        type.getDeclaredField("veyraBinding").apply { isAccessible = true }.set(actor, binding)
        @Suppress("UNCHECKED_CAST")
        val observed = field("mutableNvidiaVideo").get(player) as MutableStateFlow<NvidiaVideoState>
        observed.value = observed.value.copy(veyraAvailable = true, sourceVersion = sourceVersion)
        @Suppress("UNCHECKED_CAST")
        val output = field("mutableVideoOutput").get(player) as MutableStateFlow<PlayerVideoOutputState>
        output.value = output.value.copy(inputPrimaries = "bt.709")
    }
    fun recordHdrDisplayTarget() {
        recordOutput { it.copy(hdrDisplay = WindowsHdrDisplayState(known = true, hdrSupported = true,
            hdrUserEnabled = true, hdrActive = true, hdrEnabled = true)) }
        @Suppress("UNCHECKED_CAST")
        val observed = field("mutableNvidiaVideo").get(player) as MutableStateFlow<NvidiaVideoState>
        observed.value = observed.value.copy(targetTransfer = "pq", targetPrimaries = "bt.2020")
    }
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
        if (command[1] == "add") filters += (if (command[2].substringAfter(':').startsWith("bilipai-rtx=")) "bilipai-rtx" else "d3d11vpp") to
            command[2].substringAfter('@').substringBefore(':')
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
    @TempDir lateinit var enhancementSettingsRoot: Path


    private fun fixtureSharedCoreBinding(): DesktopVeyraVerifiedBinding {
        val hash = "a".repeat(64)
        val identity = DesktopVeyraInstalledIdentity("fixture", hash, hash, "fixture", hash, hash, hash, hash)
        return DesktopVeyraVerifiedBinding(Path.of("C:/fixture/mpv/libmpv-2.dll"),
            Path.of("C:/fixture/core/bilipai_veyra_core.dll"), Path.of("C:/fixture/runtime"),
            "00000000-0000-0000-0000-000000000001", hash, identity)
    }

    @Test fun realSessionAndActorRequestNativeSizeCoreProcessingBeforeRendererDownscale() = runBlocking<Unit> {
        for ((width, height) in listOf(3840 to 2160, 7680 to 4320)) {
            val enabled = MutableStateFlow(true)
            MpvPlayer().use { player ->
                player.setVolume(23.0); player.setMuted(true); player.setSpeed(1.25)
                val version = player.loadVersioned(PlaybackSource("file:///C:/core-unity-downscale.avi"))
                val source = assertNotNull(player.currentSourceSnapshot())
                NvidiaWithdrawalActor(player).use { actor ->
                    // Fixture metadata only: real session/actor logic, no DLL, SDK, GPU or HWND.
                    actor.recordPlayableOutput(); actor.recordVeyra(fixtureSharedCoreBinding())
                    actor.recordOutput { it.copy(inputWidth = width, inputHeight = height,
                        displayWidth = width / 2, displayHeight = height / 2) }
                    val playback = player.state.value
                    DesktopVideoEnhancementSession(player, enabled, MutableStateFlow(true), MutableStateFlow(false), {
                        enabled.value = it; CompletableDeferred(Unit)
                    }).use { enhancement ->
                        awaitSession { enhancement.state.value.pending && actor.hasQueuedNvidia() }
                        while (actor.hasQueuedNvidia()) actor.apply()
                        val request = player.nvidiaVideoState.value
                        val add = actor.native.commands.single { it.take(2) == listOf("vf", "add") }
                        assertTrue(add[2].contains(":bilipai-rtx="))
                        assertTrue(add[2].contains(":session=$version:generation=" + request.configurationVersion + ":scale=1.0:quality=4:hdr=no:"))
                        assertEquals(NvidiaVideoBackend.VEYRA_CORE, request.backend)
                        assertEquals(1.0, request.requestedScale)
                        assertTrue(request.nativeResolutionProcessingRequested)
                        assertTrue(request.pending); assertFalse(request.active); assertFalse(request.veyraSubmitted)
                        assertFalse(enhancement.state.value.active)
                        assertEquals(playback, player.state.value)
                        assertTrue(player.ownsSourceSnapshot(source))
                        assertEquals(width, player.videoOutput.value.inputWidth)
                        assertEquals(width / 2, player.videoOutput.value.displayWidth)
                        assertTrue(actor.native.filters.contains("scale" to "foreign-user-filter"))
                        assertNotNull(enhancement.setCurrentVideoEnabled(false)).join()
                        awaitSession { actor.hasQueuedNvidia() && !enhancement.state.value.requested }
                        while (actor.hasQueuedNvidia()) actor.apply()
                        assertEquals(listOf("scale" to "foreign-user-filter"), actor.native.filters)
                        assertEquals(playback, player.state.value)
                        assertTrue(player.ownsSourceSnapshot(source))
                    }
                }
            }
        }
    }

    @Test fun realSessionCannotGrantSharedCoreUnityToAnAbsentRetiredOrUnsupportedCoreInput() = runBlocking<Unit> {
        for (case in listOf("absent-binding", "retired-target", "primaries", "unknown-transfer", "native-pq", "dolby-vision")) {
            val enabled = MutableStateFlow(true)
            MpvPlayer().use { player ->
                val version = player.loadVersioned(PlaybackSource("file:///C:/core-unity-ineligible.avi"))
                NvidiaWithdrawalActor(player).use { actor ->
                    actor.recordPlayableOutput()
                    if (case != "absent-binding") actor.recordVeyra(fixtureSharedCoreBinding(),
                        if (case == "retired-target") version + 1 else version)
                    actor.recordOutput { it.copy(inputWidth = 3840, inputHeight = 2160,
                        displayWidth = 1920, displayHeight = 1080,
                        inputPrimaries = if (case == "primaries") "bt.2020" else "bt.709",
                        gamma = when (case) { "unknown-transfer" -> null; "native-pq" -> "pq"; else -> "bt.1886" },
                        dolbyVisionProfile = if (case == "dolby-vision") 5 else null) }
                    DesktopVideoEnhancementSession(player, enabled, MutableStateFlow(true), MutableStateFlow(false), {
                        enabled.value = it; CompletableDeferred(Unit)
                    }).use { enhancement ->
                        awaitSession { enhancement.state.value.sourceVersion == version && enhancement.state.value.requested }
                        assertFalse(enhancement.state.value.pending, case)
                        assertFalse(enhancement.state.value.active, case)
                        assertFalse(actor.hasQueuedNvidia(), case)
                        assertTrue(actor.native.commands.isEmpty(), case)
                        assertEquals(listOf("scale" to "foreign-user-filter"), actor.native.filters, case)
                    }
                }
            }
        }
    }

    @Test fun realActorPreservesEveryUnityAdmissionBoundaryWithSharedCoreDownscale() {
        for (case in listOf("absent-binding", "source", "odd-width", "odd-height", "unknown-transfer",
            "linear-transfer", "native-pq", "native-hlg", "dolby-vision", "primaries", "texture-limit",
            "empty-viewport", "oversized-viewport", "wrong-vendor", "wrong-context")) {
            MpvPlayer().use { player ->
                val version = player.loadVersioned(PlaybackSource("file:///C:/core-unity-actor-gates.avi"))
                val snapshot = assertNotNull(player.currentSourceSnapshot())
                NvidiaWithdrawalActor(player).use { actor ->
                    actor.recordPlayableOutput()
                    if (case != "absent-binding") actor.recordVeyra(fixtureSharedCoreBinding())
                    actor.recordOutput { it.copy(sourceVersion = if (case == "source") version + 1 else version,
                        inputWidth = if (case == "odd-width") 3839 else if (case == "texture-limit") 7680 else 3840,
                        inputHeight = if (case == "odd-height") 2159 else 2160,
                        displayWidth = if (case == "empty-viewport") 0 else if (case == "oversized-viewport") 3841 else 1920,
                        displayHeight = 1080, maximumTextureDimension = if (case == "texture-limit") 4096 else 16384,
                        inputPrimaries = if (case == "primaries") "bt.2020" else "bt.709",
                        gamma = when (case) { "unknown-transfer" -> null; "linear-transfer" -> "linear";
                            "native-pq" -> "pq"; "native-hlg" -> "hlg"; else -> "bt.1886" },
                        dolbyVisionProfile = if (case == "dolby-vision") 5 else null) }
                    if (case == "wrong-vendor") actor.recordDevice(0x1002, "d3d11")
                    if (case == "wrong-context") actor.recordDevice(0x10de, "vulkan")
                    assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(version,
                        NvidiaVideoOptions(nativeResolutionProcessing = true, backend = NvidiaVideoBackend.VEYRA_CORE)))
                    actor.apply()
                    val rejected = player.nvidiaVideoState.value
                    assertFalse(rejected.active, case); assertFalse(rejected.pending, case)
                    assertTrue(rejected.error != null || rejected.unavailableReason != null, case)
                    assertTrue(actor.native.commands.isEmpty(), case)
                    assertEquals(listOf("scale" to "foreign-user-filter"), actor.native.filters, case)
                    assertTrue(player.ownsSourceSnapshot(snapshot), case)
                }
            }
        }
    }

    @Test fun realDriverUnityAttemptRetainsItsExactViewportRequirement() {
        for (exact in listOf(false, true)) {
            MpvPlayer().use { player ->
                val version = player.loadVersioned(PlaybackSource("file:///C:/driver-unity-viewport.avi"))
                NvidiaWithdrawalActor(player).use { actor ->
                    actor.recordPlayableOutput(); actor.recordDriverNativePatch()
                    actor.recordOutput { it.copy(inputWidth = 3840, inputHeight = 2160,
                        displayWidth = if (exact) 3840 else 1920, displayHeight = if (exact) 2160 else 1080) }
                    assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(version,
                        NvidiaVideoOptions(nativeResolutionProcessing = true)))
                    actor.apply()
                    assertFalse(player.nvidiaVideoState.value.active)
                    if (exact) {
                        assertTrue(actor.native.commands.single()[2].contains(":d3d11vpp=scale=1.0:"))
                        assertTrue(player.nvidiaVideoState.value.pending)
                    } else {
                        assertTrue(actor.native.commands.isEmpty())
                        assertNotNull(player.nvidiaVideoState.value.unavailableReason)
                    }
                }
            }
        }
    }

    @Test fun persistedQualityReplacesOnlyOwnedFilterOnTheSamePlayingSource() = runBlocking<Unit> {
        val configuration = DesktopVideoEnhancementConfiguration(DesktopPluginStore(enhancementSettingsRoot),
            dispatcher = Dispatchers.Default)
        try {
            withTimeout(3000) { configuration.setAutomaticEnabled(true).await() }
            val hash = "a".repeat(64)
            val identity = DesktopVeyraInstalledIdentity("fixture", hash, hash, "fixture", hash, hash, hash, hash)
            val binding = DesktopVeyraVerifiedBinding(Path.of("C:/fixture/mpv/libmpv-2.dll"),
                Path.of("C:/fixture/core/bilipai_veyra_core.dll"), Path.of("C:/fixture/runtime"),
                "00000000-0000-0000-0000-000000000001", hash, identity)
            MpvPlayer().use { player ->
                player.setVolume(23.0); player.setMuted(true); player.setSpeed(1.25)
                val version = player.loadVersioned(PlaybackSource("file:///C:/quality-owned-playing.avi"))
                val source = assertNotNull(player.currentSourceSnapshot())
                NvidiaWithdrawalActor(player).use { actor ->
                    // Memory-only fixture metadata/binding. No verification, SDK, GPU or DLL executes.
                    actor.recordPlayableOutput(); actor.recordVeyra(binding)
                    DesktopVideoEnhancementSession(player, configuration.automaticEnabled, MutableStateFlow(true),
                        MutableStateFlow(false), configuration::setAutomaticEnabled,
                        enhancementPreferences = configuration.preferences).use { enhancement ->
                        awaitSession { enhancement.state.value.pending && actor.hasQueuedNvidia() }
                        while (actor.hasQueuedNvidia()) actor.apply()
                        val first = player.nvidiaVideoState.value.configurationVersion
                        val playback = player.state.value
                        assertTrue(actor.native.commands.last().last().contains(":quality=4:hdr=no:"))
                        withTimeout(3000) { configuration.setQuality(DesktopNvidiaVideoQuality.STANDARD).await() }
                        awaitSession { player.nvidiaVideoState.value.configurationVersion != first &&
                            player.nvidiaVideoState.value.requestedQualityLevel == 2 && actor.hasQueuedNvidia() }
                        while (actor.hasQueuedNvidia()) actor.apply()
                        assertTrue(actor.native.commands.last().last().contains(":quality=2:hdr=no:"))
                        assertEquals(playback, player.state.value)
                        assertEquals(version, player.currentSourceVersion)
                        assertTrue(player.ownsSourceSnapshot(source))
                        assertFalse(enhancement.state.value.active)
                        assertTrue(actor.native.filters.any { it == ("scale" to "foreign-user-filter") })
                        assertTrue(actor.native.commands.all { it.take(2) in listOf(listOf("vf", "add"), listOf("vf", "remove")) })
                        val beforeReplacement = player.nvidiaVideoState.value.configurationVersion
                        player.loadVersioned(PlaybackSource("file:///C:/quality-replacement.avi"))
                        assertNull(player.setNvidiaVideoEnhancementIfSourceSnapshot(source,
                            NvidiaVideoOptions(2.0, backend = NvidiaVideoBackend.VEYRA_CORE, qualityLevel = 3)))
                        assertFalse(player.clearNvidiaVideoEnhancementIfConfigurationVersion(beforeReplacement))
                    }
                }
            }
        } finally { withTimeout(3000) { configuration.flushAndClose() } }
    }

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
    @Test fun realSessionAndActorSwitchThreeContentsOnTheOwnedSource() = runBlocking<Unit> {
        val automatic = MutableStateFlow(true)
        val preferences = MutableStateFlow(DesktopNvidiaVideoPreferences(enabled = true,
            quality = DesktopNvidiaVideoQuality.STANDARD))
        MpvPlayer().use { player ->
            player.setVolume(23.0); player.setMuted(true); player.setSpeed(1.25)
            val version = player.loadVersioned(PlaybackSource("file:///C:/content-owned.avi", startPaused = true))
            val source = assertNotNull(player.currentSourceSnapshot())
            NvidiaWithdrawalActor(player).use { actor ->
                actor.recordPlayableOutput(); actor.recordVeyra(fixtureSharedCoreBinding()); actor.recordHdrDisplayTarget()
                val originalState = player.state.value
                DesktopVideoEnhancementSession(player, automatic, MutableStateFlow(true), MutableStateFlow(false),
                    { automatic.value = it; CompletableDeferred(Unit) }, enhancementPreferences = preferences).use { enhancement ->
                    for (content in DesktopNvidiaVideoContent.entries) {
                        preferences.value = preferences.value.copy(srEnabled = content.srEnabled, hdrMode = content.hdrMode)
                        awaitSession { actor.hasQueuedNvidia() }
                        while (actor.hasQueuedNvidia()) actor.apply()
                        val add = actor.native.commands.last { it.take(2) == listOf("vf", "add") }[2]
                        assertTrue(add.contains(":session=$version:"))
                        assertTrue(add.contains(if (content.srEnabled) ":scale=2.0:quality=2:" else ":scale=1.0:quality=4:"))
                        assertTrue(add.contains(if (content.srEnabled) ":sr=yes:" else ":sr=no:"))
                        assertTrue(add.contains(if (content.hdrMode == DesktopNvidiaVideoHdrMode.AUTO) ":hdr=yes:" else ":hdr=no:"))
                        assertEquals(content.srEnabled, player.nvidiaVideoState.value.srEnabledRequested)
                        assertFalse(player.nvidiaVideoState.value.active)
                        assertFalse(player.nvidiaVideoState.value.driverVsrAccepted)
                        assertFalse(player.nvidiaVideoState.value.veyraSubmitted)
                        assertTrue(actor.native.filters.contains("scale" to "foreign-user-filter"))
                        assertEquals(originalState, player.state.value)
                        assertTrue(player.ownsSourceSnapshot(source))
                    }
                    preferences.value = preferences.value.copy(enabled = false)
                    awaitSession { actor.hasQueuedNvidia() }
                    while (actor.hasQueuedNvidia()) actor.apply()
                    assertEquals(listOf("scale" to "foreign-user-filter"), actor.native.filters)
                    assertEquals(originalState, player.state.value)
                }
            }
        }
    }

    @Test fun hdrOnlySessionCannotFallBackToDriverWhenCoreIsUnavailable() = runBlocking<Unit> {
        val automatic = MutableStateFlow(true)
        val preferences = MutableStateFlow(DesktopNvidiaVideoPreferences(true, DesktopNvidiaVideoQuality.HIGHEST,
            DesktopNvidiaVideoHdrMode.AUTO, false))
        MpvPlayer().use { player ->
            player.loadVersioned(PlaybackSource("file:///C:/hdr-only-no-core.avi"))
            NvidiaWithdrawalActor(player).use { actor ->
                actor.recordPlayableOutput(); actor.recordHdrDisplayTarget()
                DesktopVideoEnhancementSession(player, automatic, MutableStateFlow(true), MutableStateFlow(false),
                    { automatic.value = it; CompletableDeferred(Unit) }, enhancementPreferences = preferences).use { enhancement ->
                    awaitSession { enhancement.state.value.unavailableReason != null }
                    assertFalse(enhancement.state.value.active)
                    assertTrue(actor.native.commands.isEmpty())
                    assertFalse(actor.hasQueuedNvidia())
                    assertEquals(listOf("scale" to "foreign-user-filter"), actor.native.filters)
                }
            }
        }
    }

    @Test fun hdrOnlyActorPreservesSourceHdrDeviceColorTextureAndDisplayGates() {
        for (case in listOf("binding", "source", "native-pq", "native-hlg", "dv", "transfer", "primaries",
            "texture", "display", "viewport", "vendor", "context")) {
            MpvPlayer().use { player ->
                val version = player.loadVersioned(PlaybackSource("file:///C:/hdr-only-gates.avi"))
                val source = assertNotNull(player.currentSourceSnapshot())
                NvidiaWithdrawalActor(player).use { actor ->
                    actor.recordPlayableOutput()
                    if (case != "binding") actor.recordVeyra(fixtureSharedCoreBinding())
                    actor.recordHdrDisplayTarget()
                    actor.recordOutput { it.copy(sourceVersion = if (case == "source") version + 1 else version,
                        gamma = when (case) { "native-pq" -> "pq"; "native-hlg" -> "hlg"; "transfer" -> "linear"; else -> "bt.1886" },
                        dolbyVisionProfile = if (case == "dv") 5 else null,
                        inputPrimaries = if (case == "primaries") "bt.2020" else "bt.709",
                        maximumTextureDimension = if (case == "texture") 320 else 16384,
                        displayWidth = if (case == "viewport") 0 else 1280,
                        hdrDisplay = if (case == "display") WindowsHdrDisplayState() else it.hdrDisplay) }
                    if (case == "vendor") actor.recordDevice(0x1002, "d3d11")
                    if (case == "context") actor.recordDevice(0x10de, "vulkan")
                    assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(version,
                        NvidiaVideoOptions(hdr = true, backend = NvidiaVideoBackend.VEYRA_CORE, srEnabled = false)))
                    actor.apply()
                    assertFalse(player.nvidiaVideoState.value.active, case)
                    assertFalse(player.nvidiaVideoState.value.pending, case)
                    assertTrue(player.nvidiaVideoState.value.error != null || player.nvidiaVideoState.value.unavailableReason != null, case)
                    assertTrue(actor.native.commands.isEmpty(), case)
                    assertEquals(listOf("scale" to "foreign-user-filter"), actor.native.filters, case)
                    assertTrue(player.ownsSourceSnapshot(source), case)
                }
            }
        }
    }

}
