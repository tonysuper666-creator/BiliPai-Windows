// Original v0.2.9 tests; fixed a4b77f894d0a2dd26c0b9fc144b8adb88ac05480; raw SHA-256 f9b22207531205964d66aade778f0aced6ddba218a52292ec0a0475fe53a4af0.
package com.android.purebilibili.data.repository

import com.android.purebilibili.data.model.response.GradeDanmakuSummary
import com.android.purebilibili.danmaku.parser.DanmakuProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopV029GradeSummaryPolicyTest {
    @Test
    fun resolveGradeDanmakuSummary_matchesActualGradeIdAndIgnoresUnrelatedCommands() {
        val commands = listOf(
            DanmakuProto.CommandDm(
                command = "#VOTE#",
                extra = """{"grade_id":3651137,"count":999,"avg_score":1}"""
            ),
            DanmakuProto.CommandDm(command = "#GRADE#", extra = "{invalid"),
            DanmakuProto.CommandDm(
                command = "#GRADE#",
                extra = """{"grade_id":3651138,"count":999,"avg_score":1}"""
            ),
            DanmakuProto.CommandDm(
                command = "#GRADE#",
                extra = """{"msg":"熟练程度如何","grade_id":3651137,"mid_score":0,"count":2,"avg_score":10,"duration":5000,"summary_duration":6000,"posX":333.5,"posY":243.75,"posX_2":50,"posY_2":65}"""
            )
        )

        assertEquals(GradeDanmakuSummary(2L, 10.0), resolveGradeDanmakuSummary(commands, "3651137"))
        assertNull(resolveGradeDanmakuSummary(commands, "missing"))
    }

    @Test
    fun resolveGradeDanmakuSummary_preservesAuthenticatedPersonalScoreAndRealZeroCount() {
        val commands = listOf(DanmakuProto.CommandDm(
            command = "#GRADE#",
            extra = """{"grade_id":"3651137","count":0,"avg_score":0,"mid_score":8}"""
        ))

        assertEquals(GradeDanmakuSummary(0L, 0.0, 8), resolveGradeDanmakuSummary(commands, "3651137"))
    }

    @Test
    fun resolveGradeDanmakuSummary_doesNotInventMissingOrInvalidAggregates() {
        for (extra in listOf(
            """{"grade_id":3651137,"mid_score":10}""",
            """{"grade_id":3651137,"count":-1,"avg_score":"Infinity","mid_score":10}"""
        )) {
            val commands = listOf(DanmakuProto.CommandDm(command = "#GRADE#", extra = extra))
            assertEquals(GradeDanmakuSummary(userScore = 10), resolveGradeDanmakuSummary(commands, "3651137"))
        }
    }
}
