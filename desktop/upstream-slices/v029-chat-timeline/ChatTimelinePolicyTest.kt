package com.android.purebilibili.feature.message

import com.android.purebilibili.data.model.response.PrivateMessageItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ChatTimelinePolicyTest {
    private fun message(sequence: Long, status: Int = 0) = PrivateMessageItem(
        msg_key = sequence,
        msg_seqno = sequence,
        msg_status = status,
    )

    @Test
    fun refreshingLatestPreservesLoadedHistoryAndUpdatesWithdrawnMessage() {
        val merged = mergeChatMessages(
            existing = (1L..60L).map { message(it) },
            incoming = (40L..70L).map { message(it, status = if (it == 50L) 1 else 0) },
        )
        assertEquals((1L..70L).toList(), merged.map { it.msg_seqno })
        assertEquals(1, merged.single { it.msg_seqno == 50L }.msg_status)
    }

    @Test
    fun overlappingOlderPageKeepsMessageKeysAndLatestStatus() {
        val current = listOf(message(30, status = 1), message(31))
        val merged = mergeChatMessages((1L..30L).map { message(it) }, current)

        assertEquals((1L..31L).toList(), merged.map { it.msg_seqno })
        assertEquals(1, merged.single { it.msg_seqno == 30L }.msg_status)
        assertEquals(chatMessageKey(current.last()), chatMessageKey(merged.last()))
    }

    @Test
    fun missingMessageIdsUseDistinctSequenceKeys() {
        val first = PrivateMessageItem(msg_seqno = 11L)
        val second = PrivateMessageItem(msg_seqno = 12L)
        assertNotEquals(chatMessageKey(first), chatMessageKey(second))
        assertEquals(2, mergeChatMessages(listOf(first), listOf(second)).size)
    }
}
