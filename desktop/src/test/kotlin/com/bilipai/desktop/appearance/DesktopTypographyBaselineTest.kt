package com.bilipai.desktop.appearance

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.android.purebilibili.core.theme.AppFontSizePreset
import com.android.purebilibili.core.theme.AppFontWeightPreset
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.Md3Typography
import com.android.purebilibili.core.theme.resolveMaterialTypography
import com.android.purebilibili.core.theme.scaled
import com.android.purebilibili.core.theme.toMiuixTextStyles
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.theme.TextStyles
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Root decision R-T1 (2026-10-19): the Windows theme uses the v0.4.6 Material baseline typography
 * as is, with no extra 4K compensation. The app has its own display scale (whole-window zoom
 * multiplied into the density), and the font size and weight presets are the only text transforms
 * on top. For the native Miuix text styles they are applied AFTER the Material to Miuix conversion,
 * because that conversion pins title1..title4 to a fixed 32/24/20/18sp Medium scale.
 */
class DesktopTypographyBaselineTest {
    private fun TextStyles.slots() = listOf(
        main, paragraph, body1, body2, button, footnote1, footnote2, headline1, headline2, subtitle,
        title1, title2, title3, title4,
    )

    @Test fun everyUiStyleResolvesTheUnmodifiedMaterialBaseline() {
        for (style in AppUiStyle.entries) {
            val typography = resolveMaterialTypography(style)
            assertSame(Md3Typography, typography)
            assertEquals(16.sp, typography.bodyLarge.fontSize)
            assertEquals(14.sp, typography.bodyMedium.fontSize)
            assertEquals(12.sp, typography.bodySmall.fontSize)
            assertEquals(16.sp, typography.bodySmall.lineHeight)
            assertEquals(12.sp, typography.labelMedium.fontSize)
            assertEquals(11.sp, typography.labelSmall.fontSize)
            assertEquals(16.sp, typography.labelSmall.lineHeight)
        }
    }

    @Test fun theDefaultFontPresetAddsNoCompensationAndLargerPresetsScaleSlotsExactly() {
        val base = resolveMaterialTypography(AppUiStyle.MATERIAL3)
        assertSame(base, base.scaled(AppFontSizePreset.DEFAULT.multiplier))
        val larger = base.scaled(AppFontSizePreset.EXTRA_EXTRA_LARGE.multiplier)
        assertEquals(base.bodySmall.fontSize * 1.25f, larger.bodySmall.fontSize)
        assertEquals(base.labelSmall.fontSize * 1.25f, larger.labelSmall.fontSize)
    }

    @Test fun nativeMiuixRolesFollowTheBaselineAndKeepTheCompactTitleScale() {
        val base = resolveMaterialTypography(AppUiStyle.MIUIX)
        val styles = base.toMiuixTextStyles()
        assertEquals(base.bodyLarge, styles.main)
        assertEquals(base.bodyMedium, styles.body1)
        assertEquals(base.bodySmall, styles.body2)
        assertEquals(32.sp, styles.title1.fontSize)
        assertEquals(24.sp, styles.title2.fontSize)
        assertEquals(20.sp, styles.title3.fontSize)
        assertEquals(18.sp, styles.title4.fontSize)
    }

    @Test fun theDefaultPresetsLeaveTheNativeMiuixStylesAtTheBaselineForEveryUiStyle() {
        for (style in AppUiStyle.entries) {
            val base = resolveMaterialTypography(style)
            val styles = resolveDesktopMiuixTextStyles(style, AppFontSizePreset.DEFAULT, AppFontWeightPreset.FOLLOW_THEME)
            assertEquals(base.bodyLarge, styles.main)
            assertEquals(base.bodyMedium, styles.body1)
            assertEquals(base.bodySmall, styles.body2)
            assertEquals(base.labelLarge.copy(fontWeight = FontWeight.Bold), styles.subtitle)
            assertEquals(listOf(32.sp, 24.sp, 20.sp, 18.sp),
                listOf(styles.title1, styles.title2, styles.title3, styles.title4).map { it.fontSize })
            assertEquals(listOf(38.sp, 30.sp, 26.sp, 24.sp),
                listOf(styles.title1, styles.title2, styles.title3, styles.title4).map { it.lineHeight })
            assertEquals(List(4) { FontWeight.Medium },
                listOf(styles.title1, styles.title2, styles.title3, styles.title4).map { it.fontWeight })
        }
    }

    @Test fun theFontSizeAndWeightPresetsReachTheNativeMiuixTitlesThroughTheWindowsPipeline() {
        for (style in AppUiStyle.entries) {
            val base = resolveMaterialTypography(style)
            val styles = resolveDesktopMiuixTextStyles(
                style, AppFontSizePreset.EXTRA_EXTRA_LARGE, AppFontWeightPreset.BOLD)
            // 1.25 times the fixed 32/24/20/18sp titles and their 38/30/26/24sp line heights (all exact in Float).
            assertEquals(40.sp, styles.title1.fontSize)
            assertEquals(30.sp, styles.title2.fontSize)
            assertEquals(25.sp, styles.title3.fontSize)
            assertEquals(22.5.sp, styles.title4.fontSize)
            assertEquals(47.5.sp, styles.title1.lineHeight)
            assertEquals(37.5.sp, styles.title2.lineHeight)
            assertEquals(32.5.sp, styles.title3.lineHeight)
            assertEquals(30.sp, styles.title4.lineHeight)
            assertEquals(base.bodyLarge.fontSize * 1.25f, styles.main.fontSize)
            assertEquals(base.bodySmall.fontSize * 1.25f, styles.body2.fontSize)
            assertEquals(base.labelSmall.fontSize * 1.25f, styles.footnote2.fontSize)
            // The weight preset overrides every Miuix slot, including the ones the conversion pins.
            assertEquals(List(14) { FontWeight.Bold }, styles.slots().map { it.fontWeight })
        }
    }

    @Test fun aSmallerSizePresetAndASemiBoldWeightAlsoReachTheNativeMiuixTitles() {
        val styles = resolveDesktopMiuixTextStyles(
            AppUiStyle.MIUIX, AppFontSizePreset.SMALLEST, AppFontWeightPreset.SEMI_BOLD)
        assertEquals(32.sp * 0.85f, styles.title1.fontSize)
        assertEquals(18.sp * 0.85f, styles.title4.fontSize)
        assertEquals(List(14) { FontWeight.SemiBold }, styles.slots().map { it.fontWeight })
    }
}
