package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.DanmakuProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DesktopV038DanmakuMergeBridgeTest {
    private fun comment(id: Int, ms: Int, text: String = "repeat", mode: Int = 1,
        color: Int = 0xffffff, likes: Long = 0, serverId: Long = id.toLong()): DanmakuComment {
        val element = DanmakuProto.DanmakuElem(id = serverId, progress = ms, mode = mode,
            color = color, midHash = "user-$id", content = text, like = likes)
        return DanmakuComment(id, ms / 1_000.0, mode, 25, color, text,
            serverId = serverId, userHash = element.midHash, originalElement = element)
    }

    @Test
    fun fixedWindowDoesNotChainThroughThePreviousComment() {
        val result = mergeDuplicateDanmaku(listOf(comment(1, 0), comment(2, 900), comment(3, 1800)), 1000)
        assertEquals(listOf("repeat x2", "repeat"), result.map { it.text })
        assertEquals(listOf(1, 3), result.map { it.id })
    }

    @Test
    fun inclusiveWindowKeepsTheFirstSourceStyleAndActualServerIdentity() {
        val first = comment(7, 0, mode = 5, color = 0x123456, serverId = 123L)
        val second = comment(8, 1000, mode = 1, color = 0xffffff)
        val result = mergeDuplicateDanmaku(listOf(second, first), 1000).single()
        assertEquals("repeat x2", result.text)
        assertEquals(7, result.id)
        assertEquals(123L, result.serverId)
        assertEquals(5, result.mode)
        assertEquals(0x123456, result.color)
        assertSame(first.originalElement, result.originalElement)
        assertEquals(123L, result.originalRenderItem?.danmakuId)
    }

    @Test
    fun likesAndOriginalContentSurviveWithoutMutatingRawSourcesOrCreatingLocalSends() {
        val first = comment(1, 0, likes = 4)
        val second = comment(2, 800, likes = 9)
        val result = mergeDuplicateDanmaku(listOf(first, second), 1000).single()
        assertEquals(13L, result.originalRenderItem?.likeCount)
        assertEquals(2, result.originalRenderItem?.duplicateCount)
        assertEquals("repeat", result.originalRenderItem?.mergedOriginalText)
        assertEquals("repeat", first.text)
        assertEquals(4L, first.originalElement?.like)
        assertNull(result.originalLocalItem)
        assertNull(result.originalLocalInjectionPhase)
    }

    @Test
    fun missingServerIdsStillMapBackToTheFirstRealComment() {
        val first = comment(42, 0, serverId = 0)
        val result = mergeDuplicateDanmaku(listOf(first, comment(43, 100, serverId = 0)), 1000).single()
        assertEquals(42, result.id)
        assertEquals(0L, result.serverId)
        assertSame(first.originalElement, result.originalElement)
    }

    @Test
    fun processedTextIsMergedWhileItsOriginalPayloadRemainsAvailable() {
        val first = comment(1, 0, text = "raw").copy(text = "processed")
        val result = mergeDuplicateDanmaku(listOf(first, comment(2, 100, text = "processed")), 1000).single()
        assertEquals("processed x2", result.text)
        assertEquals("processed", result.originalRenderItem?.mergedOriginalText)
        assertEquals("raw", result.originalElement?.content)
    }

    @Test
    fun rendererDefaultsAndOldSavedWindowsUseTheOriginalOneToFifteenSecondRange() {
        assertEquals(15_000, DanmakuSettings().normalized().duplicateMergeWindowMs)
        assertEquals(1000, DanmakuSettings(duplicateMergeWindowMs = 500).normalized().duplicateMergeWindowMs)
        assertEquals(15_000, DanmakuSettings(duplicateMergeWindowMs = 60_000).normalized().duplicateMergeWindowMs)
    }
}
