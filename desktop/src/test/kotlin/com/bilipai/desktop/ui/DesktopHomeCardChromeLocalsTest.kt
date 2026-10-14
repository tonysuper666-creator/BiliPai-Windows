package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopHomeCardVisualSettings
import com.android.purebilibili.core.store.HomeDurationStyle
import com.android.purebilibili.core.store.HomeSettings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopHomeCardChromeLocalsTest {
    @Test fun defaultsMatchTheOffCompositionLocals() {
        val locals = resolveDesktopHomeCardChromeLocals(HomeSettings())
        assertFalse(locals.dynamicTintEnabled)
        assertFalse(locals.frostedGlassEnabled)
        assertFalse(locals.showUpBadges)
        assertFalse(locals.showUpAvatars)
        assertFalse(locals.showFullVideoCardContent)
        assertFalse(locals.longPressEnabled)
    }

    @Test fun eachHomeSettingMapsToTheAppNavigationLocal() {
        val locals = resolveDesktopHomeCardChromeLocals(
            HomeSettings(
                homeCardDynamicTintEnabled = true,
                showHomeUpAvatars = true,
                videoCardLongPressActionEnabled = true,
            ),
        )
        assertTrue(locals.dynamicTintEnabled)
        assertFalse(locals.frostedGlassEnabled)
        assertFalse(locals.showUpBadges)
        assertTrue(locals.showUpAvatars)
        assertFalse(locals.showFullVideoCardContent)
        assertTrue(locals.longPressEnabled)

        val titles = resolveDesktopHomeCardChromeLocals(
            HomeSettings(showFullVideoCardContent = true, showHomeUpBadges = true, homeCardFrostedGlassEnabled = true),
        )
        assertTrue(titles.showFullVideoCardContent)
        assertTrue(titles.showUpBadges)
        assertTrue(titles.frostedGlassEnabled)
        assertFalse(titles.dynamicTintEnabled)
        assertFalse(titles.longPressEnabled)
    }

    @Test fun discoveryVisualSettingsUseTheSameSixFlags() {
        val visual = DesktopHomeCardVisualSettings(
            cardAnimationEnabled = true,
            compactVideoStatsOnCover = true,
            showHomeUpBadges = true,
            showHomeUpAvatars = false,
            showHomePublishTime = false,
            showFullVideoCardContent = true,
            videoCardLongPressActionEnabled = true,
            homeCardDynamicTintEnabled = true,
            homeCardFrostedGlassEnabled = true,
            homeDurationStyle = HomeDurationStyle.HIDDEN,
            showOnlineCount = true,
        )
        assertEquals(
            DesktopHomeCardChromeLocals(
                dynamicTintEnabled = true,
                frostedGlassEnabled = true,
                showUpBadges = true,
                showUpAvatars = false,
                showFullVideoCardContent = true,
                longPressEnabled = true,
            ),
            resolveDesktopHomeCardChromeLocals(visual),
        )
    }

    @Test fun mountedRootAndDiscoveryCardProvideTheResolvedLocals() {
        val root = load("src/main/kotlin/com/bilipai/desktop/ui/DesktopReadyOriginalRootMount.kt")
        val discovery = load("src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalDiscoveryVideoCard.kt")
        assertTrue(root.contains("ProvideDesktopHomeCardChromeLocals(resolveDesktopHomeCardChromeLocals(currentHome))"))
        assertTrue(discovery.contains("ProvideDesktopHomeCardChromeLocals(resolveDesktopHomeCardChromeLocals(settings))"))
    }

    private fun load(relativePath: String): String =
        listOf(File(relativePath), File("desktop/$relativePath")).first(File::exists).readText()
}
