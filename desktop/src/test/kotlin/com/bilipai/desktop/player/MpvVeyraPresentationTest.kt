package com.bilipai.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real x64 NODE wire fixture. No driver, native DLL, GPU receipt or clock is fabricated as activation. */
private class PresentationNodeNative(
    private val payload: Map<String, Any>,
    private val delegate: PremiumRecoveryNative = PremiumRecoveryNative(),
) : MpvNative by delegate, AutoCloseable {
    private val buffers = mutableListOf<Memory>()
    var freed = 0
        private set

    private fun memory(bytes: Long): Memory = Memory(bytes).also { it.clear(); buffers.add(it) }
    private fun text(value: String): Memory {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        return memory(bytes.size + 1L).also { it.write(0, bytes, 0, bytes.size) }
    }
    private fun encode(node: Pointer, value: Any) {
        when (value) {
            is Boolean -> { node.setInt(0, if (value) 1 else 0); node.setInt(8, 3) }
            is Long -> { node.setLong(0, value); node.setInt(8, 4) }
            is String -> { node.setPointer(0, text(value)); node.setInt(8, 1) }
            is Map<*, *> -> {
                val list = memory(24)
                val nodes = memory(value.size * 16L)
                val keys = memory(value.size * 8L)
                list.setInt(0, value.size); list.setPointer(8, nodes); list.setPointer(16, keys)
                value.entries.forEachIndexed { index, entry ->
                    keys.setPointer(index * 8L, text(entry.key as String))
                    encode(nodes.share(index * 16L), requireNotNull(entry.value))
                }
                node.setPointer(0, list); node.setInt(8, 8)
            }
            else -> error("Unexpected test NODE value")
        }
    }
    override fun mpv_get_property(handle: Pointer, name: String, format: Int, data: Pointer): Int {
        check(name == "bilipai-rtx-presentation" && format == 6)
        encode(data, payload)
        return 0
    }
    override fun mpv_free_node_contents(node: Pointer) {
        freed++
        buffers.asReversed().forEach { it.close() }
        buffers.clear()
    }
    override fun close() {
        buffers.asReversed().forEach { it.close() }
        buffers.clear()
        delegate.close()
    }
}

class MpvVeyraPresentationTest {
    private fun frameNode(): Map<String, Any> = linkedMapOf(
        "valid" to true, "fresh-renderer" to true, "hdr-output-proved" to true,
        "token-version" to 2L, "session" to 17L, "configuration-generation" to 23L,
        "stream-generation" to 23L, "sequence" to 9L, "pts-numerator" to 123_000L,
        "pts-denominator" to 1_000_000L, "frame-id" to 15L,
        "input-width" to 640L, "input-height" to 360L, "width" to 640L, "height" to 360L,
        "effects" to 2L, "transport" to 2L, "hdr-peak-nits" to 1000L,
        "source-kind" to 1L, "output-intent" to 2L, "epoch" to 1L,
        "present-count" to 10L, "present-refresh-count" to 10L, "sync-qpc" to 100L,
        "dxgi-format" to 24L, "dxgi-color-space" to 12L,
        "target-transfer" to 12L, "target-primaries" to 6L,
        "framebuffer-transfer" to 12L, "framebuffer-primaries" to 6L,
        "adapter-luid-hex" to "0000000000000001",
    )
    private fun node(
        queued: Map<String, Any> = frameNode(),
        displayed: Map<String, Any> = queued,
    ): Map<String, Any> = linkedMapOf(
        "schema" to 2L, "serial" to 1L, "epoch" to 1L, "reason" to 2L,
        "present-hresult" to 0L, "statistics-hresult" to 0L,
        "queued" to queued, "displayed" to displayed,
    )
    private fun read(payload: Map<String, Any>): VeyraPresentationSnapshot? =
        PresentationNodeNative(payload).use { native ->
            MpvVeyraPresentationProperties.read(native, Pointer(1L)).also { assertEquals(1, native.freed) }
        }
    private fun hdrOnly() = NvidiaVideoOptions(hdr = true, backend = NvidiaVideoBackend.VEYRA_CORE, srEnabled = false)
    private fun accept(snapshot: VeyraPresentationSnapshot?, options: NvidiaVideoOptions = hdrOnly()): VeyraPresentedFrame? =
        VeyraPresentationTracker().accept(snapshot, 17, 23,
            NvidiaNativeMessage.VeyraSubmitted(17, 23, 23, 9, 640, 360),
            640, 360, options, actuallyPaused = true, nowNanos = 1000)

