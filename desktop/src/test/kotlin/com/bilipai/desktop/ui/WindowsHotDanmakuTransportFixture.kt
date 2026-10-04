package com.bilipai.desktop.ui

import com.bilipai.desktop.danmaku.ApiDesktopDanmakuSource
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.danmaku.DanmakuParser
import com.bilipai.desktop.danmaku.DesktopDanmakuSource
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.lang.reflect.Field
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Opt-in Main fixture transport only. It never creates an Overlay, player, Root,
 * Store or VM, and never writes rawDocument/settings/native state. UI assertions
 * and same-send cancellation remain the actual Main driver's responsibility. */
internal class WindowsHotDanmakuTransportFixture private constructor(
    private val overlay: DanmakuOverlay,
    private val assembly: DesktopOriginalVideoOwnerAssembly,
    private val player: MpvPlayer,
    private val accepted: DesktopOriginalVideoAcceptedPublication,
    private val acceptedReference: AtomicReference<*>,
    private val stillOwned: () -> Boolean,
    private val sourceField: Field,
    private val requestLock: Any,
    private val originalSource: DesktopDanmakuSource,
    private val fixtureSource: FixtureSource,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var restoreSucceeded = false

    // Existing loader also calls this inside its request lock. Only the actual
    // captured token/entry/MPV readbacks belong here, never SessionStore admission.
    private fun owns(): Boolean = !closed.get() && stillOwned() && assembly.owns() &&
        acceptedReference.get() === accepted && player.ownsSourceSnapshot(accepted.nativeSource)

    /** Normal remote-loader entry, with the actual captured CID/AID and full native owner. */
    suspend fun reload() {
        currentCoroutineContext().ensureActive()
        check(owns() && assembly.native.isCurrent(accepted)) { "Original hot-danmaku fixture source retired before load" }
        synchronized(requestLock) {
            check(sourceField.get(overlay) === fixtureSource)
        }
        overlay.load(accepted.request.cid, accepted.request.aid,
            durationSeconds = player.state.value.durationSeconds,
            expectedSourceVersion = accepted.sourceVersion, maskSource = null, stillOwned = ::owns)
        currentCoroutineContext().ensureActive()
        check(owns() && assembly.native.isCurrent(accepted)) { "Original hot-danmaku fixture source retired during load" }
    }

    fun receipt(): JsonObject = buildJsonObject {
        put("scope", "PRIVATE_PB_TRANSPORT_ORIGINAL_OVERLAY_PIPELINE_ONLY")
        put("sameActualOverlay", true); put("newPlayerCreated", false); put("newRootCreated", false)
        put("rawDocumentWrittenByFixture", false); put("settingsWrittenByFixture", false)
        put("vmOrNativeStateWrittenByFixture", false); put("accountRequestSentByFixture", false)
        put("cid", accepted.request.cid); put("aid", accepted.request.aid)
        put("nativeSourceVersion", accepted.sourceVersion)
        put("protobufSha256", fixtureSource.protobufSha256)
        put("rawElementCount", fixtureSource.rawElementCount)
        put("visibleTextCandidates", JsonArray(listOf("高赞验收", "同款验收").map(::JsonPrimitive)))
        put("requests", JsonArray(fixtureSource.requests.toList()))
        put("fieldRestoredByIdentity", restoreSucceeded)
        put("hotUiRendered", false) // This transport receipt is not a UI oracle.
        put("likeOrSendAccepted", false)
    }

    /** Restore the captured field only if this lease still owns its test injection.
     * Do not reload the original public transport or clear a successor's document. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        fixtureSource.retire()
        synchronized(requestLock) {
            if (sourceField.get(overlay) === fixtureSource) {
                sourceField.set(overlay, originalSource)
                check(sourceField.get(overlay) === originalSource)
                restoreSucceeded = true
            }
        }
    }

    companion object {
        /** Fixed reflection into the already captured original binding; no object graph traversal. */
        fun install(assembly: DesktopOriginalVideoOwnerAssembly, player: MpvPlayer,
            accepted: DesktopOriginalVideoAcceptedPublication, token: String,
            stillOwned: () -> Boolean): WindowsHotDanmakuTransportFixture {
            require(System.getProperty("bilipai.validation.featureInput") == "true")
            require(UUID.fromString(token).toString() == token)
            require(System.getProperty("bilipai.rootValidationToken") == token)
            val local = UpdateStorage.existingPathWithoutLinks(Path.of(requireNotNull(System.getenv("LOCALAPPDATA"))))
            val temp = UpdateStorage.existingPathWithoutLinks(Path.of(System.getProperty("java.io.tmpdir")))
            require(local.startsWith(temp) && local != temp &&
                local.fileName.toString().startsWith("BiliPai-v025-root-routes-"))
            val marker = local.resolve(".bilipai-root-validation")
            require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker) &&
                Files.readString(marker) == token)
            require(assembly.owns() && assembly.native.player === player && assembly.native.isCurrent(accepted) &&
                player.ownsSourceSnapshot(accepted.nativeSource) && stillOwned())
            val binding = assembly.environment.danmaku
            require(binding.javaClass == DesktopOriginalVideoOwnerDanmakuBinding::class.java)
            val overlayField = DesktopOriginalVideoOwnerDanmakuBinding::class.java.getDeclaredField("overlay")
                .apply { isAccessible = true }
            val overlay = overlayField.get(binding) as DanmakuOverlay
            val playerField = DanmakuOverlay::class.java.getDeclaredField("player").apply { isAccessible = true }
            require(playerField.get(overlay) === player)
            val sourceField = DanmakuOverlay::class.java.getDeclaredField("source").apply { isAccessible = true }
            require(sourceField.type == DesktopDanmakuSource::class.java)
            val requestLock = DanmakuOverlay::class.java.getDeclaredField("requestLock")
                .apply { isAccessible = true }.get(overlay)
            val acceptedReference = DesktopOriginalVideoNativeOwner::class.java.getDeclaredField("accepted")
                .apply { isAccessible = true }.get(assembly.native) as AtomicReference<*>
            require(acceptedReference.get() === accepted)
            val fixture = FixtureSource(accepted.request.cid, accepted.request.aid,
                player.state.value.durationSeconds) {
                stillOwned() && assembly.owns() && acceptedReference.get() === accepted &&
                    player.ownsSourceSnapshot(accepted.nativeSource)
            }
            // Full receipt validation above/below stays outside Overlay.requestLock.
            val original = synchronized(requestLock) {
                val original = sourceField.get(overlay) as DesktopDanmakuSource
                require(original is ApiDesktopDanmakuSource) { "Unexpected actual Overlay transport or nested injection" }
                sourceField.set(overlay, fixture)
                check(sourceField.get(overlay) === fixture)
                original
            }
            val result = WindowsHotDanmakuTransportFixture(overlay, assembly, player, accepted, acceptedReference,
                stillOwned, sourceField, requestLock, original, fixture)
            try {
                require(result.owns() && assembly.native.isCurrent(accepted))
                return result
            } catch (failure: Throwable) { result.close(); throw failure }
        }
    }

    private class FixtureSource(private val cid: Long, private val aid: Long,
        durationSeconds: Double, private val owns: () -> Boolean) : DesktopDanmakuSource {
        private val retired = AtomicBoolean(false)
        val requests = CopyOnWriteArrayList<JsonObject>()
        private val bytes: ByteArray
        val rawElementCount: Int
        val protobufSha256: String
        init {
            require(cid > 0 && aid >= 0 && durationSeconds.isFinite() && durationSeconds in 1.0..300.0) {
                "This PB fixture is limited to the existing short local replay media"
            }
            val lastMs = (durationSeconds * 1_000).toLong()
            val packet = ByteArrayOutputStream()
            var index = 0L
            // Repeated real wire entries keep a recent eligible pair over the local clip.
            // The original hot policy, not the fixture, chooses the current 15-second window.
            for (position in 0L..lastMs step 1_000L) {
                for ((text, likes) in listOf("高赞验收" to 42L, "同款验收" to 18L)) {
                    val element = field(1, 90_000_001L + index++) + field(2, position) + field(3, 1) +
                        field(4, 25) + field(5, 0xffffff) + field(6, bytes = "fixture-public-hash".toByteArray(Charsets.UTF_8)) +
                        field(7, bytes = text.toByteArray(Charsets.UTF_8)) + field(9, 10) + field(15, likes)
                    packet.write(field(1, bytes = element))
                }
            }
            bytes = packet.toByteArray()
            val parsed = DanmakuParser.parseProtobuf(listOf(bytes)).comments
            require(parsed.size == index.toInt() && parsed.all {
                it.originalElement != null && it.originalLocalItem == null && it.serverId > 0 &&
                    requireNotNull(it.originalElement).like >= 10
            })
            rawElementCount = parsed.size
            protobufSha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        fun retire() { retired.set(true) }
        private suspend fun checkRequest() {
            currentCoroutineContext().ensureActive()
            if (retired.get() || !owns()) throw CancellationException("Owned hot-danmaku fixture transport retired")
        }
        override suspend fun metadata(cid: Long, aid: Long): ByteArray {
            checkRequest(); require(cid == this.cid && aid == this.aid)
            requests += buildJsonObject { put("kind", "metadata"); put("cid", cid); put("aid", aid) }
            return field(4, bytes = field(1, 360_000) + field(2, 1))
        }
        override suspend fun segment(cid: Long, index: Int): ByteArray {
            checkRequest(); require(cid == this.cid && index == 1)
            requests += buildJsonObject { put("kind", "segment"); put("cid", cid); put("index", index) }
            return bytes.copyOf()
        }
        override suspend fun xml(cid: Long): ByteArray = error("Original PB fixture must not fall back to XML")
        override suspend fun special(url: String): ByteArray = error("Original PB fixture has no special transport")
        private fun field(number: Int, value: Long = 0, bytes: ByteArray? = null): ByteArray =
            if (bytes == null) varint(number * 8L) + varint(value)
            else varint(number * 8L + 2) + varint(bytes.size.toLong()) + bytes
        private fun varint(value: Long): ByteArray {
            var remaining = value
            val output = ByteArrayOutputStream()
            do {
                val next = remaining and 0x7f
                remaining = remaining ushr 7
                output.write((next or if (remaining == 0L) 0 else 0x80).toInt())
            } while (remaining != 0L)
            return output.toByteArray()
        }
    }
}
