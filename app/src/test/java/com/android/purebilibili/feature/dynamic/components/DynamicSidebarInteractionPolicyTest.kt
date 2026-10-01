package com.android.purebilibili.feature.dynamic.components

import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.ui.TopChromeRenderMode
import com.android.purebilibili.core.util.HapticType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicSidebarInteractionPolicyTest {
    @Test
    fun progressiveChrome_recordsBeforeFirstFrame_thenSwitchesToProgressive() {
        val warmingUp = resolveDynamicSidebarChromePolicy(
            headerBlurEnabled = false, progressiveBlurEnabled = true,
            solidFadeEnabled = false, progressiveSupported = true, sourceReady = false,
        )
        assertTrue(warmingUp.recordProgressiveSource)
        assertEquals(TopChromeRenderMode.SOLID, warmingUp.renderMode)
        val ready = resolveDynamicSidebarChromePolicy(
            headerBlurEnabled = false, progressiveBlurEnabled = true,
            solidFadeEnabled = false, progressiveSupported = true, sourceReady = true,
        )
        assertTrue(ready.recordProgressiveSource)
        assertEquals(TopChromeRenderMode.PROGRESSIVE, ready.renderMode)
    }

    @Test
    fun solidFade_doesNotRequireBackdropOrEnableGaussianBlur() {
        val policy = resolveDynamicSidebarChromePolicy(
            headerBlurEnabled = false, progressiveBlurEnabled = false,
            solidFadeEnabled = true, progressiveSupported = false, sourceReady = false,
        )
        assertTrue(policy.useSolidFade)
        assertEquals(false, policy.recordProgressiveSource)
        assertEquals(TopChromeRenderMode.SOLID, policy.renderMode)
    }

    @Test
    fun unavailableProgressiveChrome_fallsBackToSolidInsteadOfGaussian() {
        val policy = resolveDynamicSidebarChromePolicy(
            headerBlurEnabled = false, progressiveBlurEnabled = true,
            solidFadeEnabled = false, progressiveSupported = false, sourceReady = true,
        )
        assertEquals(false, policy.recordProgressiveSource)
        assertEquals(false, policy.useSolidFade)
        assertEquals(TopChromeRenderMode.SOLID, policy.renderMode)
    }

    @Test
    fun gaussianChrome_isUsedOnlyWhenRequested() {
        val policy = resolveDynamicSidebarChromePolicy(
            headerBlurEnabled = true, progressiveBlurEnabled = true,
            solidFadeEnabled = true, progressiveSupported = true, sourceReady = true,
        )
        assertEquals(TopChromeRenderMode.HAZE, policy.renderMode)
        assertEquals(false, policy.recordProgressiveSource)
        assertEquals(false, policy.useSolidFade)
        val disabled = resolveDynamicSidebarChromePolicy(
            headerBlurEnabled = false, progressiveBlurEnabled = false,
            solidFadeEnabled = false, progressiveSupported = true, sourceReady = true,
        )
        assertEquals(TopChromeRenderMode.SOLID, disabled.renderMode)
        assertEquals(false, disabled.recordProgressiveSource)
    }


    @Test
    fun userAvatarClick_triggersLightHapticBeforeFilteringUser() {
        val events = mutableListOf<String>()

        performDynamicSidebarUserAvatarClick(
            haptic = { type ->
                events += "haptic:${type.name}"
            },
            onClick = {
                events += "filter_user"
            }
        )

        assertEquals(listOf("haptic:${HapticType.LIGHT.name}", "filter_user"), events)
    }

    @Test
    fun globalWallpaperProtectsDynamicSidebarContainer() {
        val color = resolveDynamicSidebarContainerColor(
            surfaceColor = Color.White,
            globalWallpaperVisible = true
        )

        assertTrue(color.alpha >= 0.73f)
        assertTrue(color.alpha < 1f)
    }

    @Test
    fun globalWallpaperProtectsDynamicSidebarReturnHeader() {
        val color = resolveDynamicSidebarReturnHeaderColor(
            surfaceColor = Color.White,
            backgroundAlpha = 0.4f,
            globalWallpaperVisible = true
        )

        assertTrue(color.alpha >= 0.73f)
        assertTrue(color.alpha < 1f)
    }
}
