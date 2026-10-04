package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DANMAKU_LAYER_REVERSE
import com.android.purebilibili.danmaku.engine.DANMAKU_LAYER_SCROLL
import com.android.purebilibili.danmaku.engine.DANMAKU_LAYER_TOP
import com.android.purebilibili.danmaku.parser.WeightedTextData
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopHotDanmakuProjectionTest {
    @Test fun `accepted plugin rewrite preserves real protobuf identity likes and metadata without mutating payload`() {
        val comment = protobuf(id = 321, likes = 47, text = "原弹幕", weight = 8, self = true,
            pool = 1, attr = 7, count = 3, vip = true)
        val original = requireNotNull(comment.originalElement)
        val rewritten = requireNotNull(applyDesktopDanmakuPlugin(comment) { item ->
            item.copy(id = 999, content = "插件改写", timeMs = 4_250, type = 6,
                color = 0x123456, userId = "plugin-user") to null
        }).comment

        val item = desktopOriginalHotDanmakuItems(listOf(rewritten), DanmakuSettings()).single() as WeightedTextData
        assertEquals(321L, item.danmakuId)
        assertEquals(47L, item.likeCount)
        assertEquals("hash-321", item.userHash)
        assertEquals(8, item.weight)
        assertEquals(1, item.pool)
        assertEquals(7, item.attr)
        assertEquals(3, item.duplicateCount)
        assertTrue(item.isSelf)
        assertTrue(item.isVipGradualColor)
        assertEquals("插件改写", item.text)
        assertEquals(4_250L, item.showAtTime)
        assertEquals(DANMAKU_LAYER_REVERSE, item.layerType)
        assertEquals(0x123456, item.textColor)
        assertEquals("原弹幕", original.content)
        assertEquals(1_250, original.progress)
        assertEquals(47L, original.like)
        assertEquals("原弹幕 x3", comment.text)
    }

    @Test fun `plugin dropped item never reappears from its original server payload`() {
        val kept = protobuf(id = 11, text = "keep", likes = 12)
        val dropped = protobuf(id = 12, text = "drop", likes = 500)
        val accepted = listOf(kept, dropped).mapNotNull { comment ->
            applyDesktopDanmakuPlugin(comment) { item ->
                if (item.content == "drop") null else item.copy(content = "accepted") to null
            }?.comment
        }
        val items = desktopOriginalHotDanmakuItems(accepted, DanmakuSettings())
        assertEquals(listOf(11L), items.map { it.danmakuId })
        assertEquals(listOf("accepted"), items.map { it.text })
        assertEquals(listOf(12L), items.map { it.likeCount })
        assertEquals("drop", requireNotNull(dropped.originalElement).content)
    }

    @Test fun `projection retains separate server IDs and like counts before duplicate merging`() {
        val comments = listOf(protobuf(id = 101, likes = 12, text = "同一句", timeMs = 1_000),
            protobuf(id = 102, likes = 80, text = "同一句", timeMs = 1_100))
        val mergedSettings = DanmakuSettings(mergeDuplicates = true, duplicateMergeCountThreshold = 2)
        val items = desktopOriginalHotDanmakuItems(comments, mergedSettings)
        assertEquals(listOf(101L, 102L), items.map { it.danmakuId })
        assertEquals(listOf(12L, 80L), items.map { it.likeCount })
        assertEquals(listOf("同一句", "同一句"), items.map { it.text })
        assertEquals(listOf(1_000L, 1_100L), items.map { it.showAtTime })
    }

    @Test fun `type and color filters inspect accepted plugin output`() {
        val original = protobuf(id = 20, likes = 40, mode = 1, color = 0xffffff)
        val top = requireNotNull(applyDesktopDanmakuPlugin(original) { item ->
            item.copy(type = 5) to null
        }).comment
        assertTrue(desktopOriginalHotDanmakuItems(listOf(top), DanmakuSettings(allowTop = false)).isEmpty())
        assertEquals(DANMAKU_LAYER_TOP,
            desktopOriginalHotDanmakuItems(listOf(top), DanmakuSettings()).single().layerType)

        val colorful = requireNotNull(applyDesktopDanmakuPlugin(original) { item ->
            item.copy(color = 0x123456) to null
        }).comment
        assertTrue(desktopOriginalHotDanmakuItems(listOf(colorful), DanmakuSettings(allowColorful = false)).isEmpty())
        assertTrue(desktopOriginalHotDanmakuItems(listOf(original), DanmakuSettings(allowScroll = false)).isEmpty())
        assertTrue(desktopOriginalHotDanmakuItems(listOf(original), DanmakuSettings(enabled = false)).isEmpty())
    }

    @Test fun `static to scroll uses the existing effective type filter and layer policy`() {
        val top = protobuf(id = 30, likes = 20, mode = 5)
        val settings = DanmakuSettings(allowTop = false, allowScroll = true, staticDanmakuToScroll = true)
        assertEquals(DANMAKU_LAYER_SCROLL, desktopOriginalHotDanmakuItems(listOf(top), settings).single().layerType)
        assertTrue(desktopOriginalHotDanmakuItems(listOf(top), settings.copy(allowScroll = false)).isEmpty())
    }

    @Test fun `weight filter uses original weight and preserves the original self exemption before merge`() {
        val low = protobuf(id = 41, likes = 100, text = "同一句", weight = 2)
        val high = protobuf(id = 42, likes = 10, text = "同一句", weight = 8)
        val self = protobuf(id = 43, likes = 15, text = "同一句", weight = 0, self = true)
        val items = desktopOriginalHotDanmakuItems(listOf(low, high, self),
            DanmakuSettings(weightFilterLevel = 6, mergeDuplicates = true))
        assertEquals(listOf(42L, 43L), items.map { it.danmakuId })
        assertEquals(listOf(10L, 15L), items.map { it.likeCount })
        assertEquals(listOf(8, 0), items.map { it.weight })
        assertFalse(items.first().isSelf)
        assertTrue(items.last().isSelf)
    }

    @Test fun `block rules use accepted text and unchanged original user hash`() {
        val comment = protobuf(id = 51, likes = 20, text = "original")
        val rewritten = requireNotNull(applyDesktopDanmakuPlugin(comment) { item ->
            item.copy(content = "blocked rewrite", userId = "other-user") to null
        }).comment
        assertTrue(desktopOriginalHotDanmakuItems(listOf(rewritten),
            DanmakuSettings(blockedKeywords = listOf("blocked"))).isEmpty())
        assertTrue(desktopOriginalHotDanmakuItems(listOf(rewritten),
            DanmakuSettings(blockedRules = listOf("hash:hash-51"))).isEmpty())
        assertEquals(51L, desktopOriginalHotDanmakuItems(listOf(rewritten),
            DanmakuSettings(blockedKeywords = listOf("original"))).single().danmakuId)
    }

    @Test fun `only real positive server IDs with at least ten likes enter the hot projection`() {
        val comments = listOf(protobuf(id = 0, likes = 999), protobuf(id = 61, likes = 9),
            protobuf(id = 62, likes = 10), protobuf(id = 63, likes = 11))
        assertEquals(listOf(62L, 63L),
            desktopOriginalHotDanmakuItems(comments, DanmakuSettings()).map { it.danmakuId })
        // XML provides an actual server ID but no like metadata; do not invent it for the hot bar.
        val xml = DanmakuParser.parse("""<i><d p="1,1,25,16777215,0,0,hash,123">XML</d></i>""")
        assertTrue(desktopOriginalHotDanmakuItems(xml, DanmakuSettings()).isEmpty())
        val noPayload = DanmakuComment(1, 1.0, 1, 25, 0xffffff, "unproven", serverId = 999)
        assertTrue(desktopOriginalHotDanmakuItems(listOf(noPayload), DanmakuSettings()).isEmpty())
    }

    companion object {
        /** Real wire fields consumed by the unchanged upstream protobuf decoder. */
        private fun protobuf(id: Long, likes: Long = 20, text: String = "hot", mode: Int = 1,
            color: Int = 0xffffff, timeMs: Int = 1_250, weight: Int = 8, self: Boolean = false,
            pool: Int = 0, attr: Int = 0, count: Int = 1, vip: Boolean = false): DanmakuComment {
            val element = field(1, id) + field(2, timeMs.toLong()) + field(3, mode.toLong()) +
                field(4, 25) + field(5, color.toLong()) + field(6, bytes = "hash-$id".toByteArray(Charsets.UTF_8)) +
                field(7, bytes = text.toByteArray(Charsets.UTF_8)) + field(9, weight.toLong()) +
                field(11, pool.toLong()) + field(13, attr.toLong()) + field(15, likes) +
                field(24, if (vip) 60001 else 0) + field(28, count.toLong()) + field(29, if (self) 1 else 0)
            return DanmakuParser.parseProtobuf(listOf(field(1, bytes = element))).comments.single()
        }
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
