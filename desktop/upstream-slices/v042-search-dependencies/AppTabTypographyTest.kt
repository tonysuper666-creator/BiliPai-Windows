package com.android.purebilibili.core.ui

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class AppTabTypographyTest {
    @Test
    fun `glass labels raise small theme and explicit sizes but preserve larger labels`() {
        assertEquals(14.sp, resolveAppTabLabelFontSize(TextUnit.Unspecified, 12.sp, true))
        assertEquals(14.sp, resolveAppTabLabelFontSize(13.sp, 12.sp, true))
        assertEquals(15.sp, resolveAppTabLabelFontSize(15.sp, 12.sp, true))
        assertEquals(16.sp, resolveAppTabLabelFontSize(16.sp, 12.sp, true))
        assertEquals(17.sp, resolveAppTabLabelFontSize(TextUnit.Unspecified, 17.sp, true))
        assertEquals(1.em, resolveAppTabLabelFontSize(1.em, 12.sp, true))
    }

    @Test
    fun `disabled glass preserves native theme and explicit sizes`() {
        for (size in listOf(12.sp, 13.sp, 15.sp)) {
            assertEquals(size, resolveAppTabLabelFontSize(TextUnit.Unspecified, size, false))
            assertEquals(size, resolveAppTabLabelFontSize(size, 12.sp, false))
        }
    }
}
