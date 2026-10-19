package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The Windows port of the Android engine's DanmakuVisibility cases (v0.4.6 DanmakuVisibilityTest, f31c3b83).
 * The draw and touch cases of that test belong to the ByteDance render items and have no Windows counterpart;
 * the rule they rely on, which scheduled comment is hidden while the counted hot bar shows it, is what is ported. */
class DesktopHotDanmakuSelectionTest {
    private fun item(id: Long, content: String? = null) = DanmakuItem().apply { danmakuId = id; text = content }

    private fun comment(id: Int, text: String, serverId: Long, mergedOriginal: String? = null) =
        DanmakuComment(id, 1.0, 1, 25, 0xffffff, text, serverId = serverId,
            originalRenderItem = mergedOriginal?.let {
                DanmakuItem().apply { danmakuId = serverId; this.text = text; mergedOriginalText = it }
            })

    @Test
    fun selectedIdsHideOnlyTheirOwnItemsAndNothingIsHiddenWithoutASelection() {
        val selection = DesktopHotDanmakuSelection.of(listOf(item(42L, "banner")))
        assertFalse(selection.isVisible(42L))
        assertTrue(selection.isVisible(43L))
        assertFalse(selection.isVisible(comment(1, "someone else", 42L)))
        assertTrue(selection.isVisible(comment(2, "someone else", 43L)))
        assertTrue(DesktopHotDanmakuSelection.NONE.isEmpty)
        assertTrue(DesktopHotDanmakuSelection.NONE.isVisible(42L, "banner"))
        assertSame(DesktopHotDanmakuSelection.NONE, DesktopHotDanmakuSelection.of(emptyList()))
    }

    @Test
    fun sameContentWithDifferentIdsIsHiddenButLiterallyDifferentContentIsNot() {
        val selection = DesktopHotDanmakuSelection.of(listOf(item(42L, "pcl2来的")))
        assertFalse(selection.isVisible(comment(1, "pcl2来的", 43L)))
        assertFalse(selection.isVisible(44L, "pcl2来的"))
        assertTrue(selection.isVisible(comment(2, "其他内容", 45L)))
        assertTrue(selection.isVisible(comment(3, "pcl2来的！", 46L)))
    }

    @Test
    fun mergedOriginalContentHidesTheCountedTextButALiteralCountSuffixFromAnotherSourceStaysVisible() {
        val selection = DesktopHotDanmakuSelection.of(listOf(item(42L, "pcl2来的")))
        assertFalse(selection.isVisible(comment(1, "pcl2来的 x3", 10L, mergedOriginal = "pcl2来的")))
        assertTrue(selection.isVisible(comment(2, "pcl2来的 x3", 40L)))
        assertTrue(DesktopHotDanmakuSelection.NONE.isVisible(comment(1, "pcl2来的 x3", 10L, mergedOriginal = "pcl2来的")))
        // A merged comment is also hidden by the server id of its first item.
        assertFalse(DesktopHotDanmakuSelection.of(listOf(item(10L, "unrelated")))
            .isVisible(comment(1, "pcl2来的 x3", 10L, mergedOriginal = "pcl2来的")))
    }

    @Test
    fun theSelectionCopiesTheShownEntriesAndEqualSelectionsCompareEqual() {
        val shown = mutableListOf(item(42L, "banner"))
        val selection = DesktopHotDanmakuSelection.of(shown)
        shown.clear()
        assertFalse(selection.isVisible(42L))
        assertEquals(selection, DesktopHotDanmakuSelection.of(listOf(item(42L, "banner"))))
        assertFalse(selection == DesktopHotDanmakuSelection.of(listOf(item(43L, "banner"))))
    }
}
