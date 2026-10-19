package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.DanmakuProto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v0.4.6 protocol hardening in the Windows window loader: a corrupt or truncated protobuf segment is detected by the strict
 * parser, counted, evicted from the loader cache (and offered to the shared repository port, which playback does not fill),
 * and retried after the upstream interval instead of showing as a silently empty segment. A failed fetch is retried the
 * same way; a local offline session is never retried. Memory sources and a fake clock only: no network, window or GPU.
 */
class DanmakuWindowLoaderCorruptSegmentTest {
    /** The five malformed payloads of upstream DanmakuProtoPolicyTest.parseReply_distinguishesTruncatedPayloadsFromValidEmptySegments. */
    private val malformed = listOf(
        byteArrayOf(0x0a, 0x02, 0x38),
        byteArrayOf(0x80.toByte()),
        byteArrayOf(0x1d, 0x00),
        byteArrayOf(0x00),
        byteArrayOf(0x0a, 0x01, 0x80.toByte()),
    )

    private class Clock(var now: Long = 1_000_000L) { fun read(): Long = now }

    private class Source(val total: Int = 3, val offline: Boolean = false) : DesktopDanmakuSource {
        val requested = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val replies = mutableMapOf<Int, ByteArray>()
        /** Remaining fetch failures per segment index: while positive, segment() throws and counts one down. */
        val failures = mutableMapOf<Int, Int>()
        var xmlRequests = 0
        var metadataRequests = 0
        override val offlineSegmentCount: Int? get() = if (offline) total else null
        override suspend fun metadata(cid: Long, aid: Long): ByteArray {
            metadataRequests++
            return field(4, bytes = field(1, value = 360_000) + field(2, value = total.toLong()))
        }
        override suspend fun segment(cid: Long, index: Int): ByteArray {
            requested += index
            val remaining = failures[index] ?: 0
            if (remaining > 0) {
                failures[index] = remaining - 1
                throw IOException("fixture segment $index unavailable")
            }
            return replies[index] ?: segmentBytes(index)
        }
        override suspend fun xml(cid: Long): ByteArray {
            xmlRequests++
            return """<i><d p="1,1,25,16777215">XML fallback</d></i>""".toByteArray()
        }
        override suspend fun special(url: String): ByteArray = byteArrayOf()
    }

    @Test fun `a truncated segment is detected counted evicted and never half shown`() = runBlocking {
        val source = Source()
        source.replies[2] = malformed[0]
        val shared = mutableListOf<Pair<Long, Int>>()
        val loader = DanmakuWindowLoader(source, cid = 42, invalidateShared = { cid, index -> shared += cid to index })
        val initial = loader.initial()
        assertEquals(listOf(1, 2), initial.segments)
        assertEquals(listOf(2), initial.failedSegments)
        assertEquals(1, initial.document.failedSegmentCount)
        assertEquals(listOf("segment 1"), initial.document.comments.map { it.text })
        assertTrue(initial.warning.orEmpty().contains("1/2"), initial.warning)
        assertTrue(initial.warning.orEmpty().contains("1 段数据损坏"), initial.warning)
        assertEquals(listOf(42L to 2), shared)
        // Only the good segment's bytes stay in the loader cache: the corrupt bytes were evicted.
        assertEquals(segmentBytes(1).size.toLong(), loader.cachedBytes())
    }

    @Test fun `every malformed upstream payload is a failed segment and a valid empty body is not`() = runBlocking {
        malformed.forEachIndexed { index, payload ->
            val source = Source()
            source.replies[2] = payload
            val result = DanmakuWindowLoader(source, cid = 42, invalidateShared = { _, _ -> }).initial()
            assertEquals(listOf(2), result.failedSegments, "malformed payload #$index")
            assertEquals(listOf("segment 1"), result.document.comments.map { it.text })
        }
        val source = Source()
        source.replies[2] = byteArrayOf()
        val shared = mutableListOf<Int>()
        val loader = DanmakuWindowLoader(source, cid = 42, invalidateShared = { _, index -> shared += index })
        val result = loader.initial()
        assertEquals(emptyList<Int>(), result.failedSegments)
        assertNull(result.warning)
        assertEquals(0, result.document.failedSegmentCount)
        assertEquals(emptyList<Int>(), shared)
        assertEquals(listOf("segment 1"), result.document.comments.map { it.text })
        // An empty body is cached like any successful segment.
        assertEquals(segmentBytes(1).size.toLong(), loader.cachedBytes())
    }

