package com.bilipai.desktop.player

import com.sun.jna.Memory
import org.junit.Assert.*
import org.junit.Test

class MpvBackdropFrameTest {
    @Test fun fiveKFrameProducesBoundedAspectMatchedCopy() {
        val layout = checkNotNull(mpvBackdropRasterLayout(5120, 2880, 20480, 58982400))
        assertEquals(480, layout.outputWidth)
        assertEquals(270, layout.outputHeight)
        assertTrue(layout.outputWidth * layout.outputHeight * 4 <= 518400)
    }

    @Test fun truncatedAndOversizedNativeRastersAreRejectedBeforeCopy() {
        assertNull(mpvBackdropRasterLayout(2, 2, 12, 19))
        assertNull(mpvBackdropRasterLayout(2, 2, 7, 16))
        assertNull(mpvBackdropRasterLayout(Long.MAX_VALUE, 1, 4, 4))
        assertNull(mpvBackdropRasterLayout(2, 2, Long.MIN_VALUE, 16))
        assertNull(mpvBackdropRasterLayout(1, 1, 4, 129L * 1024L * 1024L))
        assertNull(mpvBackdropRasterLayout(1, 1, 4, 4, maximumWidth = 961))
    }

    @Test fun negativeStrideAndRowPaddingDoNotBecomePixels() {
        val layout = checkNotNull(mpvBackdropRasterLayout(2, 2, -12, 20, 2, 2))
        val offsets = mutableListOf<Long>()
        val pixels = copyMpvBackdropRaster(layout) { offset, length ->
            offsets += offset
            assertEquals(8, length)
            if (offset == 0L) byteArrayOf(1, 2, 3, 0, 4, 5, 6, 17)
            else byteArrayOf(7, 8, 9, 23, 10, 11, 12, 0)
        }
        assertEquals(listOf(0L, -12L), offsets)
        assertArrayEquals(byteArrayOf(1, 2, 3, -1, 4, 5, 6, -1, 7, 8, 9, -1, 10, 11, 12, -1), pixels)
    }

    @Test fun downsamplingReadsOnlySelectedRowsAndForcesOpaqueAlpha() {
        val layout = checkNotNull(mpvBackdropRasterLayout(4, 2, 16, 32, 2, 1))
        var reads = 0
        val pixels = copyMpvBackdropRaster(layout) { offset, length ->
            reads++
            assertEquals(0L, offset)
            assertEquals(16, length)
            byteArrayOf(1, 2, 3, 4, 10, 11, 12, 13, 20, 21, 22, 23, 30, 31, 32, 33)
        }
        assertEquals(1, reads)
        assertArrayEquals(byteArrayOf(1, 2, 3, -1, 20, 21, 22, -1), pixels)
    }

    @Test fun unknownOrRetiredNativeNodeNeverReadsUnionPointers() {
        Memory(16).use { node ->
            node.clear()
            node.setInt(8, 99)
            assertNull(copyMpvBackdropNode(node, 1, 2, 3, 480, 270) { true })
            node.setInt(8, 8)
            assertNull(copyMpvBackdropNode(node, 1, 2, 3, 480, 270) { false })
        }
    }

    @Test fun validNativeMapReturnsAnOwnedCopyAfterNodeMemoryIsFreed() {
        val frame = BackdropNode().use { node ->
            checkNotNull(copyMpvBackdropNode(node.root, 71, 82, 93, 2, 2) { true })
        }
        assertEquals(71L, frame.sourceVersion)
        assertEquals(82L, frame.playbackRevision)
        assertEquals(93L, frame.seekSerial)
        assertArrayEquals(byteArrayOf(1, 2, 3, -1, 4, 5, 6, -1,
            7, 8, 9, -1, 10, 11, 12, -1), frame.opaqueBgra)
    }

    @Test fun retirementDuringNativeRowCopyPreventsPublication() {
        BackdropNode().use { node ->
            var admissionReads = 0
            try {
                copyMpvBackdropNode(node.root, 71, 82, 93, 2, 2) { ++admissionReads < 4 }
                fail("Retired native pixels must not be returned")
            } catch (_: kotlinx.coroutines.CancellationException) {
                assertEquals(4, admissionReads)
            }
        }
    }

    /** Real x64 JNA node/list/byte-array layout, without loading a player or creating a window. */
    private class BackdropNode : AutoCloseable {
        private val allocations = mutableListOf<Memory>()
        private fun memory(bytes: Long) = Memory(bytes).also { it.clear(); allocations += it }
        val root = memory(16)
        private val map = memory(24)
        private val values = memory(80)
        private val keys = memory(40)
        private val bytes = memory(16)
        private val array = memory(16)
        init {
            root.setPointer(0, map); root.setInt(8, 8)
            map.setInt(0, 5); map.setPointer(8, values); map.setPointer(16, keys)
            listOf("w", "h", "stride", "format", "data").forEachIndexed { index, name ->
                val key = (name + '\u0000').toByteArray()
                keys.setPointer(index * 8L, memory(key.size.toLong()).also { it.write(0, key, 0, key.size) })
            }
            listOf(2L, 2L, 8L).forEachIndexed { index, value ->
                values.setLong(index * 16L, value); values.setInt(index * 16L + 8L, 4)
            }
            val format = byteArrayOf(98, 103, 114, 97, 0)
            values.setPointer(48, memory(5).also { it.write(0, format, 0, format.size) })
            values.setInt(56, 1)
            bytes.write(0, byteArrayOf(1, 2, 3, 0, 4, 5, 6, 0, 7, 8, 9, 0, 10, 11, 12, 0), 0, 16)
            array.setPointer(0, bytes); array.setLong(8, 16)
            values.setPointer(64, array); values.setInt(72, 9)
        }
        override fun close() { allocations.asReversed().forEach { it.close() } }
    }
}
