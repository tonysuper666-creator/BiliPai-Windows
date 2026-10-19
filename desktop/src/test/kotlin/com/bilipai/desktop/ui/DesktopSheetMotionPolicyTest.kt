package com.bilipai.desktop.ui

import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.AppModalPresentation
import com.android.purebilibili.core.ui.BottomSheetHost
import com.android.purebilibili.core.ui.resolveAdaptiveBottomSheetMotionSpec
import com.android.purebilibili.core.ui.resolveAppModalLayoutSpec
import com.android.purebilibili.core.ui.resolveBottomSheetHost
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure checks of the v0.4.6 sheet policy functions behind the Windows AppSheetComponents: no window, GPU, account or
 * network. They are the logic cases of the upstream AdaptiveBottomSheetPolicyTest that do not read Android source
 * files or depend on theme token tables.
 */
class DesktopSheetMotionPolicyTest {
    @Test fun `reduced sheet motion uses brief consistent fades for both styles`() {
        for (style in AppUiStyle.entries) {
            val spec = resolveAdaptiveBottomSheetMotionSpec(style, reducedMotion = true)
            assertEquals(120, spec.scrimEnterDurationMillis, style.name)
            assertEquals(120, spec.contentEnterFadeDurationMillis, style.name)
            assertEquals(90, spec.scrimExitDurationMillis, style.name)
            assertEquals(90, spec.contentExitFadeDurationMillis, style.name)
        }
    }

    @Test fun `wide windows switch to a centered dialog whose width depends on the miuix glass mode`() {
        val phone = resolveAppModalLayoutSpec(windowWidthDp = 412, miuixNonGlass = true)
        assertEquals(AppModalPresentation.BottomSheet, phone.presentation)
        assertEquals(412, phone.maxWidthDp)
        assertEquals(1f, phone.maxHeightFraction)

        val edge = resolveAppModalLayoutSpec(windowWidthDp = 599)
        assertEquals(AppModalPresentation.BottomSheet, edge.presentation)

        val nonGlassTablet = resolveAppModalLayoutSpec(windowWidthDp = 840, miuixNonGlass = true)
        assertEquals(AppModalPresentation.CenteredDialog, nonGlassTablet.presentation)
        assertEquals(420, nonGlassTablet.maxWidthDp)
        assertEquals(0.86f, nonGlassTablet.maxHeightFraction)

        val glassTablet = resolveAppModalLayoutSpec(windowWidthDp = 840, miuixNonGlass = false)
        assertEquals(AppModalPresentation.CenteredDialog, glassTablet.presentation)
        assertEquals(640, glassTablet.maxWidthDp)

        assertEquals(640, resolveAppModalLayoutSpec(windowWidthDp = 600).maxWidthDp)
        assertEquals(640, resolveAppModalLayoutSpec(windowWidthDp = 1199).maxWidthDp)
        assertEquals(720, resolveAppModalLayoutSpec(windowWidthDp = 1200).maxWidthDp)
        assertEquals(720, resolveAppModalLayoutSpec(windowWidthDp = 3840).maxWidthDp)
    }

    @Test fun `host contract resolves miuix to the window host and material3 to the material host`() {
        assertEquals(BottomSheetHost.MIUIX_WINDOW, resolveBottomSheetHost(AppUiStyle.MIUIX))
        assertEquals(BottomSheetHost.MATERIAL3, resolveBottomSheetHost(AppUiStyle.MATERIAL3))
    }
}