    @Test fun `a corrupt segment is retried after the upstream interval and the healed segment replaces the warning`() = runBlocking {
        val clock = Clock()
        val source = Source()
        source.replies[2] = malformed[0]
        val loader = DanmakuWindowLoader(source, cid = 42, nowMs = clock::read, invalidateShared = { _, _ -> })
        assertEquals(listOf(2), loader.initial().failedSegments)
        // Not due one millisecond before the interval ends: nothing is fetched and nothing is republished.
        clock.now += DanmakuWindowLoader.SEGMENT_RETRY_INTERVAL_MS - 1
        assertNull(loader.move(1_000))
        assertEquals(1, source.requested.count { it == 2 })
        // Healed on the server, due now: only the missing segment goes to the network again.
        source.replies.remove(2)
        clock.now += 1
        val healed = assertNotNull(loader.move(1_000))
        assertEquals(emptyList<Int>(), healed.failedSegments)
        assertNull(healed.warning)
        assertEquals(0, healed.document.failedSegmentCount)
        assertEquals(listOf("segment 1", "segment 2"), healed.document.comments.map { it.text })
        assertEquals(2, source.requested.count { it == 2 })
        assertEquals(1, source.requested.count { it == 1 })
        assertNull(loader.move(1_000))
        assertEquals(3, source.requested.size)
    }

    @Test fun `a segment that stays corrupt is retried on the interval and the identical result is not republished`() = runBlocking {
        val clock = Clock()
        val source = Source()
        source.replies[2] = malformed[2]
        val shared = mutableListOf<Int>()
        val loader = DanmakuWindowLoader(source, cid = 42, nowMs = clock::read, invalidateShared = { _, index -> shared += index })
        loader.initial()
        repeat(3) { round ->
            clock.now += DanmakuWindowLoader.SEGMENT_RETRY_INTERVAL_MS
            assertNull(loader.move(1_000), "round $round brings the same corrupt bytes")
            assertEquals(round + 2, source.requested.count { it == 2 })
            // The next attempt waits a full interval again.
            assertNull(loader.move(1_000))
            assertEquals(round + 2, source.requested.count { it == 2 })
        }
        assertEquals(listOf(2, 2, 2, 2), shared)
    }

    @Test fun `a window whose every segment is corrupt falls back to XML once and stops retrying`() = runBlocking {
        val clock = Clock()
        val source = Source()
        source.replies[1] = malformed[1]
        source.replies[2] = malformed[3]
        val shared = mutableListOf<Int>()
        val loader = DanmakuWindowLoader(source, cid = 42, nowMs = clock::read, invalidateShared = { _, index -> shared += index })
        val initial = loader.initial()
        assertEquals(DanmakuFormat.XML, initial.format)
        assertEquals(listOf("XML fallback"), initial.document.comments.map { it.text })
        assertEquals(emptyList<Int>(), initial.segments)
        assertEquals(listOf(1, 2), shared.sorted())
        assertEquals(0L, loader.cachedBytes())
        clock.now += 10 * DanmakuWindowLoader.SEGMENT_RETRY_INTERVAL_MS
        assertNull(loader.move(1_000))
        assertEquals(1, source.xmlRequests)
        assertEquals(2, source.requested.size)
    }

    @Test fun `a failing shared cache port never breaks loading the remaining segments`() = runBlocking {
        val source = Source()
        source.replies[2] = malformed[4]
        val loader = DanmakuWindowLoader(source, cid = 42, invalidateShared = { _, _ -> error("shared cache unavailable") })
        val result = loader.initial()
        assertEquals(listOf(2), result.failedSegments)
        assertEquals(listOf("segment 1"), result.document.comments.map { it.text })
    }

    @Test fun `a failed fetch is retried after the interval and heals without a corrupt segment report`() = runBlocking {
        val clock = Clock()
        val source = Source()
        source.failures[2] = 1
        val shared = mutableListOf<Int>()
        val loader = DanmakuWindowLoader(source, cid = 42, nowMs = clock::read, invalidateShared = { _, index -> shared += index })
        val initial = loader.initial()
        // A fetch failure is a missing segment, not a corrupt one: nothing is counted as failed and nothing is evicted.
        assertEquals(emptyList<Int>(), initial.failedSegments)
        assertEquals(0, initial.document.failedSegmentCount)
        assertEquals(listOf("segment 1"), initial.document.comments.map { it.text })
        assertTrue(initial.warning.orEmpty().contains("1/2"), initial.warning)
        assertTrue(!initial.warning.orEmpty().contains("损坏"), initial.warning)
        // Not due one millisecond before the interval ends: nothing is fetched and nothing is republished.
        clock.now += DanmakuWindowLoader.SEGMENT_RETRY_INTERVAL_MS - 1
        assertNull(loader.move(1_000))
        assertEquals(1, source.requested.count { it == 2 })
        // Due now: only the missing segment goes to the network again and the warning disappears.
        clock.now += 1
        val healed = assertNotNull(loader.move(1_000))
        assertEquals(emptyList<Int>(), healed.failedSegments)
        assertNull(healed.warning)
        assertEquals(0, healed.document.failedSegmentCount)
        assertEquals(listOf("segment 1", "segment 2"), healed.document.comments.map { it.text })
        assertEquals(2, source.requested.count { it == 2 })
        assertEquals(1, source.requested.count { it == 1 })
        assertEquals(emptyList<Int>(), shared)
        assertNull(loader.move(1_000))
        assertEquals(3, source.requested.size)
    }

