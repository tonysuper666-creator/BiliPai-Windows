package com.bilipai.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice V12a: the truth table of the original-route mount fixture and its anchor matcher. Pure Kotlin, no window, no Windows, no
 * network. desktop/tools/ux/test_ux_original_route_fixture.py reads the CASES table below and compares it with its own model.
 */
class WindowsOriginalVideoRouteMountVerdictTest {
    private val leaf = WindowsOriginalVideoRouteMount.LEAF_ANCHORS
    private val original = WindowsOriginalVideoRouteMount.ORIGINAL_ANCHORS

    // CASES: leaf anchors seen (none, one, all), original anchors seen (none, one, all), the verdict.
    private data class Case(val leafSeen: Int, val originalSeen: Int, val verdict: OriginalVideoRouteVerdict)

    private val cases = listOf(
        Case(leafSeen = 2, originalSeen = 0, verdict = OriginalVideoRouteVerdict.DESKTOP_LEAF_ONLY),
        Case(leafSeen = 2, originalSeen = 1, verdict = OriginalVideoRouteVerdict.BOTH),
        Case(leafSeen = 2, originalSeen = 10, verdict = OriginalVideoRouteVerdict.BOTH),
        Case(leafSeen = 1, originalSeen = 0, verdict = OriginalVideoRouteVerdict.INCONCLUSIVE),
        Case(leafSeen = 1, originalSeen = 1, verdict = OriginalVideoRouteVerdict.BOTH),
        Case(leafSeen = 0, originalSeen = 0, verdict = OriginalVideoRouteVerdict.INCONCLUSIVE),
        Case(leafSeen = 0, originalSeen = 1, verdict = OriginalVideoRouteVerdict.ORIGINAL_ONLY),
        Case(leafSeen = 0, originalSeen = 10, verdict = OriginalVideoRouteVerdict.ORIGINAL_ONLY),
    )

    @Test fun theTruthTableHoldsForEveryCase() {
        assertEquals(2, leaf.size)
        assertEquals(10, original.size)
        for (case in cases) {
            val verdict = WindowsOriginalVideoRouteMount.classify(leaf.take(case.leafSeen).toSet(), original.take(case.originalSeen).toSet())
            assertEquals(case.verdict, verdict, "leaf ${case.leafSeen}, original ${case.originalSeen}")
        }
    }

    @Test fun everyCombinationOfLeafAnchorsAndOneOriginalAnchorIsClassifiedByTheSameRule() {
        for (mask in 0 until (1 shl leaf.size)) {
            val seen = leaf.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }.toSet()
            for (anchor in original) {
                val verdict = WindowsOriginalVideoRouteMount.classify(seen, setOf(anchor))
                val expected = if (seen.isEmpty()) OriginalVideoRouteVerdict.ORIGINAL_ONLY else OriginalVideoRouteVerdict.BOTH
                assertEquals(expected, verdict, "leaf $seen with original $anchor")
            }
            val withoutOriginal = WindowsOriginalVideoRouteMount.classify(seen, emptySet())
            val expected = if (seen.size == leaf.size) OriginalVideoRouteVerdict.DESKTOP_LEAF_ONLY else OriginalVideoRouteVerdict.INCONCLUSIVE
            assertEquals(expected, withoutOriginal, "leaf $seen without original")
        }
    }

    @Test fun theAnchorListsAreNonEmptyDuplicateFreeAndDisjoint() {
        assertTrue(leaf.isNotEmpty() && original.isNotEmpty())
        assertEquals(leaf.size, leaf.toSet().size)
        assertEquals(original.size, original.toSet().size)
        assertEquals(emptySet<String>(), leaf.toSet().intersect(original.toSet()))
        assertTrue((leaf + original).none { it.isBlank() || it != it.trim() })
    }

    @Test fun anAnchorMatchesAWholeNameOrAWholeSegmentOfAMergedName() {
        val anchor = "详情"
        assertTrue(WindowsOriginalVideoRouteMount.containsAnchor(listOf("详情"), anchor))
        assertTrue(WindowsOriginalVideoRouteMount.containsAnchor(listOf("播放, 详情"), anchor))
        assertTrue(WindowsOriginalVideoRouteMount.containsAnchor(listOf("播放,详情,全屏"), anchor))
        assertTrue(WindowsOriginalVideoRouteMount.containsAnchor(listOf("播放，详情"), anchor))
        assertTrue(WindowsOriginalVideoRouteMount.containsAnchor(listOf("播放\n详情\n全屏"), anchor))
        assertTrue(WindowsOriginalVideoRouteMount.containsAnchor(listOf("x", " 详情 "), anchor))
    }

    @Test fun aLongerNameThatOnlyContainsTheAnchorIsNotAMatch() {
        assertFalse(WindowsOriginalVideoRouteMount.containsAnchor(listOf("退出全屏"), "全屏"))
        assertFalse(WindowsOriginalVideoRouteMount.containsAnchor(listOf("关闭详情"), "详情"))
        assertFalse(WindowsOriginalVideoRouteMount.containsAnchor(listOf("视频详情页"), "详情"))
        assertFalse(WindowsOriginalVideoRouteMount.containsAnchor(emptyList(), "详情"))
        assertFalse(WindowsOriginalVideoRouteMount.containsAnchor(listOf(""), "详情"))
    }

    @Test fun anAnchorWithRegexCharactersIsMatchedLiterally() {
        assertTrue(WindowsOriginalVideoRouteMount.containsAnchor(listOf("a.b(c)"), "a.b(c)"))
        assertFalse(WindowsOriginalVideoRouteMount.containsAnchor(listOf("axb(c)"), "a.b(c)"))
    }

    @Test fun theExpectationsAcceptTheirOwnVerdictsOnly() {
        val all = OriginalVideoRouteVerdict.entries
        assertEquals(listOf(OriginalVideoRouteVerdict.DESKTOP_LEAF_ONLY),
            all.filter { OriginalVideoRouteExpectation.DESKTOP_LEAF.accepts(it) })
        assertEquals(listOf(OriginalVideoRouteVerdict.ORIGINAL_ONLY),
            all.filter { OriginalVideoRouteExpectation.ORIGINAL.accepts(it) })
        assertEquals(listOf(OriginalVideoRouteVerdict.DESKTOP_LEAF_ONLY, OriginalVideoRouteVerdict.ORIGINAL_ONLY, OriginalVideoRouteVerdict.BOTH),
            all.filter { OriginalVideoRouteExpectation.ANY.accepts(it) })
        assertFalse(OriginalVideoRouteExpectation.ANY.accepts(OriginalVideoRouteVerdict.INCONCLUSIVE))
    }

    @Test fun theExpectationArgumentsParseAndAnythingElseIsRefused() {
        assertEquals(OriginalVideoRouteExpectation.DESKTOP_LEAF, OriginalVideoRouteExpectation.parse("desktop-leaf"))
        assertEquals(OriginalVideoRouteExpectation.ORIGINAL, OriginalVideoRouteExpectation.parse("original"))
        assertEquals(OriginalVideoRouteExpectation.ANY, OriginalVideoRouteExpectation.parse("any"))
        assertFailsWith<IllegalArgumentException> { OriginalVideoRouteExpectation.parse("") }
        assertFailsWith<IllegalArgumentException> { OriginalVideoRouteExpectation.parse("DESKTOP_LEAF") }
    }
}