    @Test fun actualNodeDecoderAcceptsOnlyExactUnityHdrOnlyTuple() {
        val snapshot = assertNotNull(read(node()))
        assertEquals(2, snapshot.displayed.effects)
        assertEquals(snapshot.displayed.inputWidth, snapshot.displayed.width)
        assertNotNull(accept(snapshot))
        val rejected = listOf(
            mapOf("width" to 1280L), mapOf("height" to 720L), mapOf("effects" to 0L),
            mapOf("transport" to 1L), mapOf("output-intent" to 1L), mapOf("hdr-peak-nits" to 0L),
            mapOf("hdr-peak-nits" to 399L), mapOf("hdr-peak-nits" to 2001L),
            mapOf("source-kind" to 2L), mapOf("hdr-output-proved" to false),
            mapOf("fresh-renderer" to false), mapOf("session" to 0L),
            mapOf("stream-generation" to 22L), mapOf("adapter-luid-hex" to "0000000000000000"),
        )
        rejected.forEach { changes -> assertNull(read(node(frameNode() + changes)), changes.toString()) }
    }

    @Test fun actualNodeSchemaRemainsClosedAndRequiresTypedFlagsAndMeasuredProof() {
        assertNull(read(node() + ("unrecognized" to 1L)))
        assertNull(read(node(frameNode() - "source-kind")))
        assertNull(read(node(frameNode() + ("valid" to 1L))))
        assertNull(read(node(frameNode() + ("adapter-luid-hex" to "FFFFFFFFFFFFFFFF"))))
        assertNull(read(node() + ("schema" to 1L)))
        assertNull(read(node() + ("serial" to 0L)))
        val unproven = assertNotNull(read(node(frameNode() + ("valid" to false))))
        assertNull(accept(unproven))
    }

    @Test fun actualTrackerKeepsHdrOnlyAndCombinedRequestsDistinct() {
        val only = assertNotNull(read(node()))
        val combined = assertNotNull(read(node(frameNode() + ("effects" to 3L))))
        assertNotNull(accept(only))
        assertNull(accept(combined))
        val combinedOptions = hdrOnly().copy(srEnabled = true)
        assertNotNull(accept(combined, combinedOptions))
        assertNull(accept(only, combinedOptions))
        assertNull(accept(only, hdrOnly().copy(scale = 2.0)))
        assertNull(accept(only, hdrOnly().copy(hdr = false)))
        assertNull(accept(only, hdrOnly().copy(backend = NvidiaVideoBackend.DRIVER)))
        assertNull(accept(only, hdrOnly().copy(nativeResolutionProcessing = true)))
        val sdr = assertNotNull(read(node(frameNode() + mapOf(
            "effects" to 1L, "transport" to 1L, "output-intent" to 1L,
            "hdr-peak-nits" to 0L, "hdr-output-proved" to false,
            "dxgi-format" to 28L, "dxgi-color-space" to 0L,
        ))))
        assertNotNull(accept(sdr, NvidiaVideoOptions(backend = NvidiaVideoBackend.VEYRA_CORE)))
        assertNull(accept(sdr))
    }

    @Test fun actualTrackerRequiresOwnedSourceEpochAdapterAndActualHdrPresentation() {
        val mutations: List<(VeyraPresentationSnapshot) -> VeyraPresentationSnapshot> = listOf(
            { it.copy(reason = 1) }, { it.copy(presentHresult = -1) }, { it.copy(statisticsHresult = -1) },
            { it.copy(displayed = it.displayed.copy(session = 18)) },
            { it.copy(displayed = it.displayed.copy(configuration = 24)) },
            { it.copy(displayed = it.displayed.copy(stream = 24)) },
            { it.copy(displayed = it.displayed.copy(epoch = 2)) },
            { it.copy(displayed = it.displayed.copy(adapterLuidHex = "0000000000000002")) },
            { it.copy(displayed = it.displayed.copy(sequence = 8)) },
            { it.copy(displayed = it.displayed.copy(presentCount = 11)) },
            { it.copy(displayed = it.displayed.copy(presentRefreshCount = 0)) },
            { it.copy(displayed = it.displayed.copy(syncQpc = 0)) },
            { it.copy(displayed = it.displayed.copy(framebufferTransfer = 1)) },
            { it.copy(displayed = it.displayed.copy(framebufferPrimaries = 1)) },
            { it.copy(displayed = it.displayed.copy(hdrOutputProved = false)) },
            { it.copy(queued = it.queued.copy(hdrOutputProved = false)) },
            { it.copy(queued = it.queued.copy(frameId = 16)) },
            { it.copy(queued = it.queued.copy(inputWidth = 641)) },
            { it.copy(displayed = it.displayed.copy(dxgiFormat = 28, dxgiColorSpace = 0),
                queued = it.queued.copy(dxgiFormat = 28, dxgiColorSpace = 0)) },
        )
        val snapshot = assertNotNull(read(node()))
        mutations.forEachIndexed { index, mutate -> assertNull(accept(mutate(snapshot)), "case $index") }
        assertTrue(snapshot.displayed.hdrOutputProved)
    }
}
