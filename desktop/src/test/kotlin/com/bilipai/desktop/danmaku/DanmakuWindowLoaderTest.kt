package com.bilipai.desktop.danmaku

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DanmakuWindowLoaderTest {
    @Test fun `original protobuf pool retains complete server payload independently of renderer sanitization`() {
        val content = "原弹幕\t" + "字".repeat(420)
        val element = field(1, value = 321) + field(2, value = 1250) + field(3, value = 6) +
            field(4, value = 64) + field(5, value = 255) + field(6, bytes = "hash-original".toByteArray()) +
            field(7, bytes = content.toByteArray()) + field(9, value = 8) + field(11, value = 1) +
            field(13, value = 7) + field(15, value = 9) + field(24, value = 60001) +
            field(28, value = 3) + field(29, value = 1)
        val comment = DanmakuParser.parseProtobuf(listOf(field(1, bytes = element))).comments.single()
        val original = requireNotNull(comment.originalElement)
        val item = requireNotNull(com.android.purebilibili.feature.video.danmaku.DesktopOriginalDanmakuItemParser
            .createTextDataFromProto(original)) as com.android.purebilibili.danmaku.parser.WeightedTextData
        assertEquals(content, original.content)
        assertEquals("$content x3", item.text)
        assertEquals(321L, item.danmakuId)
        assertEquals("hash-original", item.userHash)
        assertEquals(1250L, item.showAtTime)
        assertEquals(1, item.pool)
        assertEquals(7, item.attr)
        assertEquals(9L, item.likeCount)
        assertEquals(8, item.weight)
        assertTrue(item.isSelf)
        assertTrue(item.isVipGradualColor)
        assertEquals(3, item.duplicateCount)
        assertEquals(303, comment.text.length)
        assertFalse(comment.text.contains('\t'))
        assertEquals(48, comment.size)
    }

    @Test fun `offline protobuf uses original segment slots and keeps special assets over seeks without HTTP`() = runBlocking {
        val directory = Files.createTempDirectory("bilipai-offline-danmaku-")
        val standard = (1..4).map { directory.resolve("seg-$it.pb") }
        val special = directory.resolve("special.xml")
        try {
            standard.forEachIndexed { offset, file ->
                if (offset != 2) Files.write(file, field(1, bytes = field(1, value = offset + 1L) +
                    field(2, value = offset * 360_000L + 1_000) + field(7, bytes = "offline ${offset + 1}".toByteArray())))
            }
            Files.writeString(special, """<i><d p="1,7,25,16777215">[0.1,0.1,"",2,"offline advanced"]</d></i>""")
            val loader = DanmakuWindowLoader(OfflineDanmakuSource(standard, listOf(special)), 1)
            val initial = loader.initial()
            assertEquals(listOf(1, 2), initial.segments)
            assertEquals(listOf("offline 1", "offline 2"), initial.document.comments.map { it.text })
            assertEquals("offline advanced", initial.document.advanced.single().content)
            assertNull(initial.warning)
            val next = requireNotNull(loader.move(720_000))
            assertEquals(listOf(2, 3, 4), next.segments)
            assertEquals(listOf("offline 2", "offline 4"), next.document.comments.map { it.text })
            assertEquals("offline advanced", next.document.advanced.single().content)
            assertTrue(next.warning.orEmpty().contains("2/3"))
        } finally {
            (standard + listOf(special)).forEach(Files::deleteIfExists)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `special-only offline download needs no invented metadata protobuf`() = runBlocking {
        val file = Files.createTempFile("bilipai-special-only-", ".xml")
        try {
            Files.writeString(file, """<i><d p="1,7,25,16777215">[0.1,0.1,"",2,"only advanced"]</d></i>""")
            val result = DanmakuWindowLoader(OfflineDanmakuSource(emptyList(), listOf(file)), 1).initial()
            assertTrue(result.document.comments.isEmpty())
            assertEquals("only advanced", result.document.advanced.single().content)
            assertNull(result.warning)
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun `three segment windows follow seeks and reuse cache without fetching the entire video`() = runBlocking {
        val source = FixtureSource(totalSegments = 5)
        val loader = DanmakuWindowLoader(source, cid = 42, aid = 7, durationMs = 1_800_000)
        val initial = loader.initial()
        assertEquals(DanmakuFormat.PROTOBUF, initial.format)
        assertEquals(listOf(1, 2), initial.segments)
        assertEquals(setOf(1, 2), source.requested.toSet())
        val forward = requireNotNull(loader.move(720_000))
        assertEquals(listOf(2, 3, 4), forward.segments)
        assertEquals(setOf(1, 2, 3, 4), source.requested.toSet())
        assertEquals(listOf("segment 2", "segment 3", "segment 4"), forward.document.comments.map { it.text })
        assertNull(loader.move(750_000))
        val back = requireNotNull(loader.move(0))
        assertEquals(listOf("segment 1", "segment 2"), back.document.comments.map { it.text })
        assertEquals(4, source.requested.size)
        assertEquals(0, source.xmlRequests)
    }

    @Test fun `metadata belongs to the new content and takes priority over stale player duration`() = runBlocking {
        val source = FixtureSource(totalSegments = 2)
        val loader = DanmakuWindowLoader(source, cid = 42, durationMs = 3_600_000)
        loader.initial(3_000_000)
        assertEquals(listOf(1, 2), loader.windowForPosition(3_000_000))
        assertEquals(setOf(1, 2), source.requested.toSet())
    }

    @Test fun `unavailable segmented API falls back to secure XML including advanced comments`() = runBlocking {
        val source = FixtureSource(failNetwork = true)
        val loader = DanmakuWindowLoader(source, 42)
        val initial = loader.initial()
        assertEquals(DanmakuFormat.XML, initial.format)
        assertEquals(listOf("XML fallback"), initial.document.comments.map { it.text })
        assertEquals(listOf("advanced fallback"), initial.document.advanced.map { it.content })
        assertEquals(1, source.xmlRequests)
        assertNull(loader.move(800_000))
        assertEquals(1, source.xmlRequests)
    }

    @Test fun `cancelled seek leaves the old window active and cancels its in flight fetch`() = runBlocking {
        withTimeout(3_000) {
            val source = FixtureSource(totalSegments = 9, blockSegment = 5)
            val loader = DanmakuWindowLoader(source, 42)
            loader.initial()
            val pending = async { loader.move(1_800_000) }
            source.blockedStarted.await()
            pending.cancelAndJoin()
            assertTrue(source.blockedCancelled)
            assertNull(loader.move(0), "Returning to the original window must retain it")
        }
    }

    @Test fun `protobuf preserves server count hash weight reverse mode and VIP color metadata`() {
        val packet = field(1, bytes = field(1, value = 987) + field(2, value = 1_500) +
            field(3, value = 6) + field(4, value = 36) + field(5, value = 0xffffff) +
            field(6, bytes = "hash-1".toByteArray()) + field(7, bytes = "reverse".toByteArray()) +
            field(9, value = 8) + field(24, value = 60_001) + field(28, value = 3))
        val comment = DanmakuParser.parseProtobuf(listOf(packet)).comments.single()
        assertEquals(987L, comment.serverId)
        assertEquals(1.5, comment.timeSeconds)
        assertEquals(6, comment.mode)
        assertEquals("hash-1", comment.userHash)
        assertEquals(8, comment.weight)
        assertEquals("reverse x3", comment.text)
        assertTrue(comment.isVipGradualColor)
    }

    @Test fun `special asset address stays on the trusted upstream CDN hosts`() {
        assertEquals("https://upos.bilivideo.com/a", ApiDesktopDanmakuSource.trustedSpecialUrl("//upos.bilivideo.com/a"))
        assertEquals("https://comment.bilibili.com/a", ApiDesktopDanmakuSource.trustedSpecialUrl("http://comment.bilibili.com/a"))
        assertFailsWith<IllegalArgumentException> { ApiDesktopDanmakuSource.trustedSpecialUrl("https://bilivideo.com.evil.test/a") }
        assertFailsWith<IllegalArgumentException> { ApiDesktopDanmakuSource.trustedSpecialUrl("https://token@comment.bilibili.com/a") }
        assertFailsWith<IllegalArgumentException> { ApiDesktopDanmakuSource.trustedSpecialUrl("file:///C:/Windows/win.ini") }
    }

    private class FixtureSource(val totalSegments: Int = 3, val failNetwork: Boolean = false, val blockSegment: Int? = null) : DesktopDanmakuSource {
        val requested = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val blockedStarted = CompletableDeferred<Unit>()
        var blockedCancelled = false
        var xmlRequests = 0
        override suspend fun metadata(cid: Long, aid: Long): ByteArray {
            if (failNetwork) throw IOException("fixture metadata unavailable")
            return field(4, bytes = field(1, value = 360_000) + field(2, value = totalSegments.toLong()))
        }
        override suspend fun segment(cid: Long, index: Int): ByteArray {
            requested += index
            if (failNetwork) throw IOException("fixture segment unavailable")
            if (index == blockSegment) {
                blockedStarted.complete(Unit)
                try { CompletableDeferred<Unit>().await() }
                finally { blockedCancelled = true }
            }
            return field(1, bytes = field(1, value = index.toLong()) + field(2, value = (index - 1) * 360_000L + 1_000) +
                field(7, bytes = "segment $index".toByteArray()))
        }
        override suspend fun xml(cid: Long): ByteArray {
            xmlRequests++
            return """<i><d p="1,1,25,16777215">XML fallback</d><d p="1,7,25,16777215">[0.1,0.1,"",2,"advanced fallback"]</d></i>""".toByteArray()
        }
        override suspend fun special(url: String) = byteArrayOf()
    }

    companion object {
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
