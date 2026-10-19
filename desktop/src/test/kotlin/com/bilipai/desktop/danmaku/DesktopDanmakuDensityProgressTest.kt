package com.bilipai.desktop.danmaku

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The local danmaku density curve that stands in for an empty or 404 pbp heat curve (upstream 918b6924). Memory sources
 * only: no network, window or GPU. The bucketing arithmetic is the pinned upstream buildDanmakuDensityValues.
 */
class DesktopDanmakuDensityProgressTest {
    private class Source(val segments: Map<Int, ByteArray>) : DesktopDanmakuSource {
        val requested = java.util.Collections.synchronizedList(mutableListOf<Int>())
        override suspend fun metadata(cid: Long, aid: Long): ByteArray = byteArrayOf()
        override suspend fun segment(cid: Long, index: Int): ByteArray {
            requested += index
            return segments[index] ?: throw IOException("fixture segment $index unavailable")
        }
        override suspend fun xml(cid: Long): ByteArray = byteArrayOf()
        override suspend fun special(url: String): ByteArray = byteArrayOf()
    }

    @Test fun `standard comments are bucketed by the adaptive step and special modes are ignored`() = runBlocking {
        // 600 s: step = max(2, min(10, 600 / 240)) = 2 s, 300 buckets, two six-minute segments.
        val source = Source(mapOf(
            1 to segment(element(0, 1), element(1_999, 2), element(2_000, 3), element(3_000, 4, mode = 7),
                element(4_000, 5, text = " ")),
            2 to segment(element(599_000, 6), element(700_000, 7)),
        ))
        val data = DesktopDanmakuDensityProgress.load(source, cid = 42, durationSeconds = 600, invalidateShared = { _, _ -> })
            .getOrThrow()
        assertEquals(2, data.stepSeconds)
        assertEquals(300, data.values.size)
        assertEquals(2f, data.values[0])
        assertEquals(1f, data.values[1])
        assertEquals(1f, data.values.last())
        assertEquals(4f, data.values.sum())
        assertEquals(listOf(1, 2), source.requested.sorted())
    }

    @Test fun `invalid arguments fail without a request`() = runBlocking {
        val source = Source(emptyMap())
        assertFailsWith<IllegalArgumentException> {
            DesktopDanmakuDensityProgress.load(source, cid = 0, durationSeconds = 600).getOrThrow()
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopDanmakuDensityProgress.load(source, cid = 42, durationSeconds = 0).getOrThrow()
        }
        assertTrue(source.requested.isEmpty())
    }

    @Test fun `a corrupt segment is skipped and evicted while the others still count`() = runBlocking {
        val source = Source(mapOf(
            1 to segment(element(1_000, 1), element(2_000, 2)),
            2 to byteArrayOf(0x0a, 0x02, 0x38),
        ))
        val shared = mutableListOf<Pair<Long, Int>>()
        val data = DesktopDanmakuDensityProgress.load(source, cid = 42, durationSeconds = 600,
            invalidateShared = { cid, index -> shared += cid to index }).getOrThrow()
        assertEquals(2f, data.values.sum())
        assertEquals(listOf(42L to 2), shared)
    }

    @Test fun `every failed fetch or API error body leaves no curve`() = runBlocking {
        val none = DesktopDanmakuDensityProgress.load(Source(emptyMap()), cid = 42, durationSeconds = 600)
        assertEquals("弹幕分段为空", none.exceptionOrNull()?.message)
        val apiError = Source(mapOf(1 to """{"code":-404}""".toByteArray(), 2 to """[]""".toByteArray()))
        assertEquals("弹幕分段为空", DesktopDanmakuDensityProgress.load(apiError, cid = 42, durationSeconds = 600)
            .exceptionOrNull()?.message)
    }

    @Test fun `a server disabled flag and a zero density are failures not empty curves`() = runBlocking {
        val disabled = Source(mapOf(1 to field(2, value = 1), 2 to segment(element(1_000, 1))))
        assertEquals("UP主已关闭该视频弹幕", DesktopDanmakuDensityProgress.load(disabled, cid = 42, durationSeconds = 600)
            .exceptionOrNull()?.message)
        val empty = Source(mapOf(1 to byteArrayOf(), 2 to byteArrayOf()))
        assertEquals("该视频弹幕密度为零", DesktopDanmakuDensityProgress.load(empty, cid = 42, durationSeconds = 600)
            .exceptionOrNull()?.message)
    }

    @Test fun `a video longer than the segment budget keeps the empty curve and downloads nothing`() = runBlocking {
        val source = Source(mapOf(1 to segment(element(1_000, 1))))
        val seconds = (DesktopDanmakuDensityProgress.MAX_SEGMENTS * 360L) + 1
        val result = DesktopDanmakuDensityProgress.load(source, cid = 42, durationSeconds = seconds)
        assertTrue(result.isFailure)
        assertTrue(source.requested.isEmpty())
        // Exactly at the budget the curve is still built (40 segments, step 10 s).
        val full = Source((1..DesktopDanmakuDensityProgress.MAX_SEGMENTS).associateWith { segment(element(1_000, it.toLong())) })
        val data = DesktopDanmakuDensityProgress.load(full, cid = 42, durationSeconds = DesktopDanmakuDensityProgress.MAX_SEGMENTS * 360L,
            invalidateShared = { _, _ -> }).getOrThrow()
        assertEquals(10, data.stepSeconds)
        assertEquals(DesktopDanmakuDensityProgress.MAX_SEGMENTS.toFloat(), data.values[0])
    }

    companion object {
        private fun element(progressMs: Long, id: Long, mode: Int = 1, text: String = "d$id"): ByteArray =
            field(1, value = id) + field(2, value = progressMs) + field(3, value = mode.toLong()) +
                field(7, bytes = text.toByteArray())

        private fun segment(vararg elements: ByteArray): ByteArray =
            elements.fold(byteArrayOf()) { all, element -> all + field(1, bytes = element) }

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
