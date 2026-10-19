package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.MessageHistoryData
import com.android.purebilibili.data.model.response.MessageHistoryResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Windows consumer of the v0.4.6 chat cursor: an empty history must open instead of failing to parse. */
class DesktopMessageHistoryCursorTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun history(body: String): MessageHistoryData {
        val response = json.decodeFromString<MessageHistoryResponse>("""{"code":0,"msg":"OK","message":"OK","ttl":1,"data":$body}""")
        return assertNotNull(response.data)
    }

    @Test
    fun emptyHistoryWithTheUnsignedSentinelOpensWithoutAnotherPage() {
        val data = history("""{"messages":null,"has_more":0,"min_seqno":18446744073709551615,"max_seqno":0}""")
        assertEquals(0L, data.min_seqno)
        assertNull(communityMessageHistory(data, 0).nextEndSeqno)
        assertNull(communityMessageHistory(data, 30).nextEndSeqno)
    }

    @Test
    fun aSentinelCursorNeverRequestsAnotherPageEvenWhenTheServerClaimsMore() {
        val data = history(
            """{"messages":[{"sender_uid":7,"msg_seqno":9,"msg_key":9,"content":"{}"}],"has_more":1,"min_seqno":18446744073709551615}"""
        )
        assertEquals(0L, data.min_seqno)
        assertNull(communityMessageHistory(data, 0).nextEndSeqno)
    }

    @Test
    fun ordinaryPagingKeepsTheRealCursorAndStopsWhenItDoesNotMove() {
        val data = history(
            """{"messages":[{"sender_uid":7,"msg_seqno":30,"msg_key":30,"content":"{}"}],"has_more":1,"min_seqno":30,"max_seqno":42}"""
        )
        assertEquals(30L, data.min_seqno)
        assertEquals(30L, communityMessageHistory(data, 0).nextEndSeqno)
        assertNull(communityMessageHistory(data, 30).nextEndSeqno)
    }

    @Test
    fun aCursorBeyondTheSignedRangeIsStillRejectedInsteadOfTruncated() {
        val failure = runCatching { history("""{"min_seqno":9223372036854775808}""") }.exceptionOrNull()
        assertNotNull(failure)
    }
}