    @Test fun `an offline session never retries a missing or a corrupt slot`() = runBlocking {
        for (corrupt in listOf(true, false)) {
            val clock = Clock()
            val source = Source(offline = true)
            if (corrupt) source.replies[2] = malformed[0] else source.failures[2] = 1
            val loader = DanmakuWindowLoader(source, cid = 42, nowMs = clock::read, invalidateShared = { _, _ -> })
            val initial = loader.initial()
            assertEquals(if (corrupt) listOf(2) else emptyList<Int>(), initial.failedSegments, "corrupt=$corrupt")
            assertEquals(listOf("segment 1"), initial.document.comments.map { it.text }, "corrupt=$corrupt")
            // The shortfall is still reported, but a local session must not promise a retry that never happens.
            assertTrue(initial.warning.orEmpty().contains("1/2"), initial.warning)
            assertTrue(!initial.warning.orEmpty().contains("重试"), initial.warning)
            // The slot would now read fine, so a retry would be visible; none happens however long playback runs.
            source.replies.remove(2)
            source.failures.remove(2)
            repeat(3) { round ->
                clock.now += 10 * DanmakuWindowLoader.SEGMENT_RETRY_INTERVAL_MS
                assertNull(loader.move(1_000), "corrupt=$corrupt round $round")
            }
            assertEquals(1, source.requested.count { it == 2 }, "corrupt=$corrupt")
            assertEquals(1, source.requested.count { it == 1 }, "corrupt=$corrupt")
            // Local downloads know their slots and never ask the server for metadata.
            assertEquals(0, source.metadataRequests, "corrupt=$corrupt")
        }
    }

    @Test fun `the default shared eviction port is wired and loads exactly like an explicit no-op port`() = runBlocking {
        // invalidateShared is left at its default, DanmakuRepository::invalidateDanmakuSegment. Playback reads segments straight
        // from the transport and never fills that repository cache, so there the call is a forward-compatible no-op; this only
        // proves the default port resolves, is harmless and changes nothing about the window.
        val source = Source()
        source.replies[2] = malformed[0]
        val withDefault = DanmakuWindowLoader(source, cid = 42).initial()
        val other = Source()
        other.replies[2] = malformed[0]
        val withNoOp = DanmakuWindowLoader(other, cid = 42, invalidateShared = { _, _ -> }).initial()
        assertEquals(listOf(2), withDefault.failedSegments)
        assertEquals(withNoOp.failedSegments, withDefault.failedSegments)
        assertEquals(withNoOp.document.comments.map { it.text }, withDefault.document.comments.map { it.text })
        assertEquals(withNoOp.warning, withDefault.warning)
        assertEquals(2, source.requested.size)
    }

    @Test fun `the parser skips a failed segment whole and counts it without trusting its state flag`() {
        val good = segmentBytes(1)
        // A complete element followed by a truncated one: the first element must not be half shown.
        val halfBroken = good + byteArrayOf(0x0a, 0x02, 0x38)
        val stateOne = field(2, value = 1) + byteArrayOf(0x0a, 0x02, 0x38)
        val window = DanmakuParser.parseProtobufWindow(listOf(good, halfBroken, byteArrayOf(), stateOne, segmentBytes(2)))
        assertEquals(listOf(1, 3), window.failedPositions)
        assertEquals(2, window.document.failedSegmentCount)
        assertEquals(listOf("segment 1", "segment 2"), window.document.comments.map { it.text })
        assertTrue(!window.document.serverDisabled)
        // The unchanged entry point keeps its shape and exposes the same count.
        assertEquals(2, DanmakuParser.parseProtobuf(listOf(good, halfBroken, byteArrayOf(), stateOne, segmentBytes(2))).failedSegmentCount)
        assertEquals(0, DanmakuParser.parseProtobuf(listOf(byteArrayOf())).failedSegmentCount)
    }

    @Test fun `an exception while a decoded segment is processed fails that segment whole and a cancellation still propagates`() {
        val one = segmentBytes(1)
        val two = segmentBytes(2)
        val three = segmentBytes(3)
        // Upstream wraps each segment in try/catch: the failure is counted and the other segments still decode.
        val window = DanmakuParser.parseProtobufWindow(listOf(one, two, three)) { bytes ->
            if (bytes.contentEquals(two)) throw IllegalStateException("element adapter failed")
            DanmakuProto.parseReply(bytes)
        }
        assertEquals(listOf(1), window.failedPositions)
        assertEquals(1, window.document.failedSegmentCount)
        assertEquals(listOf("segment 1", "segment 3"), window.document.comments.map { it.text })
        assertTrue(!window.document.serverDisabled)
        // A cancelled caller is never turned into a failed segment.
        assertFailsWith<CancellationException> {
            DanmakuParser.parseProtobufWindow(listOf(one)) { throw CancellationException("cancelled") }
        }
        // The default decoder is still the strict upstream one.
        assertEquals(emptyList<Int>(), DanmakuParser.parseProtobufWindow(listOf(one, two, three)).failedPositions)
    }

    companion object {
        private fun segmentBytes(index: Int): ByteArray =
            field(1, bytes = field(1, value = index.toLong()) + field(2, value = (index - 1) * 360_000L + 1_000) +
                field(7, bytes = "segment $index".toByteArray()))

        /** Test fixture encoder only; production reads the unchanged upstream decoder. */
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
