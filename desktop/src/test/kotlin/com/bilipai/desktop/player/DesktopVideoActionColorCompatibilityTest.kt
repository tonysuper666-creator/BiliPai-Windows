package com.android.purebilibili.feature.video.ui.feedback

import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.theme.AppUiStyle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class DesktopVideoActionColorCompatibilityTest {
    @Test
    fun retainedActionSectionArgumentsUseCurrentThemePolicyInBothStyles() {
        val primary = Color.Red
        val secondary = Color.Green
        val tertiary = Color.Blue
        for (style in listOf(AppUiStyle.MATERIAL3, AppUiStyle.MIUIX)) {
            val actual = resolveVideoDetailActionActiveColors(
                primary = primary, secondary = secondary, tertiary = tertiary, uiStyle = style,
            )
            assertEquals(resolveVideoDetailActionActiveColors(primary, secondary, style), actual)
            assertEquals(primary, actual.watchLater)
            assertEquals(if (style == AppUiStyle.MIUIX) primary else secondary, actual.downloadInProgress)
        }
    }
}
